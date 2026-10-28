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

## EF-DATA-02 (qualified; owner import confirmed)

- Agent-executed: importer + additive migration implemented;
  `scripts/test_ef_data_02.py` 29/29 OK (isolated temp databases;
  CLI commit mode never invoked); EF-DATA-01 PASS; EF-META-05 14/14
  OK; EF-ANDROID-01 25/25 OK; EF-META-04 12/12 PASS.
- Agent-executed read-only verification of the owner-imported
  database: SQLite integrity ok; exactly one physical profile row;
  stored SHA-256 matches the owner-reported hash; stored JSON bytes
  re-hash identically; synthetic fixtures intact; EF-META-05
  retrieval unchanged. No database writes performed; the evidence
  file was never read or copied by the agent.
- EF-META-05 tool allowlist unchanged.

## EF-DATA-02 owner import (executed by owner; not executed by agent)

- `COMMIT: imported PHYS-9C27FBBB2AB74BD7`, SHA-256
  `9c27fbbb2ab74bd73822f552feebfc5c473c7cfc9a4da59b26e0eb6e73db2d42`,
  backup `data/edgefit.db.bak-20261027T022612Z`.
- Post-import preview: `migration_needed: False`,
  `identical_record_exists: True`. Original evidence SHA-256 unchanged.
- Independent verification returned exit code 0 with `EF-DATA-02 verified.`
- EF-DATA-02: PASS (ingestion-qualified). Platform-reported device data
  only; not a physical inference-performance measurement.

## EF-BENCH-01 (implementation qualified; device/APK NOT RUN)

- Agent-executed: JNI bridge compiles clean against real llama.cpp
  v0.7.0 headers (NDK 28 clang, arm64); Kotlin main sources compile;
  JVM suites 18/18 OK (config, guards, serialization, state machine,
  calculations, profile export preserved); `test_ef_bench_01.py`
  18/18 OK; EF-DATA-01 PASS; EF-META-05 14/14 OK; EF-ANDROID-01 25/25
  OK; EF-DATA-02 29/29 OK; EF-META-04 12/12 PASS.
- NOT RUN: Gradle APK build, install, and any on-device benchmark.
  No model downloaded by the agent. EF-META-05 allowlists unchanged.

Do not claim a physical benchmark or a Meta-model accuracy gain until measured.

## EF-DATA-03 (implementation qualified; database NOT mutated)

- Agent-executed: additive `physical_benchmark_results` +
  `bench_context_links` schema (migration 3) plus CLI importer
  (`scripts/ef_data_03_import.py`), read-only retrieval
  (`scripts/ef_bench_retrieve_03.py`), and offline A/B comparison
  (`scripts/ef_bench_compare_03.py`) implemented.
- Agent-executed: `scripts/test_ef_data_03.py` 35/35 OK (isolated
  temp databases; CLI commit mode never invoked against the real
  database). One test-found defect fixed: the v3 CLI parser accepted
  flags only before the positional path, unlike EF-DATA-02; parsing
  is now order-independent.
- Agent-executed regressions: EF-DATA-02 29/29 OK; EF-META-05 14/14
  OK; EF-ANDROID-01 25/25 OK; EF-BENCH-01 19/19 OK; bench native 3/3
  OK; EF-META-04 12/12 PASS; EF-DATA-01 PASS. No Meta API calls.
- Agent-executed evidence integrity: all 5 evidence SHA-256 values
  match owner-supplied hashes (Run A bench 4C02B3CF...,
  Run B bench D720CF20..., profiles 9C27FBBB.../A375D8A2.../838B9FD2...).
  New Run B files independently re-validated offline: `VALID:
  contract v1 result`, `VALID: contract v1 profile`.
- Agent-executed dry-run previews (read-only, exit 0): Run A
  `BENCH-20261027T042748Z-93ce3477` with 2 proposed
  `same_device_context` links (deltas +8862s / -334s); Run B
  `BENCH-20261027T170002Z-36227bc0` with 1 proposed link
  (delta -108s). Both report `migration_needed: True`,
  `identical_record_exists: False`.
