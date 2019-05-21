package com.avast.grpc.jsonbridge

import java.util.concurrent.Executor

import cats.effect.Async
import cats.syntax.all._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.google.common.util.concurrent._
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.{Message, MessageOrBuilder}
import com.typesafe.scalalogging.StrictLogging
import io.grpc.MethodDescriptor.{MethodType, PrototypeMarshaller}
import io.grpc._
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.stub.AbstractStub

import scala.collection.JavaConverters._
import scala.language.higherKinds
import scala.util.control.NonFatal

class ReflectionGrpcJsonBridge[F[_]](services: ServerServiceDefinition*)(implicit executor: Executor, F: Async[F])
    extends GrpcJsonBridge[F]
    with AutoCloseable
    with StrictLogging {

  def this(grpcServer: io.grpc.Server)(implicit executor: Executor, F: Async[F]) = this(grpcServer.getImmutableServices.asScala: _*)

  protected def isSupportedMethod(d: ServerMethodDefinition[_, _]): Boolean = d.getMethodDescriptor.getType == MethodType.UNARY

  protected val parser: JsonFormat.Parser = JsonFormat.parser()
  protected val printer: JsonFormat.Printer = {
    JsonFormat.printer().includingDefaultValueFields().omittingInsignificantWhitespace()
  }

  private val inProcessServiceName = s"HttpWrapper-${System.nanoTime()}"
  private val inProcessServer = {
    val b = InProcessServerBuilder.forName(inProcessServiceName).executor(executor)
    services.foreach(b.addService)
    b.build().start()
  }
  private val inProcessChannel = InProcessChannelBuilder.forName(inProcessServiceName).executor(executor).build()

  override def close(): Unit = {
    inProcessChannel.shutdownNow()
    inProcessServer.shutdownNow()
    ()
  }

  // JSON body and headers to a response (fail status or JSON response)
  protected type HandlerFunc = (String, Map[String, String]) => F[Either[Status, String]]

  // map from full method name to a function that invokes that method
  protected val handlersPerMethod: Map[String, HandlerFunc] =
    inProcessServer.getImmutableServices.asScala.flatMap { ssd =>
      ssd.getMethods.asScala
        .filter(isSupportedMethod)
        .map { method =>
          val requestMessagePrototype = getRequestMessagePrototype(method)
          val javaMethodName = getJavaMethodName(method)

          val execute = executeRequest(createNewFutureStubFunction(ssd), requestMessagePrototype, javaMethodName) _

          val handler: HandlerFunc = (json, headers) => {
            parseRequest(json, requestMessagePrototype) match {
              case Right(req) => execute(req, headers).map(resp => Right(printer.print(resp)))
              case Left(status) => F.pure(Left(status))
            }
          }

          (method.getMethodDescriptor.getFullMethodName, handler)
        }
    }.toMap

  private def executeRequest(createFutureStub: () => AbstractStub[_], requestMessagePrototype: Message, javaMethodName: String)(
      req: Message,
      headers: Map[String, String]): F[MessageOrBuilder] = {
    F.delay {
        val futureStub: AbstractStub[_] = createFutureStub()
        val method = futureStub.getClass.getDeclaredMethod(javaMethodName, requestMessagePrototype.getClass)

        val md = new Metadata()
        headers.foreach { case (k, v) => md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v) }

        (method, JavaGenericHelper.attachHeaders(futureStub, md))
      }
      .flatMap {
        case (method, stub) =>
          fromListenableFuture(F.delay {
            method.invoke(stub, req).asInstanceOf[ListenableFuture[MessageOrBuilder]]
          })
      }
  }

  private def getJavaMethodName(method: ServerMethodDefinition[_, _]): String = {
    val Seq(_, methodName) = method.getMethodDescriptor.getFullMethodName.split('/').toSeq
    methodName.substring(0, 1).toLowerCase + methodName.substring(1)
  }

  private def getRequestMessagePrototype(method: ServerMethodDefinition[_, _]): Message = {
    val requestMarshaller = method.getMethodDescriptor.getRequestMarshaller.asInstanceOf[PrototypeMarshaller[_]]
    requestMarshaller.getMessagePrototype.asInstanceOf[Message]
  }

  private def parseRequest(json: String, requestMessage: Message): Either[Status, Message] =
    try {
      val requestBuilder = requestMessage.newBuilderForType()
      parser.merge(json, requestBuilder)
      Right(requestBuilder.build())
    } catch {
      case NonFatal(ex) =>
        val message = "Error while converting JSON to GPB"
        logger.warn(message, ex)
        ex match {
          case e: StatusRuntimeException =>
            Left(richStatus(e.getStatus, message, e.getStatus.getCause))
          case _ =>
            Left(richStatus(Status.INVALID_ARGUMENT, message, ex))
        }
    }

  private def createNewFutureStubFunction(ssd: ServerServiceDefinition): () => AbstractStub[_] = {
    val method = getServiceGeneratedClass(ssd.getServiceDescriptor).getDeclaredMethod("newFutureStub", classOf[Channel])
    () =>
      method.invoke(null, inProcessChannel).asInstanceOf[AbstractStub[_]]
  }

  protected def getServiceGeneratedClass(sd: ServiceDescriptor): Class[_] = {
    val className = if (sd.getName.startsWith("grpc.")) "io." + sd.getName + "Grpc" else sd.getName + "Grpc"
    Class.forName(className)
  }

  override def invoke(methodName: GrpcMethodName, body: String, headers: Map[String, String]): F[Either[Status, String]] =
    handlersPerMethod.get(methodName.fullName) match {
      case None => F.pure(Left(Status.NOT_FOUND.withDescription(s"Method '$methodName' not found")))
      case Some(handler) =>
        handler(body, headers)
          .recover {
            case NonFatal(ex) =>
              val message = "Error while executing the request"
              logger.info(message, ex)
              ex match {
                case e: StatusException if e.getStatus.getCode == Status.Code.UNKNOWN =>
                  Left(richStatus(Status.INTERNAL, message, e.getStatus.getCause))
                case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.UNKNOWN =>
                  Left(richStatus(Status.INTERNAL, message, e.getStatus.getCause))
                case e: StatusException =>
                  Left(richStatus(e.getStatus, message, e.getStatus.getCause))
                case e: StatusRuntimeException =>
                  Left(richStatus(e.getStatus, message, e.getStatus.getCause))
                case _ =>
                  Left(richStatus(Status.INTERNAL, message, ex))
              }
          }
    }

  override val methodsNames: Seq[GrpcMethodName] = handlersPerMethod.keys.map(m => GrpcMethodName(m)).toSeq
  override val servicesNames: Seq[String] = methodsNames.map(_.service).distinct

  private def richStatus(status: Status, description: String, cause: Throwable): Status = {
    val d = Option(status.getDescription).getOrElse(description)
    val c = Option(status.getCause).getOrElse(cause)
    status.withDescription(d).withCause(c)
  }

  private def fromListenableFuture[T](flf: F[ListenableFuture[T]]): F[T] = F.flatMap(flf) { lf =>
    F.async { cb =>
      Futures.addCallback(lf, new FutureCallback[T] {
        def onFailure(t: Throwable): Unit = cb(Left(t))
        def onSuccess(result: T): Unit = cb(Right(result))
      }, executor)
    }
  }

}
