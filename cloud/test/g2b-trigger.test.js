// FREE-04 atomic eligibility-audit qualification (GT-01..GT-24 +
// G2B-D-N3). Proves migration 002's trigger makes each guarded
// eligibility UPDATE atomic with its history row: no phantoms
// under overlap, no gaps under failure, exact reasons/order,
// revocation untouched, migration mechanics sound on both the
// node and D1-adapter paths.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { openD1 } from "../d1.js";
import { initSchema, openDatabase } from "../db.js";
import { handleRequest } from "../api.js";
import { scheduledTick } from "../worker.js";
import { openMockD1Binding } from "./d1mock.js";
import {
  NOW, MODEL_A, makeDeps, openTestDb, seedInstall, submitValid,
  get, del, qualify, applyMigrations, makeEnvelope, makeResult,
} from "./helpers.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const MIGRATION_002 = readFileSync(
  join(HERE, "..", "migrations", "002_eligibility_audit_trigger.sql"),
  "utf8");
const PROMPT_B = "b".repeat(64);
const AT_ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
let runCounter = 9000;

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-GT-${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
}

async function seed3(db, deps) {
  const tokens = { 1: "trig-tok-1", 2: "trig-tok-2", 3: "trig-tok-3" };
  for (let i = 1; i <= 3; i++) {
    await seedInstall(db, deps, `trig-install-${i}`, tokens[i]);
  }
  return tokens;
}

async function submitN(db, deps, tokens, n, overrides = {}) {
  const ids = [];
  for (let i = 0; i < n; i++) {
    ids.push(await submitValid(db, deps, tokens[(i % 3) + 1],
      { ...freshRun(), ...overrides }));
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
    "SELECT from_state AS f, to_state AS t, reason AS r, at AS at FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  );
}

async function eligibilityOf(db, id) {
  return (await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?", id))
    .eligibility;
}

// Direct guarded UPDATE in the exact transition() shape. Pins the
// mechanism per transition; behavioral coverage goes through
// qualify()/scheduledTick below.
async function directTransition(db, id, from, to, at) {
  const [updated] = await db.batch([
    { sql: `UPDATE submissions SET eligibility = ?, published_at = ?
            WHERE submission_id = ? AND eligibility = ?`,
      params: [to, to === "PUBLISHED" ? at : null, id, from] },
  ]);
  return updated.changes === 1;
}

// 1. SUBMITTED -> VALIDATED writes its own audit row.
test("GT-01: guarded S->V update audits itself via trigger", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  assert.equal(await directTransition(db, id, "SUBMITTED", "VALIDATED",
    new Date(NOW).toISOString()), true);
  const rows = await fullTrailOf(db, id);
  assert.deepEqual(rows.map((r) => [r.f, r.t, r.r]), [
    [null, "SUBMITTED", "received"],
    ["SUBMITTED", "VALIDATED", "schema recheck passed"],
  ]);
  for (const row of rows) {
    assert.match(row.at, AT_ISO, JSON.stringify(row));
  }
});

// 2. VALIDATED -> QUALIFIED.
test("GT-02: guarded V->Q update audits itself via trigger", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const at = new Date(NOW).toISOString();
  assert.equal(await directTransition(db, id, "SUBMITTED", "VALIDATED", at),
    true);
  assert.equal(await directTransition(db, id, "VALIDATED", "QUALIFIED", at),
    true);
  const rows = await fullTrailOf(db, id);
  assert.deepEqual(rows.map((r) => [r.f, r.t, r.r]), [
    [null, "SUBMITTED", "received"],
    ["SUBMITTED", "VALIDATED", "schema recheck passed"],
    ["VALIDATED", "QUALIFIED", "anomaly suite passed"],
  ]);
});

