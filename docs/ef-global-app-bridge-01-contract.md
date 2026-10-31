# EF-GLOBAL-APP-BRIDGE-01 integration contract (v1.0, PROPOSED)

Versioned client/server handshake for "Contribute to EdgeFit
Global" (Android) and the future website contribution channel.
Readers: the global-backend session, the Android session, and the
future website session. Each MUST be able to implement from this
document alone, without guessing backend behavior.

Status words: IMPLEMENTED (in the backend commit below, host
tested) vs PROPOSED (designed here, not built) vs UNAVAILABLE
(does not exist anywhere yet). Every behavioral claim cites the
source file and line that proves it; every receipt and error
below is genuine output of the backend at the pinned commit,
run locally on an isolated database and labeled LOCAL-HOST.
Nothing here is a staging receipt: staging does not exist.

## 1. Backend branch and commit provenance

- Repository: `https://github.com/Ron-Bee/EdgeFit-AI`
- Branch: `implement/ef-global-01-2026-10-30`
- Backend commit: `71a3952e8fa11ad11f25500425c9fafb3896ff6a`
  (`EF-GLOBAL-01 G2-B1: qualify async Worker and D1 integration
  locally`; parent `6f39c7b`, G2-A).
- Host qualification at that commit: `npm test` 131/131 PASS in
  `cloud/` (Node 24, zero dependencies).
- API version: `"1"` (`cloud/validate.js:6`, `API_VERSION`).
- Methodology version: `"ef-stats-01"` (`STATS_VERSION`).
- HTTPS staging origin: **UNAVAILABLE**. No Worker is deployed,
  no D1 database exists, no DNS is configured. The Android and
  website sessions MUST treat the origin as a configuration value
  to be supplied at staging qualification, never hardcode one,
  and fail closed with "staging unavailable" until it is provided
  through an owner-approved channel.

## 2. API version and endpoint definitions

Base: `{staging_origin}/v1` (origin UNAVAILABLE, see §1).
Content type is always `application/json`, requests and responses.
Method/path routing rejects in three distinct cases
(`cloud/worker.js`, `cloud/api.js:675-704`): a method outside
GET/POST/DELETE is `405 method_not_allowed` at the Worker edge
on ANY path; a path outside `/v1/` is `404 not_found` at the
edge; inside `/v1/`, an otherwise-allowed method on a
recognized route that does not define it is `405
method_not_allowed` from the dispatcher — through the Worker
this is exactly one case, POST on `/v1/submissions/{id}` —
while every other unrecognized route/method combination is
`404 not_found` (e.g. GET or DELETE on the `/v1/submissions`
collection, POST on `/v1/models`). NOT every method/path
mismatch is 405. The error envelope is always
`{"error": {"code", "message", "details"?}}`; `details` appears
only on `invalid_submission`.

| Operation | Auth | Success | Notes |
|---|---|---|---|
| `POST /v1/submissions` | Bearer install token | `201` fresh, `200` duplicate replay | §5 receipts; §9 idempotency |
| `GET /v1/submissions/{id}` | Owner's token | `200` owner view | Foreign/missing id: `404` (no oracle); unknown token: `401` |
| `DELETE /v1/submissions/{id}` | Owner's token | `200` REVOKED, idempotent | Terminal; see §8, §10 |
| `GET /v1/benchmarks/aggregates?model_sha=…` | None | `200` groups | Quorum-gated; thin cohorts listed, never averaged (§12) |

`GET /v1/benchmarks/runs` exists but is DISABLED (`404`
`runs_disclosure_disabled`) until its policy is approved
(contract §runs, `cloud/api.js`). There is deliberately NO
token-issuance, admin, migration, or health endpoint (§7).

## 3. Complete valid submission fixture (SYNTHETIC)

Validator verdict on this exact envelope at the pinned commit:
`ok=true`, zero rejections. All values are synthetic fixtures;
`display_name`, `mem.method`, and `provenance` say so inline.
An Android client sends the same SHAPE with genuine measured
values (see §13 for the per-field mapping).

