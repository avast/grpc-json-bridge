# gRPC Json Bridge - Akka Http

The [gRPC Json Bridge](../README.md) integration with [Akka Http](https://doc.akka.io/docs/akka-http/current/server-side/index.html).

## Dependency

### Gradle
```groovy
compile 'com.avast.grpc:grpc-json-bridge-akkahttp_2.12:x.x.x'
```

### SBT
```scala
libraryDependencies += "com.avast.grpc" %% "grpc-json-bridge-akkahttp" % "x.x.x"
```

## Usage

```scala
import akka.http.scaladsl.server.Route
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import com.avast.grpc.jsonbridge.akkahttp.{AkkaHttp, Configuration}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.TestApiServiceImplBase

implicit val scheduler: monix.execution.Scheduler = ???

val bridge: GrpcJsonBridge[Task, TestApiServiceImplBase] = ??? // see core module docs for info about creating the bridge

val route: Route = AkkaHttp(Configuration.Default)(bridge)
```

See [official docs](https://doc.akka.io/docs/akka-http/current/routing-dsl/routes.html) for learn about following steps.

