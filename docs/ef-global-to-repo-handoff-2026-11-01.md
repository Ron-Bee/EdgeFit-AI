# EF-GLOBAL-HANDOFF-01 — Authoritative Global Evidence Handoff

**Date:** 2026-11-01
**From:** MUSE-B (Global Evidence backend)
**To:** MUSE-C (GitHub showcase / submission readiness)
**Status:** Consent PROPOSED, collection UNAPPROVED, publication allowlist EMPTY.
Live Android-to-cloud upload NOT YET DEMONSTRATED.

## A. Backend repository and branch

- Repo: `https://github.com/Ron-Bee/EdgeFit-AI.git`
- Branch: `implement/ef-global-01-2026-10-30`
- Local worktree (MUSE-B only): `C:\Users\ronba\EdgeFit-AI-EF-GLOBAL-01`

## B. Full authoritative commit SHA

- Handoff commit: see EF-GLOBAL-HANDOFF-01 milestone report §D (starting
  HEAD `359e97103b5aa0e6d145498b80622604083c9395`, tree clean at creation).
- Verified remote HEADs (2026-11-01, read-only fetch):
  - Backend `implement/ef-global-01-2026-10-30` → `359e9710...`
  - Website `implement/ef-web-launch-01-2026-11-01` → `8d41a6109f3fee5ca34597ea5d4dab43e146d412`
  - Android `feature/ef-android-global-01-2026-11-01` → `29e59397a1a0e8b0b66d9de1a792a112c9e7f2ac`
    (INSTALL-07 recovery commit, owner-reported PASS on SM-S908U).

## C. Architecture summary

Three Cloudflare Workers (Free plan) + one D1 database:

| Service | URL / identity | Role | Exposure |
|---|---|---|---|
| Staging API + scheduler | `edgefit-evidence-staging-staging` (v `049c82b7-2137-4504-94b6-c01361aae2ee`, 100%) | Validated writes, qualification cron `*/10`, aggregates | Nonpublic (`workers_dev=false`, `preview_urls=false`), Access owner-only |
| Public reader | `https://edgefit-evidence-public.ronbaute92.workers.dev` (v `34e66243-5098-433b-a0fe-342ca50da2d8`) | Anonymous GET-only aggregates/models/candidates | Public, live |
| Ingest gateway | `https://edgefit-evidence-ingest-demo.ronbaute92.workers.dev` (v `a23f6c4b-d926-4d53-846a0df903498eff`) | Per-install authenticated POST/GET-item/DELETE-item | Public URL, auth-gated |
| D1 | `edgefit-evidence-staging` (`2ccce000-c70c-4c5a-9267-d330dedc22aa`), journal [001,002,003] | All persistence | Operator CLI only |

Pipeline: Android benchmark → explicit per-batch consent → per-install
bearer auth → ingest gateway → validation → server-assigned origin →
D1 → cron qualification → exact-cohort quorum (5 runs / 3 installs) →
publication allowlist gate → public reader → dashboard.

## D. Feature implementation matrix

Statuses: I=implemented, H=host-qualified, P=physically verified,
L=live-integration qualified, D=deployed.