```json
{
  "result": {
    "contract": "edgefit-bench-result",
    "contract_version": 1,
    "run_id": "BENCH-BRIDGE-001",
    "device": {
      "profile_ref": "0000000000000000000000000000000000000000000000000000000000000000",
      "model": "SM-S908U",
      "sdk_int": 36,
      "supported_abis": ["arm64-v8a"],
      "logical_processors": 8,
      "total_ram_bytes": 12884901888
    },
    "model": {
      "display_name": "SYNTHETIC-FIXTURE.gguf",
      "size_bytes": 807143936,
      "sha256": "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
      "quantization": "4",
      "metadata": {}
    },
    "runtime": {
      "impl": "llama.cpp",
      "version": "0.7.0-dev",
      "backend": "cpu",
      "abi": "arm64-v8a"
    },
    "config": {
      "n_ctx": 512,
      "n_threads": 4,
      "n_predict": 64,
      "temperature": 0,
      "sampler": "greedy",
      "seed": null,
      "timeout_ms": 600000
    },
    "workload": {
      "id": "edgefit-prompt-v1",
      "prompt_sha256": "36104db0657a294048410f9043d4efc8510a77088ca1e2f17c0b6c8359c44382",
      "prompt_chars": 100
    },
    "measurements": {
      "load_ms": 1200,
      "prefill_ms": 1000,
      "prefill_tokens": 23,
      "gen_ms": 4000,
      "generated_tokens": 64,
      "tps_gen": 16,
      "tps_prefill": 23,
      "eos_stop": false,
      "mem": {
        "pss_before_kb": 100,
        "pss_after_kb": 200,
        "alloc_delta_bytes": 4096,
        "method": "synthetic-fixture"
      },
      "thermal": {
        "before": "none",
        "mid": "none",
        "after": "none",
        "available": true
      }
    },
    "status": "COMPLETED",
    "error": null,
    "provenance": "synthetic_contract_fixture",
    "app_version": "0.2.0",
    "collected_at_utc": "2026-10-30T11:00:00Z",
    "x_effective_opt": { "value": "O2", "status": "operator_attested" },
    "x_mem_conditions": {
      "avail_pct_start": 24.7,
      "below_threshold_start": false,
      "low_memory_start": false
    }
  },
  "consent": {
    "policy_version": "v1",
    "accepted_at": "2026-10-30T11:30:00Z",
    "scopes": ["aggregates"]
  }
}
```

Field rules (`cloud/validate.js:69-399`): `run_id` matches
`^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$`; `collected_at_utc` ISO-8601
within [2020-01-01, server-now + 5 min]; `status` one of
COMPLETED/FAILED/TIMEOUT/CANCELLED; `error` null or ≤2048 chars;
`app_version` 1..64 chars; `device.model` 1..64 printable chars
(becomes `device_class` after trim); `sdk_int` 21..99;
`supported_abis` 1..8 strings ≤32 chars; `logical_processors`
1..1024; `total_ram_bytes` 0..2^48; `model.display_name` 1..256
chars; `size_bytes` 0..2^48; `model.sha256` 64 lowercase hex;
`quantization` 1..64 chars; `metadata` optional bounded scalar
map (≤64 keys ≤128 chars; string values ≤1024 chars, numbers,
booleans); `runtime.impl/version` ≤64 chars, `backend/abi` ≤32
chars, all required non-empty; `n_ctx`/`n_predict` 1..1048576;
`n_threads` 1..1024; `temperature` any finite number;
`sampler` ≤32 chars required; `seed` integer or null;
`timeout_ms` 1..2^48; `workload.id` 1..64 chars;
`prompt_sha256` 64 lowercase hex; `prompt_chars` 0..2^32;
`load_ms/prefill_ms/gen_ms` 0..2^48 integers;
`prefill_tokens/generated_tokens` 0..2^32 integers;
`tps_gen/tps_prefill` finite ≥ 0; `eos_stop` boolean;
`mem.pss_*` 0..2^48, `alloc_delta_bytes` |.| ≤ 2^48,
`mem.method` 1..64 chars; `thermal.before/mid/after` 1..32
chars, `thermal.available` boolean. `x_effective_opt` optional:
`value` in O0/O1/O2/O3/Os/Oz/unknown, `status` in
operator_attested/self_declared/unverified (absent →
`unknown:unverified`). `x_mem_conditions` optional:
`avail_pct_start` 0..100, two booleans, all nullable.

