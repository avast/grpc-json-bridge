package com.avast.grpc.jsonbridge

import com.google.protobuf.InvalidProtocolBufferException
import io.grpc.Status

sealed trait BridgeError extends Exception with Product with Serializable
object BridgeError {
  final case object GrpcMethodNotFound extends BridgeError

  sealed trait Narrow extends BridgeError with Product with Serializable
  final case class RequestJsonParseError(t: InvalidProtocolBufferException) extends Narrow
  final case class RequestErrorGrpc(s: Status) extends Narrow
  final case class RequestError(t: Throwable) extends Narrow
}
