// H10-02 pre-activation qualification (H10-01..H10-25).
// Proves: empty-default publication allowlist, ef-synth-01
// isolation at qualification AND query time (including
// historical PUBLISHED rows), quorum/install counting on
// approved rows only, per-IP rate limiting with contract-safe
// responses, preview-URL disablement, and full preservation of
// scheduler bounds, audit atomicity, and migration integrity.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { RATE_LIMIT_RETRY_SECS, scheduledTick, workerFetch } from "../worker.js";
import { evidenceRetrieve } from "../retrieval.js";
import {
  SYNTHETIC_CONSENT_VERSION, isPublishableVersion,
  publishableVersionClause,
} from "../validate.js";
import {
  NOW, MODEL_A, makeDeps, openTestDb, seedInstall, submitValid,
  post, get, del, qualify, makeEnvelope, makeResult,
} from "./helpers.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const STAGING_TOML = readFileSync(
  join(HERE, "..", "wrangler.staging.toml"), "utf8");
let runCounter = 30000;

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-H10-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
}

async function seed3(db, deps) {
  const tokens = { 1: "h10-tok-1", 2: "h10-tok-2", 3: "h10-tok-3" };
  for (let i = 1; i <= 3; i++) {
    await seedInstall(db, deps, `h10-install-${i}`, tokens[i]);
  }
  return tokens;
}

async function submitN(db, deps, tokens, n, version = "v1",
  overrides = {}) {
  const ids = [];
  for (let i = 0; i < n; i++) {
    ids.push(await submitValid(db, deps, tokens[(i % 3) + 1],
      { ...freshRun(), ...overrides }, { policy_version: version }));
  }
  return ids;
}

async function trailOf(db, id) {
  return (await db.all(
    "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  )).map((r) => r.to_state);
}

async function fullTrailOf(db, id) {
  return db.all(
    "SELECT from_state AS f, to_state AS t, reason AS r FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  );
}

async function eligibilityOf(db, id) {
  return (await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?", id))
    .eligibility;
}

function depsAllow(...versions) {
  return makeDeps({ publishConsentVersions: versions });
}

// 1. Empty allowlist holds publication and empties every surface.
test("H10-01: empty allowlist fails closed everywhere", async (t) => {
  assert.equal(SYNTHETIC_CONSENT_VERSION, "ef-synth-01");
  const empty = makeDeps({ publishConsentVersions: [] });
  assert.equal(isPublishableVersion("v1", empty), false);
  assert.equal(isPublishableVersion("ef-consent-01", empty), false);
  assert.deepEqual(publishableVersionClause(empty),
    { clause: "1 = 0", params: [] });
  assert.deepEqual(publishableVersionClause({}),
    { clause: "1 = 0", params: [] });
  assert.deepEqual(publishableVersionClause(makeDeps()),
    { clause: "consent_policy_version IN (?)", params: ["v1"] });
  assert.equal(isPublishableVersion("v10", makeDeps()), false);
  const db = await openTestDb();
  t.after(() => db.close());
  const tokens = await seed3(db, empty);
  const [id] = await submitN(db, empty, tokens, 1);
  const rep = await qualify(db, empty);
  assert.equal(rep.promoted.length, 2);
  assert.equal(rep.held.length, 1);
  assert.equal(rep.held[0].reason,
    "consent version not approved for publication");
  assert.equal(await eligibilityOf(db, id), "QUALIFIED");
  const agg = await get(db, empty, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.deepEqual(agg.body.groups, []);
  const models = await get(db, empty, "/v1/models", {});
  assert.deepEqual(models.body.artifacts, []);
  const cands = await get(db, empty, "/v1/evidence/model-candidates",
    {});
  assert.deepEqual(cands.body.candidates, []);
  const ret = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, empty);
  assert.equal(ret.ok, true);
  assert.deepEqual(ret.cites, []);
});

// 2. Unknown versions: API-accepted, never published.
test("H10-02: unknown consent version holds without error", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const res = await post(db, deps, tokens[1],
    makeEnvelope(makeResult(freshRun()),
      { policy_version: "nope-01" }));
  assert.equal(res.status, 201);
  const rep = await qualify(db, deps);
  assert.equal(rep.promoted.length, 2);
  assert.equal(await eligibilityOf(db, res.body.submission_id),
    "QUALIFIED");
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.deepEqual(agg.body.groups, []);
});

// 3. ef-synth-01: quorum-met yet unpublished on every surface.
test("H10-03: synthetic quorum never publishes", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ids = await submitN(db, deps, tokens, 5, "ef-synth-01");
  const rep = await qualify(db, deps);
  assert.equal(rep.promoted.length, 10);
  assert.equal(rep.held.length, 5);
  for (const id of ids) {
    assert.equal(await eligibilityOf(db, id), "QUALIFIED");
  }
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.deepEqual(agg.body.groups, []);
  const models = await get(db, deps, "/v1/models", {});
  assert.deepEqual(models.body.artifacts, []);
  const cands = await get(db, deps, "/v1/evidence/model-candidates",
    {});
  assert.deepEqual(cands.body.candidates, []);
  const ret = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, deps);
  assert.deepEqual(ret.cites, []);
});

// 4. Published-then-unapproved: historical rows excluded by query.
test("H10-04: query-time guard hides historical rows", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 2);
  await qualify(db, deps);
  const before = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(before.body.groups[0].n, 2);
  const bare = makeDeps({ publishConsentVersions: [] });
  const agg = await get(db, bare, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.deepEqual(agg.body.groups, []);
  const models = await get(db, bare, "/v1/models", {});
  assert.deepEqual(models.body.artifacts, []);
  const cands = await get(db, bare, "/v1/evidence/model-candidates",
    {});
  assert.deepEqual(cands.body.candidates, []);
  const ret = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, bare);
  assert.deepEqual(ret.cites, []);
  const other = makeDeps({ publishConsentVersions: ["ef-consent-01"] });
  const agg2 = await get(db, other, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.deepEqual(agg2.body.groups, [],
    "exact version match only: v1 rows hidden under ef-consent-01");
});

