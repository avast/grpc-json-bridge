package com.avast.grpc.jsonbrige.http4s

import cats.data.NonEmptyList
import cats.effect.IO
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader
import com.avast.grpc.jsonbridge.{GrpcJsonBridge, ToTask}
import com.typesafe.scalalogging.StrictLogging
import io.grpc.Status.Code
import io.grpc.{BindableService, Status => GrpcStatus}
import monix.execution.Scheduler
import org.http4s.dsl.impl.Root
import org.http4s.dsl.io._
import org.http4s.headers.{`Content-Type`, `WWW-Authenticate`}
import org.http4s.server.middleware.{CORS, CORSConfig}
import org.http4s.{Challenge, Header, Headers, HttpService, MediaType, Response}

import scala.language.higherKinds

object Http4s extends StrictLogging {
  private val JsonContentType: String = MediaType.`application/json`.renderString

  def apply[F[_]: ToTask](configuration: Configuration)(bridges: GrpcJsonBridge[F, _ <: BindableService]*)(
      implicit sch: Scheduler): HttpService[IO] = {
    val services = bridges.map(s => (s.serviceName, s): (String, GrpcJsonBridge[F, _])).toMap

    val pathPrefix = configuration.pathPrefix
      .map(_.foldLeft[Path](Root)(_ / _))
      .getOrElse(Root)

    logger.info(s"Creating HTTP4S service proxying gRPC services: ${bridges.map(_.serviceName).mkString("[", ", ", "]")}")

    val http4sService = HttpService[IO] {
      case _ @ GET -> `pathPrefix` / serviceName =>
        services.get(serviceName) match {
          case Some(service) =>
            Ok {
              service.methodsNames.mkString("\n")
            }

          case None => NotFound()
        }

      case _ @ GET -> `pathPrefix` =>
        Ok { services.values.flatMap(s => s.methodsNames).mkString("\n") }

      case request @ POST -> `pathPrefix` / serviceName / methodName =>
        val headers = request.headers

        headers.get(`Content-Type`.name) match {
          case Some(Header(`Content-Type`.name, `JsonContentType`)) =>
            services.get(serviceName) match {
              case Some(service) =>
                request
                  .as[String]
                  .map(service.invokeGrpcMethod(methodName, _, mapHeaders(headers)))
                  .flatMap { f =>
                    val future = implicitly[ToTask[F]].apply(f).runAsync
                    IO.fromFuture(IO(future))
                  }
                  .flatMap {
                    case Right(resp) => Ok(resp, `Content-Type`(MediaType.`application/json`))
                    case Left(st) => mapStatus(st, configuration)
                  }

              case None => NotFound()
            }

          case _ => BadRequest()
        }
    }

    configuration.corsConfig match {
      case Some(c) => CORS(http4sService, c)
      case None => http4sService
    }
  }

  private def mapHeaders(headers: Headers): Seq[GrpcHeader] = {
    headers.map { h =>
      GrpcHeader(h.name.value, h.value)
    }.toSeq
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

case class Configuration(pathPrefix: Option[NonEmptyList[String]],
                         authChallenges: NonEmptyList[Challenge],
                         corsConfig: Option[CORSConfig]) {
  private[http4s] val wwwAuthenticate: `WWW-Authenticate` = `WWW-Authenticate`(authChallenges)
}

object Configuration {
  val Default: Configuration = Configuration(
    pathPrefix = None,
    authChallenges = NonEmptyList.one(Challenge("Bearer", "")),
    corsConfig = None
  )
}
