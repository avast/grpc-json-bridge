package com.avast.grpc.jsonbridge.akkahttp

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.Future

trait LiftToFuture[F[_]] {
  def liftF[A](f: F[A]): Future[A]
}

object LiftToFuture {
  def apply[F[_]](implicit f: LiftToFuture[F]): LiftToFuture[F] = f

  implicit def liftToFutureForIO(implicit runtime: IORuntime): LiftToFuture[IO] = new LiftToFuture[IO] {
    override def liftF[A](f: IO[A]): Future[A] = f.unsafeToFuture()
  }
}
