"""EdgeFit EF-DATA-02: controlled physical Android profile ingestion.

Standard library only. Offline importer for contract-v1 device profiles
into a dedicated additive table. Preview mode (default) never writes.
Commit mode requires --commit plus --acknowledge-physical-import and is
never triggered automatically.

Usage:
  python scripts/ef_data_02_import.py <profile.json> [--db PATH]
  python scripts/ef_data_02_import.py <profile.json> --db PATH --commit \\
      --acknowledge-physical-import

Exit codes: 0 ok/valid/already-imported, 1 invalid/refused/conflict,
2 usage.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_android_profile_01 as contract

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DB_PATH = ROOT / "data" / "edgefit.db"

MIGRATION_VERSION = 2
MIGRATION_NAME = "ef-data-02 physical device profiles"
IMPORT_SCHEMA_VERSION = 1
SOURCE_VALUE = "physical_device_import"
ACK_FLAG = "--acknowledge-physical-import"

EXPECTED_TABLES = {
    "device_profiles": {
        "id",
        "source",
        "device_class",
        "total_ram_mb",
        "available_ram_mb",
        "api_level",
        "created_at",
    },
    "experiments": {"id", "profile_id", "model_id", "config_json", "source"},
    "predictions": {"id", "experiment_id", "predicted_tps", "is_verified", "created_at"},
    "benchmark_runs": {
        "id",
        "experiment_id",
        "measured_tps",
        "source",
        "physically_verified",
        "created_at",
    },
}

MIGRATION_STATEMENTS = [
    """CREATE TABLE IF NOT EXISTS schema_migrations (
  version INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
)""",
    """CREATE TABLE IF NOT EXISTS physical_device_profiles (
  id TEXT PRIMARY KEY,
  source_sha256 TEXT NOT NULL UNIQUE,
  contract TEXT NOT NULL,
  contract_version INTEGER NOT NULL CHECK(contract_version=1),
  collector_name TEXT NOT NULL,
  collector_version TEXT NOT NULL,
  collected_at_utc TEXT NOT NULL,
  manufacturer TEXT NOT NULL,
  model TEXT NOT NULL,
  device_codename TEXT NOT NULL,
  android_release TEXT NOT NULL,
  api_level INTEGER NOT NULL CHECK(api_level BETWEEN 21 AND 50),
  supported_abis_json TEXT NOT NULL,
  logical_processors INTEGER NOT NULL CHECK(logical_processors BETWEEN 1 AND 1024),
  total_ram_bytes INTEGER NOT NULL CHECK(total_ram_bytes>0),
  avail_ram_bytes INTEGER NOT NULL CHECK(avail_ram_bytes>=0),
  thermal_json TEXT,
  battery_json TEXT,
  unavailable_json TEXT NOT NULL,
  source TEXT NOT NULL CHECK(source='physical_device_import'),
  verification_status TEXT NOT NULL DEFAULT 'platform_reported'
    CHECK(verification_status IN ('platform_reported','revoked')),
  import_schema_version INTEGER NOT NULL,
  source_json TEXT NOT NULL,
  imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK(avail_ram_bytes<=total_ram_bytes)
)""",
    "CREATE INDEX IF NOT EXISTS idx_physical_profiles_model_api "
    "ON physical_device_profiles(model, api_level)",
]

_INSERT_SQL = """INSERT INTO physical_device_profiles(
  id, source_sha256, contract, contract_version, collector_name,
  collector_version, collected_at_utc, manufacturer, model, device_codename,
  android_release, api_level, supported_abis_json, logical_processors,
  total_ram_bytes, avail_ram_bytes, thermal_json, battery_json,
  unavailable_json, source, verification_status, import_schema_version,
  source_json
) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""


class ImportError(Exception):
    """Import refusal or failure (message is user-facing)."""


class ImportConflictError(ImportError):
    """Same identity, different content: never overwrite."""


def load_evidence(path):
    """Read and parse evidence without touching any database."""
    raw_path = Path(path)
    if not raw_path.is_file():
        raise ImportError("evidence file not found: %s" % path)
    try:
        data = raw_path.read_bytes()
    except OSError as exc:
        raise ImportError("cannot read evidence file: %s" % exc) from None
    if len(data) > contract.MAX_JSON_BYTES:
        raise ImportError(
            "evidence exceeds %d bytes" % contract.MAX_JSON_BYTES
        )
    try:
        profile = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ImportError("evidence is not valid UTF-8 JSON") from None
    errors = contract.validate_profile(profile)
    if errors:
        raise ImportError("contract INVALID: %s" % "; ".join(errors))
    return data, profile


def identity_for(source_bytes):
    """Deterministic id from content hash; independent of filenames."""
    digest = hashlib.sha256(source_bytes).hexdigest()
    return "PHYS-" + digest[:16].upper(), digest


