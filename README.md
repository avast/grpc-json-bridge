# gRPC JSON Bridge

[![Build Status](https://travis-ci.org/avast/grpc-json-bridge.svg?branch=master)](https://travis-ci.org/avast/grpc-json-bridge)
[![Download](https://api.bintray.com/packages/avast/maven/grpc-json-bridge/images/download.svg) ](https://bintray.com/avast/maven/grpc-json-bridge/_latestVersion)

This library allows to make a JSON encoded request to a gRPC service. It provides an implementation-agnostic module for mapping to your favorite HTTP server (`core`) as well as few implementations for direct usage in some well-known HTTP servers.  

[Standard GPB <-> JSON mapping](https://developers.google.com/protocol-buffers/docs/proto3#json) is used.

The API is _finally tagless_ (read more e.g. [here](https://www.beyondthelines.net/programming/introduction-to-tagless-final/)) meaning it can use whatever [`F[_]: cats.effect.Effect`](https://typelevel.org/cats-effect/typeclasses/effect.html) (e.g. `cats.effect.IO`, `monix.eval.Task`).

## Usage

```groovy
compile 'com.avast.grpc:grpc-json-bridge-core_2.12:x.x.x'
```
```scala
libraryDependencies += "com.avast.grpc" %% "grpc-json-bridge-core" % "x.x.x"
```

```proto
syntax = "proto3";
package com.avast.grpc.jsonbridge.test;

service TestService {
    rpc Add (AddParams) returns (AddResponse) {}
}

message AddParams {
    int32 a = 1;
    int32 b = 2;
}

message AddResponse {
    int32 sum = 1;
}
```
```scala
import com.avast.grpc.jsonbridge.ReflectionGrpcJsonBridge

// for whole server
val grpcServer: io.grpc.Server = ???
val bridge = ReflectionGrpcJsonBridge.createFromServer[Task](grpcServer)

// or for selected services
val s1: ServerServiceDefinition = ???
val s2: ServerServiceDefinition = ???
val anotherBridge = ReflectionGrpcJsonBridge.createFromServices[Task](s1, s2)

// call a method manually, with a header specified
val jsonResponse = bridge.invoke("com.avast.grpc.jsonbridge.test.TestService/Add", """ { "a": 1, "b": 2} """, Map("My-Header" -> "value"))
```

### http4s
```groovy
compile 'com.avast.grpc:grpc-json-bridge-http4s_2.12:x.x.x'
```
```scala
libraryDependencies += "com.avast.grpc" %% "grpc-json-bridge-http4s" % "x.x.x"
```
```scala
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import com.avast.grpc.jsonbrige.http4s.{Configuration, Http4s}
import org.http4s.HttpService

val bridge: GrpcJsonBridge[Task] = ???
val service: HttpService[Task] = Http4s(Configuration.Default)(bridge)
```

### akka-http
```groovy
compile 'com.avast.grpc:grpc-json-bridge-akkahttp_2.12:x.x.x'
```
```scala
libraryDependencies += "com.avast.grpc" %% "grpc-json-bridge-akkahttp" % "x.x.x"
```

```scala
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import com.avast.grpc.jsonbridge.akkahttp.{AkkaHttp, Configuration}
import akka.http.scaladsl.server.Route

val bridge: GrpcJsonBridge[Task] = ???
val route: Route = AkkaHttp(Configuration.Default)(bridge)
```


### Calling the bridged service
List all available methods:
```
> curl -X GET http://localhost:9999/

com.avast.grpc.jsonbridge.test.TestService/Add
```
List all methods from particular service:
```
> curl -X GET http://localhost:9999/com.avast.grpc.jsonbridge.test.TestService

com.avast.grpc.jsonbridge.test.TestService/Add
```

Call a method (please note that `POST` and `application/json` must be always specified):
```
> curl -X POST -H "Content-Type: application/json" --data ""{\"a\":1, \"b\": 2 }"" http://localhost:9999/com.avast.grpc.jsonbridge.test.TestService/Add

{"sum":3}
```
