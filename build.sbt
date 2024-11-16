import com.typesafe.tools.mima.core.*
import org.typelevel.scalacoptions.ScalacOptions

Global / onChangedBuildSource := ReloadOnSourceChanges

val logger: Logger = ConsoleLogger()

lazy val ScalaVersions = new {
  val V213 = "2.13.15"
  val V212 = "2.12.18"
  val V33 = "3.3.4"
}

lazy val Versions = new {
  val gpb3Version = "4.28.3"
  val grpcVersion = "1.68.1"
  val circeVersion = "0.14.10"
  val http4sVersion = "0.23.17"
  val akkaHttp = "10.5.3"
}

lazy val javaSettings = Seq(
  crossPaths := false,
  autoScalaLibrary := false
)

lazy val commonSettings = Seq(
  organization := "com.avast.grpc",
  homepage := Some(url("https://github.com/avast/grpc-json-bridge")),
  licenses ++= Seq("MIT" -> url(s"https://github.com/avast/grpc-json-bridge/blob/${version.value}/LICENSE")),
  developers := List(
    Developer(
      "jendakol",
      "Jenda Kolena",
      "jan.kolena@avast.com",
      url("https://github.com/jendakol")
    ),
    Developer(
      "augi",
      "Michal Augustýn",
      "michal.augustyn@avast.com",
      url("https://github.com/augi")
    ),
    Developer(
      "sideeffffect",
      "Ondra Pelech",
      "ondrej.pelech@avast.com",
      url("https://github.com/sideeffffect")
    )
  ),
  ThisBuild / turbo := true,
  scalaVersion := ScalaVersions.V33,
  crossScalaVersions := Seq(ScalaVersions.V212, ScalaVersions.V213),
  scalacOptions --= {
    if (!sys.env.contains("CI"))
      List("-Xfatal-warnings") // to enable Scalafix
    else
      List()
  },
  description := "Library for exposing gRPC endpoints via HTTP API",
  semanticdbEnabled := true, // enable SemanticDB
  semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
  ThisBuild / scalafixDependencies ++= List(
    "com.github.liancheng" %% "organize-imports" % "0.6.0",
    "com.github.vovapolu" %% "scaluzzi" % "0.1.23"
  ),
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.12.0",
    "javax.annotation" % "javax.annotation-api" % "1.3.2",
    "junit" % "junit" % "4.13.2" % Test,
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    "com.github.sbt" % "junit-interface" % "0.13.3" % Test, // Required by sbt to execute JUnit tests
    "ch.qos.logback" % "logback-classic" % "1.5.12" % Test
  ),
  missinglinkExcludedDependencies ++= List(
    moduleFilter(organization = "org.slf4j", name = "slf4j-api")
  ),
  mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet,
  testOptions += Tests.Argument(TestFrameworks.JUnit),
  Test / tpolecatExcludeOptions ++= Set(
    ScalacOptions.warnNonUnitStatement,
    ScalacOptions.warnValueDiscard
  )
) ++
  addCommandAlias("check", "; lint; +missinglinkCheck; +mimaReportBinaryIssues; +test") ++
  addCommandAlias(
    "lint",
    "; scalafmtSbtCheck; scalafmtCheckAll; compile:scalafix --check; test:scalafix --check"
  ) ++
  addCommandAlias("fix", "; compile:scalafix; test:scalafix; scalafmtSbt; scalafmtAll")

