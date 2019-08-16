package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.ClientError
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatcher, Route}
import cats.data.NonEmptyList
import cats.effect.Effect
import cats.effect.implicits._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.avast.grpc.jsonbridge.{BridgeError, BridgeErrorResponse, GrpcJsonBridge}
import com.typesafe.scalalogging.LazyLogging
import io.grpc.Status.Code
import spray.json._

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object AkkaHttp extends SprayJsonSupport with DefaultJsonProtocol with LazyLogging {

  private implicit val grpcStatusJsonFormat: RootJsonFormat[BridgeErrorResponse] = jsonFormat3(BridgeErrorResponse.apply)

  private[akkahttp] final val JsonContentType: `Content-Type` = `Content-Type` {
    ContentType.WithMissingCharset(MediaType.applicationWithOpenCharset("json"))
  }

  def apply[F[_]: Effect](configuration: Configuration)(bridge: GrpcJsonBridge[F])(implicit ec: ExecutionContext): Route = {

    val pathPattern = configuration.pathPrefix
      .map {
        case NonEmptyList(head, tail) =>
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
          val headers = request.headers
          request.header[`Content-Type`] match {
            case Some(`JsonContentType`) =>
              entity(as[String]) { body =>
                val methodNameString = GrpcMethodName(serviceName, methodName)
                val headersString = mapHeaders(headers)
                val methodCall = bridge.invoke(methodNameString, body, headersString).toIO.unsafeToFuture()
                onComplete(methodCall) {
                  case Success(result) =>
                    result match {
                      case Right(resp) =>
                        logger.trace("Request successful: {}", resp.substring(0, 100))
                        respondWithHeader(JsonContentType) {
                          complete(resp)
                        }
                      case Left(er) =>
                        er match {
                          case BridgeError.GrpcMethodNotFound =>
                            val message = s"Method '${methodNameString.fullName}' not found"
                            logger.warn(message)
                            respondWithHeader(JsonContentType) {
                              complete(StatusCodes.NotFound, BridgeErrorResponse.fromMessage(message))
                            }
                          case er: BridgeError.RequestJsonParseError =>
                            val message = "Cannot parse JSON request body"
                            logger.warn(message, er.t)
                            respondWithHeader(JsonContentType) {
                              complete(StatusCodes.BadRequest, BridgeErrorResponse.fromException(message, er.t))
                            }
                          case er: BridgeError.RequestErrorGrpc =>
                            val message = "gRPC request error" + Option(er.s.getDescription).map(": " + _).getOrElse("")
                            logger.debug(message, er.s.getCause)
                            val (s, body) = mapStatus(er.s)
                            respondWithHeader(JsonContentType) {
                              complete(s, body)
                            }
                          case er: BridgeError.RequestError =>
                            val message = "Unknown request error"
                            logger.info(message, er.t)
                            respondWithHeader(JsonContentType) {
                              complete(StatusCodes.InternalServerError, BridgeErrorResponse.fromException(message, er.t))
                            }
                        }
                    }
                  case Failure(NonFatal(er)) =>
                    val message = "Unknown exception"
                    logger.info(message, er)
                    respondWithHeader(JsonContentType) {
                      complete(StatusCodes.InternalServerError, BridgeErrorResponse.fromException(message, er))
                    }
                }
              }
            case Some(c) =>
              val message = s"Content-Type must be '$JsonContentType', it is '$c'"
              logger.info(message)
              respondWithHeader(JsonContentType) {
                complete(StatusCodes.BadRequest, BridgeErrorResponse.fromMessage(message))
              }
            case None =>
              val message = s"Content-Type must be '$JsonContentType'"
              logger.info(message)
              respondWithHeader(JsonContentType) {
                complete(StatusCodes.BadRequest, BridgeErrorResponse.fromMessage(message))
              }
          }
        }
      }
    } ~ get {
      path(Segment) { serviceName =>
        NonEmptyList.fromList(bridge.methodsNames.filter(_.service == serviceName).toList) match {
          case None =>
            val message = s"Service '$serviceName' not found"
            logger.info(message)
            respondWithHeader(JsonContentType) {
              complete(StatusCodes.NotFound, BridgeErrorResponse.fromMessage(message))
            }
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
        (ClientError(499)("Client Closed Request", "The operation was cancelled, typically by the caller."), description)
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

case class Configuration private (pathPrefix: Option[NonEmptyList[String]])

object Configuration {
  val Default: Configuration = Configuration(
    pathPrefix = None
  )
}