ACCEPTED-BUT-IGNORED (client warning, §16): `device.profile_ref`
and `result.provenance` are not validated and not stored as
columns — they survive only inside the opaque `result_json`
blob. A client MUST NOT rely on them for identity, dedup, or
provenance; see §11 for the origin-classification gap.

Server-derived columns for this fixture (genuine validator
output): `content_hash 48860e36…98179` (sha256 of canonical
JSON of `result` only), `device_class "SM-S908U"`,
`config_hash 64e875bc…48d2f`
(`sha256("v1|512|4|64|greedy")`), `build_key
"O2:operator_attested"`, `gen_tokens 64`, `prompt_tokens 23`,
`consent_*` echo. Canonical JSON sorts object keys, so key
order never affects the content hash or dedup.

## 4. Complete invalid submission fixtures (SYNTHETIC)

Each fixture mutates ONLY the named field(s) of §3. Each
response is genuine LOCAL-HOST output (`status` + body).

1. Wrong contract version (`result.contract_version: 2`):
   `422 invalid_submission`, details
   `[{field: "result.contract_version",
   reason: "unsupported contract version"}]`.
2. Disabled scope (`consent.scopes: ["aggregates", "runs"]`):
   `422`, `consent.scopes` /
   `"runs disclosure is disabled until its policy is approved"`.
3. Missing consent (no `consent` key): `422`, `consent` /
   `"consent must be an object"`. There is NO submission
   without a consent object — enforced, not advisory.
4. `accepted_at_utc` instead of `accepted_at`: `422`,
   `consent.accepted_at` / `"must be ISO-8601"`. THIS IS THE
   DOCUMENTED DOC-DRIFT PROOF: the field MUST be
   `accepted_at`; see §6.
5. Bad model SHA (`"xyz"`): `422`, `result.model.sha256` /
   `"must be 64 lowercase hex chars"`.
6. Future collection (`collected_at_utc: 2027-01-01`): `422`,
   `result.collected_at_utc` / `"timestamp is in the future"`.
7. Missing/unknown bearer token: `401 unauthorized` /
   `"valid per-install upload token required"`, nothing stored.
8. Malformed JSON body (`{oops`): `400 malformed_json` /
   `"body is not JSON"`.
9. Oversized body (> 131072 UTF-8 bytes): `413 body_too_large` /
   `"submission exceeds 128 KiB"`, enforced at the edge before
   reading (Content-Length) and after reading (byte count),
   then again in the handler.

## 5. Expected HTTP response fixtures (genuine LOCAL-HOST)

`submission_id` values below are the local counter format
(`SUB-` + 32 digits); staging issues `SUB-` + 32 lowercase hex
(`cloud/worker.js:75`). Clients MUST treat the id as an opaque
string matching `[A-Za-z0-9-]+` (route regex, `api.js:680`).

Fresh receipt (`POST`, new bytes):
```json
{ "status": 201, "submission_id": "SUB-00000000000000000000000000000001",
  "eligibility": "SUBMITTED", "duplicate": false }
```

Duplicate replay (same canonical `result` bytes, any install):
```json
{ "status": 200, "submission_id": "SUB-00000000000000000000000000000001",
  "eligibility": "SUBMITTED", "duplicate": true }
```
The replay returns the FIRST install's id and CURRENT
eligibility; replays never consume quota. (Cross-install
content-existence disclosure is the recorded R3 caveat.)

Owner status (`GET /v1/submissions/{id}`, own token):
```json
{ "status": 200, "submission_id": "SUB-00000000000000000000000000000001",
  "run_id": "BENCH-BRIDGE-001",
  "content_hash": "48860e3689650443e6eb5f0b9f67966cc2fdceb75677e3addd2f74e796698179",
  "eligibility": "SUBMITTED",
  "received_at": "2026-10-30T12:00:00.000Z", "published_at": null,
  "eligibility_history": [
    { "from_state": null, "to_state": "SUBMITTED",
      "reason": "received", "at": "2026-10-30T12:00:00.000Z" } ] }
```

Foreign install's token on the same id: `404 not_found` /
`"no such submission"` (missing and foreign are
indistinguishable — no cross-install oracle). Unknown token:
`401`. Disabled runs route: `404 runs_disclosure_disabled`.
Filter violations on `GET /v1/benchmarks/aggregates` and
`GET /v1/evidence/model-candidates` are `400 bad_request`:
malformed `model_sha`/`config_hash`/`prompt_sha`, bad `build`
shape, empty or overlong filter strings, `limit` outside 1..5,
or `intended_n_ctx` out of range — the client corrects the
parameters and never re-sends the offending request
unchanged. Wrong-method rejections are `405
method_not_allowed` per the §2 routing rules. Both name the
offending expectation in `message`.

