package com.avast.grpc.jsonbrige.http4s

import java.util.concurrent.{Executor, Executors}

import cats.data.NonEmptyList
import com.avast.grpc.jsonbridge._
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import com.avast.grpc.jsonbridge.test.{TestApi, TestApiService}
import io.grpc._
import io.grpc.stub.StreamObserver
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{Charset, Header, Headers, MediaType, Method, Request, Uri}
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Random

class Http4sTest extends FunSuite with ScalaFutures {
  implicit val executor: Executor = Executors.newCachedThreadPool()

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
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[Task](
          method = Method.POST,
          uri = Uri.fromString(s"${classOf[TestApiService].getName.replace("$", ".")}/Get").getOrElse(fail())
        ).withBody(""" { "names": ["abc","def"] } """)
          .runAsync
          .futureValue
          .withContentType(`Content-Type`(MediaType.`application/json`, Charset.`UTF-8`))
      )
      .value
      .runAsync
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("""{"results":{"name":42}}""")(response.as[String].runAsync.futureValue)

    assertResult(
      Headers(
        `Content-Type`(MediaType.`application/json`),
        `Content-Length`.fromLong(23).getOrElse(fail())
      ))(response.headers)

  }

  test("path prefix") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))

    val service = Http4s(configuration)(bridge)

    val Some(response) = service
      .apply(
        Request[Task](method = Method.POST,
                      uri = Uri.fromString(s"/abc/def/${classOf[TestApiService].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody(""" { "names": ["abc","def"] } """)
          .runAsync
          .futureValue
          .withContentType(`Content-Type`(MediaType.`application/json`))
      )
      .value
      .runAsync
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("""{"results":{"name":42}}""")(response.as[String].runAsync.futureValue)

    assertResult(
      Headers(
        `Content-Type`(MediaType.`application/json`),
        `Content-Length`.fromLong(23).getOrElse(fail())
      ))(response.headers)

  }

  test("bad request after wrong request") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    { // empty body
      val Some(response) = service
        .apply(
          Request[Task](method = Method.POST,
                        uri = Uri.fromString(s"${classOf[TestApiService].getName.replace("$", ".")}/Get").getOrElse(fail()))
            .withBody("")
            .runAsync
            .futureValue
            .withContentType(`Content-Type`(MediaType.`application/json`))
        )
        .value
        .runAsync
        .futureValue

      assertResult(org.http4s.Status.BadRequest)(response.status)
    }

    {
      val Some(response) = service
        .apply(
          Request[Task](method = Method.POST,
                        uri = Uri.fromString(s"${classOf[TestApiService].getName.replace("$", ".")}/Get").getOrElse(fail()))
            .withBody(""" { "names": ["abc","def"] } """)
            .runAsync
            .futureValue
        )
        .value
        .runAsync
        .futureValue

      assertResult(org.http4s.Status.BadRequest)(response.status)
    }
  }

  test("propagate user-specified status") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        responseObserver.onError(new StatusException(Status.PERMISSION_DENIED))
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[Task](method = Method.POST,
                      uri = Uri.fromString(s"${classOf[TestApiService].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody(""" { "names": ["abc","def"] } """)
          .runAsync
          .futureValue
          .withContentType(`Content-Type`(MediaType.`application/json`))
      )
      .value
      .runAsync
      .futureValue

    assertResult(org.http4s.Status.Forbidden)(response.status)
  }

  test("provides service info") {
    val bridge = new TestApiServiceImplBase {}.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[Task](method = Method.GET, uri = Uri.fromString(s"${classOf[TestApiService].getName.replace("$", ".")}").getOrElse(fail()))
      )
      .value
      .runAsync
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("com.avast.grpc.jsonbridge.test.TestApiService/Get\ncom.avast.grpc.jsonbridge.test.TestApiService/Get2")(
      response.as[String].runAsync.futureValue)
  }

  test("provides services info") {
    val bridge = new TestApiServiceImplBase {}.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[Task](method = Method.GET, uri = Uri.fromString("/").getOrElse(fail()))
      )
      .value
      .runAsync
      .futureValue

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("com.avast.grpc.jsonbridge.test.TestApiService/Get\ncom.avast.grpc.jsonbridge.test.TestApiService/Get2")(
      response.as[String].runAsync.futureValue)
  }

  test("passes user headers") {
    val headerValue = Random.alphanumeric.take(10).mkString("")

    val ctxKey = Context.key[String]("theHeader")
    val mtKey = Metadata.Key.of("The-Header", Metadata.ASCII_STRING_MARSHALLER)

    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        // NOTE: there is exception with this failed assert in the log; however it's fina - it's really missing during the first call
        assertResult(headerValue)(ctxKey.get())

        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub](
      new ServerInterceptor {
        override def interceptCall[ReqT, RespT](call: ServerCall[ReqT, RespT],
                                                headers: Metadata,
                                                next: ServerCallHandler[ReqT, RespT]): ServerCall.Listener[ReqT] = {

          Contexts.interceptCall(Context.current().withValue(ctxKey, headers.get(mtKey)), call, headers, next)
        }
      }
    )

    val service = Http4s(Configuration.Default)(bridge)

    {
      val Some(response) = service
        .apply(
          Request[Task](
            method = Method.POST,
            uri = Uri.fromString(s"${classOf[TestApiService].getName.replace("$", ".")}/Get").getOrElse(fail()),
            headers = Headers(Header("The-Header", headerValue))
          ).withBody(""" { "names": ["abc","def"] } """)
            .runAsync
            .futureValue
            .withContentType(`Content-Type`(MediaType.`application/json`))
        )
        .value
        .runAsync
        .futureValue

      assertResult(org.http4s.Status.Ok)(response.status)
    }

    {
      val Some(response) = service
        .apply(
          Request[Task](method = Method.POST,
                        uri = Uri.fromString(s"${classOf[TestApiService].getName.replace("$", ".")}/Get").getOrElse(fail()))
            .withBody(""" { "names": ["abc","def"] } """)
            .runAsync
            .futureValue
            .withContentType(`Content-Type`(MediaType.`application/json`))
        )
        .value
        .runAsync
        .futureValue

      assertResult(org.http4s.Status.InternalServerError)(response.status) // because of failed assertResult
    }
  }
}
