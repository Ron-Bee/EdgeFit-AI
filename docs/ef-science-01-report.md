# EdgeFit AI: On-Device LLM Benchmarking and Evidence-Conditioned Optimization — Technical Report (DRAFT v0.1)

Status: DRAFT. Exploratory observations only. All
confirmation sections are marked PENDING until the
worksheet trials (§12) are executed and analyzed.
No performance claim in this draft exceeds its stated
evidence class. Method: `docs/ef-science-01-protocol.md`.

## 1. Title and abstract

EdgeFit AI measures real on-device large-language-model
inference performance on Android (pinned llama.cpp,
fixed workload, exact-cohort grouping) and conditions
Meta Muse inference on the resulting evidence for
developer-approved optimization experiments. This
report documents the methodology, the four validated
exploratory runs on a Samsung Galaxy S22 Ultra, the
observed within-cohort spread (including one run
recorded at Android thermal status moderate), and
the pre-registered confirmation plan. No confirmatory
finding is reported: every comparison below is
descriptive and observational (n≤3 per cohort), and
no difference is attributed to a single isolated
cause.

## 2. Research questions

R1. What is the run-to-run spread of generation
throughput for one fixed model/device/workload/
runtime/configuration?
R2. Does a candidate configuration measure differently
from baseline beyond within-configuration spread?
R3. How far are carried Meta predictions from repeated
measurements of the same configuration?

## 3. Background and motivation

Mobile developers choosing on-device LLM deployments
lack trustworthy, device-specific throughput data:
vendor figures rarely match a developer's exact model
bytes, runtime build, and configuration. EdgeFit
addresses this by (a) executing a fixed benchmark on
the developer's own phone, (b) persisting
prediction-before-measurement sessions with exact
identity, and (c) offering bounded, developer-approved
follow-up experiments. This report documents and
checks the measurement half of that loop first:
without repeatability, no optimization claim is
meaningful.

## 4. Device and software environment

- Device: Samsung Galaxy S22 Ultra (SM-S908U),
  Android 16 (API 36), 8 logical processors.
- App: EdgeFit AI benchmark variant v0.2.0
  (`ai.edgefit.diag.bench`); one exploratory run used
  the unoptimized debug native build (labeled).
- Runtime: llama.cpp 0.7.0-dev, CPU backend,
  arm64-v8a (`v0.7.0 @ 620d2225`, CPU only).
- Model: Llama-3.2-1B-Instruct-Q4_K_M.gguf,
  SHA-256 `6F85A640…5611DF83`, 807,694,464 bytes.
- Workload: `edgefit-prompt-v1`, prompt SHA-256
  `36104DB0…C44382` (118 chars), greedy, seed 42.
- Configuration (all runs): n_ctx=512, n_threads=4,
  n_predict=64. No configuration variation exists
  in the current evidence.

## 5. Benchmark methodology

Fixed prompt, fixed token budget (64 generated),
monotonic-clock phase timings (load/prefill/
generation), throughput recomputed as tokens×1000/ms
and cross-checked (±0.01) by the contract validator.
Process PSS and Android thermal state are recorded
with method labels, never as exact native peaks.
Memory/thermal preflight guards refuse unsafe starts;
refusals are recorded, never retried silently, never
counted as measurements. Contract:
`docs/bench-result-contract.md` v1; validator:
`scripts/ef_bench_result_01.py` (mirrored by the
analysis tool §8).

## 6. Experimental design

Exploratory phase (COMPLETE, this draft): four
contract-valid runs, uncontrolled order, two native
build identities. Pre-registered confirmation phase
(PENDING): ≥5 COMPLETED trials each of baseline
(512/4/64) and ONE selected candidate, counterbalanced
order, charging/thermal controls, cooling rests; see
`docs/ef-science-01-worksheet.md`. Exploratory runs
never count as confirmation trials (§7 protocol:
selection-bias rule). Hypotheses H1–H3 and endpoints
are pre-registered in the protocol; H3 tolerance ±50%.

## 7. Data collection and provenance

Four artifacts, exported on-device and hashed on the
workstation (read-only; originals never modified):

| File | Run ID | SHA-256 (prefix) |
|---|---|---|
| edgefit-bench-20261026-233319.json | …93ce3477 | 4C02B3CF… |
| edgefit-bench-20261027-120145.json | …36227bc0 | D720CF20… |
| edgefit-bench-20261027-153531.json | …4197e471 | E1058F76… |
| edgefit-bench-20261027-171127.json | …6555133c | 52A1FE26… |

