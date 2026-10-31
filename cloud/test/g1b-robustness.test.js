// G1B RED: corrupt persisted records, failure propagation, rate limits (D6/D7).
import test from "node:test";
import assert from "node:assert/strict";
import {
  NOW, makeDeps, openTestDb, seedInstall, makeResult, makeEnvelope, post,
  submitValid, qualify,
} from "./helpers.js";

let runCounter = 0;

async function setup() {
  const deps = makeDeps();
  const db = await openTestDb();
  await seedInstall(db, deps, "install-1", "token-one");
  return { deps, db };
}

function freshRun() {
  runCounter += 1;
  return {
    run_id: `BENCH-G1BR${runCounter}`,
    collected_at_utc: new Date(NOW - runCounter * 60000).toISOString(),
  };
}

async function eligibility(db, id) {
  return (await db.get("SELECT eligibility FROM submissions WHERE submission_id = ?", id))
    .eligibility;
}

function tappedDb(db, hooks = {}) {
  return {
    get: db.get.bind(db),
    all: db.all.bind(db),
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

test("G1B-F1: corrupt VALIDATED result_json holds without aborting the pass", async () => {
  const { deps, db } = await setup();
  const bad = await submitValid(db, deps, "token-one", freshRun());
  const good = await submitValid(db, deps, "token-one", freshRun());
  // Test-only surgery: a row that reached VALIDATED, then rotted on disk.
  await db.run("UPDATE submissions SET eligibility = 'VALIDATED', result_json = '{corrupt' WHERE submission_id = ?",
    bad);
  await db.run(
    `INSERT INTO eligibility_history (submission_id, from_state, to_state, reason, at)
     VALUES (?, 'SUBMITTED', 'VALIDATED', 'test surgery', ?)`,
    bad, new Date(NOW).toISOString(),
  );
  let report = null;
  let threw = null;
  try {
    report = await qualify(db, deps);
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `qualification threw: ${threw && threw.message}`);
  assert.equal(await eligibility(db, bad), "VALIDATED");
  assert.ok(report.held.some((h) => h.submission_id === bad));
  assert.equal(await eligibility(db, good), "PUBLISHED");
  db.close();
});

test("G1B-F2: corrupt SUBMITTED consent_scopes holds without throwing", async () => {
  const { deps, db } = await setup();
  const id = await submitValid(db, deps, "token-one", freshRun());
  await db.run("UPDATE submissions SET consent_scopes = '{bad' WHERE submission_id = ?", id);
  let report = null;
  let threw = null;
  try {
    report = await qualify(db, deps);
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `qualification threw: ${threw && threw.message}`);
  assert.equal(await eligibility(db, id), "SUBMITTED");
  assert.ok(report.held.some((h) => h.submission_id === id));
  db.close();
});

test("G1B-F3: corrupt QUALIFIED consent_scopes holds without throwing", async () => {
  const { deps, db } = await setup();
  const id = await submitValid(db, deps, "token-one", freshRun());
  await qualify(db, deps);
  assert.equal(await eligibility(db, id), "PUBLISHED");
  // Test-only surgery: consent field rotted after the row qualified.
  await db.run(
    "UPDATE submissions SET eligibility = 'QUALIFIED', consent_scopes = '{bad', published_at = NULL WHERE submission_id = ?",
    id,
  );
  let report = null;
  let threw = null;
  try {
    report = await qualify(db, deps);
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `qualification threw: ${threw && threw.message}`);
  assert.equal(await eligibility(db, id), "QUALIFIED");
  assert.ok(report.held.some((h) => h.submission_id === id));
  db.close();
});

test("G1B-F4: mid-write database failure rolls back with a sanitized 500", async () => {
  const { deps, db } = await setup();
  const tapped = tappedDb(db, {
    async beforeRun(sql) {
      if (/INSERT INTO eligibility_history/i.test(sql)) {
        throw new Error("CANARY_MARKER_9z9z SQLITE_FAKE x'; DROP TABLE submissions; --");
      }
    },
  });
  let res = null;
  let threw = null;
  try {
    res = await post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
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
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM submissions")).n, 0);
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM eligibility_history")).n, 0);
  db.close();
});

test("G1B-F5: database lock maps to 503 with Retry-After", async () => {
  const { deps, db } = await setup();
  const tapped = tappedDb(db, {
    async beforeRun(sql) {
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
    res = await post(tapped, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 503);
  assert.equal(res.body.error.code, "temporarily_unavailable");
  assert.equal(res.headers["retry-after"], "1");
  assert.equal((await db.get("SELECT COUNT(*) AS n FROM submissions")).n, 0);
  db.close();
});

test("G1B-F6: quota rejection carries Retry-After to next UTC midnight", async () => {
  const deps = makeDeps({ quotaPerDay: 2 });
  const db = await openTestDb();
  await seedInstall(db, deps, "install-1", "token-one");
  await submitValid(db, deps, "token-one", freshRun());
  await submitValid(db, deps, "token-one", freshRun());
  const res = await post(db, deps, "token-one", makeEnvelope(makeResult(freshRun())));
  assert.equal(res.status, 429);
  assert.equal(res.body.error.code, "quota_exceeded");
  const expectedSecs = Math.ceil(
    (Date.parse("2026-10-31T00:00:00Z") - NOW) / 1000);
  assert.equal(res.headers["retry-after"], String(expectedSecs));
  db.close();
});

test("G1B-F7: unknown tier/synthetic markers never alter the pipeline (guard)", async () => {
  const { deps, db } = await setup();
  const envelope = makeEnvelope(makeResult(freshRun()));
  envelope.synthetic = true;
  envelope.result.tier = "synthetic_example";
  const res = await post(db, deps, "token-one", envelope);
  assert.equal(res.status, 201);
  assert.equal(res.body.eligibility, "SUBMITTED");
  await qualify(db, deps);
  assert.equal(await eligibility(db, res.body.submission_id), "PUBLISHED");
  const trail = (await db.all(
    "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
    res.body.submission_id,
  )).map((r) => r.to_state);
  assert.deepEqual(trail, ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  db.close();
});

test("G1B-F8: every PUBLISHED row owns a full promotion chain (guard)", async () => {
  const { deps, db } = await setup();
  for (let i = 0; i < 3; i++) await submitValid(db, deps, "token-one", freshRun());
  await qualify(db, deps);
  const rows = await db.all("SELECT submission_id FROM submissions WHERE eligibility = 'PUBLISHED'");
  assert.equal(rows.length, 3);
  for (const { submission_id } of rows) {
    const trail = (await db.all(
      "SELECT to_state FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC",
      submission_id,
    )).map((r) => r.to_state);
    assert.deepEqual(trail, ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED"]);
  }
  db.close();
});