// 3. QUALIFIED -> PUBLISHED sets published_at with the audit row.
test("GT-03: Q->P stamps published_at atomically with history", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const at = new Date(NOW).toISOString();
  for (const [f, to] of [["SUBMITTED", "VALIDATED"],
    ["VALIDATED", "QUALIFIED"], ["QUALIFIED", "PUBLISHED"]]) {
    assert.equal(await directTransition(db, id, f, to, at), true);
  }
  const row = await db.get(
    "SELECT eligibility, published_at FROM submissions WHERE submission_id = ?",
    id);
  assert.equal(row.eligibility, "PUBLISHED");
  assert.equal(row.published_at, at);
  const trail = await fullTrailOf(db, id);
  assert.equal(trail.length, 4);
  assert.deepEqual(
    [trail[3].f, trail[3].t, trail[3].r],
    ["QUALIFIED", "PUBLISHED", "consent current; published"]);
  assert.match(trail[3].at, AT_ISO);
});

// 4. Full behavioral trail through the real machine.
test("GT-04: qualify() yields exact ordered audited trails", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const rep = await qualify(db, deps);
  assert.equal(rep.promoted.length, 3);
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  const rows = await fullTrailOf(db, id);
  assert.deepEqual(rows.map((r) => [r.f, r.t, r.r]), [
    [null, "SUBMITTED", "received"],
    ["SUBMITTED", "VALIDATED", "schema recheck passed"],
    ["VALIDATED", "QUALIFIED", "anomaly suite passed"],
    ["QUALIFIED", "PUBLISHED", "consent current; published"],
  ]);
  for (const r of rows) assert.match(r.at, AT_ISO, JSON.stringify(r));
});

// 5a. Natural overlap through the non-tick caller path.
test("GT-05a: concurrent qualify() calls stay exactly-once", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ids = await submitN(db, deps, tokens, 5);
  const [a, b] = await Promise.all(
    [qualify(db, deps), qualify(db, deps)]);
  assert.equal(a.promoted.length + b.promoted.length, 15);
  for (const id of ids) {
    assert.deepEqual(await trailOf(db, id),
      ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"], id);
  }
});

// 5b. FORCED overlap: both passes inside the transition at once.
test("GT-05b: forced transition overlap audits exactly once", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  let entered = 0;
  let release;
  const gate = new Promise((r) => { release = r; });
  let gated = 0;
  const gatedDb = { ...db,
    batch: async (s) => {
      if (s.length === 1 &&
          /UPDATE submissions SET eligibility/.test(s[0].sql) &&
          gated < 2) {
        gated += 1;
        entered += 1;
        if (entered === 2) release();
        else await gate;
      }
      return db.batch(s);
    } };
  const tick = () => scheduledTick({ db: gatedDb, deps, nowMs: NOW,
    log: () => {}, logErr: () => {} });
  const [a, b] = await Promise.all([tick(), tick()]);
  assert.equal(a.promoted + b.promoted, 3);
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  const rows = await fullTrailOf(db, id);
  assert.deepEqual(rows.map((r) => r.r), ["received",
    "schema recheck passed", "anomaly suite passed",
    "consent current; published"]);
});

// 6. Stale CAS loser fires no trigger row.
test("GT-06: 0-row guarded UPDATE writes no history", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const at = new Date(NOW).toISOString();
  assert.equal(await directTransition(db, id, "SUBMITTED", "VALIDATED", at),
    true);
  assert.equal(await directTransition(db, id, "SUBMITTED", "VALIDATED", at),
    false);
  assert.deepEqual(await trailOf(db, id), ["SUBMITTED", "VALIDATED"]);
});

// 7. Revocation before qualification wins outright.
test("GT-07: revoke-before-tick leaves [SUBMITTED, REVOKED]", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const gone = await del(db, deps, `/v1/submissions/${id}`, tokens[1]);
  assert.equal(gone.status, 200);
  assert.equal(gone.body.eligibility, "REVOKED");
  const rep = await qualify(db, deps);
  assert.equal(rep.promoted.length, 0);
  assert.deepEqual(await trailOf(db, id), ["SUBMITTED", "REVOKED"]);
  const rows = await fullTrailOf(db, id);
  assert.equal(rows[1].r, "consent withdrawn by owner");
});

