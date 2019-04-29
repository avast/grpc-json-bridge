package com.avast.grpc.jsonbrige.http4s

import cats.data.NonEmptyList
import cats.effect._
import cats.syntax.all._
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader
import com.typesafe.scalalogging.StrictLogging
import io.grpc.Status.Code
import io.grpc.{BindableService, Status => GrpcStatus}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`Content-Type`, `WWW-Authenticate`}
import org.http4s.server.middleware.{CORS, CORSConfig}
import org.http4s.{Challenge, Header, Headers, HttpRoutes, MediaType, Response}

import scala.language.higherKinds

object Http4s extends StrictLogging {

  def apply[F[_]: Sync](configuration: Configuration)(bridges: GrpcJsonBridge[F, _ <: BindableService]*): HttpRoutes[F] = {
    implicit val h: Http4sDsl[F] = Http4sDsl[F]
    import h._

    val bridgesMap = bridges.map(s => (s.serviceName, s): (String, GrpcJsonBridge[F, _])).toMap

    val pathPrefix = configuration.pathPrefix
      .map(_.foldLeft[Path](Root)(_ / _))
      .getOrElse(Root)

    logger.info(s"Creating HTTP4S service proxying gRPC services: ${bridges.map(_.serviceName).mkString("[", ", ", "]")}")

    val http4sService = HttpRoutes.of[F] {
      case _ @GET -> `pathPrefix` / serviceName if serviceName.nonEmpty =>
        bridgesMap.get(serviceName) match {
          case Some(service) =>
            Ok {
              service.methodsNames.mkString("\n")
            }

          case None =>
            val message = s"Attempt to GET non-existing service: $serviceName"
            logger.warn(message)
            NotFound(message)
        }

      case _ @GET -> `pathPrefix` =>
        Ok { bridgesMap.values.flatMap(s => s.methodsNames).mkString("\n") }

      case request @ POST -> `pathPrefix` / serviceName / methodName =>
        val headers = request.headers
        headers.get(`Content-Type`.name) match {
          case Some(Header(_, contentTypeValue)) =>
            `Content-Type`.parse(contentTypeValue) match {
              case Right(`Content-Type`(MediaType.application.json, _)) =>
                bridgesMap.get(serviceName) match {
                  case Some(service) =>
                    request
                      .as[String]
                      .flatMap(service.invokeGrpcMethod(methodName, _, mapHeaders(headers)))
                      .flatMap {
                        case Right(resp) => Ok(resp, `Content-Type`(MediaType.application.json))
                        case Left(st) => mapStatus(st, configuration)
                      }

                  case None =>
                    val message = s"Attempt to POST non-existing service: $serviceName"
                    logger.warn(message)
                    NotFound(message)
                }

              case Left(e) =>
                val message = s"Content-Type must be '${MediaType.application.json}', it's '$contentTypeValue'"
                logger.warn(message, e)
                BadRequest(message)
            }

          case None =>
            val message = s"Content-Type must be '${MediaType.application.json}'"
            logger.warn(message)
            BadRequest(message)
        }
    }

    configuration.corsConfig match {
      case Some(c) => CORS(http4sService, c)
      case None => http4sService
    }
  }

  private def mapHeaders(headers: Headers): Seq[GrpcHeader] = {
    headers.iterator.map { h =>
      GrpcHeader(h.name.value, h.value)
    }.toSeq
  }

  private def mapStatus[F[_]: Sync](s: GrpcStatus, configuration: Configuration)(implicit h: Http4sDsl[F]): F[Response[F]] = {
    import h._

    s.getCode match {
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
