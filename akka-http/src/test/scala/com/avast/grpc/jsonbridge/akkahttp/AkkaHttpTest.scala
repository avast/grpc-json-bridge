package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.avast.grpc.jsonbridge._
import com.avast.grpc.jsonbridge.test.TestApi
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.{Status, StatusException}
import io.grpc.stub.StreamObserver
import org.scalatest.FunSuite

import scala.collection.JavaConverters._
import scala.concurrent.Future

class AkkaHttpTest extends FunSuite with ScalatestRouteTest {

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

    val route = AkkaHttp(bridge)

    Post(s"/${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """) ~> route ~> check {
      assertResult(status)(StatusCodes.OK)

      assertResult("""{
                     |  "results": {
                     |    "name": 42
                     |  }
                     |}""".stripMargin)(responseAs[String])
    }
  }

  test("bad request after wrong request") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val route = AkkaHttp(bridge)

    Post(s"/${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get", "") ~> route ~> check {
      assertResult(status)(StatusCodes.BadRequest)
    }
  }

  test("propagate user-specified status") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        responseObserver.onError(new StatusException(Status.PERMISSION_DENIED))
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val route = AkkaHttp(bridge)

    Post(s"/${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """) ~> route ~> check {
      assertResult(status)(StatusCodes.Forbidden)
    }
  }
}