- Agent-executed offline comparison (exit 0): matched device model,
  model SHA-256, workload, token counts, context, threads, sampler,
  backend; generation 0.382809 -> 14.382022 tok/s (37.57x recomputed
  from timings); prefill 37.85x; thermal difference reported as
  caveat; requested `-O3 -g` kept distinct from verified effective
  `-O2`. One physical pair, not a population result.
- Database UNCHANGED: `data/edgefit.db` SHA-256 identical before and
  after all previews and comparisons
  (`986A70E2...C4EE7`); no new files in `data/`. EF-META-05
  synthetic behavior and tool allowlist unchanged.
- Pending owner gates: dry-run review; attestation wording for
  effective `-O2`; explicit two-flag commit authorization per run;
  post-commit read-only verification. EF-DATA-03: LOCAL
  IMPLEMENTATION PASS, ingestion NOT YET QUALIFIED.

## EF-DATA-03b (qualified; owner import confirmed)

- Owner-executed: both physical benchmark runs committed with
  EF-DATA-03 gates; post-import verification reported migrations
  [2, 3], 2 physical rows, 3 contextual links, synthetic records
  intact. Database SHA-256 `B36977AD...190FE9D`.
- Agent-executed acceptance (pre-import): importer/migration/
  retrieval source review with no blocking defects; evidence hashes
  and contracts re-verified; journal mode `delete`; pre-import backup
  `data/edgefit.db.pre-import-03b-20261027T174115Z` created via the
  SQLite backup API and content-matched to live; exact two-record
  rehearsal on an isolated copy (values, flags, states, links,
  idempotent re-import, conflict rejection, retrieval, no path
  disclosure) all green. Effective `-O2` independently confirmed in
  all 77 compile commands. Live database never written by the agent.
- EF-DATA-03b: PASS (ingestion-qualified). Both rows `ingested`.

## EF-META-06 (offline qualified; live NOT RUN)

- Agent-executed: separate `retrieve_physical_benchmarks` dispatcher
  (`scripts/ef_dispatch_06.py`) with narrow model-visible projection,
  host-pinned COMPLETED filter, and deterministic host comparison;
  bounded live module (`scripts/ef_meta_06_live.py`, max one tool
  call, two requests max) implemented. EF-META-05 synthetic files
  untouched (git-verified); no tool merging.
- Agent-executed: `scripts/test_ef_meta_06.py` 39/39 OK (mocked
  transport only; all fake API payloads labeled synthetic test
  artifacts) covering dispatch matrix, injection rejection,
  source separation, empty/missing/corrupt/changed DB, state
  labeling without promotion, output caps, prompt-injection
  isolation, secret non-disclosure, recomputed 37.57x speedup with
  banned-claim scan, and offline live-path failures.
- Agent-executed regressions: EF-META-05 14/14 OK; EF-DATA-03 35/35
  OK; EF-DATA-02 29/29 OK; EF-ANDROID-01 25/25 OK; EF-BENCH-01 19/19
  OK; bench native 3/3 OK; EF-META-04 PASS (exit 0); EF-DATA-01 PASS.
  No Meta API calls.
- Database UNCHANGED: `data/edgefit.db` SHA-256 `B36977AD...190FE9D`
  before, during, and after all offline work; both rows remain
  `ingested`. No writes, no promotion, no cloud.
- Pending: explicit owner authorization for the bounded live
  demonstration (`py -3 scripts/ef_meta_06_live.py` with
  MODEL_API_KEY). EF-META-06: OFFLINE PASS, LIVE AUTHORIZATION
  REQUIRED.

## EF-META-06 live qualification (executed by owner; not executed by agent)

- Command: `py -3 scripts/ef_meta_06_live.py` — result
  `EF-META-06 LIVE: PASS - one validated round trip with physical
  evidence.`, exit code 0.
- The live request retrieved both physical benchmark run IDs and
  presented a host-calculated comparison including 37.57x
  generation throughput improvement.
- Response completeness: UNQUALIFIED — the model's analysis was
  visibly incomplete at its final recommendation section. The
  narrative is not described as presentation-ready.
- EF-META-06: PASS (live-qualified) with the completeness caveat
  above. No global eligibility claimed.

## EF-LOOP-01 (offline integrated PASS; historical replay PASS)

