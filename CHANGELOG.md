# Changelog

All notable changes to **eegfaktura-eda-xp (Scala/Pekko EDA connector, e-mail + Ponton/KEP)** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and
versioning follows the deployment release tags. Detailed diffs stay in the `git log`;
this changelog highlights the changes relevant for overview and operations.

## [Unreleased]

## [1.0.0] – 2026-06-28

Part of the unified source-build cutover of the eegfaktura suite.

### Changed
- Migrated from Akka to Apache Pekko (resolves the BSL license block). (#2)
- CI: push to the registry's development tier with an auto-rollout bridge
  (dispatch-deploy, ADR-0005). (#3, #4)
- Added AGPL-3.0 license; README with service overview and tech stack. (#7)

### Fixed
- HTTP/2 disable override via the pekko-http 1.x `PreviewServerSettings` API.
