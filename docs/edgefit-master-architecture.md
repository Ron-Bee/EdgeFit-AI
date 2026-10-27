# EdgeFit master architecture: global evidence network

Governing architecture for EdgeFit's two-database evidence network,
global benchmark website, cloud orchestration, and Meta Muse API
communication. Design-only: no infrastructure is deployed by this
document. Existing qualified contracts
(EF-META-05 retrieval, EF-DATA-02 ingestion, EF-BENCH-01 results) are
preserved and extended, never weakened.

EF-ARCH-02 v2 reconciliation (OWNER REVIEW REQUIRED except where
marked ACCEPTED): adds the public developer application scope —
optional direct BYOK operation alongside the managed backend mode,
an Android private session store, a bounded agentic loop, public
statistics methodology, and signed distribution. Original sections
stand; v2 material is marked and traced in
`docs/ef-arch-02-traceability.md`.

Status legend: IMPLEMENTED (built) · QUALIFIED (built + tested/accepted)
· QUALIFIED PROTOTYPE (qualified off-cloud, not production-hosted) ·
PROPOSED (designed, not built) · NOT STARTED. New work additionally
uses HOST QUALIFIED · PHYSICALLY QUALIFIED · LIVE API QUALIFIED ·
DEPLOYED · PRODUCTION QUALIFIED · BLOCKED per EF-ARCH-02.

## 1. Components and status

| # | Component | Status | Notes |
|---|---|---|---|
| 1a | Windows SQLite prototype (profiles, experiments, predictions, observations) | QUALIFIED | EF-DATA-01 fixtures + EF-DATA-02 import, workstation-hosted |
| 1b | On-device Android private session store | NOT STARTED | App holds no SQLite/Room code; JSON export only |
| 2 | EdgeFit cloud backend (auth API, validation, consent, retrieval) | PROPOSED | `api.edgefit.ronbeesoftware.com` (proposed, not configured) |
| 3 | Global SQL evidence database (consented, qualified, deduplicated) | PROPOSED | Multi-device; Cloudflare D1 candidate |
| 4 | Muse Spark connection | QUALIFIED PROTOTYPE (Windows-hosted EF-META-05) | Live round trip qualified on workstation; cloud orchestration PROPOSED |
| 4a | Direct BYOK mode (Mode A) | PROPOSED (direction ACCEPTED, ADR-015) | User key, direct HTTPS, Keystore rules; not built |
| 4b | Managed service mode (Mode B) | PROPOSED | Server-side key; not built, never claimed |
| 5 | Public explorer `edgefit.ronbeesoftware.com` | PROPOSED | Static site over public API (proposed, not configured) |
| 6 | Backend API contract EF-CLOUD-01 | PROPOSED | `docs/ef-cloud-01-api-contract.md` |
| 6a | Stats methodology EF-STATS-01 | PROPOSED | Annex to EF-CLOUD-01 per ADR-018 |
| 7 | GitHub source + public snapshots | IMPLEMENTED (source) / PROPOSED (snapshots) | Never the live database |
| 8 | Android session store | NOT STARTED | Contract in §17; EF-SESSION-01 |
| 9 | Bounded agentic loop | PROPOSED (design) | `docs/edgefit-agentic-workflow.md`; workstation loop HOST QUALIFIED |
| 10 | Signed distribution | NOT STARTED | `docs/edgefit-release-and-distribution.md`; EF-DIST-01 |

`ronbeesoftware.com` subdomains, Cloudflare Workers, and D1 are
proposed deployment choices — not implemented infrastructure. No DNS,
Workers, D1, accounts, or credentials are created here.

## 2. Topology

```
Phone (EdgeFitDiag: JSON export, no local database yet)
  │  HTTPS out only: consent submission upload (explicit tap)
  ▼
Cloud backend (auth, validation, consent, dedup, aggregation)
  │  SQL read/write            │  HTTPS out (server-side key)
  ▼                            ▼
Global evidence DB        Meta Muse Spark (responses API)
  ▲
  │  HTTPS public reads (published aggregates only)
Public explorer (static site)
```

