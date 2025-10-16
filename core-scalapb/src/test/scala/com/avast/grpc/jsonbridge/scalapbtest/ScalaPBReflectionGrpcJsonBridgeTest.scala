package com.avast.grpc.jsonbridge.scalapbtest

import cats.effect.IO
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.avast.grpc.jsonbridge.scalapb.ScalaPBReflectionGrpcJsonBridge
import com.avast.grpc.jsonbridge.scalapbtest.TestServices.TestServiceGrpc
import com.avast.grpc.jsonbridge.{BridgeError, GrpcJsonBridge}
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.services.{HealthStatusManager, ProtoReflectionServiceV1}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Outcome, flatspec}

import scala.concurrent.ExecutionContext.Implicits.global

class ScalaPBReflectionGrpcJsonBridgeTest extends flatspec.FixtureAnyFlatSpec with Matchers {

  case class FixtureParam(bridge: GrpcJsonBridge[IO]) // scalafix:ok

  override protected def withFixture(test: OneArgTest): Outcome = {
    val channelName = InProcessServerBuilder.generateName
    val server = InProcessServerBuilder
      .forName(channelName)
      .addService(TestServiceGrpc.bindService(new TestServiceImpl, global))
      .addService(ProtoReflectionServiceV1.newInstance())
      .addService(new HealthStatusManager().getHealthService)
      .build
    val (bridge, close) = ScalaPBReflectionGrpcJsonBridge.createFromServer[IO](global)(server).allocated.unsafeRunSync()
    try {
      test(FixtureParam(bridge))
    } finally {
      server.shutdownNow()
      close.unsafeRunSync()
    }
  }

  it must "successfully call the invoke method" in { f =>
    val Right(response) =
      f.bridge
        .invoke(GrpcMethodName("com.avast.grpc.jsonbridge.scalapbtest.TestService/Add"), """ { "a": 1, "b": 2} """, Map.empty)
        .unsafeRunSync()
    response shouldBe """{"sum":3}"""
  }

  it must "return expected status code for missing method" in { f =>
    val Left(status) = f.bridge.invoke(GrpcMethodName("ble/bla"), "{}", Map.empty).unsafeRunSync()
    status shouldBe BridgeError.GrpcMethodNotFound
  }

  it must "return BridgeError.Json for wrongly named field" in { f =>
    val Left(status) = f.bridge
      .invoke(GrpcMethodName("com.avast.grpc.jsonbridge.scalapbtest.TestService/Add"), """ { "x": 1, "b": 2} """, Map.empty)
      .unsafeRunSync()
    status should matchPattern { case BridgeError.Json(_) => }
  }

  it must "return expected status code for malformed JSON" in { f =>
    val Left(status) =
      f.bridge.invoke(GrpcMethodName("com.avast.grpc.jsonbridge.scalapbtest.TestService/Add"), "{ble}", Map.empty).unsafeRunSync()
    status should matchPattern { case BridgeError.Json(_) => }
  }

}
