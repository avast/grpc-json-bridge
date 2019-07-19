package com.avast.grpc.jsonbridge

import io.grpc.Status

final case class GrpcStatusJson(description: Option[String], exception: Option[String], message: Option[String])

object GrpcStatusJson {
  def fromGrpcStatus(s: Status): GrpcStatusJson = GrpcStatusJson(
    Option(s.getDescription),
    Option(s.getCause).flatMap(e => Option(e.getClass.getCanonicalName)),
    Option(s.getCause).flatMap(e => Option(e.getMessage))
  )
}
