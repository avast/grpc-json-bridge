package com.avast.grpc.jsonbridge

import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader
import io.grpc.{BindableService, Status}

import scala.language.higherKinds

trait GrpcJsonBridge[F[_], Service <: BindableService] extends AutoCloseable {

  /** Invokes method with given name, if it exists. The method should never return a failed Future.
    *
    * @param name Name of the method.
    * @param json Method input (JSON string).
    * @return Left if there was some error, Right otherwise.
    */
  def invokeGrpcMethod(name: String, json: => String, headers: => Seq[GrpcHeader] = Seq.empty): F[Either[Status, String]]

  /** Returns sequence of method names supported by this `GrpcJsonBridge`.
    */
  def serviceInfo: Seq[String]

  /** Returns name of the service (FQN).
    */
  def serviceName: String
}

object GrpcJsonBridge {

  case class GrpcHeader(name: String, value: String)

}
