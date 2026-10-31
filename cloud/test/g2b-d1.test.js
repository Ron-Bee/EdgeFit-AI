// G2-B1 D1 adapter qualification (harness E-N, adapter half).
// Drives cloud/d1.js through the binding-shape mock: adapter logic
// only (mapping, order, normalization, loud metadata failures).
// NOT D1 service qualification: atomicity, error text, limits and
// read visibility need live staging (G2-B2 plan). Behavioral suites
// (I, J) re-prove G1B/G2-A guarantees hold THROUGH the adapter.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { openD1 } from "../d1.js";
import { initSchema } from "../db.js";
import { evidenceRetrieve } from "../retrieval.js";
import { openMockD1Binding } from "./d1mock.js";
import {
  NOW, MODEL_A, makeDeps, seedInstall, get, submitValid, qualify,
  applyMigrations,
} from "./helpers.js";

const PROMPT_B = "b".repeat(64);
let runCounter = 0;

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-G2B-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
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

/** D1 adapter over a fresh mock binding, schema + migrations applied. */
async function openAdapter(t, faults = {}) {
  const mock = openMockD1Binding({ faults });
  t.after(() => mock.close());
  const db = openD1(mock.binding);
  await initSchema(db);
  await applyMigrations(db);
  return { db, mock };
}

async function seed3(db, deps) {
  const tokens = { 1: "token-one", 2: "token-two", 3: "token-three" };
  for (let i = 1; i <= 3; i++) {
    await seedInstall(db, deps, `install-${i}`, tokens[i]);
  }
  return tokens;
}

/** Five quorum runs across installs 1..3 (2/2/1 split). */
async function submitQuorum(db, deps, tokens, overrides = {}) {
  const ids = [];
  const lanes = [1, 2, 3, 1, 2];
  for (const lane of lanes) {
    ids.push(await submitValid(db, deps, tokens[lane],
      { ...freshRun(), ...overrides }));
  }
  return ids;
}

// ---------- E. D1 binding failures ----------

test("G2B-D-E1: binding prepare failure rejects every adapter call", async (t) => {
  const boom = new Error("D1_FAKE_DOWN");
  const { db } = await openAdapter(t, { throwOn: { prepare: boom } });
  for (const call of [
    () => db.get("SELECT 1"),
    () => db.all("SELECT 1"),
    () => db.run("SELECT 1"),
    () => db.batch([{ sql: "SELECT 1", mode: "all" }]),
  ]) {
    await assert.rejects(call, /D1_FAKE_DOWN/);
  }
});

test("G2B-D-E2: batch rejection returns nothing, never partial results", async (t) => {
  // Negative: a failed batch must throw, not resolve with a prefix.
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  await seedInstall(db, deps, "install-1", "token-one");
  await assert.rejects(
    db.batch([
      { sql: "INSERT INTO daily_counts (install_id, day, count) VALUES (?, ?, ?)",
        params: ["install-1", "2026-10-30", 1] },
      { sql: "INSERT INTO nope_missing (x) VALUES (1)" },
    ]),
    /nope_missing/,
  );
});

test("G2B-D-E3: exec failure propagates to the caller", async (t) => {
  const boom = new Error("D1_FAKE_EXEC_DOWN");
  // No schema setup: initSchema itself uses exec and would trip the fault.
  const mock = openMockD1Binding({ faults: { throwOn: { exec: boom } } });
  t.after(() => mock.close());
  const db = openD1(mock.binding);
  await assert.rejects(db.exec("SELECT 1"), /D1_FAKE_EXEC_DOWN/);
});

// ---------- F. batch shape and order ----------

