# Developer-Value Case Study: One Exploratory Loop (EF-SCIENCE-01A)

Every number below recomputes from the appendix
artifacts via `scripts/Invoke-EFScience01A.ps1`.
Nothing here is confirmatory; labels say so.

## Vignette 1 — host artifacts: the slow build and the hot repeat

**Initial configuration.** 512 ctx / 4 threads /
64 tokens, greedy, seed 42, Llama-3.2-1B Q4_K_M,
unoptimized debug native build, S22 Ultra.

**Prediction and uncertainty.** NOT AVAILABLE on the
workstation: no prediction artifact predates Run A,
so no prediction error can be computed for this
vignette. (For a prediction-bearing loop, see
Vignette 2, sourced from the qualification record.)

**Actual benchmark.** Run `BENCH-20261027T042748Z-
93ce3477`: 0.3828 gen tok/s over 167,185 ms;
thermal none/none/moderate. File hash `4C02B3CF…`.

**Host-computed prediction error.** None computable
(no prediction). The tool reports no
`prediction_errors` entries — correctly empty,
not zero.

**Proposed next experiment (owner action, no
proposal artifact).** Rebuild the native stack
optimized (requested `-O3 -g`; compile commands
later inspected as effective `-O2` — a documented
requested-vs-effective gap, never mislabeled).
This was a developer build decision, NOT a
Muse-approved Agent proposal: no proposal record
exists on the host for this step, and none is
claimed.

**Developer approval.** Owner-authorized rebuild +
retest on the same phone, same model bytes, same
workload and config.

**New measured observation.** Run `…36227bc0`:
14.3820 gen tok/s (thermal none). Observed pair
ratio vs Run A: 37.57×. Observed — one pair, two
native build identities, uncontrolled order, and
further differing conditions (collection time,
terminal thermal status, observed memory; full
list in report §9.2). Multi-factor; no isolated
cause is claimed, and this is not confirmation of
an optimization.

**Repeated confirmation trials.** Two further
same-cohort runs: `…4197e471` at 16.5846 tok/s
(thermal none) and `…6555133c` at 7.4074 tok/s
(thermal moderate×3). The third run exposed a
2.24× within-cohort spread associated with the
elevated thermal-status reading — association,
not proven causation (no battery-temperature or
frequency data exists in the artifacts). A spread
this large could swamp many configuration effects
the study might hunt, which is why the protocol
controls thermals first. These are EXPLORATORY
repeats, not confirmation trials.

**Final decision and limitations.** Decision: do
not conclude; control thermals first (protocol §4,
worksheet). Limitations: n=1 debug cohort, n=3
benchmark cohort, no predictions, one device, no
configuration variation, instrumentation overhead
unmeasured, no causal attribution available.

## Vignette 2 — full loop with prediction (qualification record)

Sourced from the EF-AGENT-01-PHYS-QUAL entry in
`docs/qualification-status.md` (values as recorded
there, not re-derived on this host):

- Agent parent `SES-a77eaa6f4cd92641`, experiment 4
  child `SES-63a4ace2c6d85778`, run
  `BENCH-20261028T224503Z-38ccf1f5`.
- Approved + executed 256/4/64; live Meta prediction
  17.5 gen tok/s recorded BEFORE the run.
- Measured 17.7384 gen tok/s → +1.36% error
  (underestimate), prefill +14.8%.
- User-authorized follow-up recorded; Muse judged
  one paired observation insufficient for
  generalization (supports_conclusion false);
  parent budget 4/5 after four attempts.

This is the loop working as designed — including
its restraint: one observation, no trend claimed.

## What "developer value" is demonstrated

1. A real bottleneck surfaced numerically (0.38
   tok/s) instead of as a vague "slow" feeling.
2. A build change was followed by re-measurement
   (14.38), with identity tracking that prevents
   pooling incomparable builds — an observed
   sequence, not a validated improvement.
3. A thermal-status association was caught by repeat
   runs before an uncontrolled spread could corrupt
   a conclusion.
4. The full prediction→approval→measure→follow-up
   path is evidenced on-device with restraint
   intact.

No autonomy is claimed: every step above had a
human decision in it, and the report says which.
