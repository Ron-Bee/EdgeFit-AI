// EF-CLOUD-01A bounded Muse retrieval: allowlisted,
// quorum-passing cohorts only, cites without raw rows.
import test from "node:test";
import assert from "node:assert/strict";
import { evidenceRetrieve } from "../retrieval.js";
import {
  NOW, MODEL_A, MODEL_B, makeDeps, openTestDb, seedInstall,
  submitValid, qualify,
} from "./helpers.js";

async function setup() {
  const deps = makeDeps();
  const db = await openTestDb();
  for (let i = 1; i <= 3; i++) {
    await seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  }
  return { deps, db };
}

async function publish(db, deps, prefix, installs, n, extra = {}) {
  for (let i = 0; i < n; i++) {
    await submitValid(db, deps, `test-token-${installs[i % installs.length]}`, {
      run_id: `BENCH-${prefix}${i}`,
      collected_at_utc: new Date(NOW - (i + 1) * 60000).toISOString(),
      ...extra,
    });
  }
  await qualify(db, deps);
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

test("retrieval cites quorum-passing cohorts only", async () => {
  const { deps, db } = await setup();
  await publish(db, deps, "RA", [1, 2, 3], 5);
  await publish(db, deps, "RB", [1], 2, {
    config: { n_ctx: 256, n_threads: 4, n_predict: 64 },
  });
  const res = await evidenceRetrieve(db, {
    device_class: "SM-S908U", model_id: MODEL_A,
  }, retrievalDeps(deps));
  assert.equal(res.ok, true);
  assert.equal(res.cites.length, 1);
  const cite = res.cites[0];
  assert.equal(cite.model_sha, MODEL_A);
  assert.equal(cite.n, 5);
  assert.equal(cite.installs, 3);
  assert.equal(cite.median_gen_tps, 16);
  assert.equal(cite.cohort_hash.length, 64);
  assert.deepEqual(Object.keys(cite.config).sort(),
    ["n_ctx", "n_predict", "n_threads", "sampler"]);
  const serialized = JSON.stringify(res);
  assert.ok(!serialized.includes("install-"));
  assert.ok(!serialized.includes("token-"));
  assert.ok(!serialized.includes("BENCH-"));
  db.close();
});

test("retrieval enforces its allowlist and limits", async () => {
  const { deps, db } = await setup();
  await publish(db, deps, "RL", [1, 2, 3], 5);
  const rdeps = retrievalDeps(deps);
  assert.equal((await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: "xyz" }, rdeps)).ok, false);
  assert.equal((await evidenceRetrieve(db,
    { device_class: "Unlisted Phone", model_id: MODEL_A }, rdeps)).code,
    "bad_request");
  assert.equal((await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A, limit: 9 },
    rdeps)).code, "bad_request");
  assert.equal((await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A, limit: 0 },
    rdeps)).code, "bad_request");
  // Well-formed but empty: success with no cites.
  const empty = await evidenceRetrieve(db,
    { device_class: "Pixel 9", model_id: MODEL_A }, rdeps);
  assert.equal(empty.ok, true);
  assert.deepEqual(empty.cites, []);
  const other = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_B }, rdeps);
  assert.equal(other.ok, true);
  assert.deepEqual(other.cites, []);
  db.close();
});

test("retrieval caps cites deterministically", async () => {
  const { deps, db } = await setup();
  await publish(db, deps, "R1", [1, 2, 3], 5,
    { config: { n_ctx: 512, n_threads: 4, n_predict: 64 } });
  await publish(db, deps, "R2", [1, 2, 3], 5,
    { config: { n_ctx: 256, n_threads: 4, n_predict: 64 } });
  await publish(db, deps, "R3", [1, 2, 3], 5,
    { config: { n_ctx: 128, n_threads: 4, n_predict: 64 } });
  await publish(db, deps, "R4", [1, 2, 3], 5,
    { config: { n_ctx: 1024, n_threads: 4, n_predict: 64 } });
  const rdeps = retrievalDeps(deps);
  const full = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, rdeps);
  assert.equal(full.cites.length, 3);
  const hashes = full.cites.map((c) => c.cohort_hash);
  assert.deepEqual(hashes, [...hashes].sort());
  const one = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A, limit: 1 }, rdeps);
  assert.equal(one.cites.length, 1);
  assert.equal(one.cites[0].cohort_hash, hashes[0]);
  db.close();
});
