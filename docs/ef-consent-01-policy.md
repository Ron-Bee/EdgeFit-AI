# Consent policy ef-consent-01 — physical-device contribution

Status: PROPOSED. NOT APPROVED. The publication allowlist stays
EMPTY until the owner approves this exact text (record the SHA-256
of the approved file with the approval).

Revision: 2026-11-01 — bounded hackathon retention to
2026-12-31T23:59:59Z, no retained judging archive, independent
installation rule, terminology fix. No collection or publication
scope change beyond retention/disposal bounds.

Version history: ef-consent-01 was proposed during the Android
bridge milestones but NEVER approved, NEVER allowlisted, and NO
database row carries it (staging rows are `v1` vintage). It has no
tainted history. `v1` and `ef-synth-01` remain permanently
ineligible for genuine publication.

Scope: voluntary contribution of genuine physical-device EdgeFit
benchmarks to hackathon staging, with possible exact-cohort
aggregate publication after the 5-run / 3-installation quorum.

## What is uploaded (per explicitly confirmed batch)

Measurement results of runs YOU choose to contribute:

- Throughput: generation/prefill tokens-per-second, token counts,
  phase timings (load/prefill/generate ms).
- Memory signals: available-memory percent, low-memory flags.
- Model identity: SHA-256, size in bytes, quantization label.
- Runtime/build: implementation, backend, version, effective
  optimization and status, build key.
- Workload identity: workload id, prompt SHA-256 (hashes, never
  prompt text).
- Inference configuration: context length, thread count,
  prediction length, sampler, configuration hash.
- Device metadata: model string (e.g. SM-S908U), SoC platform,
  Android SDK level, supported ABIs, CPU count, total RAM,
  and a device-profile hash. No phone number, no account id,
  no contacts, no location, no unrelated app data.

## Installation identity

The server assigns your installation a random identifier plus a
secret credential issued by the operator. The identifier is used
to count DISTINCT installations toward quorum (as salted,
irreversible values) and to authorize your own status lookups
and withdrawals. Raw installation identifiers are NEVER published.
They are NOT hardware attestation and prove nothing beyond
"this credential holder uploaded these rows".

## How evidence may be used

- Stored in the staging evidence database with your consent
  version and timestamp.
- Qualified by exact cohort (identical model hash, device class,
  runtime, build, workload, prompt hash, configuration).
- After quorum, published ONLY as aggregate statistics
  (medians/min/max, run and installation counts). Individual
  run values are NEVER published (scope `aggregates` only).
- Below quorum, statistics are withheld; the dashboard shows
  honest insufficient-evidence states.

## Withdrawal

In-app WITHDRAW CLOUD CONTRIBUTION revokes your submission on
the server (terminal `REVOKED` state). Revoked rows are excluded
from all future aggregates immediately on next read; the public
dashboard refreshes manually. A revocation tombstone (row id,
state, audit trail) is RETAINED for evidence integrity; the
measurement no longer counts anywhere.

DELETE LOCAL RESULT removes only your on-device copy. It does
NOT withdraw the cloud contribution. To remove cloud evidence,
use WITHDRAW (or ask the operator to revoke the credential and
withdraw affected rows).

## Retention and disposal (bounded hackathon policy)

- New collection under this version ends at owner-declared demo
  close, and in no case later than 2026-12-31T23:59:59Z.
- By 2026-12-31T23:59:59Z the owner deletes: all rows stamped
  with this version, their audit tombstones, the demo
  credentials, and any operator backups/exports containing them.
- Revocation before teardown works as in Withdrawal above
  (immediate exclusion; the tombstone is then deleted with the
  rest at teardown).
- Published aggregates lose their backing rows at teardown; the
  dashboard reverts to honest insufficient-evidence states.
- NO judging archive is retained. Judges inspect the live
  system before teardown. No exception, no extension.
- A future production network needs a separately reviewed
  consent version and retention policy. Hackathon rows are
  NEVER migrated there under this version.
- Truthful limit: deletion covers everything within owner
  control (staging database, credentials, operator files).
  Cloudflare-internal replication/backups beyond owner control
  are not covered and no promise is made about them.

No sale, no advertising use, no sharing beyond the published
aggregates described above. Operator access is limited to
qualification, withdrawal handling, and integrity checks.

## Independent installations

Quorum counts DISTINCT AUTHENTICATED INSTALLATIONS as the server
counts them (5 runs / 3 installations per exact cohort). An
installation identity alone does not prove a separate physical
device, and the project does not deliberately reinstall,
re-enroll, or reset one device to inflate quorum diversity.
Public statistics describe authenticated installations, never
attested distinct physical devices.

## Your choice

Contribution is OPTIONAL and per-batch: each upload requires
your explicit confirmation showing exactly what will be sent.
Local benchmarking, history, and results work fully WITHOUT any
cloud contribution. You may decline, contribute some batches and
not others, and withdraw contributed batches as described above.

## Owner approval record (fill on approval)

- Approved version: ef-consent-01
- Approved text SHA-256: _pending_
- Scope: DEMO physical-device staging + quorum publication
- Date (UTC): _pending_
- Allowlist action: _pending (separate deployment step after approval)_

Approval populates the allowlist with `ef-consent-01` ONLY, in
BOTH the staging worker (`WORKER_DEPS`) and the public worker
(`PUBLIC_DEPS`), via reviewed code change + redeploys. Origin
gate (`device_contribution`) stays independently enforced.
