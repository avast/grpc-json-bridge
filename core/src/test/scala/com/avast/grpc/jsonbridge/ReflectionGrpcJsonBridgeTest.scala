package com.avast.grpc.jsonbridge

import cats.effect.IO
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.services.ProtoReflectionService
import org.scalatest.{fixture, Matchers, Outcome}

import scala.concurrent.ExecutionContext.Implicits.global

class ReflectionGrpcJsonBridgeTest extends fixture.FlatSpec with Matchers {

  case class FixtureParam(bridge: GrpcJsonBridge[IO])

  override protected def withFixture(test: OneArgTest): Outcome = {
    val channelName = InProcessServerBuilder.generateName
    val server = InProcessServerBuilder
      .forName(channelName)
      .addService(new TestServiceImpl())
      .addService(ProtoReflectionService.newInstance())
      .build
    val (bridge, close) = ReflectionGrpcJsonBridge.createFromServer[IO](global)(server).allocated.unsafeRunSync()
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
        .invoke(GrpcMethodName("com.avast.grpc.jsonbridge.test.TestService/Add"), """ { "a": 1, "b": 2} """, Map.empty)
        .unsafeRunSync()
    response shouldBe """{"sum":3}"""
  }

  it must "return expected status code for missing method" in { f =>
    val Left(status) = f.bridge.invoke(GrpcMethodName("ble/bla"), "{}", Map.empty).unsafeRunSync()
    status shouldBe BridgeError.GrpcMethodNotFound
  }

  it must "return expected status code for malformed JSON" in { f =>
    val Left(status) =
      f.bridge.invoke(GrpcMethodName("com.avast.grpc.jsonbridge.test.TestService/Add"), "{ble}", Map.empty).unsafeRunSync()
    status should matchPattern { case BridgeError.RequestJsonParseError(_) => }
  }

}
