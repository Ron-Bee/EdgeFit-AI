// G1B RED: concurrent mutation behavior (D4/D5/D6) via a deterministic
// interleave harness: two connections sharing one temp-file database,
// plus statement hooks that commit a competitor's write mid-operation.
import test from "node:test";
import assert from "node:assert/strict";
import { join, dirname } from "node:path";
import { mkdirSync, unlinkSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { openDatabase, initSchema } from "../db.js";
import {
  NOW, makeDeps, openTestDb, seedInstall, makeResult, makeEnvelope, post,
  submitValid, qualify,
} from "./helpers.js";

let fileCounter = 0;
let runCounter = 0;

async function openShared(t) {
  fileCounter += 1;
  // Repo-local scratch dir (works in sandboxes without a shared tmp);
  // every file is unlinked in t.after, verified by git status.
  const dir = join(dirname(fileURLToPath(import.meta.url)), ".tmp-g1b");
  mkdirSync(dir, { recursive: true });
  const path = join(dir, `shared-${process.pid}-${fileCounter}.db`);
  const a = openDatabase(path);
  await initSchema(a);
  const b = openDatabase(path);
  await a.exec("PRAGMA busy_timeout = 5000");
  await b.exec("PRAGMA busy_timeout = 100");
  t.after(() => {
    try { a.close(); } catch { /* ignore */ }
    try { b.close(); } catch { /* ignore */ }
    try { unlinkSync(path); } catch { /* ignore */ }
  });
  return { a, b };
}

const TOKENS = { 1: "token-one", 2: "token-two", 3: "token-three" };

async function seed3(db, deps) {
  for (let i = 1; i <= 3; i++) await seedInstall(db, deps, `install-${i}`, TOKENS[i]);
}

// Forwards the adapter interface; hooks observe (never rewrite) statements.
// Batch hooks fire per statement up front, in order, before the atomic
// unit executes: a hook's competing write models a batch that serialized
// first. Deterministic: no lock timing involved.
function tappedDb(db, hooks = {}) {
  return {
    get: db.get.bind(db),
    async all(sql, ...params) {
      const rows = await db.all(sql, ...params);
      if (hooks.afterAll) hooks.afterAll(sql, rows);
      return rows;
    },
    async run(sql, ...params) {
      if (hooks.beforeRun) hooks.beforeRun(sql, params);
      return await db.run(sql, ...params);
    },
    async batch(statements) {
      if (hooks.beforeRun) {
        for (const s of statements) await hooks.beforeRun(s.sql, s.params ?? []);
      }
      return await db.batch(statements);
    },
    exec: db.exec.bind(db),
    close: db.close.bind(db),
  };
}

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-G1BX${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
}

async function tryPost(db, deps, token, envelope) {
  try {
    return { res: await post(db, deps, token, envelope), threw: null };
  } catch (e) {
    return { res: null, threw: e };
  }
}

test("G1B-T6: revocation during qualification stays terminal", async (t) => {
  const deps = makeDeps();
  const { a, b } = await openShared(t);
  await seed3(a, deps);
  const id = await submitValid(a, deps, "token-one", freshRun());
  let fired = false;
  const tapped = tappedDb(a, {
    async afterAll(sql) {
      if (fired || !sql.includes("eligibility IN (")) return;
      fired = true;
      // A concurrent owner DELETE commits between snapshot and transition.
      await b.run("UPDATE submissions SET eligibility = 'REVOKED' WHERE submission_id = ?", id);
      await b.run(
        `INSERT INTO eligibility_history
           (submission_id, from_state, to_state, reason, at)
         VALUES (?, 'SUBMITTED', 'REVOKED', 'concurrent test revoke', ?)`,
        id, new Date(NOW).toISOString(),
      );
    },
  });
  const report = await qualify(tapped, deps);
  assert.ok(fired, "interleave hook never fired");
  assert.equal(
    (await a.get("SELECT eligibility FROM submissions WHERE submission_id = ?", id)).eligibility,
    "REVOKED",
  );
  const trail = await a.all(
    "SELECT from_state, to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  );
  assert.ok(trail.every((r) => r.from_state !== "REVOKED"),
    `resurrection in history: ${JSON.stringify(trail)}`);
  assert.ok(report.promoted.every((p) => p.submission_id !== id));
});

test("G1B-T7: concurrent uploads at the quota edge admit exactly quota", async () => {
  const deps = makeDeps({ quotaPerDay: 3 });
  const db = await openTestDb();
  await seed3(db, deps);
  await submitValid(db, deps, "token-one", freshRun());
  await submitValid(db, deps, "token-one", freshRun());
  let bResult = null;
  let fired = false;
  const tapped = tappedDb(db, {
    async beforeRun(sql) {
      if (fired || !/^INSERT INTO submissions/i.test(sql)) return;
      fired = true;
      // A competitor's batch serialized first and took the last slot.
      // (Single-writer engines serialize batches; any order reduces to
      // one batch winning. Lock-timing contention is covered by G1B-F5.)
      bResult = await post(db, deps, "token-one",
        makeEnvelope(makeResult(freshRun())));
    },
  });
  const aRes = await post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  assert.ok(fired, "interleave hook never fired");
  assert.equal(bResult.status, 201);
  assert.equal(aRes.status, 429);
  assert.ok(aRes.headers && aRes.headers["retry-after"], "missing Retry-After");
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM submissions")).n, 3);
  // The rejected claim was compensated in-batch: the counter is exact.
  assert.equal((await db.get(
    "SELECT count FROM daily_counts WHERE install_id = 'install-1'")).count, 3);
  db.close();
});

test("G1B-T7b: racing duplicate content resolves to one row", async () => {
  const deps = makeDeps();
  const db = await openTestDb();
  await seed3(db, deps);
  const envelope = makeEnvelope(makeResult(freshRun()));
  let bResult = null;
  let fired = false;
  const tapped = tappedDb(db, {
    async beforeRun(sql) {
      if (fired || !/^INSERT INTO submissions/i.test(sql)) return;
      fired = true;
      // A competitor committed the identical content first.
      bResult = await post(db, deps, "token-two", envelope);
    },
  });
  const { res, threw } = await tryPost(tapped, deps, "token-one", envelope);
  assert.ok(fired, "interleave hook never fired");
  assert.equal(bResult.status, 201);
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 200);
  assert.equal(res.body.duplicate, true);
  assert.equal(res.body.submission_id, bResult.body.submission_id);
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM submissions")).n, 1);
  // The loser's rolled-back claim cost nothing.
  assert.equal((await db.get(
    "SELECT count FROM daily_counts WHERE install_id = 'install-2'")).count, 1);
  assert.equal((await db.get(
    "SELECT COUNT(*) AS n FROM daily_counts WHERE install_id = 'install-1'")).n, 0);
  db.close();
});

test("G1B-T7c: UNIQUE conflicts without a committed row stay retryable", async () => {
  const deps = makeDeps();
  const db = await openTestDb();
  await seed3(db, deps);
  const tapped = tappedDb(db, {
    async beforeRun(sql) {
      if (/^INSERT INTO submissions/i.test(sql)) {
        // A UNIQUE failure with no committed winner (vanishing race).
        throw new Error("UNIQUE constraint failed: submissions.content_hash");
      }
    },
  });
  const { res, threw } = await tryPost(tapped, deps, "token-one",
    makeEnvelope(makeResult(freshRun())));
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 503);
  assert.equal(res.body.error.code, "temporarily_unavailable");
  assert.equal(res.headers["retry-after"], "1");
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM submissions")).n, 0);
  db.close();
});

