package com.avast.grpc.jsonbridge

import io.grpc.Status

sealed trait BridgeError extends Exception with Product with Serializable
object BridgeError {
  final case object GrpcMethodNotFound extends BridgeError

  sealed trait Narrow extends BridgeError
  final case class Json(t: Throwable) extends Narrow
  final case class Grpc(s: Status) extends Narrow
  final case class Unknown(t: Throwable) extends Narrow
}
