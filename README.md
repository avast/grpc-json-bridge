# gRPC JSON Bridge

[![Build Status](https://travis-ci.org/avast/grpc-json-bridge.svg?branch=master)](https://travis-ci.org/avast/grpc-json-bridge)
[![Release Artifacts][Badge-SonatypeReleases]][Link-SonatypeReleases]
[![Snapshot Artifacts][Badge-SonatypeSnapshots]][Link-SonatypeSnapshots]

This library allows to make a JSON encoded HTTP request to a gRPC service that is implemented in Java or Scala (using ScalaPB).

It provides an implementation-agnostic module for mapping to your favorite HTTP server (`core`, `core-scalapb`) as well as few implementations for direct usage in some well-known HTTP servers.  

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

This usage supposes that the project uses standard gRPC Java class generation, using [protoc-gen-grpc-java](https://github.com/grpc/grpc-java/tree/master/compiler).

If the project uses [ScalaPB](https://scalapb.github.io/grpc.html) to generate the classes then following dependency should used instead:
```groovy
compile 'com.avast.grpc:grpc-json-bridge-core-scalapb_2.12:x.x.x'
```
```scala
libraryDependencies += "com.avast.grpc" %% "grpc-json-bridge-core-scalapb" % "x.x.x"
```
And `ScalaPBReflectionGrpcJsonBridge` class must be used to create a new bridge.

### http4s
```groovy
compile 'com.avast.grpc:grpc-json-bridge-http4s_2.12:x.x.x'
```
```scala
libraryDependencies += "com.avast.grpc" %% "grpc-json-bridge-http4s" % "x.x.x"
```
```scala
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import com.avast.grpc.jsonbridge.http4s.{Configuration, Http4s}
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

[Link-SonatypeReleases]: https://search.maven.org/artifact/com.avast.grpc/grpc-json-bridge-core_2.13 "Sonatype Releases"
[Link-SonatypeSnapshots]: https://oss.sonatype.org/content/repositories/snapshots/com/avast/grpc/grpc-json-bridge-core_2.13/ "Sonatype Snapshots"

[Badge-SonatypeReleases]: https://img.shields.io/nexus/r/https/oss.sonatype.org/com.avast.grpc/grpc-json-bridge-core_2.13.svg "Sonatype Releases"
[Badge-SonatypeSnapshots]: https://img.shields.io/nexus/s/https/oss.sonatype.org/com.avast.grpc/grpc-json-bridge-core_2.13.svg "Sonatype Snapshots"