// 8. Revoke lands between two rows' transitions: no cross-talk.
test("GT-08: mid-tick revoke isolates rows exactly", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [idA, idB] = await submitN(db, deps, tokens, 2);
  let release;
  const gate = new Promise((r) => { release = r; });
  let entered;
  const enteredP = new Promise((r) => { entered = r; });
  let armed = true;
  const gatedDb = { ...db,
    batch: async (s) => {
      if (armed && s.length === 1 &&
          /UPDATE submissions SET eligibility/.test(s[0].sql) &&
          (s[0].params ?? []).includes(idB)) {
        armed = false;
        entered();
        await gate;
      }
      return db.batch(s);
    } };
  const tickP = scheduledTick({ db: gatedDb, deps, nowMs: NOW,
    log: () => {}, logErr: () => {} });
  await enteredP;
  const gone = await del(gatedDb, deps, `/v1/submissions/${idB}`,
    tokens[2]);
  assert.equal(gone.body.eligibility, "REVOKED");
  release();
  const sum = await tickP;
  assert.equal(await eligibilityOf(db, idA), "PUBLISHED");
  assert.deepEqual(await trailOf(db, idA),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  assert.equal(await eligibilityOf(db, idB), "REVOKED");
  assert.deepEqual(await trailOf(db, idB), ["SUBMITTED", "REVOKED"]);
  assert.ok(sum.deferred >= 1);
});

// 9. Revocation after publication appends exactly one row.
test("GT-09: revoke-after-publish keeps full exact trail", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  await qualify(db, deps);
  assert.equal(await eligibilityOf(db, id), "PUBLISHED");
  const gone = await del(db, deps, `/v1/submissions/${id}`, tokens[1]);
  assert.equal(gone.body.eligibility, "REVOKED");
  const rows = await fullTrailOf(db, id);
  assert.deepEqual(rows.map((r) => [r.f, r.t]), [
    [null, "SUBMITTED"], ["SUBMITTED", "VALIDATED"],
    ["VALIDATED", "QUALIFIED"], ["QUALIFIED", "PUBLISHED"],
    ["PUBLISHED", "REVOKED"],
  ]);
  assert.equal(rows[4].r, "consent withdrawn by owner");
});

// 10. Revoked rows never resurrect; trails freeze.
test("GT-10: later ticks never touch revoked trails", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  await qualify(db, deps);
  await del(db, deps, `/v1/submissions/${id}`, tokens[1]);
  const before = await fullTrailOf(db, id);
  const rep = await qualify(db, deps);
  assert.equal(rep.promoted.length, 0);
  assert.equal(await eligibilityOf(db, id), "REVOKED");
  assert.deepEqual(await fullTrailOf(db, id), before);
});

// 11. Trigger failure aborts the parent UPDATE (no partial audit).
test("GT-11: dead audit table rolls back the transition", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  await db.exec("DROP TABLE eligibility_history");
  await assert.rejects(
    directTransition(db, id, "SUBMITTED", "VALIDATED",
      new Date(NOW).toISOString()));
  assert.equal(await eligibilityOf(db, id), "SUBMITTED");
  const rep = await qualify(db, deps);
  assert.equal(rep.held.length, 1);
  assert.equal(rep.promoted.length, 0);
  assert.equal(await eligibilityOf(db, id), "SUBMITTED");
});

// 12. Failed transition batch leaves eligibility AND audit clean.
test("GT-12: flaky transition batch holds without residue", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  let failNext = true;
  const flaky = { ...db,
    batch: async (s) => {
      if (failNext && s.length === 1 &&
          /UPDATE submissions SET eligibility/.test(s[0].sql)) {
        failNext = false;
        throw new Error("GT12_FAKE transition down");
      }
      return db.batch(s);
    } };
  const rep = await qualify(flaky, deps);
  assert.equal(rep.held.length, 1);
  assert.equal(rep.promoted.length, 0);
  assert.equal(await eligibilityOf(db, id), "SUBMITTED");
  assert.deepEqual(await trailOf(db, id), ["SUBMITTED"]);
});

