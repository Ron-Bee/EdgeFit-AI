# EF-COMP-01B Owner Execution Procedure
(FINAL infrastructure; LIVE CALLS + RUNS need separate authorization)

Every command runs from the repo root in PowerShell 5.1
unless marked PHONE. Env-driven Gradle steps prefix
`:app:cleanTestBenchmarkUnitTest`: Gradle does not treat
env changes as task inputs, so without the clean step a
run may silently reuse stale results.

STOP conditions (anywhere): any FAIL/BLOCKED verdict,
hash mismatch, timestamp inversion, thermal `severe` at
run start, >1 failed/refused run, missing export, or a
provider error. Stop, record evidence, report. Never
retry a live call to "fix" a reply; never backfill runs.

## 1. Read-only verify the original freeze

    $env:EF_STUDY_VERIFY_DIR = "$pwd\eval\comp-01b"
    $env:EDGEFIT_EVIDENCE = 'C:\Users\Ron\EdgeFit-Evidence'
    $env:EF_STUDY_CORPUS = "$pwd\eval\comp-01a-corpus.json"
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    & <gradle-8.9>\bin\gradle.bat -p android `
      :app:cleanTestBenchmarkUnitTest :app:testBenchmarkUnitTest `
      --offline --tests "ai.edgefit.diag.EvalStudyRunnerTest"

PASS = BUILD SUCCESSFUL (manifest hash, R re-derivation,
request hashes, endpoint/model, M0 separation, evidence
re-hash all clean). Writes nothing. STOP on failure.

## 2. PHONE: verify exact APK + GGUF on the S22 Ultra

Install the ROUTE benchmark APK and record: app version
0.2.0 (`ai.edgefit.diag.bench`), APK SHA-256
`2813339C087DBD4191718085C0DC43B5AE5533A81A35889AEA7D3192575B46DE`
(9,043,807 bytes; Kotlin/UI delta only — native
llama.cpp/JNI libraries byte-identical to the historical
benchmark cohort, effective `-O2`, variant `benchmark`).
GGUF display name + size + SHA-256 from the run screen
must equal `6f85a640…5611df83`; runtime `0.7.0-dev`/
`arm64-v8a`. STOP on any mismatch.

## 3. PHONE: validate both candidate configs

On the Benchmark tab: no active session bound (Sessions
must show unbound), then tap Select Config. Confirm
three options: Device default, Manual A (512/4/64),
Manual B (512/8/64, supported on the 8-core S22).
Select A: the Config line reads exactly
`ctx=512 threads=4 predict=64 (manual selection)`.
Select B: `threads=8`. Screenshot each. STOP if B is
refused or either line mismatches: the study is
redesigned, not forced.

## 4. Review the two frozen request bodies

Read `eval/comp-01b/study-prompts.txt` and the `body`
fields of `study-request-m0.json` / `study-request-m1.json`.
Confirm: same candidates + candidate hash both arms;
M0 names no run id or throughput; M1 cites exactly the
three manifested runs. STOP on any deviation.

## 5. Explicitly authorize and execute M0

    $env:EF_STUDY_ARM = 'M0'
    $env:EF_STUDY_AUTHORIZE = 'YES'
    $env:EF_STUDY_STUDYDIR = "$pwd\eval\comp-01b"
    $env:EF_STUDY_API_KEY = '<owner key; env only, never pasted to chat>'
    $env:EF_STUDY_LIVE = 'YES'
    & <gradle-8.9>\bin\gradle.bat -p android `
      :app:cleanTestBenchmarkUnitTest :app:testBenchmarkUnitTest `
      --offline --tests "ai.edgefit.diag.EvalStudyLiveRunnerTest"

One POST, no retry. Writes `study-decision-m0.json` +
`study-response-m0.json` once (refuses if present).
Omit EF_STUDY_LIVE=YES first for a verify-only dry run
(no POST, no writes). STOP on any failure; a failed arm
stays undecided (no synthetic substitute).

## 6. Explicitly authorize and execute M1

Same as step 5 with `EF_STUDY_ARM = 'M1'` in a separate
invocation. Two arms = two explicit authorizations.

## 7. Verify all three decision records

`study-decision-r.json` (committed), `study-decision-m0.json`,
`study-decision-m1.json` present; each parses; selections
are A/B/abstain; M0 cites nothing; M1 cites only
manifested runs; transports are offline/live (never
mock-synthetic). Re-run step 1 (it also re-checks R).

## 8. Confirm every decision predates physical testing

All three `decided_at_utc` stamps must be strictly
earlier than run 1's START. The scorer enforces this
itself; a quick eyeball check now avoids wasted runs.
Do not start run 1 in the same second as a decision.

