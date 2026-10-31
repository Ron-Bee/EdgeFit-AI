// EF-GLOBAL-01 G2-B1 real D1 adapter. Maps a Cloudflare D1Database
// binding onto the async { get, all, run, exec, batch, close }
// adapter contract shared with cloud/db.js (node:sqlite). The API,
// qualification and retrieval layers speak only this contract, so
// no handler changes when the engine swaps underneath.
//
// D1 binding surface consumed (Cloudflare's public D1 API shape):
//   binding.prepare(sql).bind(...params) -> statement
//   statement.first()  -> row object | null
//   statement.all()    -> { results: [...] }
//   statement.run()    -> { meta: { changes, last_row_id } }
//   binding.batch([statement, ...]) -> per-statement results, in order
//   binding.exec(sql)  -> opaque exec result (DDL only here)
//
// UNVERIFIED against a live D1 service (see the G2-B2 plan): batch
// atomicity, result ordering, meta field presence, error shapes,
// read-after-write visibility, and per-invocation limits. This file
// normalizes defensively and throws EXPLICIT errors naming the gap
// instead of guessing, so staging failures are diagnosable, never
// silent. Local tests drive it through cloud/test/d1mock.js, which
// is a binding-shape mock, never a D1 service emulator.

/** Adapter-level failures: the binding did not honor its contract. */
function contractError(what) {
  return new Error(`d1: ${what}`);
}

function checkMode(mode) {
  const m = mode ?? "run";
  if (m !== "run" && m !== "get" && m !== "all") {
    throw contractError(`unknown batch mode: ${mode}`);
  }
  return m;
}

/**
 * Normalize a D1 write result to { changes, lastInsertRowid }.
 * changes MUST be a non-negative integer: the submission quota
 * verdict, revoke CAS, and qualification CAS all branch on exact
 * counts, so missing metadata fails LOUD (test G), never as zero.
 */
function runResult(res, what) {
  const changes = res?.meta?.changes;
  if (typeof changes !== "number" || !Number.isInteger(changes) ||
      changes < 0) {
    throw contractError(`${what} missing integer changes metadata`);
  }
  return { changes, lastInsertRowid: res.meta.last_row_id ?? null };
}

function rowsResult(res, what) {
  if (!res || !Array.isArray(res.results)) {
    throw contractError(`${what} missing results array`);
  }
  return res.results;
}

export function openD1(d1) {
  if (!d1 || typeof d1.prepare !== "function" ||
      typeof d1.batch !== "function") {
    throw contractError("binding lacks prepare()/batch(); not a D1 database");
  }
  const bound = (sql, params) => d1.prepare(sql).bind(...params);
  return {
    async get(sql, ...params) {
      // D1 first() yields null on no row; the contract says
      // undefined. Every handler treats both as absent, but the
      // adapter normalizes so engines are indistinguishable.
      const row = await bound(sql, params).first();
      return row ?? undefined;
    },
    async all(sql, ...params) {
      return rowsResult(await bound(sql, params).all(), "all()");
    },
    async run(sql, ...params) {
      return runResult(await bound(sql, params).run(), "run()");
    },
    async exec(sql) {
      // DDL path (schema/migration application). The result is
      // engine-opaque by contract; callers never read it.
      if (typeof d1.exec !== "function") {
        throw contractError("binding lacks exec(); cannot apply DDL");
      }
      return await d1.exec(sql);
    },
    /**
     * Fixed statement array in, aligned results out, in order.
     * Modes mirror db.js: run -> { changes, lastInsertRowid },
     * get -> row|undefined, all -> rows. ATOMICITY IS ASSUMED,
     * NOT PROVEN: the contract requires all-or-nothing, and a
     * rejected batch() yields NO partial results here (the throw
     * propagates with nothing returned), but whether live D1
     * rolls back already-applied statements needs the G2-B2
     * atomicity experiment. No mid-batch reads by construction:
     * every statement is bound before batch() is called.
     */
    async batch(statements) {
      const modes = statements.map((s) => checkMode(s.mode));
      const prepared = statements.map((s) => bound(s.sql, s.params ?? []));
      const results = await d1.batch(prepared);
      if (!Array.isArray(results) ||
          results.length !== statements.length) {
        throw contractError(
          "batch() result count does not match statement count");
      }
      return statements.map((s, i) => {
        const res = results[i];
        if (modes[i] === "get") {
          return rowsResult(res, "batch() get")[0] ?? undefined;
        }
        if (modes[i] === "all") {
          return rowsResult(res, "batch() all");
        }
        return runResult(res, "batch()");
      });
    },
    close() {
      // D1 bindings are runtime-managed; there is nothing to close.
      // Present so teardown code stays engine-agnostic.
    },
  };
}
