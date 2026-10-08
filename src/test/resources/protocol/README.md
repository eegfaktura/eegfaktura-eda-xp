# Protocol catalog

Test data that describes eda-xp's EDA protocol: `catalog.conf` (one row per outbound message code and
version label, one per inbound fixture), `samples/<code>.json` (the MQTT request per message code),
`golden/` (the expected documents and Ponton headers, normalised). `OutboundProtocolSpec` registers one
test per row.

**Testing a protocol change** (new version label, schema set, header rule): add or change the row,
run `bash scripts/dev/test.sh --only '*Protocol*' --update-golden`, review `git diff src/test/resources/protocol/golden`,
run again without the flag. A row for a known defect carries `known-error` and its correct outcome in
`expect`; its golden files are never written from today's output.