| Feature | Source | Tests | Status |
|---|---|---|---|
| Canonical exact-cohort identity | [cloud/cohort.js](../cloud/cohort.js) | g1b-cohort, GT-21 | I,H,D |
| Submission validation + consent parse | [cloud/validate.js](../cloud/validate.js) | retrieval, pub-origin | I,H,D |
| Per-install auth, quota, dedup | [cloud/api.js](../cloud/api.js), [cloud/db.js](../cloud/db.js) | T7/T7b/T7c, g1b-* | I,H,D |
| Atomic eligibility+audit (trigger 002, unified incl. REVOKED) | [cloud/migrations/002_eligibility_audit_trigger.sql](../cloud/migrations/002_eligibility_audit_trigger.sql) | g2b-trigger (26), withdrawal (8) | I,H,D |
| Scheduled qualification (maxRows=10, cron */10) | [cloud/qualify.js](../cloud/qualify.js), [cloud/worker.js](../cloud/worker.js) | g2b-scheduler (16) | I,H,D |
| Durable provenance (kind/origin, immutability) | [cloud/migrations/003_evidence_origin.sql](../cloud/migrations/003_evidence_origin.sql) | pub-origin (18) | I,H,D |
| Qualification-time + query-time publication guards | [cloud/retrieval.js](../cloud/retrieval.js) | pub-read PR-01..PR-30, h10-isolation | I,H,D |
| Isolate-local rate limit (enforcing; Free binding kept as upgrade path) | [cloud/public-reader.js](../cloud/public-reader.js) | PR-27..PR-30 + live 429s | I,H,L,D |
| Public GET-only reader + CORS | [cloud/public-reader.js](../cloud/public-reader.js) | pub-read (30) | I,H,L,D |
| Ingest gateway (auth POST/GET-item/DELETE-item) | [cloud/ingest-demo.js](../cloud/ingest-demo.js) | ingest-demo ID-01..ID-26 | I,H,L,D |
| Operator credential helper (dry-run default) | [cloud/ops/issue-install.mjs](../cloud/ops/issue-install.mjs) | ops-issue OP-01..OP-10 | I,H (never executed) |
| Dashboard HttpProvider + charts/filters | Website `website/js/provider.js`, `dashboard.js`, `config.js` | 58/58 + V4-02 12/12 + PUB-03 5/5 | I,H,L,D (empty state) |
| Owner-scoped status lookup / withdrawal | [cloud/api.js](../cloud/api.js) | host + synthetic live | I,H,D |
| Android contribution client | Android branch (MUSE-A) | 634 JVM (reported) + INSTALL-07 PASS (reported) | I,P(app install), upload NOT run |
| Genuine Android→cloud upload | — | — | NOT RUN |
| Genuine public aggregate | — | — | BLOCKED (consent, credentials, quorum) |

## E. Test and verification results

- Backend: **297/297 PASS** re-run in HANDOFF-01 (213 inherited + 84
  PUB/DEMO: 18 PO + 30 PR + 26 ID + 10 OP). Baseline chain preserved.
- Website: 58/58 host + 12/12 V4-02 + 5/5 PUB-03 (MUSE-C worktree; verified
  via prior reports, not re-run here).
- Live read-only (2026-11-01, this milestone):
  - `GET /dashboard.html` → 200.
  - Public reader `GET /v1/models` → `{"api_version":"1","artifacts":[],"truncated":false}`.
  - Public reader aggregates (valid model_sha) → valid envelope, `groups:[]`.
  - Ingest gateway `GET /v1/models` → **404** (narrow routing intact).
  - Ingest gateway unauthenticated `POST /v1/submissions` → **401**.
- Live D1 census (read-only, 2026-11-01): 19 submissions
  (16 PUBLISHED legacy_untrusted, 1 REVOKED legacy, 2 REVOKED
  synthetic_test), 7 installs (4 legacy + 3 revoked synthetic),
  70 history rows, **0 `device_contribution` submissions**.
- NOT RUN: Android INSTALL-07→cloud (P0), withdrawal of genuine evidence
  (P1), multi-device (P2), quorum publication (P3).

## F. Scientific methodology

- Exact cohort = model SHA-256 + device class + runtime/backend +
  quantization + workload ID + prompt SHA-256 + n_ctx + n_threads +
  n_predict + sampler + effective optimization (+status) + build
  provenance. Canonical JSON serialization, no delimiter collisions.
- Quorum: ≥5 eligible runs from ≥3 distinct authenticated installs in
  the SAME exact cohort. A distinct install ≠ an attested physical
  device; never reinstall to inflate quorum.
- SM-S908U and SM-S906U are DIFFERENT exact cohorts; cross-device pooling
  is rejected by design (different means measure different hardware).
- Below-quorum cohorts return `insufficient_evidence` with no statistics;
  revoked rows are excluded everywhere; synthetic/legacy rows are
  permanently ineligible for genuine publication.
- Full contract: [docs/ef-stats-01-contract.md](ef-stats-01-contract.md),
  [docs/ef-cloud-01-api-contract.md](ef-cloud-01-api-contract.md),
  [docs/ef-global-01-g1-audit.md](ef-global-01-g1-audit.md).

