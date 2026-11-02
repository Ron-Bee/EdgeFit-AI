// SCHEDULER-FAIRNESS-01: held QUALIFIED rows must not starve
// SUBMITTED/VALIDATED work. Live incident (FLEET-DASHBOARD-01):
// 10 allowlist-held QUALIFIED rows filled every oldest-first
// 10-row tick, starving 18 SUBMITTED rows (zero promotions for
// 6+ ticks). The fix prioritizes earlier stages first while
// preserving bounds, oldest-first within a stage, holds, and the
// post-approval QUALIFIED->PUBLISHED path.
import test from "node:test";
import assert from "node:assert/strict";
import { runQualification } from "../qualify.js";
import {
  NOW, makeDeps, openTestDb, seedInstall, submitValid,
} from "./helpers.js";

let fairCounter = 9000;

function freshRun() {
  fairCounter += 1;
  return {
    run_id: `BENCH-FAIR-${fairCounter}`,
    collected_at_utc: new Date(NOW - fairCounter * 60000).toISOString(),
  };
}

async function seed3(db, deps) {
  const tokens = { 1: "fair-tok-1", 2: "fair-tok-2", 3: "fair-tok-3" };
  for (let i = 1; i <= 3; i++) {
    await seedInstall(db, deps, `fair-install-${i}`, tokens[i],
      "device_contribution");
  }
  return tokens;
}

// 10 old rows driven to (held) QUALIFIED, then N newer SUBMITTED rows.
// Held rows stamp ef-synth-01: accepted at submit, never publishable.
async function seedHeldPlusPending(db, deps, tokens, nPending,
    pendingAt = NOW + 3600_000) {
  const heldIds = [];
  for (let i = 0; i < 10; i++) {
    heldIds.push(await submitValid(db, deps, tokens[(i % 3) + 1],
      { ...freshRun() }, { policy_version: "ef-synth-01" }, NOW));
  }
  const first = await runQualification(db, NOW + 1000, deps);
  assert.equal(first.promoted.length, 20);
  const pendIds = [];
  for (let i = 0; i < nPending; i++) {
    pendIds.push(await submitValid(db, deps, tokens[(i % 3) + 1],
      { ...freshRun() }, { policy_version: "ef-synth-01" },
      pendingAt + i * 1000));
  }
  return { heldIds, pendIds };
}

async function eligibilityOf(db, id) {
  const row = await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?", id);
  return row.eligibility;
}

// 1. The live defect: 10 held QUALIFIED + 5 SUBMITTED, one capped pass.
test("FAIR-01: held QUALIFIED rows cannot starve SUBMITTED work", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const { pendIds } = await seedHeldPlusPending(db, deps, tokens, 5);
  const rep = await runQualification(db, NOW + 7200_000, deps, 10);
  assert.ok(rep.processed <= 10);
  for (const id of pendIds) {
    assert.equal(await eligibilityOf(db, id), "QUALIFIED");
  }
  const fromSub = rep.promoted.filter((p) => p.from === "SUBMITTED");
  assert.equal(fromSub.length, 5);
});

// 2. Bounds hold under the new ordering.
test("FAIR-02: fairness pass stays bounded by maxRows", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  await seedHeldPlusPending(db, deps, tokens, 5);
  const rep = await runQualification(db, NOW + 7200_000, deps, 3);
  assert.equal(rep.processed, 3);
  const fromSub = rep.promoted.filter((p) => p.from === "SUBMITTED");
  assert.equal(fromSub.length, 3);
});

// 3. Oldest-first is preserved WITHIN a stage.
test("FAIR-03: earlier SUBMITTED rows win remaining slots first", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const { pendIds } = await seedHeldPlusPending(db, deps, tokens, 5);
  await runQualification(db, NOW + 7200_000, deps, 3);
  for (const id of pendIds.slice(0, 3)) {
    assert.equal(await eligibilityOf(db, id), "QUALIFIED");
  }
  for (const id of pendIds.slice(3)) {
    assert.equal(await eligibilityOf(db, id), "SUBMITTED");
  }
});

// 4. The post-approval path survives: allowlisted rows still publish,
// held rows still hold, and the hold is reported (fail-safe).
test("FAIR-04: QUALIFIED->PUBLISHED path and holds preserved", async (t) => {
  const db = await openTestDb();
  t.after(() => db.close());
  const deps = makeDeps();
  const tokens = await seed3(db, deps);
  const { heldIds } = await seedHeldPlusPending(db, deps, tokens, 0);
  const pubIds = [];
  for (let i = 0; i < 2; i++) {
    pubIds.push(await submitValid(db, deps, tokens[i + 1],
      { ...freshRun() }, { policy_version: "v1" }, NOW + 3600_000));
  }
  const rep = await runQualification(db, NOW + 7200_000, deps, 12);
  for (const id of pubIds) {
    assert.equal(await eligibilityOf(db, id), "PUBLISHED");
  }
  for (const id of heldIds) {
    assert.equal(await eligibilityOf(db, id), "QUALIFIED");
  }
  const holdReasons = rep.held
    .filter((h) => h.state === "QUALIFIED")
    .map((h) => h.reason);
  assert.ok(holdReasons.includes(
    "consent version not approved for publication"));
  const leaked = await db.get(
    `SELECT COUNT(*) AS c FROM submissions
     WHERE eligibility = 'PUBLISHED'
     AND consent_policy_version = 'ef-synth-01'`);
  assert.equal(leaked.c, 0);
});
