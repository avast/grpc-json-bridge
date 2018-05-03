# gRPC Json Bridge - http4s

The [gRPC Json Bridge](../README.md) integration with [http4s](https://http4s.org/).

## Dependency

### Gradle
```groovy
compile 'com.avast.grpc:grpc-json-bridge-http4s_2.12:x.x.x'
```

### Gradle
```scala
libraryDependencies += "com.avast.grpc" %% "grpc-json-bridge-http4s" % "x.x.x"
```


## Usage

```scala
import cats.effect.IO
import com.avast.grpc.jsonbridge.GrpcJsonBridge
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.TestApiServiceImplBase
import com.avast.grpc.jsonbrige.http4s.{Configuration, Http4s}
import org.http4s.HttpService

val bridge: GrpcJsonBridge[TestApiServiceImplBase] = ??? // see core module docs for info about creating the bridge

val service: HttpService[IO] = Http4s(Configuration.Default)(bridge)
```

See [official docs](https://http4s.org/v0.18/dsl/) for learn about following steps.
