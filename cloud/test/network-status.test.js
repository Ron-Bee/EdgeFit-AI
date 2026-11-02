// EVIDENCE-VISIBILITY-01 network status endpoint (NS-01..NS-10).
// Proves GET /v1/network/status serves counts-only,
// pre-publication contribution truth: genuine origins only, revoked
// excluded from accepted counts, closest-cohort quorum progress,
// server-computed publication reasons, and zero identifiers.
import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";
import { publicFetch, __resetPublicRateBuckets } from "../public-reader.js";
import { NETWORK_MAX_CLASSES } from "../api.js";
import {
  NOW, MODEL_A, MODEL_B, makeDeps, openTestDb, seedInstall,
  submitValid, qualify,
} from "./helpers.js";

beforeEach(__resetPublicRateBuckets);

const ORIGIN = "https://public.test";
const PATH = "/v1/network/status";
let runCounter = 31000;

function freshRun(prefix = "BENCH-NET") {
  runCounter += 1;
  return {
    run_id: `${prefix}-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 1000).toISOString(),
  };
}

async function world(versions, kinds, installCount = 3) {
  const db = await openTestDb();
  const deps = makeDeps({ publishConsentVersions: versions });
  const tokens = [];
  for (let i = 0; i < installCount; i += 1) {
    const tok = `net-token-${i + 1}`;
    tokens.push(tok);
    await seedInstall(db, deps, `install-net-${i + 1}`, tok,
      kinds ? kinds[i] : "device_contribution");
  }
  return { db, deps, tokens };
}

async function submitCohort(db, deps, tokens, consent, runs, result = {}) {
  const ids = [];
  for (let r = 0; r < runs; r += 1) {
    ids.push(await submitValid(db, deps, tokens[r % tokens.length],
      { ...freshRun(), ...result }, consent));
  }
  return ids;
}

function req(path, { method = "GET", origin, ip } = {}) {
  const headers = {};
  if (origin !== undefined) headers.origin = origin;
  if (ip !== undefined) headers["cf-connecting-ip"] = ip;
  return new Request(`${ORIGIN}${path}`, { method, headers });
}

async function statusOf(db, deps, path = PATH) {
  const res = await publicFetch(req(path), { db, deps, nowMs: NOW });
  assert.equal(res.status, 200);
  return await res.json();
}

test("NS-01 empty database reports honest zeros", async () => {
  const { db, deps } = await world([], undefined);
  const s = await statusOf(db, deps);
  assert.equal(s.api_version, "1");
  assert.equal(s.scope, "staging-contributions");
  assert.equal(s.accepted_submissions, 0);
  assert.equal(s.contributing_installs, 0);
  assert.deepEqual(s.by_device_class, []);
  assert.equal(s.classes_truncated, false);
  assert.deepEqual(s.stages, { SUBMITTED: 0, VALIDATED: 0,
    QUALIFIED: 0, PUBLISHED: 0, REVOKED: 0 });
  assert.equal(s.closest_cohort, null);
  assert.deepEqual(s.quorum, { min_runs: 5, min_installs: 3 });
  assert.equal(s.publication_open, false);
  assert.ok(s.why_not_published.includes("no_contributions"));
  assert.ok(s.why_not_published.includes("publication_gate_closed"));
  assert.equal(typeof s.generated_at, "string");
  db.close();
});

test("NS-02 legacy and synthetic rows never count", async () => {
  const { db, deps, tokens } = await world([],
    ["legacy_untrusted", "synthetic_test", "device_contribution"]);
  await submitCohort(db, deps, tokens, { policy_version: "v1" }, 6);
  await db.run("UPDATE submissions SET eligibility = 'PUBLISHED'");
  const s = await statusOf(db, deps);
  assert.equal(s.accepted_submissions, 2);
  assert.equal(s.contributing_installs, 1);
  assert.deepEqual(s.by_device_class, [
    { device_class: "SM-S908U", accepted: 2 }]);
  db.close();
});

test("NS-03 revoked rows leave accepted counts, stay in stages", async () => {
  const { db, deps, tokens } = await world([], undefined);
  const ids = await submitCohort(db, deps, tokens,
    { policy_version: "v1" }, 3);
  await db.run(
    "UPDATE submissions SET eligibility = 'REVOKED' WHERE submission_id = ?",
    ids[0]);
  const s = await statusOf(db, deps);
  assert.equal(s.accepted_submissions, 2);
  assert.equal(s.stages.REVOKED, 1);
  assert.equal(s.stages.SUBMITTED, 2);
  db.close();
});

test("NS-04 per-class counts cap at 100 with truncation flag", async () => {
  const classes = [];
  for (let i = 0; i < NETWORK_MAX_CLASSES + 1; i += 1) {
    classes.push(`CLASS-${String(i).padStart(3, "0")}`);
  }
  const db = await openTestDb();
  const deps = makeDeps({ publishConsentVersions: [],
    deviceClasses: classes, quotaPerDay: 500 });
  const tokens = ["net-cap-1", "net-cap-2", "net-cap-3"];
  for (let i = 0; i < 3; i += 1) {
    await seedInstall(db, deps, `install-cap-${i + 1}`, tokens[i],
      "device_contribution");
  }
  for (let i = 0; i < classes.length; i += 1) {
    await submitValid(db, deps, tokens[i % 3],
      { ...freshRun(), device: { model: classes[i] } },
      { policy_version: "v1" });
  }
  const s = await statusOf(db, deps);
  assert.equal(s.accepted_submissions, NETWORK_MAX_CLASSES + 1);
  assert.equal(s.by_device_class.length, NETWORK_MAX_CLASSES);
  assert.equal(s.classes_truncated, true);
  db.close();
});

test("NS-05 stages mirror scheduler progress", async () => {
  const { db, deps, tokens } = await world([], undefined);
  await submitCohort(db, deps, tokens, { policy_version: "v1" }, 2);
  await submitCohort(db, deps, tokens, { policy_version: "ef-synth-01" }, 1,
    { device: { model: "Pixel 9" } });
  await qualify(db, deps);
  const s = await statusOf(db, deps);
  // Empty allowlist holds everything at QUALIFIED; nothing publishes.
  assert.equal(s.stages.QUALIFIED, 3);
  assert.equal(s.stages.PUBLISHED, 0);
  assert.deepEqual(s.consent_versions, ["ef-synth-01", "v1"]);
  db.close();
});

test("NS-06 closest cohort ranks installs first, ties deterministic", async () => {
  const db = await openTestDb();
  const deps = makeDeps({ publishConsentVersions: [],
    deviceClasses: ["Moto G Power", "AA-Phone", "ZZ-Phone"] });
  const tokens = ["net-rank-1", "net-rank-2", "net-rank-3"];
  for (let i = 0; i < 3; i += 1) {
    await seedInstall(db, deps, `install-rank-${i + 1}`, tokens[i],
      "device_contribution");
  }
  // A: 4 runs / 1 install. B and C: 2 runs / 2 installs each.
  for (let r = 0; r < 4; r += 1) {
    await submitValid(db, deps, tokens[0],
      { ...freshRun(), device: { model: "Moto G Power" } },
      { policy_version: "v1" });
  }
  for (const cls of ["AA-Phone", "ZZ-Phone"]) {
    for (let r = 0; r < 2; r += 1) {
      await submitValid(db, deps, tokens[r],
        { ...freshRun(), device: { model: cls } },
        { policy_version: "v1" });
    }
  }
  const s = await statusOf(db, deps);
  assert.equal(s.closest_cohort.runs, 2);
  assert.equal(s.closest_cohort.installs, 2);
  assert.equal(s.closest_cohort.cohort.device_class, "AA-Phone");
  assert.equal(s.closest_cohort.awaiting_qualification, 2);
  assert.match(s.closest_cohort.cohort_hash, /^[0-9a-f]{64}$/);
  assert.equal(s.closest_cohort.cohort.model_sha, MODEL_A);
  db.close();
});

test("NS-07 quorum met plus open gate clears every reason", async () => {
  const { db, deps, tokens } = await world(["v1"], undefined);
  await submitCohort(db, deps, tokens, { policy_version: "v1" }, 6);
  await qualify(db, deps);
  const s = await statusOf(db, deps);
  assert.equal(s.closest_cohort.runs, 6);
  assert.equal(s.closest_cohort.installs, 3);
  assert.equal(s.closest_cohort.awaiting_qualification, 0);
  assert.equal(s.publication_open, true);
  assert.deepEqual(s.why_not_published, []);
  db.close();
});

test("NS-08 response carries zero identifiers", async () => {
  const { db, deps, tokens } = await world([], undefined);
  await submitCohort(db, deps, tokens, { policy_version: "v1" }, 2);
  const s = await statusOf(db, deps);
  assert.deepEqual(Object.keys(s).sort(), ["accepted_submissions",
    "api_version", "by_device_class", "classes_truncated",
    "closest_cohort", "consent_versions", "contributing_installs",
    "generated_at", "publication_open", "quorum", "scope", "stages",
    "why_not_published"]);
  const text = JSON.stringify(s);
  assert.ok(!text.includes("install-net-"));
  assert.ok(!text.includes("net-token-"));
  assert.ok(!text.includes("SUB-"));
  db.close();
});

test("NS-09 route honors method, CORS, and cache guards", async () => {
  const { db, deps } = await world([], undefined);
  const ctx = { db, deps, nowMs: NOW };
  const post = await publicFetch(req(PATH, { method: "POST" }), ctx);
  assert.equal(post.status, 405);
  const get = await publicFetch(
    req(PATH, { origin: "https://edgefit-ai.pages.dev" }), ctx);
  assert.equal(get.status, 200);
  assert.equal(get.headers.get("access-control-allow-origin"),
    "https://edgefit-ai.pages.dev");
  assert.equal(get.headers.get("cache-control"), "no-store");
  const head = await publicFetch(req(PATH, { method: "HEAD" }), ctx);
  assert.equal(head.status, 200);
  assert.equal(await head.text(), "");
  db.close();
});

test("NS-10 distinct models stay in one closest cohort", async () => {
  const { db, deps, tokens } = await world([], undefined);
  await submitCohort(db, deps, tokens, { policy_version: "v1" }, 2);
  await submitCohort(db, deps, tokens, { policy_version: "v1" }, 3,
    { model: { sha256: MODEL_B } });
  const s = await statusOf(db, deps);
  // A holds 2 runs / 2 installs, B holds 3 runs / 3 installs:
  // installs-first ranking picks B.
  assert.equal(s.closest_cohort.cohort.model_sha, MODEL_B);
  assert.equal(s.closest_cohort.runs, 3);
  db.close();
});
