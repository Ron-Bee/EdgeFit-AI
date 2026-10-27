# EF-DATA-03 physical benchmark ingestion and retrieval

Owner-controlled, offline import of contract-v1 physical benchmark
results into additive SQLite tables, plus a separate read-only
retrieval path and an offline A/B comparison. This milestone bridges
the EF-DATA-02 device-profile store and the EF-META-05 synthetic
retrieval prototype. Synthetic records, synthetic queries, and the
EF-META-05 tool allowlist are unchanged; there are no Meta API calls,
no uploads, and no automatic imports.

## Tables and relationships

`physical_benchmark_results` and `bench_context_links` are standalone:
no foreign keys to or from the EF-DATA-01 synthetic tables, so
`retrieve_comparable_benchmarks` cannot observe them.

`physical_benchmark_results` (one row per benchmark run):

- `run_id` PRIMARY KEY — content-derived run identity from the export.
- `source_sha256` UNIQUE — SHA-256 of the exact original JSON bytes.
- `contract`, `contract_version` (=1), `collected_at_utc`.
- Device snapshot: `device_model`, `device_sdk_int` (21–50),
  `device_abis_json`, `device_cpus`, `device_total_ram`,
  `device_profile_ref` (run-start snapshot digest, NOT an exported
  profile hash), `device_provenance`.
- Model: `model_sha256`, `model_size`, `model_label`,
  `model_quantization`, `model_metadata_json`.
- Runtime: `runtime_impl`, `runtime_version`, `runtime_backend`,
  `runtime_abi`.
- Workload: `workload_id`, `workload_prompt_sha256`, `prompt_chars`.
- `config_json` (canonical) plus `config_sha256` for fair-comparison
  matching.
- Measurements: `load_ms`, `prefill_ms`, `prefill_tokens`, `gen_ms`,
  `generated_tokens`, `tps_gen`, `tps_prefill` (stored as measured;
  ratios are recomputed, never trusted blindly), `eos_stop`,
  `status` (COMPLETED/CANCELLED/FAILED/TIMEOUT), `error`, `mem_json`,
  `thermal_json`.
- Build provenance: `requested_variant` / `requested_opt` (self-declared
  `x_native_build`, bounded text); `effective_opt` /
  `effective_opt_source` (separately supplied pair, see below);
  `effective_opt_status` (`unverified` unless an effective pair was
  given, then `operator_attested`).
- `qualification_state` — always `ingested` at import; CHECK-constrained
  to `ingested` / `authenticated` / `qualified` / `globally_eligible`.
  Import never implies eligibility; promotion is a future milestone.
- `source` = `physical_benchmark_import` (CHECK-enforced);
  `source_json` (exact original bytes); `imported_at` (UTC).

`bench_context_links` (explicit contextual associations only):

- `(run_id, related_profile_sha256)` PRIMARY KEY.
- `relationship` is always `same_device_context`.
- `related_filename` (basename only, never a path),
  `profile_collected_at_utc`, `collected_delta_seconds`
  (bench time minus profile time: positive means the profile
  predates the run), `note`.

`schema_migrations(version, name, applied_at)` tracks migration 3.

## Migration strategy

Additive only; EF-DATA-01/EF-DATA-02 tables are never altered, dropped,
or reseeded. Commit mode refuses a missing database (run
`ef_data_01.py` first), unexpected EF-DATA-01 layouts, unknown future
migration versions, and unexpected provenance in either the device or
bench tables. `MAX_KNOWN_MIGRATION_VERSION` in `ef_data_02_import.py`
was raised 2 → 3 so the v2 tool keeps working on a v3 database.
Migration DDL plus the bench insert (and links) run in one atomic
transaction with rollback on any failure, and the synthetic fixture
is re-checked before commit.

## Preview/commit workflow (PowerShell 5.1, repo root)

Preview (default, zero writes — safe to run any time):

```powershell
py -3 .\scripts\ef_data_03_import.py C:\Users\Ron\EdgeFit-Evidence\edgefit-bench-20261026-233319.json --expect-sha 4C02B3CFC9B4DF1B59D098CF35101AB88128F20CF1A417B5B8C8119289A09105 --associate-profile C:\Users\Ron\EdgeFit-Evidence\edgefit-profile-20261026-210006.json --associate-profile C:\Users\Ron\EdgeFit-Evidence\edgefit-profile-20261026-233322.json
```

Preview shows the run id, status, source hash, target database,
migration need, identical-record check, and proposed links with
collection-time deltas. It opens the database read-only and never
creates journals, backups, or the database file.

Commit (explicit two-flag authorization; NOT YET EXECUTED):

