package com.avast.grpc.jsonbridge

import io.grpc.Status

sealed trait BridgeError extends Exception with Product with Serializable
object BridgeError {
  final case object GrpcMethodNotFound extends BridgeError

  sealed trait Narrow extends BridgeError
  final case class RequestJsonParseError(t: Throwable) extends Narrow
  final case class RequestErrorGrpc(s: Status) extends Narrow
  final case class RequestError(t: Throwable) extends Narrow
}
