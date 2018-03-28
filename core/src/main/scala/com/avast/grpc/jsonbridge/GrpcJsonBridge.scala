package com.avast.grpc.jsonbridge

import io.grpc.BindableService

import scala.concurrent.Future

trait GrpcJsonBridge[Service <: BindableService] {
  def invokeGrpcMethod(name: String, json: String, headers: Seq[GrpcHeader] = Seq.empty): Option[Future[String]]
}

case class GrpcHeader(name: String, value: String)
