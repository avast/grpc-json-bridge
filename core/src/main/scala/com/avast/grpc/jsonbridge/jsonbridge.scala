package com.avast.grpc

import java.util.concurrent.Executor

import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import io.grpc.stub.AbstractStub
import io.grpc.{BindableService, Channel}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.experimental.macros

package object jsonbridge {

  implicit class DeriveBridge(val channel: Channel) extends AnyVal {
    def createGrpcJsonBridge[GrpcServiceStub <: BindableService, GrpcClientStub <: AbstractStub[GrpcClientStub]]()(
        implicit ec: ExecutionContext,
        ex: Executor): GrpcJsonBridge[GrpcServiceStub] =
      macro Macros.generateGrpcJsonBridge[GrpcServiceStub, GrpcClientStub]
  }

  private[jsonbridge] implicit class ListenableFuture2ScalaFuture[T](val f: ListenableFuture[T]) extends AnyVal {

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
