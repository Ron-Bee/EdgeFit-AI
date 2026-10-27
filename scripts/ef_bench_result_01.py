"""EdgeFit EF-BENCH-01: offline benchmark-result contract validator.

Standard library only. Validate-only: checks a benchmark result against
docs/bench-result-contract.md. Never writes to SQLite, never imports
results, performs no network I/O.

Usage: python scripts/ef_bench_result_01.py <result.json>
Exit codes: 0 VALID, 1 INVALID, 2 usage/error.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

CONTRACT = "edgefit-bench-result"
CONTRACT_VERSION = 1
MAX_JSON_BYTES = 65536
MAX_STRING_LEN = 256
STATUSES = frozenset({"COMPLETED", "CANCELLED", "FAILED", "TIMEOUT"})
THERMAL_STATUSES = frozenset(
    {
        "none",
        "light",
        "moderate",
        "severe",
        "critical",
        "emergency",
        "shutdown",
        "unknown",
    }
)
MEM_METHODS = frozenset(
    {"debug-memoryinfo+mallinfo2", "debug-memoryinfo+mallinfo2;alloc_unavailable"}
)

_RUN_RE = re.compile(r"^BENCH-\d{8}T\d{6}Z-[0-9a-f]{8}$")
_TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
_HEX64_RE = re.compile(r"^[0-9a-f]{64}$")


def _is_int(value):
    return type(value) is int


def _is_num(value):
    return type(value) in (int, float)


def validate_result(result):
    """Return a list of contract violations (empty means VALID)."""
    errors = []
    if not isinstance(result, dict):
        return ["result: must be a JSON object"]
    for key in result:
        if key.startswith("x_"):
            continue
        if key not in (
            "contract",
            "contract_version",
            "run_id",
            "device",
            "model",
            "runtime",
            "config",
            "workload",
            "measurements",
            "status",
            "error",
            "provenance",
            "app_version",
            "collected_at_utc",
        ):
            errors.append("%s: unknown top-level field" % key)
    if result.get("contract") != CONTRACT:
        errors.append("contract: must equal %r" % CONTRACT)
    if not _is_int(result.get("contract_version")):
        errors.append("contract_version: must be an integer")
    elif result["contract_version"] != CONTRACT_VERSION:
        errors.append("contract_version: unsupported version")
    run_id = result.get("run_id")
    if not isinstance(run_id, str) or not _RUN_RE.match(run_id):
        errors.append("run_id: bad format")
    status = result.get("status")
    if status not in STATUSES:
        errors.append("status: unknown status")
    error = result.get("error")
    if status == "COMPLETED":
        if error is not None:
            errors.append("COMPLETED: error must be null")
    elif not isinstance(error, str) or not error:
        errors.append("non-COMPLETED: error must be a non-empty string")
    if result.get("provenance") != "on_device_measurement":
        errors.append("provenance: must equal 'on_device_measurement'")
    _check_str(errors, "app_version", result.get("app_version"))
    collected = result.get("collected_at_utc")
    if not isinstance(collected, str) or not _TIMESTAMP_RE.match(collected):
        errors.append("collected_at_utc: must match YYYY-MM-DDTHH:MM:SSZ")
    _validate_device(errors, result.get("device"))
    _validate_model(errors, result.get("model"))
    _validate_runtime(errors, result.get("runtime"))
    _validate_config(errors, result.get("config"))
    _validate_workload(errors, result.get("workload"))
    _validate_measurements(errors, result.get("measurements"))
    try:
        size = len(json.dumps(result).encode("utf-8"))
    except (TypeError, ValueError):
        errors.append("result: not JSON-serializable")
    else:
        if size > MAX_JSON_BYTES:
            errors.append("result: exceeds %d bytes" % MAX_JSON_BYTES)
    return errors


def _check_str(errors, where, value, required=True):
    if value is None:
        if required:
            errors.append("%s: missing" % where)
        return
    if not isinstance(value, str) or not value or len(value) > MAX_STRING_LEN:
        errors.append("%s: must be a 1-%d char string" % (where, MAX_STRING_LEN))


def _validate_device(errors, device):
    if not isinstance(device, dict):
        errors.append("device: must be an object")
        return
    ref = device.get("profile_ref")
    if not isinstance(ref, str) or not _HEX64_RE.match(ref):
        errors.append("device.profile_ref: must be 64 hex chars")
    _check_str(errors, "device.model", device.get("model"))
    if not _is_int(device.get("sdk_int")) or not 21 <= device["sdk_int"] <= 50:
        errors.append("device.sdk_int: must be an integer 21-50")
    abis = device.get("supported_abis")
    if (
        not isinstance(abis, list)
        or not abis
        or len(abis) > 16
        or any(not isinstance(a, str) or not a for a in abis)
    ):
        errors.append("device.supported_abis: must be 1-16 non-empty strings")
    cpu = device.get("logical_processors")
    if not _is_int(cpu) or not 1 <= cpu <= 1024:
        errors.append("device.logical_processors: must be an integer 1-1024")
    total = device.get("total_ram_bytes")
    if not _is_int(total) or total <= 0:
        errors.append("device.total_ram_bytes: must be a positive integer")
    if device.get("provenance") != "platform_reported":
        errors.append("device.provenance: must equal 'platform_reported'")


def _validate_model(errors, model):
    if not isinstance(model, dict):
        errors.append("model: must be an object")
        return
    _check_str(errors, "model.display_name", model.get("display_name"))
    if not _is_int(model.get("size_bytes")) or model["size_bytes"] <= 0:
        errors.append("model.size_bytes: must be a positive integer")
    sha = model.get("sha256")
    if not isinstance(sha, str) or not _HEX64_RE.match(sha):
        errors.append("model.sha256: must be 64 hex chars")
    _check_str(errors, "model.quantization", model.get("quantization"))
    meta = model.get("metadata")
    if (
        not isinstance(meta, dict)
        or len(meta) > 32
        or any(not isinstance(k, str) or not isinstance(v, str) for k, v in meta.items())
    ):
        errors.append("model.metadata: must be an object of at most 32 strings")


def _validate_runtime(errors, runtime):
    if not isinstance(runtime, dict):
        errors.append("runtime: must be an object")
        return
    if runtime.get("impl") != "llama.cpp":
        errors.append("runtime.impl: must equal 'llama.cpp'")
    _check_str(errors, "runtime.version", runtime.get("version"))
    if runtime.get("backend") != "cpu":
        errors.append("runtime.backend: must equal 'cpu'")
    _check_str(errors, "runtime.abi", runtime.get("abi"))


def _validate_config(errors, config):
    if not isinstance(config, dict):
        errors.append("config: must be an object")
        return
    if not _is_int(config.get("n_ctx")) or not 128 <= config["n_ctx"] <= 4096:
        errors.append("config.n_ctx: must be an integer 128-4096")
    if not _is_int(config.get("n_threads")) or not 1 <= config["n_threads"] <= 16:
        errors.append("config.n_threads: must be an integer 1-16")
    if not _is_int(config.get("n_predict")) or not 1 <= config["n_predict"] <= 256:
        errors.append("config.n_predict: must be an integer 1-256")
    if config.get("temperature") != 0:
        errors.append("config.temperature: must equal 0")
    if config.get("sampler") != "greedy":
        errors.append("config.sampler: must equal 'greedy'")
    if not _is_int(config.get("seed")):
        errors.append("config.seed: must be an integer")
    if not _is_int(config.get("timeout_ms")) or config["timeout_ms"] <= 0:
        errors.append("config.timeout_ms: must be a positive integer")


def _validate_workload(errors, workload):
    if not isinstance(workload, dict):
        errors.append("workload: must be an object")
        return
    if workload.get("id") != "edgefit-prompt-v1":
        errors.append("workload.id: must equal 'edgefit-prompt-v1'")
    sha = workload.get("prompt_sha256")
    if not isinstance(sha, str) or not _HEX64_RE.match(sha):
        errors.append("workload.prompt_sha256: must be 64 hex chars")
    if not _is_int(workload.get("prompt_chars")) or workload["prompt_chars"] <= 0:
        errors.append("workload.prompt_chars: must be a positive integer")


def _validate_measurements(errors, measurements):
    if not isinstance(measurements, dict):
        errors.append("measurements: must be an object")
        return
    for field in ("load_ms", "prefill_ms", "gen_ms"):
        value = measurements.get(field)
        if not _is_int(value) or value < 0:
            errors.append("measurements.%s: must be a non-negative integer" % field)
    for field in ("prefill_tokens", "generated_tokens"):
        value = measurements.get(field)
        if not _is_int(value) or value < 0:
            errors.append("measurements.%s: must be a non-negative integer" % field)
    gen_ms = measurements.get("gen_ms")
    generated = measurements.get("generated_tokens")
    tps_gen = measurements.get("tps_gen")
    if (
        _is_int(gen_ms)
        and gen_ms > 0
        and (
            not _is_num(tps_gen)
            or abs(tps_gen - generated * 1000 / gen_ms) > 0.01
        )
    ):
        errors.append("measurements.tps_gen: inconsistent")
    prefill_ms = measurements.get("prefill_ms")
    prefill_tokens = measurements.get("prefill_tokens")
    tps_prefill = measurements.get("tps_prefill")
    if (
        _is_int(prefill_ms)
        and prefill_ms > 0
        and (
            not _is_num(tps_prefill)
            or abs(tps_prefill - prefill_tokens * 1000 / prefill_ms) > 0.01
        )
    ):
        errors.append("measurements.tps_prefill: inconsistent")
    if type(measurements.get("eos_stop")) is not bool:
        errors.append("measurements.eos_stop: must be a boolean")
    mem = measurements.get("mem")
    if not isinstance(mem, dict):
        errors.append("measurements.mem: must be an object")
    else:
        for field in ("pss_before_kb", "pss_after_kb"):
            value = mem.get(field)
            if not _is_int(value) or value < 0:
                errors.append("mem.%s: must be a non-negative integer" % field)
        if not _is_int(mem.get("alloc_delta_bytes")):
            errors.append("mem.alloc_delta_bytes: must be an integer")
        if mem.get("method") not in MEM_METHODS:
            errors.append("mem.method: unknown method")
    thermal = measurements.get("thermal")
    if not isinstance(thermal, dict):
        errors.append("measurements.thermal: must be an object")
    else:
        for field in ("before", "mid", "after"):
            if thermal.get(field) not in THERMAL_STATUSES:
                errors.append("thermal.%s: unknown status" % field)
        if type(thermal.get("available")) is not bool:
            errors.append("thermal.available: must be a boolean")


def main(argv):
    if len(argv) != 2:
        print("usage: ef_bench_result_01.py <result.json>")
        return 2
    try:
        result = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print("INVALID: cannot read result: %s" % exc)
        return 1
    errors = validate_result(result)
    if errors:
        print("INVALID:")
        for error in errors:
            print("  - %s" % error)
        return 1
    print("VALID: contract v%d result" % CONTRACT_VERSION)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
