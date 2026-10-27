"""EdgeFit EF-META-05 local tests.

Hermetic temp-database tests plus one real-database evidence test. No network
access; the live Meta API module is imported only for offline payload
inspection (its builders perform no I/O).

Run order: first ``python scripts/ef_data_01.py`` (creates data/edgefit.db),
then ``python scripts/test_ef_meta_05.py``.
"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_dispatch_05 as dispatch
import ef_meta_05_live as live
import ef_retrieval_05 as retrieval
from ef_dispatch_05 import ToolValidationError

# Schema mirror of scripts/ef_data_01.py DDL for isolated temp databases.
# MUST be updated together with that file; RealDatabaseEvidenceTest guards
# compatibility with the true EF-DATA-01 schema.
SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS device_profiles (
  id TEXT PRIMARY KEY, source TEXT NOT NULL, device_class TEXT NOT NULL,
  total_ram_mb INTEGER NOT NULL CHECK(total_ram_mb>0),
  available_ram_mb INTEGER NOT NULL CHECK(available_ram_mb>=0),
  api_level INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS experiments (
  id TEXT PRIMARY KEY, profile_id TEXT NOT NULL REFERENCES device_profiles(id),
  model_id TEXT NOT NULL, config_json TEXT NOT NULL, source TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS predictions (
  id TEXT PRIMARY KEY, experiment_id TEXT NOT NULL REFERENCES experiments(id),
  predicted_tps REAL NOT NULL CHECK(predicted_tps>=0),
  is_verified INTEGER NOT NULL CHECK(is_verified=0),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS benchmark_runs (
  id TEXT PRIMARY KEY, experiment_id TEXT NOT NULL REFERENCES experiments(id),
  measured_tps REAL NOT NULL CHECK(measured_tps>=0),
  source TEXT NOT NULL CHECK(source IN ('synthetic_fixture','physical_measurement')),
  physically_verified INTEGER NOT NULL CHECK(physically_verified IN (0,1)),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK(source='physical_measurement' OR physically_verified=0)
);
CREATE INDEX IF NOT EXISTS idx_experiments_profile_model ON experiments(profile_id,model_id);
"""

DEFAULT_CONFIG = {"quantization": "Q4_K_M", "threads": 4, "context_tokens": 2048}
DEVICE = "android-8gb-test"
MODEL = "llama-3.2-1b"
VALID_ARGS = {"device_class": DEVICE, "model_id": MODEL, "limit": 1}


