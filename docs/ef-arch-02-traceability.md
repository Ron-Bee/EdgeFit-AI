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
- Implementation: IMPLEMENTED, committed — mailbox, sampler,
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

- Requirement/doc: `edgefit-agentic-workflow.md`; FR-AGENT-02.
- Implementation: EF-AGENT-01 IMPLEMENTED, committed bf56878
  incl. FIX/FIX2 (agent parent/child sessions, evidence
  packs, proposal validation, config bridge, approval UX,
  budget/termination).
- Qualified evidence: HOST QUALIFIED (JVM 202/202;
  benchmark APK builds) + PHYSICAL SCOPED (S22: live
  proposals, approved exp-4 run attached/compared/followed
  up, FIX2 same-second verified, budget exact, rejections
  preserved). Termination paths (stagnation, invalid
  strikes, reject) NOT RUN live.
- Missing: live termination-path coverage.
- v2 requirement: within-model optimization loop (this scope).
- Acceptance test: N approved runs then budget stop; no auto-run.
- Dependency: termination-path live coverage (future).

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

## V. Compatible model discovery (EF-MODEL-ARCH-01, PROPOSED)

- Requirement/doc: FR-MODEL-01; master arch §3a, §5a.
- Implementation: none. No catalog client, no screening logic.
- Qualified evidence: none. No multi-model on-device testing exists.
- Missing: catalog contract implementation, host screener, UI.
- v2 requirement: 3–5 host-screened candidates with stated basis.
- Acceptance test: FR-MODEL-01 matrix (unsupported quant/arch,
  RAM, hash, license, duplicates, stale, no-candidate).
- Dependency: EF-CLOUD-01 catalog; future EF-MODEL-* milestone.

## W. Comparative prediction and benchmark handoff (EF-MODEL-ARCH-01, PROPOSED)

- Requirement/doc: FR-MODEL-02, FR-MODEL-03; NFR-MODEL-01.
- Implementation: none.
- Qualified evidence: none. No public cohort data exists.
- Missing: comparison schema, host validation, handoff gates.
- v2 requirement: side-by-side predictions with provenance;
  explicit staged-artifact handoff, prediction first.
- Acceptance test: mixed/no-evidence fixtures; citation and
  identity rejection; one authorization → one run.
- Dependency: V; EF-STATS-01; future EF-MODEL-* milestone.

## X. Published evidence retrieval (EF-MODEL-ARCH-01, PROPOSED)

- Requirement/doc: FR-EVID-01 (Mode A), FR-EVID-02 (Mode B);
  ADR-019 (PROPOSED); cloud contract candidate endpoint.
- Implementation: none. The built BYOK client contacts Meta only.
- Qualified evidence: none. Backend undeployed.
- Missing: public endpoint, host retrieval + validation, audits.
- v2 requirement: anonymous bounded reads (Mode A); governed
  internal ops (Mode B); no below-quorum leakage either path.
- Acceptance test: FR-EVID-01/02 matrices incl. redirect,
  revoked/thin, oversize, and key-confinement inspection.
- Dependency: EF-CLOUD-01; future EF-MODEL-* milestone.

## Y. UX overhaul (EF-UX-REQ-01, PROPOSED)

- Requirement/doc: FR-UX-01; master arch §18 amendment.
- Implementation: none. Current main screen is diagnostic-style
  (owner baseline ~3/10, subjective); all function reachable
  through it stays the implementation reference.
- Qualified evidence: none.
- Missing: navigation/information design, main-experience
  build, reachability + provenance acceptance on device.
- v2 requirement: discoverable §18 journey with progressive
  disclosure; FR-UI-01/02 detail preserved; no toolkit mandated.
- Acceptance test: scripted §18 reachability walk; artifact
  spot-checks; disabled-state and a11y checks (FR-UX-01).
- Dependency: EF-AGENT-01 physical qualification (stable
  reference behavior); future EF-UX-01 milestone.

## Z. Onboarding (EF-UX-REQ-01, PROPOSED)

- Requirement/doc: FR-ONBOARD-01; session semantics per
  EF-SESSION-01 (`SessionState`, durable store records).
- Implementation: none.
- Qualified evidence: none.
- Missing: three-topic skippable intro, completion predicate
  over durable records (top-level + COMPLETED + comparable;
  agent parents once, children never; standalone runs have no
  record and do not count), retirement + relaunch persistence.
- v2 requirement: offline, no account/key/downloads/calls/runs;
  auto-retire at three qualifying completions; Skip permanence
  is an OPEN OWNER DECISION (product requirements §Open owner
  decisions); replay counting likewise open.
- Acceptance test: offline walk, skip path, retirement across
  relaunch, non-counting matrix (FR-ONBOARD-01).
- Dependency: Y (surfaces); EF-SESSION-01 (semantics exist).

## AA. Report import and attachment (EF-UX-REQ-01, PROPOSED)

- Requirement/doc: FR-RPT-01; EF-APP-AUDIT-01 gap; ADR-011,
  ADR-013; bench-result contract v1.
- Implementation: exporter exists (SAF save); importer,
  validator reuse for inbound files, and attach/bind flows
  do not exist.
- Qualified evidence: none.
- Missing: separate import milestone (contract validation,
  explicit attach/bind by model/workload/config identity,
  duplicate refusal, imported-vs-local labeling, hash
  preservation), then UI integration.
- v2 requirement: gated import that can never self-verify,
  self-qualify, or self-publish.
- Acceptance test: export→import→attach round trip with hash
  equality; refusal matrix (FR-RPT-01).
- Dependency: Y (exposure surfaces) — ordering of Y-build vs
  AA-build is an OPEN OWNER DECISION; neither absorbs the
  other.

## Pre-submission roadmap (EF-UX-REQ-01, PROPOSED sequence)

Owner-requested order with dependency gates; cloud (EF-CLOUD-01),
stats (EF-STATS-01), web (EF-WEB-01), and multi-model comparative
prediction stay in the architecture and long-term roadmap,
unchanged in status:

A. EF-AGENT-01 physical qualification + approved Git checkpoint
   (FIX2 host-qualified; needs physical attach/comparison
   evidence; rejected records preserved) → unblocks B, K.
B. EF-UX-01 requirements/design preparation (this amendment
   plus navigation design against the real app) → unblocks C.
C. EF-UX-01 implementation (Y: main experience; Z: onboarding
   + retirement; provenance retained) → unblocks E.
D. Report import/attachment milestone (AA: validation, explicit
   attach/bind, integrity tests, UI integration) → unblocks E.
   C-vs-D order is dependency-reviewed and owner-decided.
E. Full physical-device functional crawl + usability acceptance
   (S22 Ultra via ADB preferred; emulator for isolated/
   destructive cases): reachability, onboarding persistence +
   retirement, report binding, post-UI agent loop, offline,
   errors, persistence → unblocks F.
F. Security + distribution qualification (audit findings,
   key custody/privacy, EF-DIST-01 signing gates; debug vs
   release distinguished) → unblocks G.
G. EF-DEMO-01 (qualified-functionality recording only; claims
   traced to this matrix; undeployed systems never shown as
   operational).

## Summary counts

- PHYSICALLY QUALIFIED: A, B, C, E (bench path), F (imports),
  K (scoped loop).
- HOST QUALIFIED: D (impl), G, H, I, J.
- PROPOSED / NOT STARTED: L, M, N, O, P, Q, R, S, T (data), U,
  V, W, X, Y, Z, AA.
- BLOCKED on phone/live: D physical, G live, J live,
  K termination paths, T data.
