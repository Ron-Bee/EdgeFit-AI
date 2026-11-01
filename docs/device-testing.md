# Device testing

How to run the Android harness, what it actually measures, and where
the privacy boundaries are.

## What the harness is

EdgeFit AI (`android/`, application id `ai.edgefit.diag`) is a
hardware-aware test harness with two distinct jobs — never confuse them:

1. **Device-profile collection** — platform-reported specs (model, Android
   level, CPU ABIs, core count, RAM, thermal/battery state). This is
   inventory, **not** a performance measurement.
2. **Inference benchmarking** — real on-device generation with llama.cpp
   (v0.7.0), timed and recorded per run. Only COMPLETED runs are
   measurements.

## Supported measurements

Per benchmark run (contract `edgefit-bench-result` v1):

- Load time, prefill tokens/ms, generation tokens/ms
- Computed generation and prefill throughput (tok/s)
- Memory: PSS before/after, allocator delta, available-RAM conditions
- Thermal state before/mid/after
- Full provenance: model SHA-256, workload id + prompt hash, config
  (ctx/threads/predict/sampler/seed), runtime version/ABI, app version,
  collection timestamp

Per device profile (contract v1): model string, SDK level, ABIs, logical
processors, total RAM, thermal/battery snapshot. No serials, no
advertising ids, no accounts.

Contracts: [android-profile-contract.md](android-profile-contract.md),
[bench-result-contract.md](bench-result-contract.md).

## Setup

1. Open `android/` in Android Studio; sync (AGP 8.7.3, compileSdk 35).
   First sync needs network; builds work offline afterwards.
2. Clone llama.cpp v0.7.0 into `android/third_party/llama.cpp`
   (gitignored) — see [bench-build.md](bench-build.md).
3. Build the `benchmark` variant; install on a physical device
   (physical runs only — emulators are not evidence).
4. Copy a GGUF model (e.g. Llama-3.2-1B-Instruct-Q4_K_M) to the device
   and stage it in-app. The app never downloads models itself.

Build details: [android-build.md](android-build.md).

## Execution

1. **Home** — verify device card, live telemetry, and staged model.
2. **Benchmark** — run memory-readiness check, pick a config
   (context/threads/predict; sampler fixed to greedy in studies).
3. **Run** — the dark run screen shows live token progress and a
   generation chart; cancellation and guard refusals are explicit states.
4. **Review** — measured prefill/generation tok/s with thermal and
   memory conditions; failures render as failures, never as 0.0 tok/s.
5. **Export** — Save locally, then export contract-v1 JSON through the
   system file picker on explicit tap.

## Workstation validation and import

```powershell
# Read-only validation (never writes to SQLite)
py -3 .\scripts\ef_android_profile_01.py <profile>.json
py -3 .\scripts\ef_bench_result_01.py <result>.json
```

Import into the private SQLite store is preview-by-default and commits
only with two explicit flags (owner-authorized; never done by tests):

```powershell
py -3 .\scripts\ef_data_02_import.py <profile>.json        # preview
py -3 .\scripts\ef_data_03_import.py <result>.json         # preview
```

Procedures: [ef-data-02-import.md](ef-data-02-import.md),
[ef-data-03-bench.md](ef-data-03-bench.md).

## Privacy boundaries

- Profiling needs zero permissions and zero network.
- Exports go only to a user-chosen destination via the system picker.
- No automatic upload, no telemetry, no analytics SDKs.
- Global sharing (when a backend exists) is per-submission, with a
  human-readable review screen and recorded consent — see the
  contribution screen in the README gallery.
- Exported `edgefit-profile-*.json` / `edgefit-bench-*.json` files and
  `*.db` files stay out of git (see [.gitignore](../.gitignore)).