def create_temp_db(testcase, prediction_rows, benchmark_rows,
                   device_class=DEVICE, model_id=MODEL,
                   device_id="TMP-DEVICE-001", experiment_id="TMP-EXPERIMENT-001",
                   config=None):
    """Create an isolated temp SQLite DB following the EF-DATA-01 schema."""
    fd, name = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    path = Path(name)
    testcase.addCleanup(lambda: path.unlink(missing_ok=True))
    connection = sqlite3.connect(path)
    try:
        connection.execute("PRAGMA foreign_keys=ON")
        connection.executescript(SCHEMA_SQL)
        with connection:
            connection.execute(
                "INSERT INTO device_profiles(id,source,device_class,total_ram_mb,"
                "available_ram_mb,api_level) VALUES (?,?,?,?,?,?)",
                (device_id, "synthetic_fixture", device_class, 8192, 4200, 36),
            )
            connection.execute(
                "INSERT INTO experiments(id,profile_id,model_id,config_json,source)"
                " VALUES (?,?,?,?,?)",
                (experiment_id, device_id, model_id,
                 json.dumps(config if config is not None else DEFAULT_CONFIG),
                 "synthetic_fixture"),
            )
            for pred_id, predicted in prediction_rows:
                connection.execute(
                    "INSERT INTO predictions(id,experiment_id,predicted_tps,"
                    "is_verified) VALUES (?,?,?,0)",
                    (pred_id, experiment_id, predicted),
                )
            for bench_id, measured, source, verified in benchmark_rows:
                connection.execute(
                    "INSERT INTO benchmark_runs(id,experiment_id,measured_tps,"
                    "source,physically_verified) VALUES (?,?,?,?,?)",
                    (bench_id, experiment_id, measured, source, verified),
                )
    finally:
        connection.close()
    return path


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class RetrievalTest(unittest.TestCase):
    def test_valid_retrieval_values_come_from_sqlite(self):
        db = create_temp_db(
            self,
            [("TMP-PRED-001", 42.5)],
            [("TMP-BENCH-001", 40.0, "synthetic_fixture", 0)],
        )
        records = retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 1, db_path=db)
        self.assertEqual(len(records), 1)
        record = records[0]
        self.assertEqual(record["benchmark_id"], "TMP-BENCH-001")
        self.assertEqual(record["prediction_id"], "TMP-PRED-001")
        self.assertEqual(record["experiment_id"], "TMP-EXPERIMENT-001")
        self.assertEqual(record["device_id"], "TMP-DEVICE-001")
        self.assertEqual(record["config"], DEFAULT_CONFIG)
        self.assertEqual(record["predicted_tps"], 42.5)
        self.assertEqual(record["measured_tps"], 40.0)
        self.assertEqual(record["difference_tps"], 2.5)
        self.assertEqual(record["record_source"], "synthetic_fixture")
        self.assertEqual(record["physically_verified"], 0)

    def test_limit_bounds_result_size(self):
        db = create_temp_db(
            self,
            [("TMP-PRED-001", 10.0)],
            [
                ("TMP-BENCH-001", 9.0, "synthetic_fixture", 0),
                ("TMP-BENCH-002", 8.0, "synthetic_fixture", 0),
                ("TMP-BENCH-003", 7.0, "synthetic_fixture", 0),
            ],
        )
        one = retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 1, db_path=db)
        three = retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 3, db_path=db)
        self.assertEqual(len(one), 1)
        self.assertEqual(len(three), 3)

    def test_deterministic_ordering_across_calls(self):
        db = create_temp_db(
            self,
            [("TMP-PRED-001", 10.0)],
            [
                ("TMP-BENCH-003", 7.0, "synthetic_fixture", 0),
                ("TMP-BENCH-001", 9.0, "synthetic_fixture", 0),
                ("TMP-BENCH-002", 8.0, "synthetic_fixture", 0),
            ],
        )
        first = retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 3, db_path=db)
        second = retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 3, db_path=db)
        self.assertEqual(first, second)
        self.assertEqual(
            [r["benchmark_id"] for r in first],
            ["TMP-BENCH-001", "TMP-BENCH-002", "TMP-BENCH-003"],
        )

    def test_empty_results_return_empty_list(self):
        db = create_temp_db(self, [("TMP-PRED-001", 10.0)], [])
        self.assertEqual(
            retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 3, db_path=db), []
        )
        envelope = dispatch.dispatch_tool_call(
            dispatch.AUTHORIZED_TOOL, json.dumps(VALID_ARGS), db_path=db
        )
        self.assertEqual(envelope["count"], 0)
        self.assertEqual(envelope["records"], [])

    def test_missing_database_raises_and_creates_nothing(self):
        missing = Path(tempfile.mkdtemp()) / "absent.db"
        self.assertFalse(missing.exists())
        with self.assertRaises(FileNotFoundError):
            retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 1, db_path=missing)
        with self.assertRaises(FileNotFoundError):
            dispatch.dispatch_tool_call(
                dispatch.AUTHORIZED_TOOL, json.dumps(VALID_ARGS), db_path=missing
            )
        self.assertFalse(missing.exists())

    def test_provenance_preserved_per_record(self):
        db = create_temp_db(
            self,
            [("TMP-PRED-001", 10.0)],
            [
                ("TMP-BENCH-001", 9.0, "synthetic_fixture", 0),
                ("TMP-BENCH-002", 8.5, "physical_measurement", 1),
            ],
        )
        records = retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 3, db_path=db)
        by_id = {r["benchmark_id"]: r for r in records}
        self.assertEqual(by_id["TMP-BENCH-001"]["record_source"], "synthetic_fixture")
        self.assertEqual(by_id["TMP-BENCH-001"]["physically_verified"], 0)
        self.assertEqual(by_id["TMP-BENCH-002"]["record_source"], "physical_measurement")
        self.assertEqual(by_id["TMP-BENCH-002"]["physically_verified"], 1)

    def test_read_only_leaves_file_untouched(self):
        db = create_temp_db(
            self,
            [("TMP-PRED-001", 10.0)],
            [("TMP-BENCH-001", 9.0, "synthetic_fixture", 0)],
        )
        before_hash = file_hash(db)
        before_size = db.stat().st_size
        retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 1, db_path=db)
        retrieval.retrieve_comparable_benchmarks(DEVICE, MODEL, 1, db_path=db)
        self.assertEqual(file_hash(db), before_hash)
        self.assertEqual(db.stat().st_size, before_size)