def _open_ro(db_path):
    path = Path(db_path)
    if not path.is_file():
        return None
    uri = "file:%s?mode=ro" % path.absolute().as_posix()
    connection = sqlite3.connect(uri, uri=True)
    connection.execute("PRAGMA query_only = ON")
    return connection


def _table_columns(connection, table):
    return {row[1] for row in connection.execute("PRAGMA table_info(%s)" % table)}


def check_expected_schema(connection):
    """Refuse databases that do not match the EF-DATA-01 layout."""
    tables = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }
    for table, columns in EXPECTED_TABLES.items():
        if table not in tables:
            raise ImportError("database missing expected table: %s" % table)
        actual = _table_columns(connection, table)
        if actual != columns:
            raise ImportError("database table has unexpected layout: %s" % table)
    if "schema_migrations" in tables:
        versions = [
            row[0] for row in connection.execute("SELECT version FROM schema_migrations")
        ]
        if any(v > MIGRATION_VERSION for v in versions):
            raise ImportError("database has unknown future migration version")
        if MIGRATION_VERSION in versions and "physical_device_profiles" not in tables:
            raise ImportError("database migration record without profile table")


def check_provenance(connection):
    """Refuse databases with unexpected or inconsistent provenance."""
    devices = {
        row[0]
        for row in connection.execute("SELECT DISTINCT source FROM device_profiles")
    }
    if not devices <= {"synthetic_fixture"}:
        raise ImportError("device_profiles has unexpected source values")
    benches = {
        row[0]
        for row in connection.execute("SELECT DISTINCT source FROM benchmark_runs")
    }
    if not benches <= {"synthetic_fixture", "physical_measurement"}:
        raise ImportError("benchmark_runs has unexpected source values")
    tables = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }
    if "physical_device_profiles" in tables:
        rows = connection.execute(
            "SELECT DISTINCT source, verification_status FROM physical_device_profiles"
        ).fetchall()
        for source, status in rows:
            if source != SOURCE_VALUE or status not in (
                "platform_reported",
                "revoked",
            ):
                raise ImportError("physical profiles have inconsistent provenance")


def _migration_needed(connection):
    tables = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }
    if "schema_migrations" not in tables:
        return True
    versions = {
        row[0] for row in connection.execute("SELECT version FROM schema_migrations")
    }
    return MIGRATION_VERSION not in versions


def apply_migration(connection):
    connection.executescript(";\n".join(MIGRATION_STATEMENTS))
    connection.execute(
        "INSERT OR IGNORE INTO schema_migrations(version, name) VALUES (?, ?)",
        (MIGRATION_VERSION, MIGRATION_NAME),
    )


def _canonical(value):
    if value is None:
        return None
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def build_row(profile_id, digest, source_text, profile):
    device = profile["device"]
    return (
        profile_id,
        digest,
        profile["contract"],
        profile["contract_version"],
        profile["collector"]["name"],
        profile["collector"]["version"],
        profile["collected_at_utc"],
        device["manufacturer"],
        device["model"],
        device["device_codename"],
        device["android_release"],
        device["sdk_int"],
        _canonical(device["supported_abis"]),
        device["logical_processors"],
        device["total_ram_bytes"],
        device["avail_ram_bytes"],
        _canonical(profile["thermal"]),
        _canonical(profile["battery"]),
        _canonical(profile["unavailable"]),
        SOURCE_VALUE,
        "platform_reported",
        IMPORT_SCHEMA_VERSION,
        source_text,
    )


def plan_import(evidence_path, db_path=None):
    """Validate and plan without any database write. Returns a plan dict."""
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    data, profile = load_evidence(evidence_path)
    profile_id, digest = identity_for(data)
    plan = {
        "status": "valid",
        "profile_id": profile_id,
        "source_sha256": digest,
        "source_bytes": len(data),
        "target_database": str(target),
        "database_exists": target.is_file(),
        "migration_needed": True,
        "migration_statements": list(MIGRATION_STATEMENTS),
        "identical_record_exists": False,
        "model": profile["device"]["model"],
        "api_level": profile["device"]["sdk_int"],
        "collected_at_utc": profile["collected_at_utc"],
    }
    connection = _open_ro(target)
    if connection is None:
        plan["note"] = "database file does not exist; commit would refuse"
        return plan
    try:
        check_expected_schema(connection)
        check_provenance(connection)
        plan["migration_needed"] = _migration_needed(connection)
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        if "physical_device_profiles" in tables:
            row = connection.execute(
                "SELECT source_sha256 FROM physical_device_profiles WHERE id=?",
                (profile_id,),
            ).fetchone()
            if row is not None:
                if row[0] != digest:
                    raise ImportConflictError(
                        "identity %s exists with different content; refusing" % profile_id
                    )
                plan["identical_record_exists"] = True
    finally:
        connection.close()
    return plan


