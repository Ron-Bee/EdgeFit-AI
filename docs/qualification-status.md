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

## EF-BYOK-01 (implementation + host qualified; physical scoped)

- Agent-executed: direct BYOK Mode A implemented (Meta Model API
  contract mirrored from the live-qualified 05/06 calls; Keystore
  AES/GCM vault with session-only default and Forget Key;
  allowlisted HTTPS client with timeouts, size caps, no retries;
  per-action authorization dialogs; strict prediction/follow-up
  validation; live orchestration over the EF-SESSION-01 state
  machine; minimal Meta UI). No-tools requests; no managed
  backend. `BenchRunner`, JNI, workload, session integrity
  rules, and bench-result contract v1 unchanged (git-verified).
- Agent-executed: JVM `:app:testDebugUnitTest` 119/119 OK (49
  new: contract shape, URL allowlist, prompt identities,
  extraction, prediction/follow-up validation matrix,
  proposal quarantine, vault custody with fake seal,
  orchestration with scripted mock transport, view builders).
  Mocks are labeled and never claimed as live qualification.
- Agent-executed regressions: session parity 1/1 OK; EF-LOOP-01
  26/26 OK; EF-BENCH-01 19/19 OK; bench native 3/3 OK;
  EF-DATA-02 29/29 OK; EF-DATA-03 35/35 OK; EF-META-05 14/14
  OK; EF-META-06 39/39 OK; EF-ANDROID-01 25/25 OK;
  EF-ANDROID-UI-01 14/14 OK; EF-META-04 12/12 PASS; EF-DATA-01
  PASS. No Meta API calls by the agent.
- Agent-executed: `:app:assembleBenchmark` BUILD SUCCESSFUL
  offline; `app-benchmark.apk` verified (`ai.edgefit.diag.bench`,
  INTERNET the sole permission, Meta classes + allowlisted URL
  + network config + backup exclusions present). APK not
  published; build outputs not committed.
- Database UNCHANGED: `data/edgefit.db` SHA-256
  `B36977AD...190FE9D` identical before and after all suites.
  No writes, no credentials in source or artifacts
  (grep-verified), no key in logs/exports/state.
- EF-BYOK-01: IMPLEMENTATION PASS, HOST TEST PASS.

## EF-BYOK-01 physical acceptance (executed by owner on Galaxy S22 Ultra, Android 16; not executed by agent)

- One linked live session `SES-aa255b757ace13cd`: live_meta
  prediction from `muse-spark-1.3` (12.0 generation tok/s,
  45.0 prefill tok/s, low confidence, uncertainty disclosed)
  recorded BEFORE the physical benchmark; benchmark
  completed; deterministic comparison +6.417 tok/s (+53.48%,
  prediction underestimated measurement); explicitly
  authorized live follow-up received, displayed, and recorded
  (insufficient for generalization, one proposed experiment,
  not automatically executed).
- Owner-confirmed: session contents persisted after closing
  and reopening EdgeFit AI; Forget Key cleared the
  user-visible stored credential state.
- Scoped acceptance only: live prediction, benchmark
  association, deterministic comparison, authorized
  follow-up, relaunch persistence, key clearing. Benchmark
  run id and export for the live session were not reported;
  independent export inspection is DEFERRED.
- Explicitly DEFERRED, not PASS: forced process-death and
  activity-lifecycle edge cases; Keystore error/restoration
  scenarios; redirect and transport hardening; comprehensive
  public-release security qualification.
- EF-BYOK-01: PHYSICAL SCOPED PASS. LIVE META API PASS
  (owner-executed, one session). FULL PREDICTION→BENCHMARK→
  FOLLOW-UP PASS (owner-executed, one session).

## EF-AGENT-01 (implementation + host qualified; live/physical NOT RUN)

- Agent-executed: bounded within-model optimization loop
  implemented (agent parent/child sessions, evidence packs
  with comparable/contextual split, strict proposal
  validation, narrow runner config bridge, explicit
  approve/reject/finish, 5-attempt budget, stagnation/user/
  decline/invalid termination, reconcile + summaries, agent
  dialog). Plain validated JSON, no function calling. Native
  workload, JNI, and bench-result contract v1 unchanged
  (git-verified); runner override path keeps legacy default
  byte-for-byte.
- Agent-executed: JVM `:app:testDebugUnitTest` 165/165 OK (46
  new: 12 protocol, 7 evidence, 18 lifecycle, 5 view, 4
  client-proposal mocks). All Python suites, EF-DATA-01, and
  EF-META-04 12/12 green.
- Agent-executed: `:app:assembleBenchmark` BUILD SUCCESSFUL
  offline; APK verified to contain agent classes and the
  config bridge. APK not published; build outputs not
  committed. Implementation UNCOMMITTED at checkpoint time.
- Database UNCHANGED: `data/edgefit.db` SHA-256
  `B36977AD...190FE9D` identical before and after all suites.
  No Meta API calls by the agent, no benchmarks run.
- EF-AGENT-01: IMPLEMENTATION PASS, HOST TEST PASS. LIVE
  AGENT PROPOSAL NOT RUN. PHYSICAL AGENT EXPERIMENT NOT RUN.
  END-TO-END AGENTIC LOOP NOT RUN.

## EF-MODEL-ARCH-01 (design-only reconciliation; owner review required)

- Agent-executed, documentation only: incorporated the
  owner-supplied consolidated amendment (3–5 candidate
  shortlist; the task brief's "35" is a transcription error
  and the amendment governs). Added FR-MODEL-01/02/03,
  FR-EVID-01/02, NFR-MODEL-01 (all PROPOSED); PROPOSED
  ADR-019 for optional anonymous Mode A public-evidence
  reads with a precise no-key/no-measurements boundary;
  master-architecture Mode A clarification plus stale-status
  refresh against verified milestone evidence; agentic
  within-model vs alternative-model scoping; cloud catalog
  and `model-candidates` endpoint proposals; stats
  cross-model read rules with quorum intact; traceability
  rows V/W/X plus an AGENT-01 status correction.
- No accepted ADR was changed or weakened. FR-BYOK-02 keeps
  its no-backend-contact acceptance for the built client,
  with a recorded pending interaction for FR-EVID-01. No
  multi-model testing, public cohort data, cloud, website,
  distribution, or physical work is claimed or begun.
- EF-MODEL-ARCH-01: DESIGN COMPLETE, OWNER REVIEW REQUIRED.

## EF-UX-REQ-01 (documentation only; owner review required)

- Agent-executed, documentation only: owner-requirements
  reconciliation for the pre-submission UI/UX overhaul. Added
  FR-UX-01 (coherent main experience), FR-ONBOARD-01
  (skippable onboarding with durable-record retirement),
  FR-RPT-01 (report import/attachment as a separate
  milestone) — all PROPOSED / OWNER-REQUESTED, NOT STARTED;
  a milestone-governance rule covering all remaining
  milestones; four open owner decisions (Skip permanence,
  standalone-run counting, replay counting, C-vs-D order);
  master-architecture §18 amendment + component rows 9a/9b;
  traceability rows Y/Z/AA plus the A–G pre-submission
  roadmap; this entry. No ADR added or amended (no new
  architecture decision; no accepted decision conflicts);
  agentic workflow untouched.
- All pre-existing FR/NFR/ADR text preserved verbatim in
  place; cloud, stats, web, and multi-model scope retained
  long-term with statuses unchanged. Nothing designed,
  implemented, or qualified by this milestone.
