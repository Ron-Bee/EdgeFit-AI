# EF-ANDROID-UI-01 benchmark execution screen

Live visualization of physical llama.cpp inference. Every displayed
number comes from the native run or the measured result; nothing is
simulated, interpolated as fact, or hardcoded.

## Progress path

Native (`bench_jni.cpp`) writes throttled int32 cells into a 64-byte
direct mailbox: stage, target/generated/prefill counts, load/prefill/
generation timings, and a terminal error code. Writes use relaxed
atomic stores (roughly 20 per run); there are no JNI upcalls, no
retained Java references, and no token or prompt logging.

Kotlin (`BenchRunner`) polls the mailbox on the existing watchdog
thread at ~4 Hz, derives the display execution state, and invokes the
progress listener on that thread. `MainActivity` marshals every update
to the main thread with `runOnUiThread` before touching views.
Thermal and process-PSS observations are sampled at ~0.5 Hz on the
same thread.

## Execution states

`IDLE → VALIDATING_MODEL → LOADING_MODEL → PROCESSING_PROMPT →
GENERATING → FINALIZING → COMPLETED | FAILED | CANCELLED | TIMEOUT`

VALIDATING is set by Kotlin preflight; LOADING/PROCESSING/GENERATING
come from the mailbox stage; FINALIZING is set after `nativeRun`
returns; terminal states come from the measured `BenchResult.status`.
TIMEOUT exists because the watchdog already produces that contract
status. Forward skips are legal (a fast stage may pass between two
4 Hz polls); backward steps are rejected. Pure transition table is
unit-tested in `BenchUiTest`.

## Displayed metric sources

| Display | Source |
|---|---|
| Stage banner | Execution state (mailbox + lifecycle events) |
| Tokens `n / 64` + bar | Mailbox `generated`/`target`, clamped |
| Provisional tok/s | `generated * 1000 / elapsedMs` (Kotlin clock), GENERATING only |
| Elapsed mm:ss | Monotonic `elapsedRealtime` basis |
| Model/config card | Staged file identity + fixed `BenchConfig` |
| Thermal / PSS line | `PowerManager` / `Debug.MemoryInfo` samples |
| Chart | Trailing (elapsed, generated) sampler pairs, ≤2048 |
| Result block | Measured `BenchResult` only; error via `safeError` |

The chart buffer is display-only and never enters benchmark JSON.
Mailbox timings are display-only; authoritative timings stay
Kotlin-derived exactly as before.

## Standard workload transparency (acceptance correction)

`BenchWorkloadView` shows the exact `WORKLOAD_PROMPT` constant also
passed to native inference (single source of truth), the workload
id `edgefit-prompt-v1`, the SHA-256 computed at display time
(`36104db0…4382`, matching physical evidence and SQLite), config,
model identity, measured prompt-token count when available, and a
tokenizer-variance note. Reachable pre-run (View Workload) and
post-run (Workload button). Whitespace is preserved; the prompt,
hash, and contract are frozen — any edit fails the freeze tests.

## Visual corrections (acceptance)

- App label is now "EdgeFit AI" (evidence collector id
  `edgefit-diag` intentionally unchanged for provenance stability).
- The light action-bar header hides on the dark benchmark screen
  (shown again on return); bench content honors system-bar insets.
- Status/navigation icon contrast toggles per screen; bar colors
  stay system-owned so restore is exact.
- Main screen gains consistent section headers; no theme rewrite.

## Cancellation and lifecycle (reviewed, preserved)

Cooperative cancel via the direct-byte mailbox was already qualified
and is unchanged: the Cancel button, `onStop`, and `onDestroy`
(all + `joinGracefully`) set it; native polls it per token and after
tokenization. Cancel during the blocking model load takes effect at
the first poll. Outcomes stay distinct: user/lifecycle cancel →
CANCELLED, watchdog expiry → TIMEOUT, native failure → FAILED with a
fixed-string error; partial output is never COMPLETED. Rotation
recreates the activity and cancels the run (fail-safe, as before).

## Instrumentation overhead (NOT YET QUALIFIED)

Expected cost is negligible (~20 relaxed int stores per run, no
syscalls), but honesty requires measured evidence from hardware:

1. Install the `benchmark` variant APK below.
2. Run 3× with the current build (same model, config, workload).
3. Compare generation throughput, prefill throughput, total elapsed,
   thermal, and memory against historical Run B (14.382 tok/s gen,
   1020 ms prefill) and across the 3 runs for variance.
4. Report mean ± spread; do not conclude zero overhead from one run.

If instrumented throughput degrades materially or semantics change,
the mailbox writes get a build-time kill switch before any release.

## Build variants and install (Windows PowerShell)

Preferred demo variant: `benchmark` (optimized native, debuggable,
app id `ai.edgefit.diag.bench`).

```powershell
$env:JAVA_HOME = "<path-to-Studio-jbr-or-JDK-17>"
<gradle-8.9> --offline -p android :app:assembleBenchmark
adb -s <device-serial> install -r android\app\build\outputs\apk\benchmark\app-benchmark.apk
```

Substitute the local Studio `jbr` path, a Gradle 8.9 launcher,
and the target device serial; see `android-build.md` for toolchain
setup. Verified with an offline Gradle + NDK 28 build.

Or build `assembleBenchmark` in Android Studio and Run on the S22
Ultra. The GGUF stays on the phone; pick the same staged file used
for Run B (`Llama-3.2-1B-Instruct-Q4_K_M.gguf`).

## Physical acceptance sequence (PHONE REQUIRED)

1. Launch `ai.edgefit.diag.bench`, select the GGUF, tap Run Benchmark.
2. Observe, in order: Validating → Loading model → Processing prompt
   → Generating (rising token count, provisional tok/s, live chart)
   → Finalizing → Completed with measured numbers.
3. Save the result; pull it with ADB; run
   `python scripts/ef_bench_result_01.py <file>` (must print VALID).
4. Collect: one screenshot per stage, the exported JSON, and a note
   of final tok/s vs Run B for the overhead comparison.
5. Also verify: Cancel mid-generation → CANCELLED (never COMPLETED);
   backgrounding the app cancels safely; rotation cancels safely.

Acceptance passes only when real stages are seen in order and the
exported JSON validates.
