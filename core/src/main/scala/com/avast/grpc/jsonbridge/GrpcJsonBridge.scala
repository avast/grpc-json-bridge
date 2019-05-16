package com.avast.grpc.jsonbridge

import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import io.grpc.Status

import scala.language.higherKinds

trait GrpcJsonBridge[F[_]] {
  def invoke(fullMethodName: String, body: String, headers: Map[String, String]): F[Either[Status, String]]
  def methodsNames: Seq[GrpcMethodName]
  def servicesNames: Seq[String]
}

object GrpcJsonBridge {
  case class GrpcMethodName(service: String, method: String) {
    val fullName: String = service + "/" + method
  }
  object GrpcMethodName {
    def apply(fullMethodName: String): GrpcMethodName = {
      val s = fullMethodName.split('/')
      GrpcMethodName(s(0), s(1))
    }
  }
}
