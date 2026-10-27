# EdgeFit AI

Hackathon prototype: Meta-assisted, evidence-based Android inference experiments.

**Development started October 26, 2026.** This repository is intended for new hackathon work. Honey LLM and BeeBench are pre-existing independent projects; their code and results are not claimed as new work.

## EF-META-05 evidence flow

Muse Spark 1.3 → validated tool request → read-only SQLite query → evidence response → Muse analysis.

- `scripts/ef_retrieval_05.py` — read-only SQLite service over the EF-DATA-01 schema (one fixed parameterized query, deterministic order, bounded limit, provenance preserved).
- `scripts/ef_dispatch_05.py` — application-side dispatcher for `retrieve_comparable_benchmarks`; enforces the EF-META-04 validation rules before any database access.
- `scripts/ef_meta_05_live.py` — opt-in live round-trip test (model `muse-spark-1.3`); runs only when executed directly with `MODEL_API_KEY` in the environment. Maximum one tool-call round trip; evidence returned via `function_call_output`.
- `scripts/test_ef_meta_05.py` — local test suite (no network): retrieval, dispatcher validation matrix, empty/missing-database handling, provenance, determinism, read-only behavior, and offline API-payload regression.

## Qualification scripts

- `scripts/Test-EF-META-04.ps1` — PowerShell local invalid tool-argument tests (no Meta API usage).
- `scripts/ef_data_01.py` — Python standard-library SQLite database fixture and persistence verification (no Meta API usage).
- `scripts/test_ef_meta_05.py` — Python standard-library EF-META-05 local tests (no Meta API usage).
- `scripts/ef_meta_05_live.py` — Opt-in live Meta API round trip (requires `MODEL_API_KEY`; owner-executed).

Synthetic fixture values are illustrative and not physical measurements.
Run from repository root:

```powershell
& .\scripts\Test-EF-META-04.ps1
py -3 .\scripts\ef_data_01.py
py -3 .\scripts\test_ef_meta_05.py
```

Live qualification (owner workstation with API key only; never run by automated tests):

```powershell
py -3 .\scripts\ef_meta_05_live.py
```

Credentials must not be committed. Database files in `data/` are intentionally ignored. Public repository publication and acceptance under official hackathon rules must be verified separately.
