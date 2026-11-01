// EF-GLOBAL-01 local migration runner. Applies cloud/migrations/*.sql
// in filename order to a LOCAL node:sqlite file, then verifies the
// expected tables and indexes. Used for repeat-application testing
// and schema consistency checks WITHOUT touching Cloudflare: there
// is no remote path in this script.
//
// Usage (from cloud/):
//   node migrations/apply-local.mjs <db-file>          # apply + verify
//   node migrations/apply-local.mjs --check <db-file>  # verify only
//
// Exit 0 with "MIGRATIONS OK" on success; nonzero with a named
// failure otherwise. Safe to re-run: applied files are journaled
// and skipped (001/002 predate the journal and are claimed by the
// objects they created).
import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const EXPECTED_TABLES = [
  "daily_counts",
  "eligibility_history",
  "installs",
  "submissions",
];
const EXPECTED_INDEXES = [
  "idx_hist_sub",
  "idx_sub_cohort",
  "idx_sub_eligibility",
  "idx_sub_install",
  "idx_sub_run",
];
// FREE-04: migration 002 adds the eligibility-audit trigger.
// PUB-02: migration 003 adds the provenance triggers.
const EXPECTED_TRIGGERS = [
  "trg_eligibility_audit",
  "trg_install_kind_frozen",
  "trg_submission_origin_check",
  "trg_submission_origin_frozen",
];

// PUB-02: local migration journal. SQLite has no ADD COLUMN IF NOT
// EXISTS, so migration files with ALTERs (003+) cannot blindly
// re-execute. Applied filenames are recorded here and skipped on
// later runs, mirroring wrangler's remote d1_migrations journal.
// Files 001/002 predate the journal: a database that already has
// their objects but no journal rows claims them below, so no
// applied migration is ever re-applied or lost.
const JOURNAL_TABLE = "_migrations_applied";

function journaled(sqlite) {
  sqlite.exec(
    `CREATE TABLE IF NOT EXISTS ${JOURNAL_TABLE} (filename TEXT PRIMARY KEY, applied_at TEXT NOT NULL)`);
  return new Set(sqlite
    .prepare(`SELECT filename FROM ${JOURNAL_TABLE}`)
    .all().map((r) => r.filename));
}

function claimPreJournal(sqlite, done) {
  const names = new Set(sqlite
    .prepare("SELECT type, name FROM sqlite_master").all()
    .map((r) => `${r.type}:${r.name}`));
  const has002 = names.has("trigger:trg_eligibility_audit");
  const has001 = names.has("table:submissions");
  const now = new Date().toISOString();
  const claim = (filename) => {
    sqlite.prepare(
      `INSERT OR IGNORE INTO ${JOURNAL_TABLE} (filename, applied_at) VALUES (?, ?)`)
      .run(filename, now);
    done.add(filename);
  };
  if (has001 && !done.has("001_init.sql")) claim("001_init.sql");
  if (has002 && !done.has("002_eligibility_audit_trigger.sql")) {
    claim("002_eligibility_audit_trigger.sql");
  }
}

const dir = dirname(fileURLToPath(import.meta.url));

function migrationFiles() {
  return readdirSync(dir).filter((f) => f.endsWith(".sql")).sort();
}

function verify(sqlite) {
  const rows = sqlite
    .prepare("SELECT type, name FROM sqlite_master ORDER BY name")
    .all();
  const names = new Set(rows.map((r) => `${r.type}:${r.name}`));
  const missing = [];
  for (const t of EXPECTED_TABLES) {
    if (!names.has(`table:${t}`)) missing.push(`table ${t}`);
  }
  for (const i of EXPECTED_INDEXES) {
    if (!names.has(`index:${i}`)) missing.push(`index ${i}`);
  }
  for (const tr of EXPECTED_TRIGGERS) {
    if (!names.has(`trigger:${tr}`)) missing.push(`trigger ${tr}`);
  }
  return missing;
}

function main(argv) {
  const checkOnly = argv[0] === "--check";
  const target = checkOnly ? argv[1] : argv[0];
  if (!target) {
    console.error("usage: apply-local.mjs [--check] <db-file>");
    process.exit(2);
  }
  const sqlite = new DatabaseSync(target);
  try {
    if (!checkOnly) {
      const files = migrationFiles();
      if (files.length === 0) {
        console.error("no migration files found");
        process.exit(1);
      }
      const done = journaled(sqlite);
      claimPreJournal(sqlite, done);
      for (const file of files) {
        if (done.has(file)) {
          console.log(`skipped ${file} (already applied)`);
          continue;
        }
        sqlite.exec(readFileSync(join(dir, file), "utf8"));
        sqlite.prepare(
          `INSERT INTO ${JOURNAL_TABLE} (filename, applied_at) VALUES (?, ?)`)
          .run(file, new Date().toISOString());
        console.log(`applied ${file}`);
      }
    }
    const missing = verify(sqlite);
    if (missing.length > 0) {
      console.error(`schema incomplete, missing: ${missing.join(", ")}`);
      process.exit(1);
    }
    console.log("MIGRATIONS OK");
  } finally {
    sqlite.close();
  }
}

main(process.argv.slice(2));
