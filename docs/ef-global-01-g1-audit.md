# EF-GLOBAL-01 G1 — Source-matched backend audit

- Track: EF-GLOBAL-01 · Milestone G1 (AUDIT-ONLY, no implementation)
- Date: 2026-10-31 UTC
- Worktree: `C:\Users\ronba\EdgeFit-AI-EF-GLOBAL-01`
- Branch: `implement/ef-global-01-2026-10-30`
- Base/HEAD: `7a3085316c93439c216122c9319eeb5fff15f948`
- Method: read every governing doc and every `cloud/` source/test file;
  re-ran the host suite twice unmodified; ran throwaway counterexamples
  from scratch space (outside the repo) against isolated `:memory:` DBs.
- Status words: VERIFIED (reproduced or directly cited) vs HYPOTHESIS
  (inspection/deployment-dependent, not reproduced) vs PROPOSAL (new work).

Nothing was deployed, provisioned, purchased, pushed, or activated.
No production, Android, test, or other doc file was changed.

## A. Repository identity and baseline proof

VERIFIED via `git -c safe.directory='*' rev-parse/status/log`
(`-c` only bypasses a Windows ownership warning; no config changed):

- `rev-parse --abbrev-ref HEAD` → `implement/ef-global-01-2026-10-30` (MATCHES required branch)
- `rev-parse HEAD` → `7a3085316c93439c216122c9319eeb5fff15f948` (MATCHES required base)
- `status --porcelain=v1 -b` → `## implement/ef-global-01-2026-10-30`, zero
  modified/untracked entries (clean before this report was written)
- HEAD subject: `EF-COMP-01B-GATE-1A: correct Appendix R ordering …`

Governing documents present (sizes at audit time):

| File | Bytes |
|---|---|
| `docs/edgefit-master-architecture.md` | 20413 |
| `docs/edgefit-architecture-decisions.md` | 10290 |
| `docs/edgefit-product-requirements.md` | 24277 |
| `docs/ef-arch-02-traceability.md` | 17988 |
| `docs/ef-cloud-01-api-contract.md` | 7867 |
| `docs/ef-stats-01-contract.md` | 7685 |
| `docs/qualification-status.md` | 73587 |

Backend present:

| File | Bytes |
|---|---|
| `cloud/api.js` | 19423 |
| `cloud/db.js` | 1044 |
| `cloud/retrieval.js` | 3140 |
| `cloud/qualify.js` | 5701 |
| `cloud/stats.js` | 1125 |
| `cloud/schema.sql` | 2639 |
| `cloud/validate.js` (supporting, not in milestone file list) | 15730 |
| `cloud/README.md` | 4717 |
| `cloud/test/` (6 suites + `helpers.js`) | 7 files |

No Makefile, CI workflows, or linter configs exist in the worktree;
the only configured gate is `cloud/package.json` → `npm test`.

## B. Existing tests and exact results

VERIFIED. Documented command (`cloud/package.json:8`, `cloud/README.md:33`,
`docs/qualification-status.md:1260`): `npm test` = `node --test "test/*.test.js"`.

Two unmodified runs on Node v24.19.0, npm 11.17.0, `node:sqlite` present:

| Run | tests | pass | fail | cancelled | skipped | todo | duration |
|---|---|---|---|---|---|---|---|
| 1 | 38 | 38 | 0 | 0 | 0 | 0 | 462 ms |
| 2 | 38 | 38 | 0 | 0 | 0 | 0 | 459 ms |

The previously reported “38 passing” is now INDEPENDENTLY REPRODUCED.
Breakdown: `submit` 11, `eligibility` 8, `aggregates` 8, `retrieval` 3,
`models` 3, `abuse` 5. Zero skipped/cancelled/todo.

Runtime requirements (VERIFIED): Node 24+ (uses `node:sqlite`
`DatabaseSync`), zero npm dependencies, no network, no filesystem
beyond the repo (all suites use isolated `:memory:` databases).

Nondeterminism (VERIFIED none observed): fixtures pin `NOW`,
deterministic `SUB-…` counters, fixed tokens/salts; both runs produced
identical pass sets in one process each. No wall-clock, random, or
order-dependent input exists in the suite.

Coverage gaps found by this audit (i.e. what 38/38 does NOT prove):

