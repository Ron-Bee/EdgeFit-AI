// EF-CLOUD-01A submission API: auth, validation, quota,
// dedup, consent, owner reads, revocation.
import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "../api.js";
import {
  NOW, MODEL_A, makeDeps, openTestDb, seedInstall, makeResult,
  makeEnvelope, post, get, del, submitValid,
} from "./helpers.js";

function setup() {
  const deps = makeDeps();
  const db = openTestDb();
  seedInstall(db, deps, "install-1", "token-one");
  seedInstall(db, deps, "install-2", "token-two");
  return { deps, db };
}

test("valid submission returns 201 SUBMITTED", () => {
  const { deps, db } = setup();
  const res = post(db, deps, "token-one",
    makeEnvelope(makeResult({ run_id: "BENCH-A1" })));
  assert.equal(res.status, 201);
  assert.equal(res.body.eligibility, "SUBMITTED");
  assert.equal(res.body.duplicate, false);
  assert.match(res.body.submission_id, /^SUB-/);
  const row = db.get("SELECT * FROM submissions WHERE submission_id = ?",
    res.body.submission_id);
  assert.equal(row.run_id, "BENCH-A1");
  assert.equal(row.device_class, "SM-S908U");
  assert.equal(row.build_key, "O2:operator_attested");
  assert.equal(row.config_hash.length, 64);
  db.close();
});

test("missing, unknown, and revoked tokens are 401", () => {
  const { deps, db } = setup();
  const envelope = makeEnvelope(makeResult({ run_id: "BENCH-A1" }));
  const base = {
    method: "POST", path: "/v1/submissions", query: {},
    bodyText: JSON.stringify(envelope), db, nowMs: NOW, deps,
  };
  assert.equal(handleRequest({ ...base, headers: {} }).status, 401);
  assert.equal(handleRequest({
    ...base, headers: { authorization: "Bearer nope" },
  }).status, 401);
  assert.equal(handleRequest({
    ...base, headers: { authorization: "Token token-one" },
  }).status, 401);
  db.run("UPDATE installs SET revoked_at = ? WHERE install_id = ?",
    new Date(NOW).toISOString(), "install-1");
  assert.equal(post(db, deps, "token-one", envelope).status, 401);
  db.close();
});

test("daily quota returns 429 without storing", () => {
  const deps = makeDeps({ quotaPerDay: 2 });
  const db = openTestDb();
  seedInstall(db, deps, "install-1", "token-one");
  submitValid(db, deps, "token-one", { run_id: "BENCH-Q1" });
  submitValid(db, deps, "token-one", { run_id: "BENCH-Q2" });
  const res = post(db, deps, "token-one",
    makeEnvelope(makeResult({ run_id: "BENCH-Q3" })));
  assert.equal(res.status, 429);
  assert.equal(res.body.error.code, "quota_exceeded");
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 2);
  db.close();
});

test("identical bytes dedup to the first submission", () => {
  const { deps, db } = setup();
  const envelope = makeEnvelope(makeResult({ run_id: "BENCH-D1" }));
  const first = post(db, deps, "token-one", envelope);
  assert.equal(first.status, 201);
  const second = post(db, deps, "token-two", envelope);
  assert.equal(second.status, 200);
  assert.equal(second.body.duplicate, true);
  assert.equal(second.body.submission_id, first.body.submission_id);
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 1);
  db.close();
});

test("schema violations are itemized 422s storing nothing", () => {
  const { deps, db } = setup();
  const cases = [
    [{ ...makeResult(), contract: "wrong" }, "result.contract"],
    [{ ...makeResult(), run_id: "" }, "result.run_id"],
    [{ ...makeResult(), status: "MAYBE" }, "result.status"],
    [{ ...makeResult(), collected_at_utc: "2030-01-01T00:00:00Z" },
      "result.collected_at_utc"],
    [makeResult({ model: { sha256: "xyz" } }), "result.model.sha256"],
    [makeResult({ config: { n_ctx: 0 } }), "result.config.n_ctx"],
    [makeResult({ workload: { prompt_sha256: "nope" } }),
      "result.workload.prompt_sha256"],
    [makeResult({ measurements: { tps_gen: -1 } }),
      "result.measurements.tps_gen"],
  ];
  for (const [result, field] of cases) {
    const res = post(db, deps, "token-one", makeEnvelope(result));
    assert.equal(res.status, 422);
    assert.ok(res.body.error.details.some((d) => d.field === field),
      `missing rejection for ${field}: ${JSON.stringify(res.body)}`);
  }
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 0);
  db.close();
});