Aggregates with 2 PUBLISHED runs (below quorum): `200` with
`api_version "1"`, `stats_version`/`methodology "ef-stats-01"`,
`tier "global_eligible"`, `quorum {min_runs 5, min_installs 3}`,
one group with the full cohort object, `cohort_hash`,
`n 2`, `installs 2`, `status "insufficient-evidence"`,
`reason "below publication quorum"`, `aggregates null`,
`truncated false`. Below-quorum means NO statistics, exactly
as shown — never zeros, never partial means.

Withdrawal (`DELETE`, own token): `200 {submission_id,
eligibility: "REVOKED"}`; repeating the DELETE returns the
identical body (idempotent).

Quota exhaustion: `429 quota_exceeded` with a `retry-after`
header of seconds-to-UTC-midnight (e.g. `43200` at UTC noon).
Database contention: `503 temporarily_unavailable` with
`retry-after: 1`. Both headers pass the Worker edge
unmodified; no other handler header crosses it.

## 6. Field-by-field consent requirements

Consent is per submission, explicit, and enforced BEFORE any
storage or quota accounting (`cloud/api.js:101-130`,
`validate.js:335-356`):

- `policy_version`: string, 1..32 chars, REQUIRED. Recorded
  verbatim on the row. WARNING: the server performs NO
  allowlist check — any 1..32-char string passes. The
  fixtures use `"v1"`, but no consent-policy-v1 CONTENT has
  been reviewed or approved (owner decision H-§5, G1 §H.5).
  Android MUST send the exact policy version the user saw,
  and MUST NOT treat server acceptance as policy approval.
- `accepted_at`: ISO-8601 string, REQUIRED, must not be more
  than 5 minutes in the server's future. MUST be named
  exactly `accepted_at`.
- `scopes`: non-empty array REQUIRED; every entry MUST be
  `"aggregates"`. `"runs"` is rejected with its own message
  until the runs-disclosure policy is approved; anything
  else is `"unknown scope requested"`.

DOCUMENTATION DRIFT, reconciled (do not re-litigate in
client code): `docs/ef-cloud-01-api-contract.md:52-57`
(spec text, PROPOSED) names the field `accepted_at_utc` and
the receipts `202` / `duplicate_of`. The IMPLEMENTATION uses
`accepted_at` and `201` / `duplicate`, proven by fixture §4.4
above. THIS CONTRACT FOLLOWS THE IMPLEMENTATION. The spec
text needs an owner-approved touch-up (audit items V1/V3,
carried); the backend is NOT altered to match the stale
text, and clients MUST send `accepted_at` and expect `201`.

## 7. Credential lifecycle (IMPLEMENTED owner-issued system)

Source: `cloud/api.js:74-89` (lookup), `cloud/schema.sql:9-14`
(`installs`), staging design §4 (H9 procedure).

- Format: `Authorization: Bearer <token>` where `<token>`
  matches `[A-Za-z0-9._~+/-]{8,256}=*` after trimming
  (`api.js:74-78`). Shorter than 8 chars, wrong characters,
  or a missing header is `401` before any other work.
- Binding: one token per install, stored ONLY as
  `sha256hex(token)` with `install_id` and `created_at`.
  The server never stores, logs, or returns the secret.
- Issuance (staging): MANUAL, owner-operated. ≥128-bit CSPRNG
  token per install; the owner inserts
  `(install_id, token_hash, created_at, NULL)` by direct,
  logged database write and delivers the secret to the
  device out of band. THERE IS NO ISSUANCE ENDPOINT and
  none is proposed here. The first Android integration uses
  ONE controlled owner-issued staging token.
- Verification: equality match on the hash; row missing or
  `revoked_at` set → `401`. (Timing-attack hardening is the
  recorded R5 caveat; token entropy is the mitigation.)
- Revocation/rotation: set `revoked_at` (immediate effect on
  next request). Rotation = issue a new token + revoke the
  old. Prior rows STAND (no retroactive invalidation); the
  rotated install re-registers by the same manual path.