## G. Deployed infrastructure status

Cloudflare Free only, no paid services. Verified this milestone:

- Staging Worker `edgefit-evidence-staging-staging` @100%, nonpublic,
  cron `*/10`, migration journal [001,002,003].
- Public reader live, anonymous GET-only, enforcing 120/60s isolate
  rate limit (live 429s previously proven), CORS `https://edgefit-ai.pages.dev`.
- Ingest gateway live, auth-gated (404/401 behavior re-verified).
- D1 `edgefit-evidence-staging`: synthetic/legacy test rows only, 0
  genuine rows, allowlist EMPTY.

## H. Verified public URLs

| URL | Verified | Result |
|---|---|---|
| `https://edgefit-ai.pages.dev/dashboard.html` | 2026-11-01, live HTTP | 200, honest empty state |
| `https://edgefit-evidence-public.ronbaute92.workers.dev/v1/models` | 2026-11-01, live HTTP | 200, `artifacts:[]` |
| `.../v1/benchmarks/aggregates?model_sha=…` | 2026-11-01, live HTTP | 200, `groups:[]` |
| `https://edgefit-evidence-ingest-demo.ronbaute92.workers.dev` | 2026-11-01, live HTTP | 404 on read routes, 401 unauthenticated POST |

## I. Screenshot paths and hashes

Assets live in the WEBSITE worktree (`implement/ef-web-launch-01-2026-11-01`),
hashed read-only 2026-11-01:

| Asset | SHA-256 | Proves | Label |
|---|---|---|---|
| `website/assets/edgefit-home-android.jpg` | `D808DB25…C2381D377` (full: `D808DB257DFF1CDF485A72AE086F8E6F603B8674ECDF8B3F5C65CB9C2381D377`) | Real Android Home UI (owner-supplied) | GENUINE APP UI |
| `website/assets/edgefit-benchmark-android.jpg` | `4A53AAA6…138AED` (full: `4A53AAA626B27B6B9552FC536F2CDB60C78DD4830537F68E3A3E67030F138AED`) | Real Android Benchmark workflow | GENUINE APP UI |
| `website/assets/edgefit-hackathon-wallpaper-reference.jpg` | `B2D8CF12…67CF0BA` (full: `B2D8CF12463D3F3A8555D806FD676F90A968B20E7693AAB110DDD954A67CF0BA`) | Branding inspiration only | REFERENCE ART, NOT UI |

MISSING ASSETS (MUSE-C to capture): live dashboard screenshot in a real
browser; public API JSON response screenshot; any Android contribution-flow
screenshot must come from MUSE-A — none may be fabricated here.

## J. Relevant source and document paths

Backend branch (`implement/ef-global-01-2026-10-30`):
`cloud/worker.js`, `cloud/d1.js`, `cloud/db.js`, `cloud/api.js`,
`cloud/validate.js`, `cloud/qualify.js`, `cloud/retrieval.js`,
`cloud/cohort.js`, `cloud/schema.sql`, `cloud/public-reader.js`,
`cloud/ingest-demo.js`, `cloud/ops/issue-install.mjs`,
`cloud/migrations/001_init.sql`, `cloud/migrations/002_eligibility_audit_trigger.sql`
(SHA `B1E71B09…64770E`), `cloud/migrations/003_evidence_origin.sql`
(SHA `82C1C744…D2ACE`), `docs/ef-global-app-bridge-01-contract.md`
(v1.1; v1.0 fixtures SHA `dfa7a741…50454`),
`docs/ef-consent-01-policy.md` (SHA `B83E78FB…8CDB21`),
`docs/ef-consent-01-approval.md` (PREPARED, NOT APPROVED),
`docs/ef-global-operator-credentials.md`,
`docs/ef-global-demo01-enrollment.md`,
`docs/ef-global-demo01-android-handoff.md`,
`docs/ef-global-demo03-registry.md`,
`docs/ef-global-demo03-musea-handoff.md`,
`docs/ef-global-demo04-runbook.md`, `docs/qualification-status.md`.

