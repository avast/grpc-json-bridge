package com.avast.grpc.jsonbridge

import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import io.grpc.Status
import io.grpc.inprocess.InProcessServerBuilder
import monix.eval.Task
import org.scalatest.{fixture, Matchers, Outcome}

import scala.concurrent.duration.Duration
import monix.execution.Scheduler.Implicits.global

class ReflectionGrpcJsonBridgeTest extends fixture.FlatSpec with Matchers {

  case class FixtureParam(bridge: GrpcJsonBridge[Task])

  override protected def withFixture(test: OneArgTest): Outcome = {
    val channelName = InProcessServerBuilder.generateName
    val server = InProcessServerBuilder.forName(channelName).addService(new TestServiceImpl()).build
    val bridge = new ReflectionGrpcJsonBridge[Task](server)
    try {
      test(FixtureParam(bridge))
    } finally {
      server.shutdownNow()
      bridge.close()
    }
  }

  it must "successfully call the invoke method" in { f =>
    val Right(response) =
      f.bridge
        .invoke(GrpcMethodName("com.avast.grpc.jsonbridge.test.TestService/Add"), "{\"a\":1, \"b\": 2}", Map.empty)
        .runSyncUnsafe(Duration.Inf)
    response shouldBe "{\"sum\":3}"
  }

  it must "return expected status code for missing method" in { f =>
    val Left(status) = f.bridge.invoke(GrpcMethodName("ble/bla"), "{}", Map.empty).runSyncUnsafe(Duration.Inf)
    status.getCode shouldBe Status.NOT_FOUND.getCode
  }

  it must "return expected status code for malformed JSON" in { f =>
    val Left(status) =
      f.bridge.invoke(GrpcMethodName("com.avast.grpc.jsonbridge.test.TestService/Add"), "{ble}", Map.empty).runSyncUnsafe(Duration.Inf)
    status.getCode shouldBe Status.INVALID_ARGUMENT.getCode
  }

}
