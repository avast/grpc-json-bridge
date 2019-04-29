package com.avast.grpc.jsonbridge

import cats.~>
import com.avast.cactus.grpc.server.GrpcService
import com.avast.grpc.jsonbridge.internalPackage.MyServiceImpl
import com.avast.grpc.jsonbridge.test.TestApi
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.Status
import io.grpc.stub.StreamObserver
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.language.higherKinds

package internalPackage {
  // this is here to test that package from PROTO fgile is used as "serviceName"
  class MyServiceImpl extends TestApiServiceImplBase with FakeImplBase

  // this is here to prevent wrong impl base detection
  trait FakeImplBase
}

class GrpcJsonBridgeTest extends FunSuite with ScalaFutures {

  implicit val p: PatienceConfig = PatienceConfig(timeout = Span(1, Seconds), interval = Span(100, Milliseconds))

  case class MyRequest(names: Seq[String])

  case class MyResponse(results: Map[String, Int])

  trait MyApi[F[_]] extends GrpcService {
    def get(request: MyRequest): F[Either[Status, MyResponse]]

    def get2(request: MyRequest): F[Either[Status, MyResponse]]
  }

  test("basic") {
    val bridge = new MyServiceImpl {
      override def get(request: GetRequest, responseObserver: StreamObserver[GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val Right(response) = bridge
      .invokeGrpcMethod(
        "Get",
        """ { "names": ["abc","def"] } """
      )
      .runToFuture
      .futureValue

    assertResult("""{"results":{"name":42}}""")(response)

    assertResult(Left(Status.NOT_FOUND)) {
      bridge
        .invokeGrpcMethod(
          "get", // wrong casing
          """ { "names": ["abc","def"] } """
        )
        .runToFuture
        .futureValue
    }

    assertResult("com.avast.grpc.jsonbridge.test.TestApiService")(bridge.serviceName)
  }

  test("bad request") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        fail()
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val Left(status) = bridge
      .invokeGrpcMethod("Get", "")
      .runToFuture
      .futureValue

    assertResult(Status.INVALID_ARGUMENT.getCode)(status.getCode)
  }

  test("total failure") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        sys.error("The failure")
      }
    }.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val Left(status) = bridge
      .invokeGrpcMethod("Get", """ { "names": ["abc","def"] } """)
      .runToFuture
      .futureValue

    assertResult(Status.INTERNAL.getCode)(status.getCode)
  }

  test("with Cactus") {
    import com.avast.cactus.grpc._
    import com.avast.cactus.grpc.server._

    val service = new MyApi[Task] {
      override def get(request: MyRequest): Task[Either[Status, MyResponse]] = Task {
        assertResult(MyRequest(Seq("abc", "def")))(request)

        Right {
          MyResponse {
            Map(
              "name" -> 42
            )
          }
        }
      }

      override def get2(request: MyRequest): Task[Either[Status, MyResponse]] = Task.now(Left(Status.INTERNAL))
    }.mappedToService[TestApiServiceImplBase]() // cactus mapping

    val bridge = service.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()

    val Right(response) = bridge
      .invokeGrpcMethod(
        "Get",
        """ { "names": ["abc","def"] } """
      )
      .runToFuture
      .futureValue

    assertResult("""{"results":{"name":42}}""")(response)
  }

  test("functorK") {
    assertCompiles(
      """
        |val bridge: GrpcJsonBridge[Task, MyServiceImpl] = new MyServiceImpl {
        |  override def get(request: GetRequest, responseObserver: StreamObserver[GetResponse]): Unit = {}
        |}.createGrpcJsonBridge[Task, TestApiServiceFutureStub]()
        |
        |implicit val taskToFuture: Task ~> Future = new ~>[Task, Future] { override def apply[A](fa: Task[A]): Future[A] = fa.runToFuture }
        |
        |val bridgeFuture: GrpcJsonBridge[Future, MyServiceImpl] = bridge.mapK[Future]
      """.stripMargin)
  }
}
