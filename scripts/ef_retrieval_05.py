"""EdgeFit EF-META-05: read-only SQLite evidence retrieval service.

Standard library only. No network access, no database writes, no Meta API use.

Reads comparable benchmark evidence from the EF-DATA-01 database
(``data/edgefit.db``) through one fixed parameterized query. Caller-supplied
values are bound as parameters only; the SQL text is constant, so callers
cannot inject SQL, paths, or commands through this module.
"""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DB_PATH = ROOT / "data" / "edgefit.db"

APPROVED_DEVICE_CLASSES = frozenset({"android-8gb-test"})
APPROVED_MODEL_IDS = frozenset({"llama-3.2-1b"})

MIN_LIMIT = 1
MAX_LIMIT = 3

# Fixed query template against the EF-DATA-01 schema. All caller values are
# bound via placeholders; this text is never built from model input.
_EVIDENCE_SQL = """SELECT
  b.id AS benchmark_id,
  pred.id AS prediction_id,
  e.id AS experiment_id,
  p.id AS device_id,
  p.device_class AS device_class,
  e.model_id AS model_id,
  e.config_json AS config_json,
  pred.predicted_tps AS predicted_tps,
  b.measured_tps AS measured_tps,
  b.source AS record_source,
  b.physically_verified AS physically_verified
FROM benchmark_runs b
JOIN experiments e ON e.id = b.experiment_id
JOIN device_profiles p ON p.id = e.profile_id
JOIN predictions pred ON pred.experiment_id = e.id
WHERE p.device_class = ? AND e.model_id = ?
ORDER BY b.id ASC, pred.id ASC
LIMIT ?
"""


class EvidenceError(Exception):
    """Base class for evidence retrieval failures."""


class EvidenceDatabaseMissingError(EvidenceError, FileNotFoundError):
    """The SQLite evidence database file does not exist."""


def _validate_selector(device_class, model_id, limit):
    if not isinstance(device_class, str) or device_class not in APPROVED_DEVICE_CLASSES:
        raise EvidenceError("unapproved device_class")
    if not isinstance(model_id, str) or model_id not in APPROVED_MODEL_IDS:
        raise EvidenceError("unapproved model_id")
    if type(limit) is not int or not MIN_LIMIT <= limit <= MAX_LIMIT:
        raise EvidenceError("limit out of range")


def _open_read_only(db_path):
    path = Path(db_path)
    if not path.is_file():
        raise EvidenceDatabaseMissingError("evidence database not found: %s" % path)
    uri = "file:%s?mode=ro" % path.absolute().as_posix()
    connection = sqlite3.connect(uri, uri=True)
    connection.execute("PRAGMA query_only = ON")
    return connection


def retrieve_comparable_benchmarks(device_class, model_id, limit, db_path=None):
    """Return up to ``limit`` comparable benchmark records from SQLite.

    Deterministic order (benchmark id ascending, then prediction id).
    Returns an empty list when nothing matches. Never creates, modifies,
    or commits to the database file. ``physically_verified`` is returned
    exactly as stored (integer 0 or 1).
    """
    _validate_selector(device_class, model_id, limit)
    target = DEFAULT_DB_PATH if db_path is None else Path(db_path)
    connection = _open_read_only(target)
    try:
        connection.row_factory = sqlite3.Row
        rows = connection.execute(
            _EVIDENCE_SQL, (device_class, model_id, limit)
        ).fetchall()
    finally:
        connection.close()
    records = []
    for row in rows:
        try:
            config = json.loads(row["config_json"])
        except (TypeError, ValueError):
            raise EvidenceError("stored config_json is not valid JSON") from None
        predicted = row["predicted_tps"]
        measured = row["measured_tps"]
        records.append(
            {
                "benchmark_id": row["benchmark_id"],
                "prediction_id": row["prediction_id"],
                "experiment_id": row["experiment_id"],
                "device_id": row["device_id"],
                "device_class": row["device_class"],
                "model_id": row["model_id"],
                "config": config,
                "predicted_tps": predicted,
                "measured_tps": measured,
                "difference_tps": round(predicted - measured, 3),
                "record_source": row["record_source"],
                "physically_verified": row["physically_verified"],
            }
        )
    return records
