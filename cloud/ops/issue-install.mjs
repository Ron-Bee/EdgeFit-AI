// Operator installation-credential issuer (DEMO-03, Phase D).
//
// Mint ONE upload credential with server-controlled kind. Default
// is DRY-RUN (prints the SQL + record line, mutates nothing).
// --execute performs the INSERT against --target.
//
// Usage:
//   node cloud/ops/issue-install.mjs --kind <device_contribution|synthetic_test> \
//     --device "<model>" --purpose "<text>" --approval "<ref>" \
//     [--device-verified "<model> <UTC date>"] [--install-id <uuid>] \
//     [--target staging|<file>|:memory:] [--execute] [--quiet]
//
// Safety rules (all enforced, all tested):
// - No --approval ref: REFUSE (exit 2). Every mint names the
//   owner authorization it runs under.
// - kind=device_contribution without --device-verified: REFUSE.
//   Physical possession must be attested per mint.
// - kind=legacy_untrusted (or anything else): REFUSE. Legacy is
//   a backfill class, never issued.
// - Duplicate install_id in the target: REFUSE.
// - --target staging shells to pinned wrangler against the exact
//   staging database id; any other remote is unreachable from
//   this tool (no --database flag exists on purpose).
// - The token prints ONCE to stdout (the one-time handoff) unless
//   --quiet; it is never written to a file, never logged, and the
//   issuance record line carries NO token material.
// - Historical rows are never touched (INSERT only; no UPDATE).
import { execSync } from "node:child_process";
import { randomBytes, randomUUID, createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";

export const STAGING_DATABASE_ID = "2ccce000-c70c-4c5a-9267-d330dedc22aa";
export const STAGING_DATABASE_NAME = "edgefit-evidence-staging";
export const WRANGLER_PIN = "wrangler@4.156.0";
export const ISSUABLE_KINDS = ["device_contribution", "synthetic_test"];
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TOKEN_BYTES = 32;

export function parseArgs(argv) {
  const out = { target: "dry-run", execute: false, quiet: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === "--execute") out.execute = true;
    else if (a === "--quiet") out.quiet = true;
    else if (a.startsWith("--")) {
      const key = a.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
      out[key] = argv[i + 1];
      i += 1;
    }
  }
  return out;
}

/** Validate the request. Returns {ok, error?}; never touches a DB. */
export function validateRequest(o) {
  const target = o.target ?? "dry-run";
  if (!o.approval || o.approval.trim() === "") {
    return { ok: false,
      error: "refusing: --approval <owner-authorization-ref> is required" };
  }
  if (!ISSUABLE_KINDS.includes(o.kind)) {
    return { ok: false,
      error: `refusing: --kind must be one of ${ISSUABLE_KINDS.join(", ")}` };
  }
  if (o.kind === "device_contribution" &&
      (!o.deviceVerified || o.deviceVerified.trim() === "")) {
    return { ok: false,
      error: "refusing: device_contribution requires " +
        "--device-verified \"<model> <UTC date>\"" };
  }
  if (!o.device || o.device.trim() === "") {
    return { ok: false, error: "refusing: --device \"<model>\" is required" };
  }
  if (!o.purpose || o.purpose.trim() === "") {
    return { ok: false, error: "refusing: --purpose \"<text>\" is required" };
  }
  if (o.installId !== undefined && !UUID_RE.test(o.installId)) {
    return { ok: false,
      error: "refusing: --install-id must be a UUID (omit to generate)" };
  }
  if (!["dry-run", "staging", ":memory:"].includes(target) &&
      !target.endsWith(".db") && !target.endsWith(".sqlite") &&
      !target.endsWith(".sqlite3")) {
    return { ok: false,
      error: "refusing: --target must be staging, :memory:, or a local .db file" };
  }
  return { ok: true };
}

export function generateCredential() {
  // Standard base64 lands inside the server Bearer alphabet
  // (A-Za-z0-9._~+/- with = padding).
  return randomBytes(TOKEN_BYTES).toString("base64");
}

