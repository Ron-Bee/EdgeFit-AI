// EF-CLOUD-01A abuse boundaries: injection strings stay
// inert, type confusion and oversized fields are rejected.
import test from "node:test";
import assert from "node:assert/strict";
import {
  MODEL_A, makeDeps, openTestDb, seedInstall, makeResult,
  makeEnvelope, post, get, submitValid, qualify,
} from "./helpers.js";

function setup() {
  const deps = makeDeps();
  const db = openTestDb();
  seedInstall(db, deps, "install-1", "token-one");
  return { deps, db };
}

test("SQL text in string fields is stored inertly", () => {
  const { deps, db } = setup();
  const evil = "x'; DROP TABLE submissions; --";
  const res = post(db, deps, "token-one", makeEnvelope(makeResult({
    run_id: "BENCH-SQL1",
    model: { display_name: evil },
    error: null,
  })));
  assert.equal(res.status, 201);
  qualify(db, deps);
  // Table intact, row present, nothing executed.
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 1);
  const stored = db.get("SELECT result_json FROM submissions");
  assert.ok(stored.result_json.includes("DROP TABLE"));
  db.close();
});

test("type confusion is rejected field by field", () => {
  const { deps, db } = setup();
  const base = () => makeResult({ run_id: "BENCH-TC1" });
  const cases = [
    () => ({ ...base(), run_id: 42 }),
    () => makeResult({ run_id: "BENCH-TC2", config: { n_ctx: "512" } }),
    () => makeResult({
      run_id: "BENCH-TC3", measurements: { tps_gen: "fast" },
    }),
    () => makeResult({
      run_id: "BENCH-TC4",
      device: { supported_abis: "arm64-v8a" },
    }),
    () => ({ ...base(), status: ["COMPLETED"] }),
  ];
  for (const make of cases) {
    const res = post(db, deps, "token-one", makeEnvelope(make()));
    assert.equal(res.status, 422);
  }
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 0);
  db.close();
});

test("oversized and out-of-range fields are rejected", () => {
  const { deps, db } = setup();
  const big = makeResult({
    run_id: "BENCH-BIG1",
    model: { display_name: "x".repeat(257) },
  });
  assert.equal(post(db, deps, "token-one", makeEnvelope(big)).status, 422);
  const neg = makeResult({
    run_id: "BENCH-BIG2",
    device: { total_ram_bytes: -1 },
  });
  assert.equal(post(db, deps, "token-one", makeEnvelope(neg)).status, 422);
  const meta = {};
  for (let i = 0; i < 65; i++) meta[`k${i}`] = "v";
  const fat = makeResult({ run_id: "BENCH-BIG3", model: { metadata: meta } });
  assert.equal(post(db, deps, "token-one", makeEnvelope(fat)).status, 422);
  const nan = JSON.parse(JSON.stringify(makeResult({
    run_id: "BENCH-BIG4",
  })));
  nan.measurements.tps_gen = NaN;
  // NaN serializes to null over JSON: still rejected, as non-number.
  assert.equal(post(db, deps, "token-one", makeEnvelope(nan)).status, 422);
  db.close();
});

test("unknown top-level keys do not smuggle behavior", () => {
  const { deps, db } = setup();
  const envelope = makeEnvelope(makeResult({ run_id: "BENCH-XK1" }));
  envelope.admin = true;
  envelope.result.eligibility = "PUBLISHED";
  const res = post(db, deps, "token-one", envelope);
  assert.equal(res.status, 201);
  assert.equal(res.body.eligibility, "SUBMITTED");
  db.close();
});

test("corrupt stored rows hold instead of crashing qualification", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one", { run_id: "BENCH-CR1" });
  db.run("UPDATE submissions SET result_json = '{corrupt' WHERE submission_id = ?",
    id);
  const report = qualify(db, deps);
  assert.ok(report.held.some((h) => h.submission_id === id));
  assert.equal(
    db.get("SELECT eligibility FROM submissions WHERE submission_id = ?",
      id).eligibility,
    "SUBMITTED",
  );
  db.close();
});