- Lost token: NO self-service recovery exists. Policy: the
  install is revoked by the owner and re-enrolled as a NEW
  install id (its prior rows keep their history; future
  uploads count under the new identity).
- Quota: 20 submissions per install per UTC day, claimed
  atomically with the insert; rejected claims are compensated
  in-batch so counters stay exact. Invalid bodies never
  consume quota; replays never consume quota. `429` carries
  `retry-after` to the next UTC midnight. Global per-IP read
  limits are an edge responsibility, NOT implemented in the
  handler (H10; must be demonstrated before public exposure).
- Logging/errors: auth failures return the fixed `401`
  envelope; responses and logs MUST never contain tokens,
  hashes, salts, or install ids (proven locally by
  G2B-W-K1/M1/M2).
- EXPIRATION: **NOT IMPLEMENTED.** There is no `expires_at`
  column and no time check; only revocation ends a token's
  life. DO NOT claim, document, or depend on expiration.

PROPOSED judge-friendly workflow (separate, NOT built): a
future invitation/redeem flow could mint limited-use,
expiring invitation codes redeemed out of band for a
standard per-install token. That proposal changes no
server behavior above; invitations would be an operational
wrapper around manual issuance, with use-count and expiry
tracked in runbook records — NOT new auth semantics — until
a designed, reviewed, and tested implementation exists.

## 8. Eligibility state machine (IMPLEMENTED)

States: `SUBMITTED → VALIDATED → QUALIFIED → PUBLISHED`,
with `REVOKED` reachable from ANY state and TERMINAL
(`cloud/qualify.js`, `cloud/api.js:314-369`).

- Promotion is server-side ONLY, by rule, one guarded step
  at a time: SUBMITTED→VALIDATED (stored-envelope schema
  recheck), VALIDATED→QUALIFIED (anomaly/velocity suite:
  throughput ≤ 100000 tps, COMPLETED reconciles
  tokens×1000/ms within 0.1%, earliest run_id wins,
  24h install velocity ≤ quota), QUALIFIED→PUBLISHED
  (consent still carries `aggregates`). Each step is a
  compare-and-swap batch naming the expected from-state; a
  concurrently revoked row is never promoted or resurrected
  (proven under a held-open barrier in acceptance Step C6).
- Every applied transition appends
  `(from_state, to_state, reason, at)` to
  `eligibility_history`; holds and deferrals append nothing.
  `published_at` is set exactly at PUBLISHED.
- There is NO global lock and NO scheduled job in this
  repo: qualification runs when the operator invokes
  `runQualification` (a Cron trigger is staging design,
  PROPOSED). Concurrent passes are safe: losing CAS steps
  defer to the next pass. Clients MUST treat promotion as
  asynchronous and poll `GET /v1/submissions/{id}`.
- Presentation mapping (for §15 Explorer): SUBMITTED =
  received; VALIDATED = structurally valid; QUALIFIED =
  eligible under the rules; PUBLISHED = in the aggregate
  pool; REVOKED = withdrawn, tombstoned, excluded
  everywhere. No other states exist; do not invent any.

## 9. Retry and idempotency rules (IMPLEMENTED behavior)

- The content hash is the idempotency key: re-POSTing byte-
  identical canonical `result` returns the SAME
  `submission_id` with `duplicate: true` and the row's
  CURRENT eligibility — safe to retry after any ambiguous
  failure (timeout, dropped connection, 503, 500). There is
  no client-supplied idempotency token; do not add one
  client-side.
- `429`: stop and wait the `retry-after` seconds (to UTC
  midnight); the claim was compensated, nothing was stored.
- `503`: retry after 1 second with bounded backoff; the
  batch rolled back, nothing was stored.
- `500 internal`: NOT receipted — NOTHING about the outcome
  is known. Safe behavior: re-POST the identical bytes;
  a `duplicate: true` answer proves the first attempt had
  committed; a fresh `201` proves it had not. Either way
  exactly one row results.
- `4xx` (except 429): do NOT blindly retry — fix the
  request first. `400 bad_request` means a filter parameter
  is malformed (correct it, never re-send it unchanged);
  `405 method_not_allowed` means the method is wrong for
  the route (§2); `422` details name every offending field.