// 5. Synthetic installs never satisfy real quorum.
test("H10-05: synthetic members excluded from quorum math", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const both = makeDeps({ publishConsentVersions: ["v1", "ef-synth-01"] });
  const tokens = await seed3(db, both);
  for (const inst of [1, 2, 1, 2]) {
    await submitValid(db, both, tokens[inst], freshRun(),
      { policy_version: "v1" });
  }
  for (const inst of [3, 3]) {
    await submitValid(db, both, tokens[inst], freshRun(),
      { policy_version: "ef-synth-01" });
  }
  const rep = await qualify(db, both);
  assert.equal(rep.promoted.length, 18);
  const deps = makeDeps();
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 1);
  assert.equal(agg.body.groups[0].status, "insufficient-evidence");
  assert.equal(agg.body.groups[0].n, 4);
  assert.equal(agg.body.groups[0].installs, 2);
  assert.equal(agg.body.groups[0].aggregates, null);
});

// 6. Synthetic measurements never enter real statistics.
test("H10-06: mixed published cohort yields approved-only stats",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const both = makeDeps(
      { publishConsentVersions: ["v1", "ef-synth-01"] });
    const tokens = await seed3(db, both);
    await submitN(db, both, tokens, 5, "v1");
    await submitN(db, both, tokens, 3, "ef-synth-01",
      { generated_tokens: 640, gen_ms: 4000 });
    await qualify(db, both);
    const deps = makeDeps();
    const agg = await get(db, deps, "/v1/benchmarks/aggregates",
      { model_sha: MODEL_A });
    assert.equal(agg.body.groups[0].status, "published");
    assert.equal(agg.body.groups[0].n, 5);
    assert.equal(agg.body.groups[0].installs, 3);
    assert.equal(agg.body.groups[0].aggregates.mean_gen_tps, 16);
    assert.equal(agg.body.groups[0].aggregates.median_gen_tps, 16);
  });

