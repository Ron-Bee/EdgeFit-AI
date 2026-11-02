// PUB-03 public read-only evidence Worker (PR-01..PR-30).
// Proves the separate anonymous GET dispatcher: exact four-route
// allowlist, no mutation path, server-side publication guards,
// exact-origin CORS, no-store caching, and Free-tier rate limiting
// with worker.js-identical fail-open semantics. Nonempty positive
// fixtures use synthetic VALUES through real builders; they never
// leave the test database.
import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";
import { openD1 } from "../d1.js";
import { initSchema } from "../db.js";
import publicDefault, {
  publicFetch, makePublicDepsFromEnv, PUBLIC_WEB_ORIGIN, PUBLIC_DEPS,
  MAX_URL_LENGTH, PUBLIC_RATE_LIMIT_RETRY_SECS, PUBLIC_BUCKET_LIMIT,
  PUBLIC_BUCKET_MAX_KEYS, PUBLIC_BUCKET_WINDOW_MS,
  __resetPublicRateBuckets,
} from "../public-reader.js";

// Rate buckets are isolate-global: reset between cases so each
// test starts with a full budget (bucket tests refill within).
beforeEach(__resetPublicRateBuckets);
import { openMockD1Binding } from "./d1mock.js";
import {
  NOW, MODEL_A, makeDeps, openTestDb, seedInstall, submitValid,
  qualify, applyMigrations,
} from "./helpers.js";

const APPROVED = "ef-test-approved-01";
const ORIGIN = "https://public.test";
// Aggregates require a model_sha filter; MODEL_A matches fixtures.
const AGG = `/v1/benchmarks/aggregates?model_sha=${MODEL_A}`;
let runCounter = 90000;

