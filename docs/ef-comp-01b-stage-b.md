# EF-COMP-01B Stage B: Prospective Exploratory Protocol (PRE-REGISTERED, NOT AUTHORIZED TO RUN)

Owner-executable steps (verify/live/score commands):
`docs/ef-comp-01b-owner-run.md`.

Two-configuration, six-run exploratory comparison of
Baseline R vs Muse M0/M1 on the Galaxy S22 Ultra. This
document freezes the design BEFORE any new measurement.
Running it requires separate owner authorization per run
and per Muse call. Results must be labeled exploratory,
never statistically established.

## 1. Frozen identities

- Model: `Llama-3.2-1B-Instruct-Q4_K_M.gguf`, SHA-256
  `6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83`.
- Workload: `edgefit-prompt-v1`, prompt SHA-256
  `36104db0657a294048410f9043d4efc8510a77088ca1e2f17c0b6c8359c44382`.
- Runtime: llama.cpp `0.7.0-dev`, `arm64-v8a`, CPU backend.
- Native variant: `benchmark` (the current benchmark APK
  build; record exact APK bytes + build time at execution).
- Config A: ctx=512, threads=4, predict=64, greedy.
- Config B: ctx=512, threads=8, predict=64, greedy.
- Candidate set: exactly {A, B}. No third configuration.

## 2. Device validation gate (before any decision)

On the S22 Ultra, the owner opens Benchmark → Select
Config (unbound, ROUTE build) and verifies Manual A and
Manual B are both offered as supported, then confirms
the Config line shows each exact triple. If B is refused
or either line mismatches, STOP: the study is redesigned,
not forced. Record the validation outcome with a
screenshot per option.

## 3. Frozen decision procedure

1. Build the R/M0/M1 inputs from Stage A code:
   candidate set {A, B}; authorized historical evidence
   = benchmark-variant 512/4/64 runs B/C/D only
   (Run A excluded: Debug build, proof gate refuses).
   Config B is unmeasured: R MUST rank it below A with a
   null prediction, and may select only A or abstain.
2. Record the R decision (selection + factors) in the
   study log with timestamp.
3. Request the M0 proposal (constraints only, measurements
   withheld) with explicit owner approval. Record the
   validated response + pack hash + timestamp.
4. Request the M1 proposal (same constraints + the
   authorized B/C/D evidence) with explicit owner
   approval. Record the validated response + pack hash +
   timestamp.
5. Verify all three selections + both pack hashes are
   recorded BEFORE run 1 starts, ordered against the
   benchmark START time: the run-id stamp
   (`BENCH-yyyyMMdd'T'HHmmss'Z'-hex`, captured at run
   entry before guards/native work — BenchRunner.kt) IS
   the START time; `collected_at_utc` is captured at the
   same entry point and must agree within 120s.
   Require `decided_at_utc` strictly < START for every
   arm on every run (equal second-precision stamps prove
   nothing). Executable check:
   `EvalStudy.verifyRunOrdering`. A hash alone is not
   proof without the ordering.
6. M0 is NOT evidence-equivalent to R/M1: report it as
   the no-history control, never as a fair fight.

## 4. Run order (manual unbound runs, ROUTE correction)

Order: A, B, B, A, A, B (6 runs), each an individually
initiated UNBOUND manual run via Benchmark → Select
Config → Run. No Agent sessions, proposals, or repeats
are involved, so no experiment budget is consumed and
no session split is needed. One deliberate Run press =
one run; no batch approval, no background execution.
(Supersedes the earlier two-session plan: the ROUTE
manual selector removed the repeat-proposal dependency.)

## 5. Controls per run

Same charger state for all 6; cooldown between runs;
thermal (before/mid/after) + available-RAM % recorded
from the result screen; any refusal, failure, or
interruption is recorded as-is, never silently retried
or backfilled. Export every benchmark JSON immediately
and extend the corpus manifest (hash-verified) before
computing metrics.

## 6. Analysis (offline, Stage A code)

- Error metrics only for predictions with recorded
  timestamps predating collection.
- Regret over {A, B} medians once both are measured.
- Invalid/rejection/citation/abstention tallies from the
  two proposal responses + host gate evidence.
- Report: selections, observed medians, regret per arm,
  limitations (N=3 per config, single device, exploratory).

## 7. API budget

Exactly 2 paid Muse calls (M0 + M1 proposals). Prompt/
completion tokens and cost transcribed from the provider
usage response into the CostLedger; unknown-exact cost
pre-disclosed per call. Follow-ups are OUT OF SCOPE for
this study unless separately authorized.

## 8. Go / no-go

GO iff: both configs validate on-device; R + M0 + M1
selections and pack hashes recorded with pre-run
timestamps; owner approves each call and each run.
NO-GO (stop and report) on: any validation failure;
thermal `severe` at run start; >1 failed/refused run;
any missing export; any timestamp inversion.
