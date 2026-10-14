# Fixtures from eegfaktura-v3

`eda-xml/in/` — inbound EDA documents copied unchanged from eegfaktura-v3
`backend/src/test/resources/golden/eda-xml/in/` at commit `0b785d2` (2026-10-08; files last changed in `f6540a0`, 2026-09-28; AGPL-3.0 like this project).
Used by the protocol catalog (`../protocol/catalog.conf`, inbound rows). `EDASendError.xml` is not an
ebUtilities document (a MAIL error shape) and pins the "unknown type" result.

Refresh by hand: copy the directory again, run `bash scripts/dev/test.sh --only '*Protocol*' --update-golden`,
review the diff of `../protocol/golden/in/`, record the new commit id here. Never fetched by the build.