// 7. Thin approved cohorts stay suppressed (sanity under new code).
test("H10-07: below-quorum approved cohort stays suppressed",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    await submitN(db, deps, tokens, 2);
    await qualify(db, deps);
    const agg = await get(db, deps, "/v1/benchmarks/aggregates",
      { model_sha: MODEL_A });
    assert.equal(agg.body.groups[0].status, "insufficient-evidence");
    assert.equal(agg.body.groups[0].n, 2);
  });

// 8. Held rows keep exact state; owners see qualification status.
test("H10-08: unapproved rows report QUALIFIED to owners", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1, "ef-synth-01");
  await qualify(db, deps);
  const rep = await qualify(db, deps);
  assert.equal(rep.held.length, 1);
  assert.equal(rep.held[0].reason,
    "consent version not approved for publication");
  const seen = await get(db, deps, `/v1/submissions/${id}`, {},
    tokens[1]);
  assert.equal(seen.status, 200);
  assert.equal(seen.body.eligibility, "QUALIFIED");
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED"]);
});

// 9. Revocation composes with the publication filter.
test("H10-09: revoked approved rows leave exact counts", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ids = await submitN(db, deps, tokens, 5);
  await qualify(db, deps);
  const gone = await del(db, deps, `/v1/submissions/${ids[0]}`,
    tokens[1]);
  assert.equal(gone.body.eligibility, "REVOKED");
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "insufficient-evidence");
  assert.equal(agg.body.groups[0].n, 4);
  const models = await get(db, deps, "/v1/models", {});
  assert.equal(models.body.artifacts[0].published_runs, 4);
  assert.equal(models.body.artifacts[0].installs, 3);
  const ret = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, deps);
  assert.deepEqual(ret.cites, []);
});

// 10. Deduplication is orthogonal to publication policy.
test("H10-10: synthetic duplicate replays exactly once", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const envelope = makeEnvelope(makeResult(freshRun()),
    { policy_version: "ef-synth-01" });
  const fresh = await post(db, deps, tokens[1], envelope);
  assert.equal(fresh.status, 201);
  const dupe = await post(db, deps, tokens[1], envelope);
  assert.equal(dupe.status, 200);
  assert.equal(dupe.body.duplicate, true);
  assert.equal(dupe.body.submission_id, fresh.body.submission_id);
});

// 11. Owners keep status and withdrawal over unapproved rows.
test("H10-11: synthetic rows stay owner-manageable", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1, "ef-synth-01");
  await qualify(db, deps);
  const seen = await get(db, deps, `/v1/submissions/${id}`, {},
    tokens[1]);
  assert.equal(seen.body.eligibility, "QUALIFIED");
  const gone = await del(db, deps, `/v1/submissions/${id}`, tokens[1]);
  assert.equal(gone.body.eligibility, "REVOKED");
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "REVOKED"]);
});

// 12. Concurrent withdrawal stays exact on held rows.
test("H10-12: concurrent DELETE on held rows audits once",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    const [id] = await submitN(db, deps, tokens, 1, "ef-synth-01");
    await qualify(db, deps);
    let entered = 0;
    let release;
    const gate = new Promise((r) => { release = r; });
    let gated = 0;
    const gatedDb = { ...db,
      batch: async (s) => {
        if (/SET eligibility = 'REVOKED'/.test(s[0].sql) && gated < 2) {
          gated += 1;
          entered += 1;
          if (entered === 2) release();
          else await gate;
        }
        return db.batch(s);
      } };
    const cut = () => del(gatedDb, deps, `/v1/submissions/${id}`,
      tokens[1]);
    const [a, b] = await Promise.all([cut(), cut()]);
    assert.equal(a.body.eligibility, "REVOKED");
    assert.equal(b.body.eligibility, "REVOKED");
    assert.deepEqual(await trailOf(db, id),
      ["SUBMITTED", "VALIDATED", "QUALIFIED", "REVOKED"]);
  });

