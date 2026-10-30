# EF-SCIENCE-01 experiment protocol (PROPOSED)

Status: PROPOSED / NOT STARTED. This document specifies HOW a
future controlled study would run. It reports NO results and
makes NO performance claims. Any future study report must cite
run IDs, artifact hashes, and raw observations — never bare
ratios.

## 1. Research questions and hypotheses

R1. Within-configuration variability: what is the
    run-to-run spread of generation throughput for one
    fixed model, device, workload, runtime, and
    configuration?
R2. Cross-configuration difference: does a candidate
    configuration measure differently from baseline on
    the same device, beyond within-configuration spread?
R3. Prediction error: how far are carried Meta
    predictions from repeated measurements of the same
    configuration?

H1 (variability): repeated exact-cohort runs show a
non-trivial spread (CV reported; no threshold claimed
in advance — the study ESTIMATES it).
H2 (configuration): the candidate configuration's
median generation throughput differs from baseline by
more than the baseline's observed range. This is a
comparison of DESCRIPTIVE summaries, not a
significance test, until the confirmation quorum
(§8) is met.
H3 (prediction): carried predictions fall within ±50%
of measured medians for tested configurations
(pre-registered tolerance; the study reports pass/fail
per configuration without moving the bar).

Primary endpoint: median generation throughput
difference (absolute tok/s and percentage) between
the baseline and candidate exact cohorts.
Secondary endpoints: prefill throughput medians,
within-cohort CV, prediction absolute/pct error,
thermal-caveat rate, refusal/failure rate.

Non-goals: causal attribution of any difference to one
subsystem, cross-device generalization, and any claim of
statistical significance without the quorum below.

## 2. Fixed identities (recorded per run, never assumed)

- Device model + Android API (exported profile SHA-256).
- Model artifact SHA-256 + size + display label.
- Runtime version + ABI + native build variant and
  EFFECTIVE (inspected, not self-declared) optimization.
- Workload ID + prompt SHA-256 (`edgefit-prompt-v1`
  unless a new versioned workload is qualified).
- Exact config: n_ctx, n_threads, n_predict, sampler,
  seed, timeout.
- Run ID + source SHA-256 + collected/attached UTC.

A run missing any identity field is EXCLUDED from all
aggregates and listed as excluded with its reason.

## 3. Study design

- Minimum 5 successfully COMPLETED repetitions per
  configuration (initial target; more is better).
- Baseline + candidate configurations measured in
  counterbalanced (alternating) order, never all of one
  configuration first.
- Failed/refused runs recorded separately with cause;
  they NEVER enter throughput aggregates and NEVER count
  toward the 5-run target.
- Thermal state (before/during/after), charging state,
  and available-RAM observations recorded per run as
  caveats, not covariates for adjustment.
- No settings, model file, or app-build changes
  mid-series. Any change starts a NEW series.

## 4. Controls

- Same physical device, same OS build, same app build.
- Device rested to thermal `none` before each run where
  achievable; runs starting above `none` are flagged.
  Terminology (CORRECTION-01): "thermal status" is the
  OS-reported condition band recorded in artifacts;
  "battery temperature" (°C) and "measured CPU
  throttling" (frequency/power) are separate,
  currently unrecorded quantities. Never infer one
  from another; elevated status is a caveat flag,
  not a diagnosis.
- Consistent charging state within a series (all
  charging or all discharging); mixed series are split.
- No foreground/background workload changes mid-series.
- Failed preflight (memory/thermal) ends the attempt;
  retry only after conditions recover, as a new run ID.

## 5. Analysis (host-computed, deterministic)

Per configuration (COMPLETED runs only):

- n, min, max, mean, median generation tok/s.
- Range and max/min ratio as the variability summary.
- No p-values, no confidence intervals, no "proven
  faster" language at n=5: report the observed
  difference between configuration medians/means WITH
  both within-configuration ranges shown alongside.

Cross-configuration comparison is DESCRIPTIVE unless a
pre-registered quorum (device count, run count,
independent replication) is met and documented in the
future EF-SCIENCE-01 report.

Prediction error per run: signed and percentage error
vs the run's own (possibly carried) prediction, using
`SessionComparison` semantics.

## 6. Reproduction package

For every reported number, the report must list:

- Every run ID + source SHA-256 + session ID.
- Series membership (which runs pooled, which excluded
  and why).
- Exact CLI/export steps used to extract values.
- App version, native variant, and effective flags.
- Ambient conditions log (thermal/charging per run).

## 7. Exploratory vs confirmatory discipline

Exploration (anything goes, everything recorded):
trying configurations, following Agent proposals, and
inspecting early spreads. Exploratory runs MUST be
labeled exploratory in the worksheet and report.

Confirmation (pre-registered, then executed): once a
candidate is SELECTED (for any reason, including
"it looked best"), its evaluation uses NEW trials run
after selection under §3–§4 controls. The runs that
motivated the selection NEVER count as confirmation
trials. This blocks the selection-bias loop where the
winner of exploration is "confirmed" by its own
qualifying runs.

Repeated measurements on one phone are repeated
measures, NOT independent-device evidence. Device
count stays 1 until a second physical device with its
own exported profile contributes validated runs.

## 8. Confirmation quorum (pre-registered)

A cross-configuration difference graduates from
"observed" to "established" only when ALL hold:

- ≥5 COMPLETED confirmation trials per configuration.
- Counterbalanced order actually executed (worksheet).
- Median difference exceeds EITHER cohort's observed
  range (a pre-registered, distribution-free bar).
- No undisclosed exclusions (every refusal/failure
  listed with cause).
- Analysis re-runnable from exported artifacts via
  `scripts/Invoke-EFScience01A.ps1` (report appendix
  pins tool version + artifact hashes).

Until then, all language stays at "observed",
"measured", "suggests further trials" — never
"proven", "significant", or "faster" as a finding.

## 9. Relation to EF-FOLLOWUP-02

The repeat workflow (Repeat actions, series comparison,
bounded follow-up priors) is the MECHANISM for
collecting such series on-device. Using it does not by
itself constitute a controlled study: order, controls,
and exclusion discipline above are the owner's
procedure at run time. A casual pair of repeats is
"two observations", never "the variability result".
