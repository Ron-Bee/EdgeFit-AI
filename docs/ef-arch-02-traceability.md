# EF-ARCH-02 traceability matrix

Maps the 17 expanded product capabilities onto governing documents,
actual implementation, qualified evidence, gaps, v2 requirements, and
acceptance tests. Status labels: PROPOSED · IMPLEMENTED ·
HOST QUALIFIED · PHYSICALLY QUALIFIED · LIVE API QUALIFIED ·
DEPLOYED · PRODUCTION QUALIFIED · BLOCKED.

Nothing below is promoted for having tests or a design alone; each
status reflects the strongest evidence actually recorded in
`docs/qualification-status.md` or owner-confirmed device runs.

## A. Android profile collection

- Requirement/doc: EF-ANDROID-01; `android-profile-contract.md`.
- Implementation: EdgeFitDiag collector + serializer + SAF export.
- Qualified evidence: PHYSICALLY QUALIFIED (S22 Ultra export,
  `VALID: contract v1 profile`, owner-confirmed).
- Missing: nothing for v1 scope.
- v2 requirement: unchanged; profiles feed session store by hash.
- Acceptance test: existing contract suite + device export (done).
- Dependency: none (complete).

## B. Local GGUF model selection and validation

- Requirement/doc: EF-BENCH-01; `bench-build.md`.
- Implementation: SAF picker, cache staging, SHA-256 + size bound.
- Qualified evidence: PHYSICALLY QUALIFIED (two measured runs used
  staged model bytes; hashes recorded in evidence).
- Missing: in-app license/terms display before staging.
- v2 requirement: show model identity + license id pre-run (FR-DIST).
- Acceptance test: stage known GGUF, verify hash + bound refusal.
- Dependency: EF-ANDROID-UI-01 (display surface).

## C. Native llama.cpp inference

- Requirement/doc: EF-BENCH-01/02; pinned v0.7.0 CPU backend.
- Implementation: `bench_jni.cpp` load/prefill/generate, greedy,
  fixed workload, cooperative cancel, watchdog timeout.
- Qualified evidence: PHYSICALLY QUALIFIED (Run A/B COMPLETED on
  S22 Ultra; SIGABRT ownership fix verified by completed runs).
- Missing: nothing for v1 scope.
- v2 requirement: frozen methodology; tuning needs new contract.
- Acceptance test: COMPLETED run + VALID contract (done).
- Dependency: none (complete).

## D. Real-time benchmark progress and telemetry

- Requirement/doc: EF-ANDROID-UI-01 (new scope).
- Implementation: IMPLEMENTED, uncommitted — mailbox, sampler,
  execution states, dark screen, chart (`docs/bench-ui.md`).
- Qualified evidence: HOST QUALIFIED only (JVM 31/31, native
  compile, benchmark APK builds). Physical UI NOT RUN.
