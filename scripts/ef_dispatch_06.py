"""EdgeFit EF-META-06: dispatcher for the physical tool retrieve_physical_benchmarks.

Separately allowlisted from the EF-META-05 synthetic tool
``retrieve_comparable_benchmarks`` (which lives in ef_dispatch_05.py and is
NOT modified here). Validation mirrors the EF-META-05 taxonomy: one
authorized tool name, exact approved fields, strict types, pinned model /
workload identifiers, limit range 1-3, and stable error codes.

The dispatcher executes only the EF-DATA-03 read-only retrieval layer with
host-pinned constraints (status COMPLETED, enforced server-side, never
model-controlled), projects each row through a narrow public tool
projection, and returns explicit physical source attribution. Model input
is never executed as SQL, resolved as a filesystem path, or passed to a
shell, and this module performs no database writes, no benchmark
execution, and no qualification_state change.

Projection rationale: ef_bench_retrieve_03 returns every stored column
(~40, including internal digests, full metadata blobs, and import
timestamps). The model-visible projection below keeps only fields needed
for grounded comparison: identity/provenance, device+model+runtime+
workload match keys, full inference config, measured timings/token
counts, parsed memory/thermal observations, and build provenance with
requested-vs-effective separation. Dropped: device abis/cpu/ram snapshot
details, profile_ref digest, config hash, error text (always null for
COMPLETED), model metadata blob, import timestamp. No filesystem paths
exist in any projected field.

Usage: ef_dispatch_06.py <tool_name> <argument_json>
Exit codes: 0 ok, 1 refused/error ({"error": CODE} on stdout), 2 usage.
"""

from __future__ import annotations

import json
import math
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_bench_result_01 as bench_contract
import ef_bench_retrieve_03 as retrieval

AUTHORIZED_TOOL = "retrieve_physical_benchmarks"
EXPECTED_FIELDS = ("model_sha", "workload_id", "limit")
# Pinned allowlist: the single physically measured model/workload pair
# ingested under EF-DATA-03b. A second model is a reviewed allowlist change.
APPROVED_MODEL_SHA = (
    "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
)
APPROVED_WORKLOAD_ID = "edgefit-prompt-v1"
# Host-enforced, never model-controlled: only completed runs are comparable.
PINNED_STATUS = "COMPLETED"
MIN_LIMIT = 1
MAX_LIMIT = 3
# Response-size boundary for one tool envelope (two projected records are
# ~3 KB; anything near this cap indicates database tampering or misuse).
MAX_TOOL_OUTPUT_BYTES = 16384

QUANTIZATION_NOTE = (
    "GGUF general.quantization_version (container format version), NOT the "
    "GGUF quantization type; per-tensor types have no single file-level "
    "string, and the conventional label is preserved in display_name."
)

EFFECTIVE_UNRECORDED_NOTE = (
    "Effective compiler flags were not independently recorded for this run; "
    "do not infer them from the requested flags or from other runs."
)


class ToolValidationError(Exception):
    """Argument validation failure; str() is the stable error code."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class EvidenceError(Exception):
    """Retrieval/projection failure; str() is the stable error code."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _reject_json_constant(value):
    raise ToolValidationError("MALFORMED_JSON")


