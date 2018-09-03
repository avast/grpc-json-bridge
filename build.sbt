import sbt.CrossVersion
import sbt.Keys.libraryDependencies

val logger: Logger = ConsoleLogger()

crossScalaVersions := Seq("2.12.6")

lazy val Versions = new {
  val gpb3Version = "3.6.1"
  val grpcVersion = "1.14.0"

  val akkaHttp = "10.1.3"
}

lazy val scalaSettings = Seq(
  scalaVersion := "2.12.6",
  scalacOptions += "-deprecation",
  scalacOptions += "-unchecked",
  scalacOptions += "-feature"
)

lazy val javaSettings = Seq(
  crossPaths := false,
  autoScalaLibrary := false
)

lazy val macroSettings = Seq(
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full),
  addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.7" cross CrossVersion.binary),
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    "org.scala-lang" % "scala-compiler" % scalaVersion.value
  )
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
  PB.protocVersion := "-v350",
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
  macroSettings,
  scalaSettings,
  grpcTestGenSettings,
  name := "grpc-json-bridge-core",
  libraryDependencies ++= Seq(
    "com.google.protobuf" % "protobuf-java" % Versions.gpb3Version,
    "com.google.protobuf" % "protobuf-java-util" % Versions.gpb3Version,
    "io.grpc" % "grpc-protobuf" % Versions.grpcVersion,
    "io.grpc" % "grpc-stub" % Versions.grpcVersion,
    "org.typelevel" %% "cats-core" % "1.2.0",
    "io.monix" %% "monix" % "3.0.0-RC1",
    "com.kailuowang" %% "mainecoon-core" % "0.6.4",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
    "org.slf4j" % "jul-to-slf4j" % "1.7.25",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.25",
    "io.grpc" % "grpc-services" % Versions.grpcVersion % "test",
    "com.avast.cactus" %% "cactus-grpc-server" % "0.11.2" % "test"
  )
)

lazy val http4s = (project in file("http4s")).settings(
  commonSettings,
  macroSettings,
  scalaSettings,
  grpcTestGenSettings,
  name := "grpc-json-bridge-http4s",
  libraryDependencies ++= Seq(
    "org.http4s" %% "http4s-dsl" % "0.18.15",
    "org.http4s" %% "http4s-blaze-server" % "0.18.15"
  ),
  scalacOptions += "-Ypartial-unification"
).dependsOn(core)

lazy val akkaHttp = (project in file("akka-http")).settings(
  commonSettings,
  macroSettings,
  scalaSettings,
  grpcTestGenSettings,
  name := "grpc-json-bridge-akkahttp",
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp,
    "com.typesafe.akka" %% "akka-stream" % "2.5.14",
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