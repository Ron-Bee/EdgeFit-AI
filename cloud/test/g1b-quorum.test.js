// G1B: exact quorum boundaries + public/internal parity (guards).
import test from "node:test";
import assert from "node:assert/strict";
import { evidenceRetrieve } from "../retrieval.js";
import {
  NOW, MODEL_A, makeDeps, openTestDb, seedInstall, get,
  submitValid, qualify,
} from "./helpers.js";

let runCounter = 0;

async function setup(nInstalls = 3) {
  const deps = makeDeps();
  const db = await openTestDb();
  for (let i = 1; i <= nInstalls; i++) {
    await seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  }
  return { deps, db };
}

async function pub(db, deps, install, extra = {}) {
  runCounter += 1;
  return await submitValid(db, deps, `test-token-${install}`, {
    run_id: `BENCH-G1BQ${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
    ...extra,
  });
}

function retrievalDeps(deps) {
  return {
    sha256hex: deps.sha256hex,
    installSalt: deps.installSalt,
    deviceClasses: deps.deviceClasses,
    quorumRuns: deps.quorumRuns,
    quorumInstalls: deps.quorumInstalls,
  };
}

test("G1B-Q1: exactly 5 runs / 3 installs publishes", async () => {
  const { deps, db } = await setup();
  for (const inst of [1, 2, 3, 1, 2]) { await pub(db, deps, inst); }
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 1);
  assert.equal(agg.body.groups[0].status, "published");
  assert.equal(agg.body.groups[0].n, 5);
  assert.equal(agg.body.groups[0].installs, 3);
  const ret = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, retrievalDeps(deps));
  assert.equal(ret.cites.length, 1);
  assert.equal(ret.cites[0].n, 5);
  assert.equal(ret.cites[0].installs, 3);
  db.close();
});

test("G1B-Q2: 5 runs / 2 installs stays thin everywhere", async () => {
  const { deps, db } = await setup(2);
  for (const inst of [1, 2, 1, 2, 1]) { await pub(db, deps, inst); }
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 1);
  assert.equal(agg.body.groups[0].status, "insufficient-evidence");
  assert.equal(agg.body.groups[0].aggregates, null);
  const ret = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, retrievalDeps(deps));
  assert.equal(ret.ok, true);
  assert.deepEqual(ret.cites, []);
  db.close();
});

test("G1B-Q3: public and internal retrieval partition identically", async () => {
  const { deps, db } = await setup();
  for (let i = 0; i < 5; i++) {
    await pub(db, deps, (i % 3) + 1, { config: { n_ctx: 512, n_threads: 4, n_predict: 64 } });
    await pub(db, deps, ((i + 1) % 3) + 1,
      { config: { n_ctx: 256, n_threads: 4, n_predict: 64 } });
  }
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  const hashes = agg.body.groups.map((g) => g.cohort_hash).sort();
  assert.equal(hashes.length, 2);
  const ret = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, retrievalDeps(deps));
  assert.deepEqual(ret.cites.map((c) => c.cohort_hash).sort(), hashes);
  db.close();
});