- Agent-executed: integrated orchestrator (`scripts/ef_loop_01.py`)
  implemented over qualified components: mocked-or-live Meta
  prediction persisted to a local run manifest before benchmarking,
  identity/integrity-checked benchmark association, deterministic
  error computation, and a bounded grounded follow-up. SQLite is
  read-only in the loop; manifests live under gitignored
  `data/loop_runs/`. Statistics API designed only
  (`docs/ef-stats-01-contract.md`, PROPOSED, not deployed).
- Agent-executed: `scripts/test_ef_loop_01.py` 26/26 OK (mocked
  Meta, hermetic temp manifests). The historical-replay demo
  exposed and fixed one real bug (followup assumed init-shaped
  evidence packs); regression test added.
- Agent-executed historical replay (honestly labeled, mocked
  follow-up): real S22 Ultra profile + real Run B benchmark with a
  supplied 12.0 tps estimate completed `REPLAY-DEMO-01` end to end:
  errors +2.382 tps (+19.85%), recommendation `adopt_config`.
- Agent-executed regressions: EF-META-06 39/39 OK; EF-META-05 14/14
  OK; EF-DATA-03 35/35 OK; EF-DATA-02 29/29 OK; EF-ANDROID-01 25/25
  OK; EF-BENCH-01 19/19 OK; bench native 3/3 OK; EF-META-04 PASS
  (exit 0); EF-DATA-01 PASS. No Meta API calls, no DB writes
  (SHA-256 `B36977AD...190FE9D` unchanged).
- NOT RUN: prospective physical end-to-end (requires phone
  benchmark + two authorized live API calls). EF-DEMO-01 deferred
  per owner directive; not begun.

## Physical benchmark runs Run A/B (executed by owner; not executed by agent)

- Correction note: the EF-BENCH-01 section above records that
  milestone's session only. Afterwards the owner built, installed,
  and ran the benchmark on the Galaxy S22 Ultra: Run A
  (`BENCH-20261027T042748Z-93ce3477`, unoptimized Debug,
  0.382809 tok/s) and Run B (`BENCH-20261027T170002Z-36227bc0`,
  benchmark variant, 14.382022 tok/s) both COMPLETED with VALID
  contract v1 JSON, ingested under EF-DATA-03b. EF-BENCH-01/02:
  PHYSICALLY QUALIFIED (implementation + device runs).

## EF-ANDROID-UI-01 acceptance corrections (agent-executed, uncommitted)

- Prompt verified byte-identical to recorded SHA-256 `36104db0…4382`
  (Kotlin literal, DB row, evidence hash all agree; 118 chars).
- Added read-only workload view (pre/post-run), EdgeFit AI naming,
  dark-screen chrome fixes, and section headers; JVM 36/36 OK
  (5 new workload tests), Python UI conformance 14/14 OK,
  benchmark APK rebuilt offline. No contract/methodology change.
- Physical acceptance of the corrections is NOT RUN (owner protocol
  issued with this milestone).

## EF-ANDROID-UI-01 (implementation HOST QUALIFIED; physical partial)

- Agent-executed (uncommitted at EF-ARCH-02 time): progress
  mailbox + sampler + execution states + dark benchmark screen
  with live chart implemented; JVM 31/31 OK (12 new UI tests);
  NDK-28 native compile clean; Python conformance 11/11 OK;
  `app-benchmark.apk` built offline.
- Owner-observed physical runs (executed by owner on Galaxy S22
  Ultra, Android 16; screenshots reviewed, not agent-executed):
  Run 1 — 64 tokens, 3859 ms generation, 16.6 tok/s displayed,
  prefill 23 tokens / 863 ms, thermal none at completion.
  Run 2 — 64 tokens, 3785 ms generation, 16.9 tok/s displayed,
  prefill 23 tokens / 939 ms, thermal light during run. Both
  screenshots show completed results and the new chart.
- Distinguished status: physical UI completed-run demonstration:
  OWNER OBSERVED PASS. Complete native event sequencing: NOT
  FULLY VERIFIED. Exported JSON validation for these two runs:
  NOT VERIFIED. Cancellation/lifecycle: NOT VERIFIED.
  Instrumentation overhead: NOT QUALIFIED.