class DispatchValidationTest(unittest.TestCase):
    def test_valid_minimum_and_maximum(self):
        db = create_temp_db(
            self,
            [("TMP-PRED-001", 10.0)],
            [("TMP-BENCH-001", 9.0, "synthetic_fixture", 0)],
        )
        for limit in (1, 3):
            args = dict(VALID_ARGS, limit=limit)
            envelope = dispatch.dispatch_tool_call(
                dispatch.AUTHORIZED_TOOL, json.dumps(args), db_path=db
            )
            self.assertEqual(
                set(envelope),
                {"tool", "device_class", "model_id", "count", "records"},
            )
            self.assertEqual(envelope["tool"], dispatch.AUTHORIZED_TOOL)
            self.assertEqual(envelope["count"], 1)

    def test_invalid_matrix(self):
        valid = json.dumps(VALID_ARGS)
        cases = [
            ("missing field",
             dispatch.AUTHORIZED_TOOL,
             '{"device_class":"android-8gb-test","model_id":"llama-3.2-1b"}',
             "MISSING_ARGUMENT"),
            ("extra field",
             dispatch.AUTHORIZED_TOOL,
             '{"device_class":"android-8gb-test","model_id":"llama-3.2-1b",'
             '"limit":1,"execute_shell":"cmd"}',
             "UNAUTHORIZED_ARGUMENT"),
            ("limit too large", dispatch.AUTHORIZED_TOOL,
             valid.replace('"limit": 1', '"limit": 999'), "OUT_OF_RANGE"),
            ("limit zero", dispatch.AUTHORIZED_TOOL,
             valid.replace('"limit": 1', '"limit": 0'), "OUT_OF_RANGE"),
            ("limit negative", dispatch.AUTHORIZED_TOOL,
             valid.replace('"limit": 1', '"limit": -1'), "OUT_OF_RANGE"),
            ("limit as text", dispatch.AUTHORIZED_TOOL,
             valid.replace('"limit": 1', '"limit": "3"'),
             "INVALID_LIMIT_TYPE"),
            ("limit as float", dispatch.AUTHORIZED_TOOL,
             valid.replace('"limit": 1', '"limit": 3.0'), "INVALID_LIMIT_TYPE"),
            ("limit as bool", dispatch.AUTHORIZED_TOOL,
             valid.replace('"limit": 1', '"limit": true'), "INVALID_LIMIT_TYPE"),
            ("limit as null", dispatch.AUTHORIZED_TOOL,
             valid.replace('"limit": 1', '"limit": null'), "INVALID_LIMIT_TYPE"),
            ("unapproved device", dispatch.AUTHORIZED_TOOL,
             valid.replace("android-8gb-test", "other"), "INVALID_DEVICE"),
            ("device case variant", dispatch.AUTHORIZED_TOOL,
             valid.replace("android-8gb-test", "Android-8gb-test"), "INVALID_DEVICE"),
            ("device as number", dispatch.AUTHORIZED_TOOL,
             valid.replace('"android-8gb-test"', "42"), "INVALID_DEVICE"),
            ("unapproved model", dispatch.AUTHORIZED_TOOL,
             valid.replace("llama-3.2-1b", "unknown"), "INVALID_MODEL"),
            ("model as null", dispatch.AUTHORIZED_TOOL,
             valid.replace('"llama-3.2-1b"', "null"), "INVALID_MODEL"),
            ("field name case variant", dispatch.AUTHORIZED_TOOL,
             valid.replace("device_class", "Device_class"), "UNAUTHORIZED_ARGUMENT"),
            ("malformed JSON", dispatch.AUTHORIZED_TOOL, "{bad", "MALFORMED_JSON"),
            ("wrong top-level type", dispatch.AUTHORIZED_TOOL, "[1,2,3]", "INVALID_OBJECT"),
            ("null root", dispatch.AUTHORIZED_TOOL, "null", "INVALID_OBJECT"),
            ("numeric root", dispatch.AUTHORIZED_TOOL, "42", "INVALID_OBJECT"),
            ("string root", dispatch.AUTHORIZED_TOOL, '"x"', "INVALID_OBJECT"),
            ("empty string", dispatch.AUTHORIZED_TOOL, "", "INVALID_OBJECT"),
            ("whitespace", dispatch.AUTHORIZED_TOOL, "   ", "INVALID_OBJECT"),
            ("unauthorized tool", "run_shell", valid, "UNAUTHORIZED_TOOL"),
            ("tool case variant", "Retrieve_Comparable_Benchmarks", valid,
             "UNAUTHORIZED_TOOL"),
            ("null tool", None, valid, "UNAUTHORIZED_TOOL"),
            ("sql injection attempt", dispatch.AUTHORIZED_TOOL,
             valid.replace('"limit": 1', '"limit": "1; DROP TABLE benchmark_runs"'),
             "INVALID_LIMIT_TYPE"),
            ("path injection attempt", dispatch.AUTHORIZED_TOOL,
             valid.replace("android-8gb-test", "../../etc/passwd"), "INVALID_DEVICE"),
        ]
        for name, tool, payload, expected in cases:
            with self.subTest(name=name):
                with self.assertRaises(ToolValidationError) as ctx:
                    dispatch.validate_tool_call(tool, payload)
                self.assertEqual(ctx.exception.code, expected)

    def test_validation_precedes_execution(self):
        missing = Path(tempfile.mkdtemp()) / "absent.db"
        with self.assertRaises(ToolValidationError) as ctx:
            dispatch.dispatch_tool_call(
                dispatch.AUTHORIZED_TOOL, '{"device_class":"x"}', db_path=missing
            )
        self.assertEqual(ctx.exception.code, "MISSING_ARGUMENT")
        self.assertFalse(missing.exists())