- No test varies `prompt_sha256` under a fixed `workload_id` (see D1).
- No test puts a `|` (or any shared substring) in cohort-key fields (D2).
- No candidates test spans several thin cohorts of one model (D3).
- No concurrency/interleave coverage (D4, D5) — impossible single-threaded.
- No corrupt-`VALIDATED`-row or corrupt-`consent_scopes` case (D7).

## C. File/line source evidence (index)

Grouping and identity:

- `cloud/api.js:281-287` `cohortKey()` — 8-field `|`-joined cohort key
  (model, quantization, device, workload_id, runtime impl/backend,
  build_key, config_hash). NO `prompt_sha`.
- `cloud/retrieval.js:44-53` — duplicate of the same 8-field key. NO `prompt_sha`.
- `cloud/validate.js:58-62` `configHashFor()` — `v1|n_ctx|n_threads|n_predict|sampler`
  only; temperature/seed excluded.
- `cloud/validate.js:383` `build_key` = `effective_opt:effective_opt_status`
  from allowlists (`validate.js:14-19`), default `unknown:unverified`
  (`validate.js:289-301`).
- `cloud/schema.sql:27` stores `prompt_sha` (NOT NULL) but nothing groups by it;
  `schema.sql:57-61` cohort index also omits it (and quantization/runtime).
- `docs/ef-stats-01-contract.md:31-40` REQUIRES `workload_prompt_sha256`,
  full build identity, and the 4 config fields in every cohort.
- `cloud/api.js:263-279` public cohort object likewise omits `prompt_sha`.

Eligibility and writes (all multi-statement, none transactional):

- `cloud/api.js:90-100` quota check, `:138-174` insert + history + counter.
- `cloud/api.js:122-135` content-hash dedup (returns first id cross-install).
- `cloud/api.js:187-199` owner check (foreign/missing → single 404).
- `cloud/api.js:232-259` withdrawal → unconditional `REVOKED`.
- `cloud/qualify.js:18-27` `transition()` — unconditional
  `UPDATE … SET eligibility=? WHERE submission_id=?` (no from-state guard).
- `cloud/qualify.js:79-84` pending snapshot; `:86-160` per-row fall-through
  SUBMITTED→VALIDATED→QUALIFIED→PUBLISHED.
- `cloud/qualify.js:33-73` anomaly suite (plausibility, zero-token,
  tps/timing reconciliation, run_id collision, velocity).
- `cloud/db.js:10-29` adapter `{get,all,run,exec}` — NO transaction/batch primitive.
- `cloud/api.js:108-110` only try/catch in `api.js` (JSON body); DB
  exceptions (e.g. UNIQUE races) propagate uncaught.

Reads:

- `cloud/api.js:289-332` per-cohort gating (5 runs / 3 installs from
  `api.js:19-25`); `:334-417` aggregates; `:421-456` models (counts only);
  `:460-565` candidates — quorum/means computed over the POOLED per-model
  row set (`:524-552`), not per cohort.
- `cloud/retrieval.js:19-43` allowlist + fixed parameterized query;
  `:55-61` below-quorum cohorts skipped entirely.
- `cloud/api.js:594-602` runs listing disabled (404 + reason).
- `cloud/stats.js:29-33` IQR gate (null below n=8).

No `GROUP BY` exists anywhere in `cloud/*.js` (grouping is JS-side);
no response-headers/`Retry-After` support; no `BEGIN/COMMIT/batch`.

## D. Confirmed defects

Severity = integrity impact if staging data were served as-is.

### D1 [HIGH] `prompt_sha256` excluded from cohorts — VERIFIED live

- Evidence: `cloud/api.js:281-287`, `cloud/retrieval.js:44-53`,
  vs required key `docs/ef-stats-01-contract.md:31-40`.
- Counterexample (probe D1): 5 PUBLISHED runs, identical except 3×
  prompt-sha-A + 2× prompt-sha-B under `edgefit-prompt-v1` →
  **1 group, n=5, `published`** (correct: 2 thin cohorts).
- Same flaw on the Muse path (probe D1b): **1 cite, n=5**.
- Fix (no schema change; column exists): add `prompt_sha` to both keys,
  cohort objects, cohort_hash input, and an optional query filter.
  This is a REQUIRED CORRECTION, not a proposal.

