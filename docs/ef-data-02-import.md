# EF-DATA-02 controlled physical profile ingestion

Owner-controlled, offline import of contract-v1 Android device profiles
into a dedicated additive SQLite table. The Galaxy S22 Ultra profile is
real platform-reported device data collected by EdgeFitDiag; it is NOT a
physical inference-performance measurement and never becomes benchmark
evidence through this path.

## Tables and relationships

`physical_device_profiles` is standalone: no foreign keys to or from the
EF-DATA-01 tables, so EF-META-05 retrieval cannot observe it.

- `id` — `PHYS-` + 16 hex chars of the source SHA-256. Deterministic,
  filename-independent, unrelated to mutable readings.
- `source_sha256` UNIQUE — SHA-256 of the exact original JSON bytes.
- `contract`, `contract_version` (=1), `collector_name`,
  `collector_version`, `collected_at_utc` — preserved from the export.
- `manufacturer`, `model`, `device_codename`, `android_release`,
  `api_level` (21–50), `supported_abis_json`, `logical_processors`,
  `total_ram_bytes`, `avail_ram_bytes` — platform-reported device data.
- `thermal_json` / `battery_json` — canonical JSON or NULL when the
  contract lists the section in `unavailable_json`.
- `unavailable_json` — dotted names of legitimately missing signals.
- `source` = `physical_device_import`; `verification_status` =
  `platform_reported` (`revoked` reserved for future quarantine).
  Nothing here claims hardware attestation.
- `import_schema_version` (=1), `source_json` (exact original bytes),
  `imported_at` (UTC, distinct from collection time).
- `schema_migrations(version, name, applied_at)` tracks migration 2.

## Migration strategy

Additive only; EF-DATA-01 tables are never altered, dropped, or reseeded.
Commit mode refuses a missing database (run `ef_data_01.py` first),
unexpected EF-DATA-01 layouts, unknown future migration versions, and
unexpected provenance. Migration DDL plus the profile insert run in one
atomic transaction with rollback on any failure.

## Preview/commit workflow (PowerShell 5.1, repo root)

Preview (default, zero writes — safe to run any time).

Set `$env:EdgeFitEvidence` to the folder holding your local evidence
exports (kept outside the repository); the commands below use it:

```powershell
py -3 .\scripts\ef_data_02_import.py $env:EdgeFitEvidence\edgefit-profile-20261026-210006.json
```

Preview shows the proposed id, source hash, target database, schema
plan, and whether an identical record already exists. It never creates
journals, backups, or the database file.

Commit (explicit two-flag authorization; NOT YET EXECUTED):

```powershell
py -3 .\scripts\ef_data_02_import.py $env:EdgeFitEvidence\edgefit-profile-20261026-210006.json --commit --acknowledge-physical-import
```

Commit re-validates, copies a timestamped backup next to the database
(`edgefit.db.bak-<UTC stamp>`), then migrates and inserts atomically.
`--commit` without the acknowledgment flag is refused. Automated tests
never invoke commit mode against the real database.

## Duplicate detection

- Same SHA-256 present → `already_imported`, no new row (idempotent).
- Same id with different SHA-256 → `CONFLICT`, no overwrite, exit 1.
- Identity derives from content hash, so renames or re-exports of
  identical bytes always converge to one record.

## Provenance semantics

Stored rows record `platform_reported` provenance: values came from
Android APIs at collection time. `verification_status` may only move to
`revoked` (manual quarantine); there is no path that upgrades a profile
row into `physically_verified` benchmark evidence. Post-commit
verification re-reads the row and compares its hash to the evidence.

## Recovery and rollback

A failed commit rolls the transaction back, leaving prior data intact;
the pre-write backup remains for manual restore:

```powershell
Copy-Item .\data\edgefit.db.bak-<stamp> .\data\edgefit.db
```

Only restore after stopping anything reading the database.

## Import security boundaries

Offline only; no Meta API calls; parameterized SQL; foreign keys on;
validation before any write transaction; no experiments, predictions,
or benchmark runs created; no stable hardware identifier stored;
original evidence bytes preserved verbatim for audit.

## Why ingestion is separate from benchmark eligibility

A device profile describes what the OS reports (model, RAM, thermal
state). Benchmark eligibility requires measured inference throughput
linked to an experiment — a different milestone (EF-BENCH-01) with its
own qualification. The physical table has no path into
`retrieve_comparable_benchmarks`, whose allowlist still serves only
`android-8gb-test` / `llama-3.2-1b`.

## Data retention and limitations

- Evidence JSON stays at its owner path outside the repo (gitignored
  pattern `edgefit-profile-*.json`); only the database copy persists.
- Backups accumulate next to the database (ignored `data/`); prune old
  `.bak-*` files manually.
- One contract version is supported (v1); v2+ profiles are refused
  until a new importer qualifies them.
