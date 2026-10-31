# EF-CLOUD-01A Global Evidence Foundation (+ G1B, G2-A local)

Versioned backend foundation for consented multi-device benchmark
evidence. Implements `docs/ef-cloud-01-api-contract.md` (v1) and the
`docs/ef-stats-01-contract.md` methodology annex as runtime-agnostic
JavaScript over plain SQLite (Cloudflare D1 compatible).

**Status: host-qualified foundation. Nothing here is deployed.**
There are no production Cloudflare resources, no DNS records, no
accounts, and no community data. All tests use isolated databases
(`:memory:`, plus self-cleaning temp files for the concurrency
harness) with synthetic fixtures that are never presented as
community measurements.

G1B applied the `docs/ef-global-01-g1-audit.md` integrity
corrections (D1-D8) to this foundation; see "Trust rules" and
"Transaction semantics" below. No ADR status changed.

G2-A (owner decision, local staging-compatibility only) converted
the three atomic units to D1-batch-compatible guarded writes
(`db.batch`, no mid-batch reads), added versioned migrations
(`migrations/001_init.sql`), and a D1-shape interface-conformance
suite. Nothing is provisioned or deployed; D1 service behavior is
still UNVERIFIED. See `docs/ef-global-01-g2-staging-design.md`.

## Layout

- `schema.sql` — D1-compatible DDL: installs, submissions,
  eligibility_history, daily_counts.
- `db.js` — node:sqlite adapter behind
  `{ get, all, run, exec, batch, close }`; deployment swaps this
  file for a D1 binding implementing the same batch contract
  (array in, results out, atomic) without touching routes,
  validation, or stats.
- `migrations/` — versioned DDL for `wrangler d1 migrations`
  (`001_init.sql` = the initial schema, drift-guarded by test).
- `cohort.js` — canonical cohort identity (G1B): the ONE shared
  grouping/qualification implementation behind aggregates,
  candidates, and Muse retrieval.
- `validate.js` — strict submission-envelope validation with
  itemized rejections; canonical JSON for content hashing.
- `api.js` — `handleRequest({ method, path, query, headers,
  bodyText, db, nowMs, deps })` → `{ status, body, headers }`.
  A Workers entry point maps fetch events onto it.
- `qualify.js` — `runQualification(db, nowMs, deps)`: rule-driven
  SUBMITTED → VALIDATED → QUALIFIED → PUBLISHED, with holds and
  compare-and-swap transitions.
- `retrieval.js` — `evidenceRetrieve(db, params, deps)`: the fixed
  allowlisted query behind future server-side Muse retrieval.
- `stats.js` — mean, median, IQR (linear-interpolation quantiles).
- `test/` — node:test suites, 93 tests, all isolated, incl.
  `d1shape.js` (D1-shape interface adapter for tests) and the
  `g2-d1shape` conformance suite.

Run: `npm test` (Node 24+; no dependencies).

## Routes (all v1)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/v1/submissions` | install token | validate, dedup, store SUBMITTED |
| GET | `/v1/submissions/{id}` | owner token | own receipt + eligibility trail |
| DELETE | `/v1/submissions/{id}` | owner token | consent withdrawal → REVOKED |
| GET | `/v1/benchmarks/aggregates` | none | quorum-gated cohort statistics |
| GET | `/v1/models` | none | observed artifacts + published counts |
| GET | `/v1/evidence/model-candidates` | none | bounded candidate evidence |
| GET | `/v1/benchmarks/runs` | — | **disabled** (404 + reason) |

Error envelope: `{ error: { code, message, details? } }`.
Responses carry `headers` (`{}` when empty); `429` quota and `503`
busy responses include `retry-after` in seconds.

## Trust rules enforced in code

- Per-install upload tokens (SHA-256 hashed at rest), owner-issued;
  no self-registration endpoint. Revoked installs are 401.
