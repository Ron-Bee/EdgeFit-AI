# EF-GLOBAL-01 migrations

Local-only migration workflow. Nothing here contacts Cloudflare;
remote application happens only under the owner-gated G2-B2 plan
(`docs/ef-global-01-g2b2-plan.md`).

## Files

- `001_init.sql` — immutable initial schema (H11). NEVER edited after
  first apply. Later changes are new `NNN_name.sql` files. A G2 drift
  test guards byte-consistency with `cloud/schema.sql`.
- `apply-local.mjs` — local apply + verify runner (node:sqlite).

## Local commands (from `cloud/`)

Apply to a scratch database and verify:

```sh
node migrations/apply-local.mjs /tmp/ef-migrate-check.db
```

Verify-only against an existing file:

```sh
node migrations/apply-local.mjs --check /tmp/ef-migrate-check.db
```

Repeat-application test (idempotence + data preservation):

```sh
node migrations/apply-local.mjs /tmp/ef-migrate-check.db
node migrations/apply-local.mjs /tmp/ef-migrate-check.db
node migrations/apply-local.mjs --check /tmp/ef-migrate-check.db
```

Expected output ends with `MIGRATIONS OK`. The second apply must
succeed without error (every statement is `IF NOT EXISTS`).

Schema consistency is also covered in-suite by `G2B-D-N1/N2`
(`cloud/test/g2b-d1.test.js`), which apply `001_init.sql` through
the D1 adapter's `exec` path and serve API traffic on the result.

## Future migrations

1. Create `NNN_short-name.sql` with the next sequence number.
2. Use `IF NOT EXISTS` / additive statements only; never rewrite
   history, never edit an applied file.
3. Extend `EXPECTED_TABLES`/`EXPECTED_INDEXES` in `apply-local.mjs`
   when objects are added, and add a `G2B-D-N*` case proving the
   migration applies cleanly on a database already at the prior
   version with data preserved.
4. Record the migration in the G2-B2/G2-C runbook before any remote
   apply; remote apply is `wrangler d1 migrations apply` under
   G2-B2 authorization only.

## Status inspection

Local ledger: none is kept — local scratch databases are disposable.
Status is always re-derived by `--check` (table/index presence) plus
the in-suite N-cases. Remote status (`wrangler d1 migrations list`)
is a G2-B2 operation, not performed here.
