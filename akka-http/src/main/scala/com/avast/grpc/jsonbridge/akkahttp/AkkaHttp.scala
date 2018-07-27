package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatcher, Route}
import cats.data.NonEmptyList
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader
import com.avast.grpc.jsonbridge.{GrpcJsonBridge, ToTask}
import io.grpc.BindableService
import io.grpc.Status.Code
import monix.execution.Scheduler

import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object AkkaHttp {

  private[akkahttp] final val JsonContentType: `Content-Type` = `Content-Type` {
    ContentType.WithMissingCharset(MediaType.applicationWithOpenCharset("json"))
  }

  def apply[F[_]: ToTask](configuration: Configuration)(bridges: GrpcJsonBridge[F, _ <: BindableService]*)(
      implicit sch: Scheduler): Route = {
    val services = bridges.map(s => (s.serviceName, s): (String, GrpcJsonBridge[F, _])).toMap

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
              services.get(serviceName) match {
                case Some(service) =>
                  entity(as[String]) { json =>
                    val methodCall = implicitly[ToTask[F]].apply {
                      service.invokeGrpcMethod(methodName, json, mapHeaders(req.headers))
                    }.runAsync

                    onComplete(methodCall) {
                      case Success(Right(r)) =>
                        respondWithHeader(JsonContentType) {
                          complete(r)
                        }
                      case Success(Left(status)) => complete(mapStatus(status))
                      case Failure(NonFatal(_)) => complete(StatusCodes.InternalServerError)
                    }
                  }

                case None => complete(StatusCodes.NotFound)
              }

            case _ =>
              complete(StatusCodes.BadRequest)
          }
        }
      }
    } ~ get {
      path(Segment) { serviceName =>
        services.get(serviceName) match {
          case Some(service) =>
            complete(service.serviceInfo.mkString("\n"))

          case None => complete(StatusCodes.NotFound)
        }
      }
    }
  }

  private def mapHeaders(headers: Seq[HttpHeader]): Seq[GrpcHeader] = {
    headers.map { h =>
      GrpcHeader(h.name(), h.value())
    }
  }

  private def mapStatus(s: io.grpc.Status): StatusCode = s.getCode match {
    case Code.NOT_FOUND => StatusCodes.NotFound
    case Code.INTERNAL => StatusCodes.InternalServerError
    case Code.INVALID_ARGUMENT => StatusCodes.BadRequest
    case Code.FAILED_PRECONDITION => StatusCodes.BadRequest
    case Code.CANCELLED => StatusCodes.RequestTimeout
    case Code.UNAVAILABLE => StatusCodes.ServiceUnavailable
    case Code.DEADLINE_EXCEEDED => StatusCodes.RequestTimeout
    case Code.UNAUTHENTICATED => StatusCodes.Unauthorized
    case Code.PERMISSION_DENIED => StatusCodes.Forbidden
    case Code.UNIMPLEMENTED => StatusCodes.NotImplemented
    case Code.RESOURCE_EXHAUSTED => StatusCodes.TooManyRequests
    case Code.ABORTED => StatusCodes.InternalServerError
    case Code.DATA_LOSS => StatusCodes.InternalServerError

    case _ => StatusCodes.InternalServerError
  }
}

case class Configuration private (pathPrefix: Option[NonEmptyList[String]])

object Configuration {
  val Default: Configuration = Configuration(
    pathPrefix = None
  )
}
