// PUB-02 durable evidence provenance (PO-01..PO-17). Proves
// migration 003's server-controlled origin: assigned from the
// install's kind at INSERT, frozen after, required (with an
// approved consent version) by qualification and by all four
// public evidence surfaces. Synthetic and legacy rows can never
// publish, whatever version they stamp.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, unlinkSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { initSchema, openDatabase } from "../db.js";
import { evidenceRetrieve } from "../retrieval.js";
import {
  NOW, MODEL_A, makeDeps, openTestDb, seedInstall, submitValid,
  get, del, post, qualify, makeEnvelope, makeResult,
} from "./helpers.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const MIGRATION_003 = readFileSync(
  join(HERE, "..", "migrations", "003_evidence_origin.sql"), "utf8");
const APPLY_LOCAL = join(HERE, "..", "migrations", "apply-local.mjs");
const APPROVED = "ef-test-approved-01";
let runCounter = 60000;

function freshRun(prefix = "BENCH-PUB02") {
  runCounter += 1;
  return `${prefix}-${runCounter}`;
}

async function freshWorld(versions, kinds) {
  const db = await openTestDb();
  const deps = makeDeps({ publishConsentVersions: versions });
  const tokens = ["pub02-token-0001", "pub02-token-0002",
    "pub02-token-0003"];
  for (let i = 0; i < 3; i += 1) {
    await seedInstall(db, deps, `install-${i + 1}`, tokens[i],
      kinds ? kinds[i] : "legacy_untrusted");
  }
  return { db, deps, tokens };
}

async function submitCohort(db, deps, tokens, consent, runs, result = {}) {
  const ids = [];
  for (let r = 0; r < runs; r += 1) {
    ids.push(await submitValid(db, deps, tokens[r % tokens.length], {
      run_id: freshRun(), generated_tokens: 60 + (r % 5), gen_ms: 1000,
      ...result,
    }, consent));
  }
  return ids;
}

async function originOf(db, id) {
  const row = await db.get(
    "SELECT origin FROM submissions WHERE submission_id = ?", id);
  return row.origin;
}

test("PO-01 origin assigned from install kind at submit", async () => {
  const { db, deps, tokens } = await freshWorld([],
    ["device_contribution", "synthetic_test", "legacy_untrusted"]);
  const ids = await submitCohort(db, deps, tokens,
    { policy_version: "v1" }, 3);
  assert.equal(await originOf(db, ids[0]), "device_contribution");
  assert.equal(await originOf(db, ids[1]), "synthetic_test");
  assert.equal(await originOf(db, ids[2]), "legacy_untrusted");
  db.close();
});

test("PO-02 synthetic origin held even with approved version", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["synthetic_test", "synthetic_test", "synthetic_test"]);
  const ids = await submitCohort(db, deps, tokens,
    { policy_version: APPROVED }, 5);
  const res = await qualify(db, deps);
  assert.equal(res.promoted.filter((p) => p.to === "PUBLISHED").length, 0);
  const holds = res.held.filter((h) =>
    h.reason === "evidence origin not approved for publication");
  assert.equal(holds.length, 5);
  for (const id of ids) {
    const row = await db.get(
      "SELECT eligibility FROM submissions WHERE submission_id = ?", id);
    assert.equal(row.eligibility, "QUALIFIED");
  }
  db.close();
});

test("PO-03 legacy origin held even with approved version", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED]);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 5);
  const res = await qualify(db, deps);
  assert.ok(res.held.some((h) =>
    h.reason === "evidence origin not approved for publication"));
  assert.equal(res.promoted.filter((p) => p.to === "PUBLISHED").length, 0);
  db.close();
});

test("PO-04 device origin plus approved version publishes", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 5);
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 1);
  assert.equal(agg.body.groups[0].status, "published");
  assert.equal(agg.body.groups[0].n, 5);
  assert.equal(agg.body.groups[0].installs, 3);
  db.close();
});

