package com.avast.grpc.jsonbrige.http4s

import cats.data.NonEmptyList
import cats.effect.IO
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import io.grpc.Status.Code
import io.grpc.{BindableService, Status => GrpcStatus}
import org.http4s.dsl.impl.Root
import org.http4s.dsl.io._
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.{Challenge, HttpService, Response}

import scala.concurrent.ExecutionContext

object Http4s {
  def apply(configuration: Configuration)(bridges: GrpcJsonBridge[_ <: BindableService]*)(
      implicit ec: ExecutionContext): HttpService[IO] = {
    val services = bridges.map(s => (s.serviceName, s): (String, GrpcJsonBridge[_])).toMap

    val pathPrefix = configuration.pathPrefix
      .map(_.foldLeft[Path](Root)(_ / _))
      .getOrElse(Root)

    HttpService[IO] {
      case _ @GET -> `pathPrefix` / serviceName =>
        services.get(serviceName) match {
          case Some(service) =>
            Ok {
              service.serviceInfo.mkString("\n")
            }

          case None => NotFound()
        }

      case request @ POST -> `pathPrefix` / serviceName / methodName =>
        services.get(serviceName) match {
          case Some(service) =>
            request
              .as[String]
              .map(service.invokeGrpcMethod(methodName, _))
              .flatMap { f =>
                IO.fromFuture(IO(f))
              }
              .flatMap {
                case Right(resp) => Ok(resp)
                case Left(st) => mapStatus(st, configuration)
              }

          case None => NotFound()
        }
    }
  }

  private def mapStatus(s: GrpcStatus, configuration: Configuration): IO[Response[IO]] = s.getCode match {
    case Code.NOT_FOUND => NotFound()
    case Code.INTERNAL => InternalServerError()
    case Code.INVALID_ARGUMENT => BadRequest()
    case Code.FAILED_PRECONDITION => BadRequest()
    case Code.CANCELLED => RequestTimeout()
    case Code.UNAVAILABLE => ServiceUnavailable()
    case Code.DEADLINE_EXCEEDED => RequestTimeout()
    case Code.UNAUTHENTICATED => Unauthorized(configuration.wwwAuthenticate)
    case Code.PERMISSION_DENIED => Forbidden()
    case Code.UNIMPLEMENTED => NotImplemented()
    case Code.RESOURCE_EXHAUSTED => TooManyRequests()
    case Code.ABORTED => InternalServerError()
    case Code.DATA_LOSS => InternalServerError()

    case _ => InternalServerError()
  }
}

case class Configuration(pathPrefix: Option[NonEmptyList[String]], authChallenges: NonEmptyList[Challenge]) {
  private[http4s] val wwwAuthenticate: `WWW-Authenticate` = `WWW-Authenticate`(authChallenges)
}

object Configuration {
  val Default: Configuration = Configuration(
    pathPrefix = None,
    authChallenges = NonEmptyList.one(Challenge("Bearer", ""))
  )
}