- EF-UX-REQ-01: DESIGN COMPLETE, OWNER REVIEW REQUIRED.

## EF-AGENT-01 physical acceptance (device records read by agent via run-as; runs executed by owner on Galaxy S22 Ultra, Android 16)

- Agent parent `SES-a77eaa6f4cd92641`, budget 4/5 after four
  attempts. Experiment 4 child `SES-63a4ace2c6d85778`:
  proposal `81c7c178` approved (256/4/64, expected 17.5/27.0
  tok/s, citing exp-1 baseline); run
  `BENCH-20261028T224503Z-38ccf1f5` (source `06ac974d…`)
  COMPLETED with executed config equal to approved config;
  measured 17.738359201773836 generation / 30.997304582210244
  prefill tok/s; host comparison +0.238 (+1.36%) / +3.997
  (+14.8%); explicitly authorized live follow-up recorded
  from `muse-spark-1.3` (underestimated, supports_conclusion
  false, single-observation limits stated).
- Same-second attachment (prediction and collection both
  `22:45:03Z`) succeeded on device: FIX2 physically verified.
  Prior rejected experiments 2/3 preserved bit-identically;
  failed runs correctly excluded from evidence packs.
- Agent-executed host evidence at checkpoint: JVM
  `:app:testDebugUnitTest` 202/202 OK (AGENT-01 + FIX + FIX2
  suites); `:app:assembleBenchmark` BUILD SUCCESSFUL;
  EF-META-04 12/12 PASS. Python suites NOT RUN (no
  interpreter in agent environment).
- Scoped only: one approved-experiment loop + exp-1 cycle.
  Stagnation/invalid-strike/reject termination paths remain
  UNQUALIFIED live. No statistical or causal optimization
  improvement is established (n=1 per config; thermal
  conditions differed between runs).
- EF-AGENT-01: PHYSICAL SCOPED PASS. LIVE AGENT PROPOSAL
  PASS. END-TO-END AGENTIC LOOP PASS (scoped).

## EF-UX-01-DESIGN (design only; owner review required)

- Agent-executed, documentation only: closed the four
  EF-UX-REQ-01 owner decisions as UX-D01 (Skip dismisses
  current presentation only), UX-D02 (standalone counts
  only if durably recorded + identifiable; smallest
  `standalone`-record extension designed), UX-D03 (replay
  counts with REPLAY label under `replay_source_sha256`
  dedup; producer owned by FR-RPT-01), UX-D04 (UX-01A…D
  before report milestone). Created `docs/ef-ux-01-design.md`
  (inventory, 5-tab navigation, Home layout, tokens,
  onboarding state model, completion/dedup predicate,
  agent/benchmark/settings flows, empty/failure/offline
  states, a11y, traceability, milestones UX-01A…D,
  S22 acceptance checklist, limitations). Updated
  requirements decisions, traceability Y/Z/AA + roadmap.
  No ADR added or amended; no accepted text weakened.
- Nothing designed here is implemented or qualified;
  implementation awaits owner design review.
- EF-UX-01-DESIGN: DESIGN COMPLETE, OWNER REVIEW REQUIRED.

## EF-UX-01A (host qualified; physical NOT RUN)

- Agent-executed: five-destination navigation shell (Home,
  Benchmark, Sessions, Agent, Settings) with zero new
  dependencies — platform Views, `onBackPressed` override,
  and resource tokens (`#0866FF`/`#FFFFFF`/`#F2F4F7`/
  `#DCE7F7`/`#172B4D` + spacing/shape). Same widget
  instances re-homed; all handlers, dialogs, session
  store, key custody, authorization, benchmark, and
  agent logic untouched. Dark bench overlay preserved
  full-screen; Back during a run stays put, otherwise
  returns to Home; tab survives rotation. Advisory
  next-action line from a pure tested function.
- Host evidence: JVM `:app:testDebugUnitTest` 213/213 OK
  (11 new: NavTab 5, NextAction 6); `:app:assembleBenchmark`
  BUILD SUCCESSFUL; EF-META-04 12/12 PASS. Emulator crawl
  (Pixel 9, NOT the S22): all tabs + dialogs open with
  preserved data, Back/rotation verified, zero FATALs.
  No benchmarks run, no Meta calls, no phone install.
- Scope: shell + tokens only. Feature-screen redesign
  (UX-01B/C), onboarding (UX-01D), report import, and
  Material3 adoption (if ever) remain NOT STARTED.
- EF-UX-01A: HOST QUALIFIED. PHYSICAL NOT RUN.

## EF-UX-01BC (combined B+C presentation overhaul; host + S22 UI crawl)

- Agent-executed: `launchTracked` + `RequestTracker` +
  non-cancelable `ProgressUi` for live prediction /
  follow-up / proposal (truthful in-flight states, no
  fabricated %); `InsightCards` + `CardDialog` structured
  ESTIMATE/MEASURED presentation with Details provenance;
  Home dashboard (next action + Continue, device card +
  full-profile Details, model card, last durable
  measurement, Meta key state, session count); Sessions
  type labels + pending actions + detail cards; Agent
  Review Details + hypothesis-missed suffix; single-line
  tab labels. Zero new dependencies; no contract, native,
  threshold, or preflight changes.
- Host evidence: `:app:testBenchmarkUnitTest` 231/231 PASS
  (18 new: RequestTracker 4, InsightCards 8, HomeDashboard
  6); `:app:assembleBenchmark` BUILD SUCCESSFUL
  (app-benchmark.apk 8.4 MB, v0.2.0). One pre-existing
  presentation assertion (`benchmark: not attached`) was
  updated to the redesigned `measured:` vocabulary with
  run-ID provenance restored to the summary line.
- Physical evidence (S22 Ultra (owner device), NOT emulator):
  non-destructive `install -r` Success; all 5 session
  SHA-256 unchanged before install, after install, and
  after crawl; all 5 tabs + Sessions dialog + session
  Detail card + Agent dialog + Device Details opened via
  uiautomator; single-line tab labels confirmed; Home
  shows live device/model/last-run (`SES-dc327cae60cd8e6f`,
  12.8/24.4 tok/s) content; landscape rotation keeps tabs,
  auto-rotate restored; zero app FATALs. No benchmarks
  run, no Meta calls, no data cleared, no credentials read.
- Memory-failure diagnosis (read-only, UNVERIFIED root
  cause): preflight needs avail >= max(2 GB, model + 1 GB);
  807 MB staged model needs 2 GB; phone showed 2.2–2.3 GB
  free at check time — borderline, consistent with an
  expected guard refusal under transient pressure, not a
  proven app defect. No threshold change made.
- Deferred: loading-state live observation (needs a real
  Meta call), onboarding (UX-01D), report import
  (FR-RPT-01), AUD-04 deletion semantics, dark theme.
- EF-UX-01BC: IMPLEMENTATION PASS. HOST PASS. S22 UI-CRAWL
  PASS (navigation/presentation only). LIVE-REQUEST
  FEEDBACK NOT OBSERVED (no paid calls authorized).

## EF-UX-01BC-R2 (visual polish, semantic metrics, icon nav)

