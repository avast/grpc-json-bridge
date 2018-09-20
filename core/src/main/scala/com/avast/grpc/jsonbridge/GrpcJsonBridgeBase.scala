package com.avast.grpc.jsonbridge

import cats.effect.Async
import cats.syntax.all._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import com.typesafe.scalalogging.StrictLogging
import io.grpc._
import io.grpc.stub.MetadataUtils
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds
import scala.util.control.NonFatal

/** This is trait for internal usage. You should not use it directly.
  */
abstract class GrpcJsonBridgeBase[F[_], Stub <: io.grpc.stub.AbstractStub[Stub]](implicit protected val F: Async[F]) extends StrictLogging {

  protected def newFutureStub: Stub
  protected val parser: JsonFormat.Parser = JsonFormat.parser()
  protected val printer: JsonFormat.Printer = {
    JsonFormat.printer().includingDefaultValueFields().omittingInsignificantWhitespace()
  }

  // https://groups.google.com/forum/#!topic/grpc-io/1-KMubq1tuc
  protected def withNewClientStub[A](headers: Seq[GrpcHeader])(f: Stub => Future[A])(
      implicit ec: ExecutionContext): F[Either[Status, A]] = {
    val metadata = new Metadata()
    headers.foreach(h => metadata.put(Metadata.Key.of(h.name, Metadata.ASCII_STRING_MARSHALLER), h.value))

    val clientFutureStub = newFutureStub
      .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))

    try {
      Task
        .deferFuture(f(clientFutureStub))
        .to[F](F, Scheduler(ec))
        .map(Right(_): Either[Status, A])
        .recover {
          case e: StatusException if e.getStatus.getCode == Status.Code.UNKNOWN => Left(Status.INTERNAL)
          case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.UNKNOWN => Left(Status.INTERNAL)
          case e: StatusException => Left(e.getStatus)
          case e: StatusRuntimeException => Left(e.getStatus)
          case NonFatal(e) =>
            logger.debug("Error while executing the request", e)
            Left(Status.INTERNAL.withCause(e))
        }
    } catch {
      case e: StatusException if e.getStatus.getCode == Status.Code.UNKNOWN => F.pure(Left(Status.INTERNAL))
      case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.UNKNOWN => F.pure(Left(Status.INTERNAL))
      case NonFatal(e) =>
        logger.debug("Error while executing the request", e)
        F.pure(Left(Status.INTERNAL.withCause(e)))
    }

    // just abandon the stub...
  }

  protected def fromJson[Gpb <: Message](inst: Gpb, json: String): Either[Status, Gpb] = {
    try {
      val builder = inst.newBuilderForType()
      parser.merge(json, builder)
      Right {
        builder.build().asInstanceOf[Gpb]
      }
    } catch {
      case e: StatusRuntimeException => Left(e.getStatus)
      case NonFatal(e) =>
        logger.debug("Error while converting JSON to GPB", e)
        Left(Status.INVALID_ARGUMENT.withCause(e))
    }
  }

  protected def toJson(resp: Message): String = {
    printer.print(resp)
  }
}
