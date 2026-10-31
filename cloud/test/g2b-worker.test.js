// G2-B1 Worker entry-point qualification (harness A-D, K, M and
// Phase-8 negatives, Worker half). Drives cloud/worker.js workerFetch
// with injected adapters (D1 adapter over the binding-shape mock)
// and real fetch Request/Response objects. Proves edge translation
// preserves handler semantics; handler behavior itself is owned by
// the 94-test baseline plus the g2b-d1 suite.
import test from "node:test";
import assert from "node:assert/strict";
import { openD1 } from "../d1.js";
import { initSchema } from "../db.js";
import workerDefault, { workerFetch } from "../worker.js";
import { MAX_BODY_BYTES } from "../validate.js";
import { openMockD1Binding } from "./d1mock.js";
import {
  NOW, MODEL_A, makeDeps, seedInstall, submitValid, qualify,
  makeEnvelope, makeResult, applyMigrations,
} from "./helpers.js";

const ORIGIN = "https://ef-staging.invalid";
let runCounter = 1000;

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-G2BW-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
}

async function openCtx(t, { faults = {}, depsOverrides = {} } = {}) {
  const mock = openMockD1Binding({ faults });
  t.after(() => mock.close());
  const db = openD1(mock.binding);
  await initSchema(db);
  await applyMigrations(db);
  const deps = makeDeps(depsOverrides);
  return { ctx: { db, deps, nowMs: NOW }, mock };
}

async function seed3(ctx) {
  const tokens = { 1: "tok-ALPHA-1", 2: "tok-BRAVO-2", 3: "tok-CHARLIE-3" };
  for (let i = 1; i <= 3; i++) {
    await seedInstall(ctx.db, ctx.deps, `install-G2BW-${i}`, tokens[i]);
  }
  return tokens;
}

function urlFor(path, query = {}) {
  const qs = new URLSearchParams(query).toString();
  return `${ORIGIN}${path}${qs ? `?${qs}` : ""}`;
}

/** Real Request in, { status, headers, body } out. */
async function wfetch(ctx, method, path, { query, headers, body } = {}) {
  const init = { method, headers: headers ?? {} };
  if (body !== undefined) init.body = body;
  const res = await workerFetch(
    new Request(urlFor(path, query), init), ctx);
  return {
    status: res.status,
    headers: res.headers,
    body: await res.json(),
  };
}

function auth(token) {
  return { authorization: `Bearer ${token}` };
}

async function submitQuorum(ctx, tokens, overrides = {}) {
  const ids = [];
  for (const lane of [1, 2, 3, 1, 2]) {
    const res = await wfetch(ctx, "POST", "/v1/submissions", {
      headers: auth(tokens[lane]),
      body: JSON.stringify(makeEnvelope(makeResult({
        ...freshRun(), ...overrides,
      }))),
    });
    assert.equal(res.status, 201, JSON.stringify(res.body));
    ids.push(res.body.submission_id);
  }
  return ids;
}

// ---------- A. request/response conversion ----------

test("G2B-W-A1: full evidence round-trip through the Worker", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  const ids = await submitQuorum(ctx, tokens);
  await qualify(ctx.db, ctx.deps);
  const own = await wfetch(ctx, "GET", `/v1/submissions/${ids[0]}`, {
    headers: auth(tokens[1]),
  });
  assert.equal(own.status, 200);
  assert.equal(own.body.eligibility, "PUBLISHED");
  assert.equal(own.body.eligibility_history.length, 4);
  const agg = await wfetch(ctx, "GET", "/v1/benchmarks/aggregates", {
    query: { model_sha: MODEL_A },
  });
  assert.equal(agg.status, 200);
  assert.equal(agg.body.groups[0].status, "published");
  assert.equal(agg.body.groups[0].n, 5);
  const models = await wfetch(ctx, "GET", "/v1/models");
  assert.equal(models.status, 200);
  assert.equal(models.body.artifacts.length, 1);
  const cands = await wfetch(ctx, "GET", "/v1/evidence/model-candidates",
    { query: { limit: "2" } });
  assert.equal(cands.status, 200);
  assert.equal(cands.body.candidates[0].status, "published");
  const gone = await wfetch(ctx, "DELETE", `/v1/submissions/${ids[0]}`, {
    headers: auth(tokens[1]),
  });
  assert.equal(gone.status, 200);
  assert.equal(gone.body.eligibility, "REVOKED");
});

