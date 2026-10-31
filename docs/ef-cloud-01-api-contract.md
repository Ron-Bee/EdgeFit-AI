# EF-CLOUD-01 backend API contract (v1, PROPOSED)

Versioned JSON contract for the EdgeFit cloud backend. Base URL is a
proposed deployment choice — NOT configured, no DNS exists, not
implemented infrastructure:
`https://api.edgefit.ronbeesoftware.com/v1`.

EF-ARCH-02 reconciliation (ADR-018, PROPOSED): this document is the
authoritative HTTP surface; `docs/ef-stats-01-contract.md` is the
statistics methodology annex (grouping, tiers, quorum behavior,
error statistics, compiler provenance). Where they overlap, this
surface plus that methodology govern together; conflicts resolve
toward stricter grouping and explicit insufficient-evidence.

## Versioning

URL-major version (`/v1`). Additive fields are minor-compatible;
removals or semantic changes require `/v2` plus a 6-month overlap.
Every response carries `api_version: "1.x"`.

## Authentication

- Public reads: none; IP rate-limited.
- Uploads: `Authorization: Bearer <install-upload-token>`
  (owner-issued per install, revocable, least privilege).
- Muse-evidence retrieval: backend-internal only; not exposed over
  HTTP (called by the server dispatcher, mirroring EF-META-05).
- Meta key: server secret store only; never in any payload.

## Error envelope

```json
{"api_version": "1.0", "error": {"code": "string", "message": "string",
"details": []}, "request_id": "string"}
```

Codes: `bad_request`, `unauthorized`, `forbidden`, `not_found`,
`conflict`, `too_large`, `unsupported_contract`, `quota_exceeded`
(429 + `Retry-After`), `unprocessable` (itemized validation errors).
G1B implementation notes (no status change): the host backend also
uses `body_too_large`, `malformed_json`, `invalid_submission`
(itemized), `method_not_allowed`, `runs_disclosure_disabled`,
`internal` (500, generic message, nothing internal leaks), and
`temporarily_unavailable` (503 + `Retry-After: 1` on transient
database contention). Every response carries a `headers` map
(`{}` when empty).

## Endpoints

### POST /v1/submissions (upload, authenticated)

Request: `{"consent": {"policy_version": "string",
"accepted_at_utc": "string"}, "install_id": "uuid",
"result": {<edgefit-bench-result v1>}}`.
Behavior: schema/hash/bounds validation → dedup by content hash →
`202` with `{"submission_id": "ulid", "eligibility": "SUBMITTED"}`
or `200` with `{"duplicate_of": "ulid"}` for exact replays.
Rejections are itemized and never stored. Body cap 128 KiB.

### GET /v1/submissions/{id} (authenticated, owner token)

Returns `{"submission_id", "eligibility", "received_at_utc",
"published_at_utc|null", "rejection_reasons": []}`.

### DELETE /v1/submissions/{id} (authenticated, consent withdrawal)

Marks `revoked`; row tombstoned and excluded from aggregates.
Returns `200` with the new state.

### GET /v1/benchmarks/aggregates (public)

Query: `model_sha` (required), `config_hash?`, `build?`
(`effective_opt` + status, required to distinguish builds),
`device_class?`, `workload_id?` (default `edgefit-prompt-v1`),
`prompt_sha?` (G1B: isolates exact prompt bytes within a workload).
Cohort identity always includes `prompt_sha`; cohort objects echo it.
Response: `{"groups": [{"model_sha", "config_hash", "build",
"device_class", "mean_tps", "median_tps", "n", "install_count",
"mean_abs_pct_error|null", "updated_at_utc"}]}`.
Only PUBLISHED rows; groups below quorum are omitted with
`quorum: {"min_n", "min_installs"}` echoed (owner-approved
provisional values 5/3; activation needs qualification). Counts are
independent installs (salted install hashes) until hardware
attestation exists. Aggregates summarize device-reported,
rule-qualified measurements; they are not authenticated hardware
proof. Prediction-error statistics appear only where loop
coverage exists, else null.

### GET /v1/benchmarks/runs (PROPOSED — disabled until approved)

Individual-run disclosure is a SEPARATE consent scope from aggregate
publication (acceptance correction): aggregate-only is the default,
and this endpoint stays disabled until its disclosure policy is
reviewed and explicitly approved. Draft shape when enabled — query:
same filters + `cursor?`, `limit?` (1–50, default 20); response:
run summaries (run id, model sha, config hash, device class, tps,
tokens, status COMPLETED, collected date) for opted-in PUBLISHED
rows only. No install ids, no raw prompts beyond the workload id.

