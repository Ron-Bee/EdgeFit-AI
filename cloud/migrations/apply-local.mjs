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
// failure otherwise. Safe to re-run: migrations are IF NOT EXISTS.
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
      for (const file of files) {
        sqlite.exec(readFileSync(join(dir, file), "utf8"));
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
