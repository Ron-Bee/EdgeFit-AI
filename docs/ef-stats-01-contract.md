# EF-STATS-01 public model statistics methodology (PROPOSED)

Status: DESIGN ONLY. No endpoint is deployed, no statistics are
published, and no community data exists. This document defines the
rules a future public API must follow before it may serve aggregates.
Thresholds and counting below are owner-approved provisional design
policies; production activation still requires qualification.

EF-ARCH-02 reconciliation (ADR-018, PROPOSED): this document is the
methodology annex; the HTTP surface is authoritative in
`docs/ef-cloud-01-api-contract.md` (`/v1/benchmarks/aggregates`,
`/v1/benchmarks/runs`). The `/v1/stats/*` sketch below is
superseded by those paths and kept only for history.

## Evidence tiers (never mixed)

- `synthetic_example`: worked fixtures for documentation and client
  testing. Labeled on every response. Never averaged with physical
  data, never counted toward cohort sizes.
- `local_physical`: on-device measurements ingested into a private
  database (`ingested`, `authenticated`, or `qualified` states).
  Never leaves the host without per-submission consent.
- `global_eligible`: consented, qualified, deduplicated submissions
  in the global database. ONLY this tier feeds public statistics.
  Here `global_eligible` means pipeline-PUBLISHED rows; it is not a
  local prerequisite — submissions enter as `SUBMITTED` on consent
  alone, and no local state auto-grants eligibility.

## Cohort definition (grouping keys)

A cohort is the exact-match group over ALL of:

- `model_sha256` (identical GGUF bytes; display labels are not identity)
- `device_class` (e.g. `samsung-sm-s908u`; never an individual device)
- `runtime_impl`, `runtime_backend` (e.g. `llama.cpp`, `cpu`)
- `quantization_label` (conventional label, e.g. `Q4_K_M`)
- `workload_id`, `workload_prompt_sha256`
- `n_ctx`, `n_threads`, `n_predict`, `sampler`
- `effective_opt`, `effective_opt_status` (requested flags are not
  identity; `unverified` and `operator_attested` rows never merge)

Different builds are different cohorts. The two current local runs
(debug vs benchmark variant) MUST NOT be averaged together; a query
spanning them returns `insufficient-evidence` (see below), not a mean.

## Aggregates per cohort

For generation throughput (`tps_gen`) and prefill throughput, plus
prediction error where loop manifests exist:

- `sample_count` (n): number of eligible measurements
- `install_count`: number of distinct installs by salted install
  hash (ADR-005 method adopted as PROPOSED; honest terminology
  until hardware attestation exists — see privacy note)
- `mean`, `median`, `min`, `max`
- `variability`: interquartile range when n >= 8, else `null` with reason
- `mean_abs_pct_error`, `median_abs_pct_error`: prediction error vs
  persisted loop predictions (null when no loop coverage)

## Minimum cohort size and insufficient evidence

Owner-approved provisional thresholds: publish aggregates only
when `sample_count >= 5` AND `install_count >= 3` (design policy;
activation needs implementation qualification). Below either
threshold the API returns the cohort definition, both counts, and:

```json
{"status": "insufficient-evidence",
 "reason": "cohort below publication threshold",
 "aggregates": null}
```

It MUST NOT return means, medians, or error bars for thin cohorts,
and MUST NOT silently widen the cohort to reach threshold.

## Device-count privacy (open design)

EdgeFit stores no stable hardware identifier by design. Counting
distinct devices therefore requires a privacy-preserving mechanism,
PROPOSED but not decided:

- Option A: count distinct `source_sha256` of consented profile
  submissions as a device proxy (overcounts re-exports; documented).
- Option B: per-install random UUID generated on first launch,
  submitted only with consent, revocable by rotation.

EF-ARCH-02 adopts Option B (salted install hash, ADR-005) as the
owner-approved provisional method. Until an implementation is
qualified, `install_count` is `null` with `"method": "undecided"`,
which forces `insufficient-evidence` under the thresholds above.
This is intentional: no public average ships before the privacy
design is implemented and qualified.

## Consent, revocation, and identity

- Per-submission consent; consent record id required on every
  eligible row. Revocation removes the row from future aggregates
  (past published aggregates are versioned snapshots, never
  silently restated).
- Aggregate publication and individual-run disclosure are
  INDEPENDENT consent scopes (acceptance correction):
  aggregate-only is the default, and run-level listing stays
  PROPOSED and disabled until its disclosure policy is approved.
- No run ids, profile hashes, IP addresses, install ids, or
  free-text fields in public aggregate responses. Cohort keys only.
- Synthetic examples are served from a separate path/flag and are
  always labeled; clients cannot mistake them for community data.

## Example: current local evidence (MUST NOT publish)

The two S22 Ultra runs differ in `effective_opt`/`requested` build
configuration, so they form two cohorts of n=1 each. A present-day
query MUST answer `insufficient-evidence` for both. Any document,
dashboard, or demo showing a "community average" from these two runs
is fabrication.

## API surface sketch (SUPERSEDED — kept for history)

Per ADR-018 the authoritative surface is EF-CLOUD-01
(`/v1/benchmarks/aggregates`, `/v1/benchmarks/runs`); the sketch
below is retained only to record what was merged:

- `GET /v1/stats/cohorts?model_sha256=...&device_class=...&...`
  exact-match cohort lookup; full cohort key required (no partial
  wildcard that could merge builds).
- `GET /v1/stats/cohorts/{cohort_hash}` stable cohort retrieval.
- JSON only; versioned (`v1`); rate-limited; no authentication
  required for reads; submissions (EF-CLOUD-01) are authenticated
  and separate.

Grouping, quorum, tier, compiler-provenance, and error-statistics
rules in this annex still govern that surface.

## Pre-deployment gates (EF-STATS-01 / EF-CLOUD-01 blockers)

1. Owner approves thresholds and the device-count method.
2. Global eligibility + consent pipeline implemented and qualified.
3. Independent second-device evidence exists (no single-phone stats).
4. Contract tests against an isolated staging database pass.
5. Abuse review: quota, anomaly rejection, duplicate detection.

Nothing in EF-LOOP-01 implements, deploys, or calls this API.
