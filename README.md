# EdgeFit AI

Hackathon prototype: Meta-assisted, evidence-based Android inference experiments.

**Development started October 26, 2026.** This repository is intended for new hackathon work. Honey LLM and BeeBench are pre-existing independent projects; their code and results are not claimed as new work.

## EF-META-05 evidence flow

Muse Spark 1.3 → validated tool request → read-only SQLite query → evidence response → Muse analysis.

- `scripts/ef_retrieval_05.py` — read-only SQLite service over the EF-DATA-01 schema (one fixed parameterized query, deterministic order, bounded limit, provenance preserved).
- `scripts/ef_dispatch_05.py` — application-side dispatcher for `retrieve_comparable_benchmarks`; enforces the EF-META-04 validation rules before any database access.
- `scripts/ef_meta_05_live.py` — opt-in live round-trip test (model `muse-spark-1.3`); runs only when executed directly with `MODEL_API_KEY` in the environment. Maximum one tool-call round trip; evidence returned via `function_call_output`.
- `scripts/test_ef_meta_05.py` — local test suite (no network): retrieval, dispatcher validation matrix, empty/missing-database handling, provenance, determinism, read-only behavior, and offline API-payload regression.

## EF-ANDROID-01 collector

EdgeFitDiag (`android/`, application id `ai.edgefit.diag`) collects real
device capabilities on the phone — model, Android/API level, CPU ABIs,
processor count, platform-reported memory, thermal and battery state —
and exports a bounded contract-v1 JSON profile through the system file
picker on explicit tap. Zero permissions, zero network, no telemetry,
no automatic upload or SQLite import.

Build: open `android/` in Android Studio and Run (AGP 8.7.3, compileSdk
35, first sync needs network). Details and device steps:
`docs/android-build.md`. Contract: `docs/android-profile-contract.md`.

Export-validation workflow (workstation, after `adb pull` of the file):

```powershell
py -3 .\scripts\ef_android_profile_01.py edgefit-profile-<stamp>.json
py -3 .\scripts\test_ef_android_01.py
```

Exported `edgefit-profile-*.json` files stay outside this repository.

## EF-DATA-02 ingestion

`scripts/ef_data_02_import.py` imports validated contract-v1 profiles
into a dedicated `physical_device_profiles` table (additive migration;
EF-DATA-01 fixtures and EF-META-05 queries untouched). Preview is the
default and performs zero writes; commit requires two explicit flags
and is never run by tests. Full procedure: `docs/ef-data-02-import.md`.

```powershell
py -3 .\scripts\ef_data_02_import.py <profile.json>
py -3 .\scripts\ef_data_02_import.py <profile.json> --commit --acknowledge-physical-import
py -3 .\scripts\test_ef_data_02.py
```

## Qualification scripts

- `scripts/Test-EF-META-04.ps1` — PowerShell local invalid tool-argument tests (no Meta API usage).
- `scripts/ef_data_01.py` — Python standard-library SQLite database fixture and persistence verification (no Meta API usage).
- `scripts/test_ef_meta_05.py` — Python standard-library EF-META-05 local tests (no Meta API usage).
- `scripts/ef_meta_05_live.py` — Opt-in live Meta API round trip (requires `MODEL_API_KEY`; owner-executed).
- `scripts/ef_android_profile_01.py` — Offline contract-v1 profile validator (validate-only; never writes to SQLite).
- `scripts/test_ef_android_01.py` — Python standard-library EF-ANDROID-01 contract tests (no Meta API usage, no device).
- `scripts/ef_data_02_import.py` — Offline controlled profile importer (preview default; two-flag commit; never run by tests).
- `scripts/test_ef_data_02.py` — Python standard-library EF-DATA-02 import/migration tests on isolated temp databases.

Synthetic fixture values are illustrative and not physical measurements.
Run from repository root:

```powershell
& .\scripts\Test-EF-META-04.ps1
py -3 .\scripts\ef_data_01.py
py -3 .\scripts\test_ef_meta_05.py
py -3 .\scripts\test_ef_android_01.py
```

Live qualification (owner workstation with API key only; never run by automated tests):

```powershell
py -3 .\scripts\ef_meta_05_live.py
```

Credentials must not be committed. Database files in `data/` are intentionally ignored. Public repository publication and acceptance under official hackathon rules must be verified separately.