test("G1B-T7d: submission-id collision retries with a fresh id", async () => {
  const ids = ["SUB-IDC-1", "SUB-IDC-1", "SUB-IDC-2"];
  let n = 0;
  const deps = makeDeps({ newId: () => ids[n++] ?? `SUB-IDC-F${n}` });
  const db = await openTestDb();
  await seed3(db, deps);
  const first = await post(db, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  assert.equal(first.status, 201);
  const { res, threw } = await tryPost(db, deps, "token-one",
    makeEnvelope(makeResult(freshRun())));
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 201);
  assert.equal(res.body.submission_id, "SUB-IDC-2");
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM submissions")).n, 2);
  db.close();
});

test("G1B-T7e: persistent id collision ends in a sanitized 500", async () => {
  const deps = makeDeps({ newId: () => "SUB-SAME-9" });
  const db = await openTestDb();
  await seed3(db, deps);
  assert.equal((await post(db, deps, "token-one",
    makeEnvelope(makeResult(freshRun())))).status, 201);
  const { res, threw } = await tryPost(db, deps, "token-one",
    makeEnvelope(makeResult(freshRun())));
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 500);
  assert.equal(res.body.error.code, "internal");
  assert.ok(!JSON.stringify(res.body).includes("SUB-SAME-9"));
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM submissions")).n, 1);
  db.close();
});
