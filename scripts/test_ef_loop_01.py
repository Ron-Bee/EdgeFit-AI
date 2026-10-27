"""EdgeFit EF-LOOP-01 offline tests.

Hermetic temp-manifest tests with mocked Meta responses (mock files are
synthetic test artifacts). The real SQLite database is read, never
written; its hash is asserted unchanged.

Run: python scripts/test_ef_loop_01.py
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_loop_01 as loop
from ef_loop_01 import LoopError

REAL_DB = Path(__file__).resolve().parent.parent / "data" / "edgefit.db"

GEN_MS = 167185
GEN_TOKENS = 64
PRE_MS = 38609
PRE_TOKENS = 23
FUTURE_STAMP = "2030-01-01T00:00:00Z"
PAST_STAMP = "2020-01-01T00:00:00Z"


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(testcase, payload, suffix=".json"):
    fd, name = tempfile.mkstemp(suffix=suffix)
    os.close(fd)
    path = Path(name)
    testcase.addCleanup(lambda: path.unlink(missing_ok=True))
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def write_text(testcase, text, suffix=".txt"):
    fd, name = tempfile.mkstemp(suffix=suffix)
    os.close(fd)
    path = Path(name)
    testcase.addCleanup(lambda: path.unlink(missing_ok=True))
    path.write_text(text, encoding="utf-8")
    return path


def make_profile(**overrides):
    profile = {
        "contract": "edgefit-android-profile",
        "contract_version": 1,
        "collector": {"name": "EdgeFitDiag", "version": "0.2.0"},
        "collected_at_utc": "2026-10-27T04:00:00Z",
        "device": {
            "manufacturer": "samsung",
            "model": "SM-S908U",
            "device_codename": "dm2q",
            "android_release": "16",
            "sdk_int": 36,
            "supported_abis": ["arm64-v8a"],
            "logical_processors": 8,
            "total_ram_bytes": 7574867968,
            "avail_ram_bytes": 2831155200,
            "provenance": "platform_reported",
        },
        "thermal": {"status_name": "none", "status_code": 0,
                    "provenance": "platform_reported"},
        "battery": {"level": 100, "scale": 100, "pct": 100.0,
                    "provenance": "platform_reported"},
        "unavailable": [],
    }
    for dotted, value in overrides.items():
        target = profile
        keys = dotted.split(".")
        for key in keys[:-1]:
            target = target[key]
        target[keys[-1]] = value
    return profile


def make_bench(model_sha, collected=FUTURE_STAMP, **overrides):
    bench = {
        "contract": "edgefit-bench-result",
        "contract_version": 1,
        "run_id": "BENCH-20300101T000000Z-aaaabbbb",
        "status": "COMPLETED",
        "error": None,
        "provenance": "on_device_measurement",
        "app_version": "0.2.0-test",
        "collected_at_utc": collected,
        "device": {
            "model": "SM-S908U",
            "sdk_int": 36,
            "supported_abis": ["arm64-v8a"],
            "logical_processors": 8,
            "total_ram_bytes": 7574867968,
            "profile_ref": "ab" * 32,
            "provenance": "platform_reported",
        },
        "model": {
            "sha256": model_sha,
            "size_bytes": 1234567,
            "display_name": "test-model.gguf",
            "quantization": "2",
            "metadata": {"general.architecture": "llama"},
        },
        "runtime": {
            "impl": "llama.cpp",
            "version": "0.7.0-dev",
            "backend": "cpu",
            "abi": "arm64-v8a",
        },
        "workload": {
            "id": "edgefit-prompt-v1",
            "prompt_sha256": "cd" * 32,
            "prompt_chars": 100,
        },
        "config": {
            "n_ctx": 512,
            "n_threads": 4,
            "n_predict": 64,
            "sampler": "greedy",
            "seed": 42,
            "temperature": 0,
            "timeout_ms": 600000,
        },
        "measurements": {
            "load_ms": 3000,
            "prefill_ms": PRE_MS,
            "prefill_tokens": PRE_TOKENS,
            "gen_ms": GEN_MS,
            "generated_tokens": GEN_TOKENS,
            "tps_gen": GEN_TOKENS * 1000.0 / GEN_MS,
            "tps_prefill": PRE_TOKENS * 1000.0 / PRE_MS,
            "eos_stop": False,
            "mem": {"pss_before_kb": 1, "pss_after_kb": 2,
                    "alloc_delta_bytes": 3,
                    "method": "debug-memoryinfo+mallinfo2"},
            "thermal": {"before": "none", "mid": "none", "after": "moderate",
                        "available": True},
        },
    }
    for dotted, value in overrides.items():
        target = bench
        keys = dotted.split(".")
        for key in keys[:-1]:
            target = target[key]
        target[keys[-1]] = value
    return bench


def mock_prediction(**overrides):
    payload = {
        "predicted_gen_tps": 0.4,
        "predicted_prefill_tps": 0.6,
        "confidence": "low",
        "recommended_config": {"n_ctx": 512, "n_threads": 4,
                               "n_predict": 64, "seed": 42},
        "rationale": "synthetic test artifact",
        "uncertainty_notes": ["uncalibrated guess"],
        "evidence_cited": [],
        "limits": ["single-device guess"],
    }
    payload.update(overrides)
    return payload


def mock_followup(**overrides):
    payload = {
        "error_assessment": "synthetic test artifact",
        "recommendation": {"action": "measure_again", "model": "test-model",
                           "config": {"n_threads": 4},
                           "rationale": "re-run to check variance"},
        "uncertainty": ["variance unknown"],
        "next_experiment": {"description": "repeat run",
                            "config_changes": {},
                            "expected_evidence": "second measurement"},
        "limits": ["one pair only"],
        "evidence_cited": [],
    }
    payload.update(overrides)
    return payload


class LoopTestBase(unittest.TestCase):
    def manifest_dir(self):
        path = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: None)
        return path

    def model_file(self, name="test-model.gguf", data=b"GGUF-fake-weights"):
        path = Path(tempfile.mkdtemp()) / name
        path.write_bytes(data)
        self.addCleanup(lambda: path.unlink(missing_ok=True))
        return path

    def init_options(self, manifest_dir, mock_payload, **overrides):
        profile = write_json(self, make_profile())
        model = self.model_file()
        mock_file = write_json(self, mock_payload)
        options = {
            "profile": str(profile),
            "model": str(model),
            "n_ctx": 512,
            "n_threads": 4,
            "n_predict": 64,
            "seed": 42,
            "mock_meta": str(mock_file),
            "manifest_dir": str(manifest_dir),
            "loop_id": "LOOP-TEST-0001",
        }
        options.update(overrides)
        return options

    def attached_loop(self, manifest_dir, loop_id="LOOP-TEST-0001"):
        options = self.init_options(manifest_dir, mock_prediction(),
                                    loop_id=loop_id)
        loop.cmd_init(options)
        model_sha = hashlib.sha256(b"GGUF-fake-weights").hexdigest()
        bench = write_json(self, make_bench(model_sha))
        loop.cmd_attach_bench({"manifest": loop_id, "bench": str(bench),
                               "manifest_dir": str(manifest_dir)})
        return loop_id


class InitTest(LoopTestBase):
    def test_init_persists_prediction_before_benchmark(self):
        manifest_dir = self.manifest_dir()
        result = loop.cmd_init(self.init_options(manifest_dir, mock_prediction()))
        self.assertEqual(result["loop_id"], "LOOP-TEST-0001")
        manifest = json.loads(
            (manifest_dir / "LOOP-TEST-0001.json").read_text(encoding="utf-8")
        )
        self.assertEqual(manifest["mode"], "prospective")
        self.assertEqual(
            manifest["prediction"]["parsed"]["predicted_gen_tps"], 0.4
        )
        self.assertEqual(manifest["prediction"]["provenance"]["mode"], "mock")
        self.assertIsNone(manifest["benchmark"])
        self.assertEqual(manifest["stages"]["prediction"]["state"], "ok")

    def test_manifest_stores_no_local_paths(self):
        manifest_dir = self.manifest_dir()
        loop.cmd_init(self.init_options(manifest_dir, mock_prediction()))
        text = (manifest_dir / "LOOP-TEST-0001.json").read_text(encoding="utf-8")
        self.assertNotIn(str(manifest_dir), text)
        self.assertNotIn(":\\", text)
        manifest = json.loads(text)
        self.assertNotIn("/", manifest["profile"]["filename"])
        self.assertNotIn("/", manifest["model"]["filename"])

    def test_init_validates_candidate_config(self):
        manifest_dir = self.manifest_dir()
        for bad in ({"n_ctx": 99999}, {"n_threads": 0}, {"n_predict": 300},
                    {"seed": "x"}):
            options = self.init_options(manifest_dir, mock_prediction(), **bad)
            with self.subTest(bad=bad), self.assertRaises(LoopError) as ctx:
                loop.cmd_init(options)
            self.assertEqual(ctx.exception.code, "BAD_CONFIG")

    def test_init_rejects_bad_profile(self):
        manifest_dir = self.manifest_dir()
        missing = str(Path(tempfile.mkdtemp()) / "absent.json")
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_init(self.init_options(manifest_dir, mock_prediction(),
                                            profile=missing))
        self.assertEqual(ctx.exception.code, "PROFILE_NOT_FOUND")
        bad = write_json(self, {"contract": "nope"})
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_init(self.init_options(manifest_dir, mock_prediction(),
                                            profile=str(bad)))
        self.assertEqual(ctx.exception.code, "PROFILE_INVALID")

    def test_init_rejects_non_gguf_model(self):
        manifest_dir = self.manifest_dir()
        other = self.model_file(name="weights.bin")
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_init(self.init_options(manifest_dir, mock_prediction(),
                                            model=str(other)))
        self.assertEqual(ctx.exception.code, "MODEL_NOT_GGUF")

    def test_init_rejects_prose_and_ungrounded_prediction(self):
        manifest_dir = self.manifest_dir()
        prose = write_text(self, "I think it will be fast.")
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_init(self.init_options(manifest_dir, mock_prediction(),
                                            mock_meta=str(prose)))
        self.assertEqual(ctx.exception.code, "PREDICTION_NOT_JSON")
        bad = mock_prediction(evidence_cited=["BENCH-FAKE"])
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_init(self.init_options(manifest_dir, bad))
        self.assertEqual(ctx.exception.code, "PREDICTION_UNGROUNDED")

    def test_init_live_refused_without_gates(self):
        manifest_dir = self.manifest_dir()
        options = self.init_options(manifest_dir, mock_prediction())
        del options["mock_meta"]
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_init(options)
        self.assertEqual(ctx.exception.code, "LIVE_NOT_AUTHORIZED")

    def test_init_live_needs_key_and_never_posts_offline(self):
        manifest_dir = self.manifest_dir()
        options = self.init_options(manifest_dir, mock_prediction())
        del options["mock_meta"]
        options["live"] = True
        options["authorize_live_call"] = True
        with mock.patch.dict(os.environ):
            os.environ.pop("MODEL_API_KEY", None)
            with mock.patch.object(
                loop, "_post", side_effect=AssertionError("network used")
            ):
                with self.assertRaises(LoopError) as ctx:
                    loop.cmd_init(options)
        self.assertEqual(ctx.exception.code, "LIVE_NO_KEY")

    def test_init_duplicate_loop_id_refused(self):
        manifest_dir = self.manifest_dir()
        loop.cmd_init(self.init_options(manifest_dir, mock_prediction()))
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_init(self.init_options(manifest_dir, mock_prediction()))
        self.assertEqual(ctx.exception.code, "MANIFEST_EXISTS")

    def test_loop_id_traversal_rejected(self):
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_status({"manifest": "../escape",
                             "manifest_dir": str(self.manifest_dir())})
        self.assertEqual(ctx.exception.code, "BAD_LOOP_ID")


class AttachTest(LoopTestBase):
    def test_attach_computes_errors_from_persisted_prediction(self):
        manifest_dir = self.manifest_dir()
        loop_id = self.attached_loop(manifest_dir)
        manifest = json.loads(
            (manifest_dir / (loop_id + ".json")).read_text(encoding="utf-8")
        )
        measured = GEN_TOKENS * 1000.0 / GEN_MS
        self.assertAlmostEqual(
            manifest["errors"]["gen_abs_tps"], round(measured - 0.4, 3)
        )
        self.assertAlmostEqual(
            manifest["errors"]["gen_pct"],
            round(100.0 * (measured - 0.4) / 0.4, 2),
        )
        self.assertTrue(manifest["errors"]["from_persisted_prediction"])
        self.assertEqual(manifest["stages"]["association"]["state"], "ok")

    def test_attach_identity_mismatches_refused(self):
        manifest_dir = self.manifest_dir()
        options = self.init_options(manifest_dir, mock_prediction())
        loop.cmd_init(options)
        model_sha = hashlib.sha256(b"GGUF-fake-weights").hexdigest()
        cases = [
            ("model", {"model.sha256": "00" * 32}, "MODEL_MISMATCH"),
            ("config", {"config.n_threads": 8}, "CONFIG_MISMATCH"),
            ("device", {"device.model": "OTHER-PHONE"}, "DEVICE_MISMATCH"),
        ]
        for name, override, code in cases:
            with self.subTest(name=name):
                bench = write_json(self, make_bench(model_sha, **override))
                with self.assertRaises(LoopError) as ctx:
                    loop.cmd_attach_bench(
                        {"manifest": "LOOP-TEST-0001", "bench": str(bench),
                         "manifest_dir": str(manifest_dir)}
                    )
                self.assertEqual(ctx.exception.code, code)

    def test_attach_prediction_must_precede_measurement(self):
        manifest_dir = self.manifest_dir()
        options = self.init_options(manifest_dir, mock_prediction())
        loop.cmd_init(options)
        model_sha = hashlib.sha256(b"GGUF-fake-weights").hexdigest()
        bench = write_json(self, make_bench(model_sha, collected=PAST_STAMP))
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_attach_bench(
                {"manifest": "LOOP-TEST-0001", "bench": str(bench),
                 "manifest_dir": str(manifest_dir)}
            )
        self.assertEqual(ctx.exception.code, "PREDICTION_NOT_FIRST")

    def test_attach_rejects_non_completed(self):
        manifest_dir = self.manifest_dir()
        options = self.init_options(manifest_dir, mock_prediction())
        loop.cmd_init(options)
        model_sha = hashlib.sha256(b"GGUF-fake-weights").hexdigest()
        bench = write_json(
            self, make_bench(model_sha, status="FAILED", error="boom")
        )
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_attach_bench(
                {"manifest": "LOOP-TEST-0001", "bench": str(bench),
                 "manifest_dir": str(manifest_dir)}
            )
        self.assertEqual(ctx.exception.code, "BENCH_NOT_COMPLETED")

    def test_attach_twice_refused(self):
        manifest_dir = self.manifest_dir()
        loop_id = self.attached_loop(manifest_dir)
        model_sha = hashlib.sha256(b"GGUF-fake-weights").hexdigest()
        bench = write_json(self, make_bench(model_sha))
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_attach_bench(
                {"manifest": loop_id, "bench": str(bench),
                 "manifest_dir": str(manifest_dir)}
            )
        self.assertEqual(ctx.exception.code, "BENCH_ALREADY_ATTACHED")


class FollowupTest(LoopTestBase):
    def test_followup_mock_completes_loop(self):
        manifest_dir = self.manifest_dir()
        loop_id = self.attached_loop(manifest_dir)
        mock_file = write_json(self, mock_followup(evidence_cited=[loop_id]))
        result = loop.cmd_followup(
            {"manifest": loop_id, "mock_meta": str(mock_file),
             "manifest_dir": str(manifest_dir)}
        )
        self.assertEqual(result["action"], "measure_again")
        manifest = json.loads(
            (manifest_dir / (loop_id + ".json")).read_text(encoding="utf-8")
        )
        self.assertEqual(manifest["stages"]["recommendation"]["state"], "ok")
        self.assertEqual(manifest["followup"]["provenance"]["mode"], "mock")

    def test_followup_requires_measurement(self):
        manifest_dir = self.manifest_dir()
        loop.cmd_init(self.init_options(manifest_dir, mock_prediction()))
        mock_file = write_json(self, mock_followup())
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_followup(
                {"manifest": "LOOP-TEST-0001", "mock_meta": str(mock_file),
                 "manifest_dir": str(manifest_dir)}
            )
        self.assertEqual(ctx.exception.code, "NO_MEASUREMENT")

    def test_followup_rejects_ungrounded_citation(self):
        manifest_dir = self.manifest_dir()
        loop_id = self.attached_loop(manifest_dir)
        mock_file = write_json(self, mock_followup(evidence_cited=["NOPE"]))
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_followup(
                {"manifest": loop_id, "mock_meta": str(mock_file),
                 "manifest_dir": str(manifest_dir)}
            )
        self.assertEqual(ctx.exception.code, "FOLLOWUP_UNGROUNDED")

    def test_followup_twice_refused(self):
        manifest_dir = self.manifest_dir()
        loop_id = self.attached_loop(manifest_dir)
        mock_file = write_json(self, mock_followup())
        options = {"manifest": loop_id, "mock_meta": str(mock_file),
                   "manifest_dir": str(manifest_dir)}
        loop.cmd_followup(options)
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_followup(options)
        self.assertEqual(ctx.exception.code, "FOLLOWUP_DONE")


class ReplayTest(LoopTestBase):
    def test_replay_labels_and_errors(self):
        manifest_dir = self.manifest_dir()
        profile = write_json(self, make_profile())
        model_sha = hashlib.sha256(b"GGUF-fake-weights").hexdigest()
        bench = write_json(self, make_bench(model_sha, collected=PAST_STAMP))
        supplied = write_json(
            self, {"predicted_gen_tps": 0.5, "predicted_prefill_tps": 0.7,
                   "note": "supplied for replay"}
        )
        result = loop.cmd_replay(
            {"profile": str(profile), "bench": str(bench),
             "prediction_json": str(supplied), "loop_id": "REPLAY-TEST-1",
             "manifest_dir": str(manifest_dir)}
        )
        self.assertEqual(result["mode"], "historical_replay")
        manifest = json.loads(
            (manifest_dir / "REPLAY-TEST-1.json").read_text(encoding="utf-8")
        )
        self.assertEqual(
            manifest["prediction"]["provenance"]["mode"], "replay_supplied"
        )
        measured = GEN_TOKENS * 1000.0 / GEN_MS
        self.assertAlmostEqual(
            manifest["errors"]["gen_abs_tps"], round(measured - 0.5, 3)
        )

    def test_followup_works_on_replay_manifest(self):
        manifest_dir = self.manifest_dir()
        profile = write_json(self, make_profile())
        model_sha = hashlib.sha256(b"GGUF-fake-weights").hexdigest()
        bench = write_json(self, make_bench(model_sha, collected=PAST_STAMP))
        supplied = write_json(
            self, {"predicted_gen_tps": 0.5, "predicted_prefill_tps": 0.7}
        )
        loop.cmd_replay(
            {"profile": str(profile), "bench": str(bench),
             "prediction_json": str(supplied), "loop_id": "REPLAY-TEST-2",
             "manifest_dir": str(manifest_dir)}
        )
        mock_file = write_json(
            self, mock_followup(
                evidence_cited=["BENCH-20300101T000000Z-aaaabbbb"])
        )
        result = loop.cmd_followup(
            {"manifest": "REPLAY-TEST-2", "mock_meta": str(mock_file),
             "manifest_dir": str(manifest_dir)}
        )
        self.assertEqual(result["action"], "measure_again")

    def test_replay_rejects_bad_supplied_prediction(self):
        manifest_dir = self.manifest_dir()
        profile = write_json(self, make_profile())
        model_sha = hashlib.sha256(b"GGUF-fake-weights").hexdigest()
        bench = write_json(self, make_bench(model_sha, collected=PAST_STAMP))
        supplied = write_json(self, {"predicted_gen_tps": -1})
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_replay(
                {"profile": str(profile), "bench": str(bench),
                 "prediction_json": str(supplied),
                 "manifest_dir": str(manifest_dir)}
            )
        self.assertEqual(ctx.exception.code, "REPLAY_PREDICTION_INVALID")


class EvidencePackTest(unittest.TestCase):
    def test_pack_reads_real_db_without_writing(self):
        if not REAL_DB.is_file():
            self.fail("real database missing: %s" % REAL_DB)
        before = file_hash(REAL_DB)
        pack = loop.build_evidence_pack()
        self.assertEqual(pack["status"], "ok")
        self.assertEqual(len(pack["physical"]), 2)
        self.assertEqual(
            [r["run_id"] for r in pack["physical"]],
            ["BENCH-20261027T042748Z-93ce3477",
             "BENCH-20261027T170002Z-36227bc0"],
        )
        self.assertEqual(file_hash(REAL_DB), before)

    def test_pack_missing_db_degrades(self):
        missing = Path(tempfile.mkdtemp()) / "absent.db"
        pack = loop.build_evidence_pack(db_path=missing)
        self.assertFalse(pack["db_available"])
        self.assertEqual(pack["physical"], [])


class CliTest(LoopTestBase):
    def test_usage_and_missing_option(self):
        self.assertEqual(loop.main(["ef_loop_01.py"]), 2)
        self.assertEqual(loop.main(["ef_loop_01.py", "bogus"]), 2)
        code = loop.main(["ef_loop_01.py", "init", "--profile", "x"])
        self.assertEqual(code, 1)

    def test_status_report(self):
        manifest_dir = self.manifest_dir()
        loop.cmd_init(self.init_options(manifest_dir, mock_prediction()))
        report = loop.cmd_status(
            {"manifest": "LOOP-TEST-0001",
             "manifest_dir": str(manifest_dir)}
        )["report"]
        self.assertIn("LOOP-TEST-0001", report)
        self.assertIn("prediction", report)
        with self.assertRaises(LoopError) as ctx:
            loop.cmd_status({"manifest": "LOOP-NOPE",
                             "manifest_dir": str(manifest_dir)})
        self.assertEqual(ctx.exception.code, "MANIFEST_NOT_FOUND")


if __name__ == "__main__":
    unittest.main(verbosity=2)
