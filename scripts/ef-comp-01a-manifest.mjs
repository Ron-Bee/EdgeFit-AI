// EF-COMP-01A Stage A corpus manifest validator (offline).
// Usage:
//   node scripts/ef-comp-01a-manifest.mjs [--manifest eval/comp-01a-corpus.json]
//   EDGEFIT_EVIDENCE env var resolves ${EDGEFIT_EVIDENCE} paths.
// Exit 0: manifest structurally valid, every file-backed entry
// hash-verified, pair ordering reported. Exit 1: any FAIL.
// Read-only: never writes artifacts, databases, or sessions.

import { createHash } from "node:crypto";
import { readFileSync, existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROLES = [
  "measurement",
  "device-context",
  "documented-observation",
  "synthetic-adversarial",
];

export function sha256Hex(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function resolvePath(raw, env) {
  // Only the declared evidence-root variable expands.
  if (typeof raw !== "string") return null;
  if (raw.includes("${")) {
    if (!raw.startsWith("${EDGEFIT_EVIDENCE}/")) return null;
    const root = env.EDGEFIT_EVIDENCE;
    if (!root) return null;
    return resolve(root, raw.slice("${EDGEFIT_EVIDENCE}/".length));
  }
  return resolve(raw);
}

function checkMeasurement(entry, env, problems) {
  const file = resolvePath(entry.path, env);
  if (file === null) {
    problems.push(`${entry.id}: path must be a plain path or \${EDGEFIT_EVIDENCE}/...`);
    return null;
  }
  if (!existsSync(file)) {
    problems.push(`${entry.id}: file missing: ${file}`);
    return null;
  }
  const bytes = readFileSync(file);
  const actual = sha256Hex(bytes).toUpperCase();
  if (actual !== String(entry.sha256).toUpperCase()) {
    problems.push(`${entry.id}: sha256 mismatch (file changed or misrecorded)`);
    return null;
  }
  let doc;
  try {
    doc = JSON.parse(bytes.toString("utf8"));
  } catch {
    problems.push(`${entry.id}: file is not JSON`);
    return null;
  }
  if (doc.contract !== "edgefit-bench-result") {
    problems.push(`${entry.id}: contract is not edgefit-bench-result`);
    return null;
  }
  return {
    id: entry.id,
    run_id: doc.run_id ?? null,
    status: doc.status ?? null,
    config: doc.config
      ? {
          n_ctx: doc.config.n_ctx,
          n_threads: doc.config.n_threads,
          n_predict: doc.config.n_predict,
        }
      : null,
    gen_tps: doc.measurements?.tps_gen ?? null,
    collected_at_utc: doc.collected_at_utc ?? null,
    model_sha256: doc.model?.sha256 ?? null,
  };
}

function checkDeviceContext(entry, env, problems) {
  const file = resolvePath(entry.path, env);
  if (file === null) {
    problems.push(`${entry.id}: path must be a plain path or \${EDGEFIT_EVIDENCE}/...`);
    return null;
  }
  if (!existsSync(file)) {
    problems.push(`${entry.id}: file missing: ${file}`);
    return null;
  }
  const bytes = readFileSync(file);
  if (sha256Hex(bytes).toUpperCase() !== String(entry.sha256).toUpperCase()) {
    problems.push(`${entry.id}: sha256 mismatch`);
    return null;
  }
  try {
    JSON.parse(bytes.toString("utf8"));
  } catch {
    problems.push(`${entry.id}: file is not JSON`);
    return null;
  }
  return { id: entry.id };
}

function checkDocumented(entry, problems) {
  if (!entry.session_ref || !entry.note) {
    problems.push(
      `${entry.id}: documented-observation needs session_ref and note`);
    return null;
  }
  return { id: entry.id, session_ref: entry.session_ref };
}

function checkSynthetic(entry, problems) {
  if (entry.payload === undefined) {
    problems.push(`${entry.id}: synthetic-adversarial needs an inline payload`);
    return null;
  }
  if (entry.sha256 !== undefined || entry.path !== undefined) {
    problems.push(
      `${entry.id}: synthetic entries must not masquerade as files`);
    return null;
  }
  return { id: entry.id };
}

export function validateManifest(manifest, env = process.env) {
  const problems = [];
  const verified = [];
  const documented = [];
  const synthetic = [];
  if (!manifest || manifest.manifest_version !== 1) {
    return {
      ok: false,
      problems: ["manifest_version must be 1"],
      report: null,
    };
  }
  if (!Array.isArray(manifest.entries)) {
    return {
      ok: false,
      problems: ["entries must be an array"],
      report: null,
    };
  }
  const ids = new Set();
  for (const entry of manifest.entries ?? []) {
    if (!entry || typeof entry.id !== "string" || ids.has(entry.id)) {
      problems.push("duplicate or missing entry id");
      continue;
    }
    ids.add(entry.id);
    if (!ROLES.includes(entry.role)) {
      problems.push(`${entry.id}: unknown role ${entry.role}`);
      continue;
    }
    if (entry.role === "measurement") {
      const v = checkMeasurement(entry, env, problems);
      if (v) verified.push(v);
    } else if (entry.role === "device-context") {
      if (checkDeviceContext(entry, env, problems)) documented.push(entry.id);
    } else if (entry.role === "documented-observation") {
      if (checkDocumented(entry, problems)) documented.push(entry.id);
    } else {
      if (checkSynthetic(entry, problems)) synthetic.push(entry.id);
    }
  }
  const pairs = [];
  for (const pair of manifest.pairs ?? []) {
    if (!pair || !ids.has(pair.prediction)) {
      problems.push(`pair ${pair?.id ?? "?"} cites unknown prediction`);
      continue;
    }
    const ordered =
      pair.predicted_at_utc != null &&
      pair.collected_at_utc != null &&
      pair.predicted_at_utc < pair.collected_at_utc;
    pairs.push({
      id: pair.id,
      prediction: pair.prediction,
      status: ordered ? "ORDERED" : "NOT_EVALUABLE",
      reason: ordered
        ? null
        : "prediction timestamp unrecorded or not before collection",
    });
  }
  const triples = new Set(
    verified
      .filter((v) => v.status === "COMPLETED" && v.config)
      .map((v) => `${v.config.n_ctx}/${v.config.n_threads}/${v.config.n_predict}`),
  );
  const report = {
    measurements_verified: verified.length,
    documented_entries: documented,
    synthetic_entries: synthetic,
    evaluable_pairs: pairs.filter((p) => p.status === "ORDERED").length,
    pair_details: pairs,
    config_variation: triples.size > 1,
    distinct_configs: [...triples],
    verified_measurements: verified,
  };
  return { ok: problems.length === 0, problems, report };
}

function main() {
  const args = process.argv.slice(2);
  let manifestPath = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--manifest" && args[i + 1]) {
      manifestPath = args[++i];
    } else if (args[i] === "--help" || args[i] === "-h") {
      console.log(
        "usage: node scripts/ef-comp-01a-manifest.mjs [--manifest <path>]");
      process.exit(0);
    } else {
      console.error(`unknown argument: ${args[i]}`);
      process.exit(2);
    }
  }
  if (manifestPath === null) {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
    manifestPath = resolve(root, "eval", "comp-01a-corpus.json");
  }
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  } catch (e) {
    console.error(`cannot read manifest: ${e.message}`);
    process.exit(2);
  }
  const { ok, problems, report } = validateManifest(manifest);
  console.log(JSON.stringify({ ok, problems, report }, null, 2));
  process.exit(ok ? 0 : 1);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