function freshRun(prefix = "BENCH-PUB3") {
  runCounter += 1;
  return {
    run_id: `${prefix}-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 1000).toISOString(),
  };
}

async function freshWorld(versions, kinds) {
  const db = await openTestDb();
  const deps = makeDeps({ publishConsentVersions: versions });
  const tokens = ["pub3-token-0001", "pub3-token-0002", "pub3-token-0003"];
  for (let i = 0; i < 3; i += 1) {
    await seedInstall(db, deps, `install-pub3-${i + 1}`, tokens[i],
      kinds ? kinds[i] : "legacy_untrusted");
  }
  return { db, deps, tokens };
}

async function submitCohort(db, deps, tokens, consent, runs, result = {}) {
  const ids = [];
  for (let r = 0; r < runs; r += 1) {
    ids.push(await submitValid(db, deps, tokens[r % tokens.length],
      { ...freshRun(), generated_tokens: 60 + (r % 5), gen_ms: 1000,
        ...result },
      consent));
  }
  return ids;
}

function req(path, { method = "GET", origin, ip } = {}) {
  const headers = {};
  if (origin !== undefined) headers.origin = origin;
  if (ip !== undefined) headers["cf-connecting-ip"] = ip;
  return new Request(`${ORIGIN}${path}`, { method, headers });
}

async function bodyOf(res) {
  return await res.json();
}

/** Adapter wrapper recording every statement for mutation audit. */
function counting(db) {
  const seen = [];
  const wrap = (name) => async (...args) => {
    seen.push({ op: name, sql: String(args[0]?.sql ?? args[0] ?? "") });
    return db[name](...args);
  };
  return { db: { ...db, get: wrap("get"), all: wrap("all"),
    run: wrap("run"), batch: wrap("batch"), exec: wrap("exec") },
  seen };
}

async function counts(db) {
  const s = await db.get("SELECT COUNT(*) AS n FROM submissions");
  const h = await db.get("SELECT COUNT(*) AS n FROM eligibility_history");
  const d = await db.get("SELECT COUNT(*) AS n FROM daily_counts");
  return [s.n, h.n, d.n];
}

test("PR-01 empty allowlist serves valid empty populations", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const agg = await publicFetch(req(AGG), ctx);
  assert.equal(agg.status, 200);
  assert.deepEqual((await bodyOf(agg)).groups, []);
  const mod = await publicFetch(req("/v1/models"), ctx);
  assert.equal(mod.status, 200);
  assert.deepEqual((await bodyOf(mod)).artifacts, []);
  const cand = await publicFetch(req("/v1/evidence/model-candidates"), ctx);
  assert.equal(cand.status, 200);
  assert.deepEqual((await bodyOf(cand)).candidates, []);
  db.close();
});

test("PR-02 historical PUBLISHED synthetic rows stay invisible", async () => {
  // Legacy PUBLISHED rows with EITHER consent vintage: excluded.
  const { db, deps, tokens } = await freshWorld([],
    ["legacy_untrusted", "legacy_untrusted", "legacy_untrusted"]);
  await submitCohort(db, deps, tokens, { policy_version: "v1" }, 5);
  await qualify(db, deps);
  // Mirror staging's pre-003 history: rows PUBLISHED before the
  // origin gate existed. Eligibility-only UPDATEs never touch
  // origin, so these stay legacy_untrusted, like staging's 16.
  await db.run("UPDATE submissions SET eligibility = 'PUBLISHED'");
  const pub = await db.all(
    "SELECT origin FROM submissions WHERE eligibility = 'PUBLISHED'");
  assert.ok(pub.length > 0, "fixture must hold PUBLISHED legacy rows");
  assert.ok(pub.every((r) => r.origin === "legacy_untrusted"));
  const ctx = { db, deps, nowMs: NOW };
  for (const path of [AGG, "/v1/models",
      "/v1/evidence/model-candidates"]) {
    const res = await publicFetch(req(path), ctx);
    assert.equal(res.status, 200, path);
  }
  const groups = (await bodyOf(await publicFetch(
    req(AGG), ctx))).groups;
  assert.deepEqual(groups, []);
  db.close();
});

test("PR-03 revoked device rows never surface", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  const ids = await submitCohort(db, deps, tokens,
    { policy_version: APPROVED }, 5);
  await qualify(db, deps);
  for (const id of ids) {
    await db.run("UPDATE submissions SET eligibility = 'REVOKED' " +
      "WHERE submission_id = ?", id);
  }
  const ctx = { db, deps, nowMs: NOW };
  const groups = (await bodyOf(await publicFetch(
    req(AGG), ctx))).groups;
  assert.deepEqual(groups, []);
  db.close();
});

test("PR-04 approved version without device origin publishes nothing", async () => {
  for (const kinds of [
    ["synthetic_test", "synthetic_test", "synthetic_test"],
    ["legacy_untrusted", "legacy_untrusted", "legacy_untrusted"],
  ]) {
    const { db, deps, tokens } = await freshWorld([APPROVED], kinds);
    await submitCohort(db, deps, tokens,
      { policy_version: APPROVED }, 5);
    await qualify(db, deps);
    const ctx = { db, deps, nowMs: NOW };
    const groups = (await bodyOf(await publicFetch(
      req(AGG), ctx))).groups;
    assert.deepEqual(groups, [], kinds.join(","));
    db.close();
  }
});

test("PR-05 below-quorum device cohort is withheld", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 4);
  await qualify(db, deps);
  const ctx = { db, deps, nowMs: NOW };
  const groups = (await bodyOf(await publicFetch(
    req(AGG), ctx))).groups;
  // Below-quorum cohorts appear with statistics withheld, never
  // as published aggregates.
  assert.equal(groups.length, 1);
  assert.equal(groups[0].status, "insufficient-evidence");
  assert.equal(groups[0].aggregates, null);
  assert.ok(!groups.some((g) => g.status === "published"));
  db.close();
});

test("PR-06 qualifying device cohort publishes with exact cohorts", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 5);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 5,
    { workload: { id: "bench-b" } });
  await qualify(db, deps);
  const ctx = { db, deps, nowMs: NOW };
  const agg = await bodyOf(await publicFetch(
    req(AGG), ctx));
  assert.equal(agg.groups.length, 2);
  assert.deepEqual(agg.groups.map((g) => g.cohort.workload_id).sort(),
    ["bench-b", "edgefit-prompt-v1"]);
  assert.ok(agg.groups.every((g) => g.status === "published"));
  assert.ok(agg.groups.every((g) => g.n >= 5));
  assert.ok(agg.groups.every((g) =>
    typeof g.aggregates.median_gen_tps === "number"));
  const mod = await bodyOf(await publicFetch(req("/v1/models"), ctx));
  assert.ok(mod.artifacts.length >= 1);
  const cand = await bodyOf(await publicFetch(
    req("/v1/evidence/model-candidates"), ctx));
  assert.ok(cand.candidates.length >= 1);
  db.close();
});

test("PR-07 unknown model filter matches nothing, validly", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const res = await publicFetch(req(
    "/v1/benchmarks/aggregates?model_sha=" + "f".repeat(64)),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 200);
  assert.deepEqual((await bodyOf(res)).groups, []);
  db.close();
});

test("PR-08 invalid query parameters are 400 envelopes", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (const path of ["/v1/benchmarks/aggregates",
      "/v1/benchmarks/aggregates?model_sha=zzz",
      `${AGG}&config_hash=zzz`]) {
    const res = await publicFetch(req(path), ctx);
    assert.equal(res.status, 400, path);
    assert.equal((await bodyOf(res)).error.code, "bad_request");
  }
  db.close();
});

test("PR-09 oversized request URLs are rejected", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const big = `/v1/models?pad=${"x".repeat(MAX_URL_LENGTH)}`;
  const res = await publicFetch(req(big), { db, deps, nowMs: NOW });
  assert.equal(res.status, 400);
  assert.equal((await bodyOf(res)).error.code, "bad_request");
  db.close();
});

test("PR-10 public bodies leak no private identifiers", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 5);
  await qualify(db, deps);
  const ctx = { db, deps, nowMs: NOW };
  let text = "";
  for (const path of [AGG, "/v1/models",
      "/v1/evidence/model-candidates"]) {
    text += JSON.stringify(
      await bodyOf(await publicFetch(req(path), ctx)));
  }
  for (const secret of [...tokens, "install-pub3-", "token_hash",
      "result_json", "content_hash", "consent_accepted_at",
      "consent_scopes", "submission_id"]) {
    assert.ok(!text.includes(secret), `leaked: ${secret}`);
  }
  db.close();
});

test("PR-11 CORS allows exactly the website origin", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const ok = await publicFetch(
    req("/v1/models", { origin: PUBLIC_WEB_ORIGIN }), ctx);
  assert.equal(ok.headers.get("access-control-allow-origin"),
    PUBLIC_WEB_ORIGIN);
  const evil = await publicFetch(
    req("/v1/models", { origin: "https://evil.example" }), ctx);
  assert.equal(evil.headers.get("access-control-allow-origin"), null);
  assert.equal(evil.status, 200);
  db.close();
});

test("PR-12 OPTIONS preflight answers without touching D1", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const { db: cdb, seen } = counting(db);
  const res = await publicFetch(
    req("/v1/models", { method: "OPTIONS", origin: PUBLIC_WEB_ORIGIN }),
    { db: cdb, deps, nowMs: NOW });
  assert.equal(res.status, 204);
  assert.equal(res.headers.get("access-control-allow-origin"),
    PUBLIC_WEB_ORIGIN);
  assert.match(res.headers.get("access-control-allow-methods"), /GET/);
  assert.deepEqual(seen, []);
  db.close();
});

test("PR-13 non-GET methods are rejected on public routes", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
    const res = await publicFetch(
      req("/v1/models", { method }), ctx);
    assert.equal(res.status, 405, method);
    assert.equal((await bodyOf(res)).error.code, "method_not_allowed");
  }
  db.close();
});

test("PR-14 submission routes do not exist publicly", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (const [method, path] of [["POST", "/v1/submissions"],
      ["GET", "/v1/submissions/SUB-1"],
      ["DELETE", "/v1/submissions/SUB-1"],
      ["GET", "/v1/benchmarks/runs"],
      ["GET", "/v2/models"],
      ["GET", "/admin"]]) {
    const res = await publicFetch(req(path, { method }), ctx);
    assert.equal(res.status, 404, `${method} ${path}`);
  }
  db.close();
});

test("PR-15 HEAD mirrors GET status with an empty body", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const get = await publicFetch(req("/v1/models"), ctx);
  const head = await publicFetch(req("/v1/models", { method: "HEAD" }),
    ctx);
  assert.equal(head.status, get.status);
  assert.equal(await head.text(), "");
  assert.equal(head.headers.get("cache-control"), "no-store");
  db.close();
});

test("PR-16 responses are no-store with Vary Origin", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (const path of ["/v1/models", "/nope"]) {
    const res = await publicFetch(req(path), ctx);
    assert.equal(res.headers.get("cache-control"), "no-store", path);
    assert.equal(res.headers.get("vary"), "Origin", path);
  }
  db.close();
});

test("PR-17 rate limiter allows, blocks, and fails open", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const base = { db, deps, nowMs: NOW };
  const ok = await publicFetch(req("/v1/models", { ip: "10.0.0.1" }),
    { ...base, rateLimit: { limit: async () => ({ success: true }) } });
  assert.equal(ok.status, 200);
  const blocked = await publicFetch(req("/v1/models", { ip: "10.0.0.2" }),
    { ...base, rateLimit: { limit: async () => ({ success: false }) } });
  assert.equal(blocked.status, 429);
  assert.equal((await bodyOf(blocked)).error.code, "rate_limited");
  assert.equal(blocked.headers.get("retry-after"),
    String(PUBLIC_RATE_LIMIT_RETRY_SECS));
  const faults = [];
  const hook = (e) => faults.push(e?.message ?? "?");
  const down = await publicFetch(req("/v1/models", { ip: "10.0.0.3" }),
    { ...base,
      rateLimit: { limit: async () => { throw new Error("RL_DOWN"); } },
      onRateLimitError: hook });
  assert.equal(down.status, 200);
  assert.deepEqual(faults, ["RL_DOWN"]);
  const weird = await publicFetch(req("/v1/models", { ip: "10.0.0.4" }),
    { ...base, rateLimit: { limit: async () => ({ nope: 1 }) },
      onRateLimitError: hook });
  assert.equal(weird.status, 200);
  assert.equal(faults.length, 2);
  db.close();
});

test("PR-18 missing rate-limit binding warns once and stays open", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const faults = [];
  const ctx = { db, deps, nowMs: NOW, rateLimit: null,
    onRateLimitError: (e) => faults.push(String(e?.message)) };
  const first = await publicFetch(req("/v1/models"), ctx);
  assert.equal(first.status, 200);
  const second = await publicFetch(req("/v1/models"), ctx);
  assert.equal(second.status, 200);
  assert.equal(faults.length, 1);
  assert.match(faults[0], /unusable/);
  db.close();
});

test("PR-19 misconfiguration and malformed input fail sanitized", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const noDb = await publicFetch(req("/v1/models"),
    { deps, nowMs: NOW });
  assert.equal(noDb.status, 500);
  assert.deepEqual(await bodyOf(noDb),
    { error: { code: "internal", message: "temporary backend failure" } });
  const stub = { method: "GET", url: "::::", headers: new Headers() };
  const bad = await publicFetch(stub, { db, deps, nowMs: NOW });
  assert.equal(bad.status, 400);
  db.close();
});

test("PR-20 no public request mutates D1", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 5);
  await qualify(db, deps);
  const before = await counts(db);
  const { db: cdb, seen } = counting(db);
  const ctx = { db: cdb, deps, nowMs: NOW };
  const battery = [
    ["GET", "/v1/models"], ["GET", AGG],
    ["GET", "/v1/evidence/model-candidates"],
    ["HEAD", "/v1/models"], ["OPTIONS", "/v1/models"],
    ["POST", "/v1/models"], ["DELETE", "/v1/benchmarks/aggregates"],
    ["POST", "/v1/submissions"], ["GET", "/v1/submissions/SUB-1"],
    ["DELETE", "/v1/submissions/SUB-1"], ["GET", "/v1/models?limit=0"],
    ["GET", "/nope"],
  ];
  for (const [method, path] of battery) {
    await publicFetch(req(path, { method,
      origin: PUBLIC_WEB_ORIGIN }), ctx);
  }
  assert.deepEqual(await counts(db), before);
  for (const { op, sql } of seen) {
    assert.ok(!/^\s*(INSERT|UPDATE|DELETE|REPLACE|ALTER|CREATE|DROP)\b/i
      .test(sql), `${op}: ${sql.slice(0, 60)}`);
  }
  db.close();
});

test("PR-21 default export requires PUBLIC_SALT, not INSTALL_SALT", async (t) => {
  const mock = openMockD1Binding();
  t.after(() => mock.close());
  const db = openD1(mock.binding);
  await initSchema(db);
  await applyMigrations(db);
  const denied = await publicDefault.fetch(
    new Request(`${ORIGIN}/v1/models`, { method: "GET" }),
    { DB: mock.binding, INSTALL_SALT: "staging-salt-value" });
  assert.equal(denied.status, 500);
  const allowed = await publicDefault.fetch(
    new Request(`${ORIGIN}/v1/models`, { method: "GET" }),
    { DB: mock.binding, PUBLIC_SALT: "public-salt-value" });
  assert.equal(allowed.status, 200);
  assert.equal(publicDefault.scheduled, undefined);
});

test("PR-22 public deps carry no consent allowlist", () => {
  assert.ok(!("publishConsentVersions" in PUBLIC_DEPS));
  assert.equal(PUBLIC_DEPS.quorumRuns, 5);
  assert.equal(PUBLIC_DEPS.quorumInstalls, 3);
});

test("PR-23 rate-limit key prefers CF-Connecting-IP", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const keys = [];
  const limiter = { limit: async ({ key }) => {
    keys.push(key);
    return { success: true };
  } };
  const ctx = { db, deps, nowMs: NOW, rateLimit: limiter };
  await publicFetch(req("/v1/models", { ip: "203.0.113.9" }), ctx);
  await publicFetch(req("/v1/models"), ctx);
  assert.deepEqual(keys, ["ip:203.0.113.9", "ip:unknown"]);
  db.close();
});

test("PR-24 preflight on unknown routes stays 404", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const res = await publicFetch(
    req("/v1/submissions", { method: "OPTIONS" }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 404);
  db.close();
});

test("PR-25 error responses keep CORS and envelope shape", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const res = await publicFetch(
    req("/v1/models", { method: "DELETE", origin: PUBLIC_WEB_ORIGIN }),
    ctx);
  assert.equal(res.headers.get("access-control-allow-origin"),
    PUBLIC_WEB_ORIGIN);
  assert.deepEqual(await bodyOf(res),
    { error: { code: "method_not_allowed", message: "method not allowed" } });
  db.close();
});

test("PR-26 mixed origins publish the device cohort only", async () => {
  const db = await openTestDb();
  const deps = makeDeps({ publishConsentVersions: [APPROVED] });
  const kinds = ["device_contribution", "device_contribution",
    "device_contribution", "synthetic_test"];
  const tokens = kinds.map((_, i) => `pub3mix-token-000${i + 1}`);
  for (let i = 0; i < 4; i += 1) {
    await seedInstall(db, deps, `install-pub3mix-${i + 1}`, tokens[i],
      kinds[i]);
  }
  // Five device runs rotating over three device installs (quorum)
  // plus five synthetic runs that must neither publish nor count.
  for (let r = 0; r < 5; r += 1) {
    await submitValid(db, deps, tokens[r % 3],
      { ...freshRun(), generated_tokens: 60, gen_ms: 1000 },
      { policy_version: APPROVED });
    await submitValid(db, deps, tokens[3],
      { ...freshRun(), generated_tokens: 60, gen_ms: 1000 },
      { policy_version: APPROVED });
  }
  await qualify(db, deps);
  const ctx = { db, deps, nowMs: NOW };
  const agg = await bodyOf(await publicFetch(req(AGG), ctx));
  assert.equal(agg.groups.length, 1);
  assert.equal(agg.groups[0].status, "published");
  assert.equal(agg.groups[0].n, 5);
  assert.equal(agg.groups[0].installs, 3);
  db.close();
});

test("PR-27 isolate bucket allows the limit then 429s", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (let i = 0; i < PUBLIC_BUCKET_LIMIT; i += 1) {
    const res = await publicFetch(req("/v1/models", { ip: "10.7.7.7" }),
      ctx);
    assert.equal(res.status, 200, `hit ${i}`);
  }
  const blocked = await publicFetch(
    req("/v1/models", { ip: "10.7.7.7" }), ctx);
  assert.equal(blocked.status, 429);
  assert.equal((await bodyOf(blocked)).error.code, "rate_limited");
  assert.equal(blocked.headers.get("retry-after"),
    String(PUBLIC_RATE_LIMIT_RETRY_SECS));
  db.close();
});

test("PR-28 isolate buckets are per-IP and reset by window", async () => {
  const { db, deps } = await freshWorld([], undefined);
  for (let i = 0; i < PUBLIC_BUCKET_LIMIT; i += 1) {
    await publicFetch(req("/v1/models", { ip: "10.8.8.8" }),
      { db, deps, nowMs: NOW });
  }
  const other = await publicFetch(req("/v1/models", { ip: "10.9.9.9" }),
    { db, deps, nowMs: NOW });
  assert.equal(other.status, 200);
  const next = await publicFetch(req("/v1/models", { ip: "10.8.8.8" }),
    { db, deps, nowMs: NOW + PUBLIC_BUCKET_WINDOW_MS });
  assert.equal(next.status, 200);
  db.close();
});

test("PR-29 isolate bucket map stays capped", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (let i = 0; i < PUBLIC_BUCKET_MAX_KEYS + 10; i += 1) {
    const res = await publicFetch(
      req("/v1/models", { ip: `10.99.${i >> 8}.${i & 255}` }), ctx);
    assert.equal(res.status, 200, `key ${i}`);
  }
  // Evicted oldest keys start over instead of erroring.
  const again = await publicFetch(req("/v1/models", { ip: "10.99.0.0" }),
    ctx);
  assert.equal(again.status, 200);
  db.close();
});

test("PR-30 junk requests burn bucket before parsing", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (let i = 0; i < PUBLIC_BUCKET_LIMIT; i += 1) {
    await publicFetch(req("/nope", { ip: "10.6.6.6" }), ctx);
  }
  const blocked = await publicFetch(
    req("/v1/models", { ip: "10.6.6.6" }), ctx);
  assert.equal(blocked.status, 429);
  db.close();
});