### D2 [MEDIUM] `|`-joined cohort key collides — VERIFIED live

- Evidence: same key builders as D1 over free-form `device_class`,
  `workload_id`, `runtime_impl/backend`, `quantization_label`
  (all admit `|`, e.g. `validate.js:23,221`).
- Counterexample (probe D2): cohort (`A|X`,`Y`) + cohort (`A`,`X|Y`),
  5 runs each → **1 group, n=10, `published`**.
- Fix: one shared key builder with length-prefixing (or hash of a
  canonical structure); covered by the shared-function recommendation (§H).

### D3 [HIGH] `model-candidates` pools across cohorts — VERIFIED live

- Evidence: `cloud/api.js:519-552` groups by `model_sha` only;
  quorum + mean/median over the pooled set. Contract REQUIRES
  per-candidate exact cohorts, per-cohort quorum, “never a borrowed or
  partial mean”, “pooled aggregation is forbidden”
  (`docs/ef-stats-01-contract.md:76-94`).
- Counterexample (probe D3): one model, 5 configs × n=1 (aggregates
  correctly show 5/5 thin) → candidate **`published`, mean 16**.
- Fix: per-candidate exact-cohort grouping with independent quorum;
  `insufficient-evidence` unless at least one cohort passes; means only
  from quorum-passing cohorts. REQUIRED CORRECTION.

### D4 [MEDIUM] Unguarded transitions can resurrect REVOKED — inspection

- Evidence: `cloud/qualify.js:18-27` (no `WHERE eligibility=<from>`) +
  snapshot-then-write across `:79-84` and `:86-160`.
- Interleave: qualification snapshots row as SUBMITTED → owner DELETEs
  (`REVOKED`, `api.js:251-254`) → pass promotes it to VALIDATED/…/PUBLISHED
  with history violating “REVOKED is terminal”.
- NOT dynamically reproduced (needs concurrent interleave; HYPOTHESIS on
  timing, VERIFIED on the missing guard). Fix: compare-and-swap updates
  + affected-row checks; single-flight qualification per deployment.

### D5 [MEDIUM] No transactions; quota TOCTOU; partial writes — inspection

- Evidence: `cloud/api.js:90-100` + `:138-174`; `cloud/db.js:10-29`
  (no transaction/batch primitive).
- Two concurrent uploads can both pass the quota SELECT and both insert;
  a crash between INSERT/history/counter leaves partial state.
- NOT dynamically reproduced single-threaded (same caveat as D4).
  Fix: atomic quota accounting, batch primitive in the adapter,
  UNIQUE-conflict handling for dedup/insert races.

### D6 [LOW] Uncaught DB exceptions; no headers/`Retry-After` — inspection

- Evidence: only try/catch is `cloud/api.js:108-110`; responses are
  `{status, body}` with no header channel, while the contract specifies
  `429` + `Retry-After` (`docs/ef-cloud-01-api-contract.md:37-39,143-147`).
- A UNIQUE-race exception becomes an entry-point-dependent 500/crash.
  Fix with the deployment entry point (D5 work).

### D7 [LOW] Qualification crashes on some corrupt rows — inspection

- Evidence: `JSON.parse(row.consent_scopes)` at `cloud/qualify.js:103`
  sits OUTSIDE the try/catch (`:87-97`); `:123` and `:141` parse with
  no guard. The abuse suite only corrupts SUBMITTED-stage `result_json`.
- One corrupt VALIDATED row (or consent field) aborts the whole pass.
  Fix: treat every stored-JSON parse as a hold, never a throw.

### D8 [LOW] Cohort logic duplicated in two files — inspection

- Evidence: `cloud/api.js:281-287` vs `cloud/retrieval.js:44-53`
  (identical today; nothing enforces parity — D1/D2 had to be fixed twice).
  Fix: one shared server-side cohort-qualification function (§H).

## E. Unproven risks and verified minor deviations

R-prefix items are HYPOTHESES (deployment-dependent or judgment calls),
each with the check that would close it. V-prefix items are VERIFIED
minor deviations with low integrity impact.

- R1. `runtime_version` / `app_version` merge across cohorts (stored,
  `schema.sql:35,45`; never grouped). Allowed by ef-stats-01/master §11,
  but physically questionable (llama.cpp 0.7 vs 0.8 pool together).
  Close: owner decides whether version joins the cohort key (§H).
