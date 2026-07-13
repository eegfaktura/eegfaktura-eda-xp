# Changelog

All notable changes to **eegfaktura-eda-xp (Scala/Pekko EDA connector, e-mail + Ponton/KEP)** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and
versioning follows the deployment release tags. Detailed diffs stay in the `git log`;
this changelog highlights the changes relevant for overview and operations.

## [Unreleased]

### Docs
- README: added "Adding a new EDA process version" — documents that outbound process
  versions are stamped by the backend (`eda-process-versions`) and selected here by
  `getVersion()` string match (unmatched → silent downgrade via `case _`), and the
  in-order convention to update eda-xp XSD/`getVersion` **and** all backend-config copies
  (Prod CM + repo default + dev/env overlays). New-version discovery is handled by the
  monthly EDA-Prozessversionen-Watcher routine.

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
