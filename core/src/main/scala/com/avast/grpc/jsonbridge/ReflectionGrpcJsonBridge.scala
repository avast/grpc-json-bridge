package com.avast.grpc.jsonbridge

import java.lang.reflect.Method

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
import scala.concurrent.ExecutionContext
import scala.language.{existentials, higherKinds}
import scala.util.control.NonFatal

class ReflectionGrpcJsonBridge[F[_]](services: ServerServiceDefinition*)(implicit ec: ExecutionContext, F: Async[F])
    extends GrpcJsonBridge[F]
    with AutoCloseable
    with StrictLogging {

  import com.avast.grpc.jsonbridge.ReflectionGrpcJsonBridge._

  def this(grpcServer: io.grpc.Server)(implicit ec: ExecutionContext, F: Async[F]) = this(grpcServer.getImmutableServices.asScala: _*)

  private val inProcessServiceName = s"HttpWrapper-${System.nanoTime()}"

  private val inProcessServer = {
    val b = InProcessServerBuilder.forName(inProcessServiceName).executor(ec.execute(_))
    services.foreach(b.addService)
    b.build().start()
  }

  private val inProcessChannel = InProcessChannelBuilder.forName(inProcessServiceName).executor(ec.execute(_)).build()

  override def close(): Unit = {
    inProcessChannel.shutdownNow()
    inProcessServer.shutdownNow()
    ()
  }

  // map from full method name to a function that invokes that method
  protected val handlersPerMethod: Map[String, HandlerFunc[F]] =
    inProcessServer.getImmutableServices.asScala
      .flatMap(createServiceHandlers(inProcessChannel)(_))
      .toMap

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

}

object ReflectionGrpcJsonBridge extends StrictLogging {

  // JSON body and headers to a response (fail status or JSON response)
  private type HandlerFunc[F[_]] = (String, Map[String, String]) => F[Either[Status, String]]

  private val parser: JsonFormat.Parser = JsonFormat.parser()

  private val printer: JsonFormat.Printer = {
    JsonFormat.printer().includingDefaultValueFields().omittingInsignificantWhitespace()
  }

  private def createFutureStubCtor(sd: ServiceDescriptor, inProcessChannel: Channel): () => AbstractStub[_] = {
    val serviceGeneratedClass = Class.forName {
      if (sd.getName.startsWith("grpc.")) "io." + sd.getName + "Grpc" else sd.getName + "Grpc"
    }
    val method = serviceGeneratedClass.getDeclaredMethod("newFutureStub", classOf[Channel])

    () =>
      method.invoke(null, inProcessChannel).asInstanceOf[AbstractStub[_]]
  }

  private def createServiceHandlers[F[_]](inProcessChannel: ManagedChannel)(
      ssd: ServerServiceDefinition)(implicit ec: ExecutionContext, F: Async[F]): Map[String, HandlerFunc[F]] = {
    val futureStubCtor = createFutureStubCtor(ssd.getServiceDescriptor, inProcessChannel)
    ssd.getMethods.asScala
      .filter(isSupportedMethod)
      .map(createHandler(futureStubCtor)(_))
      .toMap
  }

  private def createHandler[F[_]](futureStubCtor: () => AbstractStub[_])(
      method: ServerMethodDefinition[_, _])(implicit ec: ExecutionContext, F: Async[F]): (String, HandlerFunc[F]) = {
    val requestMessagePrototype = getRequestMessagePrototype(method)
    val javaMethod = futureStubCtor().getClass
      .getDeclaredMethod(getJavaMethodName(method), requestMessagePrototype.getClass)
    val execute = executeRequest[F](futureStubCtor, javaMethod) _

    val handler: HandlerFunc[F] = (json, headers) => {
      parseRequest(json, requestMessagePrototype) match {
        case Right(req) => execute(req, headers).map(resp => Right(printer.print(resp)))
        case Left(status) => F.pure(Left(status))
      }
    }
    (method.getMethodDescriptor.getFullMethodName, handler)
  }

  private def executeRequest[F[_]](futureStubCtor: () => AbstractStub[_], method: Method)(req: Message, headers: Map[String, String])(
      implicit ec: ExecutionContext,
      F: Async[F]): F[MessageOrBuilder] = {
    val metaData = {
      val md = new Metadata()
      headers.foreach { case (k, v) => md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v) }
      md
    }
    val stubWithHeaders = JavaGenericHelper.attachHeaders(futureStubCtor(), metaData)
    fromListenableFuture(F.delay {
      method.invoke(stubWithHeaders, req).asInstanceOf[ListenableFuture[MessageOrBuilder]]
    })
  }

  private def isSupportedMethod(d: ServerMethodDefinition[_, _]): Boolean = d.getMethodDescriptor.getType == MethodType.UNARY

  private def fromListenableFuture[F[_], A](flf: F[ListenableFuture[A]])(implicit ec: ExecutionContext, F: Async[F]): F[A] = flf.flatMap {
    lf =>
      F.async { cb =>
        Futures.addCallback(lf, new FutureCallback[A] {
          def onFailure(t: Throwable): Unit = cb(Left(t))
          def onSuccess(result: A): Unit = cb(Right(result))
        }, ec.execute(_))
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

  private def richStatus(status: Status, description: String, cause: Throwable): Status = {
    val d = Option(status.getDescription).getOrElse(description)
    val c = Option(status.getCause).getOrElse(cause)
    status.withDescription(d).withCause(c)
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

}
