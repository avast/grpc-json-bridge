package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.{ContentType, HttpHeader, MediaType, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.NonEmptyList
import com.avast.grpc.jsonbridge._
import com.avast.grpc.jsonbridge.test.{TestApi, TestApiService}
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc._
import io.grpc.stub.StreamObserver
import monix.eval.Task
import org.scalatest.FunSuite

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

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
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val route = AkkaHttp[Task](Configuration.Default)(bridge)(implicitly[ToTask[Task]], monix.execution.Scheduler.Implicits.global)

    Post(s"/${classOf[TestApiService].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """)
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)

      assertResult("""{"results":{"name":42}}""")(responseAs[String])

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
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))

    val route = AkkaHttp(configuration)(bridge)(implicitly[ToTask[Task]], monix.execution.Scheduler.Implicits.global)

    Post(s"/abc/def/${classOf[TestApiService].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """)
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)

      assertResult("""{"results":{"name":42}}""")(responseAs[String])
    }
  }

  test("bad request after wrong request") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val route = AkkaHttp(Configuration.Default)(bridge)(implicitly[ToTask[Task]], monix.execution.Scheduler.Implicits.global)

    // empty body
    Post(s"/${classOf[TestApiService].getName.replace("$", ".")}/Get", "")
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.BadRequest)(status)
    }

    // no Content-Type header
    Post(s"/${classOf[TestApiService].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """) ~> route ~> check {
      assertResult(StatusCodes.BadRequest)(status)
    }
  }

  test("propagates user-specified status") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        responseObserver.onError(new StatusException(Status.PERMISSION_DENIED))
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val route = AkkaHttp(Configuration.Default)(bridge)(implicitly[ToTask[Task]], monix.execution.Scheduler.Implicits.global)

    Post(s"/${classOf[TestApiService].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """)
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(status)(StatusCodes.Forbidden)
    }
  }

  test("provides service description") {
    val bridge = new TestApiServiceImplBase {}.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val route = AkkaHttp(Configuration.Default)(bridge)(implicitly[ToTask[Task]], monix.execution.Scheduler.Implicits.global)

    Get(s"/${classOf[TestApiService].getName.replace("$", ".")}") ~> route ~> check {
      assertResult(StatusCodes.OK)(status)

      assertResult("com.avast.grpc.jsonbridge.test.TestApiService/Get\ncom.avast.grpc.jsonbridge.test.TestApiService/Get2")(
        responseAs[String])
    }
  }

  test("provides services description") {
    val bridge = new TestApiServiceImplBase {}.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val route = AkkaHttp(Configuration.Default)(bridge)(implicitly[ToTask[Task]], monix.execution.Scheduler.Implicits.global)

    Get("/") ~> route ~> check {
      assertResult(StatusCodes.OK)(status)

      assertResult("com.avast.grpc.jsonbridge.test.TestApiService/Get\ncom.avast.grpc.jsonbridge.test.TestApiService/Get2")(
        responseAs[String])
    }
  }

  test("passes headers") {
    val headerValue = Random.alphanumeric.take(10).mkString("")

    val ctxKey = Context.key[String]("theHeader")
    val mtKey = Metadata.Key.of("The-Header", Metadata.ASCII_STRING_MARSHALLER)

    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[GetResponse]): Unit = {
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

    val route = AkkaHttp(Configuration.Default)(bridge)(implicitly[ToTask[Task]], monix.execution.Scheduler.Implicits.global)

    val Ok(customHeaderToBeSent, _) = HttpHeader.parse("The-Header", headerValue)

    Post(s"/${classOf[TestApiService].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """)
      .withHeaders(AkkaHttp.JsonContentType, customHeaderToBeSent) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)

      assertResult("""{"results":{"name":42}}""")(responseAs[String])

      assertResult(Seq(`Content-Type`(ContentType.WithMissingCharset(MediaType.applicationWithOpenCharset("json")))))(headers)
    }

    // missing the user header
    Post(s"/${classOf[TestApiService].getName.replace("$", ".")}/Get", """ { "names": ["abc","def"] } """)
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.InternalServerError)(status) // because there was failed `assertResult`
    }
  }
}
