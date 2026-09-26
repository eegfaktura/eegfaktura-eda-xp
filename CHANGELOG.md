# Changelog

All notable changes to **eegfaktura-eda-xp (Scala/Pekko EDA connector, e-mail + Ponton/KEP)** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and
versioning follows the deployment release tags. Detailed diffs stay in the `git log`;
this changelog highlights the changes relevant for overview and operations.

## [Unreleased]

### Added
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
