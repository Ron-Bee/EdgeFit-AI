// G2: batch-primitive conformance, D1-shape interface compatibility,
// app behavior through the D1-shape adapter, migration drift guard.
// These prove the backend uses ONLY the batch-compatible interface;
// D1 service behavior itself needs staging (see the staging design).
import test from "node:test";
import assert from "node:assert/strict";
import { join, dirname } from "node:path";
import { mkdirSync, readFileSync, unlinkSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { openDatabase, initSchema } from "../db.js";
import { evidenceRetrieve } from "../retrieval.js";
import {
  openD1Shape, openD1ShapeTestDb, normalizeD1Error,
} from "./d1shape.js";
import {
  NOW, MODEL_A, PROMPT_SHA, makeDeps, openTestDb, seedInstall, get,
  submitValid, qualify, tpsFor, post, makeEnvelope, makeResult,
} from "./helpers.js";

const PROMPT_B = "b".repeat(64);
const DAY = "2026-10-30";
let runCounter = 0;
let fileCounter = 0;

const ADAPTERS = {
  node: () => {
    const db = openTestDb();
    return db;
  },
  d1shape: () => openD1ShapeTestDb(),
};

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-G2-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
}

function seed3(db, deps) {
  const tokens = { 1: "token-one", 2: "token-two", 3: "token-three" };
  for (let i = 1; i <= 3; i++) seedInstall(db, deps, `install-${i}`, tokens[i]);
}

function tappedDb(db, hooks = {}) {
  return {
    get: db.get.bind(db),
    all(sql, ...params) {
      const rows = db.all(sql, ...params);
      if (hooks.afterAll) hooks.afterAll(sql, rows);
      return rows;
    },
    run(sql, ...params) {
      if (hooks.beforeRun) hooks.beforeRun(sql, params);
      return db.run(sql, ...params);
    },
    batch(statements) {
      if (hooks.beforeRun) {
        for (const s of statements) hooks.beforeRun(s.sql, s.params ?? []);
      }
      return db.batch(statements);
    },
    exec: db.exec.bind(db),
    close: db.close.bind(db),
  };
}

function openSharedD1(t) {
  fileCounter += 1;
  const dir = join(dirname(fileURLToPath(import.meta.url)), ".tmp-g1b");
  mkdirSync(dir, { recursive: true });
  const path = join(dir, `g2shared-${process.pid}-${fileCounter}.db`);
  const a = openD1Shape(path);
  initSchema(a);
  const b = openD1Shape(path);
  t.after(() => {
    try { a.close(); } catch { /* ignore */ }
    try { b.close(); } catch { /* ignore */ }
    try { unlinkSync(path); } catch { /* ignore */ }
  });
  return { a, b };
}

function rdeps(deps) {
  return {
    sha256hex: deps.sha256hex,
    installSalt: deps.installSalt,
    deviceClasses: deps.deviceClasses,
    quorumRuns: deps.quorumRuns,
    quorumInstalls: deps.quorumInstalls,
  };
}

// ---------- A. adapter conformance ----------

test("G2-A1: both adapters expose exactly the batch interface", () => {
  for (const [name, open] of Object.entries(ADAPTERS)) {
    const db = open();
    assert.deepEqual(Object.keys(db).sort(),
      ["all", "batch", "close", "exec", "get", "run"], name);
    assert.equal(typeof db.transaction, "undefined", name);
    db.close();
  }
});

test("G2-A2: batch run/get/all modes return aligned shapes", () => {
  for (const [name, open] of Object.entries(ADAPTERS)) {
    const deps = makeDeps();
    const db = open();
    seedInstall(db, deps, "install-1", "token-one");
    const [ins, one, many] = db.batch([
      { sql: "INSERT INTO daily_counts (install_id, day, count) VALUES (?, ?, ?)",
        params: ["install-1", DAY, 4] },
      { sql: "SELECT count FROM daily_counts WHERE install_id = ?",
        params: ["install-1"], mode: "get" },
      { sql: "SELECT install_id FROM installs", mode: "all" },
    ]);
    assert.equal(ins.changes, 1, name);
    assert.equal(one.count, 4, name);
    assert.deepEqual(many.map((r) => r.install_id), ["install-1"], name);
    db.close();
  }
});

test("G2-A3: batch failures roll back every statement", () => {
  for (const [name, open] of Object.entries(ADAPTERS)) {
    const db = open();
    let threw = null;
    try {
      db.batch([
        { sql: "INSERT INTO daily_counts (install_id, day, count) VALUES (?, ?, ?)",
          params: ["install-9", DAY, 1] },
        { sql: "INSERT INTO daily_counts (install_id, day, count) VALUES (?, ?, ?)",
          params: ["install-9", DAY, 2] },
        { sql: "INSERT INTO no_such_table (x) VALUES (1)" },
      ]);
    } catch (e) {
      threw = e;
    }
    assert.ok(threw, `${name}: batch must throw`);
    assert.equal(db.get("SELECT COUNT(*) AS n FROM daily_counts").n, 0, name);
    db.close();
  }
});

