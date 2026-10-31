// G2-B2-FREE scheduler qualification (16 tests). Drives the
// scheduledTick/scheduled() entry points over node:sqlite and the
// D1 adapter + binding-shape mock. Proves bounded ticks use the
// EXISTING runQualification machine (no second algorithm), stay
// exactly-once under overlap, fail observably without leaking,
// and leave fetch behavior untouched.
import test from "node:test";
import assert from "node:assert/strict";
import { openD1 } from "../d1.js";
import { initSchema, openDatabase } from "../db.js";
import workerDefault, {
  workerFetch, scheduledTick, SCHEDULED_MAX_ROWS,
} from "../worker.js";
import { BATCH_LIMIT } from "../qualify.js";
import { openMockD1Binding } from "./d1mock.js";
import {
  NOW, MODEL_A, makeDeps, openTestDb, seedInstall, submitValid,
  get, applyMigrations,
} from "./helpers.js";

const PROMPT_B = "b".repeat(64);
let runCounter = 5000;

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-GSCH-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
}

function collector() {
  const lines = [];
  const errors = [];
  return {
    lines,
    errors,
    log: (s) => lines.push(String(s)),
    logErr: (s) => errors.push(String(s)),
  };
}

async function seed3(db, deps) {
  const tokens = { 1: "sched-tok-1", 2: "sched-tok-2",
    3: "sched-tok-3" };
  for (let i = 1; i <= 3; i++) {
    await seedInstall(db, deps, `sched-install-${i}`, tokens[i]);
  }
  return tokens;
}

async function submitN(db, deps, tokens, n, overrides = {}) {
  const ids = [];
  for (let i = 0; i < n; i++) {
    ids.push(await submitValid(db, deps, tokens[(i % 3) + 1],
      { ...freshRun(), ...overrides }));
  }
  return ids;
}

async function trailOf(db, id) {
  return (await db.all(
    "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  )).map((r) => r.to_state);
}

// 1. Empty tick, direct + default-export paths.
test("GSCH-01: empty tick reports zeros and resolves", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const c = collector();
  const sum = await scheduledTick({ db, deps, nowMs: NOW,
    log: c.log, logErr: c.logErr });
  assert.deepEqual(sum,
    { processed: 0, promoted: 0, held: 0, deferred: 0, maxRows: 10 });
  assert.equal(c.lines.length, 1);
  assert.equal(c.errors.length, 0);
  // Default export wires env + ctx.waitUntil.
  const mock = openMockD1Binding();
  t.after(() => mock.close());
  const d1 = openD1(mock.binding);
  await initSchema(d1);
  let waited = null;
  const ctx = { waitUntil: (p) => { waited = p; } };
  await workerDefault.scheduled({ cron: "*/10 * * * *" },
    { DB: mock.binding, INSTALL_SALT: "sched-salt" }, ctx);
  assert.ok(waited instanceof Promise);
  await waited;
});

// 2. One submitted row publishes through the existing machine.
test("GSCH-02: tick promotes via the shared state machine", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const c = collector();
  const sum = await scheduledTick({ db, deps, nowMs: NOW,
    log: c.log, logErr: c.logErr });
  assert.deepEqual(sum,
    { processed: 1, promoted: 3, held: 0, deferred: 0, maxRows: 10 });
  assert.equal((await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?",
    id)).eligibility, "PUBLISHED");
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
});

// 3. Ten rows process in one tick.
test("GSCH-03: ten pending rows clear in one tick", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 10);
  const sum = await scheduledTick({ db, deps, nowMs: NOW,
    ...collector() });
  assert.equal(sum.processed, 10);
  assert.equal(sum.promoted, 30);
});