test("G2B-W-A2: query strings survive edge translation", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  await submitQuorum(ctx, tokens);
  await qualify(ctx.db, ctx.deps);
  const filtered = await wfetch(ctx, "GET", "/v1/benchmarks/aggregates", {
    query: { model_sha: MODEL_A, device_class: "SM-S908U" },
  });
  assert.equal(filtered.status, 200);
  assert.equal(filtered.body.groups.length, 1);
  const none = await wfetch(ctx, "GET", "/v1/benchmarks/aggregates", {
    query: { model_sha: MODEL_A, device_class: "No Such Device" },
  });
  assert.equal(none.status, 200);
  assert.equal(none.body.groups.length, 0);
  // First repeated key wins; second value never reaches the handler.
  const res = await workerFetch(new Request(
    `${ORIGIN}/v1/benchmarks/aggregates?model_sha=${MODEL_A}&model_sha=${"0".repeat(64)}`,
    { method: "GET" }), ctx);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.groups.length, 1);
});

// ---------- B. status and header preservation ----------

test("G2B-W-B1: handler statuses and envelopes pass through intact", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  const id = await submitValid(ctx.db, ctx.deps, tokens[1], freshRun());
  const cases = [
    ["GET", `/v1/submissions/${id}`, {}, null, 401, "unauthorized"],
    ["GET", `/v1/submissions/${id}`, {}, "tok-wrong-9", 401, "unauthorized"],
    ["GET", `/v1/submissions/${id}`, {}, tokens[2], 404, "not_found"],
    ["POST", "/v1/submissions", {}, tokens[1], 400, "malformed_json",
      "not json"],
    ["POST", "/v1/submissions", {}, tokens[1], 422, "invalid_submission",
      "{}"],
    ["GET", "/v1/benchmarks/aggregates", { model_sha: "short" }, null,
      400, "bad_request"],
    ["GET", "/v1/benchmarks/runs", {}, null, 404,
      "runs_disclosure_disabled"],
    ["GET", "/v1/nope", {}, null, 404, "not_found"],
    ["POST", `/v1/submissions/${id}`, {}, tokens[1], 405,
      "method_not_allowed"],
    ["PUT", "/v1/submissions", {}, tokens[1], 405, "method_not_allowed"],
  ];
  for (const [method, path, query, token, status, code, raw] of cases) {
    const res = await wfetch(ctx, method, path, {
      query,
      headers: token ? auth(token) : {},
      body: raw,
    });
    assert.equal(res.status, status, `${method} ${path}`);
    assert.equal(res.body.error.code, code, `${method} ${path}`);
    assert.equal(typeof res.body.error.message, "string");
  }
});

test("G2B-W-B2: JSON content type always; retry-after only when honest", async (t) => {
  const { ctx } = await openCtx(t, { depsOverrides: { quotaPerDay: 2 } });
  const tokens = await seed3(ctx);
  const first = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: JSON.stringify(makeEnvelope(makeResult(freshRun()))),
  });
  assert.equal(first.status, 201);
  assert.match(first.headers.get("content-type"), /application\/json/);
  assert.equal(first.headers.get("retry-after"), null);
  await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: JSON.stringify(makeEnvelope(makeResult(freshRun()))),
  });
  const capped = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: JSON.stringify(makeEnvelope(makeResult(freshRun()))),
  });
  assert.equal(capped.status, 429);
  assert.equal(capped.body.error.code, "quota_exceeded");
  // NOW is UTC noon: exactly 12h of quota window remain.
  assert.equal(capped.headers.get("retry-after"), "43200");
  const missing = await wfetch(ctx, "GET", "/v1/nope");
  assert.equal(missing.headers.get("retry-after"), null);
  assert.match(missing.headers.get("content-type"), /application\/json/);
});

test("G2B-W-B3: default export serves mock env, fails closed alike", async (t) => {
  const mock = openMockD1Binding();
  t.after(() => mock.close());
  await initSchema(openD1(mock.binding));
  const env = { DB: mock.binding, INSTALL_SALT: "staging-salt-value" };
  const denied = await workerDefault.fetch(
    new Request(`${ORIGIN}/v1/models`, { method: "GET" }), env);
  assert.equal(denied.status, 200);
  for (const bad of [{}, { DB: mock.binding }, { INSTALL_SALT: "x" },
    { DB: mock.binding, INSTALL_SALT: "" }, null]) {
    const res = await workerDefault.fetch(
      new Request(`${ORIGIN}/v1/models`, { method: "GET" }), bad);
    assert.equal(res.status, 500, JSON.stringify(bad));
    assert.deepEqual(await res.json(),
      { error: { code: "internal", message: "temporary backend failure" } });
  }
});