export function hashToken(token) {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

export function buildInsert({ installId, tokenHash, createdAt, kind }) {
  return "INSERT INTO installs (install_id, token_hash, created_at, " +
    `revoked_at, kind) VALUES ('${installId}', '${tokenHash}', ` +
    `'${createdAt}', NULL, '${kind}');`;
}

function openLocal(target) {
  const db = new DatabaseSync(target === ":memory:" ? ":memory:" : target);
  db.exec("CREATE TABLE IF NOT EXISTS installs (install_id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL, revoked_at TEXT, kind TEXT)");
  return db;
}

function stagingExec(sql) {
  try {
    return execSync(
      `npx -y ${WRANGLER_PIN} d1 execute ${STAGING_DATABASE_NAME} --remote --command "${sql}"`,
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (e) {
    throw new Error(`staging d1 failed status=${e.status ?? "?"}`);
  }
}

export function runIssue(opts, deps = {}) {
  const o = { target: "dry-run", ...opts };
  const v = validateRequest(o);
  Object.assign(opts, o);
  if (!v.ok) return { ok: false, error: v.error, exitCode: 2 };
  const installId = opts.installId ?? randomUUID();
  const token = generateCredential();
  const tokenHash = hashToken(token);
  const createdAt = new Date().toISOString();
  const sql = buildInsert({ installId, tokenHash, createdAt,
    kind: opts.kind });
  const record = `ISSUED date=${createdAt} install_id=${installId} ` +
    `kind=${opts.kind} device=${opts.device} purpose=${opts.purpose} ` +
    `approval=${opts.approval}`;
  if (!opts.execute) {
    return { ok: true, dryRun: true, installId, token, sql, record };
  }
  if (opts.target === "staging") {
    const seen = stagingExec(
      `SELECT install_id FROM installs WHERE install_id = '${installId}';`);
    if (seen.includes(installId)) {
      return { ok: false,
        error: `refusing: install_id ${installId} already exists`,
        exitCode: 2 };
    }
    stagingExec(sql);
    return { ok: true, installId, token, record, target: "staging" };
  }
  if (opts.target === "dry-run") {
    return { ok: false,
      error: "refusing: --execute needs --target staging or a local file",
      exitCode: 2 };
  }
  const open = deps.openLocal ?? openLocal;
  const db = open(opts.target);
  try {
    const dup = db.prepare(
      "SELECT install_id FROM installs WHERE install_id = ?")
      .get(installId);
    if (dup) {
      return { ok: false,
        error: `refusing: install_id ${installId} already exists`,
        exitCode: 2 };
    }
    db.prepare(
      "INSERT INTO installs (install_id, token_hash, created_at, revoked_at, kind) VALUES (?, ?, ?, NULL, ?)")
      .run(installId, tokenHash, createdAt, opts.kind);
    return { ok: true, installId, token, record, target: opts.target };
  } finally {
    db.close();
  }
}

function main(argv) {
  const opts = parseArgs(argv);
  const res = runIssue(opts);
  if (!res.ok) {
    console.error(res.error);
    process.exit(res.exitCode ?? 1);
  }
  if (res.dryRun) {
    console.log(`DRY-RUN install_id=${res.installId}`);
    console.log(res.sql);
  }
  console.log(res.record);
  if (!opts.quiet) {
    console.log("--- ONE-TIME HANDOFF (copy now; never stored) ---");
    console.log(`install_id=${res.installId}`);
    console.log(`token=${res.token}`);
    console.log("--- end handoff: delete from scrollback after delivery ---");
  } else {
    console.log("(token suppressed by --quiet; re-run without it to handoff)");
  }
}

const invoked = process.argv[1] &&
  process.argv[1].replace(/\\/g, "/").endsWith("ops/issue-install.mjs");
if (invoked) main(process.argv.slice(2));
