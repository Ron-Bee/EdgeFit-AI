# EF-CLOUD-01 backend API contract (v1, PROPOSED)

Versioned JSON contract for the EdgeFit cloud backend. Base URL is a
proposed deployment choice — NOT configured, no DNS exists, not
implemented infrastructure:
`https://api.edgefit.ronbeesoftware.com/v1`.

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

Query: `model_sha` (required), `config_hash?`, `device_class?`,
`workload_id?` (default `edgefit-prompt-v1`).
Response: `{"groups": [{"model_sha", "config_hash", "device_class",
"mean_tps", "median_tps", "n", "device_count", "updated_at_utc"}]}`.
Only PUBLISHED rows; groups below quorum are omitted with
`quorum: {"min_n", "min_devices"}` echoed. Aggregates summarize
device-reported, rule-qualified measurements; they are not
authenticated hardware proof.

### GET /v1/benchmarks/runs (public, paginated)

Query: same filters + `cursor?`, `limit?` (1–50, default 20).
Response: run summaries (run id, model sha, config hash, device
class, tps, tokens, status COMPLETED, collected date). No install
ids, no raw prompts beyond the workload id.

### GET /v1/models (public)

Qualified model artifacts: `[{"sha256", "display_name",
"size_bytes", "license_id", "run_count"}]`.

### Internal: evidence_retrieve (not HTTP)

Server dispatcher function for Muse tool calls. Fixed name,
exact fields `{device_class, model_id, limit}` with pinned value
sets extended by backend qualification, deterministic order, hard
cap 3. The underlying query is fixed and parameterized; model
input never becomes SQL. Returns aggregate cites only. Extends
EF-META-05 behavior.

## Quotas and provenance

Per-token: 20 submissions/day; global: 1000 requests/minute/IP on
reads. Every stored row keeps source hash, app/collector versions,
receipt time, and eligibility history. Anomaly bounds per group
reject implausible throughput; velocity caps flag device floods
for quarantine review.

## Worked example: public aggregate

`GET /v1/benchmarks/aggregates?model_sha=<64hex>` →

```json
{"api_version": "1.0", "groups": [{"model_sha": "<64hex>",
"config_hash": "<64hex>", "device_class": "android-s22ultra-physical",
"mean_tps": 18.4, "median_tps": 18.1, "n": 12, "device_count": 4,
"updated_at_utc": "2026-10-27T00:00:00Z"}],
"quorum": {"min_n": 3, "min_devices": 2}}
```

Values illustrative. No endpoint is implemented; this contract
governs the future backend build.
