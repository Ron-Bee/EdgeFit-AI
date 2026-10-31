# EF-GLOBAL-01 G2 staging design (PROPOSED, local only)

Owner decision G2-A authorized local staging-compatibility
engineering ONLY. This document records the batch-transaction
design, the D1 mapping with its explicit unknowns, migration,
operations, backup, isolation, cost, and the staging tests that
must pass before any activation. Nothing here provisions,
deploys, or spends anything.

Status words: BUILT (in `cloud/`, host-tested) vs STAGING-GATED
(requires provisioning + staging qualification, not authorized).

## 1. Batch-transaction design (BUILT)

D1 `batch()` executes a fixed statement array atomically but
cannot return mid-batch reads to JavaScript: there are no
interactive transactions. Every atomic unit is therefore shaped
as reads-outside, one guarded batch, interpret-results:

- Submission: `[claim upsert, conditional INSERT ... SELECT ...
  WHERE count <= quota, conditional history, counter
  compensation]`; the quota verdict is the INSERT's change
  count (1 = admitted 201, 0 = 429 + `retry-after`).
  Compensation keeps rejected claims counter-exact.
- Eligibility transition: `[guarded UPDATE ... WHERE
  eligibility = from, conditional history, conditional
  published_at]`; applied iff changes = 1, else deferred.
- Revocation: read outside, then `[guarded UPDATE ... WHERE
  eligibility = observed, conditional history]`; lost races
  retry once against fresh state (bounded).

Adapter interface: `{ get, all, run, exec, batch, close }`.
`batch()` takes `[{ sql, params?, mode? }]` (`run`/`get`/`all`),
returns aligned results, and rolls back on any failure. Error
contract: UNIQUE violations surface as `UNIQUE constraint
failed: <table>.<column>`; contention as errcode 5 /
`SQLITE_BUSY` / `database is locked`. The D1 binding must
satisfy this same contract; its error-mapping table takes
entries ONLY from observed staging behavior.

## 2. D1 mapping and explicit unknowns (STAGING-GATED)

Verified offline: interface conformance of both adapters,
atomicity, result shapes, and the app subset re-proven through
the D1-shape adapter (`cloud/test/d1shape.js`,
`cloud/test/g2-d1shape.test.js`). Those prove the LOGIC uses
only batch-compatible operations — never D1 service behavior.

Must be verified on staging before activation:

1. `env.DB.batch()` atomicity + rollback on a failing statement.
2. Batch result metadata (`changes` per statement) for the
   quota/CAS verdicts.
3. Real D1 error text for UNIQUE/busy/limit failures, captured
   into the adapter mapping table.
4. Single-writer contention behavior under concurrent batches
   (claim race, duplicate race, revoke race against staging D1).
5. Near-128 KiB submission inserts (statement-size accounting).
6. Per-invocation query caps and rows-read/written billing for
   the qualification cron at paginated batch sizes.
7. Read-after-write behavior for owner flows (POST then receipt,
   DELETE then read); Sessions API + bookmarks if replication
   is in play.
8. Migration apply path (`wrangler d1 migrations`) on `--local`
   then staging, including apply idempotence.

## 3. Migrations (BUILT files, STAGING-GATED apply)

- `cloud/migrations/001_init.sql` is the initial schema,
  verbatim `cloud/schema.sql` plus a header; a test fails on
  drift. Never edited after first apply; later changes are new
  numbered migrations. No seeds, no fixtures migrate anywhere.
- Procedure: export snapshot + Time Travel bookmark, apply
  `--local`, apply staging, verify table counts, record the
  journal. Rollback on staging: Time Travel restore (staging
  may alternatively be recreated while synthetic-only).

## 4. Token operations design (H9; procedure, no endpoint)

Manual, owner-operated, revocable upload tokens; no public
issuance endpoint ever. Issuance: ≥128-bit CSPRNG token per
install, recorded ONLY as SHA-256 at rest with install id and
creation time. Rotation = issue + revoke old (`revoked_at`).
Leak response: revoke immediately; prior rows stand (no
retroactive invalidation); rotated install re-registers by the
same manual path. Entropy/format pinned in the staging runbook
before any authenticated staging submission.

## 5. Edge rate limits design (H10; STAGING-GATED)

Server-enforced per-install quotas + `retry-after` are BUILT.
Global per-IP read limits are a Cloudflare edge responsibility:
configure rate rules for the staging Worker/hostname, then verify
(over-limit reads rejected before reaching quota accounting;
429s carry `retry-after`). No app-side IP limiter in staging.
No public exposure until enforcement is demonstrated.

## 6. Evidence Explorer note (H12; design only)

Public, read-only, aggregate-only over the three public
endpoints; methodology + evidence-limitations + ADR-014 notice
on every view; thin cohorts render `insufficient-evidence`
honestly; no run drill-down (runs stay 404), no submissions,
no auth, no comments. API staging qualifies before any
Explorer work begins. Not built in G2-A.

## 7. Backup and recovery (STAGING-GATED)

D1 Time Travel (point-in-time restore; retention 7d free / 30d
paid per current docs — re-verify) plus `wrangler d1 export`
snapshots before every migration or drill, stored outside the
repo with rotation. Restore drills run against a scratch
database, never blind overwrite. Required: one documented
export + restore rehearsal before staging admits fixtures.

## 8. Isolation (STAGING-GATED)

Separate staging database; distinct `installSalt` and tokens;
no production environment, bindings, DNS, or data; workers.dev
hostname only; synthetic fixtures only; consent policy v1 still
required before any real-device row (H5); no Meta keys anywhere
in staging (no Muse calls exist in the backend).

## 9. Expected cost

$0 on the Cloudflare free tier at staging scale (quotas to
re-verify at provisioning time); $5/mo paid fallback only with
separate owner approval. No spending authorized by G2-A.

## 10. H1-H13 disposition (G2-A)

H1 staging target A (B fallback); H2 5/3 for staging testing;
H3-H4 grouping baseline accepted; H5 synthetic-only; H6 exact
device strings; H7 runs disabled; H8 grouping as built, version
review before real data; H9 manual tokens; H10 edge + server
limits; H11 versioned migrations; H12 read-only Explorer;
H13 representative cohort. Production activation of everything
above remains separately gated.

## 11. Minimal staging acceptance (all STAGING-GATED)

Batch atomicity + CAS/claim contention proofs on staging D1;
error-shape mapping table captured; near-cap inserts pass;
migrations apply cleanly; backup/restore drilled; cron stays
inside budgets; read-after-write verified; rate limits
demonstrated; token rotation rehearsed; staging runs
synthetic-only with no production surface of any kind.
