package com.avast.grpc.jsonbridge

import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader
import io.grpc.BindableService

import scala.concurrent.Future

trait GrpcJsonBridge[Service <: BindableService] {

  /** Invokes method with given name, if it exists.
    *
    * @param name Name of the method.
    * @param json Method input (JSON string).
    * @return None if such method does not exist, Some(Future) otherwise.
    */
  def invokeGrpcMethod(name: String, json: String, headers: Seq[GrpcHeader] = Seq.empty): Option[Future[String]]

  /** Returns sequence of method names supported by this `GrpcJsonBridge`.
    */
  def serviceInfo: Seq[String]
}

object GrpcJsonBridge {

  case class GrpcHeader(name: String, value: String)

}
