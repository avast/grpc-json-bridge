package com.avast.grpc.jsonbridge

import cats.effect._
import cats.syntax.all._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.avast.grpc.jsonbridge.ReflectionGrpcJsonBridge.{HandlerFunc, ServiceHandlers}
import com.typesafe.scalalogging.StrictLogging
import io.grpc.MethodDescriptor.MethodType
import io.grpc._
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}

import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object ReflectionGrpcJsonBridge extends ReflectionGrpcJsonBridge(JavaServiceHandlers) {
  // JSON body and headers to a response (fail status or JSON response)
  type HandlerFunc[F[_]] = (String, Map[String, String]) => F[Either[BridgeError.Narrow, String]]

  trait ServiceHandlers {
    def createServiceHandlers[F[_]](ec: ExecutionContext)(inProcessChannel: ManagedChannel)(ssd: ServerServiceDefinition)(
        implicit F: Async[F]): Map[GrpcMethodName, HandlerFunc[F]]
  }

  def isSupportedMethod(d: ServerMethodDefinition[_, _]): Boolean = d.getMethodDescriptor.getType == MethodType.UNARY
}

private[jsonbridge] class ReflectionGrpcJsonBridge(serviceHandlers: ServiceHandlers) extends StrictLogging {

  def createFromServer[F[_]](ec: ExecutionContext)(grpcServer: io.grpc.Server)(implicit F: Async[F]): Resource[F, GrpcJsonBridge[F]] = {
    createFromServices(ec)(grpcServer.getImmutableServices.asScala.toList: _*)
  }

  def createFromServices[F[_]](ec: ExecutionContext)(services: ServerServiceDefinition*)(
      implicit F: Async[F]): Resource[F, GrpcJsonBridge[F]] = {
    for {
      inProcessServiceName <- Resource.liftF(F.delay { s"ReflectionGrpcJsonBridge-${System.nanoTime()}" })
      inProcessServer <- createInProcessServer(ec)(inProcessServiceName, services)
      inProcessChannel <- createInProcessChannel(ec)(inProcessServiceName)
      handlersPerMethod = inProcessServer.getImmutableServices.asScala
        .flatMap(serviceHandlers.createServiceHandlers(ec)(inProcessChannel)(_))
        .toMap
      bridge = createFromHandlers(handlersPerMethod)
    } yield bridge
  }

  def createFromHandlers[F[_]](handlersPerMethod: Map[GrpcMethodName, HandlerFunc[F]])(implicit F: Async[F]): GrpcJsonBridge[F] = {
    new GrpcJsonBridge[F] {

      override def invoke(methodName: GrpcJsonBridge.GrpcMethodName,
                          body: String,
                          headers: Map[String, String]): F[Either[BridgeError, String]] =
        handlersPerMethod.get(methodName) match {
          case Some(handler) => handler(body, headers).map(x => x: Either[BridgeError, String])
          case None => F.pure(Left(BridgeError.GrpcMethodNotFound))
        }

      override def methodHandlers: Map[GrpcMethodName, HandlerFunc[F]] =
        handlersPerMethod

      override val methodsNames: Seq[GrpcJsonBridge.GrpcMethodName] = handlersPerMethod.keys.toSeq
      override val servicesNames: Seq[String] = methodsNames.map(_.service).distinct
    }
  }

  private def createInProcessServer[F[_]](ec: ExecutionContext)(inProcessServiceName: String, services: Seq[ServerServiceDefinition])(
      implicit F: Sync[F]): Resource[F, Server] =
    Resource {
      F.delay {
        val b = InProcessServerBuilder
          .forName(inProcessServiceName)
          .maxInboundMessageSize(Int.MaxValue)
          .executor(ec.execute(_))
        services.foreach(b.addService)
        val s = b.build().start()
        (s, F.delay { s.shutdown().awaitTermination() })
      }
    }

  private def createInProcessChannel[F[_]](ec: ExecutionContext)(inProcessServiceName: String)(
      implicit F: Sync[F]): Resource[F, ManagedChannel] =
    Resource[F, ManagedChannel] {
      F.delay {
        val c = InProcessChannelBuilder
          .forName(inProcessServiceName)
          .maxInboundMessageSize(Int.MaxValue)
          .executor(ec.execute(_))
          .build()
        (c, F.delay { val _ = c.shutdown() })
      }
    }

}