// 13. One UPDATE per transition: no inter-step window can exist.
test("GT-13: full promotion issues exactly 3 audit-carrying UPDATEs",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    const [id] = await submitN(db, deps, tokens, 1);
    let transitionBatches = 0;
    const counting = { ...db,
      batch: async (s) => {
        if (s.length === 1 &&
            /UPDATE submissions SET eligibility/.test(s[0].sql)) {
          transitionBatches += 1;
        }
        return db.batch(s);
      } };
    const rep = await qualify(counting, deps);
    assert.equal(rep.promoted.length, 3);
    assert.equal(transitionBatches, 3);
    assert.deepEqual(await trailOf(db, id),
      ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  });

// 14. Failed middle transition retries to an exact trail (no gap).
test("GT-14: V->Q failure then retry converges exactly", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  let failNext = true;
  const flaky = { ...db,
    batch: async (s) => {
      if (failNext && s.length === 1 &&
          /UPDATE submissions SET eligibility/.test(s[0].sql) &&
          (s[0].params ?? [])[0] === "QUALIFIED") {
        failNext = false;
        throw new Error("GT14_FAKE V->Q down");
      }
      return db.batch(s);
    } };
  const mid = await qualify(flaky, deps);
  assert.equal(mid.held.length, 1);
  assert.equal(await eligibilityOf(db, id), "VALIDATED");
  assert.deepEqual(await trailOf(db, id), ["SUBMITTED", "VALIDATED"]);
  const retry = await qualify(db, deps);
  assert.equal(retry.promoted.length, 2);
  assert.equal(await eligibilityOf(db, id), "PUBLISHED");
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
});

// 15. Mixed database reconciles with zero flags.
test("GT-15: published/revoked/held rows reconcile cleanly", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [idP, idR, idH] = await submitN(db, deps, tokens, 3);
  await db.run("UPDATE submissions SET result_json = '{' WHERE submission_id = ?",
    idH);
  await qualify(db, deps);
  await del(db, deps, `/v1/submissions/${idR}`, tokens[2]);
  const rows = await db.all(
    `SELECT s.submission_id AS id, s.eligibility AS elig,
            COUNT(h.id) AS n,
            (SELECT hh.to_state FROM eligibility_history hh
             WHERE hh.submission_id = s.submission_id
             ORDER BY hh.id DESC LIMIT 1) AS last_hist
     FROM submissions s LEFT JOIN eligibility_history h
       ON h.submission_id = s.submission_id
     GROUP BY s.submission_id`);
  const byId = Object.fromEntries(rows.map((r) => [r.id, r]));
  assert.deepEqual([byId[idP].elig, byId[idP].n, byId[idP].last_hist],
    ["PUBLISHED", 4, "PUBLISHED"]);
  assert.deepEqual([byId[idR].elig, byId[idR].last_hist],
    ["REVOKED", "REVOKED"]);
  assert.deepEqual([byId[idH].elig, byId[idH].n, byId[idH].last_hist],
    ["SUBMITTED", 1, "SUBMITTED"]);
});

// 16. published_at set exactly on PUBLISHED rows.
test("GT-16: published_at consistency across states", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [idP, idH] = await submitN(db, deps, tokens, 2);
  await db.run("UPDATE submissions SET result_json = '{' WHERE submission_id = ?",
    idH);
  await qualify(db, deps);
  const pub = await db.get(
    "SELECT published_at FROM submissions WHERE submission_id = ?", idP);
  assert.ok(pub.published_at !== null);
  assert.match(pub.published_at, AT_ISO);
  const held = await db.get(
    "SELECT eligibility, published_at FROM submissions WHERE submission_id = ?",
    idH);
  assert.equal(held.eligibility, "SUBMITTED");
  assert.equal(held.published_at, null);
  await del(db, deps, `/v1/submissions/${idP}`, tokens[1]);
  const rev = await db.get(
    "SELECT published_at FROM submissions WHERE submission_id = ?", idP);
  assert.ok(rev.published_at !== null,
    "revoke keeps the original publish stamp");
});

// 17+18. Quorum and cohort semantics ride the trigger path.
test("GT-17: thin cohorts stay suppressed on trigger path", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 2);
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "insufficient-evidence");
  assert.equal(agg.body.groups[0].aggregates, null);
});