- Interrupted upload: re-POST identical bytes (§4.9 size
  cap still applies). Partial server state is impossible:
  each submission is one atomic batch.

## 10. Revocation behavior (IMPLEMENTED, terminal)

- `DELETE /v1/submissions/{id}` with the owner's token sets
  `REVOKED`, appends history
  `(from_state, REVOKED, "consent withdrawn by owner", at)`,
  and returns the new state. Repeating returns the same
  body. Foreign/missing ids are `404`; unknown tokens `401`.
- REVOKED is terminal: qualification never promotes a
  revoked row, never appends history to it, and never
  re-qualifies it (CAS guards + acceptance Step C6/I2).
- Revoked rows are excluded from aggregates, candidates,
  models, and Muse retrieval — verified on every surface
  in-suite. Past published AGGREGATES are snapshots and are
  never silently restated (stats contract); future reads
  exclude the row.
- Demo rule: withdrawal MAY be demonstrated on staging;
  the receipt MUST be presented as "contribution withdrawn",
  never as publication or verification of anything.

## 11. Evidence provenance limitations (BINDING)

Read this section twice before designing clients or demos.

1. A submission receipt (`201`) proves RECEIPT of bytes that
   passed structural validation. It proves NOTHING about
   physical execution, device identity, independent
   installation, measurement authenticity, qualification, or
   publication. Every demo MUST state this distinction.
2. The backend stores NO evidence-origin classification:
   there is no `origin`/`source`/`channel` column
   (`schema.sql`), and `result.provenance` is accepted but
   NEITHER validated NOR stored as a column (it survives
   only inside the opaque `result_json` blob). Device-
   contributed and website-imported bytes are therefore
   INDISTINGUISHABLE to every query, cohort, quorum count,
   and retrieval path. THIS IS THE REPORTED GAP: website-
   imported evidence MUST NOT enter the qualified
   statistical population until an approved provenance
   policy plus an enforceable origin column (new migration,
   reviewed) exist. Do not reinterpret `app_version`,
   `provenance` text, or any cohort field as that gate.
3. `device_class` is the client's exact `device.model`
   string, unauthenticated (H6: exact strings, no
   attestation claim). `install_id` is a random per-install
   secret (ADR-005 PROPOSED method), counted only as salted
   hashes — NEVER hardware attestation, NEVER a stable
   device identity across reinstalls.
4. `x_effective_opt` defaults to `unknown:unverified` when
   absent; `operator_attested` is a client assertion the
   server records, not a server verification.
5. Consent `policy_version` is RECORDED, not allowlisted
   (§6): server acceptance of `"v1"` is not policy approval.
6. Website-imported JSON is SELF-REPORTED by definition
   (§15): file possession proves nothing about execution.
   Same population bar as (2), plus enrollment policy.

## 12. Exact cohort and publication requirements (IMPLEMENTED)

Cohort identity v2 (`cloud/cohort.js:21-43`, the ONLY
grouping implementation, shared by aggregates, candidates,
and Muse retrieval): exact match over ALL of `model_sha`,
`quantization_label`, `device_class`, `workload_id`,
`prompt_sha`, `runtime_impl`, `runtime_backend`, `build_key`,
`config_hash` — where `build_key` is
`effective_opt:effective_opt_status` and `config_hash` is
`sha256("v1|n_ctx|n_threads|n_predict|sampler")`.
`cohort_hash` is `sha256("v2:" + JSON array of the nine
fields)`. Groups sort by `cohort_hash`. NOT grouped (same
cohort may span them): `runtime_version`, `app_version`,
`temperature`, `seed`, `collected_at`, `tps` values (risks
R1/R2/R11, carried).

Publication (provisional owner policy H2, staging-testing
only): a cohort publishes statistics iff it holds ≥ 5
PUBLISHED runs from ≥ 3 independent installs
(`passesQuorum`). Aggregates use PUBLISHED + COMPLETED +
`tps_gen > 0` rows only. Thin cohorts are LISTED with
`status "insufficient-evidence"`, counts, and
`aggregates: null` — never widened, never averaged, never
zero-filled. Candidates report ONE representative
quorum-passing cohort (largest, deterministic tie-break),
never a pooled mean; candidates without one are
`insufficient-evidence` with null means. Production
activation of these thresholds is SEPARATELY gated and NOT
granted by this contract.