// 13. Revoke landing at the publish boundary wins terminally.
test("H10-13: revoke during publish attempt never publishes",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    const [id] = await submitN(db, deps, tokens, 1);
    let release;
    const gate = new Promise((r) => { release = r; });
    let entered;
    const enteredP = new Promise((r) => { entered = r; });
    let armed = true;
    const gatedDb = { ...db,
      batch: async (s) => {
        if (armed && s.length === 1 &&
            /UPDATE submissions SET eligibility = \?/.test(s[0].sql) &&
            (s[0].params ?? [])[0] === "PUBLISHED") {
          armed = false;
          entered();
          await gate;
        }
        return db.batch(s);
      } };
    const tickP = scheduledTick({ db: gatedDb, deps, nowMs: NOW,
      log: () => {}, logErr: () => {} });
    await enteredP;
    const gone = await del(gatedDb, deps, `/v1/submissions/${id}`,
      tokens[1]);
    assert.equal(gone.body.eligibility, "REVOKED");
    release();
    const sum = await tickP;
    assert.equal(sum.promoted, 2);
    assert.ok(sum.deferred >= 1);
    assert.equal(await eligibilityOf(db, id), "REVOKED");
    assert.deepEqual(await trailOf(db, id),
      ["SUBMITTED", "VALIDATED", "QUALIFIED", "REVOKED"]);
  });

// 14+15. Published public surfaces expose counts, never identities.
test("H10-14: aggregates reveal no private identifiers", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 5);
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "published");
  const text = JSON.stringify(agg.body);
  for (const banned of ["install_id", "h10-install", "h10-tok",
    "scopes", "test-salt", "token_hash", "Bearer"]) {
    assert.ok(!text.includes(banned), `leaks ${banned}`);
  }
});

test("H10-15: candidates reveal no private identifiers", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 5);
  await qualify(db, deps);
  const cands = await get(db, deps, "/v1/evidence/model-candidates",
    {});
  assert.equal(cands.body.candidates.length, 1);
  const text = JSON.stringify(cands.body);
  for (const banned of ["install_id", "h10-install", "h10-tok",
    "scopes", "test-salt", "token_hash", "Bearer"]) {
    assert.ok(!text.includes(banned), `leaks ${banned}`);
  }
});

// 16. Filtering is purely version-driven, path-independent.
test("H10-16: legacy versions filter by policy, not origin",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const legacy = makeDeps({ publishConsentVersions: ["legacy-99"] });
    const tokens = await seed3(db, legacy);
    await submitN(db, legacy, tokens, 1, "legacy-99");
    await qualify(db, legacy);
    const bare = makeDeps({ publishConsentVersions: [] });
    const agg = await get(db, bare, "/v1/benchmarks/aggregates",
      { model_sha: MODEL_A });
    assert.deepEqual(agg.body.groups, []);
    const back = await get(db, legacy, "/v1/benchmarks/aggregates",
      { model_sha: MODEL_A });
    assert.equal(back.body.groups[0].n, 1);
  });

const ORIGIN = "https://staging.invalid";

function stubLimiter(behavior, seen = null) {
  return {
    limit: async (arg) => {
      if (seen) seen.push(arg);
      if (behavior === "throw") throw new Error("H10_FAKE down");
      return { success: behavior !== "deny" };
    },
  };
}

// 17. Allowed requests pass the limiter untouched.
test("H10-17: allowing limiter passes requests through", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const res = await workerFetch(new Request(
    `${ORIGIN}/v1/benchmarks/aggregates?model_sha=${MODEL_A}`,
    { method: "GET", headers: { "cf-connecting-ip": "9.9.9.9" } }),
  { db, deps, nowMs: NOW, rateLimit: stubLimiter("allow") });
  assert.equal(res.status, 200);
  assert.deepEqual((await res.json()).groups, []);
});

