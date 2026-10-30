# EdgeFit benchmark-result contract v1

Versioned contract for on-device LLM benchmark measurements (EF-BENCH-01).
Distinct from the device-profile contract: a result is only produced by
measured execution, never estimated or inferred.

- `contract`: must equal `edgefit-bench-result`.
- `contract_version`: must equal `1` (integer).
- Producers: EdgeFitDiag benchmark runner (Kotlin + JNI).
- Offline validator: `scripts/ef_bench_result_01.py`.

## Top-level object

| Field | Type | Required | Notes |
|---|---|---|---|
| `contract` | string | yes | `edgefit-bench-result` |
| `contract_version` | integer | yes | `1` |
| `run_id` | string | yes | `BENCH-YYYYMMDDTHHMMSSZ-` + 8 hex |
| `device` | object | yes | Section below |
| `model` | object | yes | Section below |
| `runtime` | object | yes | `{impl, version, backend, abi}` |
| `config` | object | yes | Section below |
| `workload` | object | yes | `{id, prompt_sha256, prompt_chars}` |
| `measurements` | object | yes | Section below |
| `status` | string | yes | `COMPLETED|CANCELLED|FAILED|TIMEOUT` |
| `error` | string/null | yes | Null only when `COMPLETED` |
| `provenance` | string | yes | `on_device_measurement` |
| `app_version` | string | yes | e.g. `0.2.0` |
| `collected_at_utc` | string | yes | `YYYY-MM-DDTHH:MM:SSZ` |

Unknown top-level fields are rejected unless prefixed `x_`.

Optional `x_` blocks (accepted, never required):

- `x_native_build`: `{variant, native_opt, ...}` (EF-BENCH-02).
- `x_mem_recheck` (EF-MEM-01): present only when a sole
  near-threshold memory miss engaged admission rechecking.
  `{required_bytes, initial_avail_bytes, initial_low_memory,
  rechecks_attempted, admitted, recheck_ms, samples[]}`,
  where each sample is `{avail_bytes, low_memory,
  elapsed_realtime_ms}`. The recheck interval sits outside
  the measurement window and never alters timings.
- `x_mem_diag` (EF-MEM-02): present only when the developer
  enabled memory diagnostics for that launch.
  `{instrumented: true, build_variant, native_opt,
  non_confirmatory, samples[]}`, where each sample is
  `{checkpoint, elapsed_realtime_ms, avail_bytes,
  low_memory, threshold_bytes, pss_kb, private_dirty_kb,
  native_heap_bytes, java_heap_bytes}` with `null` for
  unavailable signals. Instrumented runs are excluded
  from confirmatory cohorts until an on/off overhead
  assessment exists. Checkpoints: app_idle, home_shown,
  sessions_opened/closed, model_staged, config_ready,
  pre_admission, post_recheck, pre_native, post_native
  (inference-complete + native cleanup merged: native
  frees before returning, so no separate post-load
  peak is observable), failure_handled.
- `x_mem_conditions` (EF-MEM-03): recorded for EVERY
  new run with default diagnostics disabled.
  `{total_ram_bytes, avail_bytes_start, avail_pct_start,
  warning_threshold_pct (=15.0), below_threshold_start,
  low_memory_start, low_threshold_bytes, avail_bytes_end,
  avail_pct_end, method}`. Absent only on pre-MEM-03
  records, which readers display as Unknown (never 0%).
- `x_failure` (EF-MEM-03): present only on
  non-completed runs. `{stage, native_started}` names
  where the run stopped and whether native code was
  entered; it never fabricates a cause.

## device

Snapshot of the platform at run start plus a reference hash of the full
canonical device profile collected alongside the run.

| Field | Type | Notes |
|---|---|---|
| `profile_ref` | string | SHA-256 hex of canonical profile JSON |
| `model` | string | `Build.MODEL`, ≤256 chars |
| `sdk_int` | integer | 21–50 |
| `supported_abis` | string[] | 1–16 entries |
| `logical_processors` | integer | 1–1024 |
| `total_ram_bytes` | integer | > 0 |
| `provenance` | string | `platform_reported` |

## model

| Field | Type | Notes |
|---|---|---|
| `display_name` | string | User-visible file name, ≤256 chars |
| `size_bytes` | integer | Staged file size, > 0 |
| `sha256` | string | 64 lowercase hex of staged bytes |
| `quantization` | string | GGUF `general.quantization_version` (format version, NOT the quant type) or `unknown`. Per-tensor types have no single file-level string in GGUF; the conventional label (e.g. Q4_K_M) is preserved in `display_name`. Public uint metadata such as `general.file_type` is not readable via the llama C API, so no numeric file-type mapping is attempted. |
| `metadata` | object | ≤32 string values from model header |

## config (fixed, recorded)

| Field | Type | Notes |
|---|---|---|
| `n_ctx` | integer | 128–4096 |
| `n_threads` | integer | 1–16, actual threads used |
| `n_predict` | integer | 1–256, generation cap |
| `temperature` | number | Must equal 0 (greedy) |
| `sampler` | string | Must equal `greedy` |
| `seed` | integer | Fixed seed, recorded |
| `timeout_ms` | integer | Total-run watchdog, > 0 |

## measurements

All timings are milliseconds (integers, ≥ 0) measured with monotonic
clocks around the phase they name.

| Field | Type | Notes |
|---|---|---|
| `load_ms` | integer | Model load + context init |
| `prefill_ms` | integer | Prompt tokenize + first decode |
| `prefill_tokens` | integer | Prompt token count |
| `gen_ms` | integer | Autoregressive generation loop |
| `generated_tokens` | integer | Emitted tokens (excl. prompt) |
| `tps_gen` | number | `generated_tokens*1000/gen_ms` (±0.01 when `gen_ms` > 0) |
| `tps_prefill` | number | `prefill_tokens*1000/prefill_ms` (±0.01 when `prefill_ms` > 0) |
| `eos_stop` | boolean | Whether EOS ended generation early |
| `mem` | object | `{pss_before_kb, pss_after_kb, alloc_delta_bytes, method}` |
| `thermal` | object | `{before, mid, after, available}` |

Memory semantics: `pss_*_kb` are OS-reported process PSS
(`Debug.MemoryInfo`), not native peak allocation. `alloc_delta_bytes`
is the `mallinfo2` allocator delta across the run (approximate native
heap change; model weights are typically mmapped and excluded).
`method` names this composition verbatim, with a `;alloc_unavailable`
suffix when the allocator endpoints were unreadable (delta then 0).

Thermal semantics: platform `PowerManager` status sampled before the
run, after prefill (`mid`), and after generation (`after`). When the
API is unavailable, `available` is false and names are `unknown`.

## Status semantics

- `COMPLETED`: full workload finished; `error` must be null.
- `CANCELLED` / `TIMEOUT`: stopped by user or watchdog; `error` names
  the cause; partial timings are preserved as measured.
- `FAILED`: loader, guard, or runtime failure; `error` is required.
