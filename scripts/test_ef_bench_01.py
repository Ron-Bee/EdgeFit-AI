"""EdgeFit EF-BENCH-01 result contract tests.

Validates scripts/ef_bench_result_01.py against fixtures. No network,
no device, no SQLite writes.

Run: python scripts/test_ef_bench_01.py
"""

from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_bench_result_01 as checker

VALID = {
    "contract": "edgefit-bench-result",
    "contract_version": 1,
    "run_id": "BENCH-20261027T000000Z-abcdef12",
    "device": {
        "profile_ref": "0" * 64,
        "model": "SM-S908U",
        "sdk_int": 36,
        "supported_abis": ["arm64-v8a"],
        "logical_processors": 8,
        "total_ram_bytes": 12884901888,
        "provenance": "platform_reported",
    },
    "model": {
        "display_name": "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        "size_bytes": 807694464,
        "sha256": "1" * 64,
        "quantization": "4",
        "metadata": {"general.architecture": "llama"},
    },
    "runtime": {
        "impl": "llama.cpp",
        "version": "v0.7.0-test",
        "backend": "cpu",
        "abi": "arm64-v8a",
    },
    "config": {
        "n_ctx": 512,
        "n_threads": 4,
        "n_predict": 64,
        "temperature": 0,
        "sampler": "greedy",
        "seed": 42,
        "timeout_ms": 600000,
    },
    "workload": {
        "id": "edgefit-prompt-v1",
        "prompt_sha256": "2" * 64,
        "prompt_chars": 120,
    },
    "measurements": {
        "load_ms": 1200,
        "prefill_ms": 300,
        "prefill_tokens": 40,
        "gen_ms": 3200,
        "generated_tokens": 64,
        "tps_gen": 20.0,
        "tps_prefill": 40 * 1000 / 300,
        "eos_stop": False,
        "mem": {
            "pss_before_kb": 100,
            "pss_after_kb": 200,
            "alloc_delta_bytes": 4096,
            "method": "debug-memoryinfo+mallinfo2",
        },
        "thermal": {
            "before": "none",
            "mid": "light",
            "after": "light",
            "available": True,
        },
    },
    "status": "COMPLETED",
    "error": None,
    "provenance": "on_device_measurement",
    "app_version": "0.2.0",
    "collected_at_utc": "2026-10-27T00:00:00Z",
}


def fresh():
    return copy.deepcopy(VALID)


class StatusTest(unittest.TestCase):
    def test_valid_completed(self):
        self.assertEqual(checker.validate_result(fresh()), [])

    def test_cancelled_timeout_failed_require_error(self):
        for status in ("CANCELLED", "TIMEOUT", "FAILED"):
            with self.subTest(status=status):
                result = fresh()
                result["status"] = status
                result["error"] = "stopped for test reasons"
                self.assertEqual(checker.validate_result(result), [])

    def test_completed_rejects_error(self):
        result = fresh()
        result["error"] = "stray"
        self.assertTrue(checker.validate_result(result))

    def test_non_completed_rejects_missing_error(self):
        result = fresh()
        result["status"] = "FAILED"
        result["error"] = None
        self.assertTrue(checker.validate_result(result))

    def test_unknown_status_rejected(self):
        result = fresh()
        result["status"] = "RUNNING"
        self.assertTrue(checker.validate_result(result))

    def test_unknown_thermal_and_method_rejected(self):
        result = fresh()
        result["measurements"]["thermal"]["mid"] = "melting"
        self.assertTrue(checker.validate_result(result))
        result = fresh()
        result["measurements"]["mem"]["method"] = "guessed"
        self.assertTrue(checker.validate_result(result))

    def test_alloc_unavailable_method_accepted(self):
        result = fresh()
        result["measurements"]["mem"]["method"] = (
            "debug-memoryinfo+mallinfo2;alloc_unavailable"
        )
        self.assertEqual(checker.validate_result(result), [])


class SchemaTest(unittest.TestCase):
    def test_missing_sections(self):
        for key in (
            "contract", "contract_version", "run_id", "device", "model",
            "runtime", "config", "workload", "measurements", "status",
            "provenance", "app_version", "collected_at_utc",
        ):
            with self.subTest(key=key):
                result = fresh()
                del result[key]
                self.assertTrue(checker.validate_result(result))

    def test_bad_run_id_and_hashes(self):
        result = fresh()
        result["run_id"] = "nope"
        self.assertTrue(checker.validate_result(result))
        result = fresh()
        result["model"]["sha256"] = "xyz"
        self.assertTrue(checker.validate_result(result))
        result = fresh()
        result["device"]["profile_ref"] = "xyz"
        self.assertTrue(checker.validate_result(result))

    def test_config_ranges(self):
        cases = [
            ("n_ctx", 64), ("n_ctx", 8192), ("n_threads", 0),
            ("n_threads", 17), ("n_predict", 0), ("n_predict", 300),
            ("temperature", 0.5), ("sampler", "top_k"),
            ("timeout_ms", 0), ("seed", "42"),
        ]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                result = fresh()
                result["config"][key] = value
                self.assertTrue(checker.validate_result(result))

    def test_tps_consistency(self):
        result = fresh()
        result["measurements"]["tps_gen"] = 999.0
        self.assertTrue(checker.validate_result(result))
        result = fresh()
        result["measurements"]["tps_prefill"] = 999.0
        self.assertTrue(checker.validate_result(result))

    def test_eos_stop_type(self):
        result = fresh()
        result["measurements"]["eos_stop"] = "yes"
        self.assertTrue(checker.validate_result(result))

    def test_unknown_key_and_version(self):
        result = fresh()
        result["telemetry"] = {}
        self.assertTrue(checker.validate_result(result))
        result = fresh()
        result["contract_version"] = 2
        self.assertTrue(checker.validate_result(result))
        result = fresh()
        result["x_note"] = "extensions allowed"
        self.assertEqual(checker.validate_result(result), [])

    def test_native_build_provenance_accepted(self):
        result = fresh()
        result["x_native_build"] = {"variant": "benchmark", "native_opt": "-O3 -g"}
        self.assertEqual(checker.validate_result(result), [])
        legacy = fresh()
        self.assertNotIn("x_native_build", legacy)
        self.assertEqual(checker.validate_result(legacy), [])

    def test_oversize_rejected(self):
        result = fresh()
        result["x_blob"] = "x" * 70000
        errors = checker.validate_result(result)
        self.assertTrue(any("bytes" in e for e in errors))

    def test_unsupported_contract_rejected(self):
        result = fresh()
        result["contract"] = "edgefit-android-profile"
        self.assertTrue(checker.validate_result(result))


class CheckerCliTest(unittest.TestCase):
    def run_checker(self, payload):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "result.json"
            target.write_text(payload, encoding="utf-8")
            script = Path(__file__).resolve().parent / "ef_bench_result_01.py"
            return subprocess.run(
                [sys.executable, str(script), str(target)],
                capture_output=True, text=True, timeout=60,
            )

    def test_cli_valid(self):
        result = self.run_checker(json.dumps(fresh()))
        self.assertEqual(result.returncode, 0)
        self.assertIn("VALID", result.stdout)

    def test_cli_invalid(self):
        broken = fresh()
        del broken["measurements"]
        result = self.run_checker(json.dumps(broken))
        self.assertEqual(result.returncode, 1)
        self.assertIn("INVALID", result.stdout)

    def test_cli_usage(self):
        script = Path(__file__).resolve().parent / "ef_bench_result_01.py"
        result = subprocess.run(
            [sys.executable, str(script)], capture_output=True, text=True, timeout=60
        )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