## 13. Android responsibilities (PROPOSED client, NOT verified)

The Android app is built in a separate session; NOTHING in
this section is implemented or tested here. The Android
session owns every item below against THIS contract.

Test matrix (each row: precondition, action, expected
server/client behavior per the cited section):

1. Offline benchmark completion: run finishes without
   network; result JSON conforms to §3 shape locally.
2. User-initiated contribution: no upload path executes
   without an explicit tap (ADR-004/ADR-011).
3. Evidence preview: the app shows EXACTLY the bytes to be
   sent (canonical §3 envelope with genuine values) before
   consent; no hidden fields.
4. Explicit consent: per-submission tap producing
   `policy_version` (the version SHOWN), `accepted_at`
   (tap time, ISO-8601), `scopes ["aggregates"]` (§6).
5. Accepted policy version: the sent `policy_version`
   equals the displayed policy; mismatch is a client bug.
6. Valid serialization: envelope validates (§3 rules);
   `Content-Type: application/json`; UTF-8 body ≤ 131072
   bytes — measure BYTES, not characters.
7. Valid credential: `Authorization: Bearer <token>` with
   the owner-issued install token (§7); token stored in
   app-private storage, never logged, never screenshotted.
8. Unauthorized device: unknown/missing token → `401`;
   the app shows "not enrolled", stores nothing remotely,
   and offers no retry-loop.
9. Duplicate retry: re-POST after an ambiguous failure →
   `200 duplicate: true` with the ORIGINAL id (§9).
10. Interrupted upload: connectivity drops mid-POST →
    re-POST identical bytes; exactly one row results (§9).
11. Timeout/503: bounded backoff honoring `retry-after: 1`;
    no row exists until a receipt arrives (§9).
12. 429: stop until `retry-after` elapses; show quota state
    honestly; do not burn retries.
13. Receipt persistence: on `201`/`200`, persist
    `(submission_id, eligibility, duplicate, received_at)`
    locally, bound to the originating run/session id.
14. Status lookup: `GET /v1/submissions/{id}` with the same
    token; render eligibility + history trail (§5, §8);
   POLL — promotion is asynchronous and operator-driven.
15. Revocation: user-initiated DELETE → `200 REVOKED`;
    the app shows "withdrawn", keeps the tombstone receipt,
    and never re-submits the revoked bytes silently.
16. No submission without consent: every code path to POST
    requires a fresh tap + consent object (§4.3 proves the
    server rejects consent-less envelopes with `422`).
17. No private app data: the envelope contains ONLY §3
    fields — no Meta keys, no key material, no exports,
    no logs, no identifiers beyond `run_id`/`device.model`
    (which the server treats as unauthenticated, §11.3).

Android-side dependencies NOT YET PROVEN (from this side):
token delivery to the device, token storage mechanism,
consent-screen implementation, preview fidelity, receipt
store, poll scheduling, and every matrix row above. No
Android implementation result is claimed or invented here.

FIRST CONTROLLED INTEGRATION EXPERIMENT (defined, NOT
executed — requires staging qualified synthetic-only
first, per the G2-B2 plan, plus separate owner approval
for the physical upload): one genuine Android session on
the enrolled install submits ONE real benchmark envelope;
expected: `201 SUBMITTED`, owner `GET` shows the trail,
aggregates show no group (unpublished row); then the
operator withdraws it (`DELETE` → `REVOKED`) and confirms
exclusion on all surfaces. Success = receipt + status +
withdrawal all genuine; quorum is NOT satisfied by one
device and MUST be labeled as such in any demo.

## 14. Backend responsibilities (IMPLEMENTED)

At the pinned commit the backend already honors: the §2
surface with §5 receipts; §3 validation with §4 itemized
errors; §6 consent enforcement before storage/quota; §7
token verification, atomic quotas, and the no-issuance
rule; §8 server-side qualification with CAS terminality;
§9 atomic idempotent retries; §10 revocation; §12 exact
cohorts with no pooling and no below-quorum statistics;
secret/identity-free responses; and the H5 synthetic-only
staging posture. Remaining backend-side work is
operational, NOT behavioral: G2-B2 staging qualification
(X1–X9), H10 edge rate limits before exposure, the H9
manual-issuance runbook execution for the Android token,
and the §16 doc touch-ups after owner approval. No route-
behavior change is required by this contract.