test("G2-A4: empty batch is a no-op returning []", () => {
  for (const [name, open] of Object.entries(ADAPTERS)) {
    const db = open();
    assert.deepEqual(db.batch([]), [], name);
    db.close();
  }
});

test("G2-A5: unknown batch mode throws before executing", () => {
  for (const [name, open] of Object.entries(ADAPTERS)) {
    const db = open();
    let threw = null;
    try {
      db.batch([
        { sql: "INSERT INTO daily_counts (install_id, day, count) VALUES (?, ?, ?)",
          params: ["install-9", DAY, 1], mode: "bogus" },
      ]);
    } catch (e) {
      threw = e;
    }
    assert.ok(threw, `${name}: batch must throw`);
    assert.equal(db.get("SELECT COUNT(*) AS n FROM daily_counts").n, 0, name);
    db.close();
  }
});

// ---------- B. error normalization ----------

test("G2-B1: UNIQUE failures keep contract shape through D1 errors", () => {
  const deps = makeDeps();
  const db = openD1ShapeTestDb();
  seedInstall(db, deps, "install-1", "token-one");
  let threw = null;
  try {
    db.batch([
      { sql: "INSERT INTO installs (install_id, token_hash, created_at) VALUES (?, ?, ?)",
        params: ["install-1", "other-hash", new Date(NOW).toISOString()] },
    ]);
  } catch (e) {
    threw = e;
  }
  assert.ok(threw);
  assert.match(threw.message,
    /UNIQUE constraint failed: installs\.install_id/);
  db.close();
});

test("G2-B2: the normalizer round-trips fields, passes unknowns through", () => {
  const d1 = new Error("database is locked");
  d1.name = "D1Error";
  d1.code = "SQLITE_BUSY";
  d1.errcode = 5;
  const norm = normalizeD1Error(d1);
  assert.equal(norm.message, "database is locked");
  assert.equal(norm.code, "SQLITE_BUSY");
  assert.equal(norm.errcode, 5);
  const plain = new Error("UNIQUE constraint failed: submissions.content_hash");
  assert.equal(normalizeD1Error(plain), plain);
});

// ---------- C. app behavior through the D1-shape adapter ----------

test("G2-C1: prompt SHAs split cohorts on the D1-shape adapter", () => {
  const deps = makeDeps();
  const db = openD1ShapeTestDb();
  for (let i = 1; i <= 3; i++) seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  [1, 2, 3, 1, 2].forEach((inst, i) => {
    submitValid(db, deps, `test-token-${inst}`, {
      ...freshRun(),
      workload: { id: "edgefit-prompt-v1",
        prompt_sha256: i < 3 ? PROMPT_SHA : PROMPT_B, prompt_chars: 100 },
    });
  });
  qualify(db, deps);
  const res = get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(res.body.groups.length, 2);
  assert.ok(res.body.groups.every((g) => g.status === "insufficient-evidence"));
  const ret = evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, rdeps(deps));
  assert.deepEqual(ret.cites, []);
  db.close();
});

test("G2-C2: mixed cohorts publish representative stats on D1-shape", () => {
  const deps = makeDeps();
  const db = openD1ShapeTestDb();
  for (let i = 1; i <= 3; i++) seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  const at = (tps) => {
    const ms = Math.round((64 * 1000) / tps);
    return {
      load_ms: 1200, prefill_ms: 1000, prefill_tokens: 23,
      gen_ms: ms, generated_tokens: 64,
      tps_gen: tpsFor(64, ms), tps_prefill: 23.0, eos_stop: false,
      mem: { pss_before_kb: 100, pss_after_kb: 200,
        alloc_delta_bytes: 4096, method: "test" },
      thermal: { before: "none", mid: "none", after: "none", available: true },
    };
  };
  for (let i = 0; i < 5; i++) {
    submitValid(db, deps, `test-token-${(i % 3) + 1}`, {
      ...freshRun(),
      config: { n_ctx: 512, n_threads: 4, n_predict: 64 },
      measurements: at(10),
    });
  }
  for (const n_ctx of [256, 1024]) {
    submitValid(db, deps, "test-token-1", {
      ...freshRun(),
      config: { n_ctx, n_threads: 4, n_predict: 64 },
      measurements: at(100),
    });
  }
  qualify(db, deps);
  const res = get(db, deps, "/v1/evidence/model-candidates", {});
  const c = res.body.candidates.find((x) => x.model_sha === MODEL_A);
  assert.equal(c.status, "published");
  assert.equal(c.mean_gen_tps, 10);
  assert.equal(c.median_gen_tps, 10);
  assert.equal(c.cohort.config.n_ctx, 512);
  db.close();
});

test("G2-C3: exact quorum publishes on the D1-shape adapter", () => {
  const deps = makeDeps();
  const db = openD1ShapeTestDb();
  for (let i = 1; i <= 3; i++) seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  [1, 2, 3, 1, 2].forEach((inst) => {
    submitValid(db, deps, `test-token-${inst}`, freshRun());
  });
  qualify(db, deps);
  const agg = get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "published");
  assert.equal(agg.body.groups[0].n, 5);
  const ret = evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, rdeps(deps));
  assert.equal(ret.cites.length, 1);
  assert.equal(ret.cites[0].cohort_hash, agg.body.groups[0].cohort_hash);
  db.close();
});