v2 Mode A (direct BYOK, PROPOSED direction ACCEPTED): the phone
additionally calls `api.meta.ai` directly over HTTPS with the
developer's own key (explicit taps only, session custody default).
No EdgeFit backend is contacted in this mode; the user's key never
leaves the phone except to Meta. Mode B (managed service) keeps the
topology above with a server-side key and remains PROPOSED.

GitHub holds source plus periodic approved JSON snapshots for
transparency; it is never queried as a live database.

## 3. Evidence feedback loop

1. Backend requests a Muse prediction for a (device class, model,
   config) triple, attaching bounded authorized evidence.
2. Muse returns a prediction with reasoning referencing evidence ids.
3. Owner runs the bounded on-device benchmark (explicit tap); the
   phone stores the observation locally only.
4. Owner reviews and explicitly consents to upload; the phone pushes
   the signed submission to the backend.
5. Backend validates, deduplicates, qualifies, and publishes.
6. Future predictions retrieve the enlarged evidence set. Loop closes.

## 4. Request initiation map

| Request | Initiator | Target | Auth |
|---|---|---|---|
| Submission upload | Phone app (explicit tap) | Backend | Owner-issued upload token |
| Eligibility/status poll | Phone app | Backend | Upload token |
| Public aggregates/runs | Browser | Backend | None + rate limit |
| Evidence retrieval for Muse | Backend tool dispatcher | Global DB | Internal only |
| Muse prediction call | Backend API client | `api.meta.ai` | Server-side key |
| BYOK prediction/follow-up (Mode A) | Phone app (explicit tap) | `api.meta.ai` | User key, device-held |
| Snapshot publish | Owner workstation | GitHub | Maintainer push |

The phone accepts no inbound connections. Browsers never call Meta.
The backend never pushes to phones. In Mode A the phone contacts
only `api.meta.ai` (user key) and, for uploads/stats, the EdgeFit
backend (upload token / anonymous) — never any other host.

## 5. Data ownership, privacy, consent, synchronization

- The device owner owns local data; the backend stores only
  explicitly consented submissions under a published retention policy.
- No PII fields exist in any contract (no name, email, serial,
  advertising id). Device identity is a random install UUID,
  rotatable by reinstall, used solely for independent-device counts.
- Consent is per submission batch, explicit, recorded with timestamp
  and policy version; withdrawal marks rows `revoked` (excluded from
  aggregates, tombstoned rather than silently deleted).
- Aggregate publication and individual-run disclosure are INDEPENDENT
  consent scopes (acceptance correction). Aggregate-only publication
  is the default; listing individually identifiable runs requires a
  separate explicit opt-in under a disclosure policy that is PROPOSED
  and not yet approved — until then, run-level disclosure stays
  disabled even for PUBLISHED rows.
- Synchronization is phone-push only; there is no cloud-to-phone
  sync, no remote wipe, no silent upload.

## 6. Schemas

Workstation prototype (private SQLite, EF-DATA-01/02, QUALIFIED):
`device_profiles`, `experiments`, `predictions`, `benchmark_runs`,
`physical_device_profiles`. A future on-device session table is NOT
STARTED; the Android app currently persists nothing itself.

Global (one row per accepted submission, plus rollups):
`submissions` (id, install id hash, model sha, config hash, workload
id, measured JSON, eligibility, received_at),
`qualified_runs` (validated subset promoted by rule, never by hand),
`aggregates` (per group: mean, median, n, device count, updated_at).
Local ids are never reused as global ids; the submission id links them.

## 7. Stable identifiers

- Model artifact: SHA-256 of GGUF bytes (existing EF-BENCH-01 field).
- Workload: `edgefit-prompt-v1` + prompt SHA-256 (existing).
- Config: SHA-256 of canonical `{n_ctx, n_threads, n_predict,
  temperature, sampler, seed}`.
- Session/run: `BENCH-…` run id (existing, device-unique).
- Submission: backend-issued ULID at accept time.
- Device (global): salted hash of install UUID; raw UUID never leaves
  the phone in stored form outside the consented submission envelope.