- R2. `temperature`/`seed` excluded from `config_hash`
  (`cloud/validate.js:58-62`), contradicting master §7 config identity
  (`{n_ctx,n_threads,n_predict,temperature,sampler,seed}`).
  Harmless under greedy-only ADR-012. Close: reconcile the master text.
- R3. Cross-install dedup oracle: resubmitting known bytes returns the
  first install's id + eligibility (`cloud/api.js:122-135`). No identity
  leaks, but content-existence does. Close: abuse review at staging.
- R4. No synthetic-tier column; tier separation is purely operational
  (isolated DBs). Safe today (no synthetic path exists), fragile if
  staging fixtures ever migrate. Close: add a tier/source flag before
  any shared staging DB.
- R5. Token lookup is a plain equality match (`cloud/api.js:45-50`;
  not constant-time) and NO app-layer IP rate limiting exists on public
  reads. Close: edge rate limits + token-entropy policy at deployment.
- R6. No migration runner/versioning (`CREATE … IF NOT EXISTS` only,
  `cloud/schema.sql`). Close: versioned migrations in the deploy path.
- R7. `runQualification` is chatty (snapshot + ~3 statements/row,
  `BATCH_LIMIT=1000`). Cloudflare D1 meters rows and caps queries per
  invocation (50 free / 1000 paid per current docs — RE-VERIFY at build).
  A full 1000-row pass likely exceeds one invocation's budget even on
  paid. Close: deployment qualification with paginated batch sizes.
- R8. D1 read-after-write/edge-consistency behavior for owner
  read-after-delete, and backup/PITR retention, were NOT verified here
  (no account exists). Close: staging drills before any real data.
- R9. `GET /v1/models` sums counts across cohorts/builds
  (`cloud/api.js:429-446`). Counts-only (no means), so within the
  insufficient-evidence disclosure rule — but pooled counts can mislead.
  Close: label as cross-cohort totals or gate per cohort.
- R10. `size_bytes` is newest-wins per model (`cloud/api.js:530-531`)
  with no cross-row consistency check for identical SHAs.
- R11. Only `tps_gen` is reconciled vs timing (`cloud/qualify.js:44-48`);
  `tps_prefill` is not — low impact since prefill aggregates are not
  published (see V2).
- R12. Quorum floors are injectable (`deps`, `cloud/api.js:569-571`);
  deployment MUST pin 5/3 explicitly.
- R13. `Buffer.byteLength` (`cloud/api.js:101`) needs `nodejs_compat`
  (or a `TextEncoder` swap) on Workers — one-line portability wart.
- V1. Contract-shape drift (VERIFIED, low impact): error envelope lacks
  `api_version`/`request_id`; POST returns 201/`duplicate` vs 202/
  `duplicate_of`; models/aggregates/submission shapes differ from
  `docs/ef-cloud-01-api-contract.md`. Align before any client builds.
- V2. Prefill/error aggregates unpublished by design subset
  (`cloud/api.js:311-322`; `mean_abs_pct_error: null` + note). Matches
  “no loop coverage” but is narrower than ef-stats-01 §Aggregates text.
- V3. Stale doc text (VERIFIED): `ef-cloud-01-api-contract.md:161-163`
  (“No endpoint is implemented”), master §11 (“thin groups omitted”)
  vs ef-stats-01 explicit insufficient-evidence entries (implementation
  correctly follows the reconciliation rule). Needs owner-approved
  doc touch-up — NOT done in this audit (frozen except this report).

## F. Minimum counterexample test matrix (PROPOSAL — not implemented)

Each row is a REQUIRED host test before deployment qualification.
“Today” = current-code outcome, D1–D3/D1b already demonstrated by probe.