// ---------- C. body size enforcement ----------

test("G2B-W-C1: declared over-cap bodies are rejected unread", async (t) => {
  const { ctx, mock } = await openCtx(t);
  await seed3(ctx);
  const before = mock.calls.length;
  let read = false;
  const stub = {
    method: "POST",
    url: urlFor("/v1/submissions"),
    headers: {
      get: (k) => (k.toLowerCase() === "content-length"
        ? String(MAX_BODY_BYTES + 1) : null),
      forEach() {},
    },
    text: async () => {
      read = true;
      return "x";
    },
  };
  const res = await workerFetch(stub, ctx);
  assert.equal(res.status, 413);
  assert.deepEqual(await res.json(),
    { error: { code: "body_too_large", message: "submission exceeds 128 KiB" } });
  assert.equal(read, false);
  assert.equal(mock.calls.length, before);
});

test("G2B-W-C2: actual over-cap bodies are rejected and store nothing", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  const res = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: `{"pad":"${"x".repeat(MAX_BODY_BYTES)}"}`,
  });
  assert.equal(res.status, 413);
  assert.equal(res.body.error.code, "body_too_large");
  const count = await ctx.db.get("SELECT COUNT(*) AS n FROM submissions");
  assert.equal(count.n, 0);
});

test("G2B-W-C3: exact-cap bodies reach the handler", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  // Exactly 128 KiB of non-JSON: the edge passes it through and the
  // handler reports malformed JSON, proving handler execution.
  const exact = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: "x".repeat(MAX_BODY_BYTES),
  });
  assert.equal(exact.status, 400);
  assert.equal(exact.body.error.code, "malformed_json");
  const over = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: "x".repeat(MAX_BODY_BYTES + 1),
  });
  assert.equal(over.status, 413);
});

test("G2B-W-C4: the cap counts UTF-8 bytes, not characters", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  // 70k chars x 2 bytes = 140k bytes: over cap by bytes, under by chars.
  const res = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: "é".repeat(70000),
  });
  assert.equal(res.status, 413);
  // 60k chars x 2 bytes = 120k bytes: under cap, handler runs.
  const under = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: "é".repeat(60000),
  });
  assert.equal(under.status, 400);
  assert.equal(under.body.error.code, "malformed_json");
});

// ---------- D. no administrative route exposure ----------

test("G2B-W-D1: only v1 evidence routes answer; the rest never 2xx", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  const id = await submitValid(ctx.db, ctx.deps, tokens[1], freshRun());
  const paths = ["/", "/admin", "/v1", "/v1/admin", "/v1/installs",
    "/v1/tokens", "/v1/migrate", "/v1/migrations", "/v1/secrets",
    "/v1/qualify", "/v1/publish", "/debug", "/metrics", "/healthz",
    "/v1/submissions", "/.env", "/wrangler.toml"];
  for (const path of paths) {
    for (const method of ["GET", "POST", "DELETE"]) {
      const res = await wfetch(ctx, method, path, {
        headers: auth(tokens[1]),
        body: method === "POST" ? "{}" : undefined,
      });
      assert.ok(res.status === 401 || res.status === 404 ||
        res.status === 405 || res.status === 413 || res.status === 422,
        `${method} ${path} -> ${res.status}`);
    }
  }
  // Unsupported methods on REAL routes: 405, never executed. Stub
  // requests: undici refuses to even construct TRACE/CONNECT, which
  // is itself an edge rejection, but the Worker must reject them too.
  const stubFor = (method, path) => ({
    method,
    url: urlFor(path),
    headers: {
      get: (k) => (k.toLowerCase() === "authorization"
        ? `Bearer ${tokens[1]}` : null),
      forEach(cb) {
        cb(`Bearer ${tokens[1]}`, "authorization");
      },
    },
  });
  for (const method of ["PUT", "PATCH", "TRACE", "OPTIONS", "HEAD",
    "CONNECT"]) {
    const res = await workerFetch(stubFor(method, "/v1/submissions"), ctx);
    assert.equal(res.status, 405, method);
    const item = await workerFetch(stubFor(method, `/v1/submissions/${id}`),
      ctx);
    assert.equal(item.status, 405, `${method} item`);
  }
  // Sanity: the genuine routes still answer beside the rejects.
  const own = await wfetch(ctx, "GET", `/v1/submissions/${id}`, {
    headers: auth(tokens[1]),
  });
  assert.equal(own.status, 200);
});