class RealDatabaseEvidenceTest(unittest.TestCase):
    def test_real_database_fixture_evidence(self):
        if not retrieval.DEFAULT_DB_PATH.is_file():
            self.fail("real fixture missing; run: python scripts/ef_data_01.py")
        envelope = dispatch.dispatch_tool_call(
            dispatch.AUTHORIZED_TOOL, json.dumps(VALID_ARGS)
        )
        self.assertEqual(envelope["count"], 1)
        record = envelope["records"][0]
        self.assertEqual(record["benchmark_id"], "SYNTH-BENCH-001")
        self.assertEqual(record["prediction_id"], "SYNTH-PREDICTION-001")
        self.assertEqual(record["predicted_tps"], 15.0)
        self.assertEqual(record["measured_tps"], 9.2)
        self.assertEqual(record["difference_tps"], 5.8)
        self.assertEqual(record["record_source"], "synthetic_fixture")
        self.assertEqual(record["physically_verified"], 0)
        self.assertEqual(record["config"]["quantization"], "Q4_K_M")


class LivePayloadOfflineTest(unittest.TestCase):
    """Offline regression: Responses payloads use only supported options."""

    def test_initial_request_uses_supported_tool_choice(self):
        payload = live._initial_request()
        self.assertEqual(payload["tool_choice"], "auto")
        self.assertIs(payload["parallel_tool_calls"], False)
        self.assertEqual(len(payload["tools"]), 1)
        self.assertEqual(payload["tools"][0]["name"], dispatch.AUTHORIZED_TOOL)
        self.assertIn(dispatch.AUTHORIZED_TOOL, payload["tools"][0]["description"])

    def test_continuation_request_authorizes_no_tools(self):
        payload = live._continuation_request("resp_123", "call_123", '{"count":1}')
        self.assertNotIn("tool_choice", payload)
        self.assertNotIn("tools", payload)
        self.assertNotIn("parallel_tool_calls", payload)
        self.assertEqual(payload["previous_response_id"], "resp_123")
        self.assertEqual(payload["input"][0]["type"], "function_call_output")
        self.assertEqual(payload["input"][0]["call_id"], "call_123")

    def test_payload_builders_need_no_key_or_network(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("MODEL_API_KEY", None)
            with mock.patch(
                "urllib.request.urlopen",
                side_effect=AssertionError("network used"),
            ):
                live._initial_request()
                live._continuation_request("resp_123", "call_123", "{}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