test("GT-18: exact-cohort separation on trigger path", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await submitN(db, deps, tokens, 5);
  await submitN(db, deps, tokens, 2, {
    workload: { id: "edgefit-prompt-v1", prompt_sha256: PROMPT_B,
      prompt_chars: 100 },
  });
  await qualify(db, deps);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups.length, 2);
  const byN = Object.fromEntries(
    agg.body.groups.map((g) => [g.n, g.status]));
  assert.equal(byN[5], "published");
  assert.equal(byN[2], "insufficient-evidence");
});

// 19. v1 without 002 cannot audit: pins the requires-002 contract,
// then 002 restores full audit on the same database.
test("GT-19: pre-002 database loses audit; 002 restores it", async (t) => {
  const db = openDatabase(":memory:");
  t.after(() => db.close());
  await initSchema(db);
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [idV1] = await submitN(db, deps, tokens, 1);
  await qualify(db, deps);
  assert.equal(await eligibilityOf(db, idV1), "PUBLISHED");
  assert.deepEqual(await trailOf(db, idV1), ["SUBMITTED"],
    "code without migration 002 cannot audit: deploy 002 first");
  await applyMigrations(db);
  const [idV2] = await submitN(db, deps, tokens, 1);
  await qualify(db, deps);
  assert.deepEqual(await trailOf(db, idV2),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
});

// 20. Repeat application: one trigger, data preserved, audit works.
test("GT-20: migration chain is idempotent with data", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const ids = await submitN(db, deps, tokens, 2);
  await applyMigrations(db);
  await applyMigrations(db);
  const triggers = await db.all(
    "SELECT name FROM sqlite_master WHERE type = 'trigger'");
  assert.deepEqual(triggers.map((r) => r.name),
    ["trg_eligibility_audit"]);
  assert.equal((await db.get(
    "SELECT COUNT(*) AS n FROM submissions")).n, 2);
  await qualify(db, deps);
  for (const id of ids) {
    assert.deepEqual(await trailOf(db, id),
      ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"], id);
  }
});

// 21. Static guards on the 002 file: splitter safety, REVOKED
// exclusion, and the reason CASE stay exactly as reviewed.
test("GT-21: 002 file keeps its reviewed shape", async () => {
  assert.ok(MIGRATION_002.includes(
    "CREATE TRIGGER IF NOT EXISTS trg_eligibility_audit"));
  assert.ok(MIGRATION_002.includes("AFTER UPDATE OF eligibility"));
  // FREE-05 unified audit: the trigger covers REVOKED (no
  // exclusion term) with the exact withdrawal reason. This
  // replaces the FREE-04 exclusion assertion deliberately.
  assert.ok(!MIGRATION_002.includes("<> 'REVOKED'"),
    "no REVOKED exclusion: withdrawal audits via trigger");
  assert.ok(MIGRATION_002.includes(
    "WHEN 'VALIDATED' THEN 'schema recheck passed'"));
  assert.ok(MIGRATION_002.includes(
    "WHEN 'QUALIFIED' THEN 'anomaly suite passed'"));
  assert.ok(MIGRATION_002.includes(
    "WHEN 'PUBLISHED' THEN 'consent current; published'"));
  assert.ok(MIGRATION_002.includes(
    "WHEN 'REVOKED' THEN 'consent withdrawn by owner'"));
  const code = MIGRATION_002.split("\n")
    .filter((line) => !line.trimStart().startsWith("--"))
    .join("\n");
  assert.equal(code.split("INSERT").length - 1, 1,
    "single INSERT body for the D1 migration splitter");
  assert.equal(code.split("END;").length - 1, 1,
    "no inner block terminator");
  assert.ok(code.trimEnd().endsWith("END;"));
  assert.ok(code.includes("BEGIN"));
  assert.ok(!/[^A-Z]begin[^A-Z]/.test(code), "UPPERCASE BEGIN only");
  assert.ok(!/[^A-Z]end;/.test(code), "UPPERCASE END only");
  assert.equal(MIGRATION_002.split("END;").length - 1, 1,
    "comments never spell the block terminator");
});

// 22. Fetch contract preserved end to end on trigger-ful DBs.
test("GT-22: submit/status/aggregates/withdraw round trip", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const envelope = makeEnvelope(makeResult(freshRun()));
  const fresh = await handleRequest({ db, deps, nowMs: NOW,
    method: "POST", path: "/v1/submissions", query: {},
    headers: { authorization: `Bearer ${tokens[1]}` },
    bodyText: JSON.stringify(envelope) });
  assert.equal(fresh.status, 201);
  assert.equal(fresh.body.eligibility, "SUBMITTED");
  assert.equal(fresh.body.duplicate, false);
  const id = fresh.body.submission_id;
  const dupe = await handleRequest({ db, deps, nowMs: NOW,
    method: "POST", path: "/v1/submissions", query: {},
    headers: { authorization: `Bearer ${tokens[1]}` },
    bodyText: JSON.stringify(envelope) });
  assert.equal(dupe.status, 200);
  assert.equal(dupe.body.duplicate, true);
  assert.equal(dupe.body.submission_id, id);
  assert.equal(dupe.body.eligibility, "SUBMITTED");
  let seen = await get(db, deps, `/v1/submissions/${id}`, {},
    tokens[1]);
  assert.equal(seen.status, 200);
  assert.equal(seen.body.eligibility, "SUBMITTED");
  await qualify(db, deps);
  seen = await get(db, deps, `/v1/submissions/${id}`, {}, tokens[1]);
  assert.equal(seen.body.eligibility, "PUBLISHED");
  assert.deepEqual(
    seen.body.eligibility_history.map((h) => h.to_state),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  const agg = await get(db, deps, "/v1/benchmarks/aggregates",
    { model_sha: MODEL_A });
  assert.equal(agg.body.groups[0].status, "insufficient-evidence");
  const gone = await del(db, deps, `/v1/submissions/${id}`, tokens[1]);
  assert.equal(gone.status, 200);
  assert.equal(gone.body.eligibility, "REVOKED");
  const foreign = await get(db, deps, `/v1/submissions/${id}`, {},
    tokens[2]);
  assert.equal(foreign.status, 404);
});

// 23. Round-trip budget: one full promotion costs 3 batches + 4 reads.
test("GT-23: tick round trips stay bounded (3 batches + 4 reads)",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    await submitN(db, deps, tokens, 1);
    const calls = { get: 0, all: 0, batch: 0 };
    const counting = { ...db,
      get: async (...a) => { calls.get += 1; return db.get(...a); },
      all: async (...a) => { calls.all += 1; return db.all(...a); },
      batch: async (s) => { calls.batch += 1; return db.batch(s); } };
    const sum = await scheduledTick({ db: counting, deps, nowMs: NOW,
      log: () => {}, logErr: () => {} });
    assert.equal(sum.promoted, 3);
    assert.deepEqual(calls, { get: 3, all: 1, batch: 3 });
  });