// 18. Denied requests stop before any database touch.
test("H10-18: exhausted limiter returns 429 without DB reads",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    let touches = 0;
    const counting = { ...db,
      get: async (...a) => { touches += 1; return db.get(...a); },
      all: async (...a) => { touches += 1; return db.all(...a); },
      run: async (...a) => { touches += 1; return db.run(...a); },
      batch: async (...a) => { touches += 1; return db.batch(...a); },
      exec: async (...a) => { touches += 1; return db.exec(...a); } };
    const ctx = { db: counting, deps, nowMs: NOW,
      rateLimit: stubLimiter("deny") };
    const envelope = makeEnvelope(makeResult(freshRun()));
    const denied = await workerFetch(new Request(
      `${ORIGIN}/v1/submissions`,
      { method: "POST",
        headers: { "cf-connecting-ip": "9.9.9.9",
          authorization: `Bearer ${tokens[1]}` },
        body: JSON.stringify(envelope) }), ctx);
    assert.equal(denied.status, 429);
    assert.deepEqual(await denied.json(), { error: {
      code: "rate_limited", message: "too many requests; retry shortly" } });
    assert.equal(denied.headers.get("retry-after"), "60");
    assert.equal(denied.headers.get("content-type"), "application/json");
    const deniedGet = await workerFetch(new Request(
      `${ORIGIN}/v1/models`,
      { method: "GET", headers: { "cf-connecting-ip": "9.9.9.9" } }),
    ctx);
    assert.equal(deniedGet.status, 429);
    assert.equal(touches, 0);
  });

// 19. Missing or broken limiter never bricks the API.
test("H10-19: absent and throwing limiters fail open", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const envelope = makeEnvelope(makeResult(freshRun()));
  const bare = await workerFetch(new Request(
    `${ORIGIN}/v1/submissions`,
    { method: "POST",
      headers: { authorization: `Bearer ${tokens[1]}` },
      body: JSON.stringify(envelope) }), { db, deps, nowMs: NOW });
  assert.equal(bare.status, 201);
  const broken = await workerFetch(new Request(
    `${ORIGIN}/v1/models`, { method: "GET" }),
  { db, deps, nowMs: NOW, rateLimit: stubLimiter("throw") });
  assert.equal(broken.status, 200);
});

// 20. Staging config pins preview-off, rate-limit, and nonpublic.
test("H10-20: staging TOML carries the H10 controls", async () => {
  const envAt = STAGING_TOML.indexOf("[env.staging]");
  const previewAt = STAGING_TOML.indexOf("preview_urls = false");
  assert.ok(previewAt !== -1 && previewAt < envAt,
    "preview_urls = false must be top-level");
  assert.ok(STAGING_TOML.includes("[[env.staging.ratelimits]]"));
  assert.ok(STAGING_TOML.includes('name = "RATE_LIMIT"'));
  assert.ok(STAGING_TOML.includes('namespace_id = "7001"'));
  assert.ok(STAGING_TOML.includes("simple = { limit = 120, period = 60 }"));
  assert.ok(!/^\s*\[.*unsafe.*\]/m.test(STAGING_TOML),
    "no unsafe bindings table (comment mentions allowed)");
  assert.ok(STAGING_TOML.includes("workers_dev = false"));
  assert.ok(STAGING_TOML.includes('crons = ["*/10 * * * *"]'));
  assert.equal(RATE_LIMIT_RETRY_SECS, 60);
});

