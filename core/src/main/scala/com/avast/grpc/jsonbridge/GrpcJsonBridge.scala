package com.avast.grpc.jsonbridge

import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader
import io.grpc.BindableService

import scala.concurrent.Future

trait GrpcJsonBridge[Service <: BindableService] {
  def invokeGrpcMethod(name: String, json: String, headers: Seq[GrpcHeader] = Seq.empty): Option[Future[String]]

  def serviceInfo: Seq[String]
}

object GrpcJsonBridge {

  case class GrpcHeader(name: String, value: String)

}
