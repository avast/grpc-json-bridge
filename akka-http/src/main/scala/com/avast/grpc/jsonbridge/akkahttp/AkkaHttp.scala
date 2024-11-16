package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller, ToResponseMarshallable}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatcher, Route}
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.avast.grpc.jsonbridge.{BridgeError, BridgeErrorResponse, GrpcJsonBridge}
import com.typesafe.scalalogging.LazyLogging
import io.grpc.Status.Code
import spray.json._

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object AkkaHttp extends SprayJsonSupport with DefaultJsonProtocol with LazyLogging {

  private implicit val grpcStatusJsonFormat: RootJsonFormat[BridgeErrorResponse] = jsonFormat3(BridgeErrorResponse.apply)

  private val jsonStringMarshaller: ToEntityMarshaller[String] =
    Marshaller.stringMarshaller(MediaTypes.`application/json`)

  def apply[F[_]: Sync: LiftToFuture](configuration: Configuration)(bridge: GrpcJsonBridge[F]): Route = {

    val pathPattern = configuration.pathPrefix
      .map { case NonEmptyList(head, tail) =>
        val rest = if (tail.nonEmpty) {
          tail.foldLeft[PathMatcher[Unit]](Neutral)(_ / _)
        } else Neutral

        head ~ rest
      }
      .map(_ / Segment / Segment)
      .getOrElse(Segment / Segment)

    logger.info(s"Creating Akka HTTP service proxying gRPC services: ${bridge.servicesNames.mkString("[", ", ", "]")}")

    post {
      path(pathPattern) { (serviceName, methodName) =>
        extractRequest { request =>
          request.header[`Content-Type`] match {
            case Some(ct) if ct.contentType.mediaType == MediaTypes.`application/json` =>
              entity(as[String]) { body =>
                val methodNameString = GrpcMethodName(serviceName, methodName)
                val headersString = mapHeaders(request.headers)
                val methodCall = LiftToFuture[F].liftF {
                  bridge
                    .invoke(methodNameString, body, headersString)
                    .flatMap(Sync[F].fromEither)
                }

                onComplete(methodCall) {
                  case Success(resp) =>
                    logger.trace("Request successful: {}", resp.substring(0, 100))

                    complete(ToResponseMarshallable(resp)(jsonStringMarshaller))
                  case Failure(BridgeError.GrpcMethodNotFound) =>
                    val message = s"Method '${methodNameString.fullName}' not found"
                    logger.debug(message)

                    complete(StatusCodes.NotFound, BridgeErrorResponse.fromMessage(message))
                  case Failure(er: BridgeError.Json) =>
                    val message = "Wrong JSON"
                    logger.debug(message, er.t)

                    complete(StatusCodes.BadRequest, BridgeErrorResponse.fromException(message, er.t))
                  case Failure(er: BridgeError.Grpc) =>
                    val message = "gRPC error" + Option(er.s.getDescription).map(": " + _).getOrElse("")
                    logger.trace(message, er.s.getCause)
                    val (s, body) = mapStatus(er.s)

                    complete(s, body)
                  case Failure(er: BridgeError.Unknown) =>
                    val message = "Unknown error"
                    logger.warn(message, er.t)

                    complete(StatusCodes.InternalServerError, BridgeErrorResponse.fromException(message, er.t))
                  case Failure(NonFatal(ex)) =>
                    val message = "Unknown exception"
                    logger.debug(message, ex)

                    complete(StatusCodes.InternalServerError, BridgeErrorResponse.fromException(message, ex))
                  case Failure(e) => throw e // scalafix:ok
                }
              }
            case Some(c) =>
              val message = s"Content-Type must be 'application/json', it is '$c'"
              logger.debug(message)

              complete(StatusCodes.BadRequest, BridgeErrorResponse.fromMessage(message))
            case None =>
              val message = "Content-Type must be 'application/json'"
              logger.debug(message)

              complete(StatusCodes.BadRequest, BridgeErrorResponse.fromMessage(message))
          }
        }
      }
    } ~ get {
      path(Segment) { serviceName =>
        NonEmptyList.fromList(bridge.methodsNames.filter(_.service == serviceName).toList) match {
          case None =>
            val message = s"Service '$serviceName' not found"
            logger.debug(message)

            complete(StatusCodes.NotFound, BridgeErrorResponse.fromMessage(message))
          case Some(methods) =>
            complete(methods.map(_.fullName).toList.mkString("\n"))
        }
      }
    } ~ get {
      path(PathEnd) {
        complete(bridge.methodsNames.map(_.fullName).mkString("\n"))
      }
    }
  }

  private def mapHeaders(headers: Seq[HttpHeader]): Map[String, String] = headers.toList.map(h => (h.name(), h.value())).toMap

  // https://github.com/grpc/grpc/blob/master/doc/statuscodes.md
  private def mapStatus(s: io.grpc.Status): (StatusCode, BridgeErrorResponse) = {

    val description = BridgeErrorResponse.fromGrpcStatus(s)

    s.getCode match {
      case Code.OK => (StatusCodes.OK, description)
      case Code.CANCELLED =>
        (StatusCodes.custom(499, "Client Closed Request", "The operation was cancelled, typically by the caller."), description)
      case Code.UNKNOWN => (StatusCodes.InternalServerError, description)
      case Code.INVALID_ARGUMENT => (StatusCodes.BadRequest, description)
      case Code.DEADLINE_EXCEEDED => (StatusCodes.GatewayTimeout, description)
      case Code.NOT_FOUND => (StatusCodes.NotFound, description)
      case Code.ALREADY_EXISTS => (StatusCodes.Conflict, description)
      case Code.PERMISSION_DENIED => (StatusCodes.Forbidden, description)
      case Code.RESOURCE_EXHAUSTED => (StatusCodes.TooManyRequests, description)
      case Code.FAILED_PRECONDITION => (StatusCodes.BadRequest, description)
      case Code.ABORTED => (StatusCodes.Conflict, description)
      case Code.OUT_OF_RANGE => (StatusCodes.BadRequest, description)
      case Code.UNIMPLEMENTED => (StatusCodes.NotImplemented, description)
      case Code.INTERNAL => (StatusCodes.InternalServerError, description)
      case Code.UNAVAILABLE => (StatusCodes.ServiceUnavailable, description)
      case Code.DATA_LOSS => (StatusCodes.InternalServerError, description)
      case Code.UNAUTHENTICATED => (StatusCodes.Unauthorized, description)
    }
  }
}

final case class Configuration(pathPrefix: Option[NonEmptyList[String]])

object Configuration {
  val Default: Configuration = Configuration(
    pathPrefix = None
  )
}