test("G2-C4: revocation during qualification stays terminal on D1-shape", (t) => {
  const deps = makeDeps();
  const { a, b } = openSharedD1(t);
  seed3(a, deps);
  const id = submitValid(a, deps, "token-one", freshRun());
  let fired = false;
  const tapped = tappedDb(a, {
    afterAll(sql) {
      if (fired || !sql.includes("eligibility IN (")) return;
      fired = true;
      b.run("UPDATE submissions SET eligibility = 'REVOKED' WHERE submission_id = ?", id);
      b.run(
        `INSERT INTO eligibility_history
           (submission_id, from_state, to_state, reason, at)
         VALUES (?, 'SUBMITTED', 'REVOKED', 'concurrent test revoke', ?)`,
        id, new Date(NOW).toISOString(),
      );
    },
  });
  const report = qualify(tapped, deps);
  assert.ok(fired, "interleave hook never fired");
  assert.equal(
    a.get("SELECT eligibility FROM submissions WHERE submission_id = ?", id).eligibility,
    "REVOKED",
  );
  assert.ok(report.promoted.every((p) => p.submission_id !== id));
});

test("G2-C5: quota race admits exactly quota on D1-shape", () => {
  const deps = makeDeps({ quotaPerDay: 3 });
  const db = openD1ShapeTestDb();
  seed3(db, deps);
  submitValid(db, deps, "token-one", freshRun());
  submitValid(db, deps, "token-one", freshRun());
  let bResult = null;
  let fired = false;
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (fired || !/^INSERT INTO submissions/i.test(sql)) return;
      fired = true;
      bResult = post(db, deps, "token-one", makeEnvelope(makeResult(freshRun())));
    },
  });
  const aRes = post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  assert.ok(fired, "interleave hook never fired");
  assert.equal(bResult.status, 201);
  assert.equal(aRes.status, 429);
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 3);
  assert.equal(db.get(
    "SELECT count FROM daily_counts WHERE install_id = 'install-1'").count, 3);
  db.close();
});

test("G2-C6: duplicate race resolves to one row on D1-shape", () => {
  const deps = makeDeps();
  const db = openD1ShapeTestDb();
  seed3(db, deps);
  const envelope = makeEnvelope(makeResult(freshRun()));
  let bResult = null;
  let fired = false;
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (fired || !/^INSERT INTO submissions/i.test(sql)) return;
      fired = true;
      bResult = post(db, deps, "token-two", envelope);
    },
  });
  let res = null;
  let threw = null;
  try {
    res = post(tapped, deps, "token-one", envelope);
  } catch (e) {
    threw = e;
  }
  assert.ok(fired, "interleave hook never fired");
  assert.equal(bResult.status, 201);
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.body.duplicate, true);
  assert.equal(res.body.submission_id, bResult.body.submission_id);
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 1);
  db.close();
});

test("G2-C7: write failure sanitizes with zero partial rows on D1-shape", () => {
  const deps = makeDeps();
  const db = openD1ShapeTestDb();
  seed3(db, deps);
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (/INSERT INTO eligibility_history/i.test(sql)) {
        throw new Error("CANARY_G2_7k7k SQLITE_FAKE x'; DROP TABLE submissions; --");
      }
    },
  });
  let res = null;
  let threw = null;
  try {
    res = post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 500);
  assert.equal(res.body.error.code, "internal");
  const serialized = JSON.stringify(res.body);
  assert.ok(!serialized.includes("CANARY_G2_7k7k"));
  assert.ok(!serialized.includes("DROP TABLE"));
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 0);
  db.close();
});

test("G2-C8: lock maps to 503 with Retry-After on D1-shape", () => {
  const deps = makeDeps();
  const db = openD1ShapeTestDb();
  seed3(db, deps);
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (/^INSERT INTO submissions/i.test(sql)) {
        const err = new Error("database is locked");
        err.code = "SQLITE_BUSY";
        throw err;
      }
    },
  });
  let res = null;
  let threw = null;
  try {
    res = post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 503);
  assert.equal(res.body.error.code, "temporarily_unavailable");
  assert.equal(res.headers["retry-after"], "1");
  db.close();
});

// ---------- D. migration drift ----------

test("G2-D1: migration 001 carries the exact initial schema", () => {
  const dir = dirname(fileURLToPath(import.meta.url));
  const schema = readFileSync(join(dir, "..", "schema.sql"), "utf8");
  const migration = readFileSync(join(dir, "..", "migrations", "001_init.sql"), "utf8");
  const normalize = (text) => text.split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith("--"))
    .join("\n");
  const body = normalize(schema);
  assert.ok(body.length > 1000, "schema body unexpectedly small");
  assert.ok(normalize(migration).includes(body),
    "001_init.sql drifted from schema.sql");
});