def validate_tool_call(tool_name, argument_json):
    """Validate a physical tool request; return clean args or raise."""
    if tool_name != AUTHORIZED_TOOL:
        raise ToolValidationError("UNAUTHORIZED_TOOL")
    if not isinstance(argument_json, str) or not argument_json.strip():
        raise ToolValidationError("INVALID_OBJECT")
    try:
        root = json.loads(argument_json, parse_constant=_reject_json_constant)
    except json.JSONDecodeError:
        raise ToolValidationError("MALFORMED_JSON") from None
    if root is None or not isinstance(root, dict):
        raise ToolValidationError("INVALID_OBJECT")
    for name in root:
        if name not in EXPECTED_FIELDS:
            raise ToolValidationError("UNAUTHORIZED_ARGUMENT")
    for name in EXPECTED_FIELDS:
        if name not in root:
            raise ToolValidationError("MISSING_ARGUMENT")
    model_sha = root["model_sha"]
    if not isinstance(model_sha, str) or model_sha != APPROVED_MODEL_SHA:
        raise ToolValidationError("INVALID_MODEL")
    workload_id = root["workload_id"]
    if not isinstance(workload_id, str) or workload_id != APPROVED_WORKLOAD_ID:
        raise ToolValidationError("INVALID_WORKLOAD")
    limit = root["limit"]
    if type(limit) is not int:
        raise ToolValidationError("INVALID_LIMIT_TYPE")
    if limit < MIN_LIMIT or limit > MAX_LIMIT:
        raise ToolValidationError("OUT_OF_RANGE")
    return {"model_sha": model_sha, "workload_id": workload_id, "limit": limit}


def _parse_stored_json(text, field):
    if not isinstance(text, str):
        raise EvidenceError("EVIDENCE_ROW_INVALID")
    try:
        value = json.loads(text)
    except ValueError:
        raise EvidenceError("EVIDENCE_ROW_INVALID") from None
    if not isinstance(value, dict):
        raise EvidenceError("EVIDENCE_ROW_INVALID")
    return value


def project_record(row):
    """Project one stored physical row to the narrow model-visible shape."""
    try:
        config = _parse_stored_json(row["config_json"], "config_json")
        memory = _parse_stored_json(row["mem_json"], "mem_json")
        thermal = _parse_stored_json(row["thermal_json"], "thermal_json")
    except KeyError:
        raise EvidenceError("EVIDENCE_ROW_INVALID") from None
    try:
        record = {
            "run_id": row["run_id"],
            "source_sha256": row["source_sha256"],
            "source_type": "physical",
            "source": row["source"],
            "qualification_state": row["qualification_state"],
            "collected_at_utc": row["collected_at_utc"],
            "device": {
                "model": row["device_model"],
                "sdk_int": row["device_sdk_int"],
            },
            "model": {
                "sha256": row["model_sha256"],
                "display_name": row["model_display"],
                "size_bytes": row["model_size"],
                "quantization_version": row["model_quantization"],
                "quantization_note": QUANTIZATION_NOTE,
            },
            "runtime": {
                "impl": row["runtime_impl"],
                "version": row["runtime_version"],
                "backend": row["runtime_backend"],
                "abi": row["runtime_abi"],
            },
            "workload": {
                "id": row["workload_id"],
                "prompt_sha256": row["prompt_sha256"],
                "prompt_chars": row["prompt_chars"],
            },
            "config": config,
            "measurements": {
                "load_ms": row["load_ms"],
                "prefill_ms": row["prefill_ms"],
                "prefill_tokens": row["prefill_tokens"],
                "gen_ms": row["gen_ms"],
                "generated_tokens": row["generated_tokens"],
                "tps_gen": row["tps_gen"],
                "tps_prefill": row["tps_prefill"],
                "eos_stop": bool(row["eos_stop"]),
                "status": row["status"],
            },
            "memory": memory,
            "thermal": thermal,
            "build": {
                "requested_variant": row["requested_variant"],
                "requested_opt": row["requested_opt"],
                "effective_opt": row["effective_opt"],
                "effective_opt_status": row["effective_opt_status"],
                "effective_opt_source": row["effective_opt_source"],
            },
        }
    except KeyError:
        raise EvidenceError("EVIDENCE_ROW_INVALID") from None
    if record["build"]["effective_opt"] is None:
        record["build"]["effective_note"] = EFFECTIVE_UNRECORDED_NOTE
    return record


