package com.avast.grpc.jsonbrige.http4s

import cats.data.NonEmptyList
import com.avast.grpc.jsonbridge._
import monix.eval.Task
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{Charset, Header, Headers, MediaType, Method, Request, Uri}
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

import scala.util.Random

import monix.execution.Scheduler.Implicits.global

class Http4sTest extends FunSuite with ScalaFutures {

  test("basic") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))

    val Some(response) = service
      .apply(
        Request[Task](
          method = Method.POST,
          uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail())
        ).withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json, Charset.`UTF-8`))
      )
      .value
      .runToFuture
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("""{"sum":3}""")(response.as[String].runToFuture.futureValue)

    assertResult(
      Headers.of(
        `Content-Type`(MediaType.application.json),
        `Content-Length`.fromLong(9).getOrElse(fail())
      ))(response.headers)
  }

  test("path prefix") {
    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))
    val service = Http4s(configuration)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))
    val Some(response) = service
      .apply(
        Request[Task](method = Method.POST,
                      uri = Uri.fromString("/abc/def/com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
          .withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .runToFuture
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("""{"sum":3}""")(response.as[String].runToFuture.futureValue)

    assertResult(
      Headers.of(
        `Content-Type`(MediaType.application.json),
        `Content-Length`.fromLong(9).getOrElse(fail())
      ))(response.headers)
  }

  test("bad request after wrong request") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))

    { // empty body
      val Some(response) = service
        .apply(
          Request[Task](method = Method.POST, uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
            .withEntity("")
            .withContentType(`Content-Type`(MediaType.application.json))
        )
        .value
        .runToFuture
        .futureValue

      assertResult(org.http4s.Status.BadRequest)(response.status)
      assertResult("Bad Request")(response.status.reason)
    }

    {
      val Some(response) = service
        .apply(
          Request[Task](method = Method.POST, uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
            .withEntity(""" { "a": 1, "b": 2} """)
        )
        .value
        .runToFuture
        .futureValue

      assertResult(org.http4s.Status.BadRequest)(response.status)
    }
  }

  test("propagate user-specified status") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[Task](PermissionDeniedTestServiceImpl.bindService()))

    val Some(response) = service
      .apply(
        Request[Task](method = Method.POST, uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
          .withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .runToFuture
      .futureValue

    assertResult(org.http4s.Status.Forbidden)(response.status)
    assertResult("Forbidden")(response.status.reason)
  }

  test("provides service info") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))

    val Some(response) = service
      .apply(
        Request[Task](method = Method.GET, uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService").getOrElse(fail()))
      )
      .value
      .runToFuture
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(response.as[String].runToFuture.futureValue)
  }

  test("provides services info") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.bindService()))

    val Some(response) = service
      .apply(
        Request[Task](method = Method.GET, uri = Uri.fromString("/").getOrElse(fail()))
      )
      .value
      .runToFuture
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(response.as[String].runToFuture.futureValue)
  }

  test("passes user headers") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[Task](TestServiceImpl.withInterceptor))

    val headerValue = Random.alphanumeric.take(10).mkString("")

    val Some(response) = service
      .apply(
        Request[Task](
          method = Method.POST,
          uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()),
          headers = Headers.of(Header(TestServiceImpl.HeaderName, headerValue))
        ).withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .runToFuture
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)
    assertResult(headerValue)(TestServiceImpl.lastContextValue.get())
  }
}