test("G2B-D-F1: mixed run/get/all modes return aligned shapes", async (t) => {
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  await seedInstall(db, deps, "install-1", "token-one");
  const [ins, one, many] = await db.batch([
    { sql: "INSERT INTO daily_counts (install_id, day, count) VALUES (?, ?, ?)",
      params: ["install-1", "2026-10-30", 4] },
    { sql: "SELECT count FROM daily_counts WHERE install_id = ?",
      params: ["install-1"], mode: "get" },
    { sql: "SELECT install_id FROM installs", mode: "all" },
  ]);
  assert.equal(ins.changes, 1);
  assert.equal(one.count, 4);
  assert.deepEqual(many.map((r) => r.install_id), ["install-1"]);
});

test("G2B-D-F2: batch results stay in statement order", async (t) => {
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  await seedInstall(db, deps, "install-1", "token-one");
  const stmts = [];
  for (let i = 1; i <= 5; i++) {
    stmts.push({
      sql: "SELECT ? AS pos",
      params: [i],
      mode: "get",
    });
  }
  const rows = await db.batch(stmts);
  assert.deepEqual(rows.map((r) => r.pos), [1, 2, 3, 4, 5]);
});

test("G2B-D-F3: unknown batch mode is an explicit error", async (t) => {
  const { db } = await openAdapter(t);
  await assert.rejects(
    db.batch([{ sql: "SELECT 1", mode: "delete" }]),
    /unknown batch mode: delete/,
  );
});

// ---------- G. missing/invalid changes metadata ----------

test("G2B-D-G1: missing changes metadata fails loud, never as zero", async (t) => {
  // Negative: silent zero would flip quota/revoke/qualify CAS reads.
  const { db } = await openAdapter(t, { dropMeta: true });
  await assert.rejects(
    db.run("DELETE FROM daily_counts"), /missing integer changes metadata/);
  await assert.rejects(
    db.batch([{ sql: "DELETE FROM daily_counts" }]),
    /missing integer changes metadata/);
});

test("G2B-D-G2: non-integer changes metadata is rejected", async (t) => {
  for (const bad of ["1", 1.5, -1, NaN]) {
    const mock = openMockD1Binding({ faults: { badChanges: bad } });
    try {
      const db = openD1(mock.binding);
      await initSchema(db);
      await assert.rejects(
        db.run("DELETE FROM daily_counts"),
        /missing integer changes metadata/, `badChanges=${String(bad)}`);
    } finally {
      mock.close();
    }
  }
});

test("G2B-D-G3: missing results array fails loud", async (t) => {
  const { db } = await openAdapter(t, { dropResults: true });
  await assert.rejects(db.all("SELECT 1"), /missing results array/);
  await assert.rejects(
    db.batch([{ sql: "SELECT 1", mode: "all" }]), /missing results array/);
});

test("G2B-D-G4: short batch results are a count mismatch, not a prefix", async (t) => {
  const { db } = await openAdapter(t, { shortBatch: true });
  await assert.rejects(
    db.batch([
      { sql: "SELECT 1 AS a", mode: "get" },
      { sql: "SELECT 2 AS b", mode: "get" },
    ]),
    /result count does not match/,
  );
});

test("G2B-D-G5: D1 null rows normalize to undefined", async (t) => {
  const { db } = await openAdapter(t);
  assert.equal(await db.get("SELECT 1 WHERE 0"), undefined);
  const [missing] = await db.batch([{ sql: "SELECT 1 WHERE 0", mode: "get" }]);
  assert.equal(missing, undefined);
});

// ---------- H. atomic failure handling ----------

test("G2B-D-H1: a failing statement rolls back the whole batch", async (t) => {
  const { db } = await openAdapter(t);
  await assert.rejects(
    db.batch([
      { sql: "INSERT INTO daily_counts (install_id, day, count) VALUES (?, ?, ?)",
        params: ["install-9", "2026-10-30", 1] },
      { sql: "INSERT INTO nope_missing (x) VALUES (1)" },
    ]),
    /nope_missing/,
  );
  const rows = await db.all("SELECT * FROM daily_counts WHERE install_id = ?",
    "install-9");
  assert.equal(rows.length, 0);
});

