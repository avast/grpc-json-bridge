package com.avast.grpc.jsonbridge

import java.util.concurrent.{ExecutorService, Executors}

import com.avast.grpc.jsonbridge.test.TestApi
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.stub.StreamObserver
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

class GrpcJsonBridgeTest extends FunSuite with ScalaFutures {

  test("basic") {
    implicit val executor: ExecutorService = Executors.newCachedThreadPool()

    val channel = InProcessChannelBuilder.forName("channelName").directExecutor.build

    val bridge = channel.createGrpcJsonBridge[TestApiServiceImplBase, TestApiServiceFutureStub]()

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
