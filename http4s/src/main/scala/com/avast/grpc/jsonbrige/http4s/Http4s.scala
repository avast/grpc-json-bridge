package com.avast.grpc.jsonbrige.http4s

import cats.data.NonEmptyList
import cats.effect._
import cats.syntax.all._
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.typesafe.scalalogging.StrictLogging
import io.grpc.Status.Code
import io.grpc.{Status => GrpcStatus}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`Content-Type`, `WWW-Authenticate`}
import org.http4s.server.middleware.{CORS, CORSConfig}
import org.http4s.{Challenge, Header, Headers, HttpService, MediaType, Response}

import scala.language.higherKinds

object Http4s extends StrictLogging {

  def apply[F[_]: Sync](configuration: Configuration)(bridge: GrpcJsonBridge[F]): HttpService[F] = {
    implicit val h: Http4sDsl[F] = Http4sDsl[F]
    import h._

    val pathPrefix = configuration.pathPrefix
      .map(_.foldLeft[Path](Root)(_ / _))
      .getOrElse(Root)

    logger.info(s"Creating HTTP4S service proxying gRPC services: ${bridge.servicesNames.mkString("[", ", ", "]")}")

    val http4sService = HttpService[F] {
      case _ @GET -> `pathPrefix` / serviceName if serviceName.nonEmpty =>
        NonEmptyList.fromList(bridge.methodsNames.filter(_.service == serviceName).toList) match {
          case None =>
            val message = s"Attempt to GET non-existing service: $serviceName"
            logger.debug(message)
            NotFound(message)
          case Some(methods) =>
            Ok { methods.map(_.fullName).toList.mkString("\n") }
        }

      case _ @GET -> `pathPrefix` =>
        Ok { bridge.methodsNames.map(_.fullName).mkString("\n") }

      case request @ POST -> `pathPrefix` / serviceName / methodName =>
        val headers = request.headers
        headers.get(`Content-Type`.name) match {
          case Some(Header(_, contentTypeValue)) =>
            `Content-Type`.parse(contentTypeValue) match {
              case Right(`Content-Type`(MediaType.`application/json`, _)) =>
                request
                  .as[String]
                  .flatMap { body =>
                    bridge.invoke(GrpcMethodName(serviceName, methodName), body, mapHeaders(request.headers))
                  }
                  .flatMap {
                    case Right(resp) => Ok(resp, `Content-Type`(MediaType.`application/json`))
                    case Left(st) => mapStatus(st, configuration)
                  }
              case Right(c) =>
                val message = s"Content-Type must be '${MediaType.`application/json`}', it is '$c'"
                logger.debug(message)
                BadRequest(message)
              case Left(e) =>
                val message = s"Content-Type must be '${MediaType.`application/json`}', cannot parse '$contentTypeValue'"
                logger.debug(message, e)
                BadRequest(message)
            }

          case None =>
            val message = s"Content-Type must be '${MediaType.`application/json`}'"
            logger.debug(message)
            BadRequest(message)
        }
    }

    configuration.corsConfig match {
      case Some(c) => CORS(http4sService, c)
      case None => http4sService
    }
  }

  private def mapHeaders(headers: Headers): Map[String, String] = headers.toList.map(h => (h.name.value, h.value)).toMap

  private def mapStatus[F[_]: Sync](s: GrpcStatus, configuration: Configuration)(implicit h: Http4sDsl[F]): F[Response[F]] = {
    import h._

    val description = List(
      Option(s.getDescription),
      Option(s.getCause).flatMap(e => Option(e.getClass.getCanonicalName)),
      Option(s.getCause).flatMap(e => Option(e.getMessage))
    ).flatten.mkString(", ")

    s.getCode match {
      case Code.NOT_FOUND => NotFound(description)
      case Code.INTERNAL => InternalServerError(description)
      case Code.INVALID_ARGUMENT => BadRequest(description)
      case Code.FAILED_PRECONDITION => BadRequest(description)
      case Code.CANCELLED => RequestTimeout(description)
      case Code.UNAVAILABLE => ServiceUnavailable(description)
      case Code.DEADLINE_EXCEEDED => RequestTimeout(description)
      case Code.PERMISSION_DENIED => Forbidden(description)
      case Code.UNIMPLEMENTED => NotImplemented(description)
      case Code.RESOURCE_EXHAUSTED => TooManyRequests(description)
      case Code.ABORTED => InternalServerError(description)
      case Code.DATA_LOSS => InternalServerError(description)

      case Code.UNAUTHENTICATED => Unauthorized(configuration.wwwAuthenticate)

      case _ => InternalServerError(description)
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
