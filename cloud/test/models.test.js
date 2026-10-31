// EF-CLOUD-01A public models and model candidates:
// observed-artifact derivation, quorum honesty, no ranking.
import test from "node:test";
import assert from "node:assert/strict";
import {
  NOW, MODEL_A, MODEL_B, makeDeps, openTestDb, seedInstall, get,
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

async function publishMany(db, deps, modelSha, installs, n, prefix,
  extra = {}) {
  for (let i = 0; i < n; i++) {
    await submitValid(db, deps, `test-token-${installs[i % installs.length]}`, {
      run_id: `BENCH-${prefix}${i}`,
      collected_at_utc: new Date(NOW - (i + 1) * 60000).toISOString(),
      model: { sha256: modelSha },
      ...extra,
    });
  }
  await qualify(db, deps);
}

test("models lists published artifacts with honest counts", async () => {
  const { deps, db } = await setup();
  await publishMany(db, deps, MODEL_A, [1, 2, 3], 5, "MA");
  await publishMany(db, deps, MODEL_B, [1], 2, "MB");
  const res = await get(db, deps, "/v1/models");
  assert.equal(res.status, 200);
  assert.equal(res.body.artifacts.length, 2);
  const a = res.body.artifacts.find((r) => r.model_sha === MODEL_A);
  assert.equal(a.published_runs, 5);
  assert.equal(a.installs, 3);
  assert.ok(a.latest_collected_at);
  const b = res.body.artifacts.find((r) => r.model_sha === MODEL_B);
  assert.equal(b.published_runs, 2);
  assert.equal(b.installs, 1);
  const serialized = JSON.stringify(res.body);
  assert.ok(!serialized.includes("install-"));
  db.close();
});

test("candidates filter, cap, and label thin evidence", async () => {
  const { deps, db } = await setup();
  await publishMany(db, deps, MODEL_A, [1, 2, 3], 5, "CA");
  await publishMany(db, deps, MODEL_B, [1], 2, "CB");
  const res = await get(db, deps, "/v1/evidence/model-candidates",
    { device_class: "SM-S908U" });
  assert.equal(res.status, 200);
  assert.equal(res.body.candidates.length, 2);
  // Deterministic model_sha order, explicitly not a ranking.
  assert.ok(res.body.candidates[0].model_sha <
    res.body.candidates[1].model_sha);
  assert.ok(res.body.note.includes("no performance ranking"));
  const quorum = res.body.candidates.find((c) => c.model_sha === MODEL_A);
  assert.equal(quorum.status, "published");
  assert.equal(quorum.median_gen_tps, 16);
  assert.equal(quorum.matching_runs, 5);
  assert.equal(quorum.source_provenance, "observed_submissions");
  assert.equal(quorum.license, "unknown");
  const thin = res.body.candidates.find((c) => c.model_sha === MODEL_B);
  assert.equal(thin.status, "insufficient-evidence");
  assert.equal(thin.median_gen_tps, null);
  const capped = await get(db, deps, "/v1/evidence/model-candidates",
    { limit: "1" });
  assert.equal(capped.body.candidates.length, 1);
  db.close();
});

test("candidate query bounds are enforced", async () => {
  const { deps, db } = await setup();
  assert.equal((await get(db, deps, "/v1/evidence/model-candidates",
    { limit: "6" })).status, 400);
  assert.equal((await get(db, deps, "/v1/evidence/model-candidates",
    { limit: "0" })).status, 400);
  assert.equal((await get(db, deps, "/v1/evidence/model-candidates",
    { device_class: "" })).status, 400);
  assert.equal((await get(db, deps, "/v1/evidence/model-candidates",
    { intended_n_ctx: "huge" })).status, 400);
  const empty = await get(db, deps, "/v1/evidence/model-candidates",
    { device_class: "No Such Phone" });
  assert.equal(empty.status, 200);
  assert.deepEqual(empty.body.candidates, []);
  db.close();
});
