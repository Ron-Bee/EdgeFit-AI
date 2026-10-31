// G2-A permanent lock-contention regression (promoted from the
// independent acceptance probe S1). Exercises GENUINE two-connection
// SQLite write contention: no hooks, no injected errors.
import test from "node:test";
import assert from "node:assert/strict";
import { join, dirname } from "node:path";
import { mkdirSync, unlinkSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { openDatabase, initSchema } from "../db.js";
import {
  makeDeps, seedInstall, makeResult, makeEnvelope, post,
} from "./helpers.js";

test("G2-L1: locked database answers 503, stores nothing, recovers", async (t) => {
  const dir = join(dirname(fileURLToPath(import.meta.url)), ".tmp-g1b");
  mkdirSync(dir, { recursive: true });
  const path = join(dir, `contention-${process.pid}.db`);
  const a = openDatabase(path);
  await initSchema(a);
  const b = openDatabase(path);
  t.after(() => {
    try { a.close(); } catch { /* ignore */ }
    try { b.close(); } catch { /* ignore */ }
    try { unlinkSync(path); } catch { /* ignore */ }
  });
  const deps = makeDeps();
  await seedInstall(a, deps, "install-1", "token-one");
  // B waits briefly; the bound below only guards against hangs
  // (typical return ~0.2 s; 150x margin, not timing-sensitive).
  await b.exec("PRAGMA busy_timeout = 200");
  // A holds a RESERVED lock with an uncommitted write.
  await a.exec("BEGIN IMMEDIATE");
  await a.run("INSERT INTO daily_counts (install_id, day, count) VALUES (?, ?, ?)",
    "install-1", "2026-10-30", 0);
  const envelope = makeEnvelope(makeResult({ run_id: "BENCH-G2L1" }));
  const started = Date.now();
  let res = null;
  let threw = null;
  try {
    res = await post(b, deps, "token-one", envelope);
  } catch (e) {
    threw = e;
  }
  assert.equal(threw, null, `submission threw: ${threw && threw.message}`);
  assert.equal(res.status, 503);
  assert.equal(res.body.error.code, "temporarily_unavailable");
  assert.equal(res.headers["retry-after"], "1");
  assert.ok(Date.now() - started < 30000, "busy wait must be bounded");
  assert.equal((await b.get("SELECT COUNT(*) AS n FROM submissions")).n, 0);
  assert.equal((await b.get("SELECT COUNT(*) AS n FROM eligibility_history")).n, 0);
  // Contention clears: the same bytes store cleanly with exact counts.
  await a.exec("ROLLBACK");
  const retry = await post(b, deps, "token-one", envelope);
  assert.equal(retry.status, 201);
  assert.equal((await b.get("SELECT COUNT(*) AS n FROM submissions")).n, 1);
  assert.equal((await b.get(
    "SELECT count FROM daily_counts WHERE install_id = 'install-1'")).count, 1);
  assert.equal((await b.get("SELECT COUNT(*) AS n FROM eligibility_history")).n, 1);
});
