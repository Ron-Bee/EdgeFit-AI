// G1B independent challenge: adversarial cross-surface consistency.
// Designed after the corrections from a challenger's mindset: identical
// partitions on both retrieval surfaces, revocation everywhere,
// byte determinism, injection boundaries, auth boundaries, identifier
// stability, catalog/evidence separation, quota windows.
import test from "node:test";
import assert from "node:assert/strict";
import { evidenceRetrieve } from "../retrieval.js";
import { cohortKey } from "../cohort.js";
import {
  NOW, MODEL_A, MODEL_B, PROMPT_SHA, makeDeps, openTestDb, seedInstall,
  get, del, submitValid, qualify, tpsFor, post, makeEnvelope, makeResult,
} from "./helpers.js";

const PROMPT_B = "b".repeat(64);
let runCounter = 0;

async function setup() {
  const deps = makeDeps();
  const db = await openTestDb();
  for (let i = 1; i <= 3; i++) await seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  return { deps, db };
}

async function pub(db, deps, install, extra = {}) {
  runCounter += 1;
  return await submitValid(db, deps, `test-token-${install}`, {
    run_id: `BENCH-G1BX${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
    ...extra,
  });
}

function at(tps) {
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

function rdeps(deps) {
  return {
    sha256hex: deps.sha256hex,
    installSalt: deps.installSalt,
    deviceClasses: deps.deviceClasses,
    quorumRuns: deps.quorumRuns,
    quorumInstalls: deps.quorumInstalls,
  };
}

test("G1B-X1: both surfaces partition varied shapes identically", async () => {
  const { deps, db } = await setup();
  // Six distinct cohort shapes across two pinned devices: prompt,
  // build status, workload, backend, and config all vary.
  const shapes = [
    { device: "SM-S908U", prompt: PROMPT_SHA, runs: 5, tps: 10 },
    { device: "SM-S908U", prompt: PROMPT_B, runs: 5, tps: 20 },
    { device: "SM-S908U", prompt: PROMPT_SHA, runs: 2, tps: 99,
      build: { value: "O2", status: "self_declared" } },
    { device: "Pixel 9", prompt: PROMPT_SHA, runs: 6, tps: 30,
      workload: "edgefit-prompt-v2" },
    { device: "Pixel 9", prompt: PROMPT_SHA, runs: 5, tps: 40,
      backend: "vulkan", n_ctx: 256 },
    { device: "Pixel 9", prompt: PROMPT_B, runs: 1, tps: 99 },
  ];
  for (const s of shapes) {
    for (let i = 0; i < s.runs; i++) {
      await pub(db, deps, (i % 3) + 1, {
        device: { model: s.device },
        workload: { id: s.workload ?? "edgefit-prompt-v1",
          prompt_sha256: s.prompt, prompt_chars: 100 },
        runtime: { backend: s.backend ?? "cpu" },
        config: { n_ctx: s.n_ctx ?? 512, n_threads: 4, n_predict: 64 },
        measurements: at(s.tps),
        ...(s.build ? { x_effective_opt: s.build } : {}),
      });
    }
  }
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 6);
  for (const device of ["SM-S908U", "Pixel 9"]) {
    const publishedHere = agg.body.groups
      .filter((g) => g.cohort.device_class === device && g.status === "published");
    const thinHere = agg.body.groups
      .filter((g) => g.cohort.device_class === device && g.status !== "published");
    assert.equal(thinHere.length, 1);
    assert.equal(thinHere[0].aggregates, null);
    const ret = await evidenceRetrieve(db,
      { device_class: device, model_id: MODEL_A }, rdeps(deps));
    assert.equal(ret.ok, true);
    assert.deepEqual(
      ret.cites.map((c) => c.cohort_hash).sort(),
      publishedHere.map((g) => g.cohort_hash).sort(),
    );
    for (const cite of ret.cites) {
      const group = publishedHere.find((g) => g.cohort_hash === cite.cohort_hash);
      assert.equal(cite.median_gen_tps, group.aggregates.median_gen_tps);
      assert.equal(cite.mean_gen_tps, group.aggregates.mean_gen_tps);
      assert.equal(cite.n, group.n);
    }
  }
  db.close();
});

test("G1B-X2: revocation vanishes from every retrieval surface", async () => {
  const { deps, db } = await setup();
  const ids = [];
  for (const inst of [1, 2, 3, 1, 2]) { ids.push(await pub(db, deps, inst)); }
  await qualify(db, deps);
  assert.equal((await candidateOf(db, deps)).status, "published");
  await del(db, deps, `/v1/submissions/${ids[0]}`, "test-token-1");
  await del(db, deps, `/v1/submissions/${ids[1]}`, "test-token-2");
  const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "insufficient-evidence");
  assert.equal(agg.body.groups[0].n, 3);
  const ret = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, rdeps(deps));
  assert.deepEqual(ret.cites, []);
  const c = await candidateOf(db, deps);
  assert.equal(c.status, "insufficient-evidence");
  assert.equal(c.matching_runs, 3);
  const models = await get(db, deps, "/v1/models");
  assert.equal(models.body.artifacts[0].published_runs, 3);
  db.close();

  async function candidateOf(dbHandle, depsHandle) {
    const res = await get(dbHandle, depsHandle, "/v1/evidence/model-candidates", {});
    return res.body.candidates.find((x) => x.model_sha === MODEL_A);
  }
});

test("G1B-X3: identical fixtures yield byte-identical evidence", async () => {
  const build = async () => {
    const deps = makeDeps();
    const db = await openTestDb();
    for (let i = 1; i <= 3; i++) await seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
    for (const [i, inst] of [1, 2, 3, 1, 2].entries()) {
      await submitValid(db, deps, `test-token-${inst}`, {
        run_id: `BENCH-DET${i}`,
        collected_at_utc: new Date(NOW - (i + 1) * 60000).toISOString(),
      });
    }
    await qualify(db, deps);
    const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
    const ret = await evidenceRetrieve(db,
      { device_class: "SM-S908U", model_id: MODEL_A }, rdeps(deps));
    db.close();
    return { agg: JSON.stringify(agg.body), ret: JSON.stringify(ret) };
  };
  const first = await build();
  const second = await build();
  assert.equal(first.agg, second.agg);
  assert.equal(first.ret, second.ret);
});

test("G1B-X4: hostile strings stay inert and never merge cohorts", async () => {
  const { deps, db } = await setup();
  const evils = [
    "x'; DROP TABLE submissions; --",
    "a|b|c|d",
    '"quoted and | piped"',
    "back\\slash",
    "trailing-space ",
    "x".repeat(64),
  ];
  for (let i = 0; i < 5; i++) await pub(db, deps, (i % 3) + 1); // control cohort
  for (const [k, evil] of evils.entries()) {
    await pub(db, deps, (k % 3) + 1, {
      device: { model: `Phone ${k} ${evil}`.slice(0, 64) },
      workload: { id: `w-${k}`, prompt_sha256: PROMPT_SHA, prompt_chars: 100 },
      model: { display_name: `evil µ± ${evil}`.slice(0, 256), quantization: `Q${k}|${evil}`.slice(0, 64) },
      runtime: { impl: `r-${k}`, backend: "cpu" },
      config: { sampler: `s${k}` },
    });
  }
  await qualify(db, deps);
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM submissions")).n, 5 + evils.length);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  const control = agg.body.groups.find((g) => g.cohort.device_class === "SM-S908U");
  assert.equal(control.n, 5);
  assert.equal(control.status, "published");
  assert.equal(agg.body.groups.length, 1 + evils.length);
  db.close();
});

test("G1B-X5: hostile ownership attempts change nothing", async () => {
  const { deps, db } = await setup();
  await seedInstall(db, deps, "install-9", "token-nine");
  const ids = [];
  for (const inst of [1, 2, 3, 1, 2]) { ids.push(await pub(db, deps, inst)); }
  await qualify(db, deps);
  assert.equal((await del(db, deps, `/v1/submissions/${ids[0]}`, "token-nine")).status, 404);
  assert.equal(
    (await get(db, deps, `/v1/submissions/${ids[0]}`, {}, "token-nine")).status, 404);
  assert.equal((await get(db, deps, `/v1/submissions/${ids[0]}`)).status, 401);
  assert.equal(
    (await del(db, deps, `/v1/submissions/${ids[0]}`, "token-one-wrong")).status, 401);
  assert.equal(
    (await db.get("SELECT eligibility FROM submissions WHERE submission_id = ?",
      ids[0])).eligibility,
    "PUBLISHED",
  );
  const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "published");
  assert.equal(agg.body.groups[0].n, 5);
  db.close();
});

test("G1B-X6: v2 cohort identity is stable and unambiguous", async () => {
  const { deps, db } = await setup();
  for (let i = 0; i < 5; i++) await pub(db, deps, (i % 3) + 1);
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  const hash = agg.body.groups[0].cohort_hash;
  assert.match(hash, /^[0-9a-f]{64}$/);
  // Same cohort rebuilt in a fresh database hashes identically.
  const deps2 = makeDeps();
  const db2 = await openTestDb();
  for (let i = 1; i <= 3; i++) await seedInstall(db2, deps2, `install-${i}`, `test-token-${i}`);
  for (let i = 0; i < 5; i++) await pub(db2, deps2, (i % 3) + 1);
  await qualify(db2, deps2);
  const agg2 = await get(db2, deps2, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(agg2.body.groups[0].cohort_hash, hash);
  // The key is version-tagged and differs from the legacy ambiguous join.
  const row = await db.get("SELECT * FROM submissions LIMIT 1");
  assert.ok(cohortKey(row).startsWith("v2:"));
  const legacy = deps.sha256hex([
    row.model_sha, row.quantization_label, row.device_class,
    row.workload_id, row.runtime_impl, row.runtime_backend,
    row.build_key, row.config_hash,
  ].join("|"));
  assert.notEqual(hash, legacy);
  db.close();
  db2.close();
});

test("G1B-X7: thin models expose counts but no means anywhere", async () => {
  const { deps, db } = await setup();
  for (let i = 0; i < 2; i++) await pub(db, deps, 1, { model: { sha256: MODEL_B } });
  await qualify(db, deps);
  const models = await get(db, deps, "/v1/models");
  const artifact = models.body.artifacts.find((x) => x.model_sha === MODEL_B);
  assert.equal(artifact.published_runs, 2);
  assert.ok(!("mean_gen_tps" in artifact));
  assert.ok(!("median_gen_tps" in artifact));
  const res = await get(db, deps, "/v1/evidence/model-candidates", {});
  const c = res.body.candidates.find((x) => x.model_sha === MODEL_B);
  assert.equal(c.status, "insufficient-evidence");
  assert.equal(c.mean_gen_tps, null);
  assert.equal(c.median_gen_tps, null);
  db.close();
});

test("G1B-X8: quota windows reset at the UTC boundary", async () => {
  const deps = makeDeps({ quotaPerDay: 2 });
  const db = await openTestDb();
  await seedInstall(db, deps, "install-1", "token-one");
  await post(db, deps, "token-one", makeEnvelope(makeResult({
    run_id: "BENCH-W1", collected_at_utc: "2026-10-30T10:00:00Z",
  })));
  await post(db, deps, "token-one", makeEnvelope(makeResult({
    run_id: "BENCH-W2", collected_at_utc: "2026-10-30T11:00:00Z",
  })));
  const denied = await post(db, deps, "token-one", makeEnvelope(makeResult({
    run_id: "BENCH-W3", collected_at_utc: "2026-10-30T11:30:00Z",
  })));
  assert.equal(denied.status, 429);
  const nextDay = await post(db, deps, "token-one", makeEnvelope(makeResult({
    run_id: "BENCH-W4", collected_at_utc: "2026-10-30T11:45:00Z",
  })), Date.parse("2026-10-31T01:00:00Z"));
  assert.equal(nextDay.status, 201);
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM daily_counts")).n, 2);
  db.close();
});
