package com.avast.grpc.jsonbridge.http4s

import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.syntax.all._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.avast.grpc.jsonbridge.{BridgeError, BridgeErrorResponse, GrpcJsonBridge}
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import io.grpc.Status.Code
import io.grpc.{Status => GrpcStatus}
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.EntityResponseGenerator
import org.http4s.headers.{`Content-Type`, `WWW-Authenticate`}
import org.http4s.server.middleware.{CORS, CORSConfig}

object Http4s extends LazyLogging {

  def apply[F[_]: Sync](configuration: Configuration)(bridge: GrpcJsonBridge[F]): HttpRoutes[F] = {
    implicit val h: Http4sDsl[F] = Http4sDsl[F]
    import h._

    val pathPrefix = configuration.pathPrefix
      .map(_.foldLeft[Path](Root)(_ / _))
      .getOrElse(Root)

    logger.info(s"Creating HTTP4S service proxying gRPC services: ${bridge.servicesNames.mkString("[", ", ", "]")}")

    val http4sService = HttpRoutes.of[F] {
      case _ @GET -> `pathPrefix` / serviceName if serviceName.nonEmpty =>
        NonEmptyList.fromList(bridge.methodsNames.filter(_.service == serviceName).toList) match {
          case None =>
            val message = s"Service '$serviceName' not found"
            logger.debug(message)
            NotFound(BridgeErrorResponse.fromMessage(message))
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
              case Right(`Content-Type`(MediaType.application.json, _)) =>
                request
                  .as[String]
                  .flatMap { body =>
                    val methodNameString = GrpcMethodName(serviceName, methodName)
                    val headersString = mapHeaders(headers)
                    bridge.invoke(methodNameString, body, headersString).flatMap {
                      case Right(resp) =>
                        logger.trace("Request successful: {}", resp.substring(0, 100))
                        Ok(resp, `Content-Type`(MediaType.application.json))
                      case Left(er) =>
                        er match {
                          case BridgeError.GrpcMethodNotFound =>
                            val message = s"Method '${methodNameString.fullName}' not found"
                            logger.debug(message)
                            NotFound(BridgeErrorResponse.fromMessage(message))
                          case er: BridgeError.Json =>
                            val message = "Wrong JSON"
                            logger.debug(message, er.t)
                            BadRequest(BridgeErrorResponse.fromException(message, er.t))
                          case er: BridgeError.Grpc =>
                            val message = "gRPC error" + Option(er.s.getDescription).map(": " + _).getOrElse("")
                            logger.trace(message, er.s.getCause)
                            mapStatus(er.s, configuration)
                          case er: BridgeError.Unknown =>
                            val message = "Unknown error"
                            logger.warn(message, er.t)
                            InternalServerError(BridgeErrorResponse.fromException(message, er.t))
                        }
                    }
                  }
              case Right(c) =>
                val message = s"Content-Type must be '${MediaType.application.json}', it is '$c'"
                logger.debug(message)
                BadRequest(BridgeErrorResponse.fromMessage(message))
              case Left(e) =>
                val message = s"Content-Type must be '${MediaType.application.json}', cannot parse '$contentTypeValue'"
                logger.debug(message, e)
                BadRequest(BridgeErrorResponse.fromException(message, e))
            }

          case None =>
            val message = s"Content-Type must be '${MediaType.application.json}'"
            logger.debug(message)
            BadRequest(BridgeErrorResponse.fromMessage(message))
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
    val ClientClosedRequest = Status(499, "Client Closed Request")
    final case class ClientClosedRequestOps(status: ClientClosedRequest.type) extends EntityResponseGenerator[F, F] {
      val liftG: F ~> F = h.liftG
    }

    val description = BridgeErrorResponse.fromGrpcStatus(s)

    // https://github.com/grpc/grpc/blob/master/doc/statuscodes.md
    s.getCode match {
      case Code.OK => Ok(description)
      case Code.CANCELLED => ClientClosedRequestOps(ClientClosedRequest)(description)
      case Code.UNKNOWN => InternalServerError(description)
      case Code.INVALID_ARGUMENT => BadRequest(description)
      case Code.DEADLINE_EXCEEDED => GatewayTimeout(description)
      case Code.NOT_FOUND => NotFound(description)
      case Code.ALREADY_EXISTS => Conflict(description)
      case Code.PERMISSION_DENIED => Forbidden(description)
      case Code.RESOURCE_EXHAUSTED => TooManyRequests(description)
      case Code.FAILED_PRECONDITION => BadRequest(description)
      case Code.ABORTED => Conflict(description)
      case Code.OUT_OF_RANGE => BadRequest(description)
      case Code.UNIMPLEMENTED => NotImplemented(description)
      case Code.INTERNAL => InternalServerError(description)
      case Code.UNAVAILABLE => ServiceUnavailable(description)
      case Code.DATA_LOSS => InternalServerError(description)
      case Code.UNAUTHENTICATED => Unauthorized(configuration.wwwAuthenticate)
    }
  }

  private implicit def grpcStatusJsonEntityEncoder[F[_]: Applicative]: EntityEncoder[F, BridgeErrorResponse] =
    jsonEncoderOf[F, BridgeErrorResponse]
}

final case class Configuration(
    pathPrefix: Option[NonEmptyList[String]],
    authChallenges: NonEmptyList[Challenge],
    corsConfig: Option[CORSConfig]
) {
  private[http4s] val wwwAuthenticate: `WWW-Authenticate` = `WWW-Authenticate`(authChallenges)
}

object Configuration {
  val Default: Configuration = Configuration(
    pathPrefix = None,
    authChallenges = NonEmptyList.one(Challenge("Bearer", "")),
    corsConfig = None
  )
}