- Explicit per-submission consent: policy version, timestamp, and
  scopes. Only `aggregates` is accepted; `runs` is rejected until
  its separate disclosure policy is approved.
- Content-hash deduplication: identical bytes return the first
  submission id with `duplicate: true`, never a second row.
  Duplicate races resolve to the committed winner, never an error.
- Quota 20 uploads/install/day (UTC), claimed atomically in one
  statement; over-quota claims roll back and answer `429` with
  `retry-after` to the next UTC midnight. Invalid bodies never
  burn quota. Velocity floods (more than quota inside a sliding
  24h) hold for review.
- Eligibility transitions only through `runQualification` rules,
  each a compare-and-swap transaction writing
  `eligibility_history`. REVOKED is terminal, wins every race,
  and is excluded from every public read.
- One canonical cohort identity (`cohort.js`, v2): model SHA,
  quantization, device class, workload id, prompt SHA, runtime
  impl/backend, build key, config hash — unambiguous
  serialization, no delimiter collisions, shared by aggregates,
  candidates, and Muse retrieval. v2 `cohort_hash` values are
  not comparable to the withdrawn ambiguous v1 join.
- Aggregates use PUBLISHED + COMPLETED rows with positive
  throughput only. Failed attempts are stored evidence, never
  averages. Provisional quorum: 5 runs + 3 installs; thin
  cohorts return `insufficient-evidence` with counts, never means.
- Candidates qualify each exact cohort independently: published
  only with a quorum-passing cohort, statistics from the
  representative cohort alone, thin cohorts contributing nothing.
- Corrupt stored JSON holds its row; a poison row never aborts a
  qualification pass. Database failures answer bounded `500`
  `internal` (or retryable `503`) envelopes; SQL text, paths,
  and ids never leave the backend.
- Install counts are distinct salted hashes; raw install ids,
  tokens, and run ids never appear in public output.
- `device_class` in v1 is the exact submitted device model
  string (finest granularity; no collapsing). A coarser device
  taxonomy is future backend policy, not client choice.
- Effective build identity is `value:status`
  (`O2:operator_attested` splits from `O2:self_declared`;
  unverified builds never pool with attested ones).

## Transaction semantics (G1B, G2-A batch form)

Atomic units, each one `db.batch()`: submission quota-claim +
conditional insert + conditional history + counter compensation;
one eligibility transition + conditional history (+ conditional
timestamp); revocation + conditional history. Every decision is
encoded in SQL (guards + change-count interpretation) because D1
batch() cannot return mid-batch reads. `BEGIN IMMEDIATE`
serializes writers; `busy_timeout` (5 s) absorbs brief contention;
`SQLITE_BUSY` maps to retryable `503`.

Verified on node:sqlite only (93 host tests, including
interleave harnesses and the D1-shape interface suite). D1
service behavior is UNVERIFIED: batch error shapes, batch
result metadata, contention behavior, per-invocation budgets,
and read replication must be qualified separately on staging.
Nothing here claims D1 compatibility from node:sqlite results.

## Website integration contract (Part I readiness)

`GET /v1/benchmarks/aggregates?model_sha=…` returns everything
`edgefit.ronbeesoftware.com` needs per cohort: cohort identity,
`n`, `installs`, mean/median/min/max, IQR (n≥8), quorum echo,
methodology tag (`ef-stats-01`), and explicit
`insufficient-evidence` entries for thin cohorts. No website
changes were made in this milestone.

## Deployment gates (all still closed)

1. Owner decision: Cloudflare Workers + D1 vs VPS + Postgres
   (ADR-009: Cloudflare is a candidate, not a commitment).
2. D1 binding for `db.js` + a `wrangler.toml`/schema apply path.
3. Install-token issuance and revocation operations.
4. Consent policy version publication (`v1` is a placeholder).
5. Publication quorum confirmation (5 runs / 3 installs provisional).
6. Individual-run disclosure policy (endpoint stays 404 until then).
7. Device-class taxonomy policy (v1 uses exact model strings).