// 4. Eleventh row waits; downward config honored.
test("GSCH-04: bound holds past ten; maxRows adjusts down", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 12);
  const first = await scheduledTick({ db, deps, nowMs: NOW,
    ...collector() });
  assert.equal(first.processed, 10);
  const left = await db.get(
    "SELECT COUNT(*) AS n FROM submissions WHERE eligibility = 'SUBMITTED'");
  assert.equal(left.n, 2);
  const second = await scheduledTick({ db, deps, nowMs: NOW,
    maxRows: 1, ...collector() });
  assert.equal(second.processed, 1);
  assert.equal(second.maxRows, 1);
  const third = await scheduledTick({ db, deps, nowMs: NOW,
    ...collector() });
  assert.equal(third.processed, 1);
});

// 5+6. Overlap is exactly-once; reruns are no-ops.
test("GSCH-05: concurrent ticks never double-promote", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ids = await submitN(db, deps, tokens, 5);
  const [a, b] = await Promise.all([
    scheduledTick({ db, deps, nowMs: NOW, ...collector() }),
    scheduledTick({ db, deps, nowMs: NOW, ...collector() }),
  ]);
  assert.equal(a.promoted + b.promoted, 15);
  for (const id of ids) {
    assert.deepEqual(await trailOf(db, id),
      ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"], id);
  }
});

test("GSCH-06: second tick promotes nothing new", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 3);
  await scheduledTick({ db, deps, nowMs: NOW, ...collector() });
  const again = await scheduledTick({ db, deps, nowMs: NOW,
    ...collector() });
  assert.deepEqual(again,
    { processed: 0, promoted: 0, held: 0, deferred: 0, maxRows: 10 });
});

// 7+8. Revocation wins; never resurrected.
test("GSCH-07: revoke held open mid-tick stays revoked", async (t) => {
  const inner = openDatabase(":memory:");
  await initSchema(inner);
  await applyMigrations(inner);
  let release;
  const gate = new Promise((r) => { release = r; });
  let armed = false;
  let entered;
  const enteredP = new Promise((r) => { entered = r; });
  const db = {
    get: (...a) => inner.get(...a),
    all: (...a) => inner.all(...a),
    run: (...a) => inner.run(...a),
    exec: (...a) => inner.exec(...a),
    close: () => inner.close(),
    batch: async (s) => {
      if (armed) {
        armed = false;
        entered();
        await gate;
      }
      return inner.batch(s);
    },
  };
  const deps = makeDeps();
  try {
    const tokens = await seed3(db, deps);
    const [id] = await submitN(db, deps, tokens, 1);
    armed = true;
    const tickP = scheduledTick({ db, deps, nowMs: NOW,
      ...collector() });
    await enteredP;
    const { handleRequest } = await import("../api.js");
    await handleRequest({ db, deps, nowMs: NOW, method: "DELETE",
      path: `/v1/submissions/${id}`, query: {},
      headers: { authorization: `Bearer ${tokens[1]}` }, bodyText: "" });
    release();
    const sum = await tickP;
    assert.equal(sum.deferred, 1);
    assert.equal(sum.promoted, 0);
    assert.equal((await db.get(
      "SELECT eligibility FROM submissions WHERE submission_id = ?",
      id)).eligibility, "REVOKED");
    assert.deepEqual(await trailOf(db, id), ["SUBMITTED", "REVOKED"]);
  } finally {
    inner.close();
  }
});

test("GSCH-08: revoked rows never resurrect on later ticks", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const { handleRequest } = await import("../api.js");
  await handleRequest({ db, deps, nowMs: NOW, method: "DELETE",
    path: `/v1/submissions/${id}`, query: {},
    headers: { authorization: `Bearer ${tokens[1]}` }, bodyText: "" });
  const sum = await scheduledTick({ db, deps, nowMs: NOW,
    ...collector() });
  assert.equal(sum.promoted, 0);
  assert.deepEqual(await trailOf(db, id), ["SUBMITTED", "REVOKED"]);
});

