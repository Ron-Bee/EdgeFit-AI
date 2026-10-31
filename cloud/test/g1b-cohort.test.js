// G1B RED: exact-cohort grouping (D1/D2/D8).
// Asserts ef-stats-01 grouping; FAILS on the G1 baseline where noted.
import test from "node:test";
import assert from "node:assert/strict";
import { evidenceRetrieve } from "../retrieval.js";
import {
  NOW, MODEL_A, PROMPT_SHA, makeDeps, openTestDb, seedInstall, get,
  submitValid, qualify,
} from "./helpers.js";

const PROMPT_B = "b".repeat(64);

function setup() {
  const deps = makeDeps();
  const db = openTestDb();
  for (let i = 1; i <= 3; i++) seedInstall(db, deps, `install-${i}`, `test-token-${i}`);
  return { deps, db };
}

let runCounter = 0;

function pub(db, deps, install, i, extra = {}) {
  runCounter += 1;
  return submitValid(db, deps, `test-token-${install}`, {
    run_id: `BENCH-G1B${runCounter}`,
    collected_at_utc: new Date(NOW - (i + 1) * 60000).toISOString(),
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

test("G1B-R1: different prompt SHAs never share a cohort (aggregates)", () => {
  const { deps, db } = setup();
  [1, 2, 3, 1, 2].forEach((inst, i) => {
    const prompt = i < 3 ? PROMPT_SHA : PROMPT_B;
    pub(db, deps, inst, i, {
      workload: { id: "edgefit-prompt-v1", prompt_sha256: prompt, prompt_chars: 100 },
    });
  });
  qualify(db, deps);
  const res = get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(res.body.groups.length, 2);
  assert.ok(res.body.groups.every((g) => g.status === "insufficient-evidence"));
  assert.ok(res.body.groups.every((g) => g.aggregates === null));
  db.close();
});

test("G1B-R1b: different prompt SHAs never share a cite (retrieval)", () => {
  const { deps, db } = setup();
  [1, 2, 3, 1, 2].forEach((inst, i) => {
    const prompt = i < 3 ? PROMPT_SHA : PROMPT_B;
    pub(db, deps, inst, i + 100, {
      workload: { id: "edgefit-prompt-v1", prompt_sha256: prompt, prompt_chars: 100 },
    });
  });
  qualify(db, deps);
  const res = evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, retrievalDeps(deps));
  assert.equal(res.ok, true);
  assert.deepEqual(res.cites, []);
  db.close();
});

test("G1B-R2: delimiter collisions never merge cohorts", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 5; i++) {
    pub(db, deps, (i % 3) + 1, i, {
      device: { model: "A|X" },
      workload: { id: "Y", prompt_sha256: PROMPT_SHA, prompt_chars: 100 },
    });
    pub(db, deps, ((i + 1) % 3) + 1, i + 100, {
      device: { model: "A" },
      workload: { id: "X|Y", prompt_sha256: PROMPT_SHA, prompt_chars: 100 },
    });
  }
  qualify(db, deps);
  const res = get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(res.body.groups.length, 2);
  assert.ok(res.body.groups.every((g) => g.status === "published" && g.n === 5));
  db.close();
});

test("G1B-R3: cross-build rows never pool (guard)", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 5; i++) {
    pub(db, deps, (i % 3) + 1, i,
      { x_effective_opt: { value: "O2", status: "operator_attested" } });
    pub(db, deps, ((i + 1) % 3) + 1, i + 100,
      { x_effective_opt: { value: "O2", status: "self_declared" } });
  }
  qualify(db, deps);
  const res = get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(res.body.groups.length, 2);
  assert.deepEqual(
    res.body.groups.map((g) => g.cohort.build).sort(),
    ["O2:operator_attested", "O2:self_declared"]);
  db.close();
});

test("G1B-R3b: cross-runtime rows never pool (guard)", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 5; i++) {
    pub(db, deps, (i % 3) + 1, i, { runtime: { backend: "cpu" } });
    pub(db, deps, ((i + 1) % 3) + 1, i + 100, { runtime: { backend: "vulkan" } });
  }
  qualify(db, deps);
  const res = get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(res.body.groups.length, 2);
  db.close();
});

test("G1B-R3c: cross-workload rows never pool (guard)", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 5; i++) {
    pub(db, deps, (i % 3) + 1, i, {
      workload: { id: "edgefit-prompt-v1", prompt_sha256: PROMPT_SHA, prompt_chars: 100 },
    });
    pub(db, deps, ((i + 1) % 3) + 1, i + 100, {
      workload: { id: "edgefit-prompt-v2", prompt_sha256: PROMPT_SHA, prompt_chars: 100 },
    });
  }
  qualify(db, deps);
  const res = get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(res.body.groups.length, 2);
  db.close();
});

test("G1B-R1c: aggregates accept a prompt_sha filter", () => {
  const { deps, db } = setup();
  [1, 2, 3, 1, 2].forEach((inst, i) => {
    const prompt = i < 3 ? PROMPT_SHA : PROMPT_B;
    pub(db, deps, inst, i + 200, {
      workload: { id: "edgefit-prompt-v1", prompt_sha256: prompt, prompt_chars: 100 },
    });
  });
  qualify(db, deps);
  const res = get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A, prompt_sha: PROMPT_SHA });
  assert.equal(res.status, 200);
  assert.equal(res.body.groups.length, 1);
  assert.equal(res.body.groups[0].n, 3);
  const bad = get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A, prompt_sha: "zz" });
  assert.equal(bad.status, 400);
  db.close();
});

test("G1B-R1d: cohort object carries prompt_sha", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 5; i++) pub(db, deps, (i % 3) + 1, i + 300);
  qualify(db, deps);
  const res = get(db, deps, "/v1/benchmarks/aggregates", { model_sha: MODEL_A });
  assert.equal(res.body.groups.length, 1);
  assert.equal(res.body.groups[0].cohort.prompt_sha, PROMPT_SHA);
  db.close();
});
