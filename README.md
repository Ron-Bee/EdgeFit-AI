# EdgeFit AI

**Hardware-aware Android LLM benchmarking: Meta Muse Spark predicts on-device inference speed, the phone measures it, and every claim stays traceable to evidence.**

Built for the **2026 Meta Global AI Developer Hackathon**. EdgeFit AI combines a hardware-aware Android test harness, evidence-based model selection, Meta Muse Spark integration, persistent SQLite evidence, and a Global Evidence System design — with an honest, judge-verifiable line between what was measured and what was estimated.

> **Status snapshot (2026-10-31):** on-device benchmark + prediction sessions are implemented and physically demonstrated on a Galaxy S22 Ultra; the cloud backend and public explorer are implemented as a host-qualified foundation but **not deployed** — there is no public dashboard URL yet. See [Limitations](#limitations--qualification-status).

## Demo video

**Demo video coming soon.** No video link is published yet; this section will be updated with the verified URL.

A scripted five-minute walkthrough is ready in [docs/demo.md](docs/demo.md).

## Screenshots

Genuine captures from a Galaxy S22 Ultra (SM-S908U, Android 16). No mockups; no identifiers beyond the public device-model string.

| Screen | Capture |
|---|---|
| Completed benchmark run: 64/64 tokens, measured 15.3 tok/s with live chart | ![Completed on-device benchmark showing 64 of 64 tokens, measured 15.3 tokens per second, and a generation chart](assets/screenshots/benchmark-result.png) |
| Home dashboard: device card, live telemetry, staged model, last measurement | ![EdgeFit AI home dashboard with device telemetry, model card, and last measurement](assets/screenshots/home-dashboard.png) |

| Screen | Capture |
|---|---|
| Benchmark setup: model, configuration, and evidence-sharing steps | ![Benchmark screen with model selection, config, and evidence sharing options](assets/screenshots/benchmark-setup.png) |
| Model recommendations: measured values vs explicitly unverified estimates | ![Recommended models dialog distinguishing measured throughput from unverified estimates](assets/screenshots/model-recommendations.png) |
| Local sessions: durable prediction → benchmark records with measured throughput | ![Local sessions list showing measured throughput per session](assets/screenshots/sessions.png) |
| Meta BYOK settings: session-only key custody, explicit authorization | ![Meta API settings showing session-only key custody and Predict with Meta button](assets/screenshots/meta-byok.png) |
| Global contribution review: consent-gated sharing preview with provenance | ![Contribute to EdgeFit Global review screen showing run provenance and consent scope](assets/screenshots/global-contribute.png) |

## Features

- **On-device inference benchmark** — llama.cpp (v0.7.0) harness on Android measures load, prefill, and generation throughput with thermal and memory conditions recorded per run.
- **Prediction-before-measurement sessions** — every estimate is persisted before the benchmark runs, so error is computed deterministically, never narrated.
- **Meta Muse Spark integration** — throughput predictions, grounded follow-ups, and bounded agent proposals over validated evidence; direct BYOK mode keeps the user's key on the phone.
- **Bounded agentic loop** — within-model optimization with explicit approve/reject, a 5-attempt budget, and termination rules (no autonomous execution).
- **Curated model catalog** — offline GGUF shortlist with compatibility estimates clearly labeled unverified until measured.
- **Global contribution client** — consent-gated, per-submission review with provenance display, idempotent resubmission, and revocation support.
- **Host-qualified cloud foundation** — versioned submission API, rule-driven eligibility, quorum-gated aggregates (`cloud/`, 38 tests). Undeployed; no production data.
- **Reproducible evidence pipeline** — contract-versioned JSON exports, additive SQLite migrations, read-only retrieval, and a frozen study corpus under `eval/`.

## Architecture

```mermaid
flowchart LR
    Phone["Android app\n(benchmark + sessions + BYOK)"]
    JSON["Contract-v1 JSON\nexport / import"]
    SQLite["Workstation SQLite\n(private evidence store)"]
    Muse["Meta Muse Spark\n(predict + follow-up)"]
    Cloud["Global backend + explorer\n(PROPOSED / undeployed)"]

    Phone -->|"explicit export,\nno auto-upload"| JSON
    JSON -->|"two-flag commit\nimport"| SQLite
    SQLite -->|"bounded validated\nretrieval"| Muse
    Muse -->|"prediction recorded\nbefore measurement"| Phone
    Phone -.->|"consent-gated upload\n(client ready,\nno backend yet)| Cloud
```

Deeper reference: [docs/architecture.md](docs/architecture.md) and the authoritative [docs/edgefit-master-architecture.md](docs/edgefit-master-architecture.md).

## Meta Muse Spark's role

Muse Spark (`muse-spark-1.3` via `https://api.meta.ai/v1/responses`) does three jobs, all behind explicit user authorization:

1. **Throughput prediction** — given device profile + model + config + bounded prior evidence, predicts generation/prefill tok/s with uncertainty. The prediction is stored before the benchmark runs.
2. **Grounded follow-up** — after measurement, analyzes prediction error against cited run ids only; cannot invent measurements.
3. **Agent proposals** — suggests the next config to test within a bounded budget; the owner approves or rejects each one.

**Controlled tool calling:** workstation dispatchers (`scripts/ef_dispatch_05.py`, `scripts/ef_dispatch_06.py`) validate tool arguments against a fixed schema *before* any database access — one fixed parameterized read-only query, deterministic order, bounded limit. Model input never becomes SQL. **Validated evidence retrieval:** `scripts/ef_retrieval_05.py` serves synthetic fixtures; the `retrieve_physical_benchmarks` path serves only COMPLETED physical runs with a narrow projection. Evidence conditions inference in-context; weights are never updated.

Details: [docs/muse-integration.md](docs/muse-integration.md).

## Android physical test harness

The harness (`android/`, app id `ai.edgefit.diag`) collects platform-reported device capabilities and runs real on-device inference:

**Setup**

1. Open `android/` in Android Studio and sync (AGP 8.7.3, compileSdk 35; first sync needs network).
2. Fetch llama.cpp v0.7.0 into `android/third_party/llama.cpp` (see [docs/bench-build.md](docs/bench-build.md)).
3. Build the `benchmark` variant and install on a physical device.
4. Copy a GGUF model file to the device and stage it in-app (nothing is downloaded automatically).

**Execution**

1. Home shows live device capabilities and telemetry — no permissions, no network.
2. Benchmark tab: check memory readiness, select config (ctx/threads/predict), run.
3. The dark run screen shows live progress, then measured prefill/generation tok/s with thermal and memory conditions.
4. Save locally, export contract-v1 JSON through the system file picker, and validate on the workstation:

```powershell
py -3 .\scripts\ef_android_profile_01.py <exported-profile>.json
py -3 .\scripts\ef_bench_result_01.py <exported-result>.json
```

Full procedure: [docs/device-testing.md](docs/device-testing.md). Contracts: [docs/android-profile-contract.md](docs/android-profile-contract.md), [docs/bench-result-contract.md](docs/bench-result-contract.md).

## Local development and reproducible tests

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

## Evidence provenance: verified vs estimated

- **MEASURED** — on-device benchmark output with run id, config, model SHA-256, thermal/memory conditions, and collection timestamp. Example: `BENCH-20261031T030944Z-603a5d78`, 15.28 tok/s generation.
- **PREDICTED** — a Muse estimate persisted *before* its benchmark, with cited evidence ids and uncertainty. Error is computed by the host, not the model.
- **ESTIMATED (unverified)** — catalog compatibility guesses with no measurement behind them; the UI labels these explicitly and never pools them with measurements.
- **SYNTHETIC** — database fixtures for offline development; always labeled, never presented as device data.

Eligibility pipeline (`SUBMITTED → VALIDATED → QUALIFIED → PUBLISHED`) is rule-driven; only `PUBLISHED` rows would feed public aggregates or Muse retrieval. Methodology: [docs/evidence-methodology.md](docs/evidence-methodology.md). Results: [docs/validation-results.md](docs/validation-results.md).

## Global Evidence System

The Global backend (`cloud/`) implements the v1 submission API, content-hash dedup, per-install quotas, consent scopes, rule-driven qualification, and quorum-gated aggregates (min 5 runs / 3 installs) — all verified by 38 host tests against isolated databases. The Android contribution client (review → consent → submit → status → revoke) is implemented and host-tested (fake transport).

**Deployment status: not deployed.** There is no staging or production backend, no DNS, no issued upload tokens, and no public explorer — so this README links to no dashboard. When a backend is deployed, devices will contribute by pointing the app at the published origin and approving per-submission consent; aggregates will appear only above quorum. Planned vs implemented is tracked in [docs/architecture.md](docs/architecture.md) and [docs/ef-cloud-01-api-contract.md](docs/ef-cloud-01-api-contract.md).

## Limitations / qualification status

- Physical evidence comes from **one device** (Galaxy S22 Ultra) with small samples (1–3 runs per config): all comparisons are **exploratory**, not confirmatory.
- Device-profile collection (platform-reported specs) is **not** inference benchmarking; only COMPLETED bench runs count as measurements.
- The one live BYOK session, one agent experiment loop, and the six-run study are scoped demonstrations with recorded caveats — see [docs/qualification-status.md](docs/qualification-status.md).
- Cloud backend and explorer: host-qualified, **undeployed**. No multi-device data exists.
- No signed public release yet; distribution gates are documented but not executed.

## Documentation

- [docs/quickstart.md](docs/quickstart.md) — clone, test, and build in minutes
- [docs/architecture.md](docs/architecture.md) — system map and component status
- [docs/device-testing.md](docs/device-testing.md) — harness setup, execution, exports
- [docs/evidence-methodology.md](docs/evidence-methodology.md) — provenance and eligibility
- [docs/muse-integration.md](docs/muse-integration.md) — Muse Spark integration detail
- [docs/validation-results.md](docs/validation-results.md) — measured results with caveats
- [docs/demo.md](docs/demo.md) — five-minute demo flow (video coming soon)
- [docs/edgefit-master-architecture.md](docs/edgefit-master-architecture.md) — authoritative architecture
- [docs/qualification-status.md](docs/qualification-status.md) — full qualification record
- [cloud/README.md](cloud/README.md) — backend foundation contract

## License

No license file is published yet; the owner has not selected one. Until a license is added, no reuse rights are granted. (Owner action: approve and add `LICENSE`.)