// 9+10. Failures are loud, sanitized, and atomic.
test("GSCH-09: dead database rejects with a sanitized log line", async (t) => {
  const dead = {
    get: async () => { throw new Error("GSCH9_FAKE down"); },
    all: async () => { throw new Error("GSCH9_FAKE down"); },
    run: async () => { throw new Error("GSCH9_FAKE down"); },
    exec: async () => { throw new Error("GSCH9_FAKE down"); },
    batch: async () => { throw new Error("GSCH9_FAKE down"); },
    close: () => {},
  };
  const deps = makeDeps();
  const c = collector();
  await assert.rejects(
    scheduledTick({ db: dead, deps, nowMs: NOW,
      log: c.log, logErr: c.logErr }), /GSCH9_FAKE/);
  assert.equal(c.errors.length, 1);
  assert.ok(!c.errors[0].includes("GSCH9_FAKE"), c.errors[0]);
  assert.ok(c.lines.length === 0);
});

test("GSCH-10: failed batch rolls back the tick's writes", async (t) => {
  // Node adapter whose transition batch fails once, mid-tick.
  const deps = makeDeps();
  const inner = openDatabase(":memory:");
  await initSchema(inner);
  await applyMigrations(inner);
  let failNext = false;
  const flaky = {
    get: (...a) => inner.get(...a),
    all: (...a) => inner.all(...a),
    run: (...a) => inner.run(...a),
    exec: (...a) => inner.exec(...a),
    close: () => inner.close(),
    batch: async (s) => {
      if (failNext) {
        failNext = false;
        throw new Error("GSCH10_FAKE batch down");
      }
      return inner.batch(s);
    },
  };
  try {
    const tokens = await seed3(flaky, deps);
    const [id] = await submitN(flaky, deps, tokens, 1);
    failNext = true; // the transition batch dies mid-tick
    const c = collector();
    // Per-row containment converts even batch death to a hold:
    // the tick resolves, the row keeps SUBMITTED, no history.
    const sum = await scheduledTick({ db: flaky, deps, nowMs: NOW,
      log: c.log, logErr: c.logErr });
    assert.equal(sum.held, 1);
    assert.equal(sum.promoted, 0);
    assert.equal((await flaky.get(
      "SELECT eligibility FROM submissions WHERE submission_id = ?",
      id)).eligibility, "SUBMITTED");
    assert.deepEqual(await trailOf(flaky, id), ["SUBMITTED"]);
  } finally {
    inner.close();
  }
});

// 11. Retry after failure promotes.
test("GSCH-11: a later tick retries what failure left behind", async (t) => {
  const inner = openDatabase(":memory:");
  await initSchema(inner);
  await applyMigrations(inner);
  let down = true;
  const recovering = {
    get: async (...a) => {
      if (down) throw new Error("GSCH11_FAKE down");
      return inner.get(...a);
    },
    all: async (...a) => {
      if (down) throw new Error("GSCH11_FAKE down");
      return inner.all(...a);
    },
    run: (...a) => inner.run(...a),
    exec: (...a) => inner.exec(...a),
    close: () => inner.close(),
    batch: async (s) => {
      if (down) throw new Error("GSCH11_FAKE down");
      return inner.batch(s);
    },
  };
  const deps = makeDeps();
  try {
    // Seed while healthy.
    const tokens = await seed3(inner, deps);
    const { handleRequest } = await import("../api.js");
    const { makeEnvelope, makeResult } = await import("./helpers.js");
    const r = await handleRequest({ db: inner, deps, nowMs: NOW,
      method: "POST", path: "/v1/submissions", query: {},
      headers: { authorization: `Bearer ${tokens[1]}` },
      bodyText: JSON.stringify(makeEnvelope(makeResult(freshRun()))) });
    assert.equal(r.status, 201);
    const c = collector();
    await assert.rejects(
      scheduledTick({ db: recovering, deps, nowMs: NOW,
        log: c.log, logErr: c.logErr }), /GSCH11_FAKE/);
    down = false;
    const retry = await scheduledTick({ db: recovering, deps,
      nowMs: NOW, ...collector() });
    assert.equal(retry.processed, 1);
    assert.equal(retry.promoted, 3);
    assert.equal((await inner.get(
      "SELECT eligibility FROM submissions WHERE submission_id = ?",
      r.body.submission_id)).eligibility, "PUBLISHED");
  } finally {
    inner.close();
  }
});

