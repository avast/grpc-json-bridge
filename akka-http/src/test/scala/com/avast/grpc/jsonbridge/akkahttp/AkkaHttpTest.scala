package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.NonEmptyList
import cats.effect.Effect
import com.avast.grpc.jsonbridge._
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.FunSuite

import scala.util.Random

class AkkaHttpTest extends FunSuite with ScalatestRouteTest {

  // this is workaround which solves presence of ExecutionContextExecutor in RouteTest from AKKA
  implicit val sch: Scheduler = Scheduler.global
  private implicit val taskEff: Effect[Task] = Task.catsEffect(Scheduler.global)

  test("basic") {
    val route = AkkaHttp[Task](Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", "{\"a\":1, \"b\": 2}")
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("{\"sum\":3}")(responseAs[String])
      assertResult(Seq(`Content-Type`(ContentType.WithMissingCharset(MediaType.applicationWithOpenCharset("json")))))(headers)
    }
  }

  test("with path prefix") {
    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))
    val route = AkkaHttp[Task](configuration)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))
    Post("/abc/def/com.avast.grpc.jsonbridge.test.TestService/Add", "{\"a\":1, \"b\": 2}")
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("{\"sum\":3}")(responseAs[String])
    }
  }

  test("bad request after wrong request") {
    val route = AkkaHttp[Task](Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))
    // empty body
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", "")
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.BadRequest)(status)
    }
    // no Content-Type header
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", "{\"a\":1, \"b\": 2}") ~> route ~> check {
      assertResult(StatusCodes.BadRequest)(status)
    }
  }

  test("propagates user-specified status") {
    val route = AkkaHttp(Configuration.Default)(new ReflectionGrpcJsonBridge[Task](PermissionDeniedTestServiceImpl.bindService()))
    Post(s"/com.avast.grpc.jsonbridge.test.TestService/Add", "{\"a\":1, \"b\": 2}")
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(status)(StatusCodes.Forbidden)
    }
  }

  test("provides service description") {
    val route = AkkaHttp[Task](Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))
    Get("/com.avast.grpc.jsonbridge.test.TestService") ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(responseAs[String])
    }
  }

  test("provides services description") {
    val route = AkkaHttp[Task](Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))
    Get("/") ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(responseAs[String])
    }
  }

  test("passes headers") {
    val headerValue = Random.alphanumeric.take(10).mkString("")
    val route = AkkaHttp[Task](Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.withInterceptor))
    val Ok(customHeaderToBeSent, _) = HttpHeader.parse(TestServiceImpl.HeaderName, headerValue)
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", "{\"a\":1, \"b\": 2}")
      .withHeaders(AkkaHttp.JsonContentType, customHeaderToBeSent) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("{\"sum\":3}")(responseAs[String])
      assertResult(Seq(`Content-Type`(ContentType.WithMissingCharset(MediaType.applicationWithOpenCharset("json")))))(headers)
    }
  }
}
