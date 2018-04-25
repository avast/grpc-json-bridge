package com.avast.grpc.jsonbrige.http4s

import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.IO
import com.avast.grpc.jsonbridge._
import com.avast.grpc.jsonbridge.test.TestApi
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.stub.StreamObserver
import io.grpc.{Status, StatusException}
import org.http4s.{Method, Request, Uri}
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Http4sTest extends FunSuite with ScalaFutures {
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

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST,
                    uri = Uri.fromString(s"${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody(""" { "names": ["abc","def"] } """)
          .unsafeRunSync()
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)

  }

  test("bad request after wrong request") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST,
                    uri = Uri.fromString(s"${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody("")
          .unsafeRunSync()
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.BadRequest)(response.status)
  }

  test("propagate user-specified status") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        responseObserver.onError(new StatusException(Status.PERMISSION_DENIED))
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST,
                    uri = Uri.fromString(s"${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody(""" { "names": ["abc","def"] } """)
          .unsafeRunSync()
      )
      .value
      .unsafeToFuture()
      .futureValue

    assertResult(org.http4s.Status.Forbidden)(response.status)
  }
}
