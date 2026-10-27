"""EdgeFit EF-LOOP-01: integrated prediction -> benchmark -> recommendation loop.

Smallest reliable orchestrator over the qualified components:

  init          validate profile + candidate model/config, request a Meta
                performance prediction (live or mocked), persist the
                prediction and its input provenance to a local run manifest
                BEFORE any benchmarking.
  attach-bench  validate an exported benchmark, associate it with the
                persisted prediction by explicit identities + integrity
                checks, compute deterministic prediction errors.
  followup      send prediction + measurement + errors to Meta (live or
                mocked) for a grounded recommendation; store it.
  status        show per-stage status of a run manifest.
  replay        honestly-labeled historical replay from existing profile +
                benchmark + supplied prediction (no fresh Meta prediction).

Design constraints:

- SQLite is used READ-ONLY (evidence pack + verification reads). The loop
  never writes, migrates, or promotes database rows.
- Manifests live under data/loop_runs/ (gitignored) as <loop_id>.json,
  written atomically (temp file + os.replace). No full local paths are
  stored (basenames + SHA-256 only).
- Live Meta calls need --live AND --authorize-live-call AND MODEL_API_KEY;
  otherwise the stage is refused (exit 2). Offline --mock-meta drives all
  tests and demos. At most one API request per stage, temperature 0, no
  tools, no function calling in this workflow.
- Phone-required benchmark execution stays manual; the orchestrator
  prints exact operator instructions and resumes at attach-bench.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_android_profile_01 as profile_contract
import ef_bench_result_01 as bench_contract
import ef_bench_retrieve_03 as physical_retrieval
import ef_retrieval_05 as synthetic_retrieval

MODEL = "muse-spark-1.3"
ENDPOINT = "https://api.meta.ai/v1/responses"
TIMEOUT_SECONDS = 60
MAX_BODY_PREVIEW = 2000
MAX_EVIDENCE_ROWS = 10
MAX_RAW_TEXT = 20000
LOOP_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST_DIR = ROOT / "data" / "loop_runs"
DEFAULT_DB_PATH = ROOT / "data" / "edgefit.db"

PREDICT_SYSTEM = (
    "You are an EdgeFit performance predictor. Given a validated Android "
    "device profile, a candidate model identity, a benchmark configuration, "
    "and a labeled evidence pack, respond with ONLY a JSON object: "
    '{"predicted_gen_tps": number>0, "predicted_prefill_tps": number>0, '
    '"confidence": "low"|"medium"|"high", '
    '"recommended_config": {"n_ctx": int, "n_threads": int, '
    '"n_predict": int, "seed": int}, "rationale": string, '
    '"uncertainty_notes": [string, ...], "evidence_cited": [ids...], '
    '"limits": [string, ...]}. Cite ONLY run ids present in the evidence '
    "pack. Never claim a physical measurement; this is an estimate. Never "
    "output prose outside the JSON object."
)

FOLLOWUP_SYSTEM = (
    "You are an EdgeFit evidence analyst. Given a persisted prediction, an "
    "on-device measurement, and host-computed errors, respond with ONLY a "
    "JSON object: {'error_assessment': string, 'recommendation': {'action': "
    "'measure_again'|'adopt_config'|'reject_config', 'model': string, "
    "'config': object, 'rationale': string}, 'uncertainty': [string, ...], "
    "'next_experiment': {'description': string, 'config_changes': object, "
    "'expected_evidence': string}, 'limits': [string, ...], "
    "'evidence_cited': [ids...]}. Cite ONLY supplied run/prediction ids. "
    "Never claim statistical significance from one pair, never state a "
    "cause, never claim Meta authored any measured change. No prose "
    "outside the JSON object."
)


class LoopError(Exception):
    """Loop failure; str() is 'CODE: detail' (detail has no secrets)."""

    def __init__(self, code, detail=""):
        super().__init__("%s: %s" % (code, detail) if detail else code)
        self.code = code
        self.detail = detail


def _utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _sha_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _check_loop_id(loop_id):
    if not isinstance(loop_id, str) or not LOOP_ID_RE.match(loop_id):
        raise LoopError("BAD_LOOP_ID", "loop id has illegal characters")
    return loop_id


def _manifest_path(manifest_dir, loop_id):
    return Path(manifest_dir) / (_check_loop_id(loop_id) + ".json")


def _write_manifest(path, manifest):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp-%s" % secrets.token_hex(4))
    tmp.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    os.replace(tmp, path)


def _read_manifest(path):
    path = Path(path)
    if not path.is_file():
        raise LoopError("MANIFEST_NOT_FOUND", path.name)
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        raise LoopError("MANIFEST_CORRUPT", path.name) from None
    if not isinstance(manifest, dict) or "loop_id" not in manifest:
        raise LoopError("MANIFEST_CORRUPT", path.name)
    return manifest


def _set_status(manifest, stage, state, detail=""):
    manifest.setdefault("stages", {})[stage] = {
        "state": state,
        "detail": detail,
        "at": _utc_now(),
    }


def validate_candidate_config(n_ctx, n_threads, n_predict, seed):
    """Validate candidate config against the benchmark contract bounds."""
    errors = []
    if type(n_ctx) is not int or not 128 <= n_ctx <= 4096:
        errors.append("n_ctx must be an integer 128-4096")
    if type(n_threads) is not int or not 1 <= n_threads <= 16:
        errors.append("n_threads must be an integer 1-16")
    if type(n_predict) is not int or not 1 <= n_predict <= 256:
        errors.append("n_predict must be an integer 1-256")
    if type(seed) is not int:
        errors.append("seed must be an integer")
    if errors:
        raise LoopError("BAD_CONFIG", "; ".join(errors))
    return {"n_ctx": n_ctx, "n_threads": n_threads, "n_predict": n_predict,
            "seed": seed, "sampler": "greedy", "temperature": 0}


def load_profile(path):
    raw = Path(path)
    if not raw.is_file():
        raise LoopError("PROFILE_NOT_FOUND", raw.name)
    data = raw.read_bytes()
    if len(data) > profile_contract.MAX_JSON_BYTES:
        raise LoopError("PROFILE_TOO_LARGE", raw.name)
    try:
        profile = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise LoopError("PROFILE_MALFORMED", raw.name) from None
    errors = profile_contract.validate_profile(profile)
    if errors:
        raise LoopError("PROFILE_INVALID", "; ".join(errors[:3]))
    device = profile["device"]
    return {
        "filename": raw.name,
        "sha256": hashlib.sha256(data).hexdigest(),
        "model": device["model"],
        "manufacturer": device["manufacturer"],
        "api_level": device["sdk_int"],
        "collected_at_utc": profile["collected_at_utc"],
    }


def load_model(path, display=None):
    raw = Path(path)
    if not raw.is_file():
        raise LoopError("MODEL_NOT_FOUND", raw.name)
    if raw.suffix.lower() != ".gguf":
        raise LoopError("MODEL_NOT_GGUF", raw.name)
    return {
        "filename": raw.name,
        "display": display or raw.name,
        "sha256": _sha_file(raw),
        "size_bytes": raw.stat().st_size,
    }


def build_evidence_pack(db_path=None):
    """Assemble a bounded, labeled evidence pack (read-only)."""
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    pack = {"db_available": target.is_file(), "physical": [], "synthetic": []}
    if target.is_file():
        try:
            pack["db_sha256"] = _sha_file(target)
        except OSError:
            pack["db_sha256"] = None
    else:
        pack["status"] = "no local evidence database"
        return pack
    try:
        rows = physical_retrieval.retrieve_physical(
            status="COMPLETED", limit=MAX_EVIDENCE_ROWS, db_path=target
        )
    except Exception:
        pack["status"] = "physical retrieval unavailable"
        rows = []
    for row in rows[:MAX_EVIDENCE_ROWS]:
        pack["physical"].append(
            {
                "run_id": row["run_id"],
                "source_sha256": row["source_sha256"],
                "device_model": row["device_model"],
                "model_sha256": row["model_sha256"],
                "workload_id": row["workload_id"],
                "gen_ms": row["gen_ms"],
                "generated_tokens": row["generated_tokens"],
                "tps_gen": row["tps_gen"],
                "prefill_ms": row["prefill_ms"],
                "prefill_tokens": row["prefill_tokens"],
                "tps_prefill": row["tps_prefill"],
                "thermal": row["thermal_json"],
                "requested_opt": row["requested_opt"],
                "effective_opt": row["effective_opt"],
                "effective_opt_status": row["effective_opt_status"],
                "qualification_state": row["qualification_state"],
            }
        )
        config = json.loads(row["config_json"])
        pack["physical"][-1]["n_ctx"] = config.get("n_ctx")
        pack["physical"][-1]["n_threads"] = config.get("n_threads")
        pack["physical"][-1]["sampler"] = config.get("sampler")
    try:
        synth = synthetic_retrieval.retrieve_comparable_benchmarks(
            "android-8gb-test", "llama-3.2-1b", 1, db_path=target
        )
    except Exception:
        synth = []
    for record in synth:
        pack["synthetic"].append(
            {
                "benchmark_id": record["benchmark_id"],
                "predicted_tps": record["predicted_tps"],
                "measured_tps": record["measured_tps"],
                "record_source": record["record_source"],
                "physically_verified": record["physically_verified"],
                "label": "SYNTHETIC FIXTURE - not a measurement",
            }
        )
    pack["status"] = "ok"
    return pack


def _post(payload):
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        ENDPOINT,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["MODEL_API_KEY"],
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            detail = exc.read().decode("utf-8", "replace")[:MAX_BODY_PREVIEW]
        except Exception:
            detail = "<unreadable error body>"
        raise LoopError("META_HTTP_%s" % exc.code, detail) from None
    except urllib.error.URLError as exc:
        raise LoopError("META_UNREACHABLE", str(exc.reason)[:200]) from None


def _extract_output_text(response):
    if isinstance(response, dict) and isinstance(response.get("output_text"), str):
        return response["output_text"]
    chunks = []
    output = response.get("output") if isinstance(response, dict) else None
    if isinstance(output, list):
        for item in output:
            if not isinstance(item, dict) or item.get("type") != "message":
                continue
            for part in item.get("content") or []:
                if (
                    isinstance(part, dict)
                    and part.get("type") in ("output_text", "text")
                    and isinstance(part.get("text"), str)
                ):
                    chunks.append(part["text"])
    return "\n".join(chunks)


def _check_live_authorized(args):
    if not args.get("live"):
        raise LoopError("LIVE_NOT_AUTHORIZED",
                        "pass --live --authorize-live-call or use --mock-meta")
    if not args.get("authorize_live_call"):
        raise LoopError("LIVE_NOT_AUTHORIZED", "missing --authorize-live-call")
    if not os.environ.get("MODEL_API_KEY"):
        raise LoopError("LIVE_NO_KEY", "MODEL_API_KEY not in environment")


def _model_text(system, user, args, mock_path):
    """One bounded model request (live) or mock-file read (offline)."""
    if mock_path is not None:
        raw = Path(mock_path)
        if not raw.is_file():
            raise LoopError("MOCK_NOT_FOUND", raw.name)
        text = raw.read_text(encoding="utf-8")
        if len(text) > MAX_RAW_TEXT:
            raise LoopError("MOCK_TOO_LARGE", raw.name)
        return text, {"mode": "mock", "mock_file": raw.name}
    _check_live_authorized(args)
    response = _post(
        {"model": MODEL, "temperature": 0,
         "input": [{"role": "system", "content": system},
                   {"role": "user", "content": user}]}
    )
    usage = response.get("usage") if isinstance(response, dict) else None
    return _extract_output_text(response), {
        "mode": "live", "model": MODEL, "endpoint": ENDPOINT,
        "usage": usage if isinstance(usage, dict) else None,
    }


def _require_json_object(text, what):
    if not isinstance(text, str) or len(text) > MAX_RAW_TEXT:
        raise LoopError(what + "_NOT_JSON", "empty or oversized model output")
    try:
        value = json.loads(text)
    except ValueError:
        raise LoopError(what + "_NOT_JSON",
                        "model output was not a single JSON object") from None
    if not isinstance(value, dict):
        raise LoopError(what + "_NOT_JSON", "top level must be an object")
    return value


def _is_number(value):
    return type(value) in (int, float) and value == value and abs(value) != float("inf")


def validate_prediction(parsed, pack_run_ids):
    if not _is_number(parsed.get("predicted_gen_tps")) or parsed["predicted_gen_tps"] <= 0:
        raise LoopError("PREDICTION_INVALID", "predicted_gen_tps must be > 0")
    if not _is_number(parsed.get("predicted_prefill_tps")) or parsed["predicted_prefill_tps"] <= 0:
        raise LoopError("PREDICTION_INVALID", "predicted_prefill_tps must be > 0")
    if parsed.get("confidence") not in ("low", "medium", "high"):
        raise LoopError("PREDICTION_INVALID", "confidence must be low/medium/high")
    config = parsed.get("recommended_config")
    if not isinstance(config, dict):
        raise LoopError("PREDICTION_INVALID", "recommended_config must be an object")
    try:
        validate_candidate_config(config.get("n_ctx"), config.get("n_threads"),
                                  config.get("n_predict"), config.get("seed"))
    except LoopError as exc:
        raise LoopError("PREDICTION_INVALID", exc.detail) from None
    for key in ("rationale",):
        if not isinstance(parsed.get(key), str) or not parsed[key].strip():
            raise LoopError("PREDICTION_INVALID", key + " must be a non-empty string")
    for key in ("uncertainty_notes", "limits"):
        items = parsed.get(key)
        if not isinstance(items, list) or not items or len(items) > 8:
            raise LoopError("PREDICTION_INVALID", key + " must list 1-8 strings")
        if not all(isinstance(item, str) and item.strip() for item in items):
            raise LoopError("PREDICTION_INVALID", key + " must hold non-empty strings")
    cited = parsed.get("evidence_cited")
    if not isinstance(cited, list) or not all(isinstance(c, str) for c in cited):
        raise LoopError("PREDICTION_INVALID", "evidence_cited must be a string list")
    unknown = [c for c in cited if c not in pack_run_ids]
    if unknown:
        raise LoopError("PREDICTION_UNGROUNDED",
                        "cited ids absent from evidence pack: %s" % ",".join(unknown[:4]))
    return parsed


def validate_followup(parsed, allowed_ids):
    if not isinstance(parsed.get("error_assessment"), str) or not parsed["error_assessment"].strip():
        raise LoopError("FOLLOWUP_INVALID", "error_assessment must be non-empty")
    recommendation = parsed.get("recommendation")
    if not isinstance(recommendation, dict):
        raise LoopError("FOLLOWUP_INVALID", "recommendation must be an object")
    if recommendation.get("action") not in ("measure_again", "adopt_config", "reject_config"):
        raise LoopError("FOLLOWUP_INVALID", "recommendation.action unknown")
    for key in ("model", "rationale"):
        if not isinstance(recommendation.get(key), str) or not recommendation[key].strip():
            raise LoopError("FOLLOWUP_INVALID", "recommendation." + key + " must be non-empty")
    if not isinstance(recommendation.get("config"), dict):
        raise LoopError("FOLLOWUP_INVALID", "recommendation.config must be an object")
    for key in ("uncertainty", "limits"):
        items = parsed.get(key)
        if not isinstance(items, list) or not items or len(items) > 8:
            raise LoopError("FOLLOWUP_INVALID", key + " must list 1-8 strings")
    experiment = parsed.get("next_experiment")
    if not isinstance(experiment, dict):
        raise LoopError("FOLLOWUP_INVALID", "next_experiment must be an object")
    for key in ("description", "expected_evidence"):
        if not isinstance(experiment.get(key), str) or not experiment[key].strip():
            raise LoopError("FOLLOWUP_INVALID", "next_experiment." + key + " must be non-empty")
    if not isinstance(experiment.get("config_changes"), dict):
        raise LoopError("FOLLOWUP_INVALID", "next_experiment.config_changes must be an object")
    cited = parsed.get("evidence_cited")
    if not isinstance(cited, list) or not all(isinstance(c, str) for c in cited):
        raise LoopError("FOLLOWUP_INVALID", "evidence_cited must be a string list")
    unknown = [c for c in cited if c not in allowed_ids]
    if unknown:
        raise LoopError("FOLLOWUP_UNGROUNDED",
                        "cited ids not supplied: %s" % ",".join(unknown[:4]))
    return parsed


def cmd_init(options):
    profile = load_profile(options["profile"])
    model = load_model(options["model"], options.get("model_display"))
    config = validate_candidate_config(options["n_ctx"], options["n_threads"],
                                       options["n_predict"], options["seed"])
    workload = options.get("workload") or "edgefit-prompt-v1"
    if not isinstance(workload, str) or not workload or len(workload) > 64:
        raise LoopError("BAD_WORKLOAD", "workload id must be 1-64 chars")
    pack = build_evidence_pack(options.get("db"))
    pack_ids = [r["run_id"] for r in pack["physical"]]
    pack_ids += [r["benchmark_id"] for r in pack["synthetic"]]
    user = (
        "Device profile (platform-reported, SHA %s): %s %s, API %s, collected %s.\n"
        "Candidate model: %s (SHA %s, %d bytes).\n"
        "Candidate config: %s. Workload: %s.\n"
        "Evidence pack: %s"
        % (profile["sha256"], profile["manufacturer"], profile["model"],
           profile["api_level"], profile["collected_at_utc"], model["display"],
           model["sha256"], model["size_bytes"], json.dumps(config), workload,
           json.dumps(pack))
    )
    text, provenance = _model_text(PREDICT_SYSTEM, user, options, options.get("mock_meta"))
    parsed = validate_prediction(_require_json_object(text, "PREDICTION"), pack_ids)
    loop_id = options.get("loop_id") or (
        "LOOP-%s-%s" % (_utc_now().replace(":", "").replace("-", ""), secrets.token_hex(4).upper())
    )
    manifest_dir = options.get("manifest_dir") or DEFAULT_MANIFEST_DIR
    path = _manifest_path(manifest_dir, loop_id)
    if path.is_file():
        raise LoopError("MANIFEST_EXISTS", path.name)
    manifest = {
        "loop_id": _check_loop_id(loop_id),
        "mode": "prospective",
        "created_at": _utc_now(),
        "profile": profile,
        "model": model,
        "candidate_config": config,
        "workload": workload,
        "evidence_pack": {"status": pack["status"],
                          "db_sha256": pack.get("db_sha256"),
                          "physical_run_ids": [r["run_id"] for r in pack["physical"]],
                          "synthetic_ids": [r["benchmark_id"] for r in pack["synthetic"]]},
        "prediction": {"raw_text": text, "parsed": parsed, "provenance": provenance,
                       "persisted_at": _utc_now()},
        "benchmark": None,
        "errors": None,
        "followup": None,
        "stages": {},
    }
    _set_status(manifest, "prediction", "ok", provenance["mode"])
    _set_status(manifest, "benchmark", "pending", "phone-required: run the benchmark, then attach-bench")
    _write_manifest(path, manifest)
    return {"loop_id": loop_id, "manifest": str(path),
            "predicted_gen_tps": parsed["predicted_gen_tps"],
            "mode": provenance["mode"]}


def load_bench(path):
    raw = Path(path)
    if not raw.is_file():
        raise LoopError("BENCH_NOT_FOUND", raw.name)
    data = raw.read_bytes()
    if len(data) > bench_contract.MAX_JSON_BYTES:
        raise LoopError("BENCH_TOO_LARGE", raw.name)
    try:
        bench = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise LoopError("BENCH_MALFORMED", raw.name) from None
    errors = bench_contract.validate_result(bench)
    if errors:
        raise LoopError("BENCH_INVALID", "; ".join(errors[:3]))
    return bench, hashlib.sha256(data).hexdigest(), raw.name


def cmd_attach_bench(options):
    manifest_dir = options.get("manifest_dir") or DEFAULT_MANIFEST_DIR
    path = _manifest_path(manifest_dir, options["manifest"])
    manifest = _read_manifest(path)
    if manifest.get("benchmark") is not None:
        raise LoopError("BENCH_ALREADY_ATTACHED", manifest["loop_id"])
    bench, digest, filename = load_bench(options["bench"])
    if bench["status"] != "COMPLETED":
        raise LoopError("BENCH_NOT_COMPLETED", bench["run_id"])
    if bench["model"]["sha256"] != manifest["model"]["sha256"]:
        raise LoopError("MODEL_MISMATCH", "benchmark model differs from candidate")
    if bench["workload"]["id"] != manifest["workload"]:
        raise LoopError("WORKLOAD_MISMATCH", "benchmark workload differs")
    if bench["device"]["model"] != manifest["profile"]["model"]:
        raise LoopError("DEVICE_MISMATCH", "benchmark phone differs from profile")
    candidate = manifest["candidate_config"]
    bench_config = bench["config"]
    for key in ("n_ctx", "n_threads", "n_predict", "sampler"):
        if bench_config.get(key) != candidate.get(key):
            raise LoopError("CONFIG_MISMATCH", "benchmark %s differs" % key)
    if manifest.get("mode") == "prospective" and not (
            bench["collected_at_utc"] > manifest["prediction"]["persisted_at"]):
        raise LoopError("PREDICTION_NOT_FIRST",
                        "measurement predates the persisted prediction")
    measurements = bench["measurements"]
    predicted_gen = manifest["prediction"]["parsed"]["predicted_gen_tps"]
    predicted_pre = manifest["prediction"]["parsed"]["predicted_prefill_tps"]
    gen_err = measurements["tps_gen"] - predicted_gen
    pre_err = measurements["tps_prefill"] - predicted_pre
    manifest["benchmark"] = {
        "filename": filename,
        "source_sha256": digest,
        "run_id": bench["run_id"],
        "collected_at_utc": bench["collected_at_utc"],
        "measurements": {k: measurements[k] for k in
                         ("load_ms", "prefill_ms", "prefill_tokens", "gen_ms",
                          "generated_tokens", "tps_gen", "tps_prefill")},
    }
    manifest["errors"] = {
        "gen_abs_tps": round(gen_err, 3),
        "gen_pct": round(100.0 * gen_err / predicted_gen, 2),
        "prefill_abs_tps": round(pre_err, 3),
        "prefill_pct": round(100.0 * pre_err / predicted_pre, 2),
        "from_persisted_prediction": True,
    }
    _set_status(manifest, "benchmark", "ok", bench["run_id"])
    _set_status(manifest, "association", "ok", "identities + integrity verified")
    _set_status(manifest, "error_computation", "ok",
                "gen %+g tps (%+g%%)" % (manifest["errors"]["gen_abs_tps"],
                                         manifest["errors"]["gen_pct"]))
    _write_manifest(path, manifest)
    return {"loop_id": manifest["loop_id"], "run_id": bench["run_id"],
            "errors": manifest["errors"]}


def cmd_followup(options):
    manifest_dir = options.get("manifest_dir") or DEFAULT_MANIFEST_DIR
    path = _manifest_path(manifest_dir, options["manifest"])
    manifest = _read_manifest(path)
    if manifest.get("benchmark") is None or manifest.get("errors") is None:
        raise LoopError("NO_MEASUREMENT", "attach a benchmark first")
    if manifest.get("followup") is not None:
        raise LoopError("FOLLOWUP_DONE", manifest["loop_id"])
    allowed = [manifest["benchmark"]["run_id"], manifest["loop_id"]]
    allowed += manifest.get("evidence_pack", {}).get("physical_run_ids", [])
    user = (
        "Persisted prediction for %s: %s.\n"
        "On-device measurement %s: %s.\n"
        "Host-computed errors: %s.\n"
        "Provenance: profile SHA %s, model SHA %s, benchmark SHA %s, "
        "prediction persisted at %s."
        % (manifest["loop_id"],
           json.dumps(manifest["prediction"]["parsed"]),
           manifest["benchmark"]["run_id"],
           json.dumps(manifest["benchmark"]["measurements"]),
           json.dumps(manifest["errors"]),
           manifest["profile"]["sha256"], manifest["model"]["sha256"],
           manifest["benchmark"]["source_sha256"],
           manifest["prediction"]["persisted_at"])
    )
    text, provenance = _model_text(FOLLOWUP_SYSTEM, user, options, options.get("mock_meta"))
    parsed = validate_followup(_require_json_object(text, "FOLLOWUP"), allowed)
    manifest["followup"] = {"raw_text": text, "parsed": parsed,
                            "provenance": provenance, "at": _utc_now()}
    _set_status(manifest, "followup", "ok", provenance["mode"])
    _set_status(manifest, "recommendation", "ok",
                parsed["recommendation"]["action"])
    _write_manifest(path, manifest)
    return {"loop_id": manifest["loop_id"],
            "action": parsed["recommendation"]["action"],
            "mode": provenance["mode"]}


def cmd_replay(options):
    """Historical replay: supplied prediction + existing bench, honestly labeled."""
    profile = load_profile(options["profile"])
    bench, digest, filename = load_bench(options["bench"])
    raw = Path(options["prediction_json"])
    if not raw.is_file():
        raise LoopError("REPLAY_PREDICTION_NOT_FOUND", raw.name)
    supplied = _require_json_object(raw.read_text(encoding="utf-8"), "REPLAY_PREDICTION")
    for key in ("predicted_gen_tps", "predicted_prefill_tps"):
        if not _is_number(supplied.get(key)) or supplied[key] <= 0:
            raise LoopError("REPLAY_PREDICTION_INVALID", key + " must be > 0")
    loop_id = options.get("loop_id") or (
        "REPLAY-%s-%s" % (_utc_now().replace(":", "").replace("-", ""), secrets.token_hex(4).upper())
    )
    manifest_dir = options.get("manifest_dir") or DEFAULT_MANIFEST_DIR
    path = _manifest_path(manifest_dir, loop_id)
    if path.is_file():
        raise LoopError("MANIFEST_EXISTS", path.name)
    measurements = bench["measurements"]
    gen_err = measurements["tps_gen"] - supplied["predicted_gen_tps"]
    pre_err = measurements["tps_prefill"] - supplied["predicted_prefill_tps"]
    manifest = {
        "loop_id": _check_loop_id(loop_id),
        "mode": "historical_replay",
        "created_at": _utc_now(),
        "profile": profile,
        "model": {"filename": None,
                  "display": bench["model"]["display_name"],
                  "sha256": bench["model"]["sha256"],
                  "size_bytes": bench["model"]["size_bytes"]},
        "candidate_config": {k: bench["config"].get(k) for k in
                             ("n_ctx", "n_threads", "n_predict", "seed",
                              "sampler", "temperature")},
        "workload": bench["workload"]["id"],
        "evidence_pack": {"status": "replay: not requested",
                          "note": "historical replay; no fresh Meta prediction"},
        "prediction": {"raw_text": raw.read_text(encoding="utf-8"),
                       "parsed": supplied,
                       "provenance": {"mode": "replay_supplied",
                                      "note": "supplied by operator for replay; "
                                              "NOT a fresh Meta prediction"},
                       "persisted_at": _utc_now()},
        "benchmark": {
            "filename": filename,
            "source_sha256": digest,
            "run_id": bench["run_id"],
            "collected_at_utc": bench["collected_at_utc"],
            "measurements": {k: measurements[k] for k in
                             ("load_ms", "prefill_ms", "prefill_tokens", "gen_ms",
                              "generated_tokens", "tps_gen", "tps_prefill")},
        },
        "errors": {
            "gen_abs_tps": round(gen_err, 3),
            "gen_pct": round(100.0 * gen_err / supplied["predicted_gen_tps"], 2),
            "prefill_abs_tps": round(pre_err, 3),
            "prefill_pct": round(100.0 * pre_err / supplied["predicted_prefill_tps"], 2),
            "from_persisted_prediction": True,
        },
        "followup": None,
        "stages": {},
    }
    _set_status(manifest, "prediction", "ok", "replay_supplied (not fresh)")
    _set_status(manifest, "benchmark", "ok", bench["run_id"])
    _set_status(manifest, "association", "ok", "replay: identities verified")
    _set_status(manifest, "error_computation", "ok", "replay errors computed")
    _write_manifest(path, manifest)
    return {"loop_id": loop_id, "mode": "historical_replay",
            "run_id": bench["run_id"], "errors": manifest["errors"]}


def cmd_status(options):
    manifest_dir = options.get("manifest_dir") or DEFAULT_MANIFEST_DIR
    manifest = _read_manifest(_manifest_path(manifest_dir, options["manifest"]))
    lines = ["LOOP %s (%s)" % (manifest["loop_id"], manifest.get("mode"))]
    for stage in ("prediction", "benchmark", "association",
                  "error_computation", "followup", "recommendation"):
        info = manifest.get("stages", {}).get(stage, {"state": "pending"})
        lines.append("  %-16s %s %s" % (stage, info.get("state"),
                                        info.get("detail", "")))
    if manifest.get("errors"):
        lines.append("  errors: gen %+g tps (%+g%%), prefill %+g tps (%+g%%)"
                     % (manifest["errors"]["gen_abs_tps"],
                        manifest["errors"]["gen_pct"],
                        manifest["errors"]["prefill_abs_tps"],
                        manifest["errors"]["prefill_pct"]))
    if manifest.get("followup"):
        lines.append("  recommendation: %s"
                     % manifest["followup"]["parsed"]["recommendation"]["action"])
    return {"report": "\n".join(lines)}


def _usage():
    return (
        "usage:\n"
        "  ef_loop_01.py init --profile P --model M.gguf --n-ctx N --n-threads T\n"
        "      --n-predict K [--seed S] [--model-display D] [--workload W]\n"
        "      (--mock-meta FILE | --live --authorize-live-call)\n"
        "      [--loop-id ID] [--manifest-dir DIR] [--db PATH]\n"
        "  ef_loop_01.py attach-bench --manifest ID --bench B.json [--manifest-dir DIR]\n"
        "  ef_loop_01.py followup --manifest ID (--mock-meta FILE | --live --authorize-live-call)\n"
        "      [--manifest-dir DIR]\n"
        "  ef_loop_01.py status --manifest ID [--manifest-dir DIR]\n"
        "  ef_loop_01.py replay --profile P --bench B.json --prediction-json J.json\n"
        "      [--loop-id ID] [--manifest-dir DIR]"
    )


def _parse_int(raw, name):
    try:
        value = int(raw)
    except (TypeError, ValueError):
        raise LoopError("BAD_CONFIG", "%s must be an integer" % name) from None
    return value


def main(argv):
    args = argv[1:]
    if not args or args[0] not in ("init", "attach-bench", "followup",
                                   "status", "replay"):
        print(_usage())
        return 2
    command = args.pop(0)
    options = {"seed": 42}
    key = None
    for token in args:
        if key is not None:
            options[key] = token
            key = None
        elif token == "--live":
            options["live"] = True
        elif token == "--authorize-live-call":
            options["authorize_live_call"] = True
        elif token.startswith("--"):
            key = token[2:].replace("-", "_")
        else:
            print(_usage())
            return 2
    if key is not None:
        print(_usage())
        return 2
    try:
        if command == "init":
            for required in ("profile", "model", "n_ctx", "n_threads", "n_predict"):
                if required not in options:
                    raise LoopError("MISSING_OPTION", "--" + required.replace("_", "-"))
            for numeric in ("n_ctx", "n_threads", "n_predict", "seed"):
                if numeric in options:
                    options[numeric] = _parse_int(options[numeric], numeric)
            if options.get("mock_meta") and options.get("live"):
                raise LoopError("MOCK_XOR_LIVE", "use one of --mock-meta or --live")
            if not options.get("mock_meta") and not options.get("live"):
                raise LoopError("MOCK_XOR_LIVE", "use one of --mock-meta or --live")
            result = cmd_init(options)
            print("LOOP INIT: %s (prediction via %s)" % (result["loop_id"], result["mode"]))
            print("  manifest: %s" % result["manifest"])
            print("  predicted_gen_tps: %s" % result["predicted_gen_tps"])
            print("  NEXT (phone-required): run the benchmark with n_ctx=%s n_threads=%s "
                  "n_predict=%s, export the JSON, then run attach-bench."
                  % (options["n_ctx"], options["n_threads"], options["n_predict"]))
        elif command == "attach-bench":
            for required in ("manifest", "bench"):
                if required not in options:
                    raise LoopError("MISSING_OPTION", "--" + required)
            result = cmd_attach_bench(options)
            print("LOOP ATTACH: %s <- %s" % (result["loop_id"], result["run_id"]))
            print("  errors: %s" % json.dumps(result["errors"]))
        elif command == "followup":
            if "manifest" not in options:
                raise LoopError("MISSING_OPTION", "--manifest")
            if options.get("mock_meta") and options.get("live"):
                raise LoopError("MOCK_XOR_LIVE", "use one of --mock-meta or --live")
            if not options.get("mock_meta") and not options.get("live"):
                raise LoopError("MOCK_XOR_LIVE", "use one of --mock-meta or --live")
            result = cmd_followup(options)
            print("LOOP FOLLOWUP: %s -> %s (via %s)"
                  % (result["loop_id"], result["action"], result["mode"]))
        elif command == "status":
            if "manifest" not in options:
                raise LoopError("MISSING_OPTION", "--manifest")
            print(cmd_status(options)["report"])
        elif command == "replay":
            for required in ("profile", "bench", "prediction_json"):
                if required not in options:
                    raise LoopError("MISSING_OPTION", "--" + required.replace("_", "-"))
            result = cmd_replay(options)
            print("LOOP REPLAY: %s <- %s (historical replay; supplied prediction)"
                  % (result["loop_id"], result["run_id"]))
            print("  errors: %s" % json.dumps(result["errors"]))
        return 0
    except LoopError as exc:
        print(json.dumps({"error": exc.code, "detail": exc.detail}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
