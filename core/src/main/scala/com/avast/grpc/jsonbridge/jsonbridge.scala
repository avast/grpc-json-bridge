package com.avast.grpc

import java.util.concurrent.Executor

import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import io.grpc.BindableService
import io.grpc.stub.AbstractStub

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.experimental.macros
import scala.reflect.ClassTag

package object jsonbridge {

  implicit class DeriveBridge[GrpcServiceStub <: BindableService](val serviceStub: GrpcServiceStub) extends AnyVal {
    def createGrpcJsonBridge[GrpcClientStub <: AbstractStub[GrpcClientStub]]()(
        implicit ec: ExecutionContext,
        ex: Executor,
        ct: ClassTag[GrpcServiceStub]): GrpcJsonBridge[GrpcServiceStub] =
      macro Macros.generateGrpcJsonBridge[GrpcServiceStub, GrpcClientStub]
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

}