test("PO-05 forged client provenance cannot change origin", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["synthetic_test", "synthetic_test", "synthetic_test"]);
  const res = await post(db, deps, tokens[0], makeEnvelope(
    makeResult({ run_id: freshRun(), provenance: "on_device_measurement" }),
    { policy_version: APPROVED }));
  assert.equal(res.status, 201);
  assert.equal(await originOf(db, res.body.submission_id), "synthetic_test");
  await qualify(db, deps);
  const row = await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?",
    res.body.submission_id);
  assert.equal(row.eligibility, "QUALIFIED");
  db.close();
});

test("PO-06 forged approved version held by origin, not version", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["synthetic_test", "synthetic_test", "synthetic_test"]);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 1);
  const res = await qualify(db, deps);
  assert.ok(!res.held.some((h) =>
    h.reason === "consent version not approved for publication"));
  assert.ok(res.held.some((h) =>
    h.reason === "evidence origin not approved for publication"));
  db.close();
});

test("PO-07 all surfaces exclude synthetic and legacy when allowlisted", async () => {
  const { db, deps, tokens } = await freshWorld(["v1", APPROVED],
    ["synthetic_test", "legacy_untrusted", "synthetic_test"]);
  await submitCohort(db, deps, tokens, { policy_version: "v1" }, 5);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 5);
  await db.run(
    "UPDATE submissions SET eligibility = 'PUBLISHED'");
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 0);
  const models = await get(db, deps, "/v1/models", {});
  assert.equal(models.body.artifacts.length, 0);
  const cand = await get(db, deps, "/v1/evidence/model-candidates",
    { limit: 5 });
  assert.equal(cand.body.candidates.length, 0);
  const rdeps = {
    sha256hex: deps.sha256hex, installSalt: deps.installSalt,
    deviceClasses: deps.deviceClasses, quorumRuns: deps.quorumRuns,
    quorumInstalls: deps.quorumInstalls,
    publishConsentVersions: deps.publishConsentVersions,
  };
  const muse = await evidenceRetrieve(db,
    { device_class: "SM-S908U", model_id: MODEL_A }, rdeps);
  assert.equal(muse.ok, true);
  assert.equal(muse.cites.length, 0);
  db.close();
});

test("PO-08 install kind is immutable", async () => {
  const { db } = await freshWorld([]);
  await assert.rejects(db.run(
    "UPDATE installs SET kind = 'device_contribution' WHERE install_id = 'install-1'"),
  /install kind is immutable/);
  const row = await db.get(
    "SELECT kind FROM installs WHERE install_id = 'install-1'");
  assert.equal(row.kind, "legacy_untrusted");
  db.close();
});

test("PO-09 submission origin is immutable", async () => {
  const { db, deps, tokens } = await freshWorld([]);
  const ids = await submitCohort(db, deps, tokens,
    { policy_version: "v1" }, 1);
  await assert.rejects(db.run(
    "UPDATE submissions SET origin = 'device_contribution' WHERE submission_id = ?",
    ids[0]), /submission origin is immutable/);
  assert.equal(await originOf(db, ids[0]), "legacy_untrusted");
  db.close();
});

test("PO-10 mismatched hand insert aborts, matched succeeds", async () => {
  const { db, deps, tokens } = await freshWorld([],
    ["device_contribution", "synthetic_test", "legacy_untrusted"]);
  const ids = await submitCohort(db, deps, tokens,
    { policy_version: "v1" }, 1);
  const seed = await db.get("SELECT * FROM submissions LIMIT 1");
  assert.equal(ids.length, 1);
  const keys = Object.keys(seed).filter((k) =>
    !["submission_id", "content_hash", "run_id"].includes(k));
  const placeholders = keys.map(() => "?").join(", ");
  const attempt = async (installId, origin) => db.run(
    `INSERT INTO submissions (submission_id, content_hash, run_id, ${keys.join(", ")}) ` +
    `VALUES ('sx-${installId}-${origin}', 'chx-${installId}-${origin}', 'rx-${installId}', ${placeholders})`,
    ...keys.map((k) => k === "install_id" ? installId
      : k === "origin" ? origin : seed[k]));
  await assert.rejects(attempt("install-1", "synthetic_test"),
    /submission origin must match install kind/);
  await attempt("install-1", "device_contribution");
  const row = await db.get(
    "SELECT origin FROM submissions WHERE submission_id = 'sx-install-1-device_contribution'");
  assert.equal(row.origin, "device_contribution");
  db.close();
});

