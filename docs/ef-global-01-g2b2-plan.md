# EF-GLOBAL-01 G2-B2 plan — remote staging qualification (DRAFT, owner-gated)

Status: PROPOSED. Nothing in this document is authorized to run.
G2-B2 starts only after the owner reviews the G2-B1 local packet
(131-test suite, Worker/D1 integration, this plan) and issues a
separate, explicit provisioning authorization naming the account,
the spending cap, and the operator.

G2-B1 delivered a locally tested Worker entry point
(`cloud/worker.js`), a real D1 adapter (`cloud/d1.js`), a
staging-only wrangler template (`cloud/wrangler.staging.toml`),
the immutable `001_init.sql`, a local migration runner, and 37
local qualification tests. Every claim below marked UNVERIFIED
survived G2-B1 precisely because no live Cloudflare resource was
touched; G2-B2 exists to verify or refute each one against the
real service, in an isolated staging database, with synthetic
data only.

## 1. Resources proposed (exact)

| # | Resource | Identifier | Purpose |
|---|----------|------------|---------|
| R1 | D1 database | `edgefit-evidence-staging` | Isolated staging evidence store. No production database exists or is proposed. |
| R2 | Worker | `edgefit-evidence-staging` (`env.staging`) | Serves the v1 API from `cloud/worker.js` against R1. |
| R3 | D1 binding | `DB` on R2 | The only database binding. |
| R4 | Secret | `INSTALL_SALT` on R2/staging | Random per-deployment salt. Set via `wrangler secret put` only. |

NOT proposed: custom domains, routes, triggers, production
environments, production databases, Pages sites, R2 buckets, queues,
API tokens with broad scopes, or any resource outside the four rows
above. If qualification needs a public URL, G2-B2 deliberately flips
`workers_dev` (currently `false` in the template) as a recorded,
reversible step — never a custom domain.

## 2. Account prerequisites

- A Cloudflare account owned or explicitly delegated to the project,
  with the operator's identity recorded in the run log.
- Billing alerts configured BEFORE the first remote command (see §9).
- Wrangler pinned as a project-local dev dependency at G2-B2 start:
  `npm install -D wrangler@<pinned>` inside `cloud/`, with the
  version and lockfile diff recorded. G2-B1 deliberately installed
  nothing; the pin happens here so review sees the exact tool.
- Operator machine authenticated via `wrangler login` (OAuth, no
  long-lived API token in files). No token is ever committed.

## 3. D1 creation

```sh
cd cloud
wrangler d1 create edgefit-evidence-staging
```

- Record the returned `database_id` in the run log, then substitute
  it for `REPLACE-AT-G2B2-PROVISIONING` in a G2-B2 working copy of
  the wrangler template (the committed template keeps the
  placeholder until the owner approves recording the real id).
- Verify: `wrangler d1 list` shows exactly one new database; `wrangler
  d1 info edgefit-evidence-staging` reports the expected region/size.
- D1 creation requirements: none beyond the account (§2). No
  upfront schema, no seed data, no imported rows — the database is
  born empty and migrations (§6) build it.

## 4. Worker binding configuration

- `wrangler.staging.toml` already declares binding `DB`,
  `database_name`, `migrations_dir`, `compatibility_date
  2026-10-30`, and `compatibility_flags = ["nodejs_compat"]`.
- G2-B2 verifies startup WITHOUT traffic first: deploy (see §5),
  then `wrangler tail --env staging` while issuing one HTTPS GET to
  `/v1/models` (or `wrangler dev` equivalent if `workers_dev` stays
  off). Expected: `200`, empty artifacts, no exception. Any startup
  exception (notably node:crypto under `nodejs_compat`) STOPS the
  milestone for a Worker-compat decision — do not work around by
  editing hashing in the same step that deploys.

## 5. Deployment command review

Every remote command below requires the G2-B2 authorization; the
table records what each does and what can go wrong.

| Command | Effect | Risk / guard |
|---------|--------|--------------|
| `wrangler secret put INSTALL_SALT --env staging` | Stores R4. | Value from a local CSPRNG, never reused, never echoed. `wrangler secret list` shows names only. |
| `wrangler d1 migrations apply edgefit-evidence-staging --env staging` | Applies `migrations/*.sql` remotely. | Irreversible DDL: snapshot first (§8), verify with `migrations list` + row counts after. |
| `wrangler deploy --env staging --config wrangler.staging.toml` | Publishes R2. | Review the plan output (routes: none expected). Keep the prior version id for rollback. |
| `wrangler d1 export edgefit-evidence-staging` | Backup (§8). | Contains synthetic rows only; still handled as evidence data. |
| `wrangler tail --env staging` | Live logs. | Watch for secret/identity disclosure; stop on any. |
| `wrangler d1 execute` (ad-hoc SQL) | Manual reads. | READ-ONLY statements during qualification; any write is a recorded experiment step, never a fix. |

FORBIDDEN without a second, separate authorization: `wrangler d1
delete`, `wrangler delete`, any `--env production` (nonexistent by
design), custom-domain/route commands, and token/secret commands
outside R4.

## 6. Migration sequence

1. Export the empty database (§8) as the rollback baseline.
2. `wrangler d1 migrations apply edgefit-evidence-staging --env
   staging` — applies `001_init.sql` only.
3. Verify remotely: table list (`installs`, `submissions`,
   `eligibility_history`, `daily_counts`), index list (5), then one
   synthetic submit→qualify→revoke cycle through the Worker URL.
