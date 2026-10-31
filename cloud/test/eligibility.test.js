// EF-CLOUD-01A eligibility: rule-driven transitions,
// anomaly holds, revocation terminality.
import test from "node:test";
import assert from "node:assert/strict";
import {
  NOW, makeDeps, openTestDb, seedInstall, makeResult, makeEnvelope,
  post, del, submitValid, qualify, tpsFor,
} from "./helpers.js";

async function setup() {
  const deps = makeDeps();
  const db = await openTestDb();
  await seedInstall(db, deps, "install-1", "token-one");
  await seedInstall(db, deps, "install-2", "token-two");
  return { deps, db };
}

async function eligibility(db, id) {
  return (await db.get("SELECT eligibility FROM submissions WHERE submission_id = ?",
    id)).eligibility;
}

test("clean submission walks to PUBLISHED with full history", async () => {
  const { deps, db } = await setup();
  const id = await submitValid(db, deps, "token-one", { run_id: "BENCH-E1" });
  const report = await qualify(db, deps);
  assert.equal(report.held.length, 0);
  assert.equal(await eligibility(db, id), "PUBLISHED");
  const trail = (await db.all(
    "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  )).map((r) => r.to_state);
  assert.deepEqual(trail,
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  const row = await db.get(
    "SELECT published_at FROM submissions WHERE submission_id = ?", id);
  assert.ok(row.published_at);
  db.close();
});

test("failed attempts validate but never feed aggregates", async () => {
  const { deps, db } = await setup();
  const id = await submitValid(db, deps, "token-one",
    { run_id: "BENCH-F1", status: "FAILED", error: "native init failed" });
  await qualify(db, deps);
  assert.equal(await eligibility(db, id), "PUBLISHED");
  db.close();
});

test("implausible throughput holds at VALIDATED", async () => {
  const { deps, db } = await setup();
  const id = await submitValid(db, deps, "token-one", {
    run_id: "BENCH-H1",
    measurements: {
      load_ms: 1200, prefill_ms: 1000, prefill_tokens: 23,
      gen_ms: 4000, generated_tokens: 64,
      tps_gen: 500000, tps_prefill: 23.0, eos_stop: false,
      mem: { pss_before_kb: 100, pss_after_kb: 200,
        alloc_delta_bytes: 4096, method: "test" },
      thermal: { before: "none", mid: "none", after: "none",
        available: true },
    },
  });
  const report = await qualify(db, deps);
  assert.equal(await eligibility(db, id), "VALIDATED");
  assert.ok(report.held.some((h) =>
    h.submission_id === id && h.reason.includes("implausible")));
  db.close();
});

test("zero-token COMPLETED and inconsistent tps both hold", async () => {
  const { deps, db } = await setup();
  const zero = await submitValid(db, deps, "token-one", {
    run_id: "BENCH-H2", generated_tokens: 0, gen_ms: 4000,
  });
  // tps_gen recomputed for 0 tokens is 0: structurally valid,
  // but a COMPLETED measurement with no tokens cannot qualify.
  const skew = await submitValid(db, deps, "token-one", {
    run_id: "BENCH-H3",
    measurements: {
      load_ms: 1200, prefill_ms: 1000, prefill_tokens: 23,
      gen_ms: 4000, generated_tokens: 64,
      tps_gen: 99.9, tps_prefill: 23.0, eos_stop: false,
      mem: { pss_before_kb: 100, pss_after_kb: 200,
        alloc_delta_bytes: 4096, method: "test" },
      thermal: { before: "none", mid: "none", after: "none",
        available: true },
    },
  });
  const report = await qualify(db, deps);
  assert.equal(await eligibility(db, zero), "VALIDATED");
  assert.equal(await eligibility(db, skew), "VALIDATED");
  assert.ok(report.held.some((h) => h.reason.includes("lacks positive")));
  assert.ok(report.held.some((h) => h.reason.includes("inconsistent")));
  db.close();
});

test("run_id collision holds the later submission", async () => {
  const { deps, db } = await setup();
  const first = await submitValid(db, deps, "token-one", { run_id: "BENCH-C1" });
  const envelope = makeEnvelope(makeResult({
    run_id: "BENCH-C1", gen_ms: 3000,
    measurements: {
      load_ms: 1200, prefill_ms: 1000, prefill_tokens: 23,
      gen_ms: 3000, generated_tokens: 64,
      tps_gen: tpsFor(64, 3000), tps_prefill: 23.0, eos_stop: false,
      mem: { pss_before_kb: 100, pss_after_kb: 200,
        alloc_delta_bytes: 4096, method: "test" },
      thermal: { before: "none", mid: "none", after: "none",
        available: true },
    },
  }));
  const res = await post(db, deps, "token-two", envelope);
  assert.equal(res.status, 201);
  await qualify(db, deps);
  assert.equal(await eligibility(db, first), "PUBLISHED");
  assert.equal(await eligibility(db, res.body.submission_id), "VALIDATED");
  db.close();
});

test("revoked rows never promote and stay revoked", async () => {
  const { deps, db } = await setup();
  const id = await submitValid(db, deps, "token-one", { run_id: "BENCH-R1" });
  await del(db, deps, `/v1/submissions/${id}`, "token-one");
  const report = await qualify(db, deps);
  assert.equal(await eligibility(db, id), "REVOKED");
  assert.ok(report.promoted.every((p) => p.submission_id !== id));
  db.close();
});

test("holds record no phantom history rows", async () => {
  const { deps, db } = await setup();
  await submitValid(db, deps, "token-one", {
    run_id: "BENCH-H4",
    measurements: {
      load_ms: 1200, prefill_ms: 1000, prefill_tokens: 23,
      gen_ms: 4000, generated_tokens: 64,
      tps_gen: 500000, tps_prefill: 23.0, eos_stop: false,
      mem: { pss_before_kb: 100, pss_after_kb: 200,
        alloc_delta_bytes: 4096, method: "test" },
      thermal: { before: "none", mid: "none", after: "none",
        available: true },
    },
  });
  await qualify(db, deps);
  await qualify(db, deps);
  const n = (await db.get("SELECT COUNT(*) AS n FROM eligibility_history")).n;
  // Received + one SUBMITTED->VALIDATED promotion only.
  assert.equal(n, 2);
  db.close();
});

test("velocity floods hold for review", async () => {
  // The daily quota caps each UTC day, but 20 rows late one
  // day plus 20 early the next is 40 inside a sliding 24h
  // window: the velocity rule holds the second batch.
  const { deps, db } = await setup();
  const dayTwo = NOW + 12 * 60 * 60 * 1000;
  for (let i = 0; i < 20; i++) {
    await submitValid(db, deps, "token-one", { run_id: `BENCH-VA${i}` });
  }
  await qualify(db, deps, NOW);
  for (let i = 0; i < 20; i++) {
    await submitValid(db, deps, "token-one", { run_id: `BENCH-VB${i}` }, {},
      dayTwo);
  }
  const report = await qualify(db, deps, dayTwo);
  assert.ok(report.held.some((h) => h.reason.includes("velocity")),
    JSON.stringify(report.held));
  db.close();
});