- Missing: on-device stage observation; overhead A/B measurement.
- v2 requirement: FR-UI-01 (this milestone's acceptance).
- Acceptance test: ordered stages on S22 Ultra + VALID export.
- Dependency: EF-ANDROID-UI-01 physical acceptance (BLOCKED on phone).

## E. Physical benchmark result validation

- Requirement/doc: `bench-result-contract.md` v1.
- Implementation: `ef_bench_result_01.py` offline validator.
- Qualified evidence: HOST QUALIFIED (19/19 + real Run A/B VALID).
- Missing: nothing for v1 scope.
- v2 requirement: unchanged; v2 workload needs contract v2 (ADR-012).
- Acceptance test: existing suite (done).
- Dependency: none (complete).

## F. Local private evidence ingestion

- Requirement/doc: EF-DATA-02/03; ADR-001, ADR-011, ADR-013.
- Implementation: profile + benchmark importers, migrations 2–3,
  read-only retrieval, offline A/B compare.
- Qualified evidence: HOST QUALIFIED + owner-executed imports
  (1 profile, 2 benchmarks, integrity-verified).
- Missing: nothing for v1 scope.
- v2 requirement: unchanged workstation store; feeds migration §16.
- Acceptance test: existing suites + import rehearsal (done).
- Dependency: none (complete).

## G. Meta physical-evidence retrieval

- Requirement/doc: EF-META-06 (offline).
- Implementation: `retrieve_physical_benchmarks` dispatcher,
  projection, host comparison, bounded live module.
- Qualified evidence: HOST QUALIFIED (39/39 mocked). LIVE API
  QUALIFIED: not run (owner authorization pending).
- Missing: live demonstration against the 2-row store.
- v2 requirement: live demo, then reuse pattern for BYOK evidence.
- Acceptance test: `ef_meta_06_live.py` PASS with grounding checks.
- Dependency: owner live authorization.

## H. Prediction-before-measurement persistence

- Requirement/doc: EF-LOOP-01 (workstation manifests).
- Implementation: `ef_loop_01.py` init/replay; manifest under
  `data/loop_runs/`; temporal ordering enforced on attach.
- Qualified evidence: HOST QUALIFIED (26/26 + labeled replay).
- Missing: Android on-device equivalent (session store).
- v2 requirement: Android private session store (Phase 3 contract).
- Acceptance test: manifest predates measurement; tamper refused.
- Dependency: EF-SESSION-01 (new, PROPOSED).

## I. Predicted-versus-observed error calculations

- Requirement/doc: EF-LOOP-01.
- Implementation: deterministic abs/pct errors from persisted values.
- Qualified evidence: HOST QUALIFIED (mocked chain + replay math).
- Missing: Android on-device computation; prospective live values.
- v2 requirement: same formulas on Android (FR-AGENT-03).
- Acceptance test: replayed errors match hand computation (done).
- Dependency: EF-SESSION-01 (Android side).

## J. Meta follow-up recommendations

- Requirement/doc: EF-LOOP-01 follow-up; EF-META-06 analysis.
- Implementation: bounded follow-up call, schema-validated JSON,
  mocked offline.
- Qualified evidence: HOST QUALIFIED only. No live follow-up yet.
- Missing: live recommendation quality review; Android hosting.
- v2 requirement: FR-AGENT-02/03 with budget + approval gates.
- Acceptance test: mocked schema tests (done); live review pending.
- Dependency: owner live authorization; EF-SESSION-01.

## K. User-approved experiment iteration

- Requirement/doc: none yet (new v2 scope).
- Implementation: none.
- Qualified evidence: none.
- Missing: experiment budget, approval UX, loop-closure rules.
- v2 requirement: `edgefit-agentic-workflow.md` (this milestone).
- Acceptance test: N approved runs then budget stop; no auto-run.
- Dependency: EF-AGENT-01 (new, PROPOSED).

## L. Android-hosted BYOK operation

- Requirement/doc: none yet (owner-approved this milestone).
- Implementation: none. No Keystore, key UI, or Android HTTPS
  Meta client exists.
- Qualified evidence: none.
- Missing: everything (key entry, storage, client, disclosures).
- v2 requirement: Mode A direct BYOK (Phase 2; ACCEPTED direction).
- Acceptance test: airplane-off loop on phone, key never in
  exports/logs; Forget Key verified.
- Dependency: EF-BYOK-01 (new); EF-SESSION-01.

## M. Android private session persistence

- Requirement/doc: master arch §6 (NOT STARTED).
- Implementation: none (JSON export only).
- Qualified evidence: none.
- Missing: session store, recovery, revocation/export behavior.
- v2 requirement: Phase 3 session-store contract (PROPOSED).
- Acceptance test: kill/restart resumes without loss or forgery.
- Dependency: EF-SESSION-01 (new, PROPOSED).

## N. Global evidence qualification

- Requirement/doc: EF-CLOUD-01 eligibility pipeline (PROPOSED).
- Implementation: none.
- Qualified evidence: none.
- Missing: backend, rules engine, quarantine, revocation.
- v2 requirement: reconciled contract (Phase 5).
- Acceptance test: staged SUBMITTED→PUBLISHED + revoke/tombstone.
- Dependency: EF-CLOUD-01 (PROPOSED).

## O. Public statistical aggregation

- Requirement/doc: EF-CLOUD-01 aggregates vs EF-STATS-01 cohorts.
- Implementation: none.
- Qualified evidence: none. The 2 S22 Ultra rows are NOT community
  statistics and must never be presented as such.
- Missing: methodology decision, quorum, device counting, API.
- v2 requirement: one reconciled stats methodology + API (Phase 5).
- Acceptance test: thin cohort → insufficient-evidence; else exact
  mean/median/n with fixtures.
- Dependency: EF-STATS-01 (PROPOSED); quorum/counting owner-approved
  provisional at acceptance, activation pending implementation.

## P. Global evidence retrieval by Meta

- Requirement/doc: ADR-008 (PROPOSED); cloud contract §Internal.
- Implementation: none (workstation EF-META-05/06 only).
- Qualified evidence: none at global scope.
- Missing: server dispatcher, pinned global value sets.
- v2 requirement: extend qualified patterns; aggregates only.
- Acceptance test: tool-cap + injection matrix on staging data.
- Dependency: EF-CLOUD-01.

## Q. Public model/device explorer

- Requirement/doc: master arch §15 (PROPOSED).
- Implementation: none.
- Qualified evidence: none.
- Missing: static site, cohort pages, methodology text, disclaimer.
- Acceptance test: renders quorum-gated fixtures; ADR-014 notice.
- Dependency: EF-WEB-01 (new, PROPOSED); EF-STATS-01.

## R. Signed APK distribution

- Requirement/doc: none yet (new v2 scope).
- Implementation: none. Existing APKs are debuggable benchmark
  builds, explicitly NOT release candidates.
- Qualified evidence: none.
- Missing: signing, release variant, GitHub Releases process.
- v2 requirement: `edgefit-release-and-distribution.md` (Phase 7).
- Acceptance test: clean-install + upgrade on S22 Ultra from
  release artifact + checksum match.
- Dependency: EF-DIST-01 (new, PROPOSED).

## S. Future Google Play publication

- Requirement/doc: none yet (new v2 scope).
- Implementation: none.
- Qualified evidence: none.
- Missing: Play policy review, listing, staged rollout plan.
- v2 requirement: upgrade path from direct APK (FR-DIST-02).
- Acceptance test: Play review acceptance (external).
- Dependency: EF-DIST-01; Play account + review (owner).

## T. Multi-device hardware compatibility

- Requirement/doc: bench guards baseline (mid-range intent).
- Implementation: guards (ABI, memory, thermal); arm64-v8a only.
- Qualified evidence: one physical device (S22 Ultra). No second
  device has run anything.
- Missing: second-device runs, compatibility matrix data.
- v2 requirement: FR-QUAL-01 matrix, per-device install evidence.
- Acceptance test: same APK + model on 2+ SoCs, VALID exports.
- Dependency: owner device access; EF-DIST-01 (installable APK).

## U. Final presentation and reproducibility

- Requirement/doc: EF-DEMO-01 (DEFERRED by owner).
- Implementation: none begun.
- Qualified evidence: none.
- Missing: recording, screenshots, submission (all future).
- v2 requirement: FR-DEMO-01 source-backed claims only.
- Acceptance test: every claim traces to this matrix.
- Dependency: all physical qualifications above.

## Summary counts

- PHYSICALLY QUALIFIED: A, B, C, E (bench path), F (imports).
- HOST QUALIFIED: D (impl), G, H, I, J.
- PROPOSED / NOT STARTED: K, L, M, N, O, P, Q, R, S, T (data), U.
- BLOCKED on phone/live: D physical, G live, J live, T data.