- EF-ANDROID-UI-01 physical qualification is NOT declared.
  EF-ANDROID-UI-01: IMPLEMENTATION PASS, HOST TEST PASS.

## EF-ARCH-02 (design complete; owner review required)

- Agent-executed, documentation only: v2 reconciliation across
  master architecture, decision register (Amendment 002-A +
  ADR-015 ACCEPTED per owner BYOK approval; ADR-016/017/018
  PROPOSED), cloud/stats contract merge, product requirements
  (17 FR + 11 NFR), agentic workflow, release/distribution, and
  A–U traceability. No source, evidence, database, or history
  changes; no Meta calls; no commits.
- EF-ARCH-02: DESIGN COMPLETE, OWNER REVIEW REQUIRED.
- Acceptance corrections (agent-executed, documentation only):
  owner-observed UI-01 run evidence recorded without declaring
  physical qualification; owner META-06 live PASS recorded with
  response-completeness unqualified; local→global mapping
  corrected (consent-gated SUBMITTED entry, no auto-eligibility);
  aggregate/run consent scopes separated with run listing
  PROPOSED+disabled; six provisional v1 policies recorded as
  owner-approved design (activation not authorized).

## EF-SESSION-01 (implementation + host qualified; physical scoped)

- Agent-executed: durable on-device session layer implemented
  (atomic-file store under app-private `filesDir/sessions/`, 13
  explicit states, strict zero-dependency JSON codec,
  prediction-before-measurement integrity, duplicate-run
  refusal, deterministic EF-LOOP-01-parity comparison,
  launch recovery, minimal sessions dialog with offline-test
  creation, backup exclusions). `BenchRunner`, JNI, llama.cpp,
  and bench-result contract v1 unchanged (git-verified).
- Agent-executed: JVM `:app:testDebugUnitTest` 70/70 OK (34 new
  adversarial session tests: transitions, immutability,
  mismatches, time-order, replay labeling, duplicates,
  idempotency, quarantine vs unsupported-version, recovery,
  parity vectors, view builders).
- Agent-executed: `scripts/test_ef_session_parity.py` 1/1 OK —
  shared vectors verified against the real
  `ef_loop_01.cmd_attach_bench` (signed error,
  prediction-denominator percentages, round 3/2).
- Agent-executed regressions: EF-LOOP-01 26/26 OK; EF-BENCH-01
  19/19 OK; bench native 3/3 OK; EF-DATA-02 29/29 OK;
  EF-DATA-03 35/35 OK; EF-META-05 14/14 OK; EF-META-06 39/39
  OK; EF-ANDROID-01 25/25 OK; EF-ANDROID-UI-01 14/14 OK;
  EF-META-04 12/12 PASS; EF-DATA-01 PASS. No Meta API calls.
- Agent-executed: `:app:assembleBenchmark` BUILD SUCCESSFUL
  offline; `app-benchmark.apk` verified to contain the session
  classes, native benchmark libraries, and both backup-rule
  resources. APK not published; build outputs not committed.
- Database UNCHANGED: `data/edgefit.db` SHA-256
  `B36977AD...190FE9D` identical before and after all suites.
  No writes, no promotion, no cloud, no key handling.
- EF-SESSION-01: IMPLEMENTATION PASS, HOST TEST PASS.

## EF-SESSION-01 physical acceptance (executed by owner on Galaxy S22 Ultra; not executed by agent)

- Offline-test prediction 15.0 generation tok/s and 23.0
  prefill tok/s recorded in session `SES-763f9d7f001d830f`;
  benchmark `BENCH-20261028T031436Z-11b940fb` COMPLETED and
  associated; session reached COMPARISON_READY with signed
  generation error -3.825 tok/s (-25.5%).
- The session remained intact after the owner completely
  closed and reopened the application (relaunch persistence
  demonstrated).
- Scoped acceptance only: session creation, benchmark
  association, deterministic comparison, relaunch
  persistence. Forced process-death recovery,
  rotation/cancellation, and comprehensive lifecycle
  qualification remain explicitly DEFERRED, not failed.
- EF-SESSION-01: PHYSICAL SCOPED PASS (no full lifecycle
  qualification claimed).
