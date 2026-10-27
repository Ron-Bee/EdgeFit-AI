"""EdgeFit EF-DATA-03: controlled physical benchmark ingestion.

Standard library only. Offline importer for contract-v1 benchmark results
into additive tables. Preview mode (default) never writes. Commit mode
requires --commit plus --acknowledge-physical-import and is never
triggered automatically. Never touches synthetic records.

Usage:
  python scripts/ef_data_03_import.py <bench.json> [--db PATH]
      [--expect-sha HEX] [--associate-profile PATH]...
      [--effective-opt TEXT --effective-opt-source TEXT]
      [--commit --acknowledge-physical-import]

Exit codes: 0 ok/valid/already-imported, 1 invalid/refused/conflict,
2 usage.
"""

from __future__ import annotations

import calendar
import hashlib
import json
import shutil
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_android_profile_01 as profile_contract
import ef_bench_result_01 as bench_contract
import ef_data_02_import as ef_data_02
from ef_data_02_import import ImportConflictError, ImportError

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DB_PATH = ROOT / "data" / "edgefit.db"

MIGRATION_VERSION = 3
MIGRATION_NAME = "ef-data-03 physical benchmark results"
SOURCE_VALUE = "physical_benchmark_import"
ACK_FLAG = "--acknowledge-physical-import"
STATES = ("ingested", "authenticated", "qualified", "globally_eligible")
EFF_STATES = ("unverified", "operator_attested")

MIGRATION_STATEMENTS = [
    """CREATE TABLE IF NOT EXISTS physical_benchmark_results (
  run_id TEXT PRIMARY KEY,
  source_sha256 TEXT NOT NULL UNIQUE,
  contract TEXT NOT NULL,
  contract_version INTEGER NOT NULL CHECK(contract_version=1),
  collected_at_utc TEXT NOT NULL,
  device_model TEXT NOT NULL,
  device_sdk_int INTEGER NOT NULL CHECK(device_sdk_int BETWEEN 21 AND 50),
  device_abis_json TEXT NOT NULL,
  device_cpus INTEGER NOT NULL CHECK(device_cpus BETWEEN 1 AND 1024),
  device_total_ram INTEGER NOT NULL CHECK(device_total_ram>0),
  device_profile_ref TEXT NOT NULL,
  device_provenance TEXT NOT NULL,
  model_sha256 TEXT NOT NULL,
  model_size INTEGER NOT NULL CHECK(model_size>0),
  model_display TEXT NOT NULL,
  model_quantization TEXT NOT NULL,
  model_metadata_json TEXT NOT NULL,
  runtime_impl TEXT NOT NULL,
  runtime_version TEXT NOT NULL,
  runtime_backend TEXT NOT NULL,
  runtime_abi TEXT NOT NULL,
  workload_id TEXT NOT NULL,
  prompt_sha256 TEXT NOT NULL,
  prompt_chars INTEGER NOT NULL CHECK(prompt_chars>0),
  config_json TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  load_ms INTEGER NOT NULL CHECK(load_ms>=0),
  prefill_ms INTEGER NOT NULL CHECK(prefill_ms>=0),
  prefill_tokens INTEGER NOT NULL CHECK(prefill_tokens>=0),
  gen_ms INTEGER NOT NULL CHECK(gen_ms>=0),
  generated_tokens INTEGER NOT NULL CHECK(generated_tokens>=0),
  tps_gen REAL NOT NULL,
  tps_prefill REAL NOT NULL,
  eos_stop INTEGER NOT NULL CHECK(eos_stop IN (0,1)),
  status TEXT NOT NULL
    CHECK(status IN ('COMPLETED','CANCELLED','FAILED','TIMEOUT')),
  error TEXT,
  mem_json TEXT NOT NULL,
  thermal_json TEXT NOT NULL,
  requested_variant TEXT,
  requested_opt TEXT,
  effective_opt TEXT,
  effective_opt_status TEXT NOT NULL DEFAULT 'unverified'
    CHECK(effective_opt_status IN ('unverified','operator_attested')),
  effective_opt_source TEXT,
  qualification_state TEXT NOT NULL DEFAULT 'ingested'
    CHECK(qualification_state IN
      ('ingested','authenticated','qualified','globally_eligible')),
  source TEXT NOT NULL CHECK(source='physical_benchmark_import'),
  source_json TEXT NOT NULL,
  imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
)""",
    "CREATE INDEX IF NOT EXISTS idx_bench_model_workload "
    "ON physical_benchmark_results(model_sha256, workload_id)",
    """CREATE TABLE IF NOT EXISTS bench_context_links (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  bench_run_id TEXT NOT NULL REFERENCES physical_benchmark_results(run_id),
  relationship TEXT NOT NULL CHECK(relationship='same_device_context'),
  related_profile_sha256 TEXT NOT NULL CHECK(length(related_profile_sha256)=64),
  related_filename TEXT NOT NULL,
  profile_collected_at_utc TEXT NOT NULL,
  collected_delta_seconds INTEGER NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  UNIQUE(bench_run_id, related_profile_sha256)
)""",
]

