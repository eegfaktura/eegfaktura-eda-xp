# External sources

Every external source of eegfaktura-eda-xp with its exact version and licence (the project is AGPL-3.0).
Created 2026-10-08 with the test environment. **Verified** = licence read from the source's own POM/LICENSE
on that date; the other rows are taken over from the build files and still have to be re-checked (open).

## Build plugins (`project/plugins.sbt`)

| Source | Version | Licence | Use | Verified |
|---|---|---|---|---|
| org.scalaxb:sbt-scalaxb | 1.12.0 | MIT | Scala code from the ebUtilities/Ponton XSDs and WSDLs | open |
| com.github.sbt:sbt-native-packager | 1.10.4 | BSD-2-Clause | image staging (`Docker/publish`) | open |
| org.apache.pekko:pekko-grpc-sbt-plugin | 1.1.1 | Apache-2.0 | gRPC code from `src/main/protobuf` | open |
| org.scoverage:sbt-scoverage | 2.4.4 (2026-01-13; scalac plugin 2.5.2, 2025-12-16) | Apache-2.0 | statement/branch coverage of the tests (`scripts/dev/test.sh --coverage`, CI) | 2026-10-08 (POM) |

## Libraries (`build.sbt`)

| Source | Version | Licence | Scope | Verified |
|---|---|---|---|---|
| Apache Pekko (actor-typed, stream, persistence-typed, serialization-jackson, discovery, slf4j) | 1.2.1 | Apache-2.0 | compile | open |
| Apache Pekko HTTP (http, http-xml) | 1.2.0 | Apache-2.0 | compile | open |
| Apache Pekko Connectors MQTT (mqtt, mqtt-streaming) | 1.1.0 | Apache-2.0 | compile | open |
| com.github.pjfanning:pekko-http-circe | 3.1.0 | Apache-2.0 | compile | open |
| io.circe (core, generic, parser) | 0.14.3 | Apache-2.0 | compile | open |
| com.typesafe.slick (slick, slick-hikaricp) | 3.5.1 | BSD-2-Clause | compile | open |
| com.github.tminglei:slick-pg, slick-pg_circe-json | 0.22.2 | BSD-2-Clause | compile | open |
| org.postgresql:postgresql | 42.7.3 | BSD-2-Clause | compile | open |
| org.flywaydb:flyway-core, flyway-database-postgresql | 10.20.0 | Apache-2.0 | compile (the only Flyway since 2026-10-08) | open |
| org.scala-lang.modules:scala-xml | 2.3.0 | Apache-2.0 | compile | open |
| jakarta.xml.bind:jakarta.xml.bind-api | 4.0.2 | EDL-1.0 (BSD-3-Clause) | compile | open |
| javax.xml.bind:jaxb-api, com.sun.xml.bind:jaxb-core | 2.3.0 | CDDL-1.1 / GPL-2.0 with Classpath Exception | compile | open |
| com.github.daddykotex:courier | 3.0.1 | MIT | compile (MAIL transport, unused) | open |
| com.typesafe.scala-logging:scala-logging | 3.9.5 | Apache-2.0 | compile | open |
| ch.qos.logback:logback-classic | 1.5.6 | EPL-1.0 / LGPL-2.1 | compile | open |
| org.slf4j:jcl-over-slf4j | 2.0.13 | Apache-2.0 | compile | open |
| org.fusesource.leveldbjni:leveldbjni-all | 1.8 | BSD-3-Clause | compile (persistence journal) | open |
| com.google.guava:guava | 33.2.1-jre | Apache-2.0 | compile | open |
| org.scalatest:scalatest | 3.2.19 | Apache-2.0 | test | open |
| org.apache.pekko: pekko-stream-testkit, pekko-actor-testkit-typed | 1.2.1 | Apache-2.0 | test | open |
| org.jvnet.mock-javamail:mock-javamail | 1.12 | CDDL-1.0 (to confirm) | test | open |
| com.opentable.components:otj-pg-embedded | 0.13.3 | Apache-2.0 | test (embedded PostgreSQL) | open |
| org.mockito:mockito-scala | 1.17.31 | MIT | test | open |
| org.scalamock:scalamock | 6.0.0 | MIT | test | open |
| io.moquette:moquette-broker | 0.17 | Apache-2.0 | test (embedded MQTT broker) | open |

Removed 2026-10-08 (test environment): `slick-testkit` (listed twice), `h2`, `flyway-core` 7.2.0 (test scope).

## Images and CI

| Source | Version | Licence | Use | Verified |
|---|---|---|---|---|
| sbtscala/scala-sbt | eclipse-temurin-17.0.19_10_1.13.0_3.8.4 | Apache-2.0 | `scripts/dev/test.sh` when no local sbt (same image as the workspace build) | open |
| eclipse-temurin (image base, `build.sbt` `dockerBaseImage`) | 17-jre (floating, known error #10) | GPL-2.0 with Classpath Exception | runtime image | open |
| actions/checkout | v4.2.2 `11bd719…` (test.yml); `@v4` (rolling-release.yml, floating, #10) | MIT | CI | open |
| actions/setup-java | v6.0.1 `de7274f…` (test.yml); `@v4` (rolling-release.yml) | MIT | CI | open |
| sbt/setup-sbt | v1.5.11 `6158cb0…` (2026-09-24, test.yml); `@v1` (rolling-release.yml) | MIT | CI | 2026-10-08 (LICENSE) |
| actions/upload-artifact | v7.0.1 `043fb46…` (test.yml) | MIT | CI coverage report | open |
| actions/cache, docker/login-action | `@v4`, `@v3` (rolling-release.yml, floating, #10) | MIT | CI | open |
| Trivy (release binary) | 0.75.0 (2026-10-01), SHA-256 checked | Apache-2.0 | CI `security-scan.yml`: secrets, vulnerabilities, misconfigurations | open |
| OSV-Scanner (release binary) | 2.6.0 (2026-09-14), SHA-256 checked | Apache-2.0 | CI `security-scan.yml`: vulnerable dependencies (from the pom `sbt makePom` exports) | open |
| Gitleaks (release binary) | 8.30.1 (2026-03-21), SHA-256 checked | MIT | CI `security-scan.yml`: secrets in the new commits | open |
| Temurin JDK via actions/setup-java (`pr-checks.yml`, `security-scan.yml`) | 17.0.19+10 | GPL-2.0 with Classpath Exception | CI | open |

## Specifications and test data

| Source | Version | Licence | Use |
|---|---|---|---|
| ebUtilities XSDs (`src/main/xsd`) and Ponton X/P WSDLs (`src/main/wsdl`) | as committed | vendor files, terms not stated in the repository | code generation, XSD validation in tests |
| backend's `base.EEG` DDL (`src/test/resources/testdb/base_eeg.sql`) | eegfaktura-backend a1b5b18 | AGPL-3.0 (same project family) | test database |