// 24. The live trigger body audits only; no sensitive columns.
test("GT-24: trigger body names no evidence or secrets", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const row = await db.get(
    "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = 'trg_eligibility_audit'");
  assert.ok(row && row.sql.length > 100);
  for (const banned of ["result_json", "token", "install_id",
    "content_hash", "secret", "salt", "scopes", "measurements"]) {
    assert.ok(!row.sql.includes(banned), `trigger names ${banned}`);
  }
  for (const needed of ["submission_id", "from_state", "to_state",
    "reason", "NEW.eligibility", "OLD.eligibility"]) {
    assert.ok(row.sql.includes(needed), `trigger lacks ${needed}`);
  }
});

// 25. D1 reports changes=2 for a trigger-firing UPDATE (observed
// on the FREE-04 scratch probe); node:sqlite reports 1. Success
// must mean > 0 on either engine, 0 for a CAS loser.
test("GT-25: D1-style changes=2 counts as CAS success", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const d1metered = { ...db,
    batch: async (s) => {
      const out = await db.batch(s);
      return out.map((r, i) => {
        if (s.length === 1 && s[i] &&
            /UPDATE submissions SET eligibility/.test(s[i].sql) &&
            r.changes === 1) {
          return { ...r, changes: 2 };
        }
        return r;
      });
    } };
  const rep = await qualify(d1metered, deps);
  assert.equal(rep.promoted.length, 3);
  assert.equal(rep.deferred.length, 0);
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
});

