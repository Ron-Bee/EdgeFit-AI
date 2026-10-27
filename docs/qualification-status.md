# EdgeFit qualification status

## Owner-reported baseline (established before EF-META-05; not executed by agent)

- EF-META-01: PASS.
- EF-META-02: Initial PASS.
- EF-META-03: Initial PASS.
- EF-META-04: 12/12 local validator PASS; production dispatch remains unqualified.
- EF-DATA-01: SQLite persistence PASS.
- EF-META-05: Pending qualification until actual test evidence exists.

## EF-META-05 session evidence (executed by agent, 2026-10-26)

- EF-DATA-01 re-run: PASS (`python scripts/ef_data_01.py`, exit 0).
- EF-META-04 re-run: 12/12 PASS (`.\scripts\Test-EF-META-04.ps1`), Meta API calls NONE.
- EF-META-05 local suite: 14/14 PASS (`python scripts/test_ef_meta_05.py`, exit 0) covering valid retrieval, invalid-argument matrix (27 subcases), empty results, missing database, provenance integrity, deterministic ordering, read-only behavior, real-DB fixture evidence, and offline Responses-payload regression (supported tool_choice values, no key or network).
- EF-META-05 live Meta round trip: NOT RUN by agent — no MODEL_API_KEY in agent environment.

## EF-META-05 live qualification (executed by owner on owner workstation; not executed by agent)

- Command: `py -3 scripts/ef_meta_05_live.py` — result `EF-META-05 LIVE: PASS - one validated round trip with SQLite evidence.`, exit code 0.
- One validated tool-call round trip through Muse Spark 1.3 retrieved SQLite evidence for SYNTH-BENCH-001: predicted 15.0 tokens/second, synthetic observation 9.2 tokens/second, overprediction 5.8 tokens/second.
- The model analysis explicitly identified the evidence as synthetic and not physically verified.
- EF-META-05: PASS (live-qualified). No physical benchmark or accuracy improvement claimed.

## EF-ANDROID-01 (qualified; device validation owner-confirmed)

- Contract v1 (`docs/android-profile-contract.md`) plus EdgeFitDiag collector
  app (`android/`) implemented. Export via Storage Access Framework to a
  user-chosen destination. No auto-import; EF-META-05 approved device-class
  constraints unchanged.
- Agent-executed: Kotlin main sources compile clean (kotlinc 2.1.10 against
  android-35); JVM serializer tests 4/4 OK (JUnit 4.13.2);
  `scripts/test_ef_android_01.py` 25/25 OK; EF-DATA-01 PASS; EF-META-05
  14/14 OK; EF-META-04 12/12 PASS.
- No benchmarks executed; no Honey LLM or BeeBench code used.

## EF-ANDROID-01 physical-device evidence (executed by owner; not executed by agent)

- Android Studio Gradle sync: PASS. APK build, install, launch on Galaxy
  S22 Ultra (Android 16): PASS. UI export-button hotfix: confirmed functional.
- Export through system file picker, ADB authorization and transfer: PASS.
- Profile `edgefit-profile-20261026-210006.json` (714 bytes, stored outside
  this repository): `VALID: contract v1 profile`.
- EF-ANDROID-01: PASS (device-qualified).

Do not claim a physical benchmark or a Meta-model accuracy gain until measured.
