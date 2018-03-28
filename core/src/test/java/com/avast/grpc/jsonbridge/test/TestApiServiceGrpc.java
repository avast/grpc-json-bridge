package com.avast.grpc.jsonbridge.test;

import io.grpc.stub.ClientCalls;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.9.0)",
    comments = "Source: test_api.proto")
public final class TestApiServiceGrpc {

  private TestApiServiceGrpc() {}

  public static final String SERVICE_NAME = "TestApiService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @Deprecated // Use {@link #getGetMethod()} instead.
  public static final io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> METHOD_GET = getGetMethod();

  private static volatile io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> getGetMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> getGetMethod() {
    io.grpc.MethodDescriptor<TestApi.GetRequest, TestApi.GetResponse> getGetMethod;
    if ((getGetMethod = TestApiServiceGrpc.getGetMethod) == null) {
      synchronized (TestApiServiceGrpc.class) {
        if ((getGetMethod = TestApiServiceGrpc.getGetMethod) == null) {
          TestApiServiceGrpc.getGetMethod = getGetMethod =
              io.grpc.MethodDescriptor.<TestApi.GetRequest, TestApi.GetResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "TestApiService", "Get"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  TestApi.GetRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  TestApi.GetResponse.getDefaultInstance()))
                  .setSchemaDescriptor(new TestApiServiceMethodDescriptorSupplier("Get"))
                  .build();
          }
        }
     }
     return getGetMethod;
  }
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @Deprecated // Use {@link #getGet2Method()} instead.
  public static final io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> METHOD_GET2 = getGet2Method();

  private static volatile io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> getGet2Method;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> getGet2Method() {
    io.grpc.MethodDescriptor<TestApi.GetRequest, TestApi.GetResponse> getGet2Method;
    if ((getGet2Method = TestApiServiceGrpc.getGet2Method) == null) {
      synchronized (TestApiServiceGrpc.class) {
        if ((getGet2Method = TestApiServiceGrpc.getGet2Method) == null) {
          TestApiServiceGrpc.getGet2Method = getGet2Method =
              io.grpc.MethodDescriptor.<TestApi.GetRequest, TestApi.GetResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "TestApiService", "Get2"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  TestApi.GetRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  TestApi.GetResponse.getDefaultInstance()))
                  .setSchemaDescriptor(new TestApiServiceMethodDescriptorSupplier("Get2"))
                  .build();
          }
        }
     }
     return getGet2Method;
  }
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @Deprecated // Use {@link #getGet3Method()} instead.
  public static final io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> METHOD_GET3 = getGet3Method();

  private static volatile io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> getGet3Method;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<TestApi.GetRequest,
      TestApi.GetResponse> getGet3Method() {
    io.grpc.MethodDescriptor<TestApi.GetRequest, TestApi.GetResponse> getGet3Method;
    if ((getGet3Method = TestApiServiceGrpc.getGet3Method) == null) {
      synchronized (TestApiServiceGrpc.class) {
        if ((getGet3Method = TestApiServiceGrpc.getGet3Method) == null) {
          TestApiServiceGrpc.getGet3Method = getGet3Method =
              io.grpc.MethodDescriptor.<TestApi.GetRequest, TestApi.GetResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "TestApiService", "Get3"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  TestApi.GetRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  TestApi.GetResponse.getDefaultInstance()))
                  .setSchemaDescriptor(new TestApiServiceMethodDescriptorSupplier("Get3"))
                  .build();
          }
        }
     }
     return getGet3Method;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static TestApiServiceStub newStub(io.grpc.Channel channel) {
    return new TestApiServiceStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static TestApiServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new TestApiServiceBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static TestApiServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new TestApiServiceFutureStub(channel);
  }

  /**
   */
  public static abstract class TestApiServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public void get(TestApi.GetRequest request,
        io.grpc.stub.StreamObserver<TestApi.GetResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getGetMethod(), responseObserver);
    }

    /**
     */
    public void get2(TestApi.GetRequest request,
        io.grpc.stub.StreamObserver<TestApi.GetResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getGet2Method(), responseObserver);
    }

    /**
     */
    public void get3(TestApi.GetRequest request,
        io.grpc.stub.StreamObserver<TestApi.GetResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getGet3Method(), responseObserver);
    }

    @Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getGetMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                TestApi.GetRequest,
                TestApi.GetResponse>(
                  this, METHODID_GET)))
          .addMethod(
            getGet2Method(),
            asyncUnaryCall(
              new MethodHandlers<
                TestApi.GetRequest,
                TestApi.GetResponse>(
                  this, METHODID_GET2)))
          .addMethod(
            getGet3Method(),
            asyncUnaryCall(
              new MethodHandlers<
                TestApi.GetRequest,
                TestApi.GetResponse>(
                  this, METHODID_GET3)))
          .build();
    }
  }

  /**
   */
  public static final class TestApiServiceStub extends io.grpc.stub.AbstractStub<TestApiServiceStub> {
    private TestApiServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private TestApiServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @Override
    protected TestApiServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new TestApiServiceStub(channel, callOptions);
    }

    /**
     */
    public void get(TestApi.GetRequest request,
        io.grpc.stub.StreamObserver<TestApi.GetResponse> responseObserver) {
      ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void get2(TestApi.GetRequest request,
        io.grpc.stub.StreamObserver<TestApi.GetResponse> responseObserver) {
      ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGet2Method(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void get3(TestApi.GetRequest request,
        io.grpc.stub.StreamObserver<TestApi.GetResponse> responseObserver) {
      ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGet3Method(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class TestApiServiceBlockingStub extends io.grpc.stub.AbstractStub<TestApiServiceBlockingStub> {
    private TestApiServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private TestApiServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @Override
    protected TestApiServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new TestApiServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public TestApi.GetResponse get(TestApi.GetRequest request) {
      return blockingUnaryCall(
          getChannel(), getGetMethod(), getCallOptions(), request);
    }

    /**
     */
    public TestApi.GetResponse get2(TestApi.GetRequest request) {
      return blockingUnaryCall(
          getChannel(), getGet2Method(), getCallOptions(), request);
    }

    /**
     */
    public TestApi.GetResponse get3(TestApi.GetRequest request) {
      return blockingUnaryCall(
          getChannel(), getGet3Method(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class TestApiServiceFutureStub extends io.grpc.stub.AbstractStub<TestApiServiceFutureStub> {
    private TestApiServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private TestApiServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @Override
    protected TestApiServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new TestApiServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<TestApi.GetResponse> get(
        TestApi.GetRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGetMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<TestApi.GetResponse> get2(
        TestApi.GetRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGet2Method(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<TestApi.GetResponse> get3(
        TestApi.GetRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGet3Method(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET = 0;
  private static final int METHODID_GET2 = 1;
  private static final int METHODID_GET3 = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final TestApiServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(TestApiServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET:
          serviceImpl.get((TestApi.GetRequest) request,
              (io.grpc.stub.StreamObserver<TestApi.GetResponse>) responseObserver);
          break;
        case METHODID_GET2:
          serviceImpl.get2((TestApi.GetRequest) request,
              (io.grpc.stub.StreamObserver<TestApi.GetResponse>) responseObserver);
          break;
        case METHODID_GET3:
          serviceImpl.get3((TestApi.GetRequest) request,
              (io.grpc.stub.StreamObserver<TestApi.GetResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class TestApiServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    TestApiServiceBaseDescriptorSupplier() {}

    @Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return TestApiOuterClass.getDescriptor();
    }

    @Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("TestApiService");
    }
  }

  private static final class TestApiServiceFileDescriptorSupplier
      extends TestApiServiceBaseDescriptorSupplier {
    TestApiServiceFileDescriptorSupplier() {}
  }

  private static final class TestApiServiceMethodDescriptorSupplier
      extends TestApiServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    TestApiServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (TestApiServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new TestApiServiceFileDescriptorSupplier())
              .addMethod(getGetMethod())
              .addMethod(getGet2Method())
              .addMethod(getGet3Method())
              .build();
        }
      }
    }
    return result;
  }
}
