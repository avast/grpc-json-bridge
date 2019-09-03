package com.avast.grpc.jsonbridge.http4s

import java.util.concurrent.atomic.AtomicReference

import com.avast.grpc.jsonbridge.test.TestServices.AddResponse
import com.avast.grpc.jsonbridge.test.{TestServiceGrpc, TestServices}
import io.grpc.{
  Context,
  Contexts,
  Metadata,
  ServerCall,
  ServerCallHandler,
  ServerInterceptor,
  ServerInterceptors,
  ServerServiceDefinition,
  Status,
  StatusException
}
import io.grpc.stub.StreamObserver

object TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
  val HeaderName: String = "The-Header"
  private val contextKey = Context.key[String]("theHeader")
  private val metadataKey = Metadata.Key.of(HeaderName, Metadata.ASCII_STRING_MARSHALLER)
  val lastContextValue = new AtomicReference[String]("")

  override def add(request: TestServices.AddParams, responseObserver: StreamObserver[TestServices.AddResponse]): Unit = {
    lastContextValue.set(contextKey.get())
    responseObserver.onNext(AddResponse.newBuilder().setSum(request.getA + request.getB).build())
    responseObserver.onCompleted()
  }

  def withInterceptor: ServerServiceDefinition =
    ServerInterceptors.intercept(
      this,
      new ServerInterceptor {
        override def interceptCall[ReqT, RespT](call: ServerCall[ReqT, RespT],
                                                headers: Metadata,
                                                next: ServerCallHandler[ReqT, RespT]): ServerCall.Listener[ReqT] =
          Contexts.interceptCall(Context.current().withValue(contextKey, headers.get(metadataKey)), call, headers, next)
      }
    )
}

object PermissionDeniedTestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
  override def add(request: TestServices.AddParams, responseObserver: StreamObserver[TestServices.AddResponse]): Unit = {
    responseObserver.onError(new StatusException(Status.PERMISSION_DENIED))
  }
}
