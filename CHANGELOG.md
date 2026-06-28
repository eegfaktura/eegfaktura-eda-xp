# Changelog

Alle nennenswerten Änderungen an **eegfaktura-eda-xp (Scala/Pekko EDA-Connector, E-Mail + Ponton/KEP)** werden hier dokumentiert.

Das Format orientiert sich an [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
die Versionierung an den Deployment-Release-Tags. Detail-Diffs bleiben im `git log`;
dieser Changelog hebt die für Überblick und Betrieb relevanten Änderungen hervor.

## [Unreleased]

## [1.0.0] – 2026-06-28

Teil des einheitlichen Source-Build-Cutovers der eegfaktura-Suite.

### Changed
- Migration von Akka auf Apache Pekko (löst BSL-Lizenz-Blockade). (#2)
- CI: Push in den Development-Tier der Registry mit Auto-Rollout-Bridge
  (dispatch-deploy, ADR-0005). (#3, #4)
- AGPL-3.0-Lizenz ergänzt; README mit Service-Überblick und Tech-Stack. (#7)

### Fixed
- HTTP/2-Disable-Override über die pekko-http-1.x-`PreviewServerSettings`-API.
