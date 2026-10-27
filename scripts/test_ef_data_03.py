"""EdgeFit EF-DATA-03 adversarial import/retrieval tests.

Isolated temp databases and synthetic fixtures only. Never touches the
real data/edgefit.db, never invokes CLI commit mode, no network I/O.

Fixtures are synthetic and are NOT physical measurements.

Run: python scripts/test_ef_data_03.py
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_bench_compare_03 as compare
import ef_bench_retrieve_03 as retrieve
import ef_data_02_import as ef_data_02
import ef_data_03_import as importer
from ef_data_02_import import ImportConflictError, ImportError

# EF-DATA-01 schema mirror for isolated temp databases.
# MUST be updated together with scripts/ef_data_01.py.
SCHEMA_SQL = """
CREATE TABLE device_profiles (
  id TEXT PRIMARY KEY, source TEXT NOT NULL, device_class TEXT NOT NULL,
  total_ram_mb INTEGER NOT NULL CHECK(total_ram_mb>0),
  available_ram_mb INTEGER NOT NULL CHECK(available_ram_mb>=0),
  api_level INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE experiments (
  id TEXT PRIMARY KEY, profile_id TEXT NOT NULL REFERENCES device_profiles(id),
  model_id TEXT NOT NULL, config_json TEXT NOT NULL, source TEXT NOT NULL
);
CREATE TABLE predictions (
  id TEXT PRIMARY KEY, experiment_id TEXT NOT NULL REFERENCES experiments(id),
  predicted_tps REAL NOT NULL CHECK(predicted_tps>=0),
  is_verified INTEGER NOT NULL CHECK(is_verified=0),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE benchmark_runs (
  id TEXT PRIMARY KEY, experiment_id TEXT NOT NULL REFERENCES experiments(id),
  measured_tps REAL NOT NULL CHECK(measured_tps>=0),
  source TEXT NOT NULL CHECK(source IN ('synthetic_fixture','physical_measurement')),
  physically_verified INTEGER NOT NULL CHECK(physically_verified IN (0,1)),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK(source='physical_measurement' OR physically_verified=0)
);
"""

BENCH = {
    "contract": "edgefit-bench-result",
    "contract_version": 1,
    "run_id": "BENCH-20261027T120000Z-aaaabbbb",
    "device": {
        "profile_ref": "0" * 64,
        "model": "TEST-PHONE-1",
        "sdk_int": 35,
        "supported_abis": ["arm64-v8a"],
        "logical_processors": 8,
        "total_ram_bytes": 8589934592,
        "provenance": "platform_reported",
    },
    "model": {
        "display_name": "TEST-MODEL.gguf",
        "size_bytes": 1000,
        "sha256": "1" * 64,
        "quantization": "unknown",
        "metadata": {},
    },
    "runtime": {
        "impl": "llama.cpp",
        "version": "test",
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
        "load_ms": 100,
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
    "collected_at_utc": "2026-10-27T12:00:00Z",
}

PROFILE = {
    "contract": "edgefit-android-profile",
    "contract_version": 1,
    "collector": {"name": "edgefit-diag", "version": "0.1.0"},
    "collected_at_utc": "2026-10-27T11:00:00Z",
    "device": {
        "manufacturer": "test",
        "model": "TEST-PHONE-1",
        "device_codename": "test",
        "android_release": "15",
        "sdk_int": 35,
        "supported_abis": ["arm64-v8a"],
        "logical_processors": 8,
        "total_ram_bytes": 8589934592,
        "avail_ram_bytes": 4294967296,
        "provenance": "platform_reported",
    },
    "thermal": {
        "status_name": "light",
        "status_code": 1,
        "provenance": "platform_reported",
    },
    "battery": {
        "level": 50,
        "scale": 100,
        "pct": 50.0,
        "provenance": "platform_reported",
    },
    "unavailable": [],
}


def fresh_bench(**overrides):
    result = copy.deepcopy(BENCH)
    for dotted, value in overrides.items():
        target = result
        keys = dotted.split(".")
        for key in keys[:-1]:
            target = target[key]
        target[keys[-1]] = value
    return result


def make_db(testcase, migrated=True):
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
                "INSERT INTO device_profiles(id,source,device_class,"
                "total_ram_mb,available_ram_mb,api_level)"
                " VALUES ('SYNTH-DEVICE-001','synthetic_fixture',"
                "'android-8gb-test',8192,4200,36)"
            )
            connection.execute(
                "INSERT INTO experiments(id,profile_id,model_id,config_json,"
                "source) VALUES ('SYNTH-EXPERIMENT-001','SYNTH-DEVICE-001',"
                "'llama-3.2-1b','{}','synthetic_fixture')"
            )
            connection.execute(
                "INSERT INTO predictions(id,experiment_id,predicted_tps,"
                "is_verified) VALUES ('SYNTH-PREDICTION-001',"
                "'SYNTH-EXPERIMENT-001',15.0,0)"
            )
            connection.execute(
                "INSERT INTO benchmark_runs(id,experiment_id,measured_tps,"
                "source,physically_verified) VALUES ('SYNTH-BENCH-001',"
                "'SYNTH-EXPERIMENT-001',9.2,'synthetic_fixture',0)"
            )
            if migrated:
                ef_data_02.apply_migration(connection)
                if 3 not in {
                    r[0]
                    for r in connection.execute(
                        "SELECT version FROM schema_migrations"
                    )
                }:
                    connection.executescript(
                        ";\n".join(importer.MIGRATION_STATEMENTS)
                    )
                    connection.execute(
                        "INSERT INTO schema_migrations(version, name)"
                        " VALUES (3, 'ef-data-03 physical benchmark results')"
                    )
    finally:
        connection.close()
    return path


def write_json(testcase, payload, name="evidence.json"):
    tmpdir = tempfile.mkdtemp()
    path = Path(tmpdir) / name
    if isinstance(payload, (dict, list)):
        path.write_text(json.dumps(payload), encoding="utf-8")
    else:
        path.write_bytes(payload)
    testcase.addCleanup(lambda: path.unlink(missing_ok=True))
    return path


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def table_count(db_path, table):
    connection = sqlite3.connect("file:%s?mode=ro" % Path(db_path).as_posix(),
                                 uri=True)
    try:
        return connection.execute("SELECT count(*) FROM %s" % table).fetchone()[0]
    finally:
        connection.close()


class ImportTest(unittest.TestCase):
    def test_valid_ingestion(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        profile = write_json(self, copy.deepcopy(PROFILE), "assoc.json")
        result = importer.commit_import(evidence, db, associate_paths=[profile])
        self.assertEqual(result["status"], "imported")
        stored = importer.read_imported(result["run_id"], db)
        self.assertEqual(stored["status"], "COMPLETED")
        self.assertEqual(stored["qualification_state"], "ingested")
        self.assertEqual(stored["source"], "physical_benchmark_import")
        self.assertEqual(stored["tps_gen"], 20.0)
        self.assertEqual(stored["effective_opt_status"], "unverified")
        self.assertIsNone(stored["effective_opt"])
        self.assertIsNone(stored["requested_variant"])
        self.assertEqual(len(stored["config_hash"]), 64)
        self.assertEqual(table_count(db, "bench_context_links"), 1)

    def test_requested_not_effective(self):
        bench = fresh_bench()
        bench["x_native_build"] = {"variant": "benchmark", "native_opt": "-O3 -g"}
        db = make_db(self)
        evidence = write_json(self, bench)
        result = importer.commit_import(evidence, db)
        stored = importer.read_imported(result["run_id"], db)
        self.assertEqual(stored["requested_variant"], "benchmark")
        self.assertEqual(stored["requested_opt"], "-O3 -g")
        self.assertIsNone(stored["effective_opt"])
        self.assertEqual(stored["effective_opt_status"], "unverified")

    def test_operator_effective_pair(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        result = importer.commit_import(
            evidence, db, effective_opt="-O2",
            effective_opt_source="compile-command inspection")
        stored = importer.read_imported(result["run_id"], db)
        self.assertEqual(stored["effective_opt"], "-O2")
        self.assertEqual(stored["effective_opt_status"], "operator_attested")

    def test_effective_pair_xor_refused(self):
        db = make_db(self)
        evidence = write_json(self, fresh_profile_bench())
        with self.assertRaises(ImportError):
            importer.commit_import(evidence, db, effective_opt="-O2")

    def test_malformed_version_oversized_missing(self):
        cases = [
            b"{bad",
            json.dumps(fresh_bench(**{"contract_version": 2})).encode(),
            b"x" * 70000,
        ]
        for payload in cases:
            with self.subTest(payload=payload[:10]):
                evidence = write_json(self, payload)
                with self.assertRaises(ImportError):
                    importer.plan_import(evidence, make_db(self))
        bench = fresh_bench()
        del bench["measurements"]["gen_ms"]
        evidence = write_json(self, bench)
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, make_db(self))

    def test_out_of_range_and_inconsistent(self):
        for dotted, value in [
            ("config.n_ctx", 64),
            ("measurements.gen_ms", -1),
            ("measurements.tps_gen", 999.0),
        ]:
            with self.subTest(dotted=dotted):
                evidence = write_json(self, fresh_bench(**{dotted: value}))
                with self.assertRaises(ImportError):
                    importer.plan_import(evidence, make_db(self))

    def test_bad_timestamp(self):
        bench = fresh_bench(**{"collected_at_utc": "yesterday"})
        evidence = write_json(self, bench)
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, make_db(self))

    def test_missing_evidence_path(self):
        missing = Path(tempfile.mkdtemp()) / "absent.json"
        with self.assertRaises(ImportError):
            importer.plan_import(missing, make_db(self))
        with self.assertRaises(ImportError):
            importer.commit_import(missing, make_db(self))

    def test_unknown_field_rejected(self):
        bench = fresh_bench()
        bench["telemetry"] = {}
        evidence = write_json(self, bench)
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, make_db(self))

    def test_tampered_expect_sha(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, db, expect_sha="0" * 64)
        good = file_hash(evidence)
        plan = importer.plan_import(evidence, db, expect_sha=good)
        self.assertEqual(plan["status"], "valid")

    def test_duplicate_exact(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        first = importer.commit_import(evidence, db)
        second = importer.commit_import(evidence, db)
        self.assertEqual(first["status"], "imported")
        self.assertEqual(second["status"], "already_imported")
        self.assertEqual(table_count(db, "physical_benchmark_results"), 1)

    def test_conflicting_run_id(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        result = importer.commit_import(evidence, db)
        connection = sqlite3.connect(db)
        with connection:
            connection.execute(
                "UPDATE physical_benchmark_results SET source_sha256=? WHERE run_id=?",
                ("0" * 64, result["run_id"]),
            )
        connection.close()
        with self.assertRaises(ImportConflictError):
            importer.commit_import(evidence, db)
        with self.assertRaises(ImportConflictError):
            importer.plan_import(evidence, db)
        stored = importer.read_imported(result["run_id"], db)
        self.assertEqual(stored["source_sha256"], "0" * 64)

    def test_rollback_on_partial_failure(self):
        db = make_db(self, migrated=False)
        connection = sqlite3.connect(db)
        with connection:
            connection.execute(
                "CREATE TABLE physical_benchmark_results(run_id TEXT PRIMARY KEY)"
            )
        connection.close()
        evidence = write_json(self, fresh_bench())
        with self.assertRaises(Exception):
            importer.commit_import(evidence, db)
        self.assertEqual(table_count(db, "device_profiles"), 1)
        self.assertEqual(table_count(db, "physical_benchmark_results"), 0)
        connection = sqlite3.connect("file:%s?mode=ro" % db.as_posix(), uri=True)
        try:
            versions = {
                r[0]
                for r in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                    " AND name='schema_migrations'"
                )
            }
            self.assertEqual(versions, set())
        finally:
            connection.close()

    def test_missing_db(self):
        missing_db = Path(tempfile.mkdtemp()) / "absent.db"
        evidence = write_json(self, fresh_bench())
        plan = importer.plan_import(evidence, missing_db)
        self.assertIn("note", plan)
        with self.assertRaises(ImportError):
            importer.commit_import(evidence, missing_db)
        self.assertFalse(missing_db.exists())

    def test_provenance_refusal(self):
        db = make_db(self)
        connection = sqlite3.connect(db)
        with connection:
            connection.execute("UPDATE device_profiles SET source='rogue'")
        connection.close()
        evidence = write_json(self, fresh_bench())
        with self.assertRaises(ImportError):
            importer.commit_import(evidence, db)

    def test_v2_upgrade_path(self):
        db = make_db(self, migrated=False)
        evidence = write_json(self, fresh_bench())
        result = importer.commit_import(evidence, db)
        self.assertEqual(result["status"], "imported")
        connection = sqlite3.connect("file:%s?mode=ro" % db.as_posix(), uri=True)
        try:
            versions = {
                r[0]
                for r in connection.execute("SELECT version FROM schema_migrations")
            }
            self.assertEqual(versions, {2, 3})
        finally:
            connection.close()

    def test_links(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        nested_dir = Path(tempfile.mkdtemp()) / "deep"
        nested_dir.mkdir()
        profile = nested_dir / "nested.json"
        profile.write_text(json.dumps(copy.deepcopy(PROFILE)), encoding="utf-8")
        result = importer.commit_import(evidence, db, associate_paths=[profile])
        self.assertEqual(result["status"], "imported")
        connection = sqlite3.connect("file:%s?mode=ro" % db.as_posix(), uri=True)
        try:
            row = connection.execute(
                "SELECT relationship, related_filename,"
                " collected_delta_seconds FROM bench_context_links"
            ).fetchone()
        finally:
            connection.close()
        self.assertEqual(row[0], "same_device_context")
        self.assertEqual(row[1], "nested.json")
        self.assertNotIn("/", row[1])
        self.assertEqual(row[2], 3600)

    def test_bad_association(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        not_a_profile = write_json(self, fresh_bench(), "bench.json")
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, db, associate_paths=[not_a_profile])
        missing = Path(tempfile.mkdtemp()) / "absent.json"
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, db, associate_paths=[missing])

    def test_synthetic_intact_and_no_extra_tables(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        importer.commit_import(evidence, db)
        self.assertEqual(table_count(db, "device_profiles"), 1)
        self.assertEqual(table_count(db, "experiments"), 1)
        self.assertEqual(table_count(db, "predictions"), 1)
        self.assertEqual(table_count(db, "benchmark_runs"), 1)
        import ef_dispatch_05 as dispatch

        args = ('{"device_class":"android-8gb-test",'
                '"model_id":"llama-3.2-1b","limit":3}')
        envelope = dispatch.dispatch_tool_call(
            dispatch.AUTHORIZED_TOOL, args, db_path=db
        )
        self.assertEqual(envelope["count"], 1)
        self.assertEqual(
            envelope["records"][0]["benchmark_id"], "SYNTH-BENCH-001"
        )

    def test_v2_tool_compat_on_v3_db(self):
        db = make_db(self)
        profile = write_json(self, copy.deepcopy(PROFILE), "prof.json")
        result = ef_data_02.commit_import(profile, db)
        self.assertEqual(result["status"], "imported")
        plan = ef_data_02.plan_import(profile, db)
        self.assertTrue(plan["identical_record_exists"])

    def test_evidence_and_real_db_untouched(self):
        evidence = write_json(self, fresh_bench())
        before = file_hash(evidence)
        real = importer.DEFAULT_DB_PATH
        snapshot = file_hash(real) if real.is_file() else None
        db = make_db(self)
        importer.plan_import(evidence, db)
        importer.commit_import(evidence, db)
        self.assertEqual(file_hash(evidence), before)
        current = file_hash(real) if real.is_file() else None
        self.assertEqual(current, snapshot)


def fresh_profile_bench():
    return fresh_bench()


class RetrievalTest(unittest.TestCase):
    def seed(self, testcase, count=1):
        db = make_db(testcase)
        for index in range(count):
            bench = fresh_bench(**{
                "run_id": "BENCH-20261027T120000Z-%08x" % index,
                "collected_at_utc": "2026-10-27T12:00:0%dZ" % index,
            })
            evidence = write_json(testcase, bench, "b%d.json" % index)
            importer.commit_import(evidence, db)
        return db

    def test_allowlist_enforcement(self):
        db = self.seed(self)
        with self.assertRaises(retrieve.RetrievalError):
            retrieve.retrieve_physical(status="RUNNING", db_path=db)
        with self.assertRaises(retrieve.RetrievalError):
            retrieve.retrieve_physical(model_sha="x' OR '1'='1", db_path=db)
        with self.assertRaises(retrieve.RetrievalError):
            retrieve.retrieve_physical(limit="3", db_path=db)
        rows = retrieve.retrieve_physical(
            model_sha="f" * 64, db_path=db
        )
        self.assertEqual(rows, [])

    def test_filters_and_limit(self):
        db = self.seed(self, count=3)
        rows = retrieve.retrieve_physical(
            model_sha="1" * 64, workload_id="edgefit-prompt-v1",
            status="COMPLETED", qualification_state="ingested",
            limit=2, db_path=db)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["run_id"], "BENCH-20261027T120000Z-00000000")
        rows = retrieve.retrieve_physical(limit=99, db_path=db)
        self.assertEqual(len(rows), 3)
        for row in rows:
            self.assertEqual(row["source"], "physical_benchmark_import")
            self.assertEqual(row["qualification_state"], "ingested")
            self.assertNotIn("source_json", row)

    def test_no_paths_in_output(self):
        db = self.seed(self)
        rows = retrieve.retrieve_physical(db_path=db)
        blob = json.dumps(rows)
        self.assertNotIn("related_filename", blob)
        self.assertNotIn("C:\\", blob)
        self.assertNotIn("/tmp/", blob)
        self.assertNotIn("source_json", blob)

    def test_missing_and_unmigrated_db(self):
        missing = Path(tempfile.mkdtemp()) / "absent.db"
        with self.assertRaises(retrieve.RetrievalError):
            retrieve.retrieve_physical(db_path=missing)
        self.assertFalse(missing.exists())
        raw = make_db(self, migrated=False)
        with self.assertRaises(retrieve.RetrievalError):
            retrieve.retrieve_physical(db_path=raw)

    def test_read_only(self):
        db = self.seed(self)
        before = file_hash(db)
        retrieve.retrieve_physical(db_path=db)
        self.assertEqual(file_hash(db), before)


class CompareTest(unittest.TestCase):
    def pair(self, test_ms_a=6400, test_ms_b=3200):
        bench_a = fresh_bench(**{
            "run_id": "BENCH-20261027T120000Z-aaaa1111",
            "measurements.gen_ms": test_ms_a,
            "measurements.tps_gen": 64 * 1000 / test_ms_a,
        })
        bench_b = fresh_bench(**{
            "run_id": "BENCH-20261027T130000Z-bbbb2222",
            "collected_at_utc": "2026-10-27T13:00:00Z",
            "measurements.gen_ms": test_ms_b,
            "measurements.tps_gen": 64 * 1000 / test_ms_b,
        })
        return bench_a, bench_b

    def test_ratio_computed_not_hardcoded(self):
        bench_a, bench_b = self.pair()
        path_a = write_json(self, bench_a, "a.json")
        path_b = write_json(self, bench_b, "b.json")
        report = compare.compare(path_a, path_b)
        self.assertAlmostEqual(report["gen_speedup"], 2.0)
        self.assertAlmostEqual(report["gen_tps_a"], 10.0)
        self.assertAlmostEqual(report["gen_tps_b"], 20.0)
        self.assertIn("thermal_a", report)
        self.assertIn("one physical A/B pair", report["scope"])

    def test_mismatch_refused(self):
        bench_a, bench_b = self.pair()
        bench_b["model"]["sha256"] = "3" * 64
        path_a = write_json(self, bench_a, "a.json")
        path_b = write_json(self, bench_b, "b.json")
        with self.assertRaises(ImportError):
            compare.compare(path_a, path_b)

    def test_non_completed_refused(self):
        bench_a, bench_b = self.pair()
        bench_b["status"] = "FAILED"
        bench_b["error"] = "boom"
        path_a = write_json(self, bench_a, "a.json")
        path_b = write_json(self, bench_b, "b.json")
        with self.assertRaises(ImportError):
            compare.compare(path_a, path_b)


class CliTest(unittest.TestCase):
    def script(self, name):
        return str(Path(__file__).resolve().parent / name)

    def test_preview_default_no_write(self):
        db = make_db(self)
        before = file_hash(db)
        evidence = write_json(self, fresh_bench())
        result = subprocess.run(
            [sys.executable, self.script("ef_data_03_import.py"),
             str(evidence), "--db", str(db)],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("PREVIEW ONLY", result.stdout)
        self.assertEqual(file_hash(db), before)

    def test_invalid_exit_1(self):
        evidence = write_json(self, b"{bad")
        result = subprocess.run(
            [sys.executable, self.script("ef_data_03_import.py"), str(evidence)],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 1)

    def test_commit_requires_ack(self):
        db = make_db(self)
        before = file_hash(db)
        evidence = write_json(self, fresh_bench())
        result = subprocess.run(
            [sys.executable, self.script("ef_data_03_import.py"),
             str(evidence), "--db", str(db), "--commit"],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 2)
        self.assertEqual(file_hash(db), before)

    def test_effective_xor_refused(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        result = subprocess.run(
            [sys.executable, self.script("ef_data_03_import.py"),
             str(evidence), "--db", str(db), "--commit",
             "--acknowledge-physical-import", "--effective-opt", "-O2"],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("REFUSED", result.stdout)

    def test_retrieve_cli(self):
        db = make_db(self)
        evidence = write_json(self, fresh_bench())
        importer.commit_import(evidence, db)
        result = subprocess.run(
            [sys.executable, self.script("ef_bench_retrieve_03.py"),
             "--db", str(db), "--status", "COMPLETED"],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 0)
        rows = json.loads(result.stdout)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["run_id"], "BENCH-20261027T120000Z-aaaabbbb")

    def test_compare_cli(self):
        bench_a = fresh_bench(**{
            "run_id": "BENCH-20261027T120000Z-aaaa1111",
            "measurements.gen_ms": 6400,
            "measurements.tps_gen": 10.0,
        })
        bench_b = fresh_bench(**{
            "run_id": "BENCH-20261027T130000Z-bbbb2222",
            "collected_at_utc": "2026-10-27T13:00:00Z",
            "measurements.gen_ms": 3200,
            "measurements.tps_gen": 20.0,
        })
        path_a = write_json(self, bench_a, "a.json")
        path_b = write_json(self, bench_b, "b.json")
        result = subprocess.run(
            [sys.executable, self.script("ef_bench_compare_03.py"),
             str(path_a), str(path_b)],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("2.00x", result.stdout)
        self.assertIn("one physical A/B pair", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