4. Repeat-apply: run the apply command again; it must report
   no-op success without touching rows (remote twin of G2B-D-N2).
5. `001_init.sql` stays byte-frozen; any schema change discovered
   during G2-B2 becomes `002_*.sql` reviewed separately — never an
   edit to the applied file.

## 7. Required staging secrets

- `INSTALL_SALT` (R4): 32+ random bytes, base64, generated at
  provisioning, recorded ONLY as "set on <date> by <operator>".
- Explicitly NOT configured: Meta service keys, install upload
  tokens (manual, owner-operated per H9 — created by direct,
  logged D1 row insert of token hashes, never via an endpoint),
  API tokens, or any production credential.

## 8. Rollback and recovery + export/restore drill

- Rollback of the Worker: `wrangler rollback` / redeploy of the
  prior version id recorded at §5.
- Rollback of data: there is no down-migration; recovery is
  export/restore. Drill (mandatory, synthetic rows only):
  1. Populate staging with a known synthetic set (fixed seeds,
     counted rows, published cohort hashes recorded).
  2. `wrangler d1 export` → store the dump with its SHA-256.
  3. Delete the staging database ONLY under the §5 second
     authorization, recreate it, re-apply migrations, import.
  4. Re-verify: row counts, cohort hashes, and one full API
     round-trip identical to step 1.
- Drill failure (count/hash mismatch, unrestorable dump) STOPS the
  milestone: no further staging traffic until recovery is proven.

## 9. Request limits and billing safeguards

- Set Cloudflare spending alerts and a D1 usage alert BEFORE §3;
  record thresholds in the run log.
- Workers free-plan budgets are the default ceiling: no paid plan
  upgrade without separate authorization.
- Application-level guards stay on: `quotaPerDay: 20` (staging
  value), 128 KiB body cap enforced twice (edge + handler), no
  public token issuance (H9), edge rate limits per H10 designed
  before any public exposure — G2-B2 traffic is operator-driven
  only, so H10 verification is "limits configured", not "attack
  survived".
- Largest-valid envelope measured in G2-A is 75.6 KB: the first
  payload-size experiment submits it verbatim to confirm D1
  statement-size headroom; anything near a service limit is
  recorded as a constraint, not retried at larger sizes blindly.

## 10. Synthetic-only data controls (H5)

- Every staged row is synthetic, generated by the documented
  fixtures; no physical device, no real install token, no
  production consent touches staging.
- Upload tokens are owner-created per H9 (logged, revocable,
  capped at a handful); the Worker exposes no issuance route
  (proven locally by G2B-W-D1).
- Consent policy review REMAINS the gate for real data: G2-B2
  success does not authorize any real-device upload.

## 11. Remote experiments (the UNVERIFIED list)

Each experiment has a pass criterion; the FIRST failure stops
traffic and converts to a tracked finding. Order is deliberate:
cheapest/foundational first.

| # | Experiment | Method | Pass |
|---|------------|--------|------|
| X1 | Batch atomicity | Submit-shaped 4-statement batch with a failing tail via a staging-only probe route or `d1 execute` batch twin; inspect tables. | All-or-nothing: zero partial rows, counters exact. |
| X2 | Result order | Mixed run/get/all batch; compare positions. | Input order == output order. |
| X3 | Changes metadata | Insert/UPDATE/conditional-write batches; record `meta.changes` and `meta.last_row_id` verbatim. | Integer `changes` on every write; values match sqlite semantics for the same statements. |
| X4 | CAS under contention | Parallel quota-claim batches (N > quota) + parallel revoke-vs-qualify races from two clients. | Quota exact, no over-admit; revocation terminal in every interleave. |
| X5 | Error mapping | Force UNIQUE/busy/type/limit failures; capture D1 error text/shapes. | Mapping table extended in the adapter WITHOUT changing sanitization: still 500/503 envelopes, nothing internal leaks. |
| X6 | Read-after-write | Write in one invocation, read in the next; same for batch-then-read. | Immediate visibility for the qualification flow (or a documented lag with a design decision). |
| X7 | Limits | Statement count, parameters, payload (75.6 KB envelope), rows read. | Recorded ceilings; backend stays comfortably under all of them. |
| X8 | Migration apply | §6 including repeat-apply. | Clean apply + clean no-op repeat. |
| X9 | Export/restore | §8 drill. | Bit-identical evidence after restore. |

## 12. Clear stopping conditions

STOP traffic and report to the owner on ANY of:

- S1. A batch applies partially (X1 fails).
- S2. `changes` metadata missing, non-integer, or wrong (X3 fails).
- S3. Sanitization break: any response, log, or error carrying SQL,
  paths, secrets, install identities, or token material (X5 or tail
  watch).
- S4. Revocation loss or quota over-admit under contention (X4).
- S5. Read-after-write lag that breaks submit→qualify→publish (X6).
- S6. Unrestorable backup (X9) or migration apply failure (X8).
- S7. Any billing meter moves before its alert is armed, or any
  spend exceeds the authorized cap.
- S8. Any real-device data, real token, or production identifier
  appears in staging traffic, logs, or dumps.

G2-B2 SUCCESS = X1–X9 pass, no S-condition fires, and the run log
(named operator, pinned wrangler version, database id, secret-set
date, all command transcripts, all captured D1 error text) is
complete for owner review. Success authorizes NOTHING further: real
data, public exposure, and production remain separately gated.
