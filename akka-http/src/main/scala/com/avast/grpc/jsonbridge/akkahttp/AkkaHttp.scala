package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.ClientError
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatcher, Route, StandardRoute}
import cats.data.NonEmptyList
import cats.effect.Effect
import cats.effect.implicits._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.avast.grpc.jsonbridge.{GrpcJsonBridge, GrpcStatusJson}
import io.grpc.Status.Code
import spray.json._

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object AkkaHttp extends SprayJsonSupport with DefaultJsonProtocol {

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

    post {
      path(pathPattern) { (serviceName, methodName) =>
        extractRequest { req =>
          req.header[`Content-Type`] match {
            case Some(`JsonContentType`) =>
              entity(as[String]) { json =>
                val methodCall = bridge
                  .invoke(GrpcMethodName(serviceName, methodName), json, mapHeaders(req.headers))
                  .toIO
                  .unsafeToFuture()
                onComplete(methodCall) {
                  case Success(Right(r)) =>
                    respondWithHeader(JsonContentType) {
                      complete(r)
                    }
                  case Success(Left(status)) =>
                    mapStatus(complete(_, _))(status)
                  case Failure(NonFatal(_)) => complete(StatusCodes.InternalServerError)
                }
              }

            case _ =>
              complete(StatusCodes.BadRequest, s"Content-Type must be '$JsonContentType'")
          }
        }
      }
    } ~ get {
      path(Segment) { serviceName =>
        NonEmptyList.fromList(bridge.methodsNames.filter(_.service == serviceName).toList) match {
          case None =>
            complete(StatusCodes.NotFound, s"Service '$serviceName' not found")
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
  private def mapStatus(complete: (StatusCode, GrpcStatusJson) => StandardRoute)(s: io.grpc.Status): StandardRoute = {

    val description = GrpcStatusJson.fromGrpcStatus(s)

    s.getCode match {
      case Code.OK => complete(StatusCodes.OK, description)
      case Code.CANCELLED =>
        complete(ClientError(499)("Client Closed Request", "The operation was cancelled, typically by the caller."), description)
      case Code.UNKNOWN => complete(StatusCodes.InternalServerError, description)
      case Code.INVALID_ARGUMENT => complete(StatusCodes.BadRequest, description)
      case Code.DEADLINE_EXCEEDED => complete(StatusCodes.GatewayTimeout, description)
      case Code.NOT_FOUND => complete(StatusCodes.NotFound, description)
      case Code.ALREADY_EXISTS => complete(StatusCodes.Conflict, description)
      case Code.PERMISSION_DENIED => complete(StatusCodes.Forbidden, description)
      case Code.RESOURCE_EXHAUSTED => complete(StatusCodes.TooManyRequests, description)
      case Code.FAILED_PRECONDITION => complete(StatusCodes.BadRequest, description)
      case Code.ABORTED => complete(StatusCodes.Conflict, description)
      case Code.OUT_OF_RANGE => complete(StatusCodes.BadRequest, description)
      case Code.UNIMPLEMENTED => complete(StatusCodes.NotImplemented, description)
      case Code.INTERNAL => complete(StatusCodes.InternalServerError, description)
      case Code.UNAVAILABLE => complete(StatusCodes.ServiceUnavailable, description)
      case Code.DATA_LOSS => complete(StatusCodes.InternalServerError, description)
      case Code.UNAUTHENTICATED => complete(StatusCodes.Unauthorized, description)
    }
  }
}

case class Configuration private (pathPrefix: Option[NonEmptyList[String]])

object Configuration {
  val Default: Configuration = Configuration(
    pathPrefix = None
  )
}