test("PO-11 CHECK rejects out-of-space kind and origin", async () => {
  const { db } = await freshWorld([]);
  await assert.rejects(seedInstall(db, makeDeps({}), "install-x",
    "pub02-token-9999", "bogus"));
  await assert.rejects(db.run(
    "INSERT INTO installs (install_id, token_hash, created_at, revoked_at, kind) " +
    "VALUES ('install-y', 'h', '2026-01-01T00:00:00Z', NULL, 'Genuine')"));
  db.close();
});

test("PO-12 audit trigger coexists with provenance triggers", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  const ids = await submitCohort(db, deps, tokens,
    { policy_version: APPROVED }, 1);
  await qualify(db, deps);
  const hist = await db.all(
    "SELECT from_state, to_state FROM eligibility_history WHERE submission_id = ? ORDER BY rowid ASC",
    ids[0]);
  const states = hist.map((h) => h.to_state);
  assert.ok(states.includes("VALIDATED"));
  assert.ok(states.includes("QUALIFIED"));
  assert.ok(states.includes("PUBLISHED"));
  assert.equal(await originOf(db, ids[0]), "device_contribution");
  db.close();
});

test("PO-13 withdrawal preserved on device rows", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  const ids = await submitCohort(db, deps, tokens,
    { policy_version: APPROVED }, 5);
  await qualify(db, deps);
  const d = await del(db, deps, `/v1/submissions/${ids[0]}`, tokens[0]);
  assert.equal(d.status, 200);
  const row = await db.get(
    "SELECT eligibility, origin FROM submissions WHERE submission_id = ?",
    ids[0]);
  assert.equal(row.eligibility, "REVOKED");
  assert.equal(row.origin, "device_contribution");
  await qualify(db, deps);
  const again = await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?",
    ids[0]);
  assert.equal(again.eligibility, "REVOKED");
  db.close();
});

test("PO-14 migration journal applies once and verifies", async (t) => {
  const dir = join(HERE, ".tmp-pub02");
  mkdirSync(dir, { recursive: true });
  const target = join(dir, `journal-${process.pid}.db`);
  t.after(() => {
    for (const suffix of ["", "-wal", "-shm", "-journal"]) {
      try { unlinkSync(target + suffix); } catch { /* ignore */ }
    }
  });
  const first = execFileSync(process.execPath,
    [APPLY_LOCAL, target], { encoding: "utf8" });
  assert.ok(first.includes("applied 001_init.sql"));
  assert.ok(first.includes("applied 002_eligibility_audit_trigger.sql"));
  assert.ok(first.includes("applied 003_evidence_origin.sql"));
  assert.ok(first.includes("MIGRATIONS OK"));
  const second = execFileSync(process.execPath,
    [APPLY_LOCAL, target], { encoding: "utf8" });
  assert.ok(second.includes("skipped 003_evidence_origin.sql (already applied)"));
  assert.ok(second.includes("MIGRATIONS OK"));
  const check = execFileSync(process.execPath,
    [APPLY_LOCAL, "--check", target], { encoding: "utf8" });
  assert.ok(check.includes("MIGRATIONS OK"));
  const db = openDatabase(target);
  try {
    const rows = await db.all(
      "SELECT filename FROM _migrations_applied ORDER BY filename ASC");
    assert.deepEqual(rows.map((r) => r.filename), ["001_init.sql",
      "002_eligibility_audit_trigger.sql", "003_evidence_origin.sql"]);
  } finally {
    db.close();
  }
});

test("PO-15 pre-journal database claims without re-applying", async (t) => {
  const dir = join(HERE, ".tmp-pub02");
  mkdirSync(dir, { recursive: true });
  const target = join(dir, `prejournal-${process.pid}.db`);
  t.after(() => {
    for (const suffix of ["", "-wal", "-shm", "-journal"]) {
      try { unlinkSync(target + suffix); } catch { /* ignore */ }
    }
  });
  const raw = openDatabase(target);
  try {
    const dir = join(HERE, "..", "migrations");
    await raw.exec(readFileSync(join(dir, "001_init.sql"), "utf8"));
    await raw.exec(readFileSync(join(dir, "002_eligibility_audit_trigger.sql"), "utf8"));
  } finally {
    raw.close();
  }
  const out = execFileSync(process.execPath,
    [APPLY_LOCAL, target], { encoding: "utf8" });
  assert.ok(out.includes("skipped 001_init.sql (already applied)"));
  assert.ok(out.includes("skipped 002_eligibility_audit_trigger.sql (already applied)"));
  assert.ok(out.includes("applied 003_evidence_origin.sql"));
  assert.ok(out.includes("MIGRATIONS OK"));
});