```powershell
py -3 .\scripts\ef_data_03_import.py C:\Users\Ron\EdgeFit-Evidence\edgefit-bench-20261026-233319.json --expect-sha 4C02... --associate-profile <profile.json> --effective-opt "-O2" --effective-opt-source "operator attestation ..." --commit --acknowledge-physical-import
```

Commit re-validates, copies a timestamped backup next to the database
(`edgefit.db.bak-<UTC stamp>`), then migrates and inserts atomically
and re-reads the row to verify the stored hash. `--commit` without
the acknowledgment flag is refused (exit 2). `--effective-opt` and
`--effective-opt-source` must be supplied as a pair or both omitted;
omitted means `effective_opt_status` stays `unverified`. Automated
tests never invoke commit mode against the real database.

## Duplicate detection

- Same `run_id` + same SHA-256 → `already_imported`, no new row.
- Same `run_id` with different bytes → `CONFLICT`, no overwrite,
  exit 1, transaction rolled back.
- Same SHA-256 under any id is blocked by the UNIQUE constraint.
- `--expect-sha` refuses bytes whose hash differs from the authorized
  value before any database work.

## Provenance semantics

Stored rows are raw contract-valid physical measurements that became
locally ingested rows (`qualification_state=ingested`). The four
evidence categories — raw valid, locally ingested, authenticated /
policy-qualified, globally eligible — are not interchangeable, and no
code path in this milestone promotes a row beyond `ingested`.

Requested native flags (`-O3 -g` for Run B) are self-declared export
fields. Effective compiler optimization is recorded only through the
separately supplied `--effective-opt` pair with its attestation
source; nothing infers effective flags from the export. For Run B the
verified effective value is `-O2` (a later RelWithDebInfo flag
overrode the requested `-O3` for JNI/ggml/llama.cpp units).

## Profile association integrity

`device.profile_ref` is a digest of the live run-start device
snapshot. It is NOT the SHA-256 of any exported profile JSON, and
the importer never joins on it. `--associate-profile` records an
explicit `same_device_context` link only after validating the profile
contract and confirming the same device model; the basename,
timestamps, and signed collection-time delta are stored. Exact
cryptographic linkage between a bench export and a profile export
does not exist in contract v1.

## Read-only physical retrieval

`scripts/ef_bench_retrieve_03.py` queries only
`physical_benchmark_results` over a read-only connection:

```powershell
py -3 .\scripts\ef_bench_retrieve_03.py --model-sha 6F85A640... --workload-id edgefit-prompt-v1 --status COMPLETED --limit 10
```

Allowlisted filters only (`--model-sha`, `--workload-id`,
`--status`, `--qualification`); results capped at 10, deterministic
`run_id` ordering; every row carries `source`
(`physical_benchmark_import`) and its
`qualification_state`. No model-supplied SQL, no filesystem paths, no
synthetic rows, no eligibility claims. Not connected to any Meta tool
call in this milestone.

## Offline A/B comparison

`scripts/ef_bench_compare_03.py <a.json> <b.json>` validates both
exports, requires COMPLETED status and matched device model, model
SHA-256, workload id + prompt hash, token counts, context, threads,
sampler, and backend, then recomputes throughput ratios from
underlying timings and token counts. Output states the scope (one
physical pair), reports thermal differences as caveats, and keeps the
requested-vs-effective flag distinction. No database access.

## Recovery and rollback

A failed commit rolls the transaction back, leaving prior data intact;
the pre-write backup remains for manual restore:

```powershell
Copy-Item .\data\edgefit.db.bak-<stamp> .\data\edgefit.db
```

Only restore after stopping anything reading the database.

## Import security boundaries

Offline only; no Meta API calls; no recursive filesystem scanning;
bounded inputs (evidence ≤ 1 MiB, links ≤ 8, text lengths capped);
parameterized SQL; foreign keys on; validation before any write
transaction; no experiments, predictions, or synthetic rows created
or modified; no GGUF bytes stored; no user paths persisted (basenames
only).

## Why ingestion is separate from benchmark eligibility

An ingested row is a local copy of a contract-valid measurement. It is
not yet source-authenticated against policy (quorum, thresholds,
anomaly review — all unimplemented), has no consent record for global
use, and represents one device, so it cannot support population
claims. `qualification_state` promotion, consent, and global sync
belong to future milestones.

## Data retention and limitations

- Evidence JSON stays at its owner path outside the repo; only the
  database copy persists.
- Backups accumulate next to the database (ignored `data/`); prune old
  `.bak-*` files manually.
- One contract version is supported (bench v1); anything else is
  refused until a new importer qualifies it.
- `tps_gen`/`tps_prefill` are stored as exported; comparisons recompute
  from `*_ms` + token counts and would surface a mismatch.
- Effective `-O2` provenance for Run B still needs the owner's final
  attestation wording before commit.
