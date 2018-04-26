package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import io.grpc.BindableService
import io.grpc.Status.Code

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object AkkaHttp {
  def apply(bridges: GrpcJsonBridge[_ <: BindableService]*): Route = {
    val services = bridges.map(s => (s.serviceName, s): (String, GrpcJsonBridge[_])).toMap

    post {
      path(Segment / Segment) { (serviceName, methodName) =>
        services.get(serviceName) match {
          case Some(service) =>
            entity(as[String]) { json =>
              onComplete(service.invokeGrpcMethod(methodName, json)) {
                case Success(Right(r)) => complete(r)
                case Success(Left(status)) => complete(mapStatus(status))
                case Failure(NonFatal(_)) => complete(StatusCodes.InternalServerError)
              }
            }

          case None => complete(StatusCodes.NotFound)
        }
      }
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
