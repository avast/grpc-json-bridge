package com.avast.grpc.jsonbridge

import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName

import scala.language.higherKinds

trait GrpcJsonBridge[F[_]] {
  def invoke(methodName: GrpcMethodName, body: String, headers: Map[String, String]): F[Either[BridgeError, String]]
  def methodHandlers: Map[GrpcMethodName, (String, Map[String, String]) => F[Either[BridgeError.Narrow, String]]]
  def methodsNames: Seq[GrpcMethodName]
  def servicesNames: Seq[String]
}

object GrpcJsonBridge {
  /*
   * Represents gRPC method name - it consists of service name (it includes package) and method name.
   */
  case class GrpcMethodName(service: String, method: String) {
    val fullName: String = service + "/" + method
  }
  object GrpcMethodName {
    def apply(fullMethodName: String): GrpcMethodName = {
      val Seq(s, m) = fullMethodName.split('/').toSeq
      GrpcMethodName(s, m)
    }
  }
}