Full hashes: evidence appendix §14. Device profiles
(3) corroborate the handset; run-start profile
snapshots (`profile_ref`) are commitments, not
filename joins. Exclusions: none (all four COMPLETED).
No predictions on the workstation predate these runs;
prediction-error analysis is PENDING (phone sessions
hold live predictions but are out of host reach).

## 8. Statistical methods

Tool: `scripts/Invoke-EFScience01A.ps1` (v1a-1,
46/46 self-tests). Exact-cohort key: model bytes,
workload, runtime, full config, app version, native
variant+flags. Per cohort: n, mean, median, min, max,
sample SD (n−1, null when n<2), CV (null unless
mean>0), descriptive interval [min,max] — explicitly
NOT a confidence interval. No p-values, no CIs at
n≤3. Cross-cohort deltas only within identical model
bytes + workload, with the differing key fields
listed and an `isolated_single_field` flag (one
differing field still does not prove cause).
Prediction errors (when supplied): signed/absolute/
percentage vs the prediction, strictly-predating
timestamps only.

## 9. Results

### 9.1 Cohorts (exploratory, descriptive only)

Debug cohort (n=1, unoptimized native build):
0.3828 gen tok/s; 0.5957 prefill tok/s. No spread
estimable.

Benchmark-variant cohort (n=3, `-O3 -g` requested /
effective -O2, identical key otherwise):
gen median 14.3820, mean 12.7913, min 7.4074,
max 16.5846, SD 4.7909, CV 0.3745;
prefill median 22.5490, mean 22.2126, min 17.4375,
max 26.6512, SD 4.6161.

### 9.2 Observed differences (not findings)

Debug → benchmark-variant medians: +13.9992 tok/s
(+3656.97%, ≈37.6×), recomputed from the original
artifacts (§14). The historical A/B pair ratio
(37.57×) is arithmetically consistent with these
medians; it remains ONE pair, not an established
speedup, and not confirmation of any optimization.

This comparison is OBSERVATIONAL and MULTI-FACTOR.
Every known differing condition between Run A
(`…93ce3477`) and Run B (`…36227bc0`), from a
field-level diff of the two artifacts:

- Native build identity: A has no `x_native_build`
  (debug build); B declares variant `benchmark`,
  requested flags `-O3 -g` (compile-command
  inspection established effective `-O2`).
- Collection time: 04:27 UTC vs 17:00 UTC (~12.5 h
  apart; prior device activity uncontrolled).
- Terminal thermal status: `moderate` (A) vs `none`
  (B); before/mid identical (`none`/`none`).
- Observed process memory: PSS before 98,096 vs
  74,984 KB; PSS after 172,592 vs 155,477 KB;
  allocator delta 2,347,328 vs 2,452,976 bytes.
- Run-start profile snapshots differ (expected:
  every run commits its own `profile_ref`).
- Unrecorded and uncontrolled: charging/battery
  state, ambient temperature, background OS activity,
  exact available RAM at start.

Held constant: model bytes, workload, config
(512/4/64, greedy, seed 42), runtime version/ABI,
app 0.2.0, device. Differing cohort-key fields:
`native_variant`, `native_opt` (two fields →
`isolated_single_field=false`). This observation is
NOT attributed to Muse optimization (Muse proposed
nothing here), to compiler flags alone, to context
settings (identical), or to any proven causal
intervention. It is preserved as useful historical
evidence with the limitations above.

### 9.3 Thermal caveat (observed association, cause unestablished)

Run …6555133c measured 7.4074 tok/s with Android
thermal status moderate/moderate/moderate
(collected 22:10:15Z), vs 14.3820 and 16.5846 tok/s
at thermal none (17:00:02Z, 20:34:46Z). Same exact
cohort; max/min ratio 2.2389 (≈2.24×), recomputed
from the original artifacts.

Thermal status is a POSSIBLE explanatory factor for
this spread — not an established cause. The
artifacts record only the Android thermal-status
label (`before`/`mid`/`after` + `available` flag).
They do NOT record battery temperature, CPU core
frequencies, or any direct throttling measurement,
so CPU frequency reduction must NOT be inferred:
"thermal status moderate" (an OS-reported condition
band), "battery temperature" (a sensor reading, in
°C, absent here), and "measured CPU throttling"
(a frequency/power observation, absent here) are
three distinct things. Other uncontrolled factors
(time of day, prior load, memory pressure) could
also contribute. Controlled confirmation trials
(worksheet) are required before any causal
conclusion; until then the protocol treats elevated
thermal status as a caveat flag, not a diagnosis.

