package com.avast.grpc.jsonbridge

import java.util.concurrent.{Executor, ExecutorService, Executors}

import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader
import com.avast.grpc.jsonbridge.Implicits._
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import com.avast.grpc.jsonbridge.test.{TestApi, TestApiServiceGrpc}
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor.MethodType
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.stub.StreamObserver
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

class GrpcJsonBridgeTest extends FunSuite with ScalaFutures {
  test("basic") {
    implicit val executor: ExecutorService = Executors.newCachedThreadPool()

    val bridge = new GrpcJsonBridge[TestApiServiceImplBase] with GrpcJsonBridgeBase[TestApiServiceFutureStub] {

      protected val channel: ManagedChannel = InProcessChannelBuilder.forName("channelName").directExecutor.build

      override protected def newFutureStub: TestApiServiceFutureStub = TestApiServiceGrpc.newFutureStub(channel)

      override def invokeGrpcMethod(name: String, json: String, headers: Seq[GrpcHeader]): Option[Future[String]] = {
        name match {
          case "Get" =>
            val request = fromJson(GetRequest.getDefaultInstance, json)

            Option {
              withNewFutureStub(headers) { stub =>
                stub.get(request).asScala.map(toJson)
              }
            }

          // unsupported method
          case _ => None
        }
      }

      override val serviceInfo: Seq[String] = {
        import scala.collection.JavaConverters._

        new TestApiServiceImplBase() {}
          .bindService()
          .getMethods
          .asScala
          .map(_.getMethodDescriptor)
          .filter(_.getType == MethodType.UNARY) // filter out all STREAMING methods
          .map(_.getFullMethodName)
          .toSeq
      }
    }

    InProcessServerBuilder
      .forName("channelName")
      .directExecutor
      .addService(new TestApiServiceImplBase {
        override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
          assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
          responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
          responseObserver.onCompleted()
        }
      })
      .build
      .start

    val response = bridge
      .invokeGrpcMethod(
        "Get",
        """ { "names": ["abc","def"] } """
      )
      .getOrElse(fail("Method was not found"))
      .futureValue

    assertResult("""{
                   |  "results": {
                   |    "name": 42
                   |  }
                   |}""".stripMargin)(response)
  }

}

object Implicits {

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