lazy val grpcTestGenSettings = inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings) ++ Seq(
  PB.protocVersion := "4.28.3",
  grpcExePath := xsbti.api.SafeLazy.strict {
    val exe: File = (Test / baseDirectory).value / ".bin" / grpcExeFileName
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
  Test / PB.protocOptions ++= Seq(
    s"--plugin=protoc-gen-java_rpc=${grpcExePath.value.get}",
    s"--java_rpc_out=${(Test / sourceManaged).value.getAbsolutePath}"
  ),
  Test / PB.targets := Seq(
    PB.gens.java -> (Test / sourceManaged).value
  )
)

lazy val grpcScalaPBTestGenSettings = inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings) ++ Seq(
  PB.protocVersion := "4.28.3",
  Test / PB.targets := Seq(
    scalapb.gen() -> (Test / sourceManaged).value
  )
)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    name := "grpc-json-bridge",
    publish / skip := true, // doesn't publish ivy XML files, in contrast to "publishArtifact := false"
    mimaReportBinaryIssues := {}
  )
  .aggregate(core, http4s, akkaHttp, coreScalaPB)

lazy val core = (project in file("core")).settings(
  commonSettings,
  grpcTestGenSettings,
  name := "grpc-json-bridge-core",
  libraryDependencies ++= Seq(
    "com.google.protobuf" % "protobuf-java" % Versions.gpb3Version,
    "com.google.protobuf" % "protobuf-java-util" % Versions.gpb3Version,
    "io.grpc" % "grpc-core" % Versions.grpcVersion,
    "io.grpc" % "grpc-protobuf" % Versions.grpcVersion,
    "io.grpc" % "grpc-stub" % Versions.grpcVersion,
    "io.grpc" % "grpc-inprocess" % Versions.grpcVersion,
    "org.typelevel" %% "cats-core" % "2.12.0",
    "org.typelevel" %% "cats-effect" % "3.5.5",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "org.slf4j" % "jul-to-slf4j" % "2.0.16",
    "org.slf4j" % "jcl-over-slf4j" % "2.0.16",
    "io.grpc" % "grpc-services" % Versions.grpcVersion % Test
  )
)

lazy val coreScalaPB = (project in file("core-scalapb"))
  .settings(
    name := "grpc-json-bridge-core-scalapb",
    commonSettings,
    grpcScalaPBTestGenSettings,
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.1",
      "junit" % "junit" % "4.13.2" % Test,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test, // Required by sbt to execute JUnit tests
      "ch.qos.logback" % "logback-classic" % "1.5.12" % Test,
      "io.grpc" % "grpc-services" % Versions.grpcVersion % Test,
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
    )
  )
  .dependsOn(core)

lazy val http4s = (project in file("http4s"))
  .settings(
    commonSettings,
    grpcTestGenSettings,
    name := "grpc-json-bridge-http4s",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % Versions.http4sVersion,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sVersion,
      "org.http4s" %% "http4s-circe" % Versions.http4sVersion,
      "io.circe" %% "circe-core" % Versions.circeVersion,
      "io.circe" %% "circe-generic" % Versions.circeVersion
    )
  )
  .dependsOn(core)

lazy val akkaHttp = (project in file("akka-http"))
  .settings(
    commonSettings,
    grpcTestGenSettings,
    name := "grpc-json-bridge-akkahttp",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp,
      "com.typesafe.akka" %% "akka-http-spray-json" % Versions.akkaHttp,
      "com.typesafe.akka" %% "akka-stream" % "2.8.8",
      "com.typesafe.akka" %% "akka-testkit" % "2.8.8" % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % Versions.akkaHttp % Test
    )
  )
  .dependsOn(core)

def grpcExeFileName: String = {
  val os =
    if (scala.util.Properties.isMac) "osx"
    else if (scala.util.Properties.isWin) "windows"
    else "linux"

  val arch =
    if (scala.util.Properties.propOrEmpty("os.arch") == "aarch64") "aarch_64"
    else "x86_64"

  s"$grpcArtifactId-${Versions.grpcVersion}-$os-$arch.exe"
}

val grpcArtifactId = "protoc-gen-grpc-java"
val grpcExeUrl = url(s"https://repo1.maven.org/maven2/io/grpc/$grpcArtifactId/${Versions.grpcVersion}/$grpcExeFileName")
val grpcExePath = SettingKey[xsbti.api.Lazy[File]]("grpcExePath")

addCommandAlias(
  "ci",
  "; check; +publishLocal"
)
