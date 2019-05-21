package com.avast.grpc.jsonbrige.http4s

import java.util.concurrent.{Executor, Executors}

import cats.data.NonEmptyList
import cats.effect.IO
import com.avast.grpc.jsonbridge._
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{Charset, Header, Headers, MediaType, Method, Request, Uri}
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

import scala.util.Random

class Http4sTest extends FunSuite with ScalaFutures {

  implicit val executor: Executor = Executors.newSingleThreadExecutor()

  test("basic") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[IO](TestServiceImpl.bindService()))

    val Some(response) = service
      .apply(
        Request[IO](
          method = Method.POST,
          uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail())
        ).withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json, Charset.`UTF-8`))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("""{"sum":3}""")(response.as[String].unsafeRunSync())

    assertResult(
      Headers.of(
        `Content-Type`(MediaType.application.json),
        `Content-Length`.fromLong(9).getOrElse(fail())
      ))(response.headers)
  }

  test("path prefix") {
    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))
    val service = Http4s(configuration)(new ReflectionGrpcJsonBridge[IO](TestServiceImpl.bindService()))
    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST, uri = Uri.fromString("/abc/def/com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
          .withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("""{"sum":3}""")(response.as[String].unsafeRunSync())

    assertResult(
      Headers.of(
        `Content-Type`(MediaType.application.json),
        `Content-Length`.fromLong(9).getOrElse(fail())
      ))(response.headers)
  }

  test("bad request after wrong request") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[IO](TestServiceImpl.bindService()))

    { // empty body
      val Some(response) = service
        .apply(
          Request[IO](method = Method.POST, uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
            .withEntity("")
            .withContentType(`Content-Type`(MediaType.application.json))
        )
        .value
        .unsafeRunSync()

      assertResult(org.http4s.Status.BadRequest)(response.status)
      assertResult("Bad Request")(response.status.reason)
    }

    {
      val Some(response) = service
        .apply(
          Request[IO](method = Method.POST, uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
            .withEntity(""" { "a": 1, "b": 2} """)
        )
        .value
        .unsafeRunSync()

      assertResult(org.http4s.Status.BadRequest)(response.status)
    }
  }

  test("propagate user-specified status") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[IO](PermissionDeniedTestServiceImpl.bindService()))

    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST, uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
          .withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Forbidden)(response.status)
    assertResult("Forbidden")(response.status.reason)
  }

  test("provides service info") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[IO](TestServiceImpl.bindService()))

    val Some(response) = service
      .apply(
        Request[IO](method = Method.GET, uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService").getOrElse(fail()))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(response.as[String].unsafeRunSync())
  }

  test("provides services info") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[IO](TestServiceImpl.bindService()))

    val Some(response) = service
      .apply(
        Request[IO](method = Method.GET, uri = Uri.fromString("/").getOrElse(fail()))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(response.as[String].unsafeRunSync())
  }

  test("passes user headers") {
    val service = Http4s(Configuration.Default)(new ReflectionGrpcJsonBridge[IO](TestServiceImpl.withInterceptor))

    val headerValue = Random.alphanumeric.take(10).mkString("")

    val Some(response) = service
      .apply(
        Request[IO](
          method = Method.POST,
          uri = Uri.fromString("com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()),
          headers = Headers.of(Header(TestServiceImpl.HeaderName, headerValue))
        ).withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)
    assertResult(headerValue)(TestServiceImpl.lastContextValue.get())
  }
}
