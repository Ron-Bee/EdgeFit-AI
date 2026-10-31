// G1B RED: candidate retrieval must not pool across cohorts (D3).
import test from "node:test";
import assert from "node:assert/strict";
import {
  NOW, MODEL_A, MODEL_B, makeDeps, openTestDb, seedInstall, get, del,
  submitValid, qualify, tpsFor,
} from "./helpers.js";

let runCounter = 0;

function setup() {
  const deps = makeDeps();
  const db = openTestDb();
  for (let i = 1; i <= 3; i++) seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  return { deps, db };
}

function pub(db, deps, install, extra = {}) {
  runCounter += 1;
  return submitValid(db, deps, `test-token-${install}`, {
    run_id: `BENCH-G1BC${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
    ...extra,
  });
}

function measurementsAt(tps) {
  const ms = Math.round((64 * 1000) / tps);
  return {
    load_ms: 1200, prefill_ms: 1000, prefill_tokens: 23,
    gen_ms: ms, generated_tokens: 64,
    tps_gen: tpsFor(64, ms), tps_prefill: 23.0, eos_stop: false,
    mem: { pss_before_kb: 100, pss_after_kb: 200,
      alloc_delta_bytes: 4096, method: "test" },
    thermal: { before: "none", mid: "none", after: "none", available: true },
  };
}

function candidateOf(db, deps, modelSha) {
  const res = get(db, deps, "/v1/evidence/model-candidates", {});
  assert.equal(res.status, 200);
  return res.body.candidates.find((c) => c.model_sha === modelSha);
}

test("G1B-C1: five incompatible n=1 cohorts stay insufficient-evidence", () => {
  const { deps, db } = setup();
  [128, 256, 512, 1024, 2048].forEach((n_ctx, i) => {
    pub(db, deps, (i % 3) + 1, { config: { n_ctx, n_threads: 4, n_predict: 64 } });
  });
  qualify(db, deps);
  const c = candidateOf(db, deps, MODEL_A);
  assert.equal(c.status, "insufficient-evidence");
  assert.equal(c.median_gen_tps, null);
  assert.equal(c.mean_gen_tps, null);
  db.close();
});

test("G1B-C2: mixed thin+quorum cohorts publish quorum stats only", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 5; i++) {
    pub(db, deps, (i % 3) + 1, {
      config: { n_ctx: 512, n_threads: 4, n_predict: 64 },
      measurements: measurementsAt(10),
    });
  }
  for (const n_ctx of [256, 1024]) {
    pub(db, deps, 1, {
      config: { n_ctx, n_threads: 4, n_predict: 64 },
      measurements: measurementsAt(100),
    });
  }
  qualify(db, deps);
  const c = candidateOf(db, deps, MODEL_A);
  assert.equal(c.status, "published");
  assert.equal(c.mean_gen_tps, 10);
  assert.equal(c.median_gen_tps, 10);
  assert.equal(c.cohort.config.n_ctx, 512);
  assert.equal(c.matching_runs, 7);
  db.close();
});

test("G1B-C3: single quorum cohort keeps its shape (guard)", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 5; i++) pub(db, deps, (i % 3) + 1);
  qualify(db, deps);
  const c = candidateOf(db, deps, MODEL_A);
  assert.equal(c.status, "published");
  assert.equal(c.median_gen_tps, 16);
  assert.equal(c.matching_runs, 5);
  assert.equal(c.source_provenance, "observed_submissions");
  assert.equal(c.license, "unknown");
  db.close();
});

test("G1B-C4: thin model labels counts without means (guard)", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 2; i++) {
    pub(db, deps, 1, { model: { sha256: MODEL_B } });
  }
  qualify(db, deps);
  const c = candidateOf(db, deps, MODEL_B);
  assert.equal(c.status, "insufficient-evidence");
  assert.equal(c.median_gen_tps, null);
  assert.equal(c.mean_gen_tps, null);
  assert.equal(c.matching_runs, 2);
  db.close();
});

test("G1B-C5: revoked rows leave candidate evidence (guard)", () => {
  const { deps, db } = setup();
  const ids = [];
  for (let i = 0; i < 5; i++) ids.push(pub(db, deps, (i % 3) + 1));
  qualify(db, deps);
  assert.equal(candidateOf(db, deps, MODEL_A).status, "published");
  del(db, deps, `/v1/submissions/${ids[0]}`, "test-token-1");
  const c = candidateOf(db, deps, MODEL_A);
  assert.equal(c.status, "insufficient-evidence");
  assert.equal(c.matching_runs, 4);
  db.close();
});

test("G1B-C6: candidates carry no run-level details (guard)", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 5; i++) pub(db, deps, (i % 3) + 1);
  qualify(db, deps);
  const res = get(db, deps, "/v1/evidence/model-candidates", {});
  const serialized = JSON.stringify(res.body);
  assert.ok(!serialized.includes("install-"));
  assert.ok(!serialized.includes("token-"));
  assert.ok(!serialized.includes("BENCH-"));
  db.close();
});