// 12. Fetch surface unchanged; no scheduled HTTP route.
test("GSCH-12: fetch map untouched; scheduled has no URL", async (t) => {
  assert.deepEqual(Object.keys(workerDefault).sort(),
    ["fetch", "scheduled"]);
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ctx = { db, deps, nowMs: NOW };
  const res = await workerFetch(new Request(
    "https://gsch.invalid/v1/submissions",
    { method: "POST", headers: { authorization: `Bearer ${tokens[1]}` },
      body: JSON.stringify((await import("./helpers.js")).makeEnvelope(
        (await import("./helpers.js")).makeResult(freshRun()))) }),
  ctx);
  assert.equal(res.status, 201);
  for (const path of ["/scheduled", "/v1/scheduled", "/cron",
    "/v1/qualify", "/v1/tick"]) {
    const probe = await workerFetch(
      new Request(`https://gsch.invalid${path}`, { method: "GET" }), ctx);
    assert.equal(probe.status, 404, path);
  }
});

// 13+14. Publication semantics identical through ticks.
test("GSCH-13: thin cohorts stay suppressed after ticks", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 2);
  await scheduledTick({ db, deps, nowMs: NOW, ...collector() });
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "insufficient-evidence");
  assert.equal(agg.body.groups[0].aggregates, null);
});

test("GSCH-14: ticks preserve exact-cohort separation", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 5);
  await submitN(db, deps, tokens, 5, {
    workload: { id: "edgefit-prompt-v1", prompt_sha256: PROMPT_B,
      prompt_chars: 100 },
  });
  await scheduledTick({ db, deps, nowMs: NOW, ...collector() });
  await scheduledTick({ db, deps, nowMs: NOW, ...collector() });
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 2);
  for (const g of agg.body.groups) {
    assert.equal(g.status, "published");
    assert.equal(g.n, 5);
  }
});

// 15. Logs carry counts only.
test("GSCH-15: tick logs never name tokens, installs or rows", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ids = await submitN(db, deps, tokens, 3);
  const c = collector();
  await scheduledTick({ db, deps, nowMs: NOW,
    log: c.log, logErr: c.logErr });
  const text = [...c.lines, ...c.errors].join("\n");
  for (const needle of [...Object.values(tokens), "sched-install-1",
    "sched-install-2", "sched-install-3", "test-salt", "token_hash",
    "result_json", ...ids]) {
    assert.ok(!text.includes(needle), `leaked ${needle}`);
  }
  assert.match(c.lines[0],
    /^qualify tick processed=3 promoted=9 held=0 deferred=0 maxRows=10$/);
});

// 16. Budgeting: ceiling, clamps, default preserved.
test("GSCH-16: batch ceiling is 10, downward-only, default intact", async (t) => {
  assert.equal(SCHEDULED_MAX_ROWS, 10);
  assert.equal(BATCH_LIMIT, 1000);
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 12);
  const over = await scheduledTick({ db, deps, nowMs: NOW,
    maxRows: 999, ...collector() });
  assert.equal(over.processed, 10);
  assert.equal(over.maxRows, 10);
  const zero = await scheduledTick({ db, deps, nowMs: NOW,
    maxRows: 0, ...collector() });
  assert.equal(zero.processed, 1);
  assert.equal(zero.maxRows, 1);
  // runQualification default still drains everything.
  const { runQualification } = await import("../qualify.js");
  const rest = await runQualification(db, NOW, deps);
  assert.equal(rest.processed, 1);
});