test("PO-16 bare 003 re-execution fails loudly (journal required)", async () => {
  const { db } = await freshWorld([]);
  await assert.rejects(db.exec(MIGRATION_003), /duplicate column name/);
  db.close();
});

test("PO-17 upgrade path backfills legacy_untrusted", async () => {
  const db = openDatabase(":memory:");
  try {
    const dir = join(HERE, "..", "migrations");
    await initSchema(db);
    await db.exec(readFileSync(join(dir, "001_init.sql"), "utf8"));
    await db.exec(readFileSync(join(dir, "002_eligibility_audit_trigger.sql"), "utf8"));
    const deps = makeDeps({ publishConsentVersions: [] });
    await db.run(
      "INSERT INTO installs (install_id, token_hash, created_at, revoked_at) VALUES ('install-1', 'h', '2026-01-01T00:00:00Z', NULL)");
    await db.run(
      "INSERT INTO submissions (submission_id, install_id, content_hash, result_json, run_id, device_class, model_sha, model_size_bytes, quantization_label, workload_id, prompt_sha, n_ctx, n_threads, n_predict, sampler, config_hash, runtime_impl, runtime_backend, runtime_version, effective_opt, effective_opt_status, build_key, status, tps_gen, tps_prefill, gen_tokens, prompt_tokens, collected_at, app_version, mem_avail_pct_start, mem_below_advisory, mem_low_memory, consent_policy_version, consent_accepted_at, consent_scopes, eligibility, received_at, published_at) " +
      "VALUES ('old-1', 'install-1', 'ch', '{}', 'r', 'd', 'm', 1, 'q', 'w', 'p', 1, 1, 1, 's', 'c', 'ri', 'rb', 'rv', 'eo', 'eos', 'b', 'COMPLETED', 5, 5, 1, 1, '2026-01-01T00:00:00Z', 'a', 1, 0, 0, 'v1', '2026-01-01T00:00:00Z', '[\"aggregates\"]', 'PUBLISHED', '2026-01-01T00:00:00Z', NULL)");
    await db.exec(MIGRATION_003);
    const sub = await db.get(
      "SELECT origin FROM submissions WHERE submission_id = 'old-1'");
    assert.equal(sub.origin, "legacy_untrusted");
    const ins = await db.get(
      "SELECT kind FROM installs WHERE install_id = 'install-1'");
    assert.equal(ins.kind, "legacy_untrusted");
    assert.equal(deps.quorumRuns, 5);
  } finally {
    db.close();
  }
});

test("PO-18 rotation boundary keeps history, stops old token", async () => {
  const { db, deps, tokens } = await freshWorld([APPROVED],
    ["device_contribution", "device_contribution", "device_contribution"]);
  await submitCohort(db, deps, tokens, { policy_version: APPROVED }, 5);
  await qualify(db, deps);
  await db.run(
    "UPDATE installs SET revoked_at = '2026-10-30T12:00:00Z' WHERE install_id = 'install-1'");
  const denied = await post(db, deps, tokens[0], makeEnvelope(
    makeResult({ run_id: freshRun() }), { policy_version: APPROVED }));
  assert.equal(denied.status, 401);
  await seedInstall(db, deps, "install-4", "pub02-token-0004",
    "device_contribution");
  const admitted = await post(db, deps, "pub02-token-0004", makeEnvelope(
    makeResult({ run_id: freshRun() }), { policy_version: APPROVED }));
  assert.equal(admitted.status, 201);
  assert.equal(await originOf(db, admitted.body.submission_id),
    "device_contribution");
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "published");
  db.close();
});
