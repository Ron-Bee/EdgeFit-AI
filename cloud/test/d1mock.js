// G2-B1 D1 binding-shape mock. Implements the BINDING surface the
// cloud/d1.js adapter consumes (prepare/bind/first/all/run/batch/
// exec) over node:sqlite, so tests verify ADAPTER logic: shape
// mapping, result order, null normalization, and loud failures on
// missing metadata. This is NOT a D1 service emulator: it proves
// nothing about live batch atomicity, D1 error text, limits, or
// read visibility. Where the contract REQUIRES behavior the mock
// implements it (atomic batch, ordered results) and says so; every
// such choice is a G2-B1 assumption listed for G2-B2 live testing.
//
// faults: {
//   throwOn: { prepare|bind|first|all|run|batch|exec: Error|fn },
//   dropMeta: true      -> run() returns {} (no meta at all)
//   badChanges: <value> -> meta.changes is not a valid count
//   dropResults: true   -> all()/SELECT batch items lose .results
//   shortBatch: true    -> batch() drops its last result
// }
// calls: appended ["prepare"|"batch"|"exec", sql|count] log.

import { DatabaseSync } from "node:sqlite";

function isSelect(sql) {
  // Mock-only verb heuristic: our batches hold INSERT/UPDATE/
  // SELECT, and INSERT..SELECT forms start with INSERT, so a
  // SELECT prefix unambiguously means "rows come back".
  return /^\s*SELECT\b/i.test(sql);
}

export function openMockD1Binding({ faults = {} } = {}) {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA busy_timeout = 5000");
  const calls = [];

  function maybeThrow(op) {
    const spec = faults.throwOn?.[op];
    if (spec === undefined || spec === null || spec === false) return;
    throw typeof spec === "function" ? spec(op) : spec;
  }

  function runShape(sql, params) {
    maybeThrow("run");
    const r = sqlite.prepare(sql).run(...params);
    if (faults.dropMeta) return { success: true };
    const changes = "badChanges" in faults ? faults.badChanges : r.changes;
    return {
      success: true,
      meta: { changes, last_row_id: r.lastInsertRowid },
    };
  }

  function allShape(sql, params) {
    maybeThrow("all");
    const rows = sqlite.prepare(sql).all(...params);
    if (faults.dropResults) return { success: true };
    return { results: rows, success: true, meta: {} };
  }

  function firstShape(sql, params) {
    maybeThrow("first");
    // D1 first() yields null on no row: the mock does exactly that
    // so adapter null->undefined normalization is really tested.
    return sqlite.prepare(sql).get(...params) ?? null;
  }

  function boundStatement(sql, params) {
    return {
      sql,
      params,
      first: async () => firstShape(sql, params),
      all: async () => allShape(sql, params),
      run: async () => runShape(sql, params),
    };
  }

  const binding = {
    prepare(sql) {
      calls.push(["prepare", sql]);
      maybeThrow("prepare");
      return {
        bind(...params) {
          maybeThrow("bind");
          return boundStatement(sql, params);
        },
      };
    },
    async batch(statements) {
      calls.push(["batch", statements.length]);
      maybeThrow("batch");
      // Atomic by REQUIREMENT, not by observation: the adapter
      // contract demands all-or-nothing, so the mock enforces it
      // with a real transaction. Whether live D1 batch() is
      // atomic is the first G2-B2 experiment, not shown here.
      sqlite.exec("BEGIN IMMEDIATE");
      const out = [];
      try {
        for (const s of statements) {
          if (isSelect(s.sql)) out.push(allShape(s.sql, s.params));
          else out.push(runShape(s.sql, s.params));
        }
        sqlite.exec("COMMIT");
      } catch (e) {
        try {
          sqlite.exec("ROLLBACK");
        } catch {
          // Keep the original failure.
        }
        throw e;
      }
      if (faults.shortBatch) return out.slice(0, -1);
      return out;
    },
    async exec(sql) {
      calls.push(["exec", sql.length]);
      maybeThrow("exec");
      sqlite.exec(sql);
      return { success: true };
    },
  };

  return {
    binding,
    calls,
    close() {
      sqlite.close();
    },
  };
}