- Agent-executed: semantic tokens (`edgefit_measured`
  #147D92, `edgefit_predicted` #7655C7, `edgefit_error`
  #D92D20, dark-safe error, inactive tab); five original
  vector nav icons (house/gauge/folder/sparkle/gear) with
  blue-selected/muted-unselected tinting, compact
  single-line labels, content descriptions, 56dp targets;
  `InsightCards.Accent` + `statusAccent` (COMPLETED-family
  MEASURED at any speed, prediction states PREDICTED,
  failures ERROR, comparison rows neutral); `SemanticUi`
  primary-blue / destructive-red button styles; blue
  Continue/Run/Predict/Follow-Up/Save/Request/Approve,
  red Delete/Forget; state-word tints in Sessions/Agent
  lists; bench result headline emphasis + red `Error:`
  line and failed stage on the dark screen. Zero new
  dependencies; no behavior, contract, or guard changes.
- Host evidence: `:app:testBenchmarkUnitTest` 238/238 PASS
  (7 new `SemanticAccentTest`); `:app:assembleBenchmark`
  BUILD SUCCESSFUL (app-benchmark.apk 8.4 MB, v0.2.0).
- Physical evidence (S22 Ultra (owner device), NOT emulator):
  non-destructive `install -r` Success; all 5 session
  SHA-256 unchanged across install + crawl; icon nav with
  selected-state tint verified by hierarchy + screenshot;
  Home/ Sessions list / Detail card / Agent dialog
  screenshotted: teal measured + completed states, purple
  estimates, red failures/destructive, blue primaries;
  Back Benchmark→Home verified; zero app FATALs. No
  benchmarks, Meta calls, clears, or credential reads.
  Large-font layout NOT RUN (system setting untouched).
- EF-UX-01BC-R2: IMPLEMENTATION PASS. HOST PASS. S22
  UI-CRAWL PASS (visual/presentation only).

## EF-UX-01E (live telemetry + session timeline; S22 verified)

- Agent-executed: `DeviceTelemetry` (cheap
  ActivityManager/PowerManager/sticky-battery sampling,
  pure formatters, injectable-scheduler `Ticker`) and
  `SessionTimeline` (UTC→local rendering, history-event
  labels, UTC evidence block). Home shows static identity
  + live battery/charging/temp/RAM/thermal/`Updated`
  lines; Device Details is a live dialog (static identity
  + auto-refreshing health + unavailable list). Session
  rows gain `created:` local dates; Detail gains
  Created/Last update, full Timeline from record history,
  and UTC Technical evidence. Agent actions vertical
  (Request Proposal no longer wraps); session buttons
  single-line. Zero new dependencies/permissions; no
  state, contract, guard, or threshold changes; no
  timestamp backfill.
- Cadence: battery event-driven via registered receiver;
  Home poll 30 s (glanceable), Details poll 15 s (active
  inspection). Sampling gated on activity-started AND
  (Home visible OR Details open); receiver/tickers
  stop on hide, tab switch, or onStop. No service, no
  background work.
- Host evidence: `:app:testBenchmarkUnitTest` 254/254 PASS
  (16 new: DeviceTelemetry 8, SessionTimeline 6,
  sessionSections timeline 2); `:app:assembleBenchmark`
  BUILD SUCCESSFUL (8.4 MB, v0.2.0). `git diff --check`
  clean.
- Physical evidence (S22 Ultra (owner device), NOT emulator):
  `install -r` Success; all pre-existing session hashes
  unchanged across install + crawl (owner's experiment 4
  SES-a84ee63d30dfbab3 predates this install); Home health
  vs `dumpsys battery` (100%/Full/34.0 °C vs app
  100%/Full/33–34 °C live fluctuation); Details
  auto-refresh observed (2.2→1.6 GB, timestamp advanced);
  Timeline dates match stored UTC (01:39:19Z = 8:39 PM
  CDT); Agent buttons single-line vertical; logcat proves
  ticker/receiver start on Home+Details and stop on hide;
  zero app FATALs. No benchmarks, Meta calls, clears, or
  credential reads.
- EF-UX-01E: IMPLEMENTATION PASS. HOST PASS. S22
  UI-CRAWL PASS (telemetry/timeline/presentation only).

## EF-UX-REVIEW-03-R1 (acceptance review + limited polish; HOST ONLY, no ADB)

- Agent-executed source review: EF-UX-01E claims verified
  against source (`DeviceTelemetry`/`SessionTimeline`
  present, 30 s/15 s cadence, battery-receiver wiring,
  Timeline + UTC evidence sections, vertical Agent
  actions) and host baseline re-run 254/254 PASS. No
  inconsistencies found; no device crawl performed (new
  owner policy: owner reviews on-device).
- Agent-executed implementation (narrow, FR-UX-01): three
  replacement nav icons (performance bars, assistant
  speech-bubble waveform — Gemini sparkle removed, modern
  8-tooth gear; Home/Sessions retained); session rows and
  experiment entries wrapped in separated cards;
  `InsightCards` session Detail gains Meta follow-up
  section (Overview/Prediction/Measurement/Comparison/
  Follow-up/Timeline/Technical evidence); Agent overview
  gains best-comparable line; failed runs render
  "measured: none (run FAILED — no observation)" and "no
  measurement (run FAILED)" instead of 0.0 tok/s;
  `BenchGuards.refusalDetail` appends measured-vs-required
  MB to guard refusals (decision/thresholds unchanged).
  Zero new dependencies; no state-machine, contract, or
  native changes.
- Memory-guard diagnosis: the observed refusal (512/2/64,
  0 tokens, none/unknown/none) matches the expected
  `failedNow` shape exactly — guard-consistent, no defect
  evidenced. Limitation recorded: avail/required numbers
  previously lost (now in the refusal string → bench
  screen + exported JSON); session records still drop
  `result.error` — minimal extension (optional
  `failure_cause` in session measurements) proposed for
  later approval, not implemented.
- Model recommendations: design-only per EF-MODEL-ARCH-01
  (FR-MODEL-01/02/03, FR-EVID-01, NFR-MODEL-01, ADR-019):
  owner-reviewed curated offline catalog is the viable
  pre-cloud step; live retrieval/downloads stay future.
  Nothing implemented.
- Host evidence: `:app:testBenchmarkUnitTest` 261/261 PASS
  (7 new); `:app:assembleBenchmark` BUILD SUCCESSFUL
  (ai.edgefit.diag.bench v0.2.0, 8.4 MB, 2026-10-28).
  `git diff --check` clean. DB hash unchanged;
  Microsoft/ untouched; ADR file unmodified.
- EF-UX-REVIEW-03-R1: REVIEW COMPLETE. IMPLEMENTATION
  PASS (limited scope). HOST PASS. PHYSICAL REVIEW
  PENDING (owner-operated; checklist in milestone
  report).

## EF-FOLLOWUP-02 / EF-MODEL-01 (diagnosis + design; HOST ONLY, no ADB)

- Agent-executed diagnosis (source-verified, no guessing):
  repeated follow-up is unavailable because (1) one run
  per session is enforced (`attachBenchmark` requires
  BENCHMARK_RUNNING + `benchmark == null`), (2)
  RECOMMENDATION_RECORDED transitions only to
  SESSION_FINISHED, (3) `requestFollowup` requires
  COMPARISON_READY, and (4) agent proposals that change
  nothing are rejected (`AgentApi`: "proposal changes
  nothing"). A repeat benchmark therefore lands
  standalone (or fails to bind), produces no comparison,
  and offers no follow-up; same-config repeats are
  unreachable in every flow. Classification: missing
  repeated-measurement workflow (E), via intended
  one-follow-up-per-session mechanics (A+B).
- Design boundary reached, NO source changes: the correct
  fix (repeat child/session with carried-prediction
  provenance, proposal `purpose: repeat` acceptance,
  budget-charged repeats, cross-run host comparison)
  touches prediction-integrity semantics, the agent
  proposal schema, and budget policy — owner approval
  required before implementation. Full amendment + GGUF
  catalog architecture + EF-SCIENCE-01 plan + UX design
  delivered in the milestone report; nothing built.
- Host evidence: `:app:testBenchmarkUnitTest` 261/261 PASS
  (baseline intact, zero new tests — nothing to cover);
  no APK rebuild (source unchanged; R1 APK
  ai.edgefit.diag.bench v0.2.0 current). `git diff
  --check` clean. DB hash unchanged; Microsoft/ untouched.
- EF-FOLLOWUP-02 / EF-MODEL-01: DIAGNOSIS COMPLETE.
  DESIGN COMPLETE. IMPLEMENTATION NOT STARTED (owner
  approval required).

## EF-FOLLOWUP-02 (repeatable benchmark + follow-up loop; HOST ONLY, no ADB)

- Agent-executed implementation (FR-AGENT-04, owner
  decisions D1–D4): NEW linked repeat records via
  `SessionManager.createRepeat` (carried prediction,
  immutable original timestamp, `carried_from_*`
  provenance, `repeat_of_*` links); run-ID format gate
  (`BENCH-…`); Agent `purpose: repeat` validation
  (eligible citation + exact config vs STORED executed
  config, sampler included; ordinary no-change still
  refused); budget-charged approval (`REPEAT_LINK` /
  `REPEAT_CONFIG` fail-closed checks); host
  `findSeriesRuns` + `SessionComparison.compareSeries`
  (COMPLETED same-config only, current-minus-prior
  deltas, descriptive range — never significance);
  bounded follow-up priors (max 4, host deltas,
  SERIES_PRIORS block, disclosure line added to the
  authorization dialog); UI Repeat/Compare actions with
  standalone-vs-agent eligibility; repeat-labeled Agent
  proposal review. Native runner, workload, guards,
  contracts, and budget size unchanged.
- Host evidence: `:app:testBenchmarkUnitTest` 288/288
  PASS (27 new `FollowUp02Test`); `:app:assembleBenchmark`
  BUILD SUCCESSFUL (ai.edgefit.diag.bench, 8,810,317
  bytes, 2026-10-28; manifest/Gradle untouched so
  identity unchanged). `git diff --check` clean. DB
  hash B36977AD…190FE9D unchanged; Microsoft/ untouched.
  No Meta calls, no benchmarks, no DB writes, no ADB.
- EF-FOLLOWUP-02: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL REVIEW PENDING (owner-operated; procedure in
  milestone report). Live repeat proposal + measured
  repeat NOT RUN.

## EF-MODEL-01A (vetted offline GGUF catalog; HOST ONLY, no ADB)

- Agent-executed implementation (FR-MODEL-01 PARTIAL):
  bundled `res/raw/model_catalog.json` v1 (5 entries,
  `pending_owner_review`); strict pure-JVM parser
  (version/size/count caps, https + host allowlist,
  .gguf paths, source-in-repo, 64-hex hashes,
  rationale speed-claim screen); conservative
  `ModelSuitability` (FR-MODEL-01 statuses; guard
  headroom rule; verified_compatible only via local
  exact-hash COMPLETED run; memory-now as caveat);
  five evidence classes (v1 renders estimate or
  measured only); Recommended Models dialog (Home
  Model section + Benchmark tab entries; cards,
  Details, explicit-tap external View Source with
  re-checked allowlist); presentation-only repeat
  boundary clarification ("Continue experimenting …
  preserved"). No network search, no downloads, no
  auto Meta calls, no guard changes.
- Source verification (agent, 2026-10-29, read-only
  web): all 5 repos/files/sizes/licenses read from
  huggingface.co (Qwen3-0.6B-Q8_0 639 MB apache-2.0;
  gemma-3-1b-it-Q4_K_M 806 MB gemma; llama-3.2-1b
  Q4_K_M bartowski 808 MB llama3.2; smollm2-360m Q8_0
  386 MB apache-2.0; qwen2.5-0.5b Q4_K_M 491 MB
  apache-2.0). Runtime arches (qwen3/gemma3/llama/
  qwen2) confirmed present in pinned llama.cpp v0.7.0
  @620d2225 source. SHA-256 unverified for all
  (no downloads performed); exact bytes unverified
  (listed sizes used as MiB upper bounds).
- Host evidence: `:app:testBenchmarkUnitTest` 318/318
  PASS (30 new `ModelCatalogTest`);
  `:app:assembleBenchmark` BUILD SUCCESSFUL
  (ai.edgefit.diag.bench, 8,829,355 bytes,
  2026-10-28; manifest/Gradle untouched). `git diff
  --check` clean. DB hash B36977AD…190FE9D unchanged;
  Microsoft/ untouched. FR-MODEL-02/03, FR-EVID-01/02,
  ADR-019 remain PROPOSED / NOT STARTED.
- EF-MODEL-01A: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL REVIEW PENDING (owner-operated).
  FR-MODEL-01 PARTIAL (offline only).

## EF-MODEL-01B (contextual recommendations; HOST ONLY, no ADB)

- Agent-executed implementation (FR-MODEL-01 PARTIAL,
  deepened): current-model context (staged ref + latest
  valid COMPLETED measurement by collection time;
  failed/non-finite excluded); host size differences
  (absolute + %, approximate unless both sides exact;
  staged baseline preferred, measurement fallback);
  deterministic `ModelRecommend` (7 kinds; measured /
  verdicts / size guidance; no scores, no speed
  claims, no cross-artifact throughput ranking);
  exact-hash `localEvidenceFor` (null catalog hash
  never matches; latest COMPLETED run wins);
  `memoryLimitedNow` flag (condition, verdict
  unchanged) + avail snapshot on Assessment;
  restructured details (Candidate / Recommendation /
  Comparison / Compatibility / Memory / Local
  evidence / Limits / Technical); dialog gains
  current-model overview, per-card Evidence button
  (exact matches only), Return to Model Selection
  (working), and the different-model new-context
  clarification. Catalog JSON unchanged (5 entries,
  still pending review). No Meta changes, no guard
  changes, no state-machine changes.
- Association + Meta boundary (design only): staged↔
  catalog joining by name/file/size rejected in
  `docs/ef-model-01b-association.md`; owner-verified
  SHA backfill proposed (no code); user-asserted link
  contract sketched and STOPPED for approval. Meta
  payload analysis: a bounded CANDIDATES block fits
  the 32 KB transport pattern, but validated
  candidate references need new optional response
  fields + ID-allowlist validation + disclosure
  update — owner approval required, NOT implemented.
- Host evidence: `:app:testBenchmarkUnitTest` 338/338
  PASS (20 new `ModelRecommendTest`; FOLLOWUP-02
  regressions green); `:app:assembleBenchmark` BUILD
  SUCCESSFUL (ai.edgefit.diag.bench, 8,845,589 bytes,
  2026-10-28; manifest/Gradle untouched). `git diff
  --check` clean. DB hash B36977AD…190FE9D unchanged;
  Microsoft/ untouched. FR-MODEL-02/03, FR-EVID-01/02,
  ADR-019 remain PROPOSED / NOT STARTED.
- EF-MODEL-01B: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL REVIEW PENDING (owner-operated).

## EF-SCIENCE-01A (analysis infrastructure; HOST ONLY, no ADB)

- Agent-executed implementation: deterministic
  PowerShell 5.1 analysis tool
  (`scripts/Invoke-EFScience01A.ps1` v1a-1, zero
  deps): contract mirror of `ef_bench_result_01.py`
  (incl. tps recomputation ±0.01), SHA-256 +
  manifest checks, duplicate run/content refusal,
  exact-cohort grouping (model+workload+runtime+
  config+app+native-build key), mean/median/min/
  max/sample-SD/CV/[min,max] descriptive interval,
  same-model-only comparisons with differing-field
  list + isolated flag, strictly-predating
  prediction errors, machine-readable JSON.
  Self-test `scripts/Test-EFScience01A.ps1`:
  46/46 PASS (15 black-box cases incl. Decimal-
  path, tamper, manifest, reproducibility).
  Python was unavailable (no interpreter), so the
  tool is PS1 by environment necessity, following
  the Test-EF-META-04 precedent.
- Evidence inventory (read-only, hashes verified):
  4 benchmark JSONs + 3 profiles intact (all 7
  SHA-256 match prior records); tool run yields 2
  cohorts (debug n=1 @0.3828; benchmark n=3,
  median 14.3820, mean 12.7913, SD 4.7909,
  CV 0.3745) + 1 multi-field comparison. No host
  predictions predate these runs; DB unreadable
  without a driver (hash + qualified-record
  citation only); logcat is context, not evidence.
- Docs: protocol refined (H1–H3, endpoints,
  exploratory/confirmatory split, §8 quorum);
  report draft `docs/ef-science-01-report.md`
  (14 sections; results exploratory-only, pending
  sections marked); case study
  `docs/ef-science-01-casestudy.md` (2 vignettes,
  second sourced from the AGENT-01 qual record);
  worksheet `docs/ef-science-01-worksheet.md`
  (B/C×5 counterbalanced, none reusable).
- Host evidence: PS self-test 46/46; JVM suite
  338/338 (up-to-date, no Android changes);
  EF-META-04 PS suite 12/12 PASS; Python suites
  NOT RUN (no interpreter). `git diff --check`
  clean. DB hash
  B36977AD…190FE9D unchanged; evidence originals
  re-hashed intact; Microsoft/ untouched.
- EF-SCIENCE-01A: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL TRIALS PENDING (owner worksheet).

## EF-SCIENCE-01A-CORRECTION-01 (wording; HOST ONLY, no ADB)

- Defect 1 (build comparison): the 37.57×/37.6×
  debug-vs-benchmark figure now carries its full
  field-diffed differing-conditions list (build
  identity, collection time, terminal thermal,
  observed memory, snapshot + unrecorded factors),
  is labeled observational/multi-factor, and is
  explicitly NOT attributed to Muse, flags alone,
  context, or any proven intervention.
- Defect 2 (thermal wording): "thermal-driven" /
  "throttling dominates" replaced with association
  language; report now distinguishes thermal status
  vs battery temperature vs measured throttling
  (only status labels exist in artifacts) and
  requires controlled trials before causal claims.
- Figures recomputed from originals: pair ratio
  37.5697, spread 2.2389. Docs corrected: report,
  case study, protocol (§4 note), worksheet.
  Analysis tool labels already correct (no code
  change). Historical milestone entries untouched.
- Verification: PS self-test 46/46 re-run green
  (tool untouched); evidence re-hashed intact; DB
  B36977AD…190FE9D unchanged. JVM suite not re-run
  (no Android changes).
- CORRECTION-01: PASS. No new limitations beyond
  the standing n≤3 / single-device / no-host-
  prediction gaps.

## EF-UX-02 (navigation/action continuity; HOST ONLY, no ADB)

- Agent-executed implementation (FR-UX-01): new pure
  `SessionListUi` (newest-first ordering w/ id
  tie-break, 13 friendly states, 6 filters, Action
  Needed eligibility, follow-up gate, row labels);
  `SessionManager.listSessions` delegates ordering
  (no schema change); `SessionView` rewritten as an
  accordion (collapsed rows, lazy expanded bodies,
  Latest highlight, 2-column action grid, delete
  confirmation, filter spinner, fixed create/close
  area, creation panel, repeat→prep transition
  w/ double-tap guard); `CardDialog` gains
  next-action buttons; `MainActivity` gains repeat-
  prep screen (run/select-model), benchmark-result
  comparison dialog w/ follow-up offer, prediction→
  Open Benchmark, staging/picker return to prep,
  follow-up next steps (Open Agent / Repeat /
  Compare), export save-button disable;
  `seriesSections` restructured (Reference row,
  explicit difference definition, observed range,
  short run tags); `pendingAction` corrected for
  offline sessions. No native/guard/contract/state-
  machine/budget changes.
- Host evidence: `:app:testBenchmarkUnitTest`
  360/360 PASS (22 new `SessionListUiTest`;
  FOLLOWUP-02 series test updated to the new
  structure); `:app:assembleBenchmark` BUILD
  SUCCESSFUL (ai.edgefit.diag.bench, 8,865,886
  bytes, 2026-10-29; manifest/Gradle untouched).
  `git diff --check` clean (exit 0; LF/CRLF
  notices pre-existing). DB hash
  B36977AD…190FE9D unchanged; Microsoft/
  untouched; no commits/pushes.
- EF-UX-02: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL REVIEW PENDING (owner-operated).

## EF-MEM-01 (adaptive memory admission; HOST ONLY, no ADB)

- Root causes (source-verified): the S22 refusal was a
  pre-load guard miss (avail 2020 MB < required 2048 MB
  floor), single-sampled, 0 tokens, 0 ms load — never
  native execution. The 02:09 elapsed was a stale
  `wallStartMs` (previous run's anchor reused on the
  refusal path, then left untouched by `showResult`).
  No native retention exists (model/ctx/sampler freed
  on every path); no large EdgeFit-owned caches; one
  worker enforced. Refusal reasons reached UI + export
  JSON but were dropped from session records.
- Agent-executed implementation: new pure `MemRecheck`
  (64 MiB near window, 3 samples @ 500 ms, sole-memory-
  miss eligibility, SAME requirement re-evaluated,
  cancellable worker-thread loop, `x_mem_recheck`
  evidence incl. monotonic stamps + recheck_ms kept
  outside the measurement window); `wallStartMs`
  anchored at run entry; durable `failure_cause` in
  session benchmark maps (schema v1 additive) +
  Cause row in Session Details; bench-screen refusal
  headline + Recheck Memory (evaluation only, never
  runs) / Smaller Model (catalog) / Details actions.
  Guard formula, thresholds, workload, native code,
  and budgets unchanged; per-model lowering STOPPED
  at the proof boundary (no ADR pins the numbers,
  but no safe lower bound is evidenced).
- Host evidence: `:app:testBenchmarkUnitTest`
  377/377 PASS (17 new `MemRecheckTest`);
  `:app:assembleBenchmark` BUILD SUCCESSFUL
  (ai.edgefit.diag.bench, 8,876,080 bytes,
  2026-10-29; manifest/Gradle untouched). `git diff
  --check` clean (exit 0; LF/CRLF notices
  pre-existing). Python validator NOT RUN (no
  interpreter); `x_` acceptance verified by reading
  the validator skip rule + importer `.get()` use.
  DB hash B36977AD…190FE9D unchanged; Microsoft/
  untouched; no commits/pushes.
- EF-MEM-01: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL REVIEW PENDING (owner-operated).

## EF-UX-01D (onboarding + acceptance polish; HOST ONLY, no ADB)

- Agent-executed implementation (FR-ONBOARD-01,
  UX-D01..D04): new pure `Onboarding` (SHOW gate,
  retirement count over durable records per design
  F, 3-page content model); `OnboardingView`
  3-screen guide (Next/Back/Skip/Start, Back-key
  paging, rotation restore, no network/key/paid
  work); cold-start + gated foreground evaluation
  (quota/dismiss/run/modal ordering, picker-return
  suppression); Settings → Help with completion
  count + replay entry; UX-D02 standalone branch
  (`MODE_STANDALONE`, standalone-only
  CREATED→AUTHORIZED machine edge, authorize/
  attachStandalone with full identity + run-sweep,
  bridge recordStandalone, COMPLETED-only persist
  on unbound runs); standalone labels; UX-D03
  replay source-key dedup (producer dormant until
  FR-RPT-01). Correction A: recovery row
  center-vertical gravity (device-verified pending).
  Correction B: `tpsHeadline` — non-completed
  zero-output runs read "Not measured", genuine
  partials still measured. No threshold, workload,
  native, budget, custody, or consent changes.
- Host evidence: `:app:testBenchmarkUnitTest`
  395/395 PASS (18 new `OnboardingTest`);
  `:app:assembleBenchmark` BUILD SUCCESSFUL
  (ai.edgefit.diag.bench, 8,888,541 bytes,
  2026-10-30; manifest/Gradle untouched).
  `git diff --check` clean (exit 0; LF/CRLF
  notices pre-existing). DB hash
  B36977AD…190FE9D unchanged; Microsoft/
  untouched; no commits/pushes.
- EF-UX-01D: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL REVIEW PENDING (owner-operated).

## EF-MEM-02 (diagnostics + readiness; HOST ONLY, no ADB)

- Agent-executed implementation (NFR-THERM-01,
  NFR-SAFE-01, NFR-PERF-01, NFR-EVID-01,
  NFR-REPRO-01, NFR-OFF-01; master arch 17-18;
  future evidence arch 3, 5, 10-15 untouched, no
  upload): new pure `MemDiag` (11 checkpoints,
  validated samples, 64-cap store, MB formatting);
  `MemReadiness` (READY/CLOSE/INSUFFICIENT/UNKNOWN
  mirroring the enforced guard; CLOSE = MEM-01 near
  window); `MemBudget` shadow estimator (no admit
  field by construction; conclusion stays
  insufficient_data); opt-in Settings toggle
  (default off, in-memory, resets each launch; no
  background polling, no uploads, process-local
  signals only); Benchmark-tab readiness line +
  Check Memory Readiness dialog (Recheck / Memory
  Details / Recommended Models; read-only, never
  starts inference); informational readiness note
  on the prediction-auth dialog (appended after,
  never inside, the transmitted field list;
  non-blocking); run checkpoints G/H/I/K+L/M in
  `BenchRunner` with `x_mem_diag` export (null
  unless enabled; marks run instrumented +
  non-confirmatory); `SessionView.onClose` hook.
  Guard formula UNCHANGED
  (`max(modelBytes + 1 GiB, 2 GiB)`); no
  threshold/native/workload/budget/custody/consent
  changes; no cleanup corrections (review found no
  material avoidable retention: staging streams
  closed, native frees on all paths, single
  1 MiB copy buffer).
- Pinned-source finding (on-disk checkout, no .git
  so commit hash unverifiable): default params use
  LLAMA_LOAD_MODE_AUTO resolving to mmap on CPU
  (llama-model.cpp:3338, llama-model-loader.cpp:560,
  llama-model.cpp:1576-1585); no mlock. Weights are
  file-backed/lazily paged, NOT fully resident —
  the guard's modelBytes term is therefore
  conservative by construction, but NO reduction
  is approved: resident KV/compute/touched-page
  needs remain unmeasured (see owner Q below).
- Host evidence: `:app:testBenchmarkUnitTest`
  423/423 PASS (28 new `MemDiagTest`);
  `:app:assembleBenchmark` BUILD SUCCESSFUL
  (ai.edgefit.diag.bench, 8,907,069 bytes,
  2026-10-30; manifest/Gradle untouched).
  `git diff --check` clean. DB hash
  B36977AD…190FE9D unchanged; Microsoft/
  untouched; no commits/pushes.
- EF-MEM-02: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL REVIEW PENDING (owner-operated).

## EF-MEM-03 (advisory memory + recovery; HOST ONLY, no ADB)

- Owner directive (revised): the 2 GiB blocking rule is
  RETIRED as an admission gate; memory is advisory (warn
  below 15% available, stronger wording below 5%), Run
  never blocked by memory alone. No accepted ADR
  enshrined the old formula; NFR-THERM-01 revised
  accordingly (thermal/ABI/model guards retained).
- Root causes (Problems A-D): A = `BenchGuards`
  memory refusal (by design, now revoked). B/C/D share
  one systemic cause, NOT a click-handler defect (chain
  verified wired with feedback): `proposalGate`
  required non-empty comparable evidence, which needs
  a COMPLETED child — after failed/refused runs the
  gate closed, Request Proposal disabled, and FAILED
  states offered no next action: a dead end.
- Agent-executed implementation: new `MemPolicy`
  (15%/5%/unknown assessment) + `MemConditions` /
  `FailureEvidence` recorded on EVERY run
  (`x_mem_conditions`, `x_failure`); runner recheck
  path removed (MEM-01 `MemRecheck` kept for reading
  historical records); `MemReadiness`/`MemBudget`
  reframed advisory; benchmark line/dialog show
  percentage + warn-only Run; result screen shows
  memory-at-start + native-started/stage; recovery
  row (Recover/Models/Details) on every uncompleted
  run; typed `purpose=recovery` agent path (gate
  RECOVERY mode, `recovery_of_run_id` validation +
  host verification, prompt forbidding invented
  throughput, approval via `createRecovery` with
  fresh expected values, budget still charged);
  `SessionRecord` recovery_of_* link (schema v1);
  offline recovery assistant (local-labeled: bound
  retry same/smaller-ctx via new linked session +
  authorize + bind; unbound via standalone config
  override; agent children route to Agent Recovery,
  never an offline budget bypass); Sessions
  Recover action; batch-size control declined
  (JNI hardcodes n_batch=512 — needs a native
  change, owner decision); no process isolation
  (wholesale rewrite, out of scope).
- Host evidence: `:app:testBenchmarkUnitTest`
  462/462 PASS (21 new `MemPolicyTest`, 21 new
  `MemRecoveryTest`; MEM-01/02/UX regressions
  green); `:app:assembleBenchmark` BUILD SUCCESSFUL
  (ai.edgefit.diag.bench, 8,929,132 bytes,
  2026-10-30; manifest/Gradle untouched).
  `git diff --check` clean. DB hash
  B36977AD…190FE9D unchanged; Microsoft/
  untouched; no commits/pushes.
- EF-MEM-03: IMPLEMENTATION PASS. HOST PASS.
  PHYSICAL REVIEW PENDING (owner-operated).

## EF-SESSION-SCALE-01 (no count ceiling; HOST ONLY, no ADB)

- Owner physical evidence (MEM-03 build): 64/64
  tokens at ~11.9 tok/s, 24.7% RAM at start, old
  2 GiB block gone, result comparison-ready — but
  Agent approval AND recovery both failed with
  "session store full (50)" at 50 local sessions.
- Root cause: `SessionStore.save()` threw
  `StoreFull` for any new id once 50 files existed
  (`MAX_SESSIONS = 50`, code-only defensive bound
  from SESSION-01's generic "bounded count" rule;
  never an accepted ADR, never documented). Both
  approval and recovery create a new session, so
  both died on the same check.
- Agent-executed implementation: count ceiling
  REMOVED (no replacement cap); safety rests on
  retained per-record bounds (64 KiB records,
  256 KiB reads, 32 history entries, quarantine
  for corrupt files only) + explicit write-failure
  errors naming the record and recovery steps.
  Sessions list renders windowed (100 rows +
  Show-more; order/filter/Latest still global).
  Header shows free app-private bytes. Fixed the
  init tmp-sweep pattern (`.tmp` never matched
  real `<id>.json.tmp-<nanos>` names).
- Host evidence: `:app:testBenchmarkUnitTest`
  478/478 PASS (16 new `SessionScaleTest`: 51st
  creation, 100/500/1000 scale, approval +
  budget, recovery, repeat, standalone, no-auto-
  delete, collision, hash stability, quarantine,
  tmp sweep, explicit exhaustion error;
  listSessions(1000) probe 493 ms host);
  `:app:assembleBenchmark` BUILD SUCCESSFUL
  (ai.edgefit.diag.bench, 8,930,572 bytes,
  2026-10-30; manifest/Gradle untouched).
  `git diff --check` clean. DB hash
  B36977AD…190FE9D unchanged; Microsoft/
  untouched; no commits/pushes.
- EF-SESSION-SCALE-01: IMPLEMENTATION PASS.
  HOST PASS. PHYSICAL REVIEW PENDING
  (owner-operated).

## EF-AGENT-REVIEW-01 (fast recommendation review; HOST ONLY, no ADB)

- Owner product requirement: every Muse proposal
  and follow-up is challenged, validated, and
  presented BEFORE approval, with NO mandatory
  second Meta request. One authorized call carries
  bounded self-critique; EdgeFit reviews locally.
- Agent-executed implementation: proposal contract
  v2 adds optional bounded self-critique
  (counterargument, alternative_explanation,
  next_benchmark_establishes; v1 replies still
  validate); follow-ups gain an optional
  counterargument. New pure `AgentReview` engine
  returns SUPPORTED / CAUTION / INVALID from
  deterministic host checks (identity, bounds,
  purpose rules, citations, budget, evidence
  depth, contradictions, expected-value
  plausibility, memory pressure, modification
  staleness). Model self-critique displays
  labeled and never verifies. Review leads the
  proposal card; INVALID disables Approve in the
  UI and blocks fail-closed in the approve path
  (no budget charge on block); CAUTION stays
  explicitly approvable. Developer Modify edits
  pending optimize/recovery configs with host
  validation (repeats fixed; expected values
  flagged stale); no Meta call, no budget charge.
- Host evidence: `:app:testBenchmarkUnitTest`
  515/515 PASS (37 new `AgentReviewTest`: v2
  parse/reject matrix, verdict matrix incl.
  repeat/recovery/budget/config edges,
  modify rules, cards, determinism, review
  overhead probe avg < 10 ms);
  `:app:assembleBenchmark` BUILD SUCCESSFUL
  (ai.edgefit.diag.bench, 8,950,017 bytes,
  2026-10-30; manifest/Gradle untouched).
  EF-META-04 12/12 PASS. `git diff --check`
  clean. DB hash B36977AD…190FE9D unchanged;
  Microsoft/ untouched; no ADB, no Meta calls,
  no benchmarks, no commits/pushes.
- EF-AGENT-REVIEW-01: IMPLEMENTATION PASS.
  HOST PASS. PHYSICAL REVIEW PENDING
  (owner-operated).

## EF-CLOUD-01A (global evidence foundation; HOST ONLY, no deploys)

- Agent-executed implementation (`cloud/`,
  zero dependencies, Node 24): versioned backend
  foundation per ef-cloud-01 / ef-stats-01 —
  strict submission validation with itemized
  rejections, per-install token auth, explicit
  per-submission consent (aggregates scope only;
  runs scope rejected), content-hash dedup,
  quota + velocity guards, rule-driven
  SUBMITTED→VALIDATED→QUALIFIED→PUBLISHED→
  REVOKED eligibility with history, consent
  withdrawal tombstones, quorum-gated public
  aggregates (provisional 5 runs / 3 installs;
  thin cohorts return insufficient-evidence),
  observed-artifact models + candidate endpoints
  (no ranking implied), runs listing explicitly
  disabled (404 + reason), and a fixed
  allowlisted Muse retrieval query over
  quorum-passing PUBLISHED cohorts only (no raw
  rows, no install identities, no SQL
  passthrough). Plain SQLite/D1-compatible DDL;
  deployment swaps only `db.js`.
- Host evidence: `node --test` 38/38 PASS
  (isolated :memory: databases; synthetic
  fixtures only — never community data):
  submit/auth/quota/dedup/consent/owner-read/
  revoke, eligibility + anomaly holds
  (implausible tps, zero-token COMPLETED,
  tps/timing inconsistency, run_id collision,
  velocity flood), quorum + cohort separation
  (config/build/opt-status/device/workload),
  stats reconciliation, revocation/failed
  exclusion, determinism, allowlist + limit
  enforcement, SQL-injection inertness, type
  confusion, oversized input.
- NOT deployed: no Cloudflare resources, DNS,
  accounts, tokens, or community data. Open
  gates: deployment choice (ADR-009),
  D1 binding, token issuance ops, consent
  policy publication, quorum confirmation,
  runs-disclosure policy, device taxonomy.
- EF-CLOUD-01A: IMPLEMENTATION PASS.
  HOST PASS. DEPLOYMENT NOT STARTED
  (owner-gated).

## EF-COMP-01A Stage A (offline baseline + harness; HOST ONLY)

- Owner directive: CONDITIONAL APPROVAL, OFFLINE
  IMPLEMENTATION ONLY. No live calls, no
  benchmarks, no deploys, no main merge.
- Agent-executed implementation on
  `implement/ef-comp-01a-stage-a-2026-10-30`
  from 3999a84: `EvalBaseline` (deterministic
  rules-only arm R: host-validated ranking,
  exposed factors, insufficient-evidence
  abstention, null predictions when unmeasured),
  `EvalMetrics` (shared R/M0/M1 metrics with
  NOT_EVALUABLE + reason, strict
  prediction-before-outcome ordering, no
  zero-fill), `EvalArms` (M0/M1 evidence
  contracts, shared-candidate enforcement,
  production-gate validation, cost ledger;
  no live request constructed).
- Corpus inventory (verified read-only
  2026-10-30): 4 SHA-verified bench JSONs,
  all COMPLETED 512/4/64, same model +
  workload (Runs A/B/C/D); 3 SHA-verified
  profiles; DB integrity ok (1 profile,
  2 rows), hash unchanged; 3 live predictions
  documented phone-resident without exported
  artifacts or repo-recorded prediction
  timestamps. `eval/comp-01a-corpus.json` +
  `scripts/ef-comp-01a-manifest.mjs` validate
  this: 4 verified, 0 evaluable pairs, no
  config variation — regret/superiority
  NOT_EVALUABLE on history, by tool output.
- Host evidence: JVM suite green incl. 28 new
  eval tests; manifest validator 5/5
  (node:test fixtures); corpus run exit 0
  with the honest report. Reuses BenchConfig,
  BenchContract, AgentApi gate, SessionJson
  hashing; no parallel systems, no native
  changes, no cloud deps.
- EF-COMP-01A Stage A: IMPLEMENTATION PASS.
  HOST PASS. STAGE B NOT AUTHORIZED.

## EF-COMP-01A-R1 (evaluation correctness; HOST ONLY)

- Independent-review corrections on
  `implement/ef-comp-01a-r1-2026-10-30` from
  f7c90d3, offline only: (1) same-triple
  evidence AGGREGATES under one cohort key
  (was silent last-wins); mixed cohorts throw;
  permutation-invariant. (2) Citations check
  per-case authorized ids (was cross-case
  union). (3) `invalidProposalRate` split from
  `hostRejectionRate`, which needs recorded
  refusal evidence (fixture-only invalids stay
  NOT_EVALUABLE). (4) M1 admission requires
  `MeasurementProof` transcribed from the
  validated artifact; all 12 identity/status/
  temporal/triple checks verified, caller
  flags ignored. (5) Pack-hash freeze-timing
  caveat documented: Stage B must record pack
  hashes with pre-run timestamps.
- Stage B pre-registered in
  `docs/ef-comp-01b-stage-b.md` (A=512/4/64,
  B=512/8/64, order A-B-B-A-A-B, 2 sessions x
  3 approvals, 2 paid calls, go/no-go gates).
  NOT AUTHORIZED TO RUN.
- Host evidence: JVM suite green incl. 7 new
  R1 tests (aggregation, permutation,
  mixed-cohort, citation isolation,
  rejection evidence, proof matrix); no
  historical evidence mutated; no live calls.
- EF-COMP-01A-R1: IMPLEMENTATION PASS.
  HOST PASS. STAGE B STILL NOT AUTHORIZED.

## EF-COMP-01B-PREP (study bridge; HOST ONLY)

- Executable preflight on
  `implement/ef-comp-01b-prep-2026-10-30` from
  43b442b, offline only. Study decisions are a
  separate type from production proposals (no
  seq/budget/session path; production optimize
  change-gate and proposal evidence-gate
  untouched and re-tested).
- Real freeze of runs B/C/D (run-a Debug
  refused: unrecorded build): manifest +
  R decision (selects A, median 14.382 over 3)
  + M0/M1 request payloads persisted under
  `eval/comp-01b/`, verdict READY. M0 payload
  verified measurement-free; M1 cites all
  three runs.
- Rejection metric now coverage-gated: any
  unknown outcome vetoes the enforcement rate
  (counts in `hostRejection`).
- Run START ordering = run-id stamp (verified
  entry-captured in BenchRunner); strict
  decision < START enforced by
  `verifyRunOrdering`. Prospective scoring
  reuses EvalMetrics; abstention never scored
  as failed selection; exploratory labeling.
- AgentReview demo acceptance protocol in
  `docs/ef-comp-01b-agent-demo.md` (prepared,
  NOT executed).
- Host evidence: JVM suite green incl. 17 new
  PREP tests; corpus 4/4 verified, 0
  evaluable pairs, no variation; no live
  calls, no benchmarks, no DB writes.
- EF-COMP-01B-PREP: IMPLEMENTATION PASS.
  HOST PASS. LIVE CALLS + 6 RUNS STILL NOT
  AUTHORIZED.

## EF-COMP-01B-FINAL (study integrity gate; HOST ONLY)

- On `implement/ef-comp-01b-final-2026-10-30` from
  66d733f, offline only. Original freeze
  re-verified read-only (manifest/R/requests/
  verdict/evidence all clean; zero bytes
  changed). Output guard: existing studies
  immutable; new freezes need a unique
  instance id + new directory.
- Live adapter wraps qualified
  HttpsMetaTransport (fixed endpoint/model,
  no retry, key from env only, scrubbed
  errors); per-call EF_STUDY_AUTHORIZE=YES;
  frozen body re-hashed + bound to manifest;
  M0/M1 citation re-checks; timestamp read
  AFTER validation; one immutable decision
  per arm. Verify-only exercised against the
  real freeze; NO live call made.
- Qualified scoring ingests 6 exported JSONs
  from source bytes (contract/identity/
  completion/measurements/unique run ids)
  and enforces decision-before-START itself;
  missing/altered/inconsistent artifacts
  block. Metrics split 3 ways: one decision
  record, paired errors, one regret per arm
  (no cost/latency/regret multiplication).
- Physical route finding: standalone UI
  cannot select 8 threads (override offers
  only ctx reduction); the Agent +
  developer-Modify/repeat route executes
  A,B,B,A,A,B with all production gates
  intact (2 sessions x 3 approvals).
- Owner procedure in
  `docs/ef-comp-01b-owner-run.md` (12 steps +
  STOP conditions); demo protocol unchanged
  (prepared, not executed).
- Host evidence: JVM suite green incl. new
  Final tests; corpus/manifest/cloud green;
  no live calls, no benchmarks, no DB writes,
  no Agent changes.
- EF-COMP-01B-FINAL: IMPLEMENTATION PASS.
  HOST PASS. LIVE CALLS + 6 RUNS STILL NOT
  AUTHORIZED (separate owner go required).

## EF-COMP-01B-ROUTE (manual A/B execution; HOST ONLY)

- On `implement/ef-comp-01b-route-2026-10-30` from
  cf055c0, offline only. Confirmed gap: unbound
  runs always used the 4-thread default; only
  Agent children could run 8 threads.
- New bounded selector (Benchmark → Select
  Config): device default + Manual A/B, through
  production BenchConfig.validate + hardware
  thread max; exact pre-run Config line; bound
  sessions still win verbatim; selection never
  auto-runs; cleared on new model staging.
  Manual triples byte-equal the frozen study
  candidates (asserted in tests).
- Provenance: executed config == exported
  config through the single resolution funnel
  (tested); native stack byte-identical to the
  historical benchmark cohort (variant
  `benchmark`, requested `-O3 -g`, effective
  `-O2`); study APK SHA-256 `2813339C…5B46DE`
  (9,043,807 bytes). No new freeze needed.
- Runbook + protocol corrected to manual
  unbound runs (no repeat proposals, no Agent
  budget); API budget split study (2 calls) vs
  demo; Appendix R UI acceptance (not run).
- Host evidence: JVM suite green incl. 7 new
  ROUTE tests; benchmark APK builds; Agent/
  session/BYOK/native code untouched; no live
  calls, no benchmarks, no DB writes.
- EF-COMP-01B-ROUTE: IMPLEMENTATION PASS.
  HOST PASS. PHONE ACCEPTANCE + LIVE STUDY
  STILL NOT AUTHORIZED.