| # | Fixture | Expected (per ef-stats-01) | Today |
|---|---|---|---|
| T1 | 5 runs, 1 workload id × 2 prompt SHAs, 3 installs → aggregates | 2 thin groups, no means | 1 published n=5 ❌ |
| T2 | Same → `evidenceRetrieve` | 0 cites | 1 cite n=5 ❌ |
| T3 | (`A\|X`,`Y`) ×5 + (`A`,`X\|Y`) ×5 → aggregates | 2 groups | 1 group n=10 ❌ |
| T4 | 1 model × 5 configs × n=1 → candidates | `insufficient-evidence`, null means | `published`, mean 16 ❌ |
| T5 | 1 quorum cohort + 2 thin cohorts, 1 model → candidates | published from quorum cohort ONLY | pooled ❌ |
| T6 | Revoke between qualification snapshot and transition | stays REVOKED, no new history | resurrects ❌ (needs interleave harness) |
| T7 | Concurrent uploads at quota edge | stored ≤ quota, no partial rows | over-quota/partial ❌ (needs concurrency harness) |
| T8 | Corrupt `result_json`/`consent_scopes` on VALIDATED row | hold, pass continues | throw ❌ |
| T9 | Mixed fixture incl. T1/T3 shapes → aggregates vs retrieval | identical cohort partition | n/a (guards D8 fix) |
| T10 | 1000-row mixed backlog → qualification | bounded statements/pass, paginated | unbounded (deployment qual) |

T6/T7 need a small interleave/concurrency harness (second connection or
fault-injection wrapper), counted as NEW test code, not existing coverage.

## G. ADR-009 hosting comparison (PROPOSAL input — verify before build)

