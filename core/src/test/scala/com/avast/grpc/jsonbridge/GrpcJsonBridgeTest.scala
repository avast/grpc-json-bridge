package com.avast.grpc.jsonbridge

import java.util.concurrent.{ExecutorService, Executors}

import com.avast.grpc.jsonbridge.test.TestApi
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class GrpcJsonBridgeTest extends FunSuite with ScalaFutures {

  implicit val executor: ExecutorService = Executors.newCachedThreadPool()

  case class MyRequest(names: Seq[String])

  case class MyResponse(results: Map[String, Int])

  trait MyApi {
    def get(request: MyRequest): Future[Either[Status, MyResponse]]

    def get2(request: MyRequest): Future[Either[Status, MyResponse]]
  }

  test("basic") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val Right(response) = bridge
      .invokeGrpcMethod(
        "Get",
        """ { "names": ["abc","def"] } """
      )
      .futureValue

    assertResult("""{
                   |  "results": {
                   |    "name": 42
                   |  }
                   |}""".stripMargin)(response)

    assertResult(Left(Status.NOT_FOUND)) {
      bridge
        .invokeGrpcMethod(
          "get", // wrong casing
          """ { "names": ["abc","def"] } """
        )
        .futureValue
    }
  }

  test("bad request") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        fail()
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val Left(status) = bridge
      .invokeGrpcMethod("Get", "")
      .futureValue

    assertResult(Status.INVALID_ARGUMENT.getCode)(status.getCode)
  }

  test("total failure") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        sys.error("The failure")
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val Left(status) = bridge
      .invokeGrpcMethod("Get", """ { "names": ["abc","def"] } """)
      .futureValue

    assertResult(Status.INTERNAL.getCode)(status.getCode)
  }

  test("with Cactus") {
    import com.avast.cactus.grpc.server._

    val service = new MyApi {
      override def get(request: MyRequest): Future[Either[Status, MyResponse]] = Future.successful {
        assertResult(MyRequest(Seq("abc", "def")))(request)

        Right {
          MyResponse {
            Map(
              "name" -> 42
            )
          }
        }
      }

      override def get2(request: MyRequest): Future[Either[Status, MyResponse]] = Future.successful(Left(Status.INTERNAL))
    }.mappedToService[TestApiServiceImplBase]() // cactus mapping

    val bridge = service.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val Right(response) = bridge
      .invokeGrpcMethod(
        "Get",
        """ { "names": ["abc","def"] } """
      )
      .futureValue

    assertResult("""{
                   |  "results": {
                   |    "name": 42
                   |  }
                   |}""".stripMargin)(response)
  }

}