### GET /v1/models (public)

Qualified model artifacts: `[{"sha256", "display_name",
"size_bytes", "license_id", "run_count"}]`.

EF-MODEL-ARCH-01 extension (PROPOSED): verified catalog with
optional `architecture`, `gguf_type`, `quantization`,
`tokenizer`, `runtime_support`, `min_runtime_version`,
`size_bytes`, `estimated_working_set` (with derivation/status),
`license_id`, `source_id`, `artifact_sha256`,
`metadata_verified_at`, `catalog_status`. Illustrative proposed
fields; final contract and verification rules need independent
review. Run count MUST NOT be read as compatibility proof.

### GET /v1/evidence/model-candidates (public, PROPOSED)

Bounded catalog-plus-published-cohort retrieval for model
discovery. Allowlisted versioned inputs: device class, ABI,
runtime/backend, memory envelope, workload id / prompt hash,
intended context, `limit` (max 5). Exact security/privacy rules
govern which device fields may be sent; no private local
measurements, user identity, API keys, or device serials are
required or accepted. Response: compatibility metadata, cohort
identities, and PUBLISHED stats (or explicit
`insufficient_evidence`) — never raw community rows. Query
scope is validated to prevent enumeration of unpublished
groups or backdoor retrieval of below-quorum statistics. The
model artifact catalog and the benchmark evidence/aggregate
store remain distinct data domains even if deployed in one
database; a family label is never a substitute for an exact
artifact SHA-256.
G1B rule (implements ef-stats-01 cross-model reads): each
exact cohort qualifies independently under the 5-run /
3-install quorum; a candidate without a quorum-passing cohort
returns `insufficient_evidence` with null means; published
candidates report statistics from ONE representative
quorum-passing cohort (largest, deterministic tie-break),
never a pooled cross-cohort mean.

### Internal: evidence_retrieve (not HTTP)

Server dispatcher function for Muse tool calls. Fixed name,
exact fields `{device_class, model_id, limit}` with pinned value
sets extended by backend qualification, deterministic order, hard
cap 3. The underlying query is fixed and parameterized; model
input never becomes SQL. Returns aggregate cites only. Extends
EF-META-05 behavior.

EF-MODEL-ARCH-01 extension (PROPOSED): separately versioned
fixed-schema operations such as
`retrieve_published_model_candidates` and
`retrieve_published_cohort_aggregates`. These tool names are
proposals, not implemented functions. Public and internal
interfaces share eligibility/policy enforcement; internal tool
access MUST NOT become an alternate path around consent or
quorum (FR-EVID-02).

## Quotas and provenance

Per-token: 20 submissions/day; global: 1000 requests/minute/IP on
reads. Every stored row keeps source hash, app/collector versions,
receipt time, and eligibility history. Anomaly bounds per group
reject implausible throughput; velocity caps flag device floods
for quarantine review.
G1B: quota is claimed atomically (one statement; concurrent
uploads cannot over-admit); rejected claims roll back and answer
`429` with `Retry-After` to the next UTC midnight; invalid
bodies never consume quota. Global per-IP read limits remain a
deployment-edge responsibility, not implemented in the handler.

## Worked example: public aggregate

`GET /v1/benchmarks/aggregates?model_sha=<64hex>` →

```json
{"api_version": "1.0", "groups": [{"model_sha": "<64hex>",
"config_hash": "<64hex>", "device_class": "android-s22ultra-physical",
"mean_tps": 18.4, "median_tps": 18.1, "n": 12, "device_count": 4,
"updated_at_utc": "2026-10-27T00:00:00Z"}],
"quorum": {"min_n": 3, "min_devices": 2}}
```

Values illustrative (the worked example above predates the
reconciled 5/3 proposal and stays illustrative only). No endpoint
is implemented; this contract governs the future backend build.

## EF-ARCH-02 reconciliation notes (PROPOSED)

- `/v1/benchmarks/runs` is the FUTURE optional individual
  public-run disclosure mechanism, currently PROPOSED and disabled
  pending disclosure-policy approval: when enabled, opted-in
  PUBLISHED rows only, no install ids, no raw prompts. Quorum
  applies to aggregates, not to run listing.
- `device_count` is renamed `install_count` with salted-hash
  semantics (ADR-005/ADR-018); hardware-attested counts stay out
  of scope until an attestation mechanism exists.
- The two existing S22 Ultra workstation rows are different build
  cohorts (n=1 each) and MUST NOT appear in any published
  aggregate; a present-day query must answer insufficient-evidence.
