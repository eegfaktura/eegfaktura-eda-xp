# Changelog

All notable changes to **eegfaktura-eda-xp (Scala/Pekko EDA connector, e-mail + Ponton/KEP)** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and
versioning follows the deployment release tags. Detailed diffs stay in the `git log`;
this changelog highlights the changes relevant for overview and operations.

## [Unreleased]

### Fixed
- **Unknown inbound messages no longer block Ponton (261005-ca11, eegfaktura-platform#111).** A
  message type eda-xp cannot map to a process (e.g. not registered, `ERROR_MESSAGE`) made
  `/pontonxp/message` answer 500, so Ponton redelivered or parked it forever, and no one was
  notified. It is now acknowledged (204), logged as ERROR with conversation and receiver, and
  published to the receiver's `protocol/error` topic, where the backend logs it. The same applies
  to a known document that cannot be read (e.g. a MessageCode eda-xp does not know yet, a missing
  element): it becomes an error message for the receiver from the Ponton header, logged with the
  exception, instead of a 500. Only an unreadable envelope or Ponton header still answers 500.

## [1.0.8] – 2026-10-08

Fixes from the EDA-XP buglist (eegfaktura-platform#111).

### Changed
- **No silent fallback to an old schema any more (261005-ca5).** A request with a missing version
  label, or a label without its own case, is no longer built with an outdated schema. It is
  refused with an ERROR log naming the label, and the sender gets an error. Prod sends a label
  with a case for every routed request (ECON 02.40, ECOF 02.30, ECP 02.10, CPF 01.10, PT 03.00,
  CCMS 01.30).
- **ECON/ECOF label `03.00` removed (261008-ca1).** 03.00 is the process version, not a schema set
  version; it now fails like any unknown label (see README, "Ponton header").
- **`app.interface.mode` must be PROD or SIMU (261005-ca8).** Any other value (e.g. a typo) used
  to fall through to PROD in every document builder; it is now an error. Case is ignored.
- ANFORDERUNG_CPF 01p20 refuses a request without ECID or participation factor instead of sending
  it without them (261008-ca3).

### Fixed
- **Message ids around new year (261005-ca9).** The year in MessageId/ConversationId came from
  the week-based year (`YYYY`): from 28 to 31 December the ids carried the next year. Now the
  calendar year.
- **`DocumentCreationDateTime` was one hour off in summer (261008-ca12).** The offset was the raw
  standard-time offset (+01:00) with the summer wall-clock time; it now includes daylight saving
  time (+02:00 in summer).
- `DateTo` of the open end of ECON/ECOF requests is 2099-12-31 again, not 2100-01-31 (0-based
  month, 261008-ca16).
- The ECON 01p10 builder declared the root element with an unbound `ns2:` prefix (261008-ca17).
- Outdated comment on the Ponton header message type (261008-ca4).

### Documentation
- README: Ponton header rule (message type + schema set version from the schema set
  definition, not process code/process version), schema set version vs. process version,
  ebUtilities API lookups and a checklist for EDA cut-over dates — lessons from the
  2026-10-05 rejections.

## [1.0.7] – 2026-10-06

### Fixed
- **Change of participation factor (ANFORDERUNG_CPF) rejected since 2026-10-05.** The messenger
  only has schema set EC_PRTFACT_CHANGE_01.10 active, which expects the payload in **ECMPList
  01p20** (`No activated XML Schema for MessageType:ANFORDERUNG_CPF Version:01.00`). With version
  label `01.10` from the backend config the request is now built in 01p20: new mandatory field
  `DataType` = `EnergyCommunityRegistration` (the value the DSOs send in SENDEN_ECP 01p20),
  `ECZoneLevel` left empty (optional), `PlantCategory` dropped. `01.00` keeps the 01p10 builder.
- The Ponton header also carries the message type for **ANFORDERUNG_CPF** and
  **ANFORDERUNG_ECOF** (in addition to ECP/ECON from 1.0.6); the adapter does not translate the
  process code for the new schema sets. PT and CCMS still use the process code.

### Changed
- `src/main/xsd/ECMPList_01p20.xsd` replaced by the published version (`MPListData` 0..n), so an
  empty ZP list in 01p20 (ABSCHLUSS_ECON/ECOF, SENDEN_ECP) is parsed instead of rejected.

## [1.0.6] – 2026-10-06

### Fixed
- **ZP list and online registration rejected by the messenger since 2026-10-05.** The Ponton
  header now carries the schema set message type `ANFORDERUNG_ECP` / `ANFORDERUNG_ECON` instead of
  the process code `EC_PODLIST` / `EC_REQ_ONL`. With the new schema sets EC_PODLIST_02.10 and
  EC_REQ_ONL_02.40 the adapter no longer translated the process code (it still does for
  CR_REQ_PT and EC_PRTFACT_CHANGE), and the messenger answered
  `No activated XML Schema for MessageType:EC_PODLIST Version:02.10`. All other requests keep
  the process code. `MessageVersion` stays the schema set version from the backend config
  (02.10 / 02.40).

## [1.0.5] – 2026-10-05

### Changed
- A version label from the backend config without its own case in `getVersion` (ECON, ECOF,
  CCMS, PT, ECP) now logs a warning naming the label, message code, conversation and the
  fallback builder used. The message is still built as before; until now the fallback — usually
  an outdated schema — happened silently. ECOF `02.00` gets its own case (same builder as
  before), so the public compose stack, which sends 02.00, does not warn.

### Added
- **Process version vs. schema set (EDA change of 2026-10-05).** Process version and schema set
  version are no longer the same number: EC_REQ_ONL 03.00 uses schema set 02.40, EC_REQ_OFF 03.00
  uses schema set 02.30. The Ponton EDA adapter resolves the schema set from the
  `MessageVersion` in the header, which must therefore carry the **process version**; sending
  `02.40` gave `adapter forced unknown schema (type:EC_REQ_ONL version:02.40 set:unknown)`.
  `ANFORDERUNG_ECON` and `ANFORDERUNG_ECOF` now accept `03.00` and build the `cmrequest 01p30`
  XML with the schema set path (`EC_REQ_ONL/02.40`, `EC_REQ_OFF/02.30`) in `schemaLocation`;
  the header keeps `03.00`. The backend config selects it via `eda-process-versions`.
- `ANFORDERUNG_ECP` accepts the version label `02.10` (schema set of EC_PODLIST 02.10, EDA change
  of 2026-10-05). Same XML (`cprequest 01p12`); only the Ponton `MessageVersion` and the
  `schemaLocation` path change. Ponton messengers that allow only the new schema sets reject
  `02.00`.

## [1.0.4] – 2026-10-05

### Added
- **Version labels for the EDA process change of 2026-10-05.** `ANFORDERUNG_ECON` accepts
  `02.40` (schema set of EC_REQ_ONL 03.00) and `ANFORDERUNG_ECOF` accepts `02.30` (schema set of
  EC_REQ_OFF 03.00). Both build the same XML as before (`cmrequest 01p30`); only the version in
  the Ponton header (`MessageVersion`) and in the `schemaLocation` path changes. Needed once a
  messenger allows only the new schema sets: until now these values fell through to the
  `case _` default and silently built the old 01p10/01p20 message. The previous labels (`02.30`,
  `02.20`) keep working. The backend config `eda-process-versions` selects which one is sent.

- MQTT: pin the CR_MSG gzip + standard Base64 wire format consumed by
  eegfaktura-energystore with a cross-repository compatibility test.
- CI builds `env/**` branches and deploys the resulting image into the matching feature
  environment (ADR-0008): a push to `env/<name>` pins this service in namespace `env-<name>`
  to that branch's `sha-…` image. Previously only the default branch, tags and `preview/**`
  produced an image at all. The environment itself is still provisioned manually.

### Fixed
- The Docker build now publishes a `sha-<short>` tag. Unlike the other services, this repo
  is packaged by sbt-native-packager rather than `docker/metadata-action`, which never
  produced that tag — while both the preview deploy (ADR-0007) and the new env deploy
  (ADR-0008) pin exactly it. Every such deploy therefore ended in `ImagePullBackOff`;
  it first showed up on 2026-09-26 with the first `env/billing` build.
- A build from a `preview/**` or `env/**` branch no longer overwrites the moving tags
  `latest` and `v0.2.22` in the development tier. The dev zone pulls `eegfaktura-kep:latest`,
  so a feature build silently became the dev zone's next image — which is what happened on
  2026-09-26. Those branches now publish their `sha-` tag only; default-branch and tag
  builds are unchanged.

## [1.0.3] – 2026-09-07

### Docs
- README: added "Adding a new EDA process version" — documents that outbound process
  versions are stamped by the backend (`eda-process-versions`) and selected here by
  `getVersion()` string match (unmatched → silent downgrade via `case _`), and the
  in-order convention to update eda-xp XSD/`getVersion` **and** all backend-config copies
  (Prod CM + repo default + dev/env overlays). New-version discovery is handled by the
  monthly EDA-Prozessversionen-Watcher routine.

### Fixed
- Test module no longer compiles-broken: `CMRequestOnline/OfflineRegistrationSpec` and
  `ECPartitionChangeSpec` called `.getTime` on `MessageHelper.getProcessDate`, which had been
  refactored from a `Calendar` to a `String` (the ready `yyyy-MM-dd` process date) — so the
  whole `Test` scope failed to compile and no eda-xp test could run. Use `getProcessDate`
  directly (identical value) and drop the now-unused `buildCalendarDate` import in the two
  CMRequest specs.

## [1.0.2] – 2026-07-05

### Fixed
- Mail server no longer drops recipients silently: the per-recipient address check used a
  closed TLD allowlist (`aero|...|travel|[a-z][a-z]`) that rejected modern gTLDs such as
  `.energy` or `.online`, and invalid `;`-parts were skipped without any feedback — in the
  worst case a mail went out with **no** recipient at all. The check now uses the shared
  suite-wide address rule (ASCII local part, TLD >= 2 letters, no allowlist), rejected
  parts are reported back to the caller via the new additive `SendMailReply.rejectedRecipients`
  field, and an address list with no valid recipient fails the request instead of sending
  a mail without a "to". CC addresses are now split/trimmed/validated the same way as "to"
  (previously an untrimmed single string that was silently dropped when invalid). Outer
  whitespace stripping explicitly covers the non-breaking spaces U+00A0/U+202F/U+2007 —
  `String#strip` alone does NOT remove them (`Character.isWhitespace` excludes NBSP).

### Changed
- CI: Preview-Deployments (ADR-0007) — Push auf `preview/**` baut+deployt on-demand in die Dev-Zone (sha-pinned, kein `:latest`), Auto-Reset bei Branch-Delete.

### Changed
- Outbound admin/notification mail (gRPC `SendMailService`) now reads the sender
  address from config (`epmsmail.admin.from`, env `EMAIL_ADMIN_FROM`) instead of
  the hardcoded `no-reply@eegfaktura.at`. The default is unchanged, so production
  behaviour is identical; a deployment can override the sender (e.g. to use a
  different SMTP relay whose domain is verified for another address).


## [1.0.1] – 2026-06-30

### Added
- Inbound processing of ECMPList 01.20 and ConsumptionRecord 01.31 market messages. (#5)

### Changed
- XSD refactor: named Inbound/OutboundMessage types instead of anonymous ones. (#6)
- CI: Snyk Code (SAST) workflow + SARIF upload to code scanning. (#10, #11)

## [1.0.0] – 2026-06-28

Part of the unified source-build cutover of the eegfaktura suite.

### Changed
- Migrated from Akka to Apache Pekko (resolves the BSL license block). (#2)
- CI: push to the registry's development tier with an auto-rollout bridge
  (dispatch-deploy, ADR-0005). (#3, #4)
- Added AGPL-3.0 license; README with service overview and tech stack. (#7)

### Fixed
- HTTP/2 disable override via the pekko-http 1.x `PreviewServerSettings` API.
