"""EdgeFit EF-META-06: explicitly invoked live physical-evidence round trip.

Opt-in by design: network requests happen ONLY when this file is executed
directly AND MODEL_API_KEY exists in the environment. Unit tests import
this module only for offline payload inspection or with a mocked
transport; the builders perform no network I/O. At most one bounded
tool-call round trip is performed (two API requests max).

Flow: request Muse Spark 1.3 with ONLY the retrieve_physical_benchmarks
tool, validate the single tool request with the EF-META-06 dispatcher,
return real SQLite physical evidence plus a deterministic host-computed
comparison via function_call_output, continue with the response id, and
check that the analysis is grounded in the evidence values.

The synthetic retrieve_comparable_benchmarks flow (ef_meta_05_live.py)
is NOT modified or merged here; each tool keeps its own qualified path.

Exit codes: 0 PASS, 1 FAIL, 2 NOT RUN (no API key). Secrets,
authorization headers, and raw credentials are never printed. No
global eligibility, no statistical claim, and no Meta authorship of the
measured optimization is claimed.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_dispatch_06 as dispatch
from ef_dispatch_06 import EvidenceError, ToolValidationError

MODEL = "muse-spark-1.3"
ENDPOINT = "https://api.meta.ai/v1/responses"
TIMEOUT_SECONDS = 60
MAX_BODY_PREVIEW = 2000
MAX_ANALYSIS_PRINT = 4000
MAX_TOOL_CALLS = 1
# Function-output boundary: evidence envelope (<=16 KiB by dispatcher)
# plus the deterministic host comparison (~2 KiB).
MAX_FUNCTION_OUTPUT_BYTES = 32768

PASS, FAIL, NOT_RUN = 0, 1, 2

SYSTEM_PROMPT = (
    "You are an EdgeFit physical-evidence analyst. First call the provided "
    "tool to retrieve the physical benchmark records, then write a short "
    "analysis grounded ONLY in the returned tool output. Separate every "
    "claim into: (1) direct measurements from the records, (2) the "
    "host-computed comparison block (quote its ratios, do not recompute), "
    "(3) compiler configuration evidence from the build fields, "
    "(4) your interpretation or hypothesis, clearly labeled as such, and "
    "(5) unknown or unmeasured factors. State that the records are "
    "locally ingested physical measurements (qualification_state ingested), "
    "not globally eligible evidence. Never claim a statistical result from "
    "this single pair, never state a cause for the throughput difference, "
    "never invent effective compiler flags for the debug run, and never "
    "claim that Meta discovered, authored, applied, or caused the "
    "optimization. Your analysis MUST contain: both run_id values exactly, "
    "the host-computed 37.57 speedup, the effective -O2 flag, and the word "
    "ingested."
)

USER_PROMPT = (
    "Using physical benchmark evidence from the same Android device and "
    "model, compare the original debug build with the optimized benchmark "
    "variant. Retrieve model_sha "
    "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83, "
    "workload_id edgefit-prompt-v1, limit 2. What changed, how much did "
    "performance improve, what does the evidence support, and what should "
    "be measured next?"
)


class LiveTestError(Exception):
    """Live round-trip failure (transport or unexpected API shape)."""


def _post(payload):
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        ENDPOINT,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["MODEL_API_KEY"],
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            detail = exc.read().decode("utf-8", "replace")[:MAX_BODY_PREVIEW]
        except Exception:
            detail = "<unreadable error body>"
        raise LiveTestError(
            "API request failed (HTTP %s): %s" % (exc.code, detail)
        ) from None
    except urllib.error.URLError as exc:
        raise LiveTestError("API request failed: %s" % exc.reason) from None


def _initial_request():
    return {
        "model": MODEL,
        "temperature": 0,
        "input": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": USER_PROMPT},
        ],
        "tools": [
            {
                "type": "function",
                "name": dispatch.AUTHORIZED_TOOL,
                "description": (
                    "Call retrieve_physical_benchmarks to obtain the "
                    "on-device physical benchmark records before answering. "
                    "Returns locally ingested physical measurements for the "
                    "approved model SHA and workload. Always call this tool "
                    "before writing the analysis."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "model_sha": {"type": "string"},
                        "workload_id": {"type": "string"},
                        "limit": {"type": "integer", "minimum": 1, "maximum": 3},
                    },
                    "required": ["model_sha", "workload_id", "limit"],
                    "additionalProperties": False,
                },
            }
        ],
        # Meta Responses API supports only tool_choice "auto".
        "tool_choice": "auto",
        "parallel_tool_calls": False,
    }


def _continuation_request(response_id, call_id, output_json):
    # No tools authorized here: the API-supported way to prevent further
    # tool calls is to omit both "tools" and "tool_choice".
    return {
        "model": MODEL,
        "temperature": 0,
        "previous_response_id": response_id,
        "input": [
            {
                "type": "function_call_output",
                "call_id": call_id,
                "output": output_json,
            }
        ],
    }


def _find_function_calls(response):
    calls = []
    output = response.get("output") if isinstance(response, dict) else None
    if isinstance(output, list):
        for item in output:
            if isinstance(item, dict) and item.get("type") == "function_call":
                calls.append(item)
    return calls


def _extract_text(response):
    chunks = []
    if isinstance(response, dict) and isinstance(response.get("output_text"), str):
        chunks.append(response["output_text"])
    output = response.get("output") if isinstance(response, dict) else None
    if isinstance(output, list):
        for item in output:
            if not isinstance(item, dict):
                continue
            if item.get("type") == "message":
                for part in item.get("content") or []:
                    if (
                        isinstance(part, dict)
                        and part.get("type") in ("output_text", "text")
                        and isinstance(part.get("text"), str)
                    ):
                        chunks.append(part["text"])
    return "\n".join(chunks)


def main():
    if not os.environ.get("MODEL_API_KEY"):
        print("EF-META-06 LIVE: NOT RUN - MODEL_API_KEY not present in environment.")
        return NOT_RUN
    try:
        first = _post(_initial_request())
    except LiveTestError as exc:
        print("EF-META-06 LIVE: FAIL - %s" % exc)
        return FAIL
    response_id = first.get("id") if isinstance(first, dict) else None
    calls = _find_function_calls(first)
    if not response_id:
        print("EF-META-06 LIVE: FAIL - first response has no response id.")
        return FAIL
    if len(calls) == 0:
        print(
            "EF-META-06 LIVE: FAIL - NO_TOOL_CALL: model answered without "
            "calling retrieve_physical_benchmarks."
        )
        return FAIL
    if len(calls) != MAX_TOOL_CALLS:
        print(
            "EF-META-06 LIVE: FAIL - tool-call budget exceeded: expected %d "
            "function_call; got %d." % (MAX_TOOL_CALLS, len(calls))
        )
        return FAIL
    call = calls[0]
    try:
        envelope = dispatch.dispatch_tool_call(call.get("name"), call.get("arguments"))
    except ToolValidationError as exc:
        print("EF-META-06 LIVE: FAIL - model tool request rejected: %s" % exc.code)
        return FAIL
    except EvidenceError as exc:
        print("EF-META-06 LIVE: FAIL - evidence retrieval failed: %s" % exc.code)
        return FAIL
    call_id = call.get("call_id") or call.get("id")
    if not call_id:
        print("EF-META-06 LIVE: FAIL - function_call has no call id.")
        return FAIL
    comparison = dispatch.build_host_comparison(envelope["records"])
    output_json = json.dumps({"evidence": envelope, "host_comparison": comparison})
    if len(output_json) > MAX_FUNCTION_OUTPUT_BYTES:
        print("EF-META-06 LIVE: FAIL - tool output exceeds size boundary.")
        return FAIL
    try:
        second = _post(_continuation_request(response_id, call_id, output_json))
    except LiveTestError as exc:
        print("EF-META-06 LIVE: FAIL - continuation failed: %s" % exc)
        return FAIL
    if _find_function_calls(second):
        print("EF-META-06 LIVE: FAIL - model attempted a second tool call.")
        return FAIL
    analysis = _extract_text(second)
    checks = {
        "references run A id": "BENCH-20261027T042748Z-93ce3477" in analysis,
        "references run B id": "BENCH-20261027T170002Z-36227bc0" in analysis,
        "reports host speedup 37.57": "37.57" in analysis,
        "distinguishes effective -O2": "-O2" in analysis,
        "states ingested qualification": "ingest" in analysis.lower(),
    }
    returned_ids = [r["run_id"] for r in envelope["records"]]
    print("Evidence run ids returned from SQLite: %s" % returned_ids)
    print("Host comparison comparable: %s" % comparison.get("comparable"))
    print("Analysis: %s" % analysis[:MAX_ANALYSIS_PRINT])
    missing = [name for name, ok in checks.items() if not ok]
    if missing:
        print("EF-META-06 LIVE: FAIL - analysis missing: %s" % ", ".join(missing))
        return FAIL
    print("EF-META-06 LIVE: PASS - one validated round trip with physical evidence.")
    print("Locally ingested pair only; no global eligibility claimed.")
    return PASS


if __name__ == "__main__":
    raise SystemExit(main())
