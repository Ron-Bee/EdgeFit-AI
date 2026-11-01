// DEMO-03 operator issuer tests (OP-01..OP-10). The helper runs
// against local scratch databases ONLY here; --target staging is
// never exercised by tests (arg validation excepted). No real
// credentials are minted: every token is random and discarded.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, rmSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";
import {
  parseArgs, validateRequest, generateCredential, hashToken,
  buildInsert, runIssue,
} from "../ops/issue-install.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const ISSUER = join(HERE, "..", "ops", "issue-install.mjs");
const BEARER = /^[A-Za-z0-9._~+/-]{8,256}=*$/;
const HEX64 = /^[0-9a-f]{64}$/;

function scratchDb(t) {
  // Repo-local: sandboxed runs cannot mkdir in os.tmpdir().
  const dir = join(HERE, ".tmp-ops");
  mkdirSync(dir, { recursive: true });
  const target = join(dir, `ops-${Date.now()}-${Math.random()
    .toString(36).slice(2)}.db`);
  t.after(() => rmSync(target, { force: true }));
  return target;
}

const BASE = { kind: "synthetic_test", device: "ops-test",
  purpose: "OP fixture", approval: "OP-TEST" };

test("OP-01 refusals: approval, kind, verification, fields", () => {
  assert.equal(validateRequest({ ...BASE, approval: "" }).ok, false);
  assert.equal(validateRequest({ ...BASE, kind: "legacy_untrusted" }).ok,
    false);
  assert.equal(validateRequest({ ...BASE, kind: "admin" }).ok, false);
  assert.equal(validateRequest({ ...BASE, kind: "device_contribution" }).ok,
    false);
  assert.equal(validateRequest({ ...BASE, kind: "device_contribution",
    deviceVerified: "SM-S908U 2026-11-01" }).ok, true);
  assert.equal(validateRequest({ ...BASE, device: "" }).ok, false);
  assert.equal(validateRequest({ ...BASE, purpose: "" }).ok, false);
  assert.equal(validateRequest({ ...BASE, installId: "nope" }).ok, false);
  assert.equal(validateRequest({ ...BASE, target: "prod" }).ok, false);
});

test("OP-02 dry-run prints SQL and record, mutates nothing", (t) => {
  const target = scratchDb(t);
  const res = runIssue({ ...BASE, target });
  assert.equal(res.ok, true);
  assert.equal(res.dryRun, true);
  assert.ok(res.sql.startsWith("INSERT INTO installs"));
  assert.ok(res.sql.includes("synthetic_test"));
  assert.ok(res.record.includes(res.installId));
  assert.ok(!res.record.includes(res.token));
  assert.equal(existsSync(target), false);
});

test("OP-03 tokens fit the server Bearer alphabet", () => {
  for (let i = 0; i < 100; i += 1) {
    const token = generateCredential();
    assert.ok(BEARER.test(token), token);
    assert.ok(HEX64.test(hashToken(token)));
  }
});

test("OP-04 local execute inserts exactly one row", (t) => {
  const target = scratchDb(t);
  const res = runIssue({ ...BASE, target, execute: true });
  assert.equal(res.ok, true);
  const db = new DatabaseSync(target);
  try {
    const rows = db.prepare("SELECT * FROM installs").all();
    assert.equal(rows.length, 1);
    assert.equal(rows[0].install_id, res.installId);
    assert.equal(rows[0].kind, "synthetic_test");
    assert.equal(rows[0].token_hash, hashToken(res.token));
    assert.equal(rows[0].revoked_at, null);
  } finally {
    db.close();
  }
});

test("OP-05 duplicate install_id is refused", (t) => {
  const target = scratchDb(t);
  const first = runIssue({ ...BASE, target, execute: true });
  assert.equal(first.ok, true);
  const dup = runIssue({ ...BASE, target, execute: true,
    installId: first.installId });
  assert.equal(dup.ok, false);
  assert.match(dup.error, /already exists/);
  const db = new DatabaseSync(target);
  try {
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM installs")
      .get().n, 1);
  } finally {
    db.close();
  }
});

test("OP-06 device kind executes with verification only", (t) => {
  const target = scratchDb(t);
  const noProof = runIssue({ ...BASE, kind: "device_contribution",
    target, execute: true });
  assert.equal(noProof.ok, false);
  const ok = runIssue({ ...BASE, kind: "device_contribution",
    deviceVerified: "SM-S908U 2026-11-01", target, execute: true });
  assert.equal(ok.ok, true);
  const db = new DatabaseSync(target);
  try {
    assert.equal(db.prepare("SELECT kind FROM installs").get().kind,
      "device_contribution");
  } finally {
    db.close();
  }
});

test("OP-07 execute without a real target is refused", () => {
  const res = runIssue({ ...BASE, execute: true });
  assert.equal(res.ok, false);
  assert.match(res.error, /--target/);
});

test("OP-08 record line carries no token material", (t) => {
  const target = scratchDb(t);
  const res = runIssue({ ...BASE, target, execute: true });
  assert.equal(res.ok, true);
  assert.ok(!res.record.includes(res.token));
  assert.ok(!res.record.includes(hashToken(res.token)));
  assert.ok(res.record.includes("device=ops-test"));
});

test("OP-09 CLI dry-run wiring end to end", () => {
  const out = execFileSync(process.execPath,
    [ISSUER, "--kind", "synthetic_test", "--device", "cli-test",
      "--purpose", "OP-09", "--approval", "OP-TEST"],
    { encoding: "utf8" });
  assert.ok(out.includes("DRY-RUN install_id="));
  assert.ok(out.includes("INSERT INTO installs"));
  assert.ok(out.includes("ONE-TIME HANDOFF"));
});

test("OP-10 CLI refusal exits nonzero without secrets", () => {
  let err = null;
  try {
    execFileSync(process.execPath, [ISSUER, "--kind", "synthetic_test"],
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (e) {
    err = e;
  }
  assert.ok(err, "expected nonzero exit");
  assert.notEqual(err.status, 0);
  const text = String(err.stderr ?? "");
  assert.ok(text.includes("--approval"));
  assert.ok(!text.includes("token="));
});