### 9.4 PENDING: confirmation trials

Empty until worksheet §12 executes. Will report
baseline/candidate medians, H1–H3 verdicts, and the
quorum checklist.

### 9.5 PENDING: prediction error

Empty until predictions strictly predating new runs
are supplied. Historical phone predictions are cited
in the case study from the qualification record,
not re-derived here.

## 10. Interpretation

What the evidence supports TODAY:
(a) the measurement pipeline produces contract-valid,
recomputable runs; (b) throughput differs by an order
of magnitude across the two observed native build
identities (one pair + one cohort; multi-factor,
cause unisolated); (c) throughput spans ~2.24× within
one cohort (n=3), associated with — not proven caused
by — elevated thermal status in the slowest run;
(d) exact-cohort grouping + exclusion discipline work
as designed. What it does NOT support: any "faster
configuration" claim (no configuration varies yet),
any cross-device claim (n_devices=1), any causal
mechanism, any statistical significance. The
developer-value case study
(`docs/ef-science-01-casestudy.md`) walks one
exploratory loop end to end with the same honesty
labels.

On Muse's role: EdgeFit does NOT train or fine-tune
Muse (ADR-014). It conditions a bounded inference
call on supplied evidence (device profile, run rows,
host deltas); the model proposes, the host validates,
the developer approves, the phone measures.

## 11. Threats to validity

- Construct: throughput conflates runtime, OS
  scheduling, thermals; single metric, single task.
- Internal: exploratory order uncontrolled; no
  blinding; 64-token burst unrepresentative of long
  sessions; instrumentation overhead unmeasured
  (NFR-PERF-01 pending).
- External: one device, one model, one OS build.
- Selection: exploratory winners must not confirm
  themselves (§7 protocol rule).
- Measurement: PSS ≠ native peak; listed catalog
  sizes are bounds; effective -O2 vs requested -O3
  shows self-declared flags need inspection.

## 12. Reproducibility instructions

1. Export run JSONs on-device (Benchmark → Export).
2. Hash them (`Get-FileHash -Algorithm SHA256`).
3. Run `Invoke-EFScience01A.ps1 -ArtifactPath <dir>
   -OutPath <report.json> [-ManifestPath m.tsv]`.
4. Compare report hashes + values against appendix
   §14. Tool self-test: `Test-EFScience01A.ps1`
   (46/46 at v1a-1). Rerunning on the four
   appendix artifacts MUST reproduce §9 bit-for-bit
   except `created_utc`.

## 13. Conclusions

The loop is measurable, the grouping is strict, and
the confounds are visible — which is exactly what an
exploratory phase should establish. No optimization
is claimed. The next scientific step is executing
the confirmation worksheet, not analyzing harder.

## 14. References and evidence appendix

- Protocol: `docs/ef-science-01-protocol.md` (§1–§9).
- Contract: `docs/bench-result-contract.md` v1.
- Tool: `scripts/Invoke-EFScience01A.ps1` v1a-1;
  self-test 46/46 PASS (PowerShell 5.1).
- Case study: `docs/ef-science-01-casestudy.md`.
- Worksheet: `docs/ef-science-01-worksheet.md`.
- Full artifact hashes:
  - 233319: `4C02B3CFC9B4DF1B59D098CF35101AB88128F20CF1A417B5B8C8119289A09105`
  - 120145: `D720CF20856A4E2A41C4CEC5B607FB5FD3C6638FA7C60BF4E74B45CB592609E5`
  - 153531: `E1058F76E137BA6699523B4F587D108DE2AF95A20317448B402CFF8FF8A7815D`
  - 171127: `52A1FE26F882E4FBFEAF0DAF1928BE4C6F8CC51BB01285A11260A78EAC9441BB`
- Cohort-2 run IDs: `BENCH-20261027T170002Z-36227bc0`
  (14.3820), `BENCH-20261027T203446Z-4197e471`
  (16.5846), `BENCH-20261027T221015Z-6555133c`
  (7.4074, thermal moderate).
