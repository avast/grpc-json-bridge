package com.avast.grpc.jsonbridge

import java.lang.reflect.Method

import cats.effect._
import cats.implicits._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.{Message, MessageOrBuilder}
import com.typesafe.scalalogging.StrictLogging
import io.grpc.MethodDescriptor.{MethodType, PrototypeMarshaller}
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc._
import io.grpc.stub.AbstractStub

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.language.existentials
import scala.language.higherKinds
import scala.util.control.NonFatal

object ReflectionGrpcJsonBridge extends StrictLogging {

  // JSON body and headers to a response (fail status or JSON response)
  type HandlerFunc[F[_]] = (String, Map[String, String]) => F[Either[Status, String]]

  private val parser: JsonFormat.Parser = JsonFormat.parser()

  private val printer: JsonFormat.Printer = {
    JsonFormat.printer().includingDefaultValueFields().omittingInsignificantWhitespace()
  }

  def createFromServer[F[_]](ec: ExecutionContext)(grpcServer: io.grpc.Server)(implicit F: Async[F]): Resource[F, GrpcJsonBridge[F]] = {
    createFromServices(ec)(grpcServer.getImmutableServices.asScala: _*)
  }

  def createFromServices[F[_]](ec: ExecutionContext)(services: ServerServiceDefinition*)(
      implicit F: Async[F]): Resource[F, GrpcJsonBridge[F]] = {
    for {
      inProcessServiceName <- Resource.liftF(F.delay { s"ReflectionGrpcJsonBridge-${System.nanoTime()}" })
      inProcessServer <- createInProcessServer(ec)(inProcessServiceName, services)
      inProcessChannel <- createInProcessChannel(ec)(inProcessServiceName)
      handlersPerMethod = inProcessServer.getImmutableServices.asScala
        .flatMap(createServiceHandlers(ec)(inProcessChannel)(_))
        .toMap
      bridge = createFromHandlers(handlersPerMethod)
    } yield bridge
  }

  def createFromHandlers[F[_]](handlersPerMethod: Map[String, HandlerFunc[F]])(implicit F: Async[F]): GrpcJsonBridge[F] = {
    new GrpcJsonBridge[F] {
      override def invoke(methodName: GrpcJsonBridge.GrpcMethodName,
                          body: String,
                          headers: Map[String, String]): F[Either[Status, String]] = handlersPerMethod.get(methodName.fullName) match {
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

      override val methodsNames: Seq[GrpcJsonBridge.GrpcMethodName] = handlersPerMethod.keys.map(m => GrpcMethodName(m)).toSeq
      override val servicesNames: Seq[String] = methodsNames.map(_.service).distinct
    }
  }

  private def createInProcessServer[F[_]](ec: ExecutionContext)(inProcessServiceName: String, services: Seq[ServerServiceDefinition])(
      implicit F: Sync[F]): Resource[F, Server] =
    Resource.make {
      F.delay {
        val b = InProcessServerBuilder.forName(inProcessServiceName).executor(ec.execute(_))
        services.foreach(b.addService)
        b.build().start()
      }
    } { s =>
      F.delay { s.shutdown().awaitTermination() }
    }

  private def createInProcessChannel[F[_]](ec: ExecutionContext)(inProcessServiceName: String)(
      implicit F: Sync[F]): Resource[F, ManagedChannel] =
    Resource.make {
      F.delay {
        InProcessChannelBuilder.forName(inProcessServiceName).executor(ec.execute(_)).build()
      }
    } { c =>
      F.delay { c.shutdown() }
    }

  private def createFutureStubCtor(sd: ServiceDescriptor, inProcessChannel: Channel): () => AbstractStub[_] = {
    val serviceClassName = if (sd.getName.startsWith("grpc.")) {
      "io." + sd.getName + "Grpc"
    } else {
      sd.getSchemaDescriptor.getClass.getName.split("\\$").head
    }

    logger.debug(s"Creating instance of $serviceClassName")

    val serviceGeneratedClass = Class.forName(serviceClassName)

    val method = serviceGeneratedClass.getDeclaredMethod("newFutureStub", classOf[Channel])

    () =>
      method.invoke(null, inProcessChannel).asInstanceOf[AbstractStub[_]]
  }

  private def createServiceHandlers[F[_]](ec: ExecutionContext)(inProcessChannel: ManagedChannel)(ssd: ServerServiceDefinition)(
      implicit F: Async[F]): Map[String, HandlerFunc[F]] = {
    val futureStubCtor = createFutureStubCtor(ssd.getServiceDescriptor, inProcessChannel)
    ssd.getMethods.asScala
      .filter(isSupportedMethod)
      .map(createHandler(ec)(futureStubCtor)(_))
      .toMap
  }

  private def createHandler[F[_]](ec: ExecutionContext)(futureStubCtor: () => AbstractStub[_])(method: ServerMethodDefinition[_, _])(
      implicit F: Async[F]): (String, HandlerFunc[F]) = {
    val requestMessagePrototype = getRequestMessagePrototype(method)
    val javaMethod = futureStubCtor().getClass
      .getDeclaredMethod(getJavaMethodName(method), requestMessagePrototype.getClass)
    val execute = executeRequest[F](ec)(futureStubCtor, javaMethod) _

    val handler: HandlerFunc[F] = (json, headers) => {
      parseRequest(json, requestMessagePrototype) match {
        case Right(req) => execute(req, headers).map(resp => Right(printer.print(resp)))
        case Left(status) => F.pure(Left(status))
      }
    }
    (method.getMethodDescriptor.getFullMethodName, handler)
  }

  private def executeRequest[F[_]](ec: ExecutionContext)(futureStubCtor: () => AbstractStub[_], method: Method)(
      req: Message,
      headers: Map[String, String])(implicit F: Async[F]): F[MessageOrBuilder] = {
    val metaData = {
      val md = new Metadata()
      headers.foreach { case (k, v) => md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v) }
      md
    }
    val stubWithHeaders = JavaGenericHelper.attachHeaders(futureStubCtor(), metaData)
    fromListenableFuture(ec)(F.delay {
      method.invoke(stubWithHeaders, req).asInstanceOf[ListenableFuture[MessageOrBuilder]]
    })
  }

  private def isSupportedMethod(d: ServerMethodDefinition[_, _]): Boolean = d.getMethodDescriptor.getType == MethodType.UNARY

  private def fromListenableFuture[F[_], A](ec: ExecutionContext)(flf: F[ListenableFuture[A]])(implicit F: Async[F]): F[A] = flf.flatMap {
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