def _is_number(value):
    return (
        type(value) in (int, float)
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def _safe_thermal(value):
    """Restrict thermal echo to the contract vocabulary.

    Records pass stored values through verbatim, but the host-authored
    comparison only repeats vocabulary the contract allows; anything
    else becomes "unreported". Host text can therefore never carry
    injected instruction strings from tampered rows.
    """
    thermal = value if isinstance(value, dict) else {}
    clean = {}
    for field in ("before", "mid", "after"):
        status = thermal.get(field)
        if status in bench_contract.THERMAL_STATUSES:
            clean[field] = status
        else:
            clean[field] = "unreported"
    available = thermal.get("available")
    clean["available"] = available if type(available) is bool else False
    return clean


def _recompute_tps(tokens, ms):
    if not _is_number(tokens) or not _is_number(ms):
        return None
    if tokens <= 0 or ms <= 0:
        return None
    return tokens * 1000.0 / ms


def build_host_comparison(records):
    """Deterministically compare exactly two projected records.

    Recomputes throughput from underlying token counts and timings
    (never trusts model arithmetic, never hardcodes a ratio), checks
    the fair-comparison match keys, and reports thermal/build
    differences as observations with explicit caveats. Refuses to
    compare (comparable=false + reason) on any mismatch or bad
    measurement instead of guessing. The template contains no causal
    or statistical claims.
    """
    if len(records) != 2:
        return {
            "comparable": False,
            "reason": "host comparison needs exactly 2 records; got %d"
            % len(records),
        }
    first, second = records[0], records[1]
    match_keys = [
        ("device.model", first["device"]["model"], second["device"]["model"]),
        ("model.sha256", first["model"]["sha256"], second["model"]["sha256"]),
        ("workload.id", first["workload"]["id"], second["workload"]["id"]),
        (
            "workload.prompt_sha256",
            first["workload"]["prompt_sha256"],
            second["workload"]["prompt_sha256"],
        ),
        (
            "config.n_ctx",
            first["config"].get("n_ctx"),
            second["config"].get("n_ctx"),
        ),
        (
            "config.n_threads",
            first["config"].get("n_threads"),
            second["config"].get("n_threads"),
        ),
        (
            "config.sampler",
            first["config"].get("sampler"),
            second["config"].get("sampler"),
        ),
        (
            "measurements.prefill_tokens",
            first["measurements"]["prefill_tokens"],
            second["measurements"]["prefill_tokens"],
        ),
        (
            "measurements.generated_tokens",
            first["measurements"]["generated_tokens"],
            second["measurements"]["generated_tokens"],
        ),
        (
            "runtime.backend",
            first["runtime"]["backend"],
            second["runtime"]["backend"],
        ),
    ]
    mismatched = sorted(name for name, a, b in match_keys if a != b)
    if mismatched:
        return {
            "comparable": False,
            "reason": "fair-comparison match keys differ",
            "mismatched_keys": mismatched,
            "run_ids": [first["run_id"], second["run_id"]],
        }
    gen_a = _recompute_tps(
        first["measurements"]["generated_tokens"], first["measurements"]["gen_ms"]
    )
    gen_b = _recompute_tps(
        second["measurements"]["generated_tokens"],
        second["measurements"]["gen_ms"],
    )
    pre_a = _recompute_tps(
        first["measurements"]["prefill_tokens"], first["measurements"]["prefill_ms"]
    )
    pre_b = _recompute_tps(
        second["measurements"]["prefill_tokens"],
        second["measurements"]["prefill_ms"],
    )
    if None in (gen_a, gen_b, pre_a, pre_b) or gen_a <= 0 or pre_a <= 0:
        return {
            "comparable": False,
            "reason": "measurements missing or non-positive; refusing ratio",
            "run_ids": [first["run_id"], second["run_id"]],
        }
    stored_ok = all(
        abs(stored - recomputed) <= 0.01
        for stored, recomputed in (
            (first["measurements"]["tps_gen"], gen_a),
            (second["measurements"]["tps_gen"], gen_b),
            (first["measurements"]["tps_prefill"], pre_a),
            (second["measurements"]["tps_prefill"], pre_b),
        )
        if _is_number(stored)
    )
    return {
        "comparable": True,
        "run_ids": [first["run_id"], second["run_id"]],
        "matched_keys": sorted(name for name, _a, _b in match_keys),
        "gen_tps_a_recomputed": round(gen_a, 3),
        "gen_tps_b_recomputed": round(gen_b, 3),
        "prefill_tps_a_recomputed": round(pre_a, 3),
        "prefill_tps_b_recomputed": round(pre_b, 3),
        "gen_speedup": round(gen_b / gen_a, 2),
        "prefill_speedup": round(pre_b / pre_a, 2),
        "stored_tps_crosscheck": "match" if stored_ok else "MISMATCH",
        "build_a": {
            "requested_variant": first["build"]["requested_variant"],
            "requested_opt": first["build"]["requested_opt"],
            "effective_opt": first["build"]["effective_opt"],
            "effective_opt_status": first["build"]["effective_opt_status"],
        },
        "build_b": {
            "requested_variant": second["build"]["requested_variant"],
            "requested_opt": second["build"]["requested_opt"],
            "effective_opt": second["build"]["effective_opt"],
            "effective_opt_status": second["build"]["effective_opt_status"],
        },
        "thermal_a": _safe_thermal(first["thermal"]),
        "thermal_b": _safe_thermal(second["thermal"]),
        "thermal_caveat": (
            "Thermal states are observed values, not an established cause "
            "of the throughput difference."
        ),
        "scope": (
            "One matched physical pair from one phone. Not a population "
            "average, not a distribution, not a claim about other devices, "
            "models, or configurations."
        ),
        "attribution": (
            "Both runs predate this analysis. The records contain no "
            "evidence about who authored or applied the build change, and "
            "no evidence that Meta did so."
        ),
        "unknown_factors": [
            "Effective compiler flags for the debug run were not recorded.",
            "Ambient temperature, battery state, and SoC governor behavior "
            "during the runs were not recorded.",
            "Run-to-run variance on the same phone was not measured.",
            "Whether the thermal difference affected throughput is unknown.",
        ],
    }


def dispatch_tool_call(tool_name, argument_json, db_path=None):
    """Validate a physical tool request, then execute read-only retrieval."""
    args = validate_tool_call(tool_name, argument_json)
    try:
        rows = retrieval.retrieve_physical(
            model_sha=args["model_sha"],
            workload_id=args["workload_id"],
            status=PINNED_STATUS,
            limit=args["limit"],
            db_path=db_path,
        )
    except retrieval.RetrievalError as exc:
        message = str(exc)
        if message.startswith("database not found"):
            raise EvidenceError("EVIDENCE_DB_MISSING") from None
        raise EvidenceError("EVIDENCE_DB_ERROR") from None
    except (sqlite3.Error, OSError, ValueError):
        raise EvidenceError("EVIDENCE_DB_ERROR") from None
    records = [project_record(row) for row in rows]
    envelope = {
        "tool": AUTHORIZED_TOOL,
        "source_type": "physical",
        "model_sha": args["model_sha"],
        "workload_id": args["workload_id"],
        "status_filter": PINNED_STATUS + " (host-enforced)",
        "count": len(records),
        "records": records,
    }
    if len(json.dumps(envelope)) > MAX_TOOL_OUTPUT_BYTES:
        raise EvidenceError("OUTPUT_TOO_LARGE")
    return envelope


def main(argv):
    if len(argv) != 3:
        print(json.dumps({"error": "usage: ef_dispatch_06.py <tool_name> <argument_json>"}))
        return 2
    try:
        envelope = dispatch_tool_call(argv[1], argv[2])
    except (ToolValidationError, EvidenceError) as exc:
        print(json.dumps({"error": exc.code}))
        return 1
    print(json.dumps(envelope, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
