![EdgeFit AI — Hardware-Aware AI. Measured on Your Device.](assets/brand/edgefit-hero-1600.png)

# EdgeFit AI

<div align="right">
  <strong>JUDGE QUICK ACCESS</strong><br>
  <strong>Download Android APK:</strong> <em>Pending signed public release</em> · <a href="docs/release-readiness.md">Release gate</a><br>
  <strong>Watch demo:</strong> <em>Pending final video</em> · <a href="docs/demo.md">Demo guide</a><br>
  <strong>Project website:</strong> <a href="https://edgefit-ai.pages.dev/">EdgeFit AI</a><br>
  <strong>Global Evidence dashboard:</strong> <a href="https://edgefit-ai.pages.dev/dashboard.html">Explore dashboard</a><br>
  <strong>Full walkthrough PDF:</strong> <a href="docs/walkthrough/v1.0/EdgeFit_AI_Definitive_Walkthrough_v1.0.pdf">Definitive Walkthrough v1.0 (PDF)</a> · <a href="docs/walkthrough/v1.0/README.md">Guide</a><br>
</div>

**Will this AI model run well on *your* phone? EdgeFit AI answers with measurements, not guesses — Meta Muse Spark predicts on-device inference speed, the phone measures it, and every claim stays traceable to evidence.**

Built for the **2026 Meta Global AI Developer Hackathon**.

