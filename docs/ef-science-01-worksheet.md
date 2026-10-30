# EF-SCIENCE-01 Confirmation Worksheet (phone operator copy)

Goal: 5 COMPLETED trials each of BASELINE and
CANDIDATE, counterbalanced, controlled. The owner
decides when/whether to run. Stop anytime; partial
series are still evidence if fully labeled.

## Fixed identities (do not change mid-series)

- Phone: Galaxy S22 Ultra, same OS build, same app
  build (benchmark variant `ai.edgefit.diag.bench`
  v0.2.0 — record the exact APK if you reinstall).
- Model file: the SAME staged GGUF all 10 trials
  (Llama-3.2-1B Q4_K_M, 807,694,464 bytes; record
  its SHA-256 from staging).
- Workload: edgefit-prompt-v1 (unchanged).
- BASELINE (B): 512 ctx / 4 threads / 64 tokens.
- CANDIDATE (C): 256 ctx / 4 threads / 64 tokens
  (valid config; one live run exists but does NOT
  count — confirmation trials start fresh).

## Conditions (pick once, hold for all 10)

- Charging: all charging OR all discharging.
- Start each trial at thermal `none` (check Device
  Details). Rest 5+ min between trials; longer if
  the previous run ended above `none`.
- Same room/ambient; no heavy apps in background.

## Trial order (counterbalanced; do not reorder)

| # | Config | Run ID (fill after export) | Thermal b/m/a | Notes |
|---|---|---|---|---|
| 1 | B | | / / | |
| 2 | C | | / / | |
| 3 | C | | / / | |
| 4 | B | | / / | |
| 5 | C | | / / | |
| 6 | B | | / / | |
| 7 | B | | / / | |
| 8 | C | | / / | |
| 9 | B | | / / | |
| 10 | C | | / / | |

## Per trial

1. Verify thermal `none` + chosen charging state.
2. Select the config for this trial number.
3. Run once. Record COMPLETED/FAILED/refused + error
   text verbatim.
4. Export the JSON immediately; note its filename.
5. Rest before the next trial.

## Interruptions and refusals (record, never hide)

- Memory/thermal refusal: log trial #, cause text,
  avail-RAM reading; retry the SAME trial number
  later as a NEW run (new run ID).
- App crash / interruption: log it; the trial is
  void unless a COMPLETED export exists.
- Failed runs do NOT count toward the 5; keep their
  exports anyway (exclusion list).

## Existing evidence: NONE reusable

The four prior runs are exploratory (uncontrolled
order, mixed builds, one recorded at thermal
status moderate) and stay exploratory. Do not
renumber them into this series.

## Hand-off checklist (no observations lost)

- [ ] 10 worksheet rows filled (or voided with cause).
- [ ] 10 exported JSONs (5 B + 5 C expected COMPLETED).
- [ ] Each JSON opens + shows its config.
- [ ] `Get-FileHash` recorded per file (SHA-256).
- [ ] Refusals/failures listed with verbatim causes.
- [ ] Charging state + rest notes attached.

Analysis: `Invoke-EFScience01A.ps1 -ArtifactPath
<dir> -OutPath <report.json> -ManifestPath <m.tsv>`.
Confirmation quorum: protocol §8 (median gap must
exceed either cohort's observed range). These 10
trials are repeated measures on ONE phone, not
independent-device evidence; medians differing
alone claims nothing causal or significant.
