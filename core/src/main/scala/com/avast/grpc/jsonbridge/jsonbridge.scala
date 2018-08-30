package com.avast.grpc

import java.util.concurrent.Executor

import cats.arrow.FunctionK
import cats.effect.{Async, Effect}
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import io.grpc.stub.AbstractStub
import io.grpc.{BindableService, ServerInterceptor}
import monix.eval.Task

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.experimental.macros
import scala.language.higherKinds
import scala.reflect.ClassTag

package object jsonbridge {

  type ToTask[A[_]] = FunctionK[A, Task]

  implicit val fkTaskIdentity: FunctionK[Task, Task] = FunctionK.id

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

}
