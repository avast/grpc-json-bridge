package com.avast.grpc.jsonbridge

import java.lang.reflect.Method

import cats.effect.Async
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.avast.grpc.jsonbridge.ReflectionGrpcJsonBridge.{HandlerFunc, ServiceHandlers}
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.{InvalidProtocolBufferException, Message, MessageOrBuilder}
import com.typesafe.scalalogging.StrictLogging
import io.grpc.MethodDescriptor.PrototypeMarshaller
import io.grpc.stub.AbstractStub
import io.grpc.{
  Channel,
  ManagedChannel,
  Metadata,
  ServerMethodDefinition,
  ServerServiceDefinition,
  ServiceDescriptor,
  StatusException,
  StatusRuntimeException
}
import cats.implicits._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.language.{existentials, higherKinds}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

private[jsonbridge] object JavaServiceHandlers extends ServiceHandlers with StrictLogging {
  private val parser: JsonFormat.Parser = JsonFormat.parser()
  private val printer: JsonFormat.Printer = {
    JsonFormat.printer().includingDefaultValueFields().omittingInsignificantWhitespace()
  }

  def createServiceHandlers[F[+ _]](ec: ExecutionContext)(inProcessChannel: ManagedChannel)(ssd: ServerServiceDefinition)(
      implicit F: Async[F]): Option[Map[GrpcMethodName, HandlerFunc[F]]] = {
    createFutureStubCtor(ssd.getServiceDescriptor, inProcessChannel).map(
      futureStubCtor =>
        ssd.getMethods.asScala
          .filter(isSupportedMethod)
          .map(createHandler(ec)(futureStubCtor)(_))
          .toMap)
  }

  private def createFutureStubCtor(sd: ServiceDescriptor, inProcessChannel: Channel): Option[() => AbstractStub[_]] =
    Try {
      val serviceClassName = sd.getSchemaDescriptor.getClass.getName.split("\\$").head
      logger.debug(s"Creating instance of $serviceClassName")
      val method = Class.forName(serviceClassName).getDeclaredMethod("newFutureStub", classOf[Channel])
      () =>
        method.invoke(null, inProcessChannel).asInstanceOf[AbstractStub[_]]
    } match {
      case Failure(e) =>
        logger.debug(s"Cannot create service handlers based on Java gRPC implementation.", e)
        None
      case Success(v) => Some(v)
    }

  private def createHandler[F[+ _]](ec: ExecutionContext)(futureStubCtor: () => AbstractStub[_])(method: ServerMethodDefinition[_, _])(
      implicit F: Async[F]): (GrpcMethodName, HandlerFunc[F]) = {
    val requestMessagePrototype = getRequestMessagePrototype(method)
    val javaMethod = futureStubCtor().getClass
      .getDeclaredMethod(getJavaMethodName(method), requestMessagePrototype.getClass)
    val grpcMethodName = GrpcMethodName(method.getMethodDescriptor.getFullMethodName)
    val methodHandler = coreHandler(requestMessagePrototype, executeRequest[F](ec)(futureStubCtor, javaMethod))
    (grpcMethodName, methodHandler)
  }

  private def getRequestMessagePrototype(method: ServerMethodDefinition[_, _]): Message = {
    val requestMarshaller = method.getMethodDescriptor.getRequestMarshaller.asInstanceOf[PrototypeMarshaller[_]]
    requestMarshaller.getMessagePrototype.asInstanceOf[Message]
  }

  private def getJavaMethodName(method: ServerMethodDefinition[_, _]): String = {
    val Seq(_, methodName) = method.getMethodDescriptor.getFullMethodName.split('/').toSeq
    methodName.substring(0, 1).toLowerCase + methodName.substring(1)
  }

  private def coreHandler[F[+ _]](requestMessagePrototype: Message, execute: (Message, Map[String, String]) => F[MessageOrBuilder])(
      implicit F: Async[F]): HandlerFunc[F] = { (json, headers) =>
    {
      parseRequest(json, requestMessagePrototype) match {
        case Right(req) =>
          execute(req, headers)
            .map(printer.print)
            .map(Right(_): Either[BridgeError.Narrow, String])
            .recover {
              case e: StatusException =>
                Left(BridgeError.RequestErrorGrpc(e.getStatus))
              case e: StatusRuntimeException =>
                Left(BridgeError.RequestErrorGrpc(e.getStatus))
              case NonFatal(ex) =>
                Left(BridgeError.RequestError(ex))
            }
        case Left(ex) =>
          F.pure {
            Left(BridgeError.RequestJsonParseError(ex))
          }
      }
    }
  }

  private def executeRequest[F[+ _]](ec: ExecutionContext)(futureStubCtor: () => AbstractStub[_], javaMethod: Method)(
      req: Message,
      headers: Map[String, String])(implicit F: Async[F]): F[MessageOrBuilder] = {
    val metaData = {
      val md = new Metadata()
      headers.foreach { case (k, v) => md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v) }
      md
    }
    val stubWithHeaders = JavaGenericHelper.attachHeaders(futureStubCtor(), metaData)
    fromListenableFuture(ec)(F.delay {
      javaMethod.invoke(stubWithHeaders, req).asInstanceOf[ListenableFuture[MessageOrBuilder]]
    })
  }

  private def parseRequest(json: String, requestMessage: Message): Either[InvalidProtocolBufferException, Message] =
    Either.catchOnly[InvalidProtocolBufferException] {
      val requestBuilder = requestMessage.newBuilderForType()
      parser.merge(json, requestBuilder)
      requestBuilder.build()
    }

  private def fromListenableFuture[F[+ _], A](ec: ExecutionContext)(flf: F[ListenableFuture[A]])(implicit F: Async[F]): F[A] = flf.flatMap {
    lf =>
      F.async { cb =>
        Futures.addCallback(lf, new FutureCallback[A] {
          def onFailure(t: Throwable): Unit = cb(Left(t))
          def onSuccess(result: A): Unit = cb(Right(result))
        }, ec.execute(_))
      }
  }
}
