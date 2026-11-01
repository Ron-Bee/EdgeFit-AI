# Evidence methodology

What counts as evidence in EdgeFit AI, how provenance is preserved, and
how verified numbers are kept separate from estimates.

## Evidence classes

| Class | Meaning | Example |
|---|---|---|
| MEASURED | On-device COMPLETED benchmark with full provenance | `BENCH-20261031T030944Z-603a5d78`, 15.28 tok/s gen |
| PREDICTED | Muse estimate persisted *before* its benchmark | 12.0 tok/s recorded, then measured |
| ESTIMATED (unverified) | Catalog compatibility guess, no measurement | "Likely compatible (unverified)" in-app |
| SYNTHETIC | Offline fixtures for development and tests | `SYNTH-BENCH-001`, always labeled |

Rules: predictions never pool across model SHAs; catalog metadata never
proves compatibility; synthetic fixtures are never presented as device
data; failed runs are stored evidence but never enter averages.

## Provenance chain

Every measurement carries: run id → device profile hash → model SHA-256
(of GGUF bytes) → workload id + prompt SHA-256 → config hash
(ctx/threads/predict/sampler/seed) → runtime version/ABI → thermal and
memory conditions → collection timestamp. Stable identifiers are defined
in [edgefit-master-architecture.md](edgefit-master-architecture.md) §7.

Workstation flow: exported JSON → read-only validation → previewed
import (two-flag commit) → additive SQLite migrations → fixed,
parameterized, read-only retrieval. Tests use isolated temp databases;
no test writes the real store.

## Eligibility pipeline

`SUBMITTED → VALIDATED → QUALIFIED → PUBLISHED`, plus terminal
`REVOKED` (consent withdrawal or fraud finding). Transitions are
rule-driven and logged — never manual promotion:

- **SUBMITTED** — a phone claims a measurement (self-reported, not proof).
- **VALIDATED** — schema, hashes, and physical-plausibility bounds pass.
- **QUALIFIED** — dedup, anomaly, and quorum rules pass.
- **PUBLISHED** — included in public aggregates and Muse evidence.

Only `PUBLISHED` rows feed the explorer or model retrieval. Local rows
in this repository's era are `ingested`, not globally eligible — no
global eligibility is claimed for any current record.

## Aggregation rules (backend design, implemented in `cloud/`)

A cohort compares only identical model SHA + config hash + workload +
runtime/backend + build identity. Published per cohort: mean, median,
n, independent-install count. Provisional quorum: ≥5 runs and ≥3
installs; thin cohorts return `insufficient-evidence`, never means.
Per-run listing stays disabled (404) until a separate disclosure policy
is approved.

Methodology annex: [ef-stats-01-contract.md](ef-stats-01-contract.md).
API contract: [ef-cloud-01-api-contract.md](ef-cloud-01-api-contract.md).
Backend implementation: [../cloud/README.md](../cloud/README.md).

## Verified vs estimated in practice

- The app renders MEASURED (teal) and PREDICTED (purple) with different
  accents and provenance dialogs; unverified catalog entries say so.
- Study corpora freeze manifests with SHA-256 hashes before any live
  call; decisions cite run ids that must exist in the manifest
  (see `eval/comp-01b/` and
  [validation-results.md](validation-results.md)).
- Response-completeness and sample-size limits are recorded as
  caveats in [qualification-status.md](qualification-status.md), not
  smoothed over.