## 15. Website responsibilities (PROPOSED architecture)

Two website surfaces, both PROPOSED and undeployed:

A. "Upload EdgeFit Benchmark" (SECOND channel, subordinate
   to Android). Proposed architecture: static page (no
   backend trust) that (1) reads a user-selected local
   `.json` file ENTIRELY client-side; (2) validates it
   against §3 rules with client-side code mirroring
   `validate.js` (same version pinned); (3) previews the
   exact canonical bytes + derived cohort fields; (4) takes
   explicit consent (§6); (5) requires the contributor's
   enrolled credential (same §7 token class; delivery by
   the same manual path — no browser key generation, no
   token in page source); (6) POSTs to the existing
   endpoint; (7) renders the genuine receipt + status and
   offers owner withdrawal. EVERY server rule applies
   unchanged: validation, quotas, dedup, CAS, revocation.
   GATES (all must clear before activation): the §11.2
   origin-classification gap (website bytes MUST be
   separable from device bytes before they share a
   population); enrollment/anti-abuse policy for browser
   contributors; CORS decision (the Worker currently sends
   NO `Access-Control-*` headers — cross-origin browser
   POSTs fail by default; §16); secret-handling review
   (no token in JS, screenshots, analytics, or logs).
   Until then: design and mock fixtures only. Mock
   fixtures: reuse §3/§4/§5 verbatim (synthetic-labeled);
   no separate mock shapes that could drift from the
   server.
B. Evidence Explorer (read-only). Maps ONLY to §8 states
   and §12 rules: received / structurally-valid /
   eligible / qualified / published-aggregate /
   insufficient-evidence / revoked. Public views show
   authorized aggregates ONLY (quorum-passing means or
   explicit insufficient-evidence; never rows, runs,
   installs, tokens, consent data, or snapshots presented
   as community evidence). Owner views (their token, their
   ids) may show own status + trail. Synthetic staging
   rows MUST be labeled staging/synthetic wherever shown
   and MUST NEVER be presented as live community evidence.

## 16. Open decisions and compatibility risks

1. Staging origin UNAVAILABLE — no client may ship a
   hardcoded origin; fail closed until owner-supplied.
2. Consent-policy-v1 CONTENT unreviewed (G1 §H.5, H5):
   `policy_version "v1"` is recorded, not approved; real
   data needs the policy review first.
3. Doc touch-ups (V1/V3): `ef-cloud-01-api-contract.md`
   still says `accepted_at_utc`/`202`, contradicting §6.
   Owner-approved edit required; backend unchanged.
4. No evidence-origin column (§11.2): blocks website
   bytes from the qualified population; needs a
   provenance policy + reviewed migration.
5. No token expiration (§7): rotation/revocation only.
   Invitation/redeem workflow is PROPOSED, not designed
   in full.
6. No upload FR in the PRD: cloud contribution has no
   numbered functional requirement (FR-CLOUD-* absent);
   recommend the owner charter one before the website
   channel activates.
7. H10 edge rate limits unconfigured: no public exposure
   until demonstrated (plan §9, staging-gated).
8. No CORS headers: blocks browser uploads by default;
   any CORS allowlist is a deliberate, reviewed,
   staging-only decision — never `*` with credentials.
9. `device.profile_ref` / `result.provenance`
   accepted-but-ignored (§3): clients MUST NOT depend on
   them; a future contract version may validate or drop
   them (minor-compatible only with owner review).
10. Cohort-excluded dimensions (R1/R2/R11):
    `runtime_version`, `app_version`, `temperature`,
    `seed` span cohorts; review before real-data
    qualification (H8).
11. `operator_attested` is a client assertion (§11.4),
    not verification; physical comparability review
    pending (H8).
12. R3 cross-install content-existence disclosure on
    duplicate replay: accepted caveat, abuse review at
    staging.
13. Qualification is operator-invoked (no Cron in repo):
    staging runbook must define the trigger before the
    demo needs PUBLISHED rows.
14. One-device demo ≠ quorum: any demo MUST label
    single-install receipts as unqualified (§11.1).
15. Contract evolution: this document is v1.0 pinned to
    backend `71a3952`. Any backend behavior change
    requires a contract minor/major bump with a
    compatibility note; clients pin the contract version
    they implement.