## 8. Meta credential handling

The Meta API key lives only in the backend secret store (environment
or managed secrets), injected at deploy time, rotated on schedule,
and never logged, returned, or embedded in apps, snapshots, or repos.
Muse calls carry no device identity beyond the authorized evidence
payload described in section 10.

v2 (Amendment 002-A / ADR-015, ACCEPTED): Mode A adds a second,
user-held credential class. The developer's key is entered
explicitly, held session-only by default (optional Keystore-backed
persistence, Forget Key removal), sent only to `api.meta.ai`, and
never uploaded to EdgeFit infrastructure, written to exports/logs,
or embedded in builds. OAuth/PKCE delegation is PROPOSED only: no
transferable mechanism is evidenced, and Muse Code sign-in is
explicitly non-transferable.

## 9. Cloudflare Workers/D1 feasibility (to verify, not assumed)

Workers, D1, and the `ronbeesoftware.com` subdomains are proposed
deployment choices — not implemented infrastructure.

- Workers fits the API shape (stateless JSON, auth, validation);
  D1 fits the global schema (SQLite-compatible SQL).
- Must verify before committing: D1 size/row limits vs projected
  submissions, write throughput for upload bursts, Worker CPU-time
  limits for aggregation jobs, custom-domain TLS setup, backup/export
  story, and exact plan costs. Costs are NOT stated here.
- Deployment requirements: Cloudflare account, domain delegation of
  `ronbeesoftware.com` subdomains, Wrangler project, secret store,
  staging vs production environments, migration runner.
- Decision ADR-009 records Cloudflare as candidate, not commitment;
  a plain VPS + Postgres remains the fallback.

## 10. Upload rules and anti-fraud

Authentication: owner-issued per-install upload tokens, revocable.
Quotas: per-token daily submission cap + global rate limits; excess
rejected with `429` and `Retry-After`. Rejection: schema-invalid,
oversized, unknown contract, or hash-mismatched bodies are rejected
with itemized errors and never stored. Provenance: every row keeps
source file hash, collector/app version, and receipt time.
Anti-fraud: exact-duplicate suppression by content hash; anomaly
bounds per group (reject physically implausible throughput);
per-device submission velocity caps; manual quarantine queue.

## 11. Aggregation

A published cohort compares only identical model artifacts (equal
GGUF SHA-256), equal config hash, equal workload id, equal
runtime/backend, and equal quantization label. Per cohort the API
publishes: arithmetic mean of tok/s, median tok/s, sample count n
(qualified runs), and independent device count (distinct salted
install hashes). Minimum cohort size (n and device floors) is set at
backend qualification time — no production statistics are fabricated
here. Groups below quorum are omitted from responses, which echo
the quorum floors instead (insufficient-evidence behavior).

v2 reconciliation (ADR-018, PROPOSED): cohort keys add explicit
build identity (`effective_opt` + `effective_opt_status`, never
requested flags alone); counting terminology becomes
independent-install until hardware attestation exists;
owner-approved provisional quorum is min_n=5 / min_installs=3
(design only — activation needs implementation qualification);
the stats methodology annex (`ef-stats-01-contract.md`) governs
grouping while `/v1/benchmarks/*` stays the HTTP surface.

## 12. Eligibility states

Four distinct levels — never conflated:

- Device-reported (`SUBMITTED`): a phone claims a measurement. This
  is self-reported data, NOT authenticated hardware proof; no
  attestation mechanism exists in this design.
- Schema-valid (`VALIDATED`): shape, hashes, and bounds verified by
  machine rule. Still not proof of honest execution.
- Qualified (`QUALIFIED`): dedup, anomaly, and quorum rules pass.
- Published (`PUBLISHED`): included in public aggregates and Muse
  evidence. `revoked` is reachable from any state via consent
  withdrawal or fraud finding.

Transitions are rule-driven and logged. Only `PUBLISHED` rows feed
the website and Muse.

## 13. Bounded evidence to Muse

