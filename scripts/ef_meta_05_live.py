"""EdgeFit EF-META-05: explicitly invoked live Meta API round-trip test.

Opt-in by design: network requests happen ONLY when this file is executed
directly AND MODEL_API_KEY exists in the environment. Unit tests import this
module only for offline payload inspection; the builders perform no network
I/O. At most one bounded tool-call round trip is performed.

Flow: request Muse Spark 1.3 with the retrieve_comparable_benchmarks tool,
validate the single tool request with the EF-META-05 dispatcher, return real
SQLite evidence via function_call_output, continue with the response id, and
check that the analysis references the evidence values.

Exit codes: 0 PASS, 1 FAIL, 2 NOT RUN (no API key). Secrets, authorization
headers, and raw credentials are never printed. No physical benchmark or
accuracy improvement is claimed.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_dispatch_05 as dispatch
from ef_dispatch_05 import ToolValidationError

MODEL = "muse-spark-1.3"
ENDPOINT = "https://api.meta.ai/v1/responses"
TIMEOUT_SECONDS = 60
MAX_BODY_PREVIEW = 2000
MAX_ANALYSIS_PRINT = 4000

PASS, FAIL, NOT_RUN = 0, 1, 2

SYSTEM_PROMPT = (
    "You are an EdgeFit evidence analyst. Call the provided tool to retrieve "
    "comparable benchmark evidence, then write a short analysis that references "
    "the benchmark id, reports predicted_tps versus measured_tps and their "
    "difference, and states that the records are synthetic fixtures that are "
    "not physically verified. Never claim a physical benchmark or an accuracy "
    "improvement."
)

USER_PROMPT = (
    "Retrieve comparable benchmarks for device_class android-8gb-test and "
    "model_id llama-3.2-1b with limit 1, then analyze the returned evidence."
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
                    "Call retrieve_comparable_benchmarks to obtain the "
                    "SQLite benchmark record before answering. Returns "
                    "comparable benchmark evidence rows for an approved "
                    "device class and model."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "device_class": {"type": "string"},
                        "model_id": {"type": "string"},
                        "limit": {"type": "integer", "minimum": 1, "maximum": 3},
                    },
                    "required": ["device_class", "model_id", "limit"],
                    "additionalProperties": False,
                },
            }
        ],
        # Meta Responses API supports only tool_choice "auto".
        "tool_choice": "auto",
        "parallel_tool_calls": False,
    }


def _continuation_request(response_id, call_id, envelope_json):
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
                "output": envelope_json,
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
        print("EF-META-05 LIVE: NOT RUN - MODEL_API_KEY not present in environment.")
        return NOT_RUN
    try:
        first = _post(_initial_request())
    except LiveTestError as exc:
        print("EF-META-05 LIVE: FAIL - %s" % exc)
        return FAIL
    response_id = first.get("id") if isinstance(first, dict) else None
    calls = _find_function_calls(first)
    if not response_id:
        print("EF-META-05 LIVE: FAIL - first response has no response id.")
        return FAIL
    if len(calls) == 0:
        print(
            "EF-META-05 LIVE: FAIL - NO_TOOL_CALL: model answered without "
            "calling retrieve_comparable_benchmarks."
        )
        return FAIL
    if len(calls) != 1:
        print(
            "EF-META-05 LIVE: FAIL - expected one function_call; got %d."
            % len(calls)
        )
        return FAIL
    call = calls[0]
    try:
        envelope = dispatch.dispatch_tool_call(call.get("name"), call.get("arguments"))
    except ToolValidationError as exc:
        print("EF-META-05 LIVE: FAIL - model tool request rejected: %s" % exc.code)
        return FAIL
    except dispatch.retrieval.EvidenceError as exc:
        print("EF-META-05 LIVE: FAIL - evidence retrieval failed: %s" % exc)
        return FAIL
    call_id = call.get("call_id") or call.get("id")
    if not call_id:
        print("EF-META-05 LIVE: FAIL - function_call has no call id.")
        return FAIL
    try:
        second = _post(
            _continuation_request(response_id, call_id, json.dumps(envelope))
        )
    except LiveTestError as exc:
        print("EF-META-05 LIVE: FAIL - continuation failed: %s" % exc)
        return FAIL
    if _find_function_calls(second):
        print("EF-META-05 LIVE: FAIL - model attempted a second tool call.")
        return FAIL
    analysis = _extract_text(second)
    lowered = analysis.lower()
    checks = {
        "references SYNTH-BENCH-001": "synth-bench-001" in lowered,
        "distinguishes predicted 15": "15" in analysis,
        "distinguishes observed 9.2": "9.2" in analysis,
        "discusses 5.8 discrepancy": "5.8" in analysis,
    }
    returned_ids = [r["benchmark_id"] for r in envelope["records"]]
    print("Evidence benchmark ids returned from SQLite: %s" % returned_ids)
    print("Analysis: %s" % analysis[:MAX_ANALYSIS_PRINT])
    missing = [name for name, ok in checks.items() if not ok]
    if missing:
        print("EF-META-05 LIVE: FAIL - analysis missing: %s" % ", ".join(missing))
        return FAIL
    print("EF-META-05 LIVE: PASS - one validated round trip with SQLite evidence.")
    print("Synthetic fixtures only; no physical benchmark claimed.")
    return PASS


if __name__ == "__main__":
    raise SystemExit(main())
