# EF-CLOUD-01A Global Evidence Foundation

Versioned backend foundation for consented multi-device benchmark
evidence. Implements `docs/ef-cloud-01-api-contract.md` (v1) and the
`docs/ef-stats-01-contract.md` methodology annex as runtime-agnostic
JavaScript over plain SQLite (Cloudflare D1 compatible).

**Status: host-qualified foundation. Nothing here is deployed.**
There are no production Cloudflare resources, no DNS records, no
accounts, and no community data. All tests use isolated `:memory:`
databases with synthetic fixtures that are never presented as
community measurements.

## Layout

- `schema.sql` — D1-compatible DDL: installs, submissions,
  eligibility_history, daily_counts.
- `db.js` — node:sqlite adapter behind `{ get, all, run, exec }`;
  deployment swaps this file for a D1 binding without touching
  routes, validation, or stats.
- `validate.js` — strict submission-envelope validation with
  itemized rejections; canonical JSON for content hashing.
- `api.js` — `handleRequest({ method, path, query, headers,
  bodyText, db, nowMs, deps })` → `{ status, body }`. A Workers
  entry point maps fetch events onto it.
- `qualify.js` — `runQualification(db, nowMs, deps)`: rule-driven
  SUBMITTED → VALIDATED → QUALIFIED → PUBLISHED, with holds.
- `retrieval.js` — `evidenceRetrieve(db, params, deps)`: the fixed
  allowlisted query behind future server-side Muse retrieval.
- `stats.js` — mean, median, IQR (linear-interpolation quantiles).
- `test/` — node:test suites, 38 tests, all isolated.

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

## Trust rules enforced in code

- Per-install upload tokens (SHA-256 hashed at rest), owner-issued;
  no self-registration endpoint. Revoked installs are 401.
- Explicit per-submission consent: policy version, timestamp, and
  scopes. Only `aggregates` is accepted; `runs` is rejected until
  its separate disclosure policy is approved.
- Content-hash deduplication: identical bytes return the first
  submission id with `duplicate: true`, never a second row.
- Quota 20 uploads/install/day (UTC); velocity floods (more than
  quota inside a sliding 24h) hold for review.
- Eligibility transitions only through `runQualification` rules,
  each writing `eligibility_history`. REVOKED is terminal and
  excluded from every public read.
- Aggregates use PUBLISHED + COMPLETED rows with positive
  throughput only. Failed attempts are stored evidence, never
  averages. Provisional quorum: 5 runs + 3 installs; thin
  cohorts return `insufficient-evidence` with counts, never means.
- Install counts are distinct salted hashes; raw install ids,
  tokens, and run ids never appear in public output.
- `device_class` in v1 is the exact submitted device model
  string (finest granularity; no collapsing). A coarser device
  taxonomy is future backend policy, not client choice.
- Effective build identity is `value:status`
  (`O2:operator_attested` splits from `O2:self_declared`;
  unverified builds never pool with attested ones).

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
