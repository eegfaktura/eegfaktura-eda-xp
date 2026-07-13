# eegfaktura-eda-xp

> EDA market-communication service — the live bridge between eegfaktura and the
> Austrian energy data-exchange network (ebUtilities / EDA).

Connects eegfaktura to grid operators over the **Ponton X/P (KEP)** messenger
(AS4/SOAP) **and** over **email** (IMAP/SMTP), and bridges parsed market messages
to and from the internal **MQTT** bus. Handles the ebUtilities message families
used by energy communities — e.g. CMRequest, CMRevoke, CMNotification,
CPRequest/CPNotification, ConsumptionRecord (`CR_MSG`) and ECMPList.
(Container image: `eegfaktura-kep`; cluster deployment: `eegfaktura-eda`.)

Part of the **eegfaktura** suite — an open-source billing and management platform
for Austrian renewable energy communities (*Erneuerbare-Energiegemeinschaften*, EEG).

## Tech stack

- **Scala 2.13**, built with **sbt**
- **Apache Pekko 1.2** — Actors, Pekko HTTP, Pekko Connectors MQTT, Pekko gRPC
  (migrated from Akka)
- **scalaxb** — generates Scala bindings from the ebUtilities XSDs
  (per-namespace package mapping) under `src/main/xsd/`
- **Courier** (IMAP/SMTP), **Slick** + Slick-PG over **PostgreSQL**, **Flyway** migrations
- Circe (JSON), Logback
- Packaged via sbt-native-packager (base image `eclipse-temurin:17-jre`)

## Key components

- `src/main/scala/at/energydash/`
  - `actors/` — `SupervisorActor`, `TenantMailActor`, `MqttPublisher`, Ponton/KEP
    handling under `soap/` + `http/`
  - `domain/xml/` — scalaxb-generated message types; `domain/eda/` — message mapping
  - `mailer/` (IMAP/SMTP), `mqtt/` (Pekko MQTT), `service/` (gRPC)
- `src/main/xsd/` — the ebUtilities XSD catalogue (scalaxb input)
- Entry point: `XpAdapter.scala`; binary `xpadapter`

## Build

```bash
sbt clean compile   # runs scalaxb XSD codegen + Pekko gRPC protoc codegen
sbt test
```

## Run

Local (requires PostgreSQL and an MQTT broker):

```bash
sbt run -Dconfig.file=src/main/resources/application.conf
```

Docker (native packager):

```bash
sbt Docker/publish
docker run -p 6090:6090 -p 9093:9093 \
  -v ./application.conf:/conf/application.conf \
  -v ./storage:/storage/prod \
  <image>
```

## Configuration

`src/main/resources/application.conf` (HOCON) — key settings:

- `app.interface.mode` — `SIMU` (simulation) or `PROD`
- `app.kepserver.url` — Ponton X/P outbound endpoint
- `app.server.port` (HTTP, 6090), `app.grpc.port` (gRPC, 9093)
- `epmsmail.*` — IMAP/SMTP accounts for EDA-over-email
- `slick.pgsql.local.db.*` / `flyway.*` — PostgreSQL connection + migrations

Environment variables (names only): `SMTP_ADMIN_SERVER_HOST`, `SMTP_ADMIN_SERVER_PORT`,
`EMAIL_ADMIN_USER`, `EMAIL_ADMIN_PWD`, `TZ`, `JAVA_OPTS`.

Exposed ports: **6090** (HTTP), **9093** (gRPC). Volumes: `/conf`, `/storage/prod`.

## Adding a new EDA process version

When ebUtilities/EDA publishes a **new version** of a process this service sends
(e.g. `CM_REV_SP`, `EC_REQ_ONL`, `EC_REQ_OFF`, `EC_PODLIST`, `CR_REQ_PT`), the
outbound version is **not** picked automatically — it is stamped by the sender and
selected here by string match. Two coupled places must be updated **in order**:

1. **eda-xp (this repo):** drop the new XSD into `src/main/xsd/` (regenerates the
   scalaxb binding on `sbt compile`), add the concrete message class, and add a
   `case Some("<xx.yy>")` branch in the relevant `getVersion()` (e.g.
   `domain/eda/CMRevokeRequest.scala`). Note the `case _` default falls back to an
   **older** version silently — an unmatched string does not error, it downgrades.
2. **eegfaktura-backend config** `eda-process-versions.<CODE>`: bump the value to the
   new version. The backend stamps it onto `MessageCodeVersion`
   (`mqtt/messageBroker.go`) and this service uses it to pick the schema above. Bump
   it in **all** copies: the Prod ConfigMap (eegfaktura-gitops base), the repo default
   `config.yaml`, and the dev/env overlays (eegfaktura-platform) — they drift apart
   otherwise (see the 2026-07-13 alignment).

Discovery of new EDA publications is covered by the monthly **EDA-Prozessversionen-Watcher**
routine (claude.ai/code/routines), which pings only when a published version exceeds the
list it tracks. Codes not routed in `MessageHelper.getEdaMessageByType` (e.g. `CCMO`, `ECC`)
or hard-coded (`CPF`, `GN`) ignore the config value.

## Dependencies

- **Ponton X/P (KEP) messenger** — AS4 market communication with grid operators
- **IMAP/SMTP mail servers** — EDA-over-email transport
- **MQTT broker** (mosquitto) — internal bus to `eegfaktura-backend` / `eegfaktura-energystore`
- **PostgreSQL** — conversation/tenant state (Flyway-managed)
- **eegfaktura-backend** (gRPC)

## License

GNU Affero General Public License v3.0 (AGPL-3.0) — see [`LICENSE`](LICENSE).
