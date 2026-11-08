# Contract copies from the neighbours

Copied by hand from the sibling repositories of the eegfaktura stack; the contract specs
(`src/test/scala/at/energydash/contract/`) read only these copies — nothing is fetched at test time.

| File | Source | Commit |
|---|---|---|
| `backend/mqtt.go.txt` | eegfaktura-backend `model/mqtt.go` (MQTT message struct and message codes) | `2fc5f8b` (2026-11-05, backend `feat/p2p-eva`, backend#147: `ecZoneLevel`, `ecTypeEda`, `techCode`, `fuelCode` of platform#229); refresh after the merge |
| `backend/area.go.txt` | excerpt of eegfaktura-backend `model/Eeg.go`, `AreaType` constants (values of `ecType`) | `2fc5f8b` (backend `feat/p2p-eva`, backend#147); refresh after the merge |
| `backend/eda-process-versions.upstream.yaml` | eegfaktura-backend `config.yaml`, block `eda-process-versions` | `e82a0d0` (2026-11-05) |
| `backend/eda-process-versions.workspace-stack.yaml` | the eegfaktura-dev workspace's `docker/backend/config.yaml`, same block | workspace 2026-10-08 |
| `backend/mail.proto` | eegfaktura-backend `proto/mail.proto` (caller of SendMailService) | `a1b5b18` |
| `admin-backend/ponton.proto` | eegfaktura-admin-backend `src/main/protobuf/ponton.proto` (caller of RegisterPontonService) | `a910655` (2026-10-05) |
| `energystore/mqtt.go.txt` | eegfaktura-energystore `model/mqtt.go` (CR_MSG parser struct) | `f222856` (2026-10-08) |
| `energystore/mock-cr-msg.json` | eegfaktura-energystore `contract/testdata/v3/mock-cr-msg.json` | `f222856` |
| `energystore/v3world-first-crmsg.json` | first message of eegfaktura-energystore `scenario/testdata/v3world/crmsg.jsonl.gz` | `f222856` |

Backend subscriptions the topic contract uses (`eegfaktura-backend` `mqtt/messageBroker.go:121-131` at `a1b5b18`):
`eda/response/+/protocol/#`, `eda/response/+/command/#`, `eda/response/error`; the energystore's (`config.yaml`):
`eda/response/+/protocol/cr_msg`.

**Refresh:** copy the files again, update the commits here, run `bash scripts/dev/test.sh --only '*Contract*'`,
and review every difference — a red contract test is a cross-project change, agreed with the other project.