## K. Git transfer status

HANDOFF-01 commits this file (docs-only) to
`implement/ef-global-01-2026-10-30` and non-force-pushes; remote SHA
reported in the milestone report and §B updated accordingly.

## L. Uncommitted work and ownership

At handoff creation the tree was clean; this file is the sole new
artifact. No source/infra/consent changes. MUSE-B owns all backend
paths above; MUSE-C owns website branch files; MUSE-A owns Android.

## M. Restrictions on public claims

MUST NOT claim: live Android-to-cloud PASS; genuine uploads completed;
published qualifying aggregates; production readiness; hardware
attestation; consent approval; any synthetic/legacy row as community
evidence. MAY claim: implemented + host-qualified + deployed backend
awaiting live physical-device qualification; dashboard honestly empty.

## N. Known blockers

1. Final `ef-consent-01` owner approval (text + SHA-256) — PENDING.
2. Genuine `device_contribution` credential issuance — NOT AUTHORIZED.
3. First physical upload via ingest gateway (P0) — NOT RUN.
4. 5-run/3-install same-cohort quorum — unreachable until P0–P2.
5. MUSE-A contribution-flow qualification on `29e5939` build — PENDING.
6. Live dashboard/API screenshots — MISSING ASSET (MUSE-C to capture).

## O. Exact MUSE-C retrieval instructions

From the website worktree, WITHOUT merging or switching branches:

```sh
git fetch origin implement/ef-global-01-2026-10-30
git show <HANDOFF_SHA>:docs/ef-global-to-repo-handoff-2026-11-01.md
git show <HANDOFF_SHA>:docs/ef-consent-01-policy.md
git show <HANDOFF_SHA>:docs/ef-global-app-bridge-01-contract.md
git show <HANDOFF_SHA>:docs/ef-stats-01-contract.md
git show <HANDOFF_SHA>:docs/qualification-status.md
```

`<HANDOFF_SHA>` = pushed HANDOFF-01 commit (see milestone report §D).

## P. Android installation qualification

REPORTED (owner log, MUSE-A EF-ANDROID-RECOVERY-INSTALL-07, NOT
independently re-verified by MUSE-B): SM-S908U install_id `95f9195b…`,
cold relaunch OK, 0 sessions, no fatals, VERDICT: PASS. This qualifies
the APP INSTALLATION only.

## Q. Unverified Android-to-cloud upload

No genuine POST receipt, no authenticated GET status, no withdrawal,
no D1 `device_contribution` row (live count = 0). Status: NOT RUN.
The install PASS in §P must never be presented as upload PASS.

## R. Consent approval status

`ef-consent-01` revision 2026-11-01, SHA-256
`B83E78FBF78AD0FBE4E77CFBBCF37709519A4305A45D86FFCCFF6C72E78CDB21`
(recomputed this milestone — MATCH). Approval-in-principle ONLY;
`docs/ef-consent-01-approval.md` fields BLANK / NOT APPROVED.
Allowlist EMPTY; `v1` and `ef-synth-01` permanently excluded.
Proposed retention: collection closes and all hackathon rows, tombstones,
demo credentials and operator copies deleted by `2026-12-31T23:59:59Z`;
no judging archive; no migration to future production under this version;
Cloudflare-internal backups outside operator control are disclosed as a
limitation.

## S. Publication/quorum restrictions

Publication requires allowlisted consent AND `origin=device_contribution`
AND 5 runs / 3 installs same exact cohort AND no revocation. Current
genuine eligible population: ZERO. Dashboard `groups:[]` / `artifacts:[]`
is the CORRECT output, not an error.

## T. Remaining demonstration requirements

Consent final approval → issue 1 device credential → MUSE-A physical
benchmark + explicit opt-in → POST + receipt (P0) → D1 provenance check →
withdrawal test (P1) → 2nd/3rd installs same cohort (P2) → allowlist
activation → quorum aggregate on dashboard (P3). Post-approval runbook:
[docs/ef-global-demo04-runbook.md](ef-global-demo04-runbook.md).
