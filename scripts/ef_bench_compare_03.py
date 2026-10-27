"""EdgeFit EF-DATA-03: offline physical A/B benchmark comparison.

Compares two contract-validated COMPLETED benchmark evidence files on
matched identity keys and computes throughput ratios from underlying
timings and token counts. No database access, no network, no inference.

One physical pair only: never a population average or an established
speedup. Requested native flags come from self-declared x_ fields;
effective flags require separate operator attestation.

Usage: python scripts/ef_bench_compare_03.py <a.json> <b.json>
Exit codes: 0 compared, 1 invalid/mismatched, 2 usage.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_data_03_import as importer
from ef_data_03_import import ImportError

MATCH_KEYS = (
    ("device", "model"),
    ("model", "sha256"),
    ("workload", "id"),
    ("workload", "prompt_sha256"),
    ("measurements", "prefill_tokens"),
    ("measurements", "generated_tokens"),
    ("config", "n_ctx"),
    ("config", "n_threads"),
    ("config", "sampler"),
    ("runtime", "backend"),
)


def _tps(tokens, ms):
    if ms <= 0:
        return 0.0
    return tokens * 1000 / ms


def compare(a_path, b_path):
    """Compare two evidence files; return a report dict or raise."""
    _, result_a, _ = importer.load_bench_evidence(a_path)
    _, result_b, _ = importer.load_bench_evidence(b_path)
    for label, result in (("A", result_a), ("B", result_b)):
        if result["status"] != "COMPLETED":
            raise ImportError("run %s is %s, not COMPLETED" % (label, result["status"]))
    mismatches = [
        "%s.%s" % key
        for key in MATCH_KEYS
        if result_a[key[0]][key[1]] != result_b[key[0]][key[1]]
    ]
    if mismatches:
        raise ImportError("unmatched keys: %s" % ", ".join(mismatches))
    ma = result_a["measurements"]
    mb = result_b["measurements"]
    tps_a = _tps(ma["generated_tokens"], ma["gen_ms"])
    tps_b = _tps(mb["generated_tokens"], mb["gen_ms"])
    pre_a = _tps(ma["prefill_tokens"], ma["prefill_ms"])
    pre_b = _tps(mb["prefill_tokens"], mb["prefill_ms"])
    return {
        "run_a": result_a["run_id"],
        "run_b": result_b["run_id"],
        "matched_keys": ["%s.%s" % key for key in MATCH_KEYS],
        "gen_tps_a": tps_a,
        "gen_tps_b": tps_b,
        "gen_speedup": (tps_b / tps_a) if tps_a > 0 else 0.0,
        "prefill_tps_a": pre_a,
        "prefill_tps_b": pre_b,
        "prefill_speedup": (pre_b / pre_a) if pre_a > 0 else 0.0,
        "gen_ms_a": ma["gen_ms"],
        "gen_ms_b": mb["gen_ms"],
        "thermal_a": ma["thermal"],
        "thermal_b": mb["thermal"],
        "requested_a": result_a.get("x_native_build"),
        "requested_b": result_b.get("x_native_build"),
        "scope": "one physical A/B pair; not a population average",
    }


def main(argv):
    if len(argv) != 3:
        print("usage: ef_bench_compare_03.py <a.json> <b.json>")
        return 2
    try:
        report = compare(argv[1], argv[2])
    except ImportError as exc:
        print("REFUSED: %s" % exc)
        return 1
    print("A/B COMPARISON: %s vs %s" % (report["run_a"], report["run_b"]))
    print("  matched: %s" % ", ".join(report["matched_keys"]))
    print("  gen tok/s: %.6f -> %.6f (%.2fx from measured timings)"
          % (report["gen_tps_a"], report["gen_tps_b"], report["gen_speedup"]))
    print("  prefill tok/s: %.6f -> %.6f (%.2fx)"
          % (report["prefill_tps_a"], report["prefill_tps_b"],
             report["prefill_speedup"]))
    print("  thermal A: %s; thermal B: %s (caveat, not proven cause)"
          % (report["thermal_a"], report["thermal_b"]))
    print("  requested native A: %s; B: %s (self-declared; effective flags "
          "require separate attestation)"
          % (report["requested_a"], report["requested_b"]))
    print("  scope: %s" % report["scope"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