## 9. PHONE: execute six individually initiated runs

Order A,B,B,A,A,B as UNBOUND manual runs — no Agent
sessions, no proposals, no repeats needed:

1. Confirm no active session is bound.
2. Tap Select Config → Manual A (or B) → Apply.
3. Confirm the Config line shows the exact triple.
4. Tap Run Benchmark (one deliberate press per run).
5. On COMPLETED, save + export the benchmark JSON.
6. Repeat for the next config in the order.

Same charger state; cooldown between runs; record
thermal + RAM% + build identity per run. Each run
persists as a standalone measurement (no prediction
invented). Study runs never consume Agent budgets and
never touch production sessions. STOP on any refusal,
crash, or config-line mismatch.

## API budget (study vs demonstration)

Independent study: EXACTLY 2 provider calls (M0, M1 in
steps 5–6), each one bounded no-tools round trip.
Production Agent demonstration (`ef-comp-01b-agent-demo.md`)
is SEPARATE: its prediction/proposal/follow-up calls are
budgeted per demo session (≤5 experiments) and are NOT
study evidence. Never mix the two call counts.

## 10. Export and hash all six result artifacts

Pull the six JSON exports; record each SHA-256. Missing
or unparseable export = STOP (scoring needs exactly 6).

## 11. Run the offline study scorer

    $env:EF_STUDY_SCORE_DIR = "$pwd\eval\comp-01b"
    $env:EF_STUDY_RUNS = 'C:\path\run1.json,C:\path\run2.json,...'
      # exactly 6 exported files, any order
    & <gradle-8.9>\bin\gradle.bat -p android `
      :app:cleanTestBenchmarkUnitTest :app:testBenchmarkUnitTest `
      --offline --tests "ai.edgefit.diag.EvalStudyRunnerTest"

Writes `study-score.json` once (refuses if present):
config medians/spread, one decision record per arm, one
regret observation per arm, paired errors, exclusions,
exploratory labels. BLOCKS (no score) on any identity,
count, duplicate, timestamp, or decision mismatch.

## 12. Judge-ready comparison

Report from `study-score.json` only: selections per arm,
observed medians, regret per arm, errors, cost/latency,
exclusions, and the exploratory limits (N=3/config,
single device, no causal claims). Cite run ids + hashes.

## Appendix R. ROUTE acceptance (GATE-1A correction:
no pre-decision measurements)

The original appendix ordered physical A/B runs before
the M0/M1 decisions, which would measure config B ahead
of the prospective predictions. Corrected order below.

### R-UI. Pre-decision UI-only acceptance (PHONE)

NO Run Benchmark presses in this section. Any inference
here contaminates the study (config B must stay
unmeasured until the study runs).

1. Install the ROUTE APK (hash in step 2); confirm
   version 0.2.0 + package ai.edgefit.diag.bench.
2. Stage the study GGUF; record display name + size.
   Staging runs no inference (full SHA via the run-as
   check or the first study export).
3. Confirm Sessions shows no bound session.
4. Benchmark tab shows a Config line with the device
   default triple; tap Select Config → 3 options
   (Device default, Manual A, Manual B); screenshot.
5. Apply Manual A → line reads exactly
   `ctx=512 threads=4 predict=64 (manual selection)`;
   screenshot. DO NOT RUN.
6. Apply Manual B → `threads=8`; screenshot. DO NOT RUN.
7. Apply Device default → line reverts to
   `(device default)`.
8. Stage a different file → manual selection cleared;
   then re-stage the study GGUF.
R-UI PASS iff all 8 match with no benchmark initiated;
else STOP and file screenshots.

### R-PHYS. Physical config proof (inside the study)

Study runs 1 (A) and 2 (B) ARE the physical acceptance:
each exported `n_threads` must equal the selected config
or STOP. No separate pre-study runs exist.

### R-PROD. Separate product acceptance (NOT study evidence)

After scoring (or on a non-study day): bound-session
precedence display, AgentReview + Modify + Approve & Run,
repeat, Muse follow-up, per
`ef-comp-01b-agent-demo.md`. Never interleave with study
runs; never add product-run measurements to the study
corpus.

### Deferred defect (low-end UI; NOT an S22 blocker)

`ManualBenchConfig.options()` advertises Manual A as
supported on devices with fewer than 4 logical
processors, while `choose()` correctly refuses it there
(fail-closed; no bad run possible). Smallest correction:
gate the A option on `bMax >= 4` with a needs-4-
processors detail, mirroring B, plus one test asserting
`options(2)` marks A unsupported. Deferred: no Android
edit or APK rebuild for this gate.
