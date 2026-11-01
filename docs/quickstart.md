# Quickstart

Get from clone to green offline tests in minutes. No device, no API key, no network (after toolchain install).

## Prerequisites

- Python 3 (standard library only; no pip packages)
- Node 24+ for `cloud/` tests (no npm dependencies)
- PowerShell for validator suites
- Android Studio (AGP 8.7.3, compileSdk 35) only for the app build

## 1. Clone

```powershell
git clone https://github.com/Ron-Bee/EdgeFit-AI.git
cd EdgeFit-AI
```

## 2. Run the offline suites

```powershell
# Workstation evidence pipeline (each exits 0 on pass)
py -3 .\scripts\ef_data_01.py
py -3 .\scripts\test_ef_meta_05.py
py -3 .\scripts\test_ef_android_01.py
py -3 .\scripts\test_ef_data_02.py
py -3 .\scripts\test_ef_bench_01.py
py -3 .\scripts\test_ef_bench_native_01.py
py -3 .\scripts\test_ef_data_03.py
py -3 .\scripts\test_ef_meta_06.py
py -3 .\scripts\test_ef_loop_01.py
py -3 .\scripts\test_ef_session_parity.py
py -3 .\scripts\test_ef_android_ui_01.py

# Validator + analysis self-tests (no Meta API usage)
.\scripts\Test-EF-META-04.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\Test-EFScience01A.ps1

# Cloud foundation (isolated in-memory SQLite)
npm test --prefix cloud

# Study manifest regression
node .\scripts\ef-comp-01a-manifest.test.mjs
```

All suites use isolated temp or in-memory databases; the real `data/edgefit.db`
is never written by tests. Live Meta scripts (`ef_meta_05_live.py`,
`ef_meta_06_live.py`, `ef_loop_01.py --live`) require `MODEL_API_KEY` plus
explicit flags and are owner-executed only — CI never runs them.

## 3. Build the Android app

1. Open `android/` in Android Studio and sync.
2. Clone llama.cpp v0.7.0 to `android/third_party/llama.cpp`
   (ignored by git; see [bench-build.md](bench-build.md)).
3. Build the `benchmark` variant and install on a physical device.
4. Stage a GGUF file on-device; nothing downloads automatically.

Device steps: [device-testing.md](device-testing.md).
Build details: [android-build.md](android-build.md).

## 4. Validate an exported file

```powershell
py -3 .\scripts\ef_android_profile_01.py <exported-profile>.json
py -3 .\scripts\ef_bench_result_01.py <exported-result>.json
```

Validators are read-only. Importing into SQLite is a separate,
two-flag, owner-authorized step — see
[ef-data-02-import.md](ef-data-02-import.md) and
[ef-data-03-bench.md](ef-data-03-bench.md).

## Next steps

- [architecture.md](architecture.md) for the system map
- [validation-results.md](validation-results.md) for what has been measured
- [demo.md](demo.md) for the five-minute walkthrough
