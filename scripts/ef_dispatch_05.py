"""EdgeFit EF-META-05: application-side dispatcher for retrieve_comparable_benchmarks.

Validation mirrors scripts/Test-EF-META-04.ps1 exactly: one authorized tool,
exact approved fields, strict types, pinned device/model identifiers, limit
range 1-3, and the same error codes. (Spec section 4 prints "Result limit
13."; interpreted as 1-3 per the EF-META-04 reference behavior.)

On success the dispatcher runs read-only SQLite retrieval. Model input is
never executed as SQL, resolved as a filesystem path, or passed to a shell,
and this module performs no database writes or benchmark execution.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_retrieval_05 as retrieval

AUTHORIZED_TOOL = "retrieve_comparable_benchmarks"
EXPECTED_FIELDS = ("device_class", "model_id", "limit")
APPROVED_DEVICE_CLASS = "android-8gb-test"
APPROVED_MODEL_ID = "llama-3.2-1b"
MIN_LIMIT = 1
MAX_LIMIT = 3


class ToolValidationError(Exception):
    """Validation failure; str() is the EF-META-04 error code."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _reject_json_constant(value):
    raise ToolValidationError("MALFORMED_JSON")


def validate_tool_call(tool_name, argument_json):
    """Validate a tool request; return clean args or raise ToolValidationError."""
    if tool_name != AUTHORIZED_TOOL:
        raise ToolValidationError("UNAUTHORIZED_TOOL")
    if not isinstance(argument_json, str) or not argument_json.strip():
        raise ToolValidationError("INVALID_OBJECT")
    try:
        root = json.loads(argument_json, parse_constant=_reject_json_constant)
    except json.JSONDecodeError:
        raise ToolValidationError("MALFORMED_JSON") from None
    if root is None or not isinstance(root, dict):
        raise ToolValidationError("INVALID_OBJECT")
    for name in root:
        if name not in EXPECTED_FIELDS:
            raise ToolValidationError("UNAUTHORIZED_ARGUMENT")
    for name in EXPECTED_FIELDS:
        if name not in root:
            raise ToolValidationError("MISSING_ARGUMENT")
    device_class = root["device_class"]
    if not isinstance(device_class, str) or device_class != APPROVED_DEVICE_CLASS:
        raise ToolValidationError("INVALID_DEVICE")
    model_id = root["model_id"]
    if not isinstance(model_id, str) or model_id != APPROVED_MODEL_ID:
        raise ToolValidationError("INVALID_MODEL")
    limit = root["limit"]
    if type(limit) is not int:
        raise ToolValidationError("INVALID_LIMIT_TYPE")
    if limit < MIN_LIMIT or limit > MAX_LIMIT:
        raise ToolValidationError("OUT_OF_RANGE")
    return {"device_class": device_class, "model_id": model_id, "limit": limit}


def dispatch_tool_call(tool_name, argument_json, db_path=None):
    """Validate a tool request, then execute read-only evidence retrieval."""
    args = validate_tool_call(tool_name, argument_json)
    records = retrieval.retrieve_comparable_benchmarks(
        args["device_class"], args["model_id"], args["limit"], db_path=db_path
    )
    return {
        "tool": AUTHORIZED_TOOL,
        "device_class": args["device_class"],
        "model_id": args["model_id"],
        "count": len(records),
        "records": records,
    }


def main(argv):
    if len(argv) != 3:
        print(json.dumps({"error": "usage: ef_dispatch_05.py <tool_name> <argument_json>"}))
        return 2
    try:
        envelope = dispatch_tool_call(argv[1], argv[2])
    except ToolValidationError as exc:
        print(json.dumps({"error": exc.code}))
        return 1
    except retrieval.EvidenceError as exc:
        print(json.dumps({"error": str(exc)}))
        return 1
    print(json.dumps(envelope, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