_INSERT_SQL = """INSERT INTO physical_benchmark_results(
  run_id, source_sha256, contract, contract_version, collected_at_utc,
  device_model, device_sdk_int, device_abis_json, device_cpus,
  device_total_ram, device_profile_ref, device_provenance,
  model_sha256, model_size, model_display, model_quantization,
  model_metadata_json, runtime_impl, runtime_version, runtime_backend,
  runtime_abi, workload_id, prompt_sha256, prompt_chars,
  config_json, config_hash, load_ms, prefill_ms, prefill_tokens,
  gen_ms, generated_tokens, tps_gen, tps_prefill, eos_stop,
  status, error, mem_json, thermal_json,
  requested_variant, requested_opt, effective_opt, effective_opt_status,
  effective_opt_source, qualification_state, source, source_json
) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""

_LINK_SQL = """INSERT OR IGNORE INTO bench_context_links(
  bench_run_id, relationship, related_profile_sha256, related_filename,
  profile_collected_at_utc, collected_delta_seconds, note
) VALUES (?,?,?,?,?,?,?)"""


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def _parse_utc(stamp):
    try:
        parsed = datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ")
    except (TypeError, ValueError):
        raise ImportError("bad UTC timestamp: %r" % (stamp,)) from None
    return calendar.timegm(parsed.timetuple())


def load_bench_evidence(path, expect_sha=None):
    raw_path = Path(path)
    if not raw_path.is_file():
        raise ImportError("evidence file not found: %s" % path)
    try:
        data = raw_path.read_bytes()
    except OSError as exc:
        raise ImportError("cannot read evidence file: %s" % exc) from None
    if len(data) > bench_contract.MAX_JSON_BYTES:
        raise ImportError(
            "evidence exceeds %d bytes" % bench_contract.MAX_JSON_BYTES
        )
    digest = hashlib.sha256(data).hexdigest()
    if expect_sha is not None and digest != expect_sha.lower():
        raise ImportError("evidence SHA-256 mismatch for %s" % path)
    try:
        result = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ImportError("evidence is not valid UTF-8 JSON") from None
    errors = bench_contract.validate_result(result)
    if errors:
        raise ImportError("contract INVALID: %s" % "; ".join(errors))
    return data, result, digest


def load_link_profile(path):
    raw_path = Path(path)
    if not raw_path.is_file():
        raise ImportError("association file not found: %s" % path)
    try:
        data = raw_path.read_bytes()
    except OSError as exc:
        raise ImportError("cannot read association file: %s" % exc) from None
    if len(data) > profile_contract.MAX_JSON_BYTES:
        raise ImportError("association file exceeds size bound")
    try:
        profile = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ImportError("association file is not valid UTF-8 JSON") from None
    errors = profile_contract.validate_profile(profile)
    if errors:
        raise ImportError(
            "association contract INVALID: %s" % "; ".join(errors)
        )
    return data, profile


def _open_ro(db_path):
    path = Path(db_path)
    if not path.is_file():
        return None
    uri = "file:%s?mode=ro" % path.absolute().as_posix()
    connection = sqlite3.connect(uri, uri=True)
    connection.execute("PRAGMA query_only = ON")
    return connection


def _applied_versions(connection):
    tables = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }
    if "schema_migrations" not in tables:
        return set()
    return {
        row[0] for row in connection.execute("SELECT version FROM schema_migrations")
    }


def ensure_migrated(connection):
    ef_data_02.apply_migration(connection)
    if MIGRATION_VERSION not in _applied_versions(connection):
        connection.executescript(";\n".join(MIGRATION_STATEMENTS))
        connection.execute(
            "INSERT OR IGNORE INTO schema_migrations(version, name) VALUES (?, ?)",
            (MIGRATION_VERSION, MIGRATION_NAME),
        )


def check_bench_provenance(connection):
    tables = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }
    if "physical_benchmark_results" in tables:
        for source, state in connection.execute(
            "SELECT DISTINCT source, qualification_state"
            " FROM physical_benchmark_results"
        ):
            if source != SOURCE_VALUE or state not in STATES:
                raise ImportError("benchmark rows have inconsistent provenance")


def _str_or_none(value, limit=256):
    if isinstance(value, str) and len(value) <= limit:
        return value
    return None


def build_row(digest, source_text, result, effective_opt, effective_opt_source):
    device = result["device"]
    model = result["model"]
    runtime = result["runtime"]
    workload = result["workload"]
    config = result["config"]
    measurements = result["measurements"]
    config_text = _canonical(config)
    stated = result.get("x_native_build") or {}
    if effective_opt is None:
        eff_status = "unverified"
    else:
        eff_status = "operator_attested"
    return (
        result["run_id"],
        digest,
        result["contract"],
        result["contract_version"],
        result["collected_at_utc"],
        device["model"],
        device["sdk_int"],
        _canonical(device["supported_abis"]),
        device["logical_processors"],
        device["total_ram_bytes"],
        device["profile_ref"],
        device["provenance"],
        model["sha256"],
        model["size_bytes"],
        model["display_name"],
        model["quantization"],
        _canonical(model["metadata"]),
        runtime["impl"],
        runtime["version"],
        runtime["backend"],
        runtime["abi"],
        workload["id"],
        workload["prompt_sha256"],
        workload["prompt_chars"],
        config_text,
        hashlib.sha256(config_text.encode("utf-8")).hexdigest(),
        measurements["load_ms"],
        measurements["prefill_ms"],
        measurements["prefill_tokens"],
        measurements["gen_ms"],
        measurements["generated_tokens"],
        measurements["tps_gen"],
        measurements["tps_prefill"],
        1 if measurements["eos_stop"] else 0,
        result["status"],
        result["error"],
        _canonical(measurements["mem"]),
        _canonical(measurements["thermal"]),
        _str_or_none(stated.get("variant"), 64),
        _str_or_none(stated.get("native_opt"), 128),
        effective_opt,
        eff_status,
        effective_opt_source,
        "ingested",
        SOURCE_VALUE,
        source_text,
    )


def build_links(result, associate_paths):
    bench_epoch = _parse_utc(result["collected_at_utc"])
    links = []
    for path in associate_paths:
        _, profile = load_link_profile(path)
        profile_epoch = _parse_utc(profile["collected_at_utc"])
        links.append(
            {
                "relationship": "same_device_context",
                "related_profile_sha256": hashlib.sha256(
                    Path(path).read_bytes()
                ).hexdigest(),
                "related_filename": Path(path).name,
                "profile_collected_at_utc": profile["collected_at_utc"],
                "collected_delta_seconds": bench_epoch - profile_epoch,
                "note": "",
            }
        )
    return links


def plan_import(evidence_path, db_path=None, expect_sha=None, associate_paths=()):
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    data, result, digest = load_bench_evidence(evidence_path, expect_sha)
    links = build_links(result, associate_paths)
    plan = {
        "status": "valid",
        "run_id": result["run_id"],
        "source_sha256": digest,
        "source_bytes": len(data),
        "target_database": str(target),
        "database_exists": target.is_file(),
        "migration_needed": True,
        "migration_statements": list(MIGRATION_STATEMENTS),
        "identical_record_exists": False,
        "proposed_links": links,
        "model_sha256": result["model"]["sha256"],
        "workload_id": result["workload"]["id"],
        "run_status": result["status"],
        "collected_at_utc": result["collected_at_utc"],
    }
    connection = _open_ro(target)
    if connection is None:
        plan["note"] = "database file does not exist; commit would refuse"
        return plan
    try:
        ef_data_02.check_expected_schema(connection)
        ef_data_02.check_provenance(connection)
        check_bench_provenance(connection)
        versions = _applied_versions(connection)
        plan["migration_needed"] = (
            ef_data_02.MIGRATION_VERSION not in versions
            or MIGRATION_VERSION not in versions
        )
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        if "physical_benchmark_results" in tables:
            row = connection.execute(
                "SELECT source_sha256 FROM physical_benchmark_results WHERE run_id=?",
                (result["run_id"],),
            ).fetchone()
            if row is not None:
                if row[0] != digest:
                    raise ImportConflictError(
                        "run_id %s exists with different content; refusing"
                        % result["run_id"]
                    )
                plan["identical_record_exists"] = True
    finally:
        connection.close()
    return plan


def commit_import(evidence_path, db_path=None, expect_sha=None,
                  associate_paths=(), effective_opt=None,
                  effective_opt_source=None, create_backup=True):
    if (effective_opt is None) != (effective_opt_source is None):
        raise ImportError("effective_opt and effective_opt_source go together")
    if effective_opt is not None and (
        not isinstance(effective_opt, str) or len(effective_opt) > 128
    ):
        raise ImportError("bad effective_opt")
    if effective_opt_source is not None and (
        not isinstance(effective_opt_source, str)
        or len(effective_opt_source) > 256
    ):
        raise ImportError("bad effective_opt_source")
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    data, result, digest = load_bench_evidence(evidence_path, expect_sha)
    links = build_links(result, associate_paths)
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
            ef_data_02.check_expected_schema(connection)
            ef_data_02.check_provenance(connection)
            check_bench_provenance(connection)
            ensure_migrated(connection)
            row = connection.execute(
                "SELECT source_sha256 FROM physical_benchmark_results WHERE run_id=?",
                (result["run_id"],),
            ).fetchone()
            if row is not None:
                if row[0] != digest:
                    raise ImportConflictError(
                        "run_id %s exists with different content; refusing"
                        % result["run_id"]
                    )
                return {
                    "status": "already_imported",
                    "run_id": result["run_id"],
                    "source_sha256": digest,
                    "backup": str(backup_path) if backup_path else None,
                }
            connection.execute(
                _INSERT_SQL,
                build_row(digest, data.decode("utf-8"), result, effective_opt,
                          effective_opt_source),
            )
            for link in links:
                connection.execute(
                    _LINK_SQL,
                    (
                        result["run_id"],
                        link["relationship"],
                        link["related_profile_sha256"],
                        link["related_filename"],
                        link["profile_collected_at_utc"],
                        link["collected_delta_seconds"],
                        link["note"],
                    ),
                )
            synth = connection.execute(
                "SELECT id FROM device_profiles WHERE id='SYNTH-DEVICE-001'"
            ).fetchone()
            if synth is None:
                raise ImportError("synthetic fixture missing after import")
    finally:
        connection.close()
    stored = read_imported(result["run_id"], target)
    if stored is None or stored["source_sha256"] != digest:
        raise ImportError("post-commit verification failed")
    return {
        "status": "imported",
        "run_id": result["run_id"],
        "source_sha256": digest,
        "backup": str(backup_path) if backup_path else None,
    }


def read_imported(run_id, db_path=None):
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    connection = _open_ro(target)
    if connection is None:
        return None
    try:
        connection.row_factory = sqlite3.Row
        row = connection.execute(
            "SELECT * FROM physical_benchmark_results WHERE run_id=?", (run_id,)
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
    expect_sha = None
    effective_opt = None
    effective_opt_source = None
    associate_paths = []
    # Order-independent flag extraction (same convention as ef_data_02_import).
    i = 0
    positionals = []
    while i < len(rest):
        flag = rest[i]
        if flag in ("--db", "--expect-sha", "--associate-profile",
                    "--effective-opt", "--effective-opt-source"):
            if i + 1 >= len(rest):
                print("usage: ef_data_03_import.py <bench.json> [options]")
                return 2
            value = rest[i + 1]
            i += 2
            if flag == "--db":
                db_path = value
            elif flag == "--expect-sha":
                expect_sha = value
            elif flag == "--associate-profile":
                associate_paths.append(value)
            elif flag == "--effective-opt":
                effective_opt = value
            elif flag == "--effective-opt-source":
                effective_opt_source = value
        elif flag.startswith("--"):
            print("usage: ef_data_03_import.py <bench.json> [options]")
            return 2
        else:
            positionals.append(flag)
            i += 1
    rest = positionals
    if len(rest) != 1:
        print("usage: ef_data_03_import.py <bench.json> [--db PATH] "
              "[--expect-sha HEX] [--associate-profile PATH]... "
              "[--effective-opt TEXT --effective-opt-source TEXT] "
              "[--commit --acknowledge-physical-import]")
        return 2
    if commit and not acknowledged:
        print("REFUSED: --commit requires %s" % ACK_FLAG)
        return 2
    try:
        if not commit:
            plan = plan_import(rest[0], db_path, expect_sha, associate_paths)
            print("PREVIEW: physical benchmark %s" % plan["run_id"])
            print("  status: %s, collected: %s"
                  % (plan["run_status"], plan["collected_at_utc"]))
            print("  source_sha256: %s" % plan["source_sha256"])
            print("  target_database: %s" % plan["target_database"])
            print("  database_exists: %s" % plan["database_exists"])
            print("  migration_needed: %s" % plan["migration_needed"])
            print("  identical_record_exists: %s" % plan["identical_record_exists"])
            print("  proposed_links: %d" % len(plan["proposed_links"]))
            for link in plan["proposed_links"]:
                print("    - %s %s delta %+ds"
                      % (link["relationship"], link["related_filename"],
                         link["collected_delta_seconds"]))
            if "note" in plan:
                print("  note: %s" % plan["note"])
            print("PREVIEW ONLY: no writes performed")
            return 0
        result = commit_import(rest[0], db_path, expect_sha, associate_paths,
                               effective_opt, effective_opt_source)
        print("COMMIT: %s %s" % (result["status"], result["run_id"]))
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
