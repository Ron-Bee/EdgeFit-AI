# EF-REPO-JUDGE-01 — Feature coverage matrix

Judge-experience gap audit for EdgeFit AI, 2026-11-01.
Branch `feature/ef-android-global-01-2026-11-01`.
Statuses use the governing vocabulary of
[qualification-status](qualification-status.md):

- **IMPLEMENTED** — code exists on this branch.
- **PHYSICALLY VERIFIED** — observed on a real Galaxy S22 Ultra
  (SM-S908U, Android 16); scope notes give the bound.
- **HOST PASS** — JVM/Python/Node suites green; no device observed.
- **PROPOSED** — design only, no implementation.

| # | Feature | Impl. | Physical qual. | Screenshot | Source | Test evidence | README | Missing / correction |
|---|---|---|---|---|---|---|---|---|
| 1 | Hardware-fragmentation story ("Why Android?") | Docs | n/a (narrative) | — | New README §Why Android | n/a | §A (this task) | Was implicit; now explicit |
| 2 | Device profiling | Yes | **Verified** — S22 profile exports | device-details.png | `DeviceCapabilityCollector.kt`, `ProfileContract.kt` | `test_ef_android_01.py`, JVM suite | §E/§G | None |
| 3 | Live telemetry | Yes | **Verified** — UX-01E S22 crawl | device-details.png (live section) | `DeviceTelemetry.kt` | JVM suite | §E | None |
| 4 | Memory + thermal readiness | Yes | **Partial** — advisory % observed on S22 (MEM-03); thermal refusal seen in UX-01BC | benchmark-execution.png | `MemPolicy.kt`, `BenchGuards.kt` | `MemPolicyTest`, JVM suite | §E | Full MEM-03 physical review pending |
| 5 | GGUF staging + identity (SHA-256) | Yes | **Verified** — staged + hashed on S22 | benchmark-setup.png | `stageModel()` in `MainActivity.kt` | JVM suite, contract tests | §E | None |
| 6 | Offline model recommendations | Yes | Host only — physical review pending | model-recommendations.png | `ModelCatalog.kt`, `ModelRecommend.kt` | JVM suite (MODEL-01A/B) | §D | On-device review capture |
| 7 | Model compatibility estimates | Yes (labeled ESTIMATED) | Unverified by design | model-recommendations.png | `ModelSuitability.kt` | JVM suite | §D | None — honesty is the feature |
| 8 | Configuration selection (incl. Manual A/B) | Yes | **Verified** — 6-run study ran A/B manually | benchmark-execution.png | `BenchConfig.kt`, `ManualBenchConfig.kt` | ROUTE tests, JVM suite | §E | None |
| 9 | Native llama.cpp benchmarking | Yes (pinned v0.7.0) | **Verified** — real S22 inference | benchmark-result.png | `LlamaBridge.kt` + JNI | Native ownership test | §E/§G | None |
| 10 | Performance measurements (load/prefill/gen) | Yes | **Verified** — COMPLETED runs on S22 | benchmark-result.png | `BenchRunner.kt`, `BenchResultJson.kt` | Contract suites | §G | None |
| 11 | Predicted-vs-measured comparison | Yes (host-computed) | **Verified, scoped** — exp-4 (+14.8%), comp-01b | sessions.png | `SessionComparison.kt` | JVM suite, `study-score.json` | §G | None |
| 12 | Sessions + historical evidence | Yes | **Verified** — ledger on S22 | sessions.png | `SessionStore.kt`, `SessionManager.kt` | JVM suite incl. scale tests | §E | None |
| 13 | Repeat measurements | Yes | Host only — physical review pending | — (series view unphotographed) | FOLLOWUP-02 paths | JVM suite | §E | Genuine repeat-series capture |
| 14 | Meta Model API BYOK | Yes (Keystore, session-only) | **Verified, scoped** — exp-4 + comp-01b live calls | meta-byok.png | `MetaKeyVault.kt`, `MetaHttp.kt` | EF-META-04 12/12 | §F | None |
| 15 | Muse Spark predictions | Yes | **Verified, scoped** — 1 BYOK session; M0/M1 abstentions | meta-byok.png | `MetaSessionClient.kt`, `AgentApi.kt` | META-05/06 live quals | §F | None |
| 16 | Muse Spark follow-ups | Yes | **Verified, scoped** — exp-4 follow-up on file | — (interpretation card unphotographed) | Follow-up paths | JVM suite | §F | Genuine follow-up card capture |
| 17 | Bounded agent proposals | Yes (5-attempt budget) | **Verified, scoped** — exp-1..exp-4 loop | agent-view.png | `AgentManager.kt`, `AgentEvidence.kt` | JVM suite | §F | Proposal-detail capture |
| 18 | Modify / Approve / Reject / Finish | Yes | **Approve+Finish verified**; Reject + Modify host-only | agent-view.png | `AgentView.kt`, `AgentReview.kt` | `AgentReviewTest` (37) | §F | Reject + Modify device captures |
| 19 | Experiment budgets + validation | Yes | Scoped physical + host | agent-view.png | `AgentManager.kt` budget paths | JVM suite | §F | None |
| 20 | JSON exports + provenance | Yes (contract v1) | **Verified** — S22 exports validate | — (file artifact, linked) | `SessionJson.kt`, `ProfileSerializer.kt` | `ef_bench_result_01.py` etc. | §H | None — link suffices |
| 21 | Persistent SQLite evidence | Yes (additive migrations) | **Qualified** — owner imports confirmed | — (workstation artifact) | `scripts/ef_data_*.py`, `data/` | DATA-02/03 suites | §H | None — link suffices |
| 22 | Tool-calling validators | Yes (fixed-schema, pre-access) | Host only (by construction) | — | `ef_dispatch_05/06.py` | META-05/06 suites | §F | None |
| 23 | Muse Code-assisted test harness | Yes | Host: 634 JVM + Python + 38 cloud | — | `android/`, `scripts/`, `cloud/` | Suites green | §H | Re-run totals before publish |
| 24 | Global contribution + consent | Yes (client; consent gate default OFF) | Backend live; first genuine upload NOT RUN | global-contribute.png, settings.png | `GlobalClient.kt`, `GlobalView.kt` | JVM suite (fake transport) | §I | P0 upload/receipt/revoke (needs consent approval) |
| 25 | Global backend qualification | Yes (deployed @ `cba27c3`) | **DEPLOYED**, 297/297 re-run; zero genuine rows | — (API JSON, linked) | Muse B `cloud/` (3 Workers + D1) | 297/297 PASS | §I | Consent approval + P0–P3 |
| 26 | Public statistics + evidence explorer | Yes (dashboard live) | **DEPLOYED, honestly empty** — zero cohorts | — (live site, linked; screenshot pending) | Website branch @ `8d41a61` | 58+12+5 per reports | §I | Genuine quorum (P3) + dashboard screenshot |
| 27 | Privacy + key security | Yes (Keystore, consent scopes) | Scoped physical + host | meta-byok.png, settings.png | `MetaKeyVault.kt`, `GlobalTokenVault.kt` | JVM suite | §F/§P | None |
| 28 | Direct APK distribution | **Proposed only** | None — no public-preview build | — (correctly absent) | `edgefit-release-and-distribution.md` | Release gates unrun | §J/§K | Signed build (owner-gated) |

## Honesty summary

- Physically verified (full or scoped): features 2–5, 8–12, 14, 15,
  17–19 (partial), 20, 21, 27.
- Host-only: 6, 7 (by design), 13, 16, 18 (Reject/Modify),
  22 (by construction).
- Deployed but empty (zero genuine rows): 24 (upload pending), 25, 26.
- Proposed / absent: 28.
- No feature above is claimed beyond its row. Items marked
  "correctly absent" must stay unlinked until they exist.
