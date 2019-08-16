package com.avast.grpc.jsonbridge

import io.grpc.Status

final case class BridgeErrorResponse(description: Option[String], exception: Option[String], message: Option[String])

object BridgeErrorResponse {

  def fromGrpcStatus(s: Status): BridgeErrorResponse = BridgeErrorResponse(
    Option(s.getDescription),
    Option(s.getCause).flatMap(e => Option(e.getClass.getCanonicalName)),
    Option(s.getCause).flatMap(e => Option(e.getMessage))
  )

  def fromMessage(message: String): BridgeErrorResponse = {
    BridgeErrorResponse(Some(message), None, None)
  }

  def fromException(message: String, ex: Throwable): BridgeErrorResponse = {
    BridgeErrorResponse(Some(message), Some(ex.getClass.getCanonicalName), Option(ex.getMessage))
  }
}
