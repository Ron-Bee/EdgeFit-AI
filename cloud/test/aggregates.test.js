// EF-CLOUD-01A aggregation: quorum, cohort separation,
// statistics, exclusion rules, determinism.
import test from "node:test";
import assert from "node:assert/strict";
import {
  NOW, MODEL_A, MODEL_B, makeDeps, openTestDb, seedInstall, get, del,
  submitValid, qualify, tpsFor,
} from "./helpers.js";

async function setup(nInstalls = 3) {
  const deps = makeDeps();
  const db = await openTestDb();
  for (let i = 1; i <= nInstalls; i++) {
    await seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  }
  return { deps, db };
}

/**
 * A submission plan with unique bytes per row (run ids and
 * collected timestamps differ, as genuine installs would).
 * Runs distribute round-robin across the install list.
 */
function plan(prefix, installs, nRuns, overrides = {}) {
  const rows = [];
  for (let i = 0; i < nRuns; i++) {
    rows.push({
      install: installs[i % installs.length],
      run_id: `BENCH-${prefix}${i}`,
      collected_at_utc: new Date(NOW - (i + 1) * 60000).toISOString(),
      ...overrides,
    });
  }
  return rows;
}

async function publishPlan(db, deps, rows) {
  for (const { install, ...overrides } of rows) {
    await submitValid(db, deps, `test-token-${install}`, overrides);
  }
  await qualify(db, deps);
}

async function aggregates(db, deps, query) {
  return await get(db, deps, "/v1/benchmarks/aggregates", query);
}

test("quorum gates publication: runs and installs", async () => {
  const { deps, db } = await setup();
  await publishPlan(db, deps, plan("T", [1, 2, 3], 4));
  let res = await aggregates(db, deps, { model_sha: MODEL_A });
  assert.equal(res.status, 200);
  assert.equal(res.body.groups.length, 1);
  assert.equal(res.body.groups[0].status, "insufficient-evidence");
  assert.equal(res.body.groups[0].n, 4);
  assert.equal(res.body.groups[0].installs, 3);
  assert.equal(res.body.groups[0].aggregates, null);
  await publishPlan(db, deps, plan("T", [1], 1).map((r) => ({
    ...r, run_id: "BENCH-T4",
  })));
  await qualify(db, deps);
  res = await aggregates(db, deps, { model_sha: MODEL_A });
  assert.equal(res.body.groups[0].status, "published");
  assert.equal(res.body.groups[0].n, 5);
  assert.ok(res.body.groups[0].aggregates.mean_gen_tps > 0);
  db.close();
});

test("many runs from few installs stay thin", async () => {
  const { deps, db } = await setup(2);
  await publishPlan(db, deps, plan("M", [1, 2], 20));
  const res = await aggregates(db, deps, { model_sha: MODEL_A });
  assert.equal(res.body.groups[0].n, 20);
  assert.equal(res.body.groups[0].installs, 2);
  assert.equal(res.body.groups[0].status, "insufficient-evidence");
  db.close();
});

test("cohorts split on config, build, device, and workload", async () => {
  const { deps, db } = await setup();
  await publishPlan(db, deps, plan("C", [1, 2, 3], 5));
  await publishPlan(db, deps, plan("8T", [1, 2, 3], 5, {
    config: { n_ctx: 512, n_threads: 8, n_predict: 64 },
  }));
  await publishPlan(db, deps, plan("SD", [1, 2, 3], 5, {
    x_effective_opt: { value: "O2", status: "self_declared" },
  }));
  await publishPlan(db, deps, plan("PX", [1, 2, 3], 5, {
    device: { model: "Pixel 9" },
  }));
  const res = await aggregates(db, deps, { model_sha: MODEL_A });
  assert.equal(res.body.groups.length, 4);
  assert.ok(res.body.groups.every((g) => g.status === "published"));
  const builds = new Set(res.body.groups.map((g) => g.cohort.build));
  assert.deepEqual([...builds].sort(),
    ["O2:operator_attested", "O2:self_declared"]);
  const one = await aggregates(db, deps,
    { model_sha: MODEL_A, device_class: "Pixel 9" });
  assert.equal(one.body.groups.length, 1);
  assert.equal(one.body.groups[0].cohort.device_class, "Pixel 9");
  db.close();
});

