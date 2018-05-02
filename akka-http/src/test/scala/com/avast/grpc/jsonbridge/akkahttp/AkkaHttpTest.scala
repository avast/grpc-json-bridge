package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.{ContentType, MediaType, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.NonEmptyList
import com.avast.grpc.jsonbridge._
import com.avast.grpc.jsonbridge.test.TestApi
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.stub.StreamObserver
import io.grpc.{Status, StatusException}
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

    val route = AkkaHttp(Configuration.Default)(bridge)

    Post(s"/${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """) ~> route ~> check {
      assertResult(status)(StatusCodes.OK)

      assertResult("""{
                     |  "results": {
                     |    "name": 42
                     |  }
                     |}""".stripMargin)(responseAs[String])

      assertResult(Seq(`Content-Type`(ContentType.WithMissingCharset(MediaType.applicationWithOpenCharset("json")))))(headers)
    }
  }

  test("with path prefix") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))

    val route = AkkaHttp(configuration)(bridge)

    Post(s"/abc/def/${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """) ~> route ~> check {
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

    val route = AkkaHttp(Configuration.Default)(bridge)

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

    val route = AkkaHttp(Configuration.Default)(bridge)

    Post(s"/${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """) ~> route ~> check {
      assertResult(status)(StatusCodes.Forbidden)
    }
  }

  test("provides service description") {
    val bridge = new TestApiServiceImplBase {}.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val route = AkkaHttp(Configuration.Default)(bridge)

    Get(s"/${classOf[TestApiServiceImplBase].getName.replace("$", ".")}") ~> route ~> check {
      assertResult(status)(StatusCodes.OK)

      assertResult("TestApiService/Get\nTestApiService/Get2")(responseAs[String])
    }
  }
}
