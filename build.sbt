import sbt.CrossVersion
import sbt.Keys.libraryDependencies

val logger: Logger = ConsoleLogger()

crossScalaVersions := Seq("2.12.8")

lazy val Versions = new {
  val gpb3Version = "3.8.0"
  val grpcVersion = "1.22.1"
  val circeVersion = "0.11.1"
  val http4sVersion = "0.20.6"
  val akkaHttp = "10.1.5" // DO NOT upgrade to 10.1.[67] - will cause https://github.com/scala/community-builds/issues/825
}

lazy val scalaSettings = Seq(
  scalaVersion := "2.12.8",
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
  description := "Library for exposing gRPC endpoints via REST api",
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
    "junit" % "junit" % "4.12" % "test",
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "com.novocode" % "junit-interface" % "0.10" % "test", // Required by sbt to execute JUnit tests
    "ch.qos.logback" % "logback-classic" % "1.2.3" % "test"
  ),
  testOptions += Tests.Argument(TestFrameworks.JUnit)
)

lazy val grpcTestGenSettings = inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings) ++ Seq(
  PB.protocVersion := "-v371",
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

lazy val root = (project in file("."))
  .settings(
    name := "grpc-json-bridge",
    publish := {},
    publishLocal := {}
  )
  .aggregate(core, http4s, akkaHttp)

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
    "org.typelevel" %% "cats-core" % "1.6.0",
    "org.typelevel" %% "cats-effect" % "1.3.0",
    "com.kailuowang" %% "mainecoon-core" % "0.6.4",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
    "org.slf4j" % "jul-to-slf4j" % "1.7.26",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.26",
    "io.grpc" % "grpc-services" % Versions.grpcVersion % "test"
  )
)

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
  scalacOptions += "-Ypartial-unification"
).dependsOn(core)

lazy val akkaHttp = (project in file("akka-http")).settings(
  commonSettings,
  scalaSettings,
  grpcTestGenSettings,
  name := "grpc-json-bridge-akkahttp",
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp,
    "com.typesafe.akka" %% "akka-stream" % "2.5.21",
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
val grpcExeUrl = url(s"http://repo1.maven.org/maven2/io/grpc/$grpcArtifactId/${Versions.grpcVersion}/$grpcExeFileName")
val grpcExePath = SettingKey[xsbti.api.Lazy[File]]("grpcExePath")
