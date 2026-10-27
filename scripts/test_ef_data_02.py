"""EdgeFit EF-DATA-02 import/migration tests.

Isolated temp databases and synthetic fixtures only. Never touches the
real data/edgefit.db, never runs CLI commit mode, performs no network I/O.

Fixtures use obviously synthetic device properties and are NOT physical
measurements.

Run: python scripts/test_ef_data_02.py
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_data_02_import as importer
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

# Synthetic fixture: clearly not a physical measurement.
PROFILE = {
    "contract": "edgefit-android-profile",
    "contract_version": 1,
    "collector": {"name": "edgefit-diag", "version": "0.1.0"},
    "collected_at_utc": "2026-10-27T12:00:00Z",
    "device": {
        "manufacturer": "test-manufacturer",
        "model": "TEST-DEVICE-01",
        "device_codename": "testcodename",
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


def fresh_profile():
    return copy.deepcopy(PROFILE)


def make_db(testcase, with_synth=True):
    fd, name = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    path = Path(name)
    testcase.addCleanup(lambda: path.unlink(missing_ok=True))
    connection = sqlite3.connect(path)
    try:
        connection.execute("PRAGMA foreign_keys=ON")
        connection.executescript(SCHEMA_SQL)
        if with_synth:
            with connection:
                connection.execute(
                    "INSERT INTO device_profiles(id,source,device_class,"
                    "total_ram_mb,available_ram_mb,api_level)"
                    " VALUES (?,?,?,?,?,?)",
                    ("SYNTH-DEVICE-001", "synthetic_fixture",
                     "android-8gb-test", 8192, 4200, 36),
                )
                connection.execute(
                    "INSERT INTO experiments(id,profile_id,model_id,config_json,"
                    "source) VALUES (?,?,?,?,?)",
                    ("SYNTH-EXPERIMENT-001", "SYNTH-DEVICE-001", "llama-3.2-1b",
                     json.dumps({"quantization": "Q4_K_M", "threads": 4,
                                 "context_tokens": 2048}),
                     "synthetic_fixture"),
                )
                connection.execute(
                    "INSERT INTO predictions(id,experiment_id,predicted_tps,"
                    "is_verified) VALUES (?,?,?,0)",
                    ("SYNTH-PREDICTION-001", "SYNTH-EXPERIMENT-001", 15.0),
                )
                connection.execute(
                    "INSERT INTO benchmark_runs(id,experiment_id,measured_tps,"
                    "source,physically_verified) VALUES (?,?,?,?,?)",
                    ("SYNTH-BENCH-001", "SYNTH-EXPERIMENT-001", 9.2,
                     "synthetic_fixture", 0),
                )
    finally:
        connection.close()
    return path


def write_evidence(testcase, payload):
    tmpdir = tempfile.mkdtemp()
    testcase.addCleanup(lambda: None)
    path = Path(tmpdir) / "fixture-profile.json"
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


class AcceptanceTest(unittest.TestCase):
    def test_01_valid_profile_accepted(self):
        evidence = write_evidence(self, fresh_profile())
        plan = importer.plan_import(evidence, make_db(self))
        self.assertEqual(plan["status"], "valid")
        self.assertTrue(plan["profile_id"].startswith("PHYS-"))

    def test_02_malformed_json_rejected(self):
        evidence = write_evidence(self, b"{bad json")
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, make_db(self))

    def test_03_unsupported_contract_version_rejected(self):
        profile = fresh_profile()
        profile["contract_version"] = 2
        evidence = write_evidence(self, profile)
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, make_db(self))

    def test_04_oversized_profile_rejected(self):
        evidence = write_evidence(self, b"x" * 70000)
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, make_db(self))

    def test_05_missing_required_field_rejected(self):
        profile = fresh_profile()
        del profile["device"]["model"]
        evidence = write_evidence(self, profile)
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, make_db(self))

    def test_06_out_of_range_values_rejected(self):
        cases = [
            ("sdk_int", 99),
            ("logical_processors", 0),
            ("avail_ram_bytes", 99999999999),
        ]
        for key, value in cases:
            with self.subTest(key=key):
                profile = fresh_profile()
                profile["device"][key] = value
                evidence = write_evidence(self, profile)
                with self.assertRaises(ImportError):
                    importer.plan_import(evidence, make_db(self))

    def test_07_unavailable_signals_handled(self):
        profile = fresh_profile()
        profile["thermal"] = None
        profile["unavailable"] = ["thermal.status"]
        evidence = write_evidence(self, profile)
        db = make_db(self)
        result = importer.commit_import(evidence, db)
        self.assertEqual(result["status"], "imported")
        stored = importer.read_imported(result["profile_id"], db)
        self.assertIsNone(stored["thermal_json"])
        self.assertEqual(json.loads(stored["unavailable_json"]), ["thermal.status"])
        self.assertIsNotNone(stored["battery_json"])

    def test_08_missing_evidence_path_rejected(self):
        missing = Path(tempfile.mkdtemp()) / "absent.json"
        with self.assertRaises(ImportError):
            importer.plan_import(missing, make_db(self))
        with self.assertRaises(ImportError):
            importer.commit_import(missing, make_db(self))

    def test_unknown_top_level_field_rejected(self):
        profile = fresh_profile()
        profile["telemetry"] = {}
        evidence = write_evidence(self, profile)
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, make_db(self))


class SafetyTest(unittest.TestCase):
    def test_09_preview_makes_zero_changes(self):
        workdir = Path(tempfile.mkdtemp())
        db = workdir / "preview.db"
        shutil.copy(make_db(self), db)
        self.addCleanup(lambda: shutil.rmtree(workdir, ignore_errors=True))
        before_hash = file_hash(db)
        before_size = db.stat().st_size
        evidence = write_evidence(self, fresh_profile())
        files_before = set(os.listdir(workdir))
        importer.plan_import(evidence, db)
        self.assertEqual(file_hash(db), before_hash)
        self.assertEqual(db.stat().st_size, before_size)
        self.assertEqual(set(os.listdir(workdir)), files_before)

    def test_09_preview_missing_db_creates_nothing(self):
        missing_db = Path(tempfile.mkdtemp()) / "absent.db"
        evidence = write_evidence(self, fresh_profile())
        plan = importer.plan_import(evidence, missing_db)
        self.assertIn("note", plan)
        self.assertFalse(missing_db.exists())

    def test_10_commit_requires_explicit_authorization(self):
        db = make_db(self)
        before_hash = file_hash(db)
        evidence = write_evidence(self, fresh_profile())
        script = Path(__file__).resolve().parent / "ef_data_02_import.py"
        result = subprocess.run(
            [sys.executable, str(script), str(evidence),
             "--db", str(db), "--commit"],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("REFUSED", result.stdout)
        self.assertEqual(file_hash(db), before_hash)

    def test_10_preview_is_default_no_flags_write(self):
        db = make_db(self)
        before_hash = file_hash(db)
        evidence = write_evidence(self, fresh_profile())
        script = Path(__file__).resolve().parent / "ef_data_02_import.py"
        result = subprocess.run(
            [sys.executable, str(script), str(evidence), "--db", str(db)],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("PREVIEW ONLY", result.stdout)
        self.assertEqual(file_hash(db), before_hash)

    def test_commit_missing_db_refused_no_create(self):
        missing_db = Path(tempfile.mkdtemp()) / "absent.db"
        evidence = write_evidence(self, fresh_profile())
        with self.assertRaises(ImportError):
            importer.commit_import(evidence, missing_db)
        self.assertFalse(missing_db.exists())

    def test_provenance_refusal(self):
        db = make_db(self)
        connection = sqlite3.connect(db)
        with connection:
            connection.execute(
                "UPDATE device_profiles SET source='rogue' WHERE id=?",
                ("SYNTH-DEVICE-001",),
            )
        connection.close()
        evidence = write_evidence(self, fresh_profile())
        with self.assertRaises(ImportError):
            importer.plan_import(evidence, db)
        with self.assertRaises(ImportError):
            importer.commit_import(evidence, db)

    def test_future_migration_refused(self):
        db = make_db(self)
        connection = sqlite3.connect(db)
        with connection:
            connection.execute(
                "CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,"
                " name TEXT NOT NULL,"
                " applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
            )
            connection.execute(
                "INSERT INTO schema_migrations(version, name) VALUES (99, 'future')"
            )
        connection.close()
        evidence = write_evidence(self, fresh_profile())
        with self.assertRaises(ImportError):
            importer.commit_import(evidence, db)

    def test_20_evidence_file_unchanged(self):
        evidence = write_evidence(self, fresh_profile())
        before = file_hash(evidence)
        db = make_db(self)
        importer.plan_import(evidence, db)
        importer.commit_import(evidence, db)
        importer.plan_import(evidence, db)
        self.assertEqual(file_hash(evidence), before)

    def test_real_database_untouched(self):
        real = importer.DEFAULT_DB_PATH
        snapshot = file_hash(real) if real.is_file() else None
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        importer.plan_import(evidence, db)
        importer.commit_import(evidence, db)
        current = file_hash(real) if real.is_file() else None
        self.assertEqual(current, snapshot)


class CommitSemanticsTest(unittest.TestCase):
    def test_11_first_import_creates_one_record(self):
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        result = importer.commit_import(evidence, db)
        self.assertEqual(result["status"], "imported")
        self.assertEqual(table_count(db, "physical_device_profiles"), 1)
        stored = importer.read_imported(result["profile_id"], db)
        self.assertEqual(stored["model"], "TEST-DEVICE-01")
        self.assertEqual(stored["api_level"], 35)

    def test_12_duplicate_identical_import_no_duplicate(self):
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        first = importer.commit_import(evidence, db)
        second = importer.commit_import(evidence, db)
        self.assertEqual(first["status"], "imported")
        self.assertEqual(second["status"], "already_imported")
        self.assertEqual(second["profile_id"], first["profile_id"])
        self.assertEqual(table_count(db, "physical_device_profiles"), 1)

    def test_12_preview_reports_identical_record(self):
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        importer.commit_import(evidence, db)
        plan = importer.plan_import(evidence, db)
        self.assertTrue(plan["identical_record_exists"])

    def test_13_conflict_cannot_silently_overwrite(self):
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        result = importer.commit_import(evidence, db)
        connection = sqlite3.connect(db)
        with connection:
            connection.execute(
                "UPDATE physical_device_profiles SET source_sha256=? WHERE id=?",
                ("0" * 64, result["profile_id"]),
            )
        connection.close()
        with self.assertRaises(ImportConflictError):
            importer.commit_import(evidence, db)
        with self.assertRaises(ImportConflictError):
            importer.plan_import(evidence, db)
        stored = importer.read_imported(result["profile_id"], db)
        self.assertEqual(stored["source_sha256"], "0" * 64)
        self.assertEqual(table_count(db, "physical_device_profiles"), 1)

    def test_14_partial_failure_rolls_back(self):
        db = make_db(self)
        connection = sqlite3.connect(db)
        with connection:
            connection.execute(
                "CREATE TABLE physical_device_profiles(id TEXT PRIMARY KEY)"
            )
        connection.close()
        evidence = write_evidence(self, fresh_profile())
        with self.assertRaises(Exception):
            importer.commit_import(evidence, db)
        self.assertEqual(
            table_count(db, "device_profiles"), 1
        )
        connection = sqlite3.connect("file:%s?mode=ro" % db.as_posix(), uri=True)
        try:
            row = connection.execute(
                "SELECT device_class FROM device_profiles"
                " WHERE id='SYNTH-DEVICE-001'"
            ).fetchone()
            self.assertEqual(row[0], "android-8gb-test")
            migrations = [
                r[0]
                for r in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                    " AND name='schema_migrations'"
                )
            ]
            self.assertEqual(migrations, [])
        finally:
            connection.close()

    def test_15_timestamps_provenance_preserved(self):
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        result = importer.commit_import(evidence, db)
        stored = importer.read_imported(result["profile_id"], db)
        self.assertEqual(stored["collected_at_utc"], "2026-10-27T12:00:00Z")
        self.assertRegex(stored["imported_at"], r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")
        self.assertEqual(stored["source"], "physical_device_import")
        self.assertEqual(stored["verification_status"], "platform_reported")
        self.assertEqual(stored["import_schema_version"], 1)
        self.assertEqual(stored["collector_name"], "edgefit-diag")
        self.assertEqual(stored["collector_version"], "0.1.0")

    def test_16_sha_matches_original_bytes(self):
        evidence = write_evidence(self, fresh_profile())
        raw = evidence.read_bytes()
        expected = hashlib.sha256(raw).hexdigest()
        db = make_db(self)
        result = importer.commit_import(evidence, db)
        self.assertEqual(result["source_sha256"], expected)
        stored = importer.read_imported(result["profile_id"], db)
        self.assertEqual(stored["source_sha256"], expected)
        self.assertEqual(stored["source_json"], raw.decode("utf-8"))
        self.assertEqual(
            result["profile_id"], "PHYS-" + expected[:16].upper()
        )

    def test_17_synthetic_fixtures_survive(self):
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        importer.commit_import(evidence, db)
        connection = sqlite3.connect("file:%s?mode=ro" % db.as_posix(), uri=True)
        try:
            device = connection.execute(
                "SELECT source, device_class, total_ram_mb, available_ram_mb,"
                " api_level FROM device_profiles WHERE id='SYNTH-DEVICE-001'"
            ).fetchone()
            self.assertEqual(
                tuple(device),
                ("synthetic_fixture", "android-8gb-test", 8192, 4200, 36),
            )
            bench = connection.execute(
                "SELECT measured_tps, source, physically_verified"
                " FROM benchmark_runs WHERE id='SYNTH-BENCH-001'"
            ).fetchone()
            self.assertEqual(tuple(bench), (9.2, "synthetic_fixture", 0))
            pred = connection.execute(
                "SELECT predicted_tps FROM predictions"
                " WHERE id='SYNTH-PREDICTION-001'"
            ).fetchone()[0]
            self.assertEqual(pred, 15.0)
        finally:
            connection.close()

    def test_18_no_experiments_predictions_benchmarks_created(self):
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        importer.commit_import(evidence, db)
        self.assertEqual(table_count(db, "experiments"), 1)
        self.assertEqual(table_count(db, "predictions"), 1)
        self.assertEqual(table_count(db, "benchmark_runs"), 1)

    def test_19_meta05_retrieval_unchanged(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import ef_dispatch_05 as dispatch

        db = make_db(self)
        args = (
            '{"device_class":"android-8gb-test",'
            '"model_id":"llama-3.2-1b","limit":3}'
        )
        before = dispatch.dispatch_tool_call(
            dispatch.AUTHORIZED_TOOL, args, db_path=db
        )
        evidence = write_evidence(self, fresh_profile())
        importer.commit_import(evidence, db)
        after = dispatch.dispatch_tool_call(
            dispatch.AUTHORIZED_TOOL, args, db_path=db
        )
        self.assertEqual(before, after)
        self.assertEqual(after["count"], 1)
        self.assertEqual(after["records"][0]["benchmark_id"], "SYNTH-BENCH-001")

    def test_backup_created_before_write(self):
        db = make_db(self)
        evidence = write_evidence(self, fresh_profile())
        result = importer.commit_import(evidence, db)
        backup = Path(result["backup"])
        self.assertTrue(backup.is_file())
        self.addCleanup(lambda: backup.unlink(missing_ok=True))
        connection = sqlite3.connect("file:%s?mode=ro" % backup.as_posix(), uri=True)
        try:
            tables = {
                r[0]
                for r in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                )
            }
            self.assertNotIn("physical_device_profiles", tables)
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
