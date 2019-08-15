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
import com.avast.grpc.jsonbridge.{GrpcJsonBridge, GrpcStatusJson}
import com.typesafe.scalalogging.StrictLogging
import io.grpc.Status.Code
import spray.json._

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object AkkaHttp extends SprayJsonSupport with DefaultJsonProtocol with StrictLogging {

  private implicit val grpcStatusJsonFormat: RootJsonFormat[GrpcStatusJson] = jsonFormat3(GrpcStatusJson.apply)

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
                val methodName0 = GrpcMethodName(serviceName, methodName)
                bridge.methodHandlers.get(methodName0) match {
                  case Some(handler) =>
                    val headers0 = mapHeaders(headers)
                    val methodCall = handler(body, headers0).toIO.unsafeToFuture()
                    onComplete(methodCall) {
                      case Success(Right(r)) =>
                        respondWithHeader(JsonContentType) {
                          complete(r)
                        }
                      case Success(Left(status)) =>
                        val (s, body) = mapStatus(status)
                        complete(s, body)
                      case Failure(NonFatal(_)) => complete(StatusCodes.InternalServerError)
                    }
                  case None =>
                    val message = s"Method '${methodName0.fullName}' not found"
                    logger.info(message)
                    complete(StatusCodes.NotFound, message)
                }
              }
            case Some(c) =>
              val message = s"Content-Type must be '$JsonContentType', it is '$c'"
              logger.info(message)
              complete(StatusCodes.BadRequest, message)
            case None =>
              val message = s"Content-Type must be '$JsonContentType'"
              logger.info(message)
              complete(StatusCodes.BadRequest, message)
          }
        }
      }
    } ~ get {
      path(Segment) { serviceName =>
        NonEmptyList.fromList(bridge.methodsNames.filter(_.service == serviceName).toList) match {
          case None =>
            val message = s"Service '$serviceName' not found"
            logger.info(message)
            complete(StatusCodes.NotFound, message)
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
  private def mapStatus(s: io.grpc.Status): (StatusCode, GrpcStatusJson) = {

    val description = GrpcStatusJson.fromGrpcStatus(s)

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
