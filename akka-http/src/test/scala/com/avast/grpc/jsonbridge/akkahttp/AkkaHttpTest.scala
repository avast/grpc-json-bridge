package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.avast.grpc.jsonbridge._
import io.grpc.ServerServiceDefinition
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.ExecutionContext.Implicits.{global => ec}
import scala.util.Random

class AkkaHttpTest extends AnyFunSuite with ScalatestRouteTest {

  def bridge(ssd: ServerServiceDefinition): GrpcJsonBridge[IO] =
    ReflectionGrpcJsonBridge
      .createFromServices[IO](ec)(ssd)
      .allocated
      .unsafeRunSync()
      ._1

  test("basic") {
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.bindService()))
    val entity = HttpEntity(ContentTypes.`application/json`, """ { "a": 1, "b": 2} """)
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", entity) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("""{"sum":3}""")(responseAs[String])
      assertResult(MediaTypes.`application/json`.some)(
        header[`Content-Type`].map(_.contentType.mediaType)
      )
    }
  }

  test("with path prefix") {
    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))
    val route = AkkaHttp[IO](configuration)(bridge(TestServiceImpl.bindService()))

    val entity = HttpEntity(ContentTypes.`application/json`, """ { "a": 1, "b": 2} """)
    Post("/abc/def/com.avast.grpc.jsonbridge.test.TestService/Add", entity) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("""{"sum":3}""")(responseAs[String])
    }
  }

  test("bad request after wrong request") {
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.bindService()))
    // empty body
    val entity = HttpEntity(ContentTypes.`application/json`, "")
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", entity) ~> route ~> check {
      assertResult(StatusCodes.BadRequest)(status)
    }
    // no Content-Type header
    val entity2 = HttpEntity(""" { "a": 1, "b": 2} """)
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", entity2) ~> route ~> check {
      assertResult(StatusCodes.BadRequest)(status)
    }
  }

  test("propagates user-specified status") {
    val route = AkkaHttp(Configuration.Default)(bridge(PermissionDeniedTestServiceImpl.bindService()))
    val entity = HttpEntity(ContentTypes.`application/json`, """ { "a": 1, "b": 2} """)
    Post(s"/com.avast.grpc.jsonbridge.test.TestService/Add", entity) ~> route ~> check {
      assertResult(status)(StatusCodes.Forbidden)
    }
  }

  test("provides service description") {
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.bindService()))
    Get("/com.avast.grpc.jsonbridge.test.TestService") ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(responseAs[String])
    }
  }

  test("provides services description") {
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.bindService()))
    Get("/") ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(responseAs[String])
    }
  }

  test("passes headers") {
    val headerValue = Random.alphanumeric.take(10).mkString("")
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.withInterceptor))
    val customHeaderToBeSent = RawHeader(TestServiceImpl.HeaderName, headerValue)
    val entity = HttpEntity(ContentTypes.`application/json`, """ { "a": 1, "b": 2} """)
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", entity)
      .withHeaders(customHeaderToBeSent) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("""{"sum":3}""")(responseAs[String])
      assertResult(MediaTypes.`application/json`.some)(
        header[`Content-Type`].map(_.contentType.mediaType)
      )
    }
  }
}
