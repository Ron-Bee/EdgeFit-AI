"""EdgeFit EF-DATA-03: read-only physical benchmark retrieval.

Standard library only. Separate allowlisted path over
physical_benchmark_results. Never touches synthetic tables, never
writes, never returns filesystem paths. Not connected to any Meta
tool call.

Usage: python scripts/ef_bench_retrieve_03.py [--db PATH]
    [--model-sha HEX] [--workload-id ID] [--status S]
    [--qualification Q] [--limit N]
"""

from __future__ import annotations

import json
import re
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_data_03_import as importer

DEFAULT_DB_PATH = importer.DEFAULT_DB_PATH
MAX_LIMIT = 10
STATUSES = frozenset({"COMPLETED", "CANCELLED", "FAILED", "TIMEOUT"})
QUALIFICATIONS = frozenset(
    {"ingested", "authenticated", "qualified", "globally_eligible"}
)
_HEX64_RE = re.compile(r"^[0-9a-f]{64}$")

_COLUMNS = (
    "run_id, source_sha256, contract, contract_version, collected_at_utc,"
    " device_model, device_sdk_int, device_abis_json, device_cpus,"
    " device_total_ram, device_profile_ref, device_provenance,"
    " model_sha256, model_size, model_display, model_quantization,"
    " model_metadata_json, runtime_impl, runtime_version, runtime_backend,"
    " runtime_abi, workload_id, prompt_sha256, prompt_chars,"
    " config_json, config_hash, load_ms, prefill_ms, prefill_tokens,"
    " gen_ms, generated_tokens, tps_gen, tps_prefill, eos_stop,"
    " status, error, mem_json, thermal_json,"
    " requested_variant, requested_opt, effective_opt, effective_opt_status,"
    " effective_opt_source, qualification_state, source, imported_at"
)


class RetrievalError(Exception):
    """Physical retrieval refusal or failure."""


def _open_ro(db_path):
    path = Path(db_path)
    if not path.is_file():
        raise RetrievalError("database not found: %s" % path)
    uri = "file:%s?mode=ro" % path.absolute().as_posix()
    connection = sqlite3.connect(uri, uri=True)
    connection.execute("PRAGMA query_only = ON")
    return connection


def retrieve_physical(model_sha=None, workload_id=None, status=None,
                      qualification_state=None, limit=10, db_path=None):
    """Return up to `limit` physical benchmark records (read-only)."""
    if model_sha is not None and (
        not isinstance(model_sha, str) or not _HEX64_RE.match(model_sha)
    ):
        raise RetrievalError("model_sha must be 64 hex chars")
    if workload_id is not None and (
        not isinstance(workload_id, str)
        or not workload_id
        or len(workload_id) > 64
    ):
        raise RetrievalError("workload_id must be a 1-64 char string")
    if status is not None and status not in STATUSES:
        raise RetrievalError("unknown status filter")
    if qualification_state is not None and qualification_state not in QUALIFICATIONS:
        raise RetrievalError("unknown qualification filter")
    if type(limit) is not int:
        raise RetrievalError("limit must be an integer")
    bounded = min(max(limit, 1), MAX_LIMIT)
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    connection = _open_ro(target)
    try:
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        if "physical_benchmark_results" not in tables:
            raise RetrievalError("database not migrated for physical benchmarks")
        clauses = []
        params = []
        if model_sha is not None:
            clauses.append("model_sha256 = ?")
            params.append(model_sha)
        if workload_id is not None:
            clauses.append("workload_id = ?")
            params.append(workload_id)
        if status is not None:
            clauses.append("status = ?")
            params.append(status)
        if qualification_state is not None:
            clauses.append("qualification_state = ?")
            params.append(qualification_state)
        sql = "SELECT %s FROM physical_benchmark_results" % _COLUMNS
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        sql += " ORDER BY collected_at_utc ASC, run_id ASC LIMIT ?"
        params.append(bounded)
        connection.row_factory = sqlite3.Row
        rows = connection.execute(sql, params).fetchall()
    finally:
        connection.close()
    return [dict(row) for row in rows]


def main(argv):
    args = argv[1:]
    db_path = None
    filters = {}
    limit = 10
    while args and args[0].startswith("--"):
        flag = args.pop(0)
        if flag == "--db" and args:
            db_path = args.pop(0)
        elif flag == "--model-sha" and args:
            filters["model_sha"] = args.pop(0)
        elif flag == "--workload-id" and args:
            filters["workload_id"] = args.pop(0)
        elif flag == "--status" and args:
            filters["status"] = args.pop(0)
        elif flag == "--qualification" and args:
            filters["qualification_state"] = args.pop(0)
        elif flag == "--limit" and args:
            try:
                limit = int(args.pop(0))
            except ValueError:
                print("usage: ef_bench_retrieve_03.py [options]")
                return 2
        else:
            print("usage: ef_bench_retrieve_03.py [options]")
            return 2
    if args:
        print("usage: ef_bench_retrieve_03.py [options]")
        return 2
    try:
        records = retrieve_physical(limit=limit, db_path=db_path, **filters)
    except RetrievalError as exc:
        print("REFUSED: %s" % exc)
        return 1
    print(json.dumps(records, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
