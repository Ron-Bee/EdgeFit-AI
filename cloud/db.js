// EF-CLOUD-01A database adapter, G2 batch primitive. The API
// layer speaks only { get, all, run, exec, batch, close }.
//
// batch() is the SOLE multi-statement primitive, shaped for D1:
// a fixed statement array in, per-statement results out, executed
// atomically with no mid-batch reads. node:sqlite runs it as one
// BEGIN IMMEDIATE .. COMMIT; a D1 deployment implements the same
// array-in/array-out contract over env.DB.batch() without touching
// routes, validation, or stats. D1 service behavior is UNVERIFIED:
// error shapes, batch limits, and contention must be qualified on
// staging (see docs/ef-global-01-g2-staging-design.md).
//
// Adapter error contract (both engines): UNIQUE violations surface
// as Error "UNIQUE constraint failed: <table>.<column>"; lock
// contention surfaces as errcode 5 / SQLITE_BUSY /
// "database is locked". Staging extends the D1 mapping table.
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/** Lock wait before SQLITE_BUSY. Short: batches here are tiny. */
export const BUSY_TIMEOUT_MS = 5000;

export function openDatabase(filename = ":memory:") {
  const db = new DatabaseSync(filename);
  db.exec(`PRAGMA busy_timeout = ${BUSY_TIMEOUT_MS}`);
  return {
    get(sql, ...params) {
      return db.prepare(sql).get(...params);
    },
    all(sql, ...params) {
      return db.prepare(sql).all(...params);
    },
    run(sql, ...params) {
      return db.prepare(sql).run(...params);
    },
    exec(sql) {
      return db.exec(sql);
    },
    /**
     * Execute statements atomically, in order, all-or-nothing.
     * Each entry: { sql, params?, mode? } with mode 'run' (default),
     * 'get', or 'all'. Returns results aligned with the input:
     * run -> { changes, lastInsertRowid }, get -> row|undefined,
     * all -> rows. Any failure rolls back and rethrows. NOT
     * nestable: one batch per atomic unit (submission claim+insert,
     * single eligibility transition, revocation).
     */
    batch(statements) {
      for (const s of statements) {
        const mode = s.mode ?? "run";
        if (mode !== "run" && mode !== "get" && mode !== "all") {
          throw new Error(`unknown batch mode: ${mode}`);
        }
      }
      db.exec("BEGIN IMMEDIATE");
      const out = [];
      try {
        for (const s of statements) {
          const params = s.params ?? [];
          const mode = s.mode ?? "run";
          if (mode === "get") out.push(db.prepare(s.sql).get(...params));
          else if (mode === "all") out.push(db.prepare(s.sql).all(...params));
          else {
            const r = db.prepare(s.sql).run(...params);
            out.push({ changes: r.changes, lastInsertRowid: r.lastInsertRowid });
          }
        }
        db.exec("COMMIT");
        return out;
      } catch (e) {
        try {
          db.exec("ROLLBACK");
        } catch {
          // Keep the original failure; rollback of a failed
          // batch has nothing left to report.
        }
        throw e;
      }
    },
    close() {
      db.close();
    },
  };
}

export function initSchema(db) {
  const dir = dirname(fileURLToPath(import.meta.url));
  const ddl = readFileSync(join(dir, "schema.sql"), "utf8");
  db.exec(ddl);
}
