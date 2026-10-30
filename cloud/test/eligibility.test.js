// EF-CLOUD-01A eligibility: rule-driven transitions,
// anomaly holds, revocation terminality.
import test from "node:test";
import assert from "node:assert/strict";
import {
  NOW, makeDeps, openTestDb, seedInstall, makeResult, makeEnvelope,
  post, del, submitValid, qualify, tpsFor,
} from "./helpers.js";

function setup() {
  const deps = makeDeps();
  const db = openTestDb();
  seedInstall(db, deps, "install-1", "token-one");
  seedInstall(db, deps, "install-2", "token-two");
  return { deps, db };
}

function eligibility(db, id) {
  return db.get("SELECT eligibility FROM submissions WHERE submission_id = ?",
    id).eligibility;
}

test("clean submission walks to PUBLISHED with full history", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one", { run_id: "BENCH-E1" });
  const report = qualify(db, deps);
  assert.equal(report.held.length, 0);
  assert.equal(eligibility(db, id), "PUBLISHED");
  const trail = db.all(
    "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  ).map((r) => r.to_state);
  assert.deepEqual(trail,
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  const row = db.get(
    "SELECT published_at FROM submissions WHERE submission_id = ?", id);
  assert.ok(row.published_at);
  db.close();
});

test("failed attempts validate but never feed aggregates", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one",
    { run_id: "BENCH-F1", status: "FAILED", error: "native init failed" });
  qualify(db, deps);
  assert.equal(eligibility(db, id), "PUBLISHED");
  db.close();
});

test("implausible throughput holds at VALIDATED", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one", {
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
  const report = qualify(db, deps);
  assert.equal(eligibility(db, id), "VALIDATED");
  assert.ok(report.held.some((h) =>
    h.submission_id === id && h.reason.includes("implausible")));
  db.close();
});

test("zero-token COMPLETED and inconsistent tps both hold", () => {
  const { deps, db } = setup();
  const zero = submitValid(db, deps, "token-one", {
    run_id: "BENCH-H2", generated_tokens: 0, gen_ms: 4000,
  });
  // tps_gen recomputed for 0 tokens is 0: structurally valid,
  // but a COMPLETED measurement with no tokens cannot qualify.
  const skew = submitValid(db, deps, "token-one", {
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
  const report = qualify(db, deps);
  assert.equal(eligibility(db, zero), "VALIDATED");
  assert.equal(eligibility(db, skew), "VALIDATED");
  assert.ok(report.held.some((h) => h.reason.includes("lacks positive")));
  assert.ok(report.held.some((h) => h.reason.includes("inconsistent")));
  db.close();
});

test("run_id collision holds the later submission", () => {
  const { deps, db } = setup();
  const first = submitValid(db, deps, "token-one", { run_id: "BENCH-C1" });
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
  const res = post(db, deps, "token-two", envelope);
  assert.equal(res.status, 201);
  qualify(db, deps);
  assert.equal(eligibility(db, first), "PUBLISHED");
  assert.equal(eligibility(db, res.body.submission_id), "VALIDATED");
  db.close();
});

test("revoked rows never promote and stay revoked", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one", { run_id: "BENCH-R1" });
  del(db, deps, `/v1/submissions/${id}`, "token-one");
  const report = qualify(db, deps);
  assert.equal(eligibility(db, id), "REVOKED");
  assert.ok(report.promoted.every((p) => p.submission_id !== id));
  db.close();
});

test("holds record no phantom history rows", () => {
  const { deps, db } = setup();
  submitValid(db, deps, "token-one", {
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
  qualify(db, deps);
  qualify(db, deps);
  const n = db.get("SELECT COUNT(*) AS n FROM eligibility_history").n;
  // Received + one SUBMITTED->VALIDATED promotion only.
  assert.equal(n, 2);
  db.close();
});

test("velocity floods hold for review", () => {
  // The daily quota caps each UTC day, but 20 rows late one
  // day plus 20 early the next is 40 inside a sliding 24h
  // window: the velocity rule holds the second batch.
  const { deps, db } = setup();
  const dayTwo = NOW + 12 * 60 * 60 * 1000;
  for (let i = 0; i < 20; i++) {
    submitValid(db, deps, "token-one", { run_id: `BENCH-VA${i}` });
  }
  qualify(db, deps, NOW);
  for (let i = 0; i < 20; i++) {
    submitValid(db, deps, "token-one", { run_id: `BENCH-VB${i}` }, {},
      dayTwo);
  }
  const report = qualify(db, deps, dayTwo);
  assert.ok(report.held.some((h) => h.reason.includes("velocity")),
    JSON.stringify(report.held));
  db.close();
});
