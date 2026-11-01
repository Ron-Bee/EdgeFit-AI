// DEMO-02 device-ingestion Worker (ID-01..ID-26). Proves the
// narrow write gateway: exactly POST-collection + GET/DELETE-item
// through the reviewed builders, per-install bearer auth, quotas,
// dedup, owner isolation, no admin/enrollment/read surfaces, no
// salt or secret needs, and PUB-03-style rate defense (isolate
// budget enforced, binding fail-open). Fixtures are synthetic
// VALUES through real code; nothing leaves the test database.
import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { openD1 } from "../d1.js";
import { initSchema } from "../db.js";
import ingestDefault, {
  ingestFetch, makeIngestDepsFromEnv, INGEST_DEPS,
  INGEST_RATE_LIMIT_RETRY_SECS, INGEST_BUCKET_LIMIT,
  __resetIngestRateBuckets,
} from "../ingest-demo.js";
import { MAX_BODY_BYTES } from "../validate.js";
import { openMockD1Binding } from "./d1mock.js";
import {
  NOW, MODEL_A, makeDeps, openTestDb, seedInstall, submitValid,
  applyMigrations, makeResult, makeEnvelope, qualify,
} from "./helpers.js";

const APPROVED = "ef-test-approved-01";
const ORIGIN = "https://ingest.test";
let runCounter = 70000;

beforeEach(__resetIngestRateBuckets);

