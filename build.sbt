import sbt.Keys.libraryDependencies

val logger: Logger = ConsoleLogger()

lazy val ScalaVersions = new {
  val V213 = "2.13.1"
  val V212 = "2.12.10"
}

crossScalaVersions := Seq(ScalaVersions.V212, ScalaVersions.V213)

lazy val Versions = new {
  val gpb3Version = "3.11.1"
  val grpcVersion = "1.28.1"
  val circeVersion = "0.13.0"
  val http4sVersion = "0.21.3"
  val akkaHttp = "10.1.11"
}

lazy val scalaSettings = Seq(
  scalaVersion := ScalaVersions.V213,
  scalacOptions += "-deprecation",
  scalacOptions += "-unchecked",
  scalacOptions += "-feature"
)

lazy val javaSettings = Seq(
  crossPaths := false,
  autoScalaLibrary := false
)

lazy val commonSettings = Seq(
  organization := "com.avast.grpc",
  version := sys.env.getOrElse("TRAVIS_TAG", "0.1-SNAPSHOT"),
  description := "Library for exposing gRPC endpoints via HTTP API",
  licenses ++= Seq("MIT" -> url(s"https://github.com/avast/grpc-json-bridge/blob/${version.value}/LICENSE")),
  publishArtifact in Test := false,
  publishArtifact in(Compile, packageDoc) := false,
  sources in(Compile, doc) := Seq.empty,
  bintrayOrganization := Some("avast"),
  bintrayPackage := "grpc-json-bridge",
  pomExtra := (
    <scm>
      <url>git@github.com:avast/
        {name.value}
        .git</url>
      <connection>scm:git:git@github.com:avast/
        {name.value}
        .git</connection>
    </scm>
      <developers>
        <developer>
          <id>avast</id>
          <name>Avast Software s.r.o.</name>
          <url>https://www.avast.com</url>
        </developer>
      </developers>
    ),
  resolvers += Resolver.jcenterRepo,
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.6",
    "javax.annotation" % "javax.annotation-api" % "1.3.2",
    "junit" % "junit" % "4.13" % "test",
    "org.scalatest" %% "scalatest" % "3.1.1" % "test",
    "com.novocode" % "junit-interface" % "0.11" % "test", // Required by sbt to execute JUnit tests
    "ch.qos.logback" % "logback-classic" % "1.2.3" % "test"
  ),
  testOptions += Tests.Argument(TestFrameworks.JUnit)
)

lazy val grpcTestGenSettings = inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings) ++ Seq(
  PB.protocVersion := "-v391",
  grpcExePath := xsbti.api.SafeLazy.strict {
    val exe: File = (baseDirectory in Test).value / ".bin" / grpcExeFileName
    if (!exe.exists) {
      logger.info("gRPC protoc plugin (for Java) does not exist. Downloading")
      //    IO.download(grpcExeUrl, exe)
      IO.transfer(grpcExeUrl.openStream(), exe)
      exe.setExecutable(true)
    } else {
      logger.debug("gRPC protoc plugin (for Java) exists")
    }
    exe
  },
  PB.protocOptions in Test ++= Seq(
    s"--plugin=protoc-gen-java_rpc=${grpcExePath.value.get}",
    s"--java_rpc_out=${(sourceManaged in Test).value.getAbsolutePath}"
  ),
  PB.targets in Test := Seq(
    PB.gens.java -> (sourceManaged in Test).value
  )
)

lazy val grpcScalaPBTestGenSettings = inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings) ++ Seq(
  PB.protocVersion := "-v391",
  PB.targets in Test := Seq(
    scalapb.gen() -> (sourceManaged in Test).value
  )
)

lazy val root = (project in file("."))
  .settings(
    name := "grpc-json-bridge",
    publish := {},
    publishLocal := {}
  )
  .aggregate(core, http4s, akkaHttp, coreScalaPB)

lazy val core = (project in file("core")).settings(
  commonSettings,
  scalaSettings,
  grpcTestGenSettings,
  name := "grpc-json-bridge-core",
  libraryDependencies ++= Seq(
    "com.google.protobuf" % "protobuf-java" % Versions.gpb3Version,
    "com.google.protobuf" % "protobuf-java-util" % Versions.gpb3Version,
    "io.grpc" % "grpc-core" % Versions.grpcVersion,
    "io.grpc" % "grpc-protobuf" % Versions.grpcVersion,
    "io.grpc" % "grpc-stub" % Versions.grpcVersion,
    "org.typelevel" %% "cats-core" % "2.1.1",
    "org.typelevel" %% "cats-effect" % "2.1.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
    "org.slf4j" % "jul-to-slf4j" % "1.7.30",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.30",
    "io.grpc" % "grpc-services" % Versions.grpcVersion % "test"
  )
)

lazy val coreScalaPB = (project in file("core-scalapb")).settings(
  name := "grpc-json-bridge-core-scalapb",
  commonSettings,
  scalaSettings,
  grpcScalaPBTestGenSettings,
  libraryDependencies ++= Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
    "com.thesamet.scalapb" %% "scalapb-json4s" % "0.10.1",
    "junit" % "junit" % "4.13" % "test",
    "org.scalatest" %% "scalatest" % "3.1.1" % "test",
    "com.novocode" % "junit-interface" % "0.11" % "test", // Required by sbt to execute JUnit tests
    "ch.qos.logback" % "logback-classic" % "1.2.3" % "test",
    "io.grpc" % "grpc-services" % Versions.grpcVersion % "test",
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf")
).dependsOn(core)

lazy val http4s = (project in file("http4s")).settings(
  commonSettings,
  scalaSettings,
  grpcTestGenSettings,
  name := "grpc-json-bridge-http4s",
  libraryDependencies ++= Seq(
    "org.http4s" %% "http4s-dsl" % Versions.http4sVersion,
    "org.http4s" %% "http4s-blaze-server" % Versions.http4sVersion,
    "org.http4s" %% "http4s-circe" % Versions.http4sVersion,
    "io.circe" %% "circe-core" % Versions.circeVersion,
    "io.circe" %% "circe-generic" % Versions.circeVersion
  ),
  scalacOptions ++= { if (scalaVersion.value == ScalaVersions.V212) Seq("-Ypartial-unification") else Seq.empty }
).dependsOn(core)

lazy val akkaHttp = (project in file("akka-http")).settings(
  commonSettings,
  scalaSettings,
  grpcTestGenSettings,
  name := "grpc-json-bridge-akkahttp",
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp,
    "com.typesafe.akka" %% "akka-http-spray-json" % Versions.akkaHttp,
    "com.typesafe.akka" %% "akka-stream" % "2.6.4",
    "com.typesafe.akka" %% "akka-testkit" % "2.6.4" % "test",
    "com.typesafe.akka" %% "akka-http-testkit" % Versions.akkaHttp % "test"
  ),
).dependsOn(core)

def grpcExeFileName: String = {
  val os = if (scala.util.Properties.isMac) {
    "osx-x86_64"
  } else if (scala.util.Properties.isWin) {
    "windows-x86_64"
  } else {
    "linux-x86_64"
  }
  s"$grpcArtifactId-${Versions.grpcVersion}-$os.exe"
}

val grpcArtifactId = "protoc-gen-grpc-java"
val grpcExeUrl = url(s"https://repo1.maven.org/maven2/io/grpc/$grpcArtifactId/${Versions.grpcVersion}/$grpcExeFileName")
val grpcExePath = SettingKey[xsbti.api.Lazy[File]]("grpcExePath")