test("G2B-D-H2: failed writes leave quota counters exact", async (t) => {
  // Negative: no partial quota claim may survive a batch failure.
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitValid(db, deps, tokens[1], freshRun());
  await assert.rejects(
    db.batch([
      { sql: "UPDATE daily_counts SET count = count + 5 WHERE install_id = ?",
        params: ["install-1"] },
      { sql: "INSERT INTO nope_missing (x) VALUES (1)" },
    ]),
    /nope_missing/,
  );
  const row = await db.get(
    "SELECT count FROM daily_counts WHERE install_id = ?", "install-1");
  assert.equal(row.count, 1);
});

// ---------- I. revocation/eligibility through the adapter ----------

test("G2B-D-I1: full lifecycle publishes then revokes with exact history", async (t) => {
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ids = await submitQuorum(db, deps, tokens);
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "published");
  // Revoke every run through the owner path, then re-check.
  const { handleRequest } = await import("../api.js");
  for (const [i, id] of ids.entries()) {
    const lane = [1, 2, 3, 1, 2][i];
    const res = await handleRequest({
      method: "DELETE",
      path: `/v1/submissions/${id}`,
      query: {},
      headers: { authorization: `Bearer ${tokens[lane]}` },
      bodyText: "",
      db,
      nowMs: NOW,
      deps,
    });
    assert.equal(res.body.eligibility, "REVOKED", id);
  }
  const trail = (await db.all(
    "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    ids[0],
  )).map((r) => r.to_state);
  assert.deepEqual(trail,
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED", "REVOKED"]);
  const after = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(after.body.groups.length, 0);
});

test("G2B-D-I2: revoked rows stay revoked through qualification", async (t) => {
  // Negative: no lost revocation via the adapter's CAS reads.
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const id = await submitValid(db, deps, tokens[1], freshRun());
  const { handleRequest } = await import("../api.js");
  await handleRequest({
    method: "DELETE",
    path: `/v1/submissions/${id}`,
    query: {},
    headers: { authorization: `Bearer ${tokens[1]}` },
    bodyText: "",
    db,
    nowMs: NOW,
    deps,
  });
  await qualify(db, deps);
  const row = await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?", id);
  assert.equal(row.eligibility, "REVOKED");
  const history = await db.all(
    "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  );
  assert.deepEqual(history.map((r) => r.to_state),
    ["SUBMITTED", "REVOKED"]);
});

// ---------- J. quorum and exact cohorts through the adapter ----------

test("G2B-D-J1: 5 runs / 3 installs publishes through the adapter", async (t) => {
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitQuorum(db, deps, tokens);
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 1);
  assert.equal(agg.body.groups[0].status, "published");
  assert.equal(agg.body.groups[0].n, 5);
  assert.equal(agg.body.groups[0].installs, 3);
  assert.ok(agg.body.groups[0].aggregates.mean_gen_tps > 0);
});

test("G2B-D-J2: 5 runs / 2 installs stays below quorum", async (t) => {
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  for (const lane of [1, 2, 1, 2, 1]) {
    await submitValid(db, deps, tokens[lane], freshRun());
  }
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "insufficient-evidence");
  assert.equal(agg.body.groups[0].aggregates, null);
});

test("G2B-D-J3: prompt and build splits stay separate cohorts", async (t) => {
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitQuorum(db, deps, tokens);
  await submitQuorum(db, deps, tokens, {
    workload: { id: "edgefit-prompt-v1", prompt_sha256: PROMPT_B,
      prompt_chars: 100 },
    x_effective_opt: { value: "O0", status: "operator_attested" },
  });
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 2);
  for (const g of agg.body.groups) {
    assert.equal(g.status, "published");
    assert.equal(g.n, 5);
  }
  const hashes = agg.body.groups.map((g) => g.cohort_hash).sort();
  assert.equal(new Set(hashes).size, 2);
  // Each cohort's mean comes from its own 5 members only: identical
  // fixtures => identical means, but distinct cohort identities.
  assert.notEqual(agg.body.groups[0].cohort.prompt_sha,
    agg.body.groups[1].cohort.prompt_sha);
});

