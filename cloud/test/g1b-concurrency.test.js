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

function openShared(t) {
  fileCounter += 1;
  // Repo-local scratch dir (works in sandboxes without a shared tmp);
  // every file is unlinked in t.after, verified by git status.
  const dir = join(dirname(fileURLToPath(import.meta.url)), ".tmp-g1b");
  mkdirSync(dir, { recursive: true });
  const path = join(dir, `shared-${process.pid}-${fileCounter}.db`);
  const a = openDatabase(path);
  initSchema(a);
  const b = openDatabase(path);
  a.exec("PRAGMA busy_timeout = 5000");
  b.exec("PRAGMA busy_timeout = 100");
  t.after(() => {
    try { a.close(); } catch { /* ignore */ }
    try { b.close(); } catch { /* ignore */ }
    try { unlinkSync(path); } catch { /* ignore */ }
  });
  return { a, b };
}

const TOKENS = { 1: "token-one", 2: "token-two", 3: "token-three" };

function seed3(db, deps) {
  for (let i = 1; i <= 3; i++) seedInstall(db, deps, `install-${i}`, TOKENS[i]);
}

// Forwards the adapter interface; hooks observe (never rewrite) statements.
// Batch hooks fire per statement up front, in order, before the atomic
// unit executes: a hook's competing write models a batch that serialized
// first. Deterministic: no lock timing involved.
function tappedDb(db, hooks = {}) {
  return {
    get: db.get.bind(db),
    all(sql, ...params) {
      const rows = db.all(sql, ...params);
      if (hooks.afterAll) hooks.afterAll(sql, rows);
      return rows;
    },
    run(sql, ...params) {
      if (hooks.beforeRun) hooks.beforeRun(sql, params);
      return db.run(sql, ...params);
    },
    batch(statements) {
      if (hooks.beforeRun) {
        for (const s of statements) hooks.beforeRun(s.sql, s.params ?? []);
      }
      return db.batch(statements);
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

function tryPost(db, deps, token, envelope) {
  try {
    return { res: post(db, deps, token, envelope), threw: null };
  } catch (e) {
    return { res: null, threw: e };
  }
}

test("G1B-T6: revocation during qualification stays terminal", (t) => {
  const deps = makeDeps();
  const { a, b } = openShared(t);
  seed3(a, deps);
  const id = submitValid(a, deps, "token-one", freshRun());
  let fired = false;
  const tapped = tappedDb(a, {
    afterAll(sql) {
      if (fired || !sql.includes("eligibility IN (")) return;
      fired = true;
      // A concurrent owner DELETE commits between snapshot and transition.
      b.run("UPDATE submissions SET eligibility = 'REVOKED' WHERE submission_id = ?", id);
      b.run(
        `INSERT INTO eligibility_history
           (submission_id, from_state, to_state, reason, at)
         VALUES (?, 'SUBMITTED', 'REVOKED', 'concurrent test revoke', ?)`,
        id, new Date(NOW).toISOString(),
      );
    },
  });
  const report = qualify(tapped, deps);
  assert.ok(fired, "interleave hook never fired");
  assert.equal(
    a.get("SELECT eligibility FROM submissions WHERE submission_id = ?", id).eligibility,
    "REVOKED",
  );
  const trail = a.all(
    "SELECT from_state, to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    id,
  );
  assert.ok(trail.every((r) => r.from_state !== "REVOKED"),
    `resurrection in history: ${JSON.stringify(trail)}`);
  assert.ok(report.promoted.every((p) => p.submission_id !== id));
});

test("G1B-T7: concurrent uploads at the quota edge admit exactly quota", () => {
  const deps = makeDeps({ quotaPerDay: 3 });
  const db = openTestDb();
  seed3(db, deps);
  submitValid(db, deps, "token-one", freshRun());
  submitValid(db, deps, "token-one", freshRun());
  let bResult = null;
  let fired = false;
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (fired || !/^INSERT INTO submissions/i.test(sql)) return;
      fired = true;
      // A competitor's batch serialized first and took the last slot.
      // (Single-writer engines serialize batches; any order reduces to
      // one batch winning. Lock-timing contention is covered by G1B-F5.)
      bResult = post(db, deps, "token-one",
        makeEnvelope(makeResult(freshRun())));
    },
  });
  const aRes = post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  assert.ok(fired, "interleave hook never fired");
  assert.equal(bResult.status, 201);
  assert.equal(aRes.status, 429);
  assert.ok(aRes.headers && aRes.headers["retry-after"], "missing Retry-After");
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 3);
  // The rejected claim was compensated in-batch: the counter is exact.
  assert.equal(db.get(
    "SELECT count FROM daily_counts WHERE install_id = 'install-1'").count, 3);
  db.close();
});