// 21. Full fetch round trip preserved behind an allowing limiter.
test("H10-21: workerFetch contract holds with limiter present",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    const ctx = { db, deps, nowMs: NOW,
      rateLimit: stubLimiter("allow") };
    const call = async (path, init) => {
      const res = await workerFetch(
        new Request(`${ORIGIN}${path}`, init), ctx);
      return { status: res.status, body: await res.json() };
    };
    const fresh = await call("/v1/submissions", { method: "POST",
      headers: { authorization: `Bearer ${tokens[1]}` },
      body: JSON.stringify(makeEnvelope(makeResult(freshRun()))) });
    assert.equal(fresh.status, 201);
    assert.equal(fresh.body.duplicate, false);
    const id = fresh.body.submission_id;
    const seen = await call(`/v1/submissions/${id}`, { method: "GET",
      headers: { authorization: `Bearer ${tokens[1]}` } });
    assert.equal(seen.status, 200);
    assert.equal(seen.body.eligibility, "SUBMITTED");
    const agg = await call(
      `/v1/benchmarks/aggregates?model_sha=${MODEL_A}`,
      { method: "GET" });
    assert.equal(agg.status, 200);
    assert.deepEqual(agg.body.groups, [],
      "no published rows yet: groups empty");
    const gone = await call(`/v1/submissions/${id}`, { method: "DELETE",
      headers: { authorization: `Bearer ${tokens[1]}` } });
    assert.equal(gone.status, 200);
    assert.equal(gone.body.eligibility, "REVOKED");
  });

// 22. Limiter sees the client IP only, never credentials.
test("H10-22: rate-limit keys carry no secrets", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const seen = [];
  await workerFetch(new Request(`${ORIGIN}/v1/models`,
    { method: "GET",
      headers: { "cf-connecting-ip": "9.9.9.9",
        authorization: `Bearer ${tokens[1]}` } }),
  { db, deps, nowMs: NOW, rateLimit: stubLimiter("allow", seen) });
  assert.deepEqual(seen, [{ key: "ip:9.9.9.9" }]);
  assert.ok(!JSON.stringify(seen).includes(tokens[1]));
});

// 23. Scheduler bounds hold with policy-held rows.
test("H10-23: tick bounds cap held rows exactly", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ids = await submitN(db, deps, tokens, 12, "ef-synth-01");
  const sum = await scheduledTick({ db, deps, nowMs: NOW,
    log: () => {}, logErr: () => {} });
  assert.equal(sum.processed, 10);
  assert.equal(sum.promoted, 20);
  assert.equal(sum.held, 10);
  for (const id of ids.slice(0, 10)) {
    assert.equal(await eligibilityOf(db, id), "QUALIFIED");
  }
  for (const id of ids.slice(10)) {
    assert.equal(await eligibilityOf(db, id), "SUBMITTED");
  }
});

// 24. Trigger audit stays exact under the publication hold.
test("H10-24: held rows keep exact trigger trails", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1, "ef-synth-01");
  await qualify(db, deps);
  assert.deepEqual((await fullTrailOf(db, id)).map((r) => [r.f, r.t, r.r]), [
    [null, "SUBMITTED", "received"],
    ["SUBMITTED", "VALIDATED", "schema recheck passed"],
    ["VALIDATED", "QUALIFIED", "anomaly suite passed"],
  ]);
  await del(db, deps, `/v1/submissions/${id}`, tokens[1]);
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "REVOKED"]);
});

// 25. Chain + policy mix: approved publish, synthetic hold.
test("H10-25: mixed workloads separate by policy version",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    const approved = await submitN(db, deps, tokens, 2, "v1");
    const synth = await submitN(db, deps, tokens, 2, "ef-synth-01");
    await qualify(db, deps);
    for (const id of approved) {
      assert.equal(await eligibilityOf(db, id), "PUBLISHED");
    }
    for (const id of synth) {
      assert.equal(await eligibilityOf(db, id), "QUALIFIED");
    }
    const agg = await get(db, deps, "/v1/benchmarks/aggregates",
      { model_sha: MODEL_A });
    assert.equal(agg.body.groups.length, 1);
    assert.equal(agg.body.groups[0].status, "insufficient-evidence");
    assert.equal(agg.body.groups[0].n, 2);
    const models = await get(db, deps, "/v1/models", {});
    assert.equal(models.body.artifacts[0].published_runs, 2);
  });
