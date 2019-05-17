package com.avast.grpc.jsonbridge

import cats.effect.Async
import cats.syntax.all._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.{Message, MessageOrBuilder}
import com.typesafe.scalalogging.StrictLogging
import io.grpc.MethodDescriptor.{MethodType, PrototypeMarshaller}
import io.grpc._
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.stub.AbstractStub
import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.language.higherKinds
import scala.util.control.NonFatal

class ReflectionGrpcJsonBridge[F[_]](services: ServerServiceDefinition*)(implicit val scheduler: Scheduler, protected val F: Async[F])
    extends GrpcJsonBridge[F]
    with AutoCloseable
    with StrictLogging {

  def this(grpcServer: io.grpc.Server)(implicit scheduler: Scheduler, F: Async[F]) =
    this(grpcServer.getImmutableServices.asScala: _*)(scheduler, F)

  protected def supportedMethod(d: ServerMethodDefinition[_, _]): Boolean = d.getMethodDescriptor.getType == MethodType.UNARY
  protected val parser: JsonFormat.Parser = JsonFormat.parser()
  protected val printer: JsonFormat.Printer = {
    JsonFormat.printer().includingDefaultValueFields().omittingInsignificantWhitespace()
  }

  private val inProcessServiceName = s"HttpWrapper-${System.nanoTime()}"
  private val inProcessServer = {
    val b = InProcessServerBuilder.forName(inProcessServiceName).executor(scheduler)
    services.foreach(b.addService)
    b.build().start()
  }
  private val inProcessChannel = InProcessChannelBuilder.forName(inProcessServiceName).executor(scheduler).build()

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
      val newFutureStub = createNewFutureStubFunction(ssd)
      val methods = ssd.getMethods.asScala
        .filter(supportedMethod)
        .map { m =>
          val requestMarshaller = m.getMethodDescriptor.getRequestMarshaller.asInstanceOf[PrototypeMarshaller[_]]
          val requestMessagePrototype = requestMarshaller.getMessagePrototype.asInstanceOf[Message]
          val methodName = m.getMethodDescriptor.getFullMethodName.split('/')(1)
          val javaMethodName = methodName.substring(0, 1).toLowerCase + methodName.substring(1)
          val stubMethod = newFutureStub().getClass.getDeclaredMethod(javaMethodName, requestMessagePrototype.getClass)
          val handler: HandlerFunc = (json, headers) =>
            Task
              .deferFuture {
                val md = new Metadata()
                headers.foreach { case (k, v) => md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v) }
                val stubWithHeaders: Any = JavaGenericHelper.attachHeaders(newFutureStub(), md)
                val requestBuilder = requestMessagePrototype.newBuilderForType()
                val request: Either[Status, Message] = try {
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
                request match {
                  case Right(r) =>
                    val listenableFuture = stubMethod.invoke(stubWithHeaders, r).asInstanceOf[ListenableFuture[MessageOrBuilder]]
                    toFuture(listenableFuture).map(response => Right(printer.print(response)))
                  case Left(status) => Future(Left(status))
                }
              }
              .to[F]
          (m.getMethodDescriptor.getFullMethodName, handler)
        }
      methods
    }.toMap

  private def createNewFutureStubFunction(ssd: ServerServiceDefinition): () => AbstractStub[_] = {
    val method = getServiceGeneratedClass(ssd.getServiceDescriptor).getDeclaredMethod("newFutureStub", classOf[Channel])
    () =>
      JavaGenericHelper.asAbstractStub(method.invoke(null, inProcessChannel))
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

  private def toFuture[T](lf: ListenableFuture[T]): Future[T] = {
    val p = Promise[T]()
    Futures.addCallback(lf, new FutureCallback[T] {
      def onFailure(t: Throwable): Unit = p failure t
      def onSuccess(result: T): Unit = p success result
    }, scheduler)
    p.future
  }

}