// ---------- K. no evidence/identity disclosure ----------

test("G2B-W-K1: public responses carry cohorts, never identities", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  await submitQuorum(ctx, tokens);
  await qualify(ctx.db, ctx.deps);
  const ids = ["install-G2BW-1", "install-G2BW-2", "install-G2BW-3"];
  const secrets = [tokens[1], tokens[2], tokens[3], "test-salt"];
  const pages = [
    await wfetch(ctx, "GET", "/v1/benchmarks/aggregates",
      { query: { model_sha: MODEL_A } }),
    await wfetch(ctx, "GET", "/v1/models"),
    await wfetch(ctx, "GET", "/v1/evidence/model-candidates"),
  ];
  for (const page of pages) {
    assert.equal(page.status, 200);
    const text = JSON.stringify(page.body);
    for (const needle of [...ids, ...secrets, "token_hash", "result_json",
      "run_id", "submission_id", "eligibility_history"]) {
      assert.ok(!text.includes(needle), `leaked ${needle}`);
    }
  }
  // Cohort evidence itself IS present: privacy is absence of
  // identity, not absence of statistics.
  assert.equal(pages[0].body.groups[0].cohort.device_class, "SM-S908U");
  assert.ok(pages[0].body.groups[0].aggregates.mean_gen_tps > 0);
});

// ---------- M. no secret exposure + E. binding failures ----------

test("G2B-W-M1: binding failures are sanitized 500s on every route", async (t) => {
  const { ctx } = await openCtx(t, {
    faults: { throwOn: { prepare: new Error("D1_FAKE exploded") } },
  });
  const routes = [
    ["GET", "/v1/benchmarks/aggregates", { model_sha: MODEL_A }],
    ["GET", "/v1/models", {}],
    ["GET", "/v1/evidence/model-candidates", {}],
  ];
  for (const [method, path, query] of routes) {
    const res = await wfetch(ctx, method, path, { query });
    assert.equal(res.status, 500, path);
    assert.deepEqual(res.body,
      { error: { code: "internal", message: "temporary backend failure" } });
  }
  const sub = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth("tok-ALPHA-1"),
    body: "{}",
  });
  assert.equal(sub.status, 500);
  assert.ok(!JSON.stringify(sub.body).includes("D1_FAKE"));
});

test("G2B-W-M2: rejection envelopes never echo secrets", async (t) => {
  const { ctx } = await openCtx(t);
  const tokens = await seed3(ctx);
  const bad = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: JSON.stringify({ result: {}, consent: {} }),
  });
  assert.equal(bad.status, 422);
  const text = JSON.stringify(bad.body);
  assert.ok(!text.includes(tokens[1]));
  assert.ok(!text.includes("test-salt"));
  assert.ok(!text.toLowerCase().includes("salt"));
});

// ---------- Phase-8 negatives ----------

test("G2B-W-NEG1: failed batches store nothing and burn no quota", async (t) => {
  const { ctx } = await openCtx(t, {
    faults: { throwOn: { batch: new Error("D1_FAKE batch down") } },
  });
  const tokens = await seed3(ctx);
  const res = await wfetch(ctx, "POST", "/v1/submissions", {
    headers: auth(tokens[1]),
    body: JSON.stringify(makeEnvelope(makeResult(freshRun()))),
  });
  assert.equal(res.status, 500);
  const rows = await ctx.db.get("SELECT COUNT(*) AS n FROM submissions");
  assert.equal(rows.n, 0);
  const quota = await ctx.db.get(
    "SELECT COUNT(*) AS n FROM daily_counts WHERE install_id = ?",
    "install-G2BW-1");
  assert.equal(quota.n, 0);
});

test("G2B-W-NEG2: unauthorized submissions store nothing", async (t) => {
  const { ctx } = await openCtx(t);
  await seed3(ctx);
  for (const headers of [{}, auth("tok-wrong-9"),
    { authorization: "Bearer short" }]) {
    const res = await wfetch(ctx, "POST", "/v1/submissions", {
      headers,
      body: JSON.stringify(makeEnvelope(makeResult(freshRun()))),
    });
    assert.equal(res.status, 401);
  }
  const rows = await ctx.db.get("SELECT COUNT(*) AS n FROM submissions");
  assert.equal(rows.n, 0);
});