test("statistics reconcile with the stored rows", async () => {
  const { deps, db } = await setup();
  await publishPlan(db, deps, plan("S", [1, 2, 3], 13));
  const res = await aggregates(db, deps, { model_sha: MODEL_A });
  const group = res.body.groups[0];
  assert.equal(group.n, 13);
  assert.equal(group.installs, 3);
  assert.equal(group.aggregates.mean_gen_tps, 16);
  assert.equal(group.aggregates.median_gen_tps, 16);
  assert.equal(group.aggregates.min_gen_tps, 16);
  assert.equal(group.aggregates.max_gen_tps, 16);
  assert.equal(group.aggregates.iqr_gen_tps, 0);
  assert.equal(group.aggregates.mean_abs_pct_error, null);
  db.close();
});

test("IQR withholds below eight samples", async () => {
  const { deps, db } = await setup();
  for (const [i, rate] of [10, 12, 14, 16, 18, 20, 22].entries()) {
    const ms = Math.round((64 * 1000) / rate);
    const install = (i % 3) + 1;
    const exact = tpsFor(64, ms);
    await submitValid(db, deps, `test-token-${install}`, {
      run_id: `BENCH-I${i}`,
      collected_at_utc: new Date(NOW - (i + 1) * 60000).toISOString(),
      measurements: {
        load_ms: 1200, prefill_ms: 1000, prefill_tokens: 23,
        gen_ms: ms, generated_tokens: 64,
        tps_gen: exact, tps_prefill: 23.0, eos_stop: false,
        mem: { pss_before_kb: 100, pss_after_kb: 200,
          alloc_delta_bytes: 4096, method: "test" },
        thermal: { before: "none", mid: "none", after: "none",
          available: true },
      },
    });
  }
  await qualify(db, deps);
  const res = await aggregates(db, deps, { model_sha: MODEL_A });
  assert.equal(res.body.groups[0].status, "published");
  assert.equal(res.body.groups[0].aggregates.iqr_gen_tps, null);
  assert.ok(res.body.groups[0].aggregates.iqr_note.includes("8 samples"));
  db.close();
});

test("revoked and failed rows never enter aggregates", async () => {
  const { deps, db } = await setup();
  const gone = await submitValid(db, deps, "test-token-1", { run_id: "BENCH-X1" });
  await submitValid(db, deps, "test-token-1",
    { run_id: "BENCH-X2", status: "FAILED", error: "boom" });
  await del(db, deps, `/v1/submissions/${gone}`, "test-token-1");
  await publishPlan(db, deps, plan("XK", [1, 2, 3, 1, 2], 5));
  const res = await aggregates(db, deps, { model_sha: MODEL_A });
  assert.equal(res.body.groups[0].n, 5);
  db.close();
});

test("response is deterministic and contract-shaped", async () => {
  const { deps, db } = await setup();
  await publishPlan(db, deps, plan("D", [1, 2], 2));
  const first = await aggregates(db, deps, { model_sha: MODEL_A });
  const second = await aggregates(db, deps, { model_sha: MODEL_A });
  assert.deepEqual(first.body, second.body);
  assert.equal(first.body.api_version, "1");
  assert.equal(first.body.stats_version, "ef-stats-01");
  assert.equal(first.body.methodology, "ef-stats-01");
  assert.equal(first.body.tier, "global_eligible");
  assert.deepEqual(first.body.quorum, { min_runs: 5, min_installs: 3 });
  assert.equal(first.body.truncated, false);
  const serialized = JSON.stringify(first.body);
  assert.ok(!serialized.includes("install-1"));
  assert.ok(!serialized.includes("token-"));
  db.close();
});

test("malformed aggregate filters are 400s", async () => {
  const { deps, db } = await setup();
  assert.equal((await aggregates(db, deps, {})).status, 400);
  assert.equal((await aggregates(db, deps, { model_sha: "xyz" })).status, 400);
  assert.equal((await aggregates(db, deps,
    { model_sha: MODEL_A, build: "nope" })).status, 400);
  assert.equal((await aggregates(db, deps,
    { model_sha: MODEL_A, config_hash: "zz" })).status, 400);
  const empty = await aggregates(db, deps, { model_sha: MODEL_B });
  assert.equal(empty.status, 200);
  assert.deepEqual(empty.body.groups, []);
  db.close();
});
