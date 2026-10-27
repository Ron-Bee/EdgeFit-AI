# EF-BENCH-01 native build and model acquisition

Bounded on-device LLM benchmark for EdgeFitDiag. Measures real inference
on the phone through llama.cpp (CPU, greedy, fixed workload). No
auto-download, no cloud inference, no telemetry.

## Native dependency (identified, owner-acquired)

- Source: `https://github.com/ggerganov/llama.cpp.git`
- Version: tag `v0.7.0`, commit `620d2225cc436ab0b001e3200e590e3b286ef9ab`
- License: see `LICENSE` at the source root (upstream MIT)
- Integration: core C API only (`llama.h`); no `common.h`, server, or
  chat templates. JNI surface is three functions
  (`nativeVersion`/`nativeAllocBytes`/`nativeRun`).
- Reproducible acquisition (repo root, gitignored destination):

```powershell
git clone --depth 1 --branch v0.7.0 https://github.com/ggerganov/llama.cpp.git android/third_party/llama.cpp
```

CMake fails with these instructions if the directory is absent. No
Honey LLM or BeeBench source is used or referenced.

## Model artifact (identified, not downloaded by the agent)

- File: `Llama-3.2-1B-Instruct-Q4_K_M.gguf`
- Source repo: `bartowski/Llama-3.2-1B-Instruct-GGUF` (Hugging Face)
- Exact size: 807,694,464 bytes (~770 MiB), verified via HEAD request
- License id: `llama3.2` (Meta Llama 3.2 Community License); the
  reupload repo is ungated per the HF API, but use remains under
  Meta's terms.
- Owner action: review the Llama 3.2 terms for your use; if gated
  access is required, accept it on Hugging Face/Meta first. Do not
  bypass access restrictions.
- Reproducible acquisition:

```powershell
huggingface-cli download bartowski/Llama-3.2-1B-Instruct-GGUF Llama-3.2-1B-Instruct-Q4_K_M.gguf
```

Verify the byte size, then transfer to the phone. The app hashes the
staged bytes (SHA-256) during selection; the hash is recorded in the
result. Expected memory: guards require
`max(2 GiB, model_bytes + 1 GiB)` free — about 2 GiB for this model —
plus context/scratch; refuse below that.

## Android Studio build

Requirements: Studio with JBR 17+, SDK `platforms/android-35`,
`build-tools/35.0.0`, NDK `28.0.13004108`, CMake 3.22.1 (all installed
on the owner workstation), first-sync network for AGP 8.7.3/Kotlin.

1. Clone llama.cpp as above (outside Git).
2. Open `android/` in Android Studio; let Gradle sync finish.
3. Run the `app` configuration on the S22 Ultra (arm64-v8a; x86_64
   devices are refused by preflight). First build compiles llama.cpp
   (~5–15 minutes); later builds are incremental.
4. CLI equivalent: `gradle -p android :app:assembleDebug`
   (needs a Gradle 8.9 install; no wrapper jar is shipped).

## Measurement semantics

- Fixed workload `edgefit-prompt-v1` (constant string, SHA recorded),
  n_ctx 512, 4 threads (clamped to CPU count), 64-token cap, greedy,
  seed 42, 10-minute watchdog. All values recorded in the result.
- Timings use monotonic clocks: load (derived wall minus phases),
  prefill (tokenize + first decode), generation loop. Throughput is
  `tokens*1000/ms` recomputed by every validator.
- Memory: OS process PSS before/after plus a `mallinfo2` allocator
  delta — approximate, never presented as exact native peak.
- Thermal sampled before, mid-run, and after; unknown or severe+
  states refuse or are recorded honestly.
- Greedy decoding is deterministic per device build; do not claim
  cross-device comparability without a controlled study.

## Known device-resource risks

Thermal throttling skews timings; close other apps and cool the phone
first. Backgrounding or rotation cancels the run by design. Devices
under ~3 GiB free will be refused by preflight. Keep the phone
charging-aware: heat + low battery degrades and varies results.

## PHONE REQUIRED acceptance (not performed)

Install, select the staged GGUF via the picker, run to COMPLETED, run
again and Cancel mid-way, force a guard refusal (small test file is
rejected at load), save each result, `adb pull` from the chosen
folder, and validate with
`py -3 scripts/ef_bench_result_01.py <file>` (expect VALID, statuses
COMPLETED and CANCELLED respectively).