Muse receives only server-authorized evidence through an allowlisted
retrieval tool that extends the EF-META-05 pattern: fixed tool name,
exact fields, pinned value sets, deterministic order, hard result
cap. The server query is fixed and parameterized; model input never
becomes SQL, and no unrestricted model-generated SQL exists anywhere
in this design. Raw submissions, install identities, and revoked
rows are never visible to the model. Retrieval responses cite
aggregate ids the analysis must reference.

## 14. Conditioning vs training

Evidence conditions inference in-context: retrieved measurements are
input text for a prediction call. Model weights are never updated, no
fine-tuning occurs, and no EdgeFit data trains Meta models under this
design. Every publication must preserve this distinction.

## 15. Website and explorer

Static explorer at `edgefit.ronbeesoftware.com` (PROPOSED deployment
choice — not implemented infrastructure, no DNS configured) reading
only the public aggregate endpoints. First version: model table
with median throughput, sample/install counts, and methodology text.
Per-run drill-down is PROPOSED and disabled until its disclosure
policy is reviewed and explicitly approved. No submission, auth, or
comment features in v1.

## 16. Migration from the Windows SQLite prototype

1. Freeze prototype schema; export script dumps consented rows to
   canonical JSON (hashes preserved).
2. Backend validates the dump through the same submission pipeline
   (prototype rows enter as `SUBMITTED`, not pre-qualified).
3. Dual-run: prototype remains the local store; global DB is
   authoritative for published evidence.
4. Cutover when the explorer and Muse retrieval read global only.
   The local database is retained permanently as the private store.

Local-to-global state mapping (v2, corrected at acceptance):
local measurement and validation, then user-authorized submission
carrying explicit consent, then `SUBMITTED` → `VALIDATED` →
`QUALIFIED` → `PUBLISHED`. No local state — including any
`globally_eligible` marker — automatically grants public
eligibility, and global eligibility is NOT a prerequisite for
entering `SUBMITTED`; the pipeline rules alone decide promotion.
Rejection: schema/hash/bounds failures refuse at intake and are
never stored. Revocation: consent withdrawal (or a fraud finding)
moves any held state to `revoked`, tombstoned and excluded from
aggregates; revoked rows never return to the pipeline.

## 17. Android private session store (v2, PROPOSED)

Fulfills component 1b. One session per prediction → benchmark →
recommendation loop, persisted in app-private storage (SQLite/Room
or equivalent — implementation choice, EF-SESSION-01):

- `session_id` (random, stable), `created_at_utc`, `mode` (byok).
- `model_sha256`, `model_size_bytes`, `profile_sha256`
  (canonical profile hash — never filenames alone).
- Prediction: JSON bytes, `predicted_at_utc`, source
  (`meta:<model>` or `replay`), evidence-pack id list.
- `bench_config` (canonical), `run_id` + benchmark source hash
  once attached, deterministic errors, recommendation JSON +
  provenance, experiment count/budget.
- Terminal states: `abandoned`, `failed`, `cancelled`,
  `completed`; every transition timestamped with cause.
- Consent records per export/submission; revocation marks local
  copies `revoked` and blocks re-export until re-consented.

Integrity comes from hashes and monotonic clocks; app-local
storage is explicitly NOT hardware authenticity proof.
Process-death recovery: on relaunch offer resume (re-validate
everything, never silently continue inference) or abandon with a
recorded reason. No session content leaves the phone except via
explicit export (JSON) or consented submission upload.

## 18. Product surface and distribution (v2, PROPOSED)

User journey: launch → inspect capabilities → stage GGUF →
optional BYOK connect → prediction → persisted estimate →
benchmark with live stages → measured results → error review →
authorized follow-up → recommendations → approve next run or
finish → optional consented export/submission. Offline profiling
and benchmarking work without credentials, unchanged.

Distribution follows `edgefit-release-and-distribution.md`
(ADR-017): signed `public-preview` APKs via GitHub Releases with
gates and a compatibility matrix; Play later on the same package.
Requirements live in `edgefit-product-requirements.md`; the
agentic loop in `edgefit-agentic-workflow.md`; traceability in
`ef-arch-02-traceability.md`.
