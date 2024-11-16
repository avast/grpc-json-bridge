package com.avast.grpc.jsonbridge.http4s

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.avast.grpc.jsonbridge._
import io.grpc.ServerServiceDefinition
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{Charset, Header, Headers, MediaType, Method, Request, Uri}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.ci.CIString

import scala.concurrent.ExecutionContext.Implicits.{global => ec}
import scala.util.Random

class Http4sTest extends AnyFunSuite with ScalaFutures {

  def bridge(ssd: ServerServiceDefinition): GrpcJsonBridge[IO] =
    ReflectionGrpcJsonBridge
      .createFromServices[IO](ec)(ssd)
      .allocated
      .unsafeRunSync()
      ._1

  test("basic") {
    val service = Http4s(Configuration.Default)(bridge(TestServiceImpl.bindService()))

    val response = service
      .apply(
        Request[IO](
          method = Method.POST,
          uri = Uri.fromString("/com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail())
        ).withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json, Charset.`UTF-8`))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok.some)(response.map(_.status))

    assertResult("""{"sum":3}""".some)(response.map(_.as[String].unsafeRunSync()))

    assertResult(Headers(`Content-Type`(MediaType.application.json), `Content-Length`(9)).some)(response.map(_.headers))
  }

  test("path prefix") {
    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))
    val service = Http4s(configuration)(bridge(TestServiceImpl.bindService()))
    val response = service
      .apply(
        Request[IO](method = Method.POST, uri = Uri.fromString("/abc/def/com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
          .withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok.some)(response.map(_.status))

    assertResult("""{"sum":3}""".some)(response.map(_.as[String].unsafeRunSync()))

    assertResult(Headers(`Content-Type`(MediaType.application.json), `Content-Length`(9)).some)(response.map(_.headers))
  }

  test("bad request after wrong request") {
    val service = Http4s(Configuration.Default)(bridge(TestServiceImpl.bindService()))

    { // empty body
      val response = service
        .apply(
          Request[IO](method = Method.POST, uri = Uri.fromString("/com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
            .withEntity("")
            .withContentType(`Content-Type`(MediaType.application.json))
        )
        .value
        .unsafeRunSync()

      assertResult(org.http4s.Status.BadRequest.some)(response.map(_.status))
      assertResult("Bad Request".some)(response.map(_.status.reason))
    }

    {
      val response = service
        .apply(
          Request[IO](method = Method.POST, uri = Uri.fromString("/com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
            .withEntity(""" { "a": 1, "b": 2} """)
        )
        .value
        .unsafeRunSync()

      assertResult(org.http4s.Status.BadRequest.some)(response.map(_.status))
    }
  }

  test("propagate user-specified status") {
    val service = Http4s(Configuration.Default)(bridge(PermissionDeniedTestServiceImpl.bindService()))

    val response = service
      .apply(
        Request[IO](method = Method.POST, uri = Uri.fromString("/com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()))
          .withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Forbidden.some)(response.map(_.status))
    assertResult("Forbidden".some)(response.map(_.status.reason))
  }

  test("provides service info") {
    val service = Http4s(Configuration.Default)(bridge(TestServiceImpl.bindService()))

    val response = service
      .apply(
        Request[IO](method = Method.GET, uri = Uri.fromString("/com.avast.grpc.jsonbridge.test.TestService").getOrElse(fail()))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok.some)(response.map(_.status))

    assertResult("com.avast.grpc.jsonbridge.test.TestService/Add".some)(response.map(_.as[String].unsafeRunSync()))
  }

  test("provides services info") {
    val service = Http4s(Configuration.Default)(bridge(TestServiceImpl.bindService()))

    val response = service
      .apply(
        Request[IO](method = Method.GET, uri = Uri.fromString("/").getOrElse(fail()))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok.some)(response.map(_.status))

    assertResult("com.avast.grpc.jsonbridge.test.TestService/Add".some)(response.map(_.as[String].unsafeRunSync()))
  }

  test("passes user headers") {
    val service = Http4s(Configuration.Default)(bridge(TestServiceImpl.withInterceptor))

    val headerValue = Random.alphanumeric.take(10).mkString("")

    val response = service
      .apply(
        Request[IO](
          method = Method.POST,
          uri = Uri.fromString("/com.avast.grpc.jsonbridge.test.TestService/Add").getOrElse(fail()),
          headers = Headers(Header.Raw(CIString(TestServiceImpl.HeaderName), headerValue))
        ).withEntity(""" { "a": 1, "b": 2} """)
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok.some)(response.map(_.status))
    assertResult(headerValue)(TestServiceImpl.lastContextValue.get())
  }
}
