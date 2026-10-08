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

## Tests

```bash
bash scripts/dev/test.sh                  # whole suite (local sbt, or the pinned sbt image via docker)
bash scripts/dev/test.sh --only '*TenantProviderSpec'
bash scripts/dev/test.sh --fast           # without the Slow-tagged robustness tests (~1.5 min instead of ~3.5)
bash scripts/dev/test.sh --coverage       # + scoverage report and the coverage floors
bash scripts/dev/test.sh --update-golden  # rewrite the protocol golden files, then review `git diff`
```

- Everything a test talks to is a loopback stand-in started by the tests: embedded PostgreSQL (port 54325,
  migrated with the production migrations), embedded MQTT broker (18831), a fake Ponton messenger (16060).
  **No test ever sends to a real KEP endpoint.** Configuration: `src/test/resources/application-test.conf`.
- Files a run writes stay under `target/`; the JVM runs in `Europe/Vienna`.
- A known defect is tested as `knownError("<id>") { … }`: the test asserts the correct behaviour, is
  reported as *pending* while the defect exists and fails once it is fixed (remove the marker with the fix).
- **Protocol catalog** (`src/test/resources/protocol/`): one row per outbound message code and version
  label; each row is a test (builder chosen, Ponton header, XSD-valid document, golden document). A new
  process version is tested by adding its row — see "Adding a new EDA process version" below.
- CI: `.github/workflows/test.yml` runs the suite with coverage before the image is built.

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
   **older** version — an unmatched string does not error, it downgrades (since 1.0.5
   with a WARN log naming the label).
   Then add the row for the new label to `src/test/resources/protocol/catalog.conf` (builder, XSD, Ponton
   header), run `bash scripts/dev/test.sh --only '*Protocol*' --update-golden` and review the new golden
   files with `git diff` before committing them.
2. **eegfaktura-backend config** `eda-process-versions.<CODE>`: bump the value to the
   new version. The backend stamps it onto `MessageCodeVersion`
   (`mqtt/messageBroker.go`) and this service uses it to pick the schema above. Bump
   it in **all** copies: the Prod ConfigMap (eegfaktura-gitops base), the repo default
   `config.yaml`, and the dev/env overlays (eegfaktura-platform) — they drift apart
   otherwise (see the 2026-07-13 alignment).

Discovery of new EDA publications is covered by the monthly **EDA-Prozessversionen-Watcher**
routine (claude.ai/code/routines), which pings only when a published version exceeds the
list it tracks. Codes not routed in `MessageHelper.getEdaMessageByType` (e.g. `CCMO`, `ECC`)
or hard-coded (`GN`) ignore the config value.

### The version label is the schema set version, not the process version

Since the EDA change of 2026-10-05, the process version on ebUtilities and the schema set
version differ (e.g. `EC_REQ_ONL` process 03.00 → schema set `EC_REQ_ONL_02.40`). The config
value must be the **schema set version** — the number in the schema set name
(`EC_PODLIST_02.10` → `02.10`). The ebUtilities API returns the schema set per process
version without login:

```bash
curl -s -X POST -H 'Content-Type: application/json' -d '{}' https://www.ebutilities.at/api/processes/list   # find the process uid
curl -s https://www.ebutilities.at/api/processes/show/<uid>                                               # field "schemaset"
curl -s https://www.ebutilities.at/api/market-notifications/process/<uid>/notifications/list               # message codes + payload schema (namespace, XSD link)
```

### Ponton header: message type, not process code

The SOAP header sent to the Ponton EDA webservice adapter (`PontonRequest`, `OutHeaderType`)
must carry the **message type and version from the schema set definition**:

| Header field | Value | Example |
|---|---|---|
| `MessageType` | message code of the schema set entry | `ANFORDERUNG_ECP` (not `EC_PODLIST`) |
| `MessageVersion` | schema set version | `02.10` (not the process version) |

Source: PONTON X/P 5.2 *Backend Integration Guide*, ch. 2.1.1 — "MessageName and
DTDVersionNumber need to match the attributes MessageType and MessageVersion found in the
schema-set definition files". The messenger GUI shows the same pairs under
*Messenger → Schema* (columns *Typ* / *Version*), and the inbound header already carries the
message type (`src/test/resources/Response-Envelope.xml`: `SENDEN_ECP`).

`MessageHelper.pontonHeaderMessageType` decides per request. ECP, ECON, ECOF and CPF send the
message type (since 1.0.6/1.0.7). PT and CCMS still send the process code; with the old schema
sets the adapter accepted that, with the new sets of 2026-10-05 it did not
(`No activated XML Schema for MessageType:EC_PODLIST Version:02.10`, although the set was
active on local partner, remote partner and agreement). Switch PT/CCMS to the message type
together with their next version change.

### Checklist before an EDA cut-over date

1. Per process this service sends: schema set + message codes + payload schema from the
   ebUtilities API (above). A new payload namespace (e.g. CPF 01.10 → ECMPList **01p20**) means
   a new builder, not just a new label; check new **mandatory** fields against the published
   XSD and, if the meaning is unclear, against a real message from a grid operator
   (Ponton *Message Monitor* → message → *Raw Message*).
2. Add the `getVersion()` case and make sure the header uses the message type (see above).
   Validate the built XML against the published XSD in a test (see `ECPartitionChangeSpec`).
3. Bump the backend config (all copies, see above) and restart the backend — it reads the
   config only at startup.
4. After the rollout send **one** request per process and check the status in the logs
   (`PontonRoute - Status from EDA`: `1` sent, `2` received, `12` rejected by the messenger).
   Compare a rejected header with *Messenger → Schema* before suspecting the messenger.
5. Grid operators deactivate the old schema sets in their registry profiles on the cut-over
   date, and remote partners are imported from the registry — rolling back to old labels does
   not work.

## Dependencies

- **Ponton X/P (KEP) messenger** — AS4 market communication with grid operators
- **IMAP/SMTP mail servers** — EDA-over-email transport
- **MQTT broker** (mosquitto) — internal bus to `eegfaktura-backend` / `eegfaktura-energystore`
- **PostgreSQL** — conversation/tenant state (Flyway-managed)
- **eegfaktura-backend** (gRPC)

## License

GNU Affero General Public License v3.0 (AGPL-3.0) — see [`LICENSE`](LICENSE).