// N3 (README-mandated 002 case): 002 applies through the D1
// adapter exec path on a populated v1 database; audit works
// through the adapter afterwards. Lives here instead of
// g2b-d1.test.js to keep the migration coverage in one file.
test("G2B-D-N3: 002 applies via D1 exec with data preserved",
  async (t) => {
    const mock = openMockD1Binding();
    t.after(() => mock.close());
    const db = openD1(mock.binding);
    await initSchema(db);
    const deps = makeDeps();
    await seedInstall(db, deps, "n3-install-1", "n3-token-1");
    const id = await submitValid(db, deps, "n3-token-1", freshRun());
    await db.exec(MIGRATION_002);
    await db.exec(MIGRATION_002);
    const triggers = await db.all(
      "SELECT name FROM sqlite_master WHERE type = 'trigger'");
    assert.deepEqual(triggers.map((r) => r.name),
      ["trg_eligibility_audit"]);
    assert.equal((await db.get(
      "SELECT eligibility FROM submissions WHERE submission_id = ?",
      id)).eligibility, "SUBMITTED");
    const rep = await qualify(db, deps);
    assert.equal(rep.promoted.length, 3);
    assert.deepEqual(await trailOf(db, id),
      ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  });

// ---------- FREE-05 unified withdrawal audit (RW-01..RW-08) ----------

// RW-01. Forced concurrent double-DELETE: one transition, one row.
test("RW-01: concurrent authorized DELETEs audit exactly once",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    const [id] = await submitN(db, deps, tokens, 1);
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
    assert.equal(a.status, 200);
    assert.equal(b.status, 200);
    assert.equal(a.body.eligibility, "REVOKED");
    assert.equal(b.body.eligibility, "REVOKED");
    assert.equal(await eligibilityOf(db, id), "REVOKED");
    const rows = await fullTrailOf(db, id);
    assert.deepEqual(rows.map((r) => [r.f, r.t, r.r]), [
      [null, "SUBMITTED", "received"],
      ["SUBMITTED", "REVOKED", "consent withdrawn by owner"],
    ]);
    for (const r of rows) assert.match(r.at, AT_ISO, JSON.stringify(r));
  });

// RW-02. D1 reports changes=2 for a trigger-firing revoke UPDATE
// (FREE-05 probe); node reports 1. Both mean success. GREEN guard:
// the predicate change is behavior-preserving insurance.
test("RW-02: D1-metered revoke UPDATE counts as success", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const d1metered = { ...db,
    batch: async (s) => {
      const out = await db.batch(s);
      return out.map((r, i) => {
        if (s[i] && /SET eligibility = 'REVOKED'/.test(s[i].sql) &&
            r.changes === 1) {
          return { ...r, changes: 2 };
        }
        return r;
      });
    } };
  const gone = await del(d1metered, deps, `/v1/submissions/${id}`,
    tokens[1]);
  assert.equal(gone.status, 200);
  assert.equal(gone.body.eligibility, "REVOKED");
  assert.deepEqual(await trailOf(db, id), ["SUBMITTED", "REVOKED"]);
});

// RW-03. Dead audit table: revoke fails closed, nothing applies.
test("RW-03: revoke with dead audit table is a clean 500", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  await db.exec("DROP TABLE eligibility_history");
  const res = await del(db, deps, `/v1/submissions/${id}`, tokens[1]);
  assert.equal(res.status, 500);
  assert.equal(res.body.error.code, "internal");
  assert.equal(await eligibilityOf(db, id), "SUBMITTED");
});

// RW-04. Database failure during revoke: sanitized, unchanged.
test("RW-04: revoke during outage holds state and sanitizes",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    const [id] = await submitN(db, deps, tokens, 1);
    const down = { ...db,
      get: async (...a) => {
        if (down.fail) throw new Error("RW04_FAKE down");
        return db.get(...a);
      },
      batch: async () => { throw new Error("RW04_FAKE down"); },
      fail: false,
    };
    down.fail = true;
    const res = await del(down, deps, `/v1/submissions/${id}`,
      tokens[1]);
    assert.equal(res.status, 500);
    assert.equal(res.body.error.code, "internal");
    assert.ok(!JSON.stringify(res.body).includes("RW04_FAKE"));
    down.fail = false;
    assert.equal(await eligibilityOf(db, id), "SUBMITTED");
    assert.deepEqual(await trailOf(db, id), ["SUBMITTED"]);
  });