Decision context: ADR-009 (`docs/edgefit-architecture-decisions.md:99-104`)
holds Cloudflare as candidate, not commitment; master §9 lists what must
be verified. External figures below are grounded in current vendor docs
and roundups (notably the [D1 limits page](https://developers.cloudflare.com/d1/platform/limits/))
and MUST be re-verified at build time — treat every number as HYPOTHESIS.

### Option A — Cloudflare Workers + D1 + Pages

- Portability: HIGH (VERIFIED from source). `handleRequest` is
  runtime-agnostic; `sha256hex`/`newId` are injected (WebCrypto swap is
  trivial); SQLite dialect + `?` placeholders carry to D1 with ZERO
  query rewrites; zero npm deps. One wart: R13 (`Buffer`).
- Transactions: D1 `batch()` = multi-statement transactions; NO long/
  interactive transactions (single-writer model). D4/D5 fixes must be
  designed for batch/CAS patterns, not `SELECT FOR UPDATE`.
- Qualification fit: RISK (R7). Chatty per-row pattern vs per-invocation
  query caps and rows-read billing → paginate (order ~100/pass) and run
  as a Cron Trigger; qualify batch sizing on staging.
- Auth/secrets: Workers secrets store; tokens stay SHA-256-hashed at
  rest; issuance stays an owner-operated procedure (no endpoint — correct).
- Revocation consistency: live-computed aggregates → immediate effect;
  edge read-after-write semantics need a staging check (R8).
- Migrations: `wrangler d1 migrations/*` + staging/prod DBs; needs the
  R6 runner either way.
- Abuse/rate limits: edge rate limiting/WAF available; per-install quota
  in code; app must add a header channel for `Retry-After` (D6).
- Backup/recovery: D1 automatic backups + Time Travel/PITR exist per
  docs — retention/plan details to verify; plus JSON export drills.
- Ops complexity: LOW (no servers/patching; GitHub Actions + wrangler).
- Cost (HYPOTHESIS, re-verify): $0 staging on free tier (100K req/day,
  5M rows read/day, 100K rows written/day, 500 MB/DB); paid from $5/mo.
  A 128 KiB-capped submission row fits the 2 MB row ceiling with margin.
- Requirements: Cloudflare account, domain delegation, wrangler project,
  staging+prod D1, secrets, token-issuance ops, consent-policy publication.
- Hackathon suitability: HIGH — fastest credible staging, $0, low ops.

### Option B — VPS + PostgreSQL + static frontend

- Portability: MEDIUM. Same handler/validation/stats reuse, but every
  query needs `?`→`$n`, `AUTOINCREMENT`→identity/serial, type-affinity
  review; plus a Node HTTP server, reverse proxy + TLS, static hosting.
- Transactions: FULL ACID — cleanest fix for D4/D5 (guarded updates,
  serializable quota accounting, crash-atomic multi-writes). Clear edge.
- Qualification fit: no per-invocation caps; chatty pattern merely slow.
- Auth/secrets: env/managed secrets; same token model; TLS via
  Caddy/Let's Encrypt; SSH/firewall/patch hardening is DIY.
- Revocation consistency: immediate + strongly consistent.
- Migrations: same R6 runner needed (e.g. `node-pg-migrate`/sqitch).
- Abuse/rate limits: proxy-enforced (Caddy/nginx), more DIY than A.
- Backup/recovery: `pg_dump` + offsite cron; PITR via WAL archiving DIY.
- Ops complexity: HIGHER (OS, Postgres, TLS, monitoring, uptime).
- Cost (HYPOTHESIS): ~$5–10/mo entry VPS, predictable; real cost is ops time.
- Requirements: VPS account, hardening, Postgres, Node runtime, proxy,
  backups, monitoring.
- Hackathon suitability: MEDIUM — full control, slower secure stand-up.

### Evaluation summary

For the SMALLEST staging path, A is the better fit: the code was written
D1-shaped (adapter-swap design, SQLite dialect, zero deps), staging is
$0 with minimal ops, and every D1 caveat found (R7/R8/batch patterns)
is closable in deployment qualification. B wins only if full ACID
transactions are judged mandatory before ANY staging data — a legitimate
owner call, and the retained fallback per ADR-009. Either option requires
the §D corrections first; neither is authorized by this audit.

## H. Architecture decisions requiring owner approval

Explicit PROPOSED items this audit touches (none approved here):

1. ADR-009 hosting choice: A (recommended for staging) vs B (fallback).
2. Quorum activation: confirm provisional 5 runs / 3 installs for staging.
3. D1/D3/D2 corrections: approve the grouping fixes + candidates
   per-cohort redesign (behavior changes to a qualified foundation).
4. Shared server-side cohort-qualification function for public aggregates,
   candidates, and internal retrieval (fixes D8; one parity-tested unit).
5. Consent policy `v1` content + publication (code carries a placeholder).
6. Device taxonomy: keep exact model strings vs approve a coarser mapping.
7. Runs disclosure: stays disabled (no change recommended).
8. R1/R2 grouping questions: admit `runtime_version`? reconcile master §7
   temperature/seed text? (Either answer is a doc + test update.)
9. Token issuance/revocation operations + token-entropy policy (R5).
10. Rate-limit strategy + `Retry-After` header channel (D6/R5).
11. Migration runner + schema versioning (R6).
12. Evidence Explorer scope: public read-only aggregates/models/candidates
    over the versioned API only; no run drill-down (runs stay 404).

## I. Smallest viable implementation sequence (PROPOSAL)

Staging path: physical benchmark → explicit consent → authenticated
submission → validation → qualification → DB storage → exact-cohort
quorum → bounded public aggregates → bounded Muse retrieval →
public read-only Evidence Explorer.

EXISTING implementation (reuse as-is; VERIFIED §B):

- Strict validation + itemized rejections, per-install token auth,
  per-submission consent (aggregates-only), content-hash dedup, daily
  quota + velocity/anomaly holds, rule-driven SUBMITTED→…→PUBLISHED
  with history, withdrawal tombstones, quorum-gated aggregates,
  fixed allowlisted Muse retrieval, disabled runs listing, 38 host tests.

REQUIRED CORRECTION (must precede any staging data):

1. D1 prompt-sha grouping (+ filter/response fields).
2. D3 candidates per-cohort quorum (no pooled means).
3. D2 delimiter-safe shared cohort-key builder.
4. D8 shared cohort-qualification function (serves 1–3 + retrieval).
5. D4 guarded transitions (+ single-flight qualification).
6. D5 adapter batch/atomicity primitive + quota/UNIQUE hardening.
7. D7 corrupt-row holds (no throws).
8. D6 header channel + 500 envelope in the new entry point.

NEW implementation (staging minimum, Option A):

9. Workers entry mapping fetch→`handleRequest`; D1 `db.js` binding;
   WebCrypto `sha256hex`; `TextEncoder` body-size check (R13).
10. `wrangler.toml`, versioned migrations, staging+prod D1, secrets.
11. Token-issuance/revocation ops runbook (no endpoint).
12. Consent policy `v1` publication.
13. Edge rate limits + abuse review (R3/R5).
14. Qualification Cron with paginated batches (R7) + drills (R8).
15. Static read-only Evidence Explorer on Pages (aggregates/models/
    candidates only; insufficient-evidence rendered honestly).

OWNER APPROVAL gates: §H items 1–12 (hosting + corrections + quorum +
policy + ops) before staging data; Explorer content before any link-out.

HOST QUALIFICATION: 38 existing + §F T1–T9 green on Node 24, unmodified
except the approved corrections; T6/T7 via the new harness.

DEPLOYMENT QUALIFICATION (isolated staging DB, synthetic fixtures only):
migrations up/down; T10 pagination within D1 budgets; backup/restore +
Time-Travel drill; quota/velocity/anomaly abuse matrix; issuance/
revocation rotation; rate-limit verification; no production data.

PHYSICAL MULTI-DEVICE QUALIFICATION (real evidence, still staging):
≥2 device models × ≥3 installs; ≥5 PUBLISHED runs in one exact cohort;
aggregates flip thin→published as quorum is reached; revocation
exclusion observed end-to-end; website shows only quorum-passing means.

## J. Explicit STOP/GO recommendation

**STOP — do not deploy, publish, connect the website, or admit any
staging data.** Integrity-blocking defects D1 (prompt-merge) and D3
(pooled candidate means) would serve FALSE “global” statistics, and
D4/D5 leave revocation/quota incorrect under concurrency. ADR-009,
quorum activation, consent policy, and token ops are also still open.

**GO — on the §I correction sequence only,** after the owner approves
§H: fix D1–D8 with §F T1–T9 green on host, then run deployment
qualification and physical multi-device qualification before ANY
publication is activated. Re-audit trigger: any grouping, quorum,
consent, or retrieval change restarts at §F.

## Appendix — probe provenance (reproducible, repo untouched)

Throwaway script (kept outside the repo at
`C:\Users\ronba\ef-g1-scratch\probe.mjs`): imports the UNMODIFIED
`cloud/` modules + `cloud/test/helpers.js`, isolated `:memory:` DBs.

```text
D1 prompt_sha: groups = 1 n = 5 status = published (MERGED)
D2 delimiter: groups = 1 n = 10 (MERGED)
D3 candidates: per-cohort thin groups = 5 / 5 | candidate status = published mean = 16 (POOLED)
D1b retrieval prompt_sha: cites = 1 n = 5 (MERGED)
CTRL config split: groups = 2 statuses = published,published
```

`git status` after the probe runs and before this report: clean
(`## implement/ef-global-01-2026-10-30`, no entries) — the probe wrote
nothing to the repo. After this report, the ONLY worktree change is
`docs/ef-global-01-g1-audit.md` (this file), uncommitted for owner review.

## G1B closure (2026-10-31, uncommitted with the corrections)

All eight confirmed defects (D1-D8) were corrected in `cloud/` on
this branch from base `7a30853`; no other defect inventory changed.

| Defect | Correction (file) | Regression evidence |
|---|---|---|
| D1 prompt-merge | `cloud/cohort.js` canonical key incl. `prompt_sha` + filter/response fields | G1B-R1/R1b/R1c/R1d, X1 |
| D2 delimiter collision | unambiguous v2 serialization in `cloud/cohort.js` | G1B-R2, X4 |
| D3 candidate pooling | per-cohort quorum, representative-cohort stats (`cloud/api.js`) | G1B-C1/C2, X7 |
| D4 unguarded transitions | compare-and-swap transactions (`cloud/qualify.js`, `cloud/api.js`) | G1B-T6, X2 |
| D5 no transactions | `db.transaction`, atomic quota claim, UNIQUE handling (`cloud/db.js`, `cloud/api.js`) | G1B-T7/T7b/T7c/T7d/T7e |
| D6 uncaught failures | sanitized 500/503 envelopes + `headers`/`retry-after` (`cloud/api.js`) | G1B-F4/F5/F6 |
| D7 corrupt-row crash | guarded parses + per-row containment (`cloud/qualify.js`) | G1B-F1/F2/F3 |
| D8 duplicated logic | single shared unit (`cloud/cohort.js`) | G1B-Q3, X1 parity |

Host record: RED baseline 50/69 (all 19 designed failures for cause,
log preserved outside the repo); final `npm test` 77/77 twice
(38 original unmodified, 0 weakened, 0 deleted). D1 semantics remain
UNVERIFIED by design. Deployment stays STOPPED pending the §H owner
decisions; quorum activation, consent policy, token ops, migration
runner, edge rate limits, Explorer scope, and physical multi-device
evidence are all still owner-gated.