> **Status snapshot (2026-11-01):** on-device benchmark + prediction sessions are implemented and physically demonstrated on a Galaxy S22 Ultra; the **Global Evidence backend is deployed and the public dashboard is live** (honestly empty — zero genuine publications yet). No demo-video link is published yet. No signed public APK exists yet. See [Limitations](#p-limitations-known-issues-and-roadmap).

**Quick access:** The walkthrough PDF is published (see Judge Quick Access above). The APK download will become active only after a verified release artifact exists. The direct website and dashboard links above are available for inspection; public aggregate data is not yet qualified.

> **Status update (2026-11-03):** Definitive Walkthrough v1.0 published — the illustrated technical guide (40 genuine screenshots, 16 technical diagrams) as a searchable 65-page PDF plus the editable Word master: [docs/walkthrough/v1.0/](docs/walkthrough/v1.0/README.md). No demo-video link is published yet. No signed public APK exists yet.


## A. Why Android? Hardware fragmentation is the problem

Android devices vary enormously in CPU, core count, RAM, storage, and thermal characteristics. A model that performs well on one Android configuration may be slow, throttled, or unloadable on another — and nothing in the model file tells you which. Vendors publish no per-device inference numbers.

EdgeFit AI addresses that problem using hardware profiling, candidate model recommendations, compatibility estimates, Meta Muse Spark predictions, native inference benchmarks, measured performance comparisons, persistent scientific evidence, agentic optimization, and the Global Evidence architecture — turning each phone into its own measurement lab. One honest measurement on your hardware beats a hundred generic estimates.

## B. Problem and solution

- **Problem:** choosing an on-device LLM config (model × context × threads × tokens) is guesswork; generic benchmarks don't transfer across the Android hardware zoo.
- **Solution:** profile → recommend → predict → authorize → benchmark → compare → retain → contribute. Each step is explicit, authorized, and evidence-bound; nothing uploads silently, nothing measures silently.

## C. Product screenshot gallery

Eleven genuine Samsung Galaxy S22 Ultra captures (Android 16), presented in Aero showcase panels (system status bars cropped; app pixels untouched). Click any panel to inspect the unaltered original. Benchmark values are device- and run-specific; recommendations are unverified until measured. [File hashes and provenance](docs/screenshot-gallery-2026-11-01.md) · [Presentation-provenance record](assets/screenshots/presentation/PROVENANCE.md).

#### Featured showcase

<a href="assets/screenshots/2026-11-01/edgefit-ss-01-home-overview.png"><img src="assets/screenshots/presentation/edgefit-panel-01-home.png" alt="Home Dashboard showcase panel" /></a>

#### Hardware and model selection

<a href="assets/screenshots/2026-11-01/edgefit-ss-02-device-profile.png"><img src="assets/screenshots/presentation/edgefit-panel-02-device.png" alt="Device Profile showcase panel" /></a>

<a href="assets/screenshots/2026-11-01/edgefit-ss-04-model-recommendations.png"><img src="assets/screenshots/presentation/edgefit-panel-04-recommend.png" alt="Model Recommendations showcase panel" /></a>

<a href="assets/screenshots/2026-11-01/edgefit-ss-06-model-catalog.png"><img src="assets/screenshots/presentation/edgefit-panel-06-catalog.png" alt="Model Catalog showcase panel" /></a>

#### Real inference

<a href="assets/screenshots/2026-11-01/edgefit-ss-09-benchmark-running.png"><img src="assets/screenshots/presentation/edgefit-panel-09-running.png" alt="Live Benchmark showcase panel" /></a>

<a href="assets/screenshots/2026-11-01/edgefit-ss-10-benchmark-results.png"><img src="assets/screenshots/presentation/edgefit-panel-10-results.png" alt="Measured Results showcase panel" /></a>

<a href="assets/screenshots/2026-11-01/edgefit-ss-12-session-history.png"><img src="assets/screenshots/presentation/edgefit-panel-12-sessions.png" alt="Session History showcase panel" /></a>

#### Meta AI intelligence

<a href="assets/screenshots/2026-11-01/edgefit-ss-13-muse-spark-prediction.png"><img src="assets/screenshots/presentation/edgefit-panel-13-predict.png" alt="Muse Spark Prediction showcase panel" /></a>

<a href="assets/screenshots/2026-11-01/edgefit-ss-14-muse-prediction-vs-measurement.png"><img src="assets/screenshots/presentation/edgefit-panel-14-compare.png" alt="Prediction vs Measured showcase panel" /></a>

<a href="assets/screenshots/2026-11-01/edgefit-ss-22-agent-proposal-review.png"><img src="assets/screenshots/presentation/edgefit-panel-22-agent.png" alt="Agent Proposal Review showcase panel" /></a>

#### Repeatable scientific evidence

<a href="assets/screenshots/2026-11-01/edgefit-ss-25-sessions-repeat-series-compare.png"><img src="assets/screenshots/presentation/edgefit-panel-25-repeat.png" alt="Repeat-Series Compare showcase panel" /></a>

### Judge's evidence map — Indie developer track

The [official hackathon overview](https://cerebralvalley.ai/e/meta-ai-developer-hackathon) describes the Indie track in terms of **creativity, technical ambition, and whether it works**. This repository is organized so each claim has something a judge can inspect:

| What judges should assess | EdgeFit evidence |
|---|---|
| **Creativity / user impact** | [Problem and solution](#b-problem-and-solution), [hardware-aware recommendations](#d-model-recommendations), device-first use case |
| **Meta AI use** | [Muse Spark's prediction, follow-up and bounded proposals](#f-meta-ai-capabilities), [integration details](docs/muse-integration.md) |
| **Technical ambition / depth** | [Architecture](#l-architecture-diagram-and-technical-stack), [native harness and tests](#h-test-harness-and-engineering-achievements), [measured evidence](#g-scientific-methodology-and-measured-findings) |
| **Working implementation / demo** | [Physical screenshots](#c-product-screenshot-gallery), [reproduction guide](#n-reproduction-instructions), [demo instructions](docs/demo.md), [qualification limitations](#p-limitations-known-issues-and-roadmap) |
| **Transparent scale claims** | [Global Evidence status](#i-global-evidence-system-deployed-honestly-empty), [provenance](docs/evidence-methodology.md); no invented multi-device publications |

All screenshots are genuine device captures. Model-compatibility estimates are unverified until measured; public Global aggregates should not be claimed until qualified contributions exist.

## D. Model recommendations

A major EdgeFit feature: the offline GGUF catalog recommends candidate models for *your measured hardware* — weighing compatibility (ABI, runtime), model size against device RAM/storage constraints, and estimated performance with explicit uncertainty. Every card carries provenance (catalog version, source) and states what is known vs guessed.

Crucially, the UI separates **offline catalog recommendations** from **verified physical benchmarks**: compatibility guesses are labeled ESTIMATED / unverified until a real benchmark exists, and measured values are never pooled with estimates. Not every recommended model has been physically benchmarked — the app tells you exactly which have. See [the recommendations capture](assets/screenshots/model-recommendations.png) and [evidence showcase](docs/evidence-showcase.md).

## E. How EdgeFit works

```
profile → recommend → predict → authorize → benchmark → compare → retain → contribute
```

1. **Profile** — platform-reported specs (model, CPU, RAM, ABI, API level) collected on-device; no permissions, no network.
2. **Recommend** — offline catalog suggests GGUF models with explicit unverified estimates.
3. **Predict** — Muse Spark predicts throughput from device + model + config + bounded prior evidence.
4. **Authorize** — every paid or executing step shows what will be sent and costs; nothing auto-runs.
5. **Benchmark** — real llama.cpp inference on the phone; load, prefill, generation tok/s with thermal/memory conditions per run.
6. **Compare** — host computes predicted-vs-measured error deterministically from persisted records.
7. **Retain** — sessions persist in the on-device ledger; exports are contract-v1 JSON; workstation SQLite keeps additive history.
8. **Contribute** — consent-gated per-submission review uploads to the deployed Global backend (client ready; first genuine upload not yet run).

## EdgeFit AI — How It Works

Visual companion to §E above: five source-grounded diagrams from the [Definitive Walkthrough v1.1](docs/walkthrough/v1.1/README.md) ([PDF](https://ronbeesoftware.com/downloads/EdgeFit_AI_Definitive_Walkthrough_v1.1.pdf) · [walkthrough page](https://ronbeesoftware.com/edgefit/walkthrough/)). No interfaces or measurements were fabricated. Figure numbers below are v1.1 numbers; the small `FIG NN` labels baked inside some graphics are the original master identities (see the [asset manifest](docs/assets/visual-showcase/v1.1/README.md)).

### 1. System architecture (Walkthrough Fig 02)

<a href="docs/assets/visual-showcase/v1.1/edgefit-fig-02-system-architecture.svg"><img src="docs/assets/visual-showcase/v1.1/edgefit-fig-02-system-architecture.svg" width="800" height="500" alt="EdgeFit AI system architecture: on-device app and native inference, optional Meta Muse Spark prediction, governed Cloudflare backend, public dashboard"></a>

Everything starts on the phone: the Android app profiles hardware, runs native llama.cpp benchmarks, and keeps evidence in a local hashed store. Meta Muse Spark (external, optional, owner-keyed) predicts throughput and proposes bounded experiments — estimates are never recorded as measured evidence. Consented contributions flow to the governed Cloudflare backend (ingest → D1 → qualification → quorum → publication gate B, currently OFF); only quorum-satisfied aggregates reach the public dashboard, and zero are published yet.

<details>
<summary><b>2. Native benchmark pipeline (Walkthrough Fig 26)</b> — preflight, execution, measurement, outcomes</summary>

<a href="docs/assets/visual-showcase/v1.1/edgefit-fig-26-benchmark-pipeline.svg"><img src="docs/assets/visual-showcase/v1.1/edgefit-fig-26-benchmark-pipeline.svg" width="800" height="500" alt="Native benchmark pipeline: preflight guards, worker thread with load prefill generate assemble, watchdog, terminal outcomes"></a>

BenchRunner guards every run: preflight checks (model staged, memory, thermal) run before one worker thread performs load → prefill → generate → assemble under a watchdog, producing exactly one BenchResult per run with distinct terminal outcomes (success, cancelled, failed, refused). Throughput is measured generation tokens/sec with per-run conditions — never estimated.

</details>

<details>
<summary><b>3. Meta Muse Spark agent loop (Walkthrough Fig 43)</b> — bounded proposals, validation, owner approval</summary>

<a href="docs/assets/visual-showcase/v1.1/edgefit-fig-41-agent-lifecycle.svg"><img src="docs/assets/visual-showcase/v1.1/edgefit-fig-41-agent-lifecycle.svg" width="800" height="500" alt="Muse Spark bounded agent lifecycle: model proposes, host validates, owner approves each run, device measures"></a>

Muse Spark works inside a five-attempt budget: the model proposes an experiment, the host validates it against a fixed schema (SUPPORTED / CAUTION / INVALID, fail-closed at zero budget cost), and the owner approves, modifies, or rejects each run before the phone measures it. Follow-up interpretations cite run ids only — and the model correctly abstained rather than guess beyond the evidence in the six-run study.

</details>

<details>
<summary><b>4. Global Evidence Network (Walkthrough Fig 50)</b> — consent to publication, every gate explicit</summary>

<a href="docs/assets/visual-showcase/v1.1/edgefit-fig-48-global-network.svg"><img src="docs/assets/visual-showcase/v1.1/edgefit-fig-48-global-network.svg" width="800" height="500" alt="Global evidence pipeline: consent, ingestion, quorum, publication gate and revocation"></a>

Consent → ingestion → validation → qualification → exact-cohort quorum (≥5 eligible runs from ≥3 installs) → Publication Approval B → public aggregate. Accepted ≠ qualified ≠ published: a submission receipt is not evidence of qualification, and with gate B OFF and zero genuine publications, the dashboard's empty state is the correct output.

</details>

<details>
<summary><b>5. Exploratory device performance (Walkthrough Fig 53)</b> — measured S22 Ultra study, honestly small</summary>

<a href="docs/assets/visual-showcase/v1.1/edgefit-fig-51-study-chart.svg"><img src="docs/assets/visual-showcase/v1.1/edgefit-fig-51-study-chart.svg" width="800" height="500" alt="Measured generation throughput chart: candidate A 4 threads median 16.52, candidate B 8 threads median 10.49, n=3 each, exploratory"></a>

Galaxy S22 Ultra, fixed workload, exploratory six-run study: 4-thread median **16.52 tok/s** (range 15.28–16.66, n=3) vs 8-thread median **10.49 tok/s** (range 6.82–13.38, n=3) — more threads measured *slower* on this device/workload. Bars are medians, whiskers observed ranges; the chart states NOT significant. Single device, generation only, no fleet claims. Full method: [§G](#g-scientific-methodology-and-measured-findings).

</details>

## F. Meta AI capabilities

Three distinct roles, each independently validated by host checks:

- **Muse Code (development):** assisted the 634-test JVM harness, Python contract suites, agent-review engine, and study scaffolding — every milestone test-gated and host-verified.
- **Muse Spark (runtime):** `muse-spark-1.3` via `https://api.meta.ai/v1/responses`, behind explicit authorization: (1) throughput prediction with uncertainty, stored *before* the benchmark; (2) grounded follow-up citing run ids only; (3) bounded agent proposals (5-attempt budget), each locally reviewed (SUPPORTED / CAUTION / INVALID) with no mandatory second paid call.
- **Host validators (trusted boundary):** the model proposes; the host validates, authorizes, executes, and scores. Workstation dispatchers validate tool arguments against a fixed schema *before* any database access — one fixed parameterized read-only query, deterministic order, bounded limit. Model input never becomes SQL. Invalid proposals are blocked fail-closed at zero budget cost. Evidence conditions inference in-context; weights are never updated.

**Model limitations (observed):** in the six-run study both Muse arms correctly **abstained** rather than guess beyond the evidence (fabricated-citation rate 0.0). Abstention is a correct answer, never a failed selection.

Details: [docs/muse-integration.md](docs/muse-integration.md).

## G. Scientific methodology and measured findings

Headline (exploratory, n=3/config, one device): candidate A (4 threads) median **16.52 tok/s**; candidate B (8 threads) median **10.49 tok/s** — more threads measured *slower* on this device/workload. Prediction-before-measurement ordering enforced by timestamp; frozen SHA-256 evidence manifests; per-run thermal/memory conditions; host-computed errors; single-device honesty throughout.

Full claim→artifact map: [docs/evidence-showcase.md](docs/evidence-showcase.md). Results: [docs/validation-results.md](docs/validation-results.md). Method: [docs/evidence-methodology.md](docs/evidence-methodology.md).

## H. Test harness and engineering achievements

- **Android JVM:** 634/634 PASS (sessions, agent review, memory policy, onboarding, catalog, eval arms).
- **Python offline suites** (stdlib only, isolated temp DBs): data, bench, meta, loop, session parity — all green.
- **This branch's cloud foundation:** 38/38 PASS (isolated in-memory DBs, synthetic fixtures only).
- **Deployed backend** (Muse B branch @ `cba27c3`, independently re-run from archive): **297/297 PASS**.
- **Website:** 58/58 + 12/12 + 5/5 PASS per website-workstream reports (not re-run here — no test files on the website branch).
- **PowerShell validators:** EF-META-04 12/12; science + safe-install suites green.
- **Study tooling:** frozen corpus manifests, pre-registered protocols, source-backed scoring.
- Native llama.cpp v0.7.0 pinned + reviewed; ownership test guards the JNI boundary.

Re-run everything offline: [Local development](#o-local-development-and-reproducible-tests).

## I. Global Evidence System (deployed, honestly empty)

The Global backend is **deployed on Cloudflare Free** (three Workers + one D1 database) and the public dashboard is **live**:

| Service | URL | Role |
|---|---|---|
| 📊 Dashboard | [edgefit-ai.pages.dev/dashboard.html](https://edgefit-ai.pages.dev/dashboard.html) | Public evidence dashboard (empty state) |
| 📖 Public reader | `edgefit-evidence-public.ronbaute92.workers.dev` | Anonymous GET-only aggregates/models |
| 📥 Ingest gateway | `edgefit-evidence-ingest-demo.ronbaute92.workers.dev` | Per-install authenticated uploads |

**Architecture:** phone benchmark → explicit per-batch consent → per-install bearer auth → ingest gateway → validation → server-assigned origin → D1 → scheduled qualification (10-row cron) → exact-cohort quorum → publication allowlist → public reader → dashboard. Staging internals stay nonpublic; the public reader enforces a 120/60s rate limit.

**Methodology:** a cohort matches exactly on device class, model SHA-256, quantization, runtime, workload + prompt identity, context, threads, tokens, sampler, optimization, and build provenance. Publication needs **≥5 eligible runs from ≥3 distinct installs in the same exact cohort** — SM-S908U and SM-S906U are different cohorts, never pooled. Installs ≠ attested devices. Synthetic/legacy rows are permanently ineligible.

**Current status (2026-11-01): zero genuine publications.** 19 legacy/synthetic test rows, 0 `device_contribution` rows, empty allowlist, consent `ef-consent-01` PROPOSED not approved, first genuine upload (P0) not run. The dashboard's empty charts and `artifacts:[]` API responses are the *correct* output — nothing estimated, nothing filled in.

**Independently verified by this workstream:** dashboard 200 with honest empty copy; `/v1/models` → `artifacts:[]`; ingest `GET /v1/models` → 404 (narrow routing intact); backend suite 297/297 re-run from the pinned-commit archive; rate-limit (120) and scheduler ceiling (10) confirmed in source. D1 census and 401 behavior attributed to Muse B's dated handoff (`cba27c3`).

### Website and dashboard visual evidence — remaining captures

Capture checklist, publication standards, and exact future image paths: [Website + Global Dashboard screenshot plan](docs/website-dashboard-screenshot-plan.md).

The live project site and Global Evidence dashboard are linked in **Judge Quick Access** above. Their final README screenshots are **pending capture and review**, not mockups. Required capture set:

- Website landing page: clear product value, calls to action, and link to the repository.
- Dashboard overview: real deployed UI, current data count, explicit empty/pending state if no publications exist.
- Dashboard detail: real device/cohort/provenance cards only after actual qualified entries appear.
- Consent-controlled contribution flow: Android review, successful receipt, and status distinction; show only completed authentic flows.

Never fabricate populated dashboards, device fleet results, or signed-download links to fill visual gaps.

## J. Download / install / requirements

**No signed public APK exists yet** — current builds are debuggable owner-testing images and are explicitly not release candidates. Do not distribute them. When the `public-preview` build passes its gates, it will appear as a GitHub Release with SHA-256 + signing fingerprint. Gate tracker: [docs/release-readiness.md](docs/release-readiness.md).

Build-from-source requirements: Android Studio (AGP 8.7.3, compileSdk 35), NDK, llama.cpp v0.7.0 (see [docs/bench-build.md](docs/bench-build.md)), a physical arm64-v8a device (minSdk 26), and a user-provided GGUF model file (the app never downloads models). First sync needs network.

## K. Why Google Play distribution is pending

No store listing exists because there is no signed release candidate, and the hackathon window does not fit the store timeline (policy review, data safety, rollout — plus, for personal accounts created after Nov 13 2023, a 12-tester / 14-day closed test before applying for production access). Direct GitHub distribution is the planned preview channel. Full note: [docs/play-distribution-note.md](docs/play-distribution-note.md).

## L. Architecture diagram and technical stack

```mermaid
flowchart LR
    Phone["Android app\n(benchmark + sessions + BYOK)"]
    JSON["Contract-v1 JSON\nexport / import"]
    SQLite["Workstation SQLite\n(private evidence store)"]
    Muse["Meta Muse Spark\n(predict + follow-up)"]
    Cloud["Global backend + dashboard\n(DEPLOYED, honestly empty)"]

    Phone -->|"explicit export,\nno auto-upload"| JSON
    JSON -->|"two-flag commit\nimport"| SQLite
    SQLite -->|"bounded validated\nretrieval"| Muse
    Muse -->|"prediction recorded\nbefore measurement"| Phone
    Phone -.->|"consent-gated upload\n(deployed; first\n genuine upload pending)| Cloud
```

Stack: Kotlin + JNI (llama.cpp v0.7.0, CPU), programmatic Views + Aero design system, app-private JSON ledger, Python stdlib workstation tooling, Cloudflare Workers + D1 (zero-dependency JS) Global backend.

Deeper reference: [docs/architecture.md](docs/architecture.md) and the authoritative [docs/edgefit-master-architecture.md](docs/edgefit-master-architecture.md). Decisions: [docs/edgefit-architecture-decisions.md](docs/edgefit-architecture-decisions.md). Traceability: [docs/ef-arch-02-traceability.md](docs/ef-arch-02-traceability.md).

## M. Evidence and qualification documentation

- [docs/evidence-showcase.md](docs/evidence-showcase.md) — claim → artifact map
- [docs/validation-results.md](docs/validation-results.md) — measured results with caveats
- [docs/qualification-status.md](docs/qualification-status.md) — full qualification record
- [docs/evidence-methodology.md](docs/evidence-methodology.md) — provenance and eligibility
- [docs/ef-repo-judge-01-feature-matrix.md](docs/ef-repo-judge-01-feature-matrix.md) — 28-feature audit
- [`eval/comp-01b/`](eval/comp-01b/) — frozen six-run study corpus
- [docs/walkthrough/v1.0/](docs/walkthrough/v1.0/README.md) — Definitive Walkthrough v1.0 (illustrated judge/developer guide, PDF + editable Word)

## N. Reproduction instructions

**Harness (physical):** open `android/` in Android Studio and sync; fetch llama.cpp v0.7.0 into `android/third_party/llama.cpp`; build `benchmark`; install on a physical device; stage a GGUF in-app; run from the Benchmark tab; export contract-v1 JSON via the system picker; validate on the workstation (`py -3 scripts/ef_bench_result_01.py <file>`). Full procedure: [docs/device-testing.md](docs/device-testing.md).

**Offline suites (no device, no network, no credentials):** [Local development](#o-local-development-and-reproducible-tests).

**Study:** frozen corpus + runbook reproduce the six-run analysis without re-running the phone: [docs/ef-comp-01b-owner-run.md](docs/ef-comp-01b-owner-run.md).

**Global backend:** fetch `implement/ef-global-01-2026-10-30` @ `cba27c3`, extract `cloud/`, `npm test` (297 tests, isolated DBs).

## O. Local development and reproducible tests

Prerequisites: Python 3 (stdlib only), Node 24+ (cloud tests), PowerShell (validator tests). No credentials needed for any offline suite.

```powershell
# Python offline suites (no network, isolated temp databases)
py -3 .\scripts\ef_data_01.py
py -3 .\scripts\test_ef_meta_05.py
py -3 .\scripts\test_ef_android_01.py
py -3 .\scripts\test_ef_data_02.py
py -3 .\scripts\test_ef_bench_01.py
py -3 .\scripts\test_ef_data_03.py
py -3 .\scripts\test_ef_meta_06.py
py -3 .\scripts\test_ef_loop_01.py

# PowerShell validator suites (no Meta API usage)
.\scripts\Test-EF-META-04.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\Test-EFScience01A.ps1

# Cloud foundation tests (isolated in-memory databases)
npm test --prefix cloud

# Study manifest regression
node .\scripts\ef-comp-01a-manifest.test.mjs
```

Live Meta round trips are owner-executed only, require `MODEL_API_KEY`, and never run in tests or CI. Guide: [docs/quickstart.md](docs/quickstart.md).

## P. Limitations, known issues and roadmap

- Physical evidence comes from **one device** (Galaxy S22 Ultra) with small samples (1–3 runs per config): all comparisons are **exploratory**, not confirmatory.
- Device-profile collection is **not** inference benchmarking; only COMPLETED bench runs count as measurements.
- The one live BYOK session, one agent loop, and six-run study are scoped demonstrations — see [qualification-status](docs/qualification-status.md).
- Global backend + dashboard are **deployed but empty**: zero genuine uploads, zero published cohorts, consent unapproved, P0–P3 not run. No multi-device data exists.
- No signed public release yet ([gates](docs/release-readiness.md)); no Play listing ([note](docs/play-distribution-note.md)).
- On Nov 1 the study phone's benchmark package was removed by an unattributed actor and a fresh base build installed (later INSTALL-07 recovery reported PASS on the S22); workstation evidence is intact; governed by mandatory safe-install preflight (`docs/ef-safe-install-01.md`, companion workstream file, lands separately).
- Roadmap: public-preview APK → first genuine uploads + quorum → Play publication. Proposed-only: none remaining in the showcase — the explorer is deployed.

## Q. Hackathon information and original-work scope

Built for the **2026 Meta Global AI Developer Hackathon** (deadline Nov 4, 2026). Original work: Android harness + Aero UI, session/agent/consent architecture, cloud foundation + deployed Global backend, dashboard website, study design + frozen corpus, and all documentation. Pinned third-party code: llama.cpp v0.7.0 (reviewed, ownership-tested). Meta Muse Spark (`muse-spark-1.3`) used via API for predictions, follow-ups, and agent proposals; Muse Code assisted test-harness and scaffolding development. No license file is published yet; until one is added, no reuse rights are granted. (Owner action: approve and add `LICENSE`.)

## R. Video and verified submission links

**Demo video coming soon.** No video link is published yet; this section will be updated with the verified URL. A demonstration script is ready in [docs/demo.md](docs/demo.md).

Verified links: this repository, the [live dashboard](https://edgefit-ai.pages.dev/dashboard.html), the [walkthrough PDF](docs/walkthrough/v1.0/EdgeFit_AI_Definitive_Walkthrough_v1.0.pdf), and the public read API above. No download or video URL is claimed anywhere in this README beyond what exists — pending items are labeled PENDING with no href.