test("G1B-T7b: racing duplicate content resolves to one row", () => {
  const deps = makeDeps();
  const db = openTestDb();
  seed3(db, deps);
  const envelope = makeEnvelope(makeResult(freshRun()));
  let bResult = null;
  let fired = false;
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (fired || !/^INSERT INTO submissions/i.test(sql)) return;
      fired = true;
      // A competitor committed the identical content first.
      bResult = post(db, deps, "token-two", envelope);
    },
  });
  const { res, threw } = tryPost(tapped, deps, "token-one", envelope);
  assert.ok(fired, "interleave hook never fired");
  assert.equal(bResult.status, 201);
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 200);
  assert.equal(res.body.duplicate, true);
  assert.equal(res.body.submission_id, bResult.body.submission_id);
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 1);
  // The loser's rolled-back claim cost nothing.
  assert.equal(db.get(
    "SELECT count FROM daily_counts WHERE install_id = 'install-2'").count, 1);
  assert.equal(db.get(
    "SELECT COUNT(*) AS n FROM daily_counts WHERE install_id = 'install-1'").n, 0);
  db.close();
});

test("G1B-T7c: UNIQUE conflicts without a committed row stay retryable", () => {
  const deps = makeDeps();
  const db = openTestDb();
  seed3(db, deps);
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (/^INSERT INTO submissions/i.test(sql)) {
        // A UNIQUE failure with no committed winner (vanishing race).
        throw new Error("UNIQUE constraint failed: submissions.content_hash");
      }
    },
  });
  const { res, threw } = tryPost(tapped, deps, "token-one",
    makeEnvelope(makeResult(freshRun())));
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 503);
  assert.equal(res.body.error.code, "temporarily_unavailable");
  assert.equal(res.headers["retry-after"], "1");
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 0);
  db.close();
});

test("G1B-T7d: submission-id collision retries with a fresh id", () => {
  const ids = ["SUB-IDC-1", "SUB-IDC-1", "SUB-IDC-2"];
  let n = 0;
  const deps = makeDeps({ newId: () => ids[n++] ?? `SUB-IDC-F${n}` });
  const db = openTestDb();
  seed3(db, deps);
  const first = post(db, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  assert.equal(first.status, 201);
  const { res, threw } = tryPost(db, deps, "token-one",
    makeEnvelope(makeResult(freshRun())));
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 201);
  assert.equal(res.body.submission_id, "SUB-IDC-2");
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 2);
  db.close();
});

test("G1B-T7e: persistent id collision ends in a sanitized 500", () => {
  const deps = makeDeps({ newId: () => "SUB-SAME-9" });
  const db = openTestDb();
  seed3(db, deps);
  assert.equal(post(db, deps, "token-one",
    makeEnvelope(makeResult(freshRun()))).status, 201);
  const { res, threw } = tryPost(db, deps, "token-one",
    makeEnvelope(makeResult(freshRun())));
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 500);
  assert.equal(res.body.error.code, "internal");
  assert.ok(!JSON.stringify(res.body).includes("SUB-SAME-9"));
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 1);
  db.close();
});