test("malformed JSON and oversized bodies are rejected", () => {
  const { deps, db } = setup();
  const malformed = handleRequest({
    method: "POST", path: "/v1/submissions", query: {},
    headers: { authorization: "Bearer token-one" },
    bodyText: "{nope", db, nowMs: NOW, deps,
  });
  assert.equal(malformed.status, 400);
  const big = handleRequest({
    method: "POST", path: "/v1/submissions", query: {},
    headers: { authorization: "Bearer token-one" },
    bodyText: `{"pad":"${"x".repeat(200 * 1024)}"}`,
    db, nowMs: NOW, deps,
  });
  assert.equal(big.status, 413);
  db.close();
});

test("runs scope is rejected until its policy is approved", () => {
  const { deps, db } = setup();
  const res = post(db, deps, "token-one",
    makeEnvelope(makeResult({ run_id: "BENCH-S1" }),
      { scopes: ["aggregates", "runs"] }));
  assert.equal(res.status, 422);
  assert.ok(res.body.error.details.some((d) =>
    d.reason.includes("runs disclosure is disabled")));
  const unknown = post(db, deps, "token-one",
    makeEnvelope(makeResult({ run_id: "BENCH-S2" }),
      { scopes: ["everything"] }));
  assert.equal(unknown.status, 422);
  db.close();
});

test("owner reads own submission; foreign reads are 404", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one", { run_id: "BENCH-O1" });
  const own = get(db, deps, `/v1/submissions/${id}`, {}, "token-one");
  assert.equal(own.status, 200);
  assert.equal(own.body.run_id, "BENCH-O1");
  assert.equal(own.body.eligibility_history.length, 1);
  assert.equal(
    get(db, deps, `/v1/submissions/${id}`, {}, "token-two").status, 404);
  assert.equal(
    get(db, deps, "/v1/submissions/SUB-000000000000000000000000999999",
      {}, "token-one").status, 404);
  assert.equal(get(db, deps, `/v1/submissions/${id}`).status, 401);
  db.close();
});

test("withdrawal revokes and is idempotent", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one", { run_id: "BENCH-W1" });
  const first = del(db, deps, `/v1/submissions/${id}`, "token-one");
  assert.equal(first.status, 200);
  assert.equal(first.body.eligibility, "REVOKED");
  assert.equal(del(db, deps, `/v1/submissions/${id}`, "token-one").status,
    200);
  const own = get(db, deps, `/v1/submissions/${id}`, {}, "token-one");
  assert.equal(own.body.eligibility, "REVOKED");
  assert.equal(own.body.eligibility_history.length, 2);
  // Foreign installs cannot withdraw (or even see) the row.
  assert.equal(
    del(db, deps, `/v1/submissions/${id}`, "token-two").status, 404);
  db.close();
});

test("unknown routes and methods are 404/405", () => {
  const { deps, db } = setup();
  assert.equal(get(db, deps, "/v1/nope").status, 404);
  const res = handleRequest({
    method: "PUT", path: "/v1/submissions/SUB-1", query: {},
    headers: { authorization: "Bearer token-one" },
    bodyText: "", db, nowMs: NOW, deps,
  });
  assert.equal(res.status, 405);
  db.close();
});

test("runs listing stays disabled with an explicit reason", () => {
  const { deps, db } = setup();
  const res = get(db, deps, "/v1/benchmarks/runs");
  assert.equal(res.status, 404);
  assert.equal(res.body.error.code, "runs_disclosure_disabled");
  db.close();
});
