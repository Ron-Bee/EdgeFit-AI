# EF-COMP-01A Stage A: Offline Baseline and Evaluation Harness

Stage A implements the offline half of the evidence-grounded
Muse value evaluation: a deterministic rules-only baseline,
shared metrics, M0/M1 evidence contracts, and a validated
test-corpus manifest. No live Muse calls, no benchmarks,
no cloud deployment. Stage B (prospective physical
comparison) is NOT authorized by this document.

## 1. Corpus inventory (verified 2026-10-30)

File-backed physical measurements (all SHA-256 verified,
all `edgefit-bench-result` v1, all COMPLETED, all
512/4/64, same model `6f85a640…`, same workload
`edgefit-prompt-v1` / prompt `36104db0…`):

| Entry | Run | tok/s | Build identity |
|---|---|---|---|
| run-a-debug | BENCH-…93ce3477 | 0.383 | No x_native_build (Debug) |
| run-b-benchmark | BENCH-…36227bc0 | 14.382 | benchmark, req -O3 -g, eff -O2 attested |
| run-c-benchmark | BENCH-…4197e471 | 16.585 | benchmark, req -O3 -g |
| run-d-benchmark | BENCH-…6555133c | 7.407 | benchmark, req -O3 -g, thermal elevated |

Plus 3 SHA-verified device-context profiles. DB `data/edgefit.db`
(read-only check): integrity ok, 1 profile, 2 benchmark rows
(Runs A/B). DB hash unchanged.

Documented (phone-resident, never exported, no repo artifact):

- `pred-byok-live` (SES-aa255b757ace13cd): live 12.0/45.0 tok/s.
- `pred-agent-exp1` (parent SES-a77eaa6f4cd92641 exp 1):
  live 11.0 tok/s → run …8cdc4efe at 16.072 tok/s.
- `pred-agent-exp4` (SES-63a4ace2c6d85778): live 17.5/27.0 →
  run …38ccf1f5 at 17.738 tok/s, 256/4/64.

Prediction timestamps are unrecorded in repo-available
evidence, so historical error metrics are NOT_EVALUABLE
and the corpus has zero configuration variation, so
selection regret and any superiority claim are
NOT_EVALUABLE. The manifest tool reports this itself:
`measurements_verified: 4, evaluable_pairs: 0,
config_variation: false`.

## 2. Baseline R (`EvalBaseline`, R1-corrected)

Decision rules (documented in code): host-validate
candidates against `BenchConfig.validate` + device thread
max; rank measured candidates by sample median (ties by
ascending triple); unmeasured rank below with null
predictions; abstain when nothing is measured
(`insufficient-evidence`) or nothing is valid; memory is
advisory-only and never excludes. R1 correction:
same-triple records AGGREGATE (samples concatenate,
thermal counts sum) under one cohort key instead of
last-wins overwrite; mixed cohorts throw. Deterministic by
construction (tested: evidence permutation → identical
decision).

## 3. Metrics (`EvalMetrics`, R1-corrected)

Shared by R/M0/M1: valid proposal rate, invalid rejection
rate, fabricated citation rate, abstention + appropriateness
rates, signed/abs prediction error (strict `predicted_at <
collected_at`), selection regret among tested options,
observed-throughput passthrough, latency, cost, sample
counts. R1 corrections: citations check against each
case's OWN authorized ids (never pooled);
`invalidProposalRate` (submission share) is split from
`hostRejectionRate`, which counts only invalid submissions
with recorded refusal evidence — fixture-only invalid
flags stay NOT_EVALUABLE. Unsupported metrics return
NOT_EVALUABLE with a reason; missing data is never
zero-filled.

## 4. Arms (`EvalArms`, R1-corrected)

- M0: constraints + the shared candidate set, measurements
  withheld (null + recorded exclusions).
- M1: same set + only measurements passing
  `verifyProof` against a `MeasurementProof` transcribed
  from the validated artifact (model, workload, prompt,
  runtime, ABI, variant, COMPLETED status, timestamp, run
  id, config triple). Caller flag assertions are ignored;
  refusals name the failed check.
- Pack hashes prove pack integrity, NOT pre-measurement
  freezing: Stage B records each hash with a timestamp
  predating run 1 (see `ef-comp-01b-stage-b.md`).
- `requireSharedCandidates` throws when arms diverge.
- Responses validate through production
  `AgentApi.validateProposal` (no drift).
- `CostLedger` totals cost/latency or withholds honestly.
- Pack hashes commit to exact bytes; rehashing detects
  post-hoc outcome leakage. Target outcomes never enter
  either pack (caller supplies prior evidence only).

## 5. Manifest tooling

- `eval/comp-01a-corpus.json`: the corpus above; file paths
  resolve `${EDGEFIT_EVIDENCE}` only.
- `scripts/ef-comp-01a-manifest.mjs`: hash/contract/pair
  validation, exit 0/1, read-only.
  Run: `node scripts/ef-comp-01a-manifest.mjs`
  (with `EDGEFIT_EVIDENCE` set).
- `scripts/ef-comp-01a-manifest.test.mjs`: 5/5 offline
  fixture tests. Run: `node scripts/ef-comp-01a-manifest.test.mjs`
  (`EF_COMP_TMP` overrides the temp base for sandboxes).

## 6. Reuse map

`BenchConfig` (bounds), `BenchContract` (identities),
`BenchConfigData` (candidate type), `SessionBenchBridge`
(config maps), `SessionJson` + `BenchConfig.sha256Hex`
(canonical hashes), `AgentApi` (response gate),
`SessionComparison` semantics (error direction; metrics
use strict temporal ordering for frozen artifacts).
No parallel session, evidence, or measurement system.
No new cloud dependencies. No native changes.

## 7. Qualification

- JVM: new `EvalBaselineTest` (10), `EvalMetricsTest` (11),
  `EvalArmsTest` (7); full suite must stay green.
- Node: manifest validator 5/5 incl. tamper, missing-file,
  NOT_EVALUABLE-pair, synthetic-labeling, variation cases.
- Corpus run: exit 0 with the §1 report (proves the tool
  refuses to overclaim on the real corpus).
- Stage B needs: prediction timestamps in session records
  (already durable on-device for new runs), exported
  benchmark artifacts for every trial, and the run manifest
  frozen before execution. Pre-registered in
  `docs/ef-comp-01b-stage-b.md` (A/B, A-B-B-A-A-B, two
  sessions × 3 approvals, 2 paid calls); NOT authorized
  to run.
