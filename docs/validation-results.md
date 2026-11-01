# Validation results

Measured numbers with their caveats. All physical runs below are from
one Galaxy S22 Ultra (SM-S908U, Android 16), Llama-3.2-1B-Instruct
Q4_K_M (SHA-256 `6f85a640…`), llama.cpp 0.7.0-dev, workload
`edgefit-prompt-v1`. Samples are small: treat every comparison as
**exploratory**, not confirmatory.

## Physical benchmark pairs

**Run A → Run B (2026-10-27, ingested):** unoptimized Debug build
0.383 tok/s → benchmark variant 14.382 tok/s generation (**37.57x**,
recomputed from timings by the host; prefill 37.85x). One pair, not a
population result. Requested `-O3 -g` is kept distinct from the
verified effective `-O2` (confirmed across all 77 compile commands).

**comp-01b study (2026-10-31, 6 runs, frozen manifest
`dd609616…`):** candidate A (ctx=512, threads=4) median **16.52 tok/s**
(range 15.28–16.66, n=3); candidate B (threads=8) median **10.49 tok/s**
(range 6.82–13.38, n=3). At most 3 trials per config — descriptive
summaries only. Raw runs: `eval/comp-01b/runs/` (ledger + 6 bench
JSONs); scores: `eval/comp-01b/study-score.json`.

## Muse prediction accuracy (scoped)

- **EF-META-05 live (synthetic):** predicted 15.0 vs observed 9.2 tok/s
  (overprediction 5.8). The model explicitly identified the evidence as
  synthetic. PASS.
- **EF-META-06 live (physical Run A/B):** retrieved both run ids and
  presented the host-computed 37.57x comparison. PASS with a
  response-completeness caveat (final recommendation visibly
  incomplete; not presentation-ready).
- **BYOK live session `SES-aa255b757ace13cd`:** predicted 12.0 gen /
  45.0 prefill tok/s (low confidence, uncertainty disclosed) →
  measured +6.417 tok/s (**+53.48%**); authorized follow-up recorded
  with single-observation limits. PASS (scoped).
- **Agent experiment 4:** approved proposal (256/4/64, expected
  17.5/27.0) → measured 17.738 gen (**+1.36%**) / 30.997 prefill
  (**+14.8%**); follow-up `supports_conclusion: false`. PASS (scoped,
  n=1 per config, thermal conditions differed).

## Honest abstentions (comp-01b model arms)

Both Muse arms abstained instead of guessing: M0 (no measurements,
3 withheld by design) and M1 (all manifested evidence used threads=4;
zero runs for threads=8). No regret is computed for abstentions —
abstaining was the correct answer. Fabricated-citation rate: 0.0.
Decisions and raw responses: `eval/comp-01b/study-decision-M0/M1.json`,
`study-response-M0/M1.json`.

## Test suites

- **Android JVM:** 634/634 PASS on the contribution branch
  (fake transports; no live backend observed) — see
  [ef-global-contribute-app-handoff.md](ef-global-contribute-app-handoff.md).
- **Cloud foundation:** 38/38 (`npm test --prefix cloud`), isolated
  in-memory databases.
- **Python offline suites:** EF-META-05 14/14, EF-ANDROID-01 25/25,
  EF-DATA-02 29/29, EF-BENCH-01 19/19 + native 3/3, EF-DATA-03 35/35,
  EF-META-06 39/39, EF-LOOP-01 26/26, session parity 1/1, UI
  conformance 14/14 — no network, no credentials.
- **PowerShell:** EF-META-04 12/12; EF-SCIENCE-01A self-test green.
- **Study manifest:** `ef-comp-01a-manifest.test.mjs` green.

## Standing caveats

Single device; 1–3 runs per config; termination paths (stagnation,
invalid-strike, reject) unqualified live; forced process-death and
lifecycle edge cases deferred; cloud backend and explorer undeployed;
no multi-device data. The complete record — including what is NOT
claimed — is [qualification-status.md](qualification-status.md).
