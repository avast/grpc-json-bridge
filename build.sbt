import com.typesafe.tools.mima.core._

Global / onChangedBuildSource := ReloadOnSourceChanges

val logger: Logger = ConsoleLogger()

lazy val ScalaVersions = new {
  val V213 = "2.13.6"
  val V212 = "2.12.14"
}

lazy val Versions = new {
  val gpb3Version = "3.17.3"
  val grpcVersion = "1.40.1"
  val circeVersion = "0.14.1"
  val http4sVersion = "0.22.2"
  val akkaHttp = "10.2.6"
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
  scalaVersion := ScalaVersions.V213,
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
  ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value),
  ThisBuild / scalafixDependencies ++= List(
    "com.github.liancheng" %% "organize-imports" % "0.5.0",
    "com.github.vovapolu" %% "scaluzzi" % "0.1.20"
  ),
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.5.0",
    "javax.annotation" % "javax.annotation-api" % "1.3.2",
    "junit" % "junit" % "4.13.2" % Test,
    "org.scalatest" %% "scalatest" % "3.2.10" % Test,
    "com.novocode" % "junit-interface" % "0.11" % Test, // Required by sbt to execute JUnit tests
    "ch.qos.logback" % "logback-classic" % "1.2.6" % Test
  ),
  missinglinkExcludedDependencies ++= List(
    moduleFilter(organization = "org.slf4j", name = "slf4j-api")
  ),
  mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet,
  testOptions += Tests.Argument(TestFrameworks.JUnit)
) ++
  addCommandAlias("check", "; lint; +missinglinkCheck; +mimaReportBinaryIssues; +test") ++
  addCommandAlias(
    "lint",
    "; scalafmtSbtCheck; scalafmtCheckAll; compile:scalafix --check; test:scalafix --check"
  ) ++
  addCommandAlias("fix", "; compile:scalafix; test:scalafix; scalafmtSbt; scalafmtAll")

lazy val grpcTestGenSettings = inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings) ++ Seq(
  PB.protocVersion := "3.9.1",
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
  PB.protocVersion := "3.9.1",
  PB.targets in Test := Seq(
    scalapb.gen() -> (sourceManaged in Test).value
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
    "org.typelevel" %% "cats-core" % "2.6.1",
    "org.typelevel" %% "cats-effect" % "2.5.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    "org.slf4j" % "jul-to-slf4j" % "1.7.32",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.32",
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
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.0",
      "junit" % "junit" % "4.13.2" % Test,
      "org.scalatest" %% "scalatest" % "3.2.10" % Test,
      "com.novocode" % "junit-interface" % "0.11" % Test, // Required by sbt to execute JUnit tests
      "ch.qos.logback" % "logback-classic" % "1.2.6" % Test,
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
      "com.typesafe.akka" %% "akka-stream" % "2.6.16",
      "com.typesafe.akka" %% "akka-testkit" % "2.6.16" % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % Versions.akkaHttp % Test
    )
  )
  .dependsOn(core)

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

addCommandAlias(
  "ci",
  "; check; +publishLocal"
)