// RW-05. Revoke lands between S->V commit and the V->Q attempt:
// terminal revoke wins, partial trail stays exact.
test("RW-05: revoke between transitions freezes the trail exactly",
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
            (s[0].params ?? [])[0] === "QUALIFIED") {
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
    assert.equal(sum.promoted, 1);
    assert.ok(sum.deferred >= 1);
    assert.equal(await eligibilityOf(db, id), "REVOKED");
    const rows = await fullTrailOf(db, id);
    assert.deepEqual(rows.map((r) => [r.f, r.t, r.r]), [
      [null, "SUBMITTED", "received"],
      ["SUBMITTED", "VALIDATED", "schema recheck passed"],
      ["VALIDATED", "REVOKED", "consent withdrawn by owner"],
    ]);
  });

// RW-06. Withdrawal authentication boundary: bad and revoked
// tokens fail closed without touching state.
test("RW-06: unauthorized DELETE changes nothing", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const [id] = await submitN(db, deps, tokens, 1);
  const bad = await del(db, deps, `/v1/submissions/${id}`,
    "wrong-token-999");
  assert.equal(bad.status, 401);
  await db.run("UPDATE installs SET revoked_at = ? WHERE install_id = ?",
    new Date(NOW).toISOString(), "trig-install-1");
  const revokedTok = await del(db, deps, `/v1/submissions/${id}`,
    tokens[1]);
  assert.equal(revokedTok.status, 401);
  assert.equal(await eligibilityOf(db, id), "SUBMITTED");
  assert.deepEqual(await trailOf(db, id), ["SUBMITTED"]);
});

// RW-07. Revoke + qualify interplay through the D1 adapter.
test("RW-07: adapter path revokes and freezes exactly", async (t) => {
  const mock = openMockD1Binding();
  t.after(() => mock.close());
  const db = openD1(mock.binding);
  await initSchema(db);
  await applyMigrations(db);
  const deps = makeDeps();
  await seedInstall(db, deps, "rw-install-1", "rw-token-1");
  const id = await submitValid(db, deps, "rw-token-1", freshRun());
  const rep = await qualify(db, deps);
  assert.equal(rep.promoted.length, 3);
  const gone = await del(db, deps, `/v1/submissions/${id}`,
    "rw-token-1");
  assert.equal(gone.body.eligibility, "REVOKED");
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED", "REVOKED"]);
  const again = await qualify(db, deps);
  assert.equal(again.promoted.length, 0);
  assert.deepEqual(await trailOf(db, id),
    ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED", "REVOKED"]);
});

// RW-08. Sequential duplicate DELETE: second call is a no-op read
// (no batch issued) and the audit keeps exactly one row.
test("RW-08: sequential duplicate DELETE issues no second write",
  async (t) => {
    const db = await openTestDb();
    t.after(() => db.close());
    const deps = makeDeps();
    const tokens = await seed3(db, deps);
    const [id] = await submitN(db, deps, tokens, 1);
    const first = await del(db, deps, `/v1/submissions/${id}`,
      tokens[1]);
    assert.equal(first.status, 200);
    let batches = 0;
    const counting = { ...db,
      batch: async (s) => { batches += 1; return db.batch(s); } };
    const second = await del(counting, deps,
      `/v1/submissions/${id}`, tokens[1]);
    assert.equal(second.status, 200);
    assert.equal(second.body.eligibility, "REVOKED");
    assert.equal(batches, 0);
    const rows = await fullTrailOf(db, id);
    assert.deepEqual(rows.map((r) => [r.f, r.t, r.r]), [
      [null, "SUBMITTED", "received"],
      ["SUBMITTED", "REVOKED", "consent withdrawn by owner"],
    ]);
  });
