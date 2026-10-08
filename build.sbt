import com.typesafe.sbt.packager.docker.{Cmd, DockerChmodType, ExecCmd}

ThisBuild / version := "v0.2.22"

ThisBuild / scalaVersion := "2.13.18"

lazy val courierVersion  = "3.0.1"
// Migration 2026-06-13: Akka (BSL 1.1) -> Apache Pekko (Apache 2.0).
// Pekko 1.2.x = Apache-Fork von Akka 2.7-API. Maven-Central, kein Lightbend-Resolver.
lazy val pekkoVersion             = "1.2.1"
lazy val pekkoHttpVersion         = "1.2.0"
lazy val pekkoConnectorsVersion   = "1.1.0"
lazy val circeVersion             = "0.14.3"
lazy val slickVersion             = "3.5.1"
lazy val scalaXmlVersion          = "2.3.0"

lazy val scalaxbSettings = Seq(
  Compile / scalaxbJaxbPackage := JaxbPackage.Jakarta,
  Compile / scalaxb / scalaxbPackageName := "generated",
  Compile / scalaxb / scalaxbPackageNames := Map(
    uri("http://xp.ponton.de/eda/v320") -> "ponton",
    uri("http://www.ebutilities.at/datenplattform/0700") -> "dataplatform",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmnotification/01p11") -> "cmnotification.v01p11",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmnotification/01p12") -> "cmnotification.v01p12",
    uri("http://www.ebutilities.at/schemata/customerconsent/cmnotification/01p20") -> "cmnotification.v01p20",
    uri("http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p00") -> "ecmplist.v01p00",
    uri("http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p10") -> "ecmplist.v01p10",
    uri("http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p20") -> "ecmplist.v01p20",
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
    Cmd("LABEL", s"""version="${ThisBuild / version}""""),
    ExecCmd("CMD", "/opt/docker/bin/xpadapter", "-Dconfig.file=/conf/application.conf")
  ),
  dockerChmodType := DockerChmodType.UserGroupWriteExecute,
  dockerAliases ++= {
    val repo = dockerRepository.value

    // Die uebrigen Repos erzeugen ihre Image-Tags ueber docker/metadata-action und
    // bekommen dadurch immer ein eindeutiges `sha-<short>`. Hier baut sbt-native-packager,
    // das dieses Tag nicht kennt — Preview- und Env-Deploy (ADR-0007/0008) pinnen aber
    // genau darauf und liefen deshalb in ImagePullBackOff. Also selbst erzeugen.
    val shaAlias = sys.env.get("GITHUB_SHA").filter(_.nonEmpty).toSeq.map { sha =>
      DockerAlias(repo, Some("vfeeg-development"), "eegfaktura-kep", Some("sha-" + sha.take(7)))
    }

    // `latest` und das Versions-Tag sind wandernd: die Dev-Zone zieht `latest`. Ein Build
    // aus einem preview/**- oder env/**-Branch darf sie deshalb NICHT ueberschreiben,
    // sonst laeuft die Dev-Zone unbemerkt auf einem Feature-Stand (passiert am 26.09.).
    val ref = sys.env.getOrElse("GITHUB_REF", "")
    val isFeatureBranchBuild =
      ref.startsWith("refs/heads/preview/") || ref.startsWith("refs/heads/env/")

    val movingAliases =
      if (isFeatureBranchBuild) Seq.empty
      else Seq(
        DockerAlias(repo, Some("vfeeg-development"), "eegfaktura-kep", Some((ThisBuild / version).value)),
        DockerAlias(repo, Some("vfeeg-development"), "eegfaktura-kep", Some("latest")),
      )

    movingAliases ++ shaAlias
  }
)

// Test environment (docs: scripts/dev/test.sh). One forked JVM, suites one after another, fixed zone,
// the test configuration by resource name (ScalaTestWithActorTestKit loads `application-test` by name),
// every file a test writes under target/. EDA_XP_EXTRA_TESTS adds a private test directory for one run.
lazy val testSettings = Seq(
  Test / fork := true,
  Test / parallelExecution := false,
  Test / testForkedParallel := false,
  Test / javaOptions ++= Seq(
    "-Duser.timezone=Europe/Vienna",
    "-Dconfig.resource=application-test.conf",
    s"-Djava.io.tmpdir=${(ThisBuild / baseDirectory).value / "target" / "test-tmp"}",
    s"-Deda.test.storage=${(ThisBuild / baseDirectory).value / "target" / "test-storage"}",
  ) ++ sys.props.get("eda.golden.update").map(v => s"-Deda.golden.update=$v").toSeq,
  Test / unmanagedSourceDirectories ++= sys.env.get("EDA_XP_EXTRA_TESTS").map(file).toSeq,
  Test / testOptions += Tests.Setup(() => IO.createDirectory((ThisBuild / baseDirectory).value / "target" / "test-tmp")),
  // Coverage (sbt-scoverage): generated scalaxb and pekko-grpc code excluded; floors below.
  coverageExcludedPackages := Seq("generated", "ponton", "soapenvelope11", "scalaxb", "dataplatform",
    "cmnotification", "ecmplist", "gcrequestap", "gcrequest", "consumptionrecord", "cmrequest", "cmrevoke",
    "cpnotification", "cprequest", "commontypes", "cpdocument", "xmlschema", "at\\.energydash\\.admin")
    .map(p => s"$p\\..*").mkString(";"),
  coverageFailOnMinimum := true,
  coverageMinimumStmtTotal := 79,   // floors: the clean run rounded down, raised per milestone, never lowered;
  coverageMinimumBranchTotal := 62, // per-package floors in scripts/dev/coverage-floors.txt
)

lazy val root = (project in file("."))
  .enablePlugins(ScalaxbPlugin)
  .enablePlugins(PekkoGrpcPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(scalaxbSettings)
  .settings(dockerSettings)
  .settings(
    name := "XPAdapter",

    resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/",
    resolvers += "repo.jenkins-ci.org" at "https://repo.jenkins-ci.org/releases",
    // Pekko ist auf Maven Central, kein Lightbend-Resolver mehr noetig

    libraryDependencies ++= Seq(
      "com.github.daddykotex" %% "courier" % courierVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-xml" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson"  % pekkoVersion,
      "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      "org.apache.pekko" %% "pekko-connectors-mqtt" % pekkoConnectorsVersion,
      "org.apache.pekko" %% "pekko-connectors-mqtt-streaming" % pekkoConnectorsVersion,
      "com.github.pjfanning" %% "pekko-http-circe" % "3.1.0",
      "jakarta.xml.bind" % "jakarta.xml.bind-api" % "4.0.2",
//      "org.http4s" %% "http4s-core" % "1.0.0-m38",

      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      "org.slf4j" % "jcl-over-slf4j" % "2.0.13",

      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",

      "javax.xml.bind" % "jaxb-api" % scalaXmlVersion,
      "com.sun.xml.bind" % "jaxb-core" % scalaXmlVersion,
      "org.scala-lang.modules" %% "scala-xml" % scalaXmlVersion,
//      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",

      "com.google.guava" % "guava" % "33.2.1-jre",
    ),

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19",
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion,
      "org.jvnet.mock-javamail" % "mock-javamail" % "1.12",
      "com.opentable.components" % "otj-pg-embedded" % "0.13.3",
      "org.mockito" %% "mockito-scala" % "1.17.31",
      "org.scalamock" %% "scalamock" % "6.0.0",
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
  .settings(testSettings)