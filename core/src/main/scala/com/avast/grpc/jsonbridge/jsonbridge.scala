package com.avast.grpc

import java.util.concurrent.Executor

import cats.effect.Async
import cats.~>
import com.google.common.util.concurrent._
import io.grpc._
import io.grpc.stub.AbstractStub
import mainecoon.FunctorK

import scala.concurrent._
import scala.language.experimental.macros
import scala.language.higherKinds
import scala.reflect.ClassTag

package object jsonbridge {

  implicit class DeriveBridge[GrpcServiceStub <: BindableService](val serviceStub: GrpcServiceStub) extends AnyVal {
    def createGrpcJsonBridge[F[_], GrpcClientStub <: AbstractStub[GrpcClientStub]](interceptors: ServerInterceptor*)(
        implicit ec: ExecutionContext,
        ex: Executor,
        ct: ClassTag[GrpcServiceStub],
        ct2: ClassTag[F[_]],
        asf: Async[F]): GrpcJsonBridge[F, GrpcServiceStub] =
      macro Macros.generateGrpcJsonBridge[F, GrpcServiceStub, GrpcClientStub]
  }

  implicit class ListenableFuture2ScalaFuture[T](val f: ListenableFuture[T]) extends AnyVal {

    def asScala(implicit executor: Executor): Future[T] = {
      val p = Promise[T]()
      Futures.addCallback(f, new FutureCallback[T] {
        override def onSuccess(result: T): Unit = p.success(result)

        override def onFailure(t: Throwable): Unit = p.failure(t)
      }, executor)
      p.future
    }
  }

  implicit class GrpcJsonBridgeOps[F[_], Service <: BindableService](val bridge: GrpcJsonBridge[F, Service]) extends AnyVal {
    def mapK[G[_]](implicit fToG: ~>[F, G]): GrpcJsonBridge[G, Service] = bridgeFunctorK[Service].mapK(bridge)(fToG)
  }

  implicit def bridgeFunctorK[Service <: BindableService]: FunctorK[GrpcJsonBridge[?[_], Service]] =
    new FunctorK[GrpcJsonBridge[?[_], Service]] {
      override def mapK[F[_], G[_]](bridge: GrpcJsonBridge[F, Service])(fToG: ~>[F, G]): GrpcJsonBridge[G, Service] =
        new GrpcJsonBridge[G, Service] {
          override def invokeGrpcMethod(name: String,
                                        json: => String,
                                        headers: => Seq[GrpcJsonBridge.GrpcHeader]): G[Either[Status, String]] = fToG {
            bridge.invokeGrpcMethod(name, json, headers)
          }
          override def methodsNames: Seq[String] = bridge.methodsNames

          override def serviceName: String = bridge.serviceName
          override def close(): Unit = bridge.close()
        }
    }

}