def commit_import(evidence_path, db_path=None, create_backup=True):
    """Validate, back up, migrate, and insert atomically. Explicit call only."""
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    data, profile = load_evidence(evidence_path)
    profile_id, digest = identity_for(data)
    if not target.is_file():
        raise ImportError(
            "database does not exist; run scripts/ef_data_01.py first: %s" % target
        )
    backup_path = None
    if create_backup:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup_path = target.with_name("%s.bak-%s" % (target.name, stamp))
        try:
            shutil.copy2(target, backup_path)
        except OSError as exc:
            raise ImportError("cannot create backup: %s" % exc) from None
    connection = sqlite3.connect(target)
    try:
        connection.execute("PRAGMA foreign_keys=ON")
        with connection:
            check_expected_schema(connection)
            check_provenance(connection)
            if _migration_needed(connection):
                apply_migration(connection)
            row = connection.execute(
                "SELECT source_sha256 FROM physical_device_profiles WHERE id=?",
                (profile_id,),
            ).fetchone()
            if row is not None:
                if row[0] != digest:
                    raise ImportConflictError(
                        "identity %s exists with different content; refusing"
                        % profile_id
                    )
                return {
                    "status": "already_imported",
                    "profile_id": profile_id,
                    "source_sha256": digest,
                    "backup": str(backup_path) if backup_path else None,
                }
            connection.execute(_INSERT_SQL, build_row(profile_id, digest,
                                                      data.decode("utf-8"), profile))
            synth = connection.execute(
                "SELECT id FROM device_profiles WHERE id='SYNTH-DEVICE-001'"
            ).fetchone()
            if synth is None:
                raise ImportError("synthetic fixture missing after import")
    finally:
        connection.close()
    stored = read_imported(profile_id, target)
    if stored is None or stored["source_sha256"] != digest:
        raise ImportError("post-commit verification failed")
    return {
        "status": "imported",
        "profile_id": profile_id,
        "source_sha256": digest,
        "backup": str(backup_path) if backup_path else None,
    }


def read_imported(profile_id, db_path=None):
    """Read one imported profile back (read-only) for verification."""
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    connection = _open_ro(target)
    if connection is None:
        return None
    try:
        connection.row_factory = sqlite3.Row
        row = connection.execute(
            "SELECT * FROM physical_device_profiles WHERE id=?", (profile_id,)
        ).fetchone()
    except sqlite3.OperationalError:
        return None
    finally:
        connection.close()
    return dict(row) if row is not None else None


def main(argv):
    args = argv[1:]
    commit = "--commit" in args
    acknowledged = ACK_FLAG in args
    rest = [a for a in args if a not in ("--commit", ACK_FLAG)]
    db_path = None
    if "--db" in rest:
        index = rest.index("--db")
        if index + 1 >= len(rest):
            print("usage: ef_data_02_import.py <profile.json> [--db PATH] "
                  "[--commit --acknowledge-physical-import]")
            return 2
        db_path = rest[index + 1]
        del rest[index:index + 2]
    if len(rest) != 1:
        print("usage: ef_data_02_import.py <profile.json> [--db PATH] "
              "[--commit --acknowledge-physical-import]")
        return 2
    if commit and not acknowledged:
        print("REFUSED: --commit requires %s" % ACK_FLAG)
        return 2
    try:
        if not commit:
            plan = plan_import(rest[0], db_path)
            print("PREVIEW: contract v1 profile %s" % plan["profile_id"])
            print("  model: %s, api_level: %s, collected: %s"
                  % (plan["model"], plan["api_level"], plan["collected_at_utc"]))
            print("  source_sha256: %s" % plan["source_sha256"])
            print("  source_bytes: %d" % plan["source_bytes"])
            print("  target_database: %s" % plan["target_database"])
            print("  database_exists: %s" % plan["database_exists"])
            print("  migration_needed: %s" % plan["migration_needed"])
            print("  identical_record_exists: %s" % plan["identical_record_exists"])
            if "note" in plan:
                print("  note: %s" % plan["note"])
            print("PREVIEW ONLY: no writes performed")
            return 0
        result = commit_import(rest[0], db_path)
        print("COMMIT: %s %s" % (result["status"], result["profile_id"]))
        print("  source_sha256: %s" % result["source_sha256"])
        print("  backup: %s" % result["backup"])
        return 0
    except ImportConflictError as exc:
        print("CONFLICT: %s" % exc)
        return 1
    except ImportError as exc:
        print("REFUSED: %s" % exc)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
