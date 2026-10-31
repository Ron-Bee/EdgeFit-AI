// G1B RED: corrupt persisted records, failure propagation, rate limits (D6/D7).
import test from "node:test";
import assert from "node:assert/strict";
import {
  NOW, makeDeps, openTestDb, seedInstall, makeResult, makeEnvelope, post,
  submitValid, qualify,
} from "./helpers.js";

let runCounter = 0;

function setup() {
  const deps = makeDeps();
  const db = openTestDb();
  seedInstall(db, deps, "install-1", "token-one");
  return { deps, db };
}

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-G1BR${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
}

function eligibility(db, id) {
  return db.get("SELECT eligibility FROM submissions WHERE submission_id = ?", id)
    .eligibility;
}

function tappedDb(db, hooks = {}) {
  return {
    get: db.get.bind(db),
    all: db.all.bind(db),
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

test("G1B-F1: corrupt VALIDATED result_json holds without aborting the pass", () => {
  const { deps, db } = setup();
  const bad = submitValid(db, deps, "token-one", freshRun());
  const good = submitValid(db, deps, "token-one", freshRun());
  // Test-only surgery: a row that reached VALIDATED, then rotted on disk.
  db.run("UPDATE submissions SET eligibility = 'VALIDATED', result_json = '{corrupt' WHERE submission_id = ?",
    bad);
  db.run(
    `INSERT INTO eligibility_history (submission_id, from_state, to_state, reason, at)
     VALUES (?, 'SUBMITTED', 'VALIDATED', 'test surgery', ?)`,
    bad, new Date(NOW).toISOString(),
  );
  let report = null;
  let threw = null;
  try {
    report = qualify(db, deps);
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `qualification threw: ${threw && threw.message}`);
  assert.equal(eligibility(db, bad), "VALIDATED");
  assert.ok(report.held.some((h) => h.submission_id === bad));
  assert.equal(eligibility(db, good), "PUBLISHED");
  db.close();
});

test("G1B-F2: corrupt SUBMITTED consent_scopes holds without throwing", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one", freshRun());
  db.run("UPDATE submissions SET consent_scopes = '{bad' WHERE submission_id = ?", id);
  let report = null;
  let threw = null;
  try {
    report = qualify(db, deps);
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `qualification threw: ${threw && threw.message}`);
  assert.equal(eligibility(db, id), "SUBMITTED");
  assert.ok(report.held.some((h) => h.submission_id === id));
  db.close();
});

test("G1B-F3: corrupt QUALIFIED consent_scopes holds without throwing", () => {
  const { deps, db } = setup();
  const id = submitValid(db, deps, "token-one", freshRun());
  qualify(db, deps);
  assert.equal(eligibility(db, id), "PUBLISHED");
  // Test-only surgery: consent field rotted after the row qualified.
  db.run(
    "UPDATE submissions SET eligibility = 'QUALIFIED', consent_scopes = '{bad', published_at = NULL WHERE submission_id = ?",
    id,
  );
  let report = null;
  let threw = null;
  try {
    report = qualify(db, deps);
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `qualification threw: ${threw && threw.message}`);
  assert.equal(eligibility(db, id), "QUALIFIED");
  assert.ok(report.held.some((h) => h.submission_id === id));
  db.close();
});

test("G1B-F4: mid-write database failure rolls back with a sanitized 500", () => {
  const { deps, db } = setup();
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (/INSERT INTO eligibility_history/i.test(sql)) {
        throw new Error("CANARY_MARKER_9z9z SQLITE_FAKE x'; DROP TABLE submissions; --");
      }
    },
  });
  let res = null;
  let threw = null;
  try {
    res = post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 500);
  assert.equal(res.body.error.code, "internal");
  const serialized = JSON.stringify(res.body);
  assert.ok(!serialized.includes("CANARY_MARKER_9z9z"));
  assert.ok(!serialized.includes("DROP TABLE"));
  assert.ok(!serialized.includes("SQLITE"));
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 0);
  assert.equal(db.get("SELECT COUNT(*) AS n FROM eligibility_history").n, 0);
  db.close();
});

test("G1B-F5: database lock maps to 503 with Retry-After", () => {
  const { deps, db } = setup();
  const tapped = tappedDb(db, {
    beforeRun(sql) {
      if (/^INSERT INTO submissions/i.test(sql)) {
        const err = new Error("database is locked");
        err.code = "SQLITE_BUSY";
        throw err;
      }
    },
  });
  let res = null;
  let threw = null;
  try {
    res = post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 503);
  assert.equal(res.body.error.code, "temporarily_unavailable");
  assert.equal(res.headers["retry-after"], "1");
  assert.equal(db.get("SELECT COUNT(*) AS n FROM submissions").n, 0);
  db.close();
});

test("G1B-F6: quota rejection carries Retry-After to next UTC midnight", () => {
  const deps = makeDeps({ quotaPerDay: 2 });
  const db = openTestDb();
  seedInstall(db, deps, "install-1", "token-one");
  submitValid(db, deps, "token-one", freshRun());
  submitValid(db, deps, "token-one", freshRun());
  const res = post(db, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  assert.equal(res.status, 429);
  assert.equal(res.body.error.code, "quota_exceeded");
  const expectedSecs = Math.ceil(
    (Date.parse("2026-10-31T00:00:00Z") - NOW) / 1000);
  assert.equal(res.headers["retry-after"], String(expectedSecs));
  db.close();
});

test("G1B-F7: unknown tier/synthetic markers never alter the pipeline (guard)", () => {
  const { deps, db } = setup();
  const envelope = makeEnvelope(makeResult(freshRun()));
  envelope.synthetic = true;
  envelope.result.tier = "synthetic_example";
  const res = post(db, deps, "token-one", envelope);
  assert.equal(res.status, 201);
  assert.equal(res.body.eligibility, "SUBMITTED");
  qualify(db, deps);
  assert.equal(eligibility(db, res.body.submission_id), "PUBLISHED");
  const trail = db.all(
    "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    res.body.submission_id,
  ).map((r) => r.to_state);
  assert.deepEqual(trail, ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  db.close();
});

test("G1B-F8: every PUBLISHED row owns a full promotion chain (guard)", () => {
  const { deps, db } = setup();
  for (let i = 0; i < 3; i++) submitValid(db, deps, "token-one", freshRun());
  qualify(db, deps);
  const rows = db.all("SELECT submission_id FROM submissions WHERE eligibility = 'PUBLISHED'");
  assert.equal(rows.length, 3);
  for (const { submission_id } of rows) {
    const trail = db.all(
      "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
      submission_id,
    ).map((r) => r.to_state);
    assert.deepEqual(trail, ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  }
  db.close();
});
