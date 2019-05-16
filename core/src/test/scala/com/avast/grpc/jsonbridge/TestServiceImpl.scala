package com.avast.grpc.jsonbridge

import com.avast.grpc.jsonbridge.test.TestServices.AddResponse
import com.avast.grpc.jsonbridge.test.{TestServiceGrpc, TestServices}
import io.grpc.stub.StreamObserver

class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
  override def add(request: TestServices.AddParams, responseObserver: StreamObserver[TestServices.AddResponse]): Unit = {
    responseObserver.onNext(AddResponse.newBuilder().setSum(request.getA + request.getB).build())
    responseObserver.onCompleted()
  }
}
