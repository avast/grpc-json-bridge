# gRPC JSON Bridge

[![Build Status](https://travis-ci.org/avast/grpc-json-bridge.svg?branch=master)](https://travis-ci.org/avast/grpc-json-bridge)
[![Download](https://api.bintray.com/packages/avast/maven/grpc-json-bridge/images/download.svg) ](https://bintray.com/avast/maven/grpc-json-bridge/_latestVersion)

This library makes possible to receive a JSON encoded request to a gRPC service. It provides an implementation-agnostic module for mapping to
your favorite HTTP server as well as few implementations for direct usage in some well-known HTTP servers.  
For requests/responses mapping a [standard GPB <-> JSON mapping](https://developers.google.com/protocol-buffers/docs/proto3#json) is used.

It uses Scala macros for creating mapping between runtime-provided service and method names to pregenerated Java gRPC classes. In case you
don't want to use _plain Java API_ you can easily use it together with [Cactus](https://github.com/avast/cactus).

There are several modules:
1. core - for basic implementation-agnostic usage
1. [http4s](http4s) - integration with [http4s](https://http4s.org/) webserver
1. [akka-http](akka-http) - integration with [Akka Http](https://doc.akka.io/docs/akka-http/current/server-side/index.html) webserver

The created [`GrpcJsonBridge`](core/src/main/scala/com/avast/grpc/jsonbridge/GrpcJsonBridge.scala) exposes not only the methods itself but
also provides their list to make possible to implement an _info_ endpoint (which is already implemented in server-agnostic implementations).

Recommended URL pattern for exposing the service (and the one used in provided implementations) is `/$SERVICENAME/$METHOD` name and the http
method is obviously `POST`. The _info_ endpoint is supposed to be exposed on the `/$SERVICENAME` URL and available with `GET` request.

## Core module

### Dependency

#### Gradle
```groovy
compile 'com.avast.grpc:grpc-json-bridge-akkahttp_2.12:x.x.x'
```

#### Gradle
```scala
libraryDependencies += "com.avast.grpc" %% "grpc-json-bridge-core" % "x.x.x"
```

### Usage

Having a proto like
```proto
option java_package = "com.avast.grpc.jsonbridge.test";

message TestApi {
    message GetRequest {
        repeated string names = 1;           // REQUIRED
    }
    
    message GetResponse {
        map<string, int32> results = 1;      // REQUIRED
    }
}

service TestApiService {
    rpc Get (TestApi.GetRequest) returns (TestApi.GetResponse) {}
}
```
you can create [`GrpcJsonBridge`](core/src/main/scala/com/avast/grpc/jsonbridge/GrpcJsonBridge.scala) instance by
```scala
import com.avast.grpc.jsonbridge._ // this does the magic!

import scala.concurrent.ExecutionContextExecutorService
import com.avast.grpc.jsonbridge.test.TestApi
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.stub.StreamObserver

implicit val executor: ExecutionContextExecutorService = ???

val bridge = new TestApiServiceImplBase {
  override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
    responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
    responseObserver.onCompleted()
  }
}.createGrpcJsonBridge[TestApiServiceFutureStub]() // this does the magic!
```
or you can even go with the [Cactus](https://github.com/avast/cactus) and let it map the GPB messages to your case classes:
```scala
import com.avast.grpc.jsonbridge._ // import for the grpc-json-bridge mapping
import com.avast.cactus.grpc.server._ // import for the cactus mapping

import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutorService, Future}

implicit val executor: ExecutionContextExecutorService = ???

case class MyRequest(names: Seq[String])

case class MyResponse(results: Map[String, Int])

trait MyApi {
  def get(request: MyRequest): Future[Either[Status, MyResponse]]
}

val service = new MyApi {
  override def get(request: MyRequest): Future[Either[Status, MyResponse]] = Future.successful {
    Right {
      MyResponse {
        Map(
          "name" -> 42
        )
      }
    }
  }
}.mappedToService[TestApiServiceImplBase]() // cactus mapping

val bridge = service.createGrpcJsonBridge[TestApiServiceFutureStub]()
```

### Calling the bridged service

You can use e.g. cURL command to call the `Get` method

```
curl -X POST -H "Content-Type: application/json" --data " { \"names\": [\"abc\",\"def\"] } " http://localhost:9999/com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.TestApiServiceImplBase/Get
```
or get info about exposed service:
```
curl -X GET http://localhost:9999/com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.TestApiServiceImplBase
```