test("G2B-D-J4: five incompatible n=1 cohorts never pool a mean", async (t) => {
  // Negative: D3 through the adapter; no cross-cohort statistics.
  const { db } = await openAdapter(t);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const prompts = ["a", "b", "c", "d", "e"].map((c) => c.repeat(64));
  for (let i = 0; i < 5; i++) {
    await submitValid(db, deps, tokens[(i % 3) + 1], {
      ...freshRun(),
      workload: { id: "edgefit-prompt-v1", prompt_sha256: prompts[i],
        prompt_chars: 100 },
    });
  }
  await qualify(db, deps);
  const cands = await get(db, deps, "/v1/evidence/model-candidates", {});
  assert.equal(cands.body.candidates[0].status, "insufficient-evidence");
  assert.equal(cands.body.candidates[0].mean_gen_tps, null);
  assert.equal(cands.body.candidates[0].median_gen_tps, null);
});

// ---------- L. no arbitrary SQL from Claude inputs ----------

test("G2B-D-L1: hostile retrieval inputs never reach SQL", async (t) => {
  const { db, mock } = await openAdapter(t);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitQuorum(db, deps, tokens);
  await qualify(db, deps);
  const before = mock.calls.length;
  const hostile = [
    { model_id: "' OR '1'='1", device_class: "SM-S908U" },
    { model_id: `${MODEL_A}'; DROP TABLE submissions; --`,
      device_class: "SM-S908U" },
    { model_id: MODEL_A, device_class: "SM-S908U' OR '1'='1" },
    { model_id: MODEL_A, device_class: "SM-S908U", limit: "1; DROP TABLE x" },
    { model_id: MODEL_A, device_class: "SM-S908U", limit: 99 },
    { model_id: "zz", device_class: "SM-S908U" },
  ];
  for (const params of hostile) {
    const res = await evidenceRetrieve(db, params, rdeps(deps));
    assert.equal(res.ok, false, JSON.stringify(params));
    assert.equal(res.code, "bad_request");
  }
  assert.equal(mock.calls.length, before,
    "rejected inputs must not prepare statements");
  const count = await db.get("SELECT COUNT(*) AS n FROM submissions");
  assert.equal(count.n, 5);
  const ok = await evidenceRetrieve(db,
    { model_id: MODEL_A, device_class: "SM-S908U" }, rdeps(deps));
  assert.equal(ok.ok, true);
  assert.equal(ok.cites.length, 1);
});

// ---------- N. migration consistency ----------

function migrationSQL() {
  const dir = dirname(fileURLToPath(import.meta.url));
  return readFileSync(join(dir, "..", "migrations", "001_init.sql"), "utf8");
}

test("G2B-D-N1: 001_init.sql applies via exec and serves the API", async (t) => {
  const mock = openMockD1Binding();
  t.after(() => mock.close());
  const db = openD1(mock.binding);
  await db.exec(migrationSQL());
  const tables = (await db.all(
    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
  )).map((r) => r.name);
  for (const name of ["daily_counts", "eligibility_history", "installs",
    "submissions"]) {
    assert.ok(tables.includes(name), `missing table ${name}`);
  }
  // The migrated schema serves a real submission round-trip.
  const deps = makeDeps();
  await seedInstall(db, deps, "install-1", "token-one");
  const id = await submitValid(db, deps, "token-one", freshRun());
  assert.ok(id.startsWith("SUB-"));
});

test("G2B-D-N2: repeat migration application preserves data", async (t) => {
  const mock = openMockD1Binding();
  t.after(() => mock.close());
  const db = openD1(mock.binding);
  await db.exec(migrationSQL());
  const deps = makeDeps();
  await seedInstall(db, deps, "install-1", "token-one");
  await submitValid(db, deps, "token-one", freshRun());
  await db.exec(migrationSQL());
  const count = await db.get("SELECT COUNT(*) AS n FROM submissions");
  assert.equal(count.n, 1);
});
