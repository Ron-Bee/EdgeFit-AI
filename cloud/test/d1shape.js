// Test-only D1-shape adapter (G2, G2-B1 async contract). Proves the
// backend uses only the batch-compatible interface
// ({ get, all, run, exec, batch, close }, all async) plus the
// adapter error contract — the same contract a real D1 binding must
// satisfy. SQL execution underneath is node:sqlite; this proves
// INTERFACE compatibility, never D1 service behavior (error text,
// limits, contention, replication all need staging qualification).
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { openDatabase, initSchema } from "../db.js";

// Staging-extensible mapping: observed real-D1 error shapes
// translate here into the adapter error contract. Entries are
// added ONLY from staging runs, never assumed.
const D1_ERROR_TABLE = [];

export function normalizeD1Error(e) {
  for (const [test, make] of D1_ERROR_TABLE) {
    if (test(e)) return make(e);
  }
  if (e && e.name === "D1Error") {
    const out = new Error(e.message);
    if (e.code !== undefined) out.code = e.code;
    if (e.errcode !== undefined) out.errcode = e.errcode;
    if (e.errstr !== undefined) out.errstr = e.errstr;
    return out;
  }
  return e;
}

function wrapD1Error(e) {
  // The fake engine surfaces failures D1-style (named wrapper
  // carrying code/message/errstr); the adapter normalizes them
  // through the single mapping point above, exactly as the real
  // binding must for genuine D1 errors.
  const wrapped = new Error(String((e && e.message) ?? e));
  wrapped.name = "D1Error";
  if (e && e.code !== undefined) wrapped.code = e.code;
  if (e && e.errcode !== undefined) wrapped.errcode = e.errcode;
  if (e && e.errstr !== undefined) wrapped.errstr = e.errstr;
  return wrapped;
}

export function openD1Shape(filename = ":memory:") {
  const inner = openDatabase(filename);
  return {
    get: inner.get.bind(inner),
    all: inner.all.bind(inner),
    run: inner.run.bind(inner),
    exec: inner.exec.bind(inner),
    async batch(statements) {
      for (const s of statements) {
        const mode = s.mode ?? "run";
        if (mode !== "run" && mode !== "get" && mode !== "all") {
          throw normalizeD1Error(new Error(`unknown batch mode: ${mode}`));
        }
      }
      // Fixed array in, results out: no mid-batch reads are
      // expressible, mirroring D1 batch() semantics. Atomic.
      await inner.exec("BEGIN IMMEDIATE");
      const out = [];
      try {
        for (const s of statements) {
          const params = s.params ?? [];
          const mode = s.mode ?? "run";
          if (mode === "get") out.push(await inner.get(s.sql, ...params));
          else if (mode === "all") out.push(await inner.all(s.sql, ...params));
          else {
            const r = await inner.run(s.sql, ...params);
            out.push({
              changes: r.changes,
              lastInsertRowid: r.lastInsertRowid,
            });
          }
        }
        await inner.exec("COMMIT");
        return out;
      } catch (e) {
        try {
          await inner.exec("ROLLBACK");
        } catch {
          // Keep the original failure.
        }
        throw normalizeD1Error(wrapD1Error(e));
      }
    },
    close: inner.close.bind(inner),
  };
}

// PUB-02: the D1-shape suite calibrates adapter behavior WITHOUT
// the 002 audit trigger (history assertions in G2-C assume its
// absence), but submissions now require the 003 columns. Apply
// 003 alone: current columns, provenance triggers live, audit
// trigger absent. Production never holds this hybrid (the journal
// applies in chain order); it is a test-only calibration choice.
export async function applyProvenanceColumns(db) {
  const dir = join(dirname(fileURLToPath(import.meta.url)), "..",
    "migrations");
  await db.exec(readFileSync(join(dir, "003_evidence_origin.sql"), "utf8"));
}

export async function openD1ShapeTestDb() {
  const db = openD1Shape(":memory:");
  await initSchema(db);
  await applyProvenanceColumns(db);
  return db;
}
