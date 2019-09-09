package com.avast.grpc.jsonbridge

import com.avast.grpc.jsonbridge.test.TestServices2.AddResponse2
import com.avast.grpc.jsonbridge.test.{TestService2Grpc, TestServices2}
import io.grpc.stub.StreamObserver

class TestServiceImpl2 extends TestService2Grpc.TestService2ImplBase {
  override def add2(request: TestServices2.AddParams2, responseObserver: StreamObserver[TestServices2.AddResponse2]): Unit = {
    responseObserver.onNext(AddResponse2.newBuilder().setSum(request.getA + request.getB).build())
    responseObserver.onCompleted()
  }
}
