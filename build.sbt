import com.typesafe.sbt.packager.docker.{Cmd, DockerChmodType, ExecCmd}

ThisBuild / version := "0.2.13-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.9"

lazy val courierVersion  = "3.0.1"
lazy val akkaHttpVersion = "10.6.3"
lazy val akkaVersion     = "2.9.3"
lazy val alpakkaVersion  = "8.0.0"
lazy val circeVersion    = "0.14.3"
lazy val akkaHttpCirceVersion    = "1.39.2"
lazy val slickVersion = "3.5.1"

val dockerVersion      = "v0.2.13"

lazy val scalaxbSettings = Seq(
  Compile / scalaxbJaxbPackage := JaxbPackage.Jakarta,
  Compile / scalaxb / scalaxbPackageName := "generated",
  Compile / scalaxb / scalaxbPackageNames := Map(
    uri("http://xp.ponton.de/eda/v320") -> "ponton",
//    uri("http://www.ebutilities.at/datenplattform/0700") -> "dataplatform",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmnotification/01p11") -> "cmnotification.v01p11",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmnotification/01p12") -> "cmnotification.v01p12",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmnotification/01p20") -> "cmnotification.v01p20",
    uri("http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p00") -> "ecmplist.v01p00",
    uri("http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p10") -> "ecmplist.v01p10",
    uri("http://www.ebutilities.at/schemata/customerprocesses/gc/gcrequestap/01p00") -> "gcrequestap.v01p00",
    uri("http://www.ebutilities.at/schemata/customerprocesses/gc/gcrequest/01p00") -> "gcrequest.v01p00",
    uri("http://www.ebutilities.at/schemata/customerprocesses/consumptionrecord/01p30") -> "consumptionrecord.v01p30",
    uri("http://www.ebutilities.at/schemata/customerprocesses/consumptionrecord/01p31") -> "consumptionrecord.v01p31",
    uri("http://www.ebutilities.at/schemata/customerprocesses/consumptionrecord/01p40") -> "consumptionrecord.v01p40",
    uri("http://www.ebutilities.at/schemata/customerprocesses/consumptionrecord/01p41") -> "consumptionrecord.v01p41",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p10") -> "cmrequest.v01p10",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20") -> "cmrequest.v01p20",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p21") -> "cmrequest.v01p21",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p30") -> "cmrequest.v01p30",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmrevoke/01p00") -> "cmrevoke.v01p00",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmrevoke/01p10") -> "cmrevoke.v01p10",
    uri("http://www.ebutilities.at/schemata/customerprocesses/cpnotification/01p13") -> "cpnotification.v01p13",
    uri("http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12") -> "cprequest.v01p12",
    uri("http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20") -> "commontypes.v01p20",
    uri("http://www.ebutilities.at/schemata/customerprocesses/cpdocument/01p12") -> "cpdocument.v01p12",
  ),
  Compile / scalaxb / scalaxbPrependFamily := false,
  Compile / scalaxb / scalaxbJaxbPackage := JaxbPackage.Jakarta,
  Compile / scalaxb / scalaxbGenerateDispatchClient := false,
  Compile / scalaxb / scalaxbGenerateHttp4sClient := false,
  //  Compile / scalaxb / scalaxbProtocolPackageName := Some("sepp")
)

lazy val dockerSettings = Seq(
//  Docker / packageName := "eda-xp-connector",
//  Docker / maintainer := "vfeeg <vfeeg.org>",
//  Docker / version := dockerVersion,

  packageName := "eda-xp-connector",
  maintainer := "vfeeg <vfeeg.org>",
  version := dockerVersion,
  dockerBaseImage := "eclipse-temurin:17-jre",
  dockerExposedVolumes := Seq("/conf", "/storage/prod"),
  dockerRepository := Some("ghcr.io"),
  dockerUsername := Some("vfeeg-development"),
//  dockerUpdateLatest := true,
  dockerExposedPorts := Seq(6090, 9093),
  dockerCommands := dockerCommands.value.filterNot {
    case ExecCmd("ENTRYPOINT", _) => true
    case cmd => false
  },
  dockerEnvVars := Map("TZ" -> "Europe/Vienna", "JAVA_OPTS" -> "-Xmx4g"),
  dockerCommands ++= Seq(
    Cmd("LABEL", s"""version="${dockerVersion}""""),
    ExecCmd("CMD", "/opt/docker/bin/xpadapter", "-Dconfig.file=/conf/application.conf")
  ),
  dockerChmodType := DockerChmodType.UserGroupWriteExecute,
  dockerAliases ++= {
    val repo = dockerRepository.value

    Seq(
//      DockerAlias(repo, Some("vfeeg-development"), name, Some(dockerVersion)),      // e.g. ghcr.io/eegfaktura/app:1.0.0
//      DockerAlias(repo, Some("vfeeg-development"), name, Some("latest")),
      DockerAlias(repo, Some("eegfaktura"), "eegfaktura-kep", Some(dockerVersion)),
      DockerAlias(repo, Some("eegfaktura"), "eegfaktura-kep", Some("latest")),
    )
  }
)

lazy val root = (project in file("."))
  .enablePlugins(ScalaxbPlugin)
  .enablePlugins(AkkaGrpcPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(scalaxbSettings)
  .settings(dockerSettings)
  .settings(
    name := "XPAdapter",

    resolvers += "repo.jenkins-ci.org" at "https://repo.jenkins-ci.org/releases",
    resolvers += "Akka library repository" at "https://repo.akka.io/maven",

    libraryDependencies ++= Seq(
      "com.github.daddykotex" %% "courier" % courierVersion,
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "de.heikoseeberger" %% "akka-http-circe" % akkaHttpCirceVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
//      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson"  % akkaVersion,
      "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion,
      "com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % alpakkaVersion,
      "com.lightbend.akka" %% "akka-stream-alpakka-mqtt-streaming" % alpakkaVersion,
      "jakarta.xml.bind" % "jakarta.xml.bind-api" % "4.0.2",
//      "org.http4s" %% "http4s-core" % "1.0.0-m38",

      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      "org.slf4j" % "jcl-over-slf4j" % "2.0.13",

      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",

      "javax.xml.bind" % "jaxb-api" % "2.3.0",
      "com.sun.xml.bind" % "jaxb-core" % "2.3.0",
      "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
//      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",

      "com.google.guava" % "guava" % "33.2.1-jre"
    ),

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19",
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion,
      "org.jvnet.mock-javamail" % "mock-javamail" % "1.12",
      "com.typesafe.slick" %% "slick-testkit" % slickVersion,
      "com.h2database" % "h2" % "2.2.224",
      "com.opentable.components" % "otj-pg-embedded" % "0.13.3",
      "org.flywaydb" % "flyway-core" % "7.2.0",
      "org.mockito" %% "mockito-scala" % "1.17.31",
      "org.scalamock" %% "scalamock" % "6.0.0",
      "com.typesafe.slick" %% "slick-testkit" % slickVersion,
      "io.moquette"      % "moquette-broker"  % "0.17",
    ).map(_ % Test),

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion),

    libraryDependencies ++= Seq(
      "com.typesafe.slick" %% "slick" % slickVersion,
      "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
      "org.postgresql" % "postgresql" % "42.7.3",
      "com.github.tminglei" %% "slick-pg" % "0.22.2",
      "com.github.tminglei" %% "slick-pg_circe-json" % "0.22.2"
    ),

    libraryDependencies ++= Seq(
      "org.flywaydb" % "flyway-core" % "10.20.0",
      "org.flywaydb" % "flyway-database-postgresql" % "10.20.0"
    ),

    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s", "-a")
  )