function freshRun(prefix = "BENCH-INGEST") {
  runCounter += 1;
  return {
    run_id: `${prefix}-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 1000).toISOString(),
  };
}

async function freshWorld(versions, kinds, quotaPerDay) {
  const db = await openTestDb();
  const deps = makeDeps({ publishConsentVersions: versions,
    ...(quotaPerDay === undefined ? {} : { quotaPerDay }) });
  const tokens = ["ingest-token-0001", "ingest-token-0002",
    "ingest-token-0003"];
  for (let i = 0; i < 3; i += 1) {
    await seedInstall(db, deps, `install-ingest-${i + 1}`, tokens[i],
      kinds ? kinds[i] : "device_contribution");
  }
  return { db, deps, tokens };
}

function ireq(path, { method = "GET", token, body, ip } = {}) {
  const headers = {};
  if (token !== undefined) headers.authorization = `Bearer ${token}`;
  if (ip !== undefined) headers["cf-connecting-ip"] = ip;
  const init = { method, headers };
  if (body !== undefined) init.body = body;
  return new Request(`${ORIGIN}${path}`, init);
}

function envelope(overrides = {}, consent = {}) {
  return JSON.stringify(makeEnvelope(
    makeResult({ ...freshRun(), ...overrides }), consent));
}

async function originOf(db, id) {
  return (await db.get(
    "SELECT origin FROM submissions WHERE submission_id = ?", id)).origin;
}

test("ID-01 valid token submits with server-assigned origin", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const res = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0],
      body: envelope() }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 201);
  const body = await res.json();
  assert.equal(body.eligibility, "SUBMITTED");
  assert.equal(body.duplicate, false);
  assert.equal(await originOf(db, body.submission_id),
    "device_contribution");
  db.close();
});

test("ID-02 missing token is 401", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const res = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", body: envelope() }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 401);
  db.close();
});

test("ID-03 incorrect token is 401", async () => {
  const { db, deps } = await freshWorld([], undefined);
  const res = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: "nope-not-real",
      body: envelope() }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 401);
  db.close();
});

test("ID-04 revoked credential is 401", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  await db.run("UPDATE installs SET revoked_at = ? WHERE install_id = ?",
    new Date(NOW).toISOString(), "install-ingest-1");
  const res = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0],
      body: envelope() }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 401);
  db.close();
});

test("ID-05 synthetic credential submits but never publishes", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["synthetic_test", "synthetic_test", "synthetic_test"]);
  for (let r = 0; r < 5; r += 1) {
    const res = await ingestFetch(
      ireq("/v1/submissions", { method: "POST",
        token: tokens[r % 3],
        body: envelope({}, { policy_version: APPROVED }) }),
      { db, deps, nowMs: NOW });
    assert.equal(res.status, 201);
  }
  const rep = await qualify(db, deps);
  assert.equal(rep.promoted.filter((p) => p.to === "PUBLISHED").length, 0);
  const held = await db.all(
    "SELECT DISTINCT eligibility FROM submissions");
  assert.deepEqual(held.map((r) => r.eligibility), ["QUALIFIED"]);
  db.close();
});

test("ID-06 legacy credential submits but is held untrusted", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["legacy_untrusted", "legacy_untrusted", "legacy_untrusted"]);
  const res = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0],
      body: envelope({}, { policy_version: APPROVED }) }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 201);
  assert.equal(await originOf(db, (await res.json()).submission_id),
    "legacy_untrusted");
  await qualify(db, deps);
  const row = await db.get(
    "SELECT eligibility FROM submissions WHERE install_id = ?",
    "install-ingest-1");
  assert.equal(row.eligibility, "QUALIFIED");
  db.close();
});

test("ID-07 forged client origin cannot override server kind", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const forged = makeEnvelope(
    makeResult({ ...freshRun(), origin: "device_contribution" }));
  forged.origin = "device_contribution";
  const res = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[1],
      body: JSON.stringify(forged) }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 201);
  // tokens[1] is device too; revoke-free proof is the mismatch
  // case below: synthetic install, device forgery.
  db.close();
  const w2 = await freshWorld([],
    ["synthetic_test", "synthetic_test", "synthetic_test"]);
  const res2 = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: w2.tokens[0],
      body: JSON.stringify(forged) }),
    { db: w2.db, deps: w2.deps, nowMs: NOW });
  assert.equal(res2.status, 201);
  assert.equal(await originOf(w2.db, (await res2.json()).submission_id),
    "synthetic_test");
  w2.db.close();
});

test("ID-08 malformed submissions are rejected, storing nothing", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (const body of ["{not json",
      JSON.stringify({ result: {}, consent: {} }),
      JSON.stringify(makeEnvelope(makeResult(freshRun()),
        { scopes: ["everything"] }))]) {
    const res = await ingestFetch(
      ireq("/v1/submissions", { method: "POST", token: tokens[0], body }),
      ctx);
    assert.ok([400, 422].includes(res.status), body.slice(0, 30));
  }
  const n = await db.get("SELECT COUNT(*) AS n FROM submissions");
  assert.equal(n.n, 0);
  db.close();
});

test("ID-09 oversized requests are 413 without buffering lies", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const big = envelope({ run_id: `BIG-${"x".repeat(MAX_BODY_BYTES)}` });
  assert.ok(Buffer.byteLength(big, "utf8") > MAX_BODY_BYTES);
  const over = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0],
      body: big }),
    ctx);
  assert.equal(over.status, 413);
  let read = false;
  const stub = {
    method: "POST",
    url: `${ORIGIN}/v1/submissions`,
    headers: {
      get: (k) => {
        const lk = k.toLowerCase();
        if (lk === "content-length") return String(MAX_BODY_BYTES + 1);
        if (lk === "authorization") return `Bearer ${tokens[0]}`;
        return null;
      },
      forEach(cb) { cb(`Bearer ${tokens[0]}`, "authorization"); },
    },
    text: async () => { read = true; return "x"; },
  };
  const declared = await ingestFetch(stub, ctx);
  assert.equal(declared.status, 413);
  assert.equal(read, false);
  db.close();
});

test("ID-10 duplicate upload returns the original receipt", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const body = envelope();
  const first = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0], body }),
    ctx);
  assert.equal(first.status, 201);
  const id = (await first.json()).submission_id;
  const second = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0], body }),
    ctx);
  assert.equal(second.status, 200);
  const dup = await second.json();
  assert.equal(dup.duplicate, true);
  assert.equal(dup.submission_id, id);
  db.close();
});

test("ID-11 quota exhaustion is 429 with Retry-After", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined, 2);
  const ctx = { db, deps, nowMs: NOW };
  for (let r = 0; r < 2; r += 1) {
    const res = await ingestFetch(
      ireq("/v1/submissions", { method: "POST", token: tokens[0],
        body: envelope() }),
      ctx);
    assert.equal(res.status, 201);
  }
  const capped = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0],
      body: envelope() }),
    ctx);
  assert.equal(capped.status, 429);
  assert.equal((await capped.json()).error.code, "quota_exceeded");
  assert.match(capped.headers.get("retry-after"), /^\d+$/);
  db.close();
});

test("ID-12 foreign receipt lookup is 404", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const id = await submitValid(db, deps, tokens[0], freshRun());
  const res = await ingestFetch(
    ireq(`/v1/submissions/${id}`, { token: tokens[1] }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 404);
  const own = await ingestFetch(
    ireq(`/v1/submissions/${id}`, { token: tokens[0] }),
    { db, deps, nowMs: NOW });
  assert.equal(own.status, 200);
  db.close();
});

test("ID-13 foreign withdrawal is 404 with no effect", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const id = await submitValid(db, deps, tokens[0], freshRun());
  const before = await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?", id);
  const res = await ingestFetch(
    ireq(`/v1/submissions/${id}`, { method: "DELETE",
      token: tokens[2] }),
    { db, deps, nowMs: NOW });
  assert.equal(res.status, 404);
  const after = await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?", id);
  assert.deepEqual(after, before);
  db.close();
});

test("ID-14 owner withdrawal revokes idempotently", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const id = await submitValid(db, deps, tokens[0], freshRun());
  const ctx = { db, deps, nowMs: NOW };
  const gone = await ingestFetch(
    ireq(`/v1/submissions/${id}`, { method: "DELETE",
      token: tokens[0] }),
    ctx);
  assert.equal(gone.status, 200);
  assert.equal((await gone.json()).eligibility, "REVOKED");
  const again = await ingestFetch(
    ireq(`/v1/submissions/${id}`, { method: "DELETE",
      token: tokens[0] }),
    ctx);
  assert.equal(again.status, 200);
  db.close();
});

test("ID-15 cross-install submissions stay isolated", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const ids = [];
  for (let i = 0; i < 3; i += 1) {
    const res = await ingestFetch(
      ireq("/v1/submissions", { method: "POST", token: tokens[i],
        body: envelope() }),
      ctx);
    ids.push((await res.json()).submission_id);
  }
  for (let i = 0; i < 3; i += 1) {
    for (let j = 0; j < 3; j += 1) {
      const res = await ingestFetch(
        ireq(`/v1/submissions/${ids[i]}`, { token: tokens[j] }), ctx);
      assert.equal(res.status, i === j ? 200 : 404, `${i}/${j}`);
    }
  }
  db.close();
});

test("ID-16 no admin endpoint exists", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (const [method, path] of [["GET", "/v1/admin"],
      ["POST", "/v1/admin"], ["GET", "/admin"],
      ["POST", "/v1/installs/revoke-all"], ["GET", "/v1/config"]]) {
    const res = await ingestFetch(
      ireq(path, { method, token: tokens[0] }), ctx);
    assert.equal(res.status, 404, `${method} ${path}`);
  }
  db.close();
});

test("ID-17 no enrollment endpoint exists", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (const path of ["/v1/installs", "/v1/tokens", "/v1/enroll"]) {
    const res = await ingestFetch(
      ireq(path, { method: "POST", token: tokens[0],
        body: JSON.stringify({ kind: "device_contribution" }) }),
      ctx);
    assert.equal(res.status, 404, path);
  }
  // Item-shaped enroll path: POST is 405 (no POST on items) and
  // GET is 404 (no such resource) — no enrollment either way.
  const enrollPost = await ingestFetch(
    ireq("/v1/submissions/enroll", { method: "POST", token: tokens[0],
      body: JSON.stringify({ kind: "device_contribution" }) }),
    ctx);
  assert.equal(enrollPost.status, 405);
  const enrollGet = await ingestFetch(
    ireq("/v1/submissions/enroll", { token: tokens[0] }), ctx);
  assert.equal(enrollGet.status, 404);
  const n = await db.get("SELECT COUNT(*) AS n FROM installs");
  assert.equal(n.n, 3);
  db.close();
});

test("ID-18 read and unknown routes are 404", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (const path of ["/v1/models", "/v1/benchmarks/aggregates",
      "/v1/evidence/model-candidates", "/v2/submissions",
      "/v1/submissions/", "/", "/v1/submissions"]) {
    const res = await ingestFetch(ireq(path, { token: tokens[0] }), ctx);
    assert.equal(res.status, 404, path);
  }
  db.close();
});

test("ID-19 concurrent submissions all land distinctly", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const outs = await Promise.all([0, 1, 2, 3, 4].map(() =>
    ingestFetch(
      ireq("/v1/submissions", { method: "POST", token: tokens[0],
        body: envelope() }),
      ctx).then(async (r) => ({ status: r.status,
        body: await r.json() }))));
  assert.ok(outs.every((o) => o.status === 201));
  assert.equal(new Set(outs.map((o) => o.body.submission_id)).size, 5);
  db.close();
});

test("ID-20 publication guards hold; no read surface to leak", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["synthetic_test", "synthetic_test", "synthetic_test"]);
  const ctx = { db, deps, nowMs: NOW };
  for (let r = 0; r < 5; r += 1) {
    await ingestFetch(
      ireq("/v1/submissions", { method: "POST", token: tokens[r % 3],
        body: envelope({}, { policy_version: APPROVED }) }),
      ctx);
  }
  await qualify(db, deps);
  const n = await db.get(
    "SELECT COUNT(*) AS n FROM submissions WHERE eligibility = 'PUBLISHED'");
  assert.equal(n.n, 0);
  for (const path of ["/v1/models", "/v1/benchmarks/aggregates"]) {
    const res = await ingestFetch(ireq(path, { token: tokens[0] }), ctx);
    assert.equal(res.status, 404, path);
  }
  db.close();
});

test("ID-21 ingest needs no staging secrets; file is self-contained", async () => {
  const src = readFileSync(
    join(dirname(fileURLToPath(import.meta.url)), "..", "ingest-demo.js"),
    "utf8");
  for (const s of ["env.INSTALL_SALT", "env.PUBLIC_SALT",
      "staging-staging.workers.dev", "workers.dev",
      "async scheduled(", "unsafe.bindings"]) {
    assert.ok(!src.includes(s), `ingest source references ${s}`);
  }
  assert.ok(!("publishConsentVersions" in INGEST_DEPS));
  // Live proof: env with ONLY a DB binding serves writes.
  const mock = openMockD1Binding();
  try {
    const db = openD1(mock.binding);
    await initSchema(db);
    await applyMigrations(db);
    const deps = makeIngestDepsFromEnv();
    await seedInstall(db, deps, "ingest-nodep-1", "ingest-nodep-token",
      "device_contribution");
    const res = await ingestFetch(
      ireq("/v1/submissions", { method: "POST",
        token: "ingest-nodep-token", body: envelope() }),
      { db, deps, nowMs: NOW });
    assert.equal(res.status, 201);
  } finally {
    mock.close();
  }
});

test("ID-22 responses leak no secrets", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const bodies = [];
  const first = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0],
      body: envelope() }),
    ctx);
  bodies.push(JSON.stringify(await first.json()));
  const id = JSON.parse(bodies[0]).submission_id;
  for (const [method, path, token] of [
      ["GET", `/v1/submissions/${id}`, tokens[0]],
      ["GET", `/v1/submissions/${id}`, tokens[1]],
      ["DELETE", `/v1/submissions/${id}`, tokens[1]],
      ["DELETE", `/v1/submissions/${id}`, tokens[0]],
      ["POST", "/v1/submissions", "wrong-token"],
      ["GET", "/v1/models", tokens[0]]]) {
    const res = await ingestFetch(
      ireq(path, { method, token,
        body: method === "POST" ? envelope() : undefined }),
      ctx);
    bodies.push(JSON.stringify(await res.json()));
  }
  const text = bodies.join("\n");
  for (const secret of [...tokens, "wrong-token", "token_hash",
      "INSTALL_SALT", "PUBLIC_SALT", "result_json"]) {
    assert.ok(!text.includes(secret), `leaked: ${secret}`);
  }
  db.close();
});

test("ID-23 method/path matrix matches api.js dispatch", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const id = await submitValid(db, deps, tokens[0], freshRun());
  for (const [method, path, want] of [
      ["PUT", "/v1/submissions", 404],
      ["PATCH", "/v1/submissions", 404],
      ["DELETE", "/v1/submissions", 404],
      ["GET", "/v1/submissions", 404],
      ["PUT", `/v1/submissions/${id}`, 405],
      ["PATCH", `/v1/submissions/${id}`, 405],
      ["POST", `/v1/submissions/${id}`, 405],
      ["OPTIONS", `/v1/submissions/${id}`, 405],
      ["HEAD", "/v1/submissions", 404],
    ]) {
    const res = await ingestFetch(ireq(path, { method, token: tokens[0] }),
      ctx);
    assert.equal(res.status, want, `${method} ${path}`);
  }
  db.close();
});

test("ID-24 isolate budget allows the limit then 429s", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  for (let i = 0; i < INGEST_BUCKET_LIMIT; i += 1) {
    const res = await ingestFetch(
      ireq("/nope", { ip: "10.3.3.3", token: tokens[0] }), ctx);
    assert.equal(res.status, 404, `hit ${i}`);
  }
  const blocked = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", ip: "10.3.3.3",
      token: tokens[0], body: envelope() }),
    ctx);
  assert.equal(blocked.status, 429);
  assert.equal((await blocked.json()).error.code, "rate_limited");
  assert.equal(blocked.headers.get("retry-after"),
    String(INGEST_RATE_LIMIT_RETRY_SECS));
  db.close();
});

test("ID-25 binding faults fail open and observed", async () => {
  const { db, deps, tokens } = await freshWorld([], undefined);
  const faults = [];
  const hook = (e) => faults.push(e?.message ?? "?");
  const base = { db, deps, nowMs: NOW, onRateLimitError: hook };
  const down = await ingestFetch(
    ireq("/v1/submissions", { method: "POST", token: tokens[0],
      body: envelope(), ip: "10.4.4.4" }),
    { ...base,
      rateLimit: { limit: async () => { throw new Error("RL_DOWN"); } } });
  assert.equal(down.status, 201);
  assert.deepEqual(faults, ["RL_DOWN"]);
  const missing = await ingestFetch(
    ireq("/nope", { ip: "10.4.4.5" }),
    { ...base, rateLimit: null });
  assert.equal(missing.status, 404);
  assert.equal(faults.length, 2);
  db.close();
});

test("ID-26 default export needs only a DB binding", async (t) => {
  const mock = openMockD1Binding();
  t.after(() => mock.close());
  const db = openD1(mock.binding);
  await initSchema(db);
  await applyMigrations(db);
  const nodeps = makeIngestDepsFromEnv();
  await seedInstall(db, nodeps, "ingest-def-1", "ingest-def-token",
    "device_contribution");
  const env = { DB: mock.binding };
  const res = await ingestDefault.fetch(
    new Request(`${ORIGIN}/v1/submissions`, { method: "POST",
      headers: { authorization: "Bearer ingest-def-token" },
      body: JSON.stringify(makeEnvelope(makeResult(freshRun()))) }),
    env);
  assert.equal(res.status, 201);
  const broken = await ingestDefault.fetch(
    new Request(`${ORIGIN}/v1/models`, { method: "GET" }), {});
  assert.equal(broken.status, 500);
  assert.equal(ingestDefault.scheduled, undefined);
});
