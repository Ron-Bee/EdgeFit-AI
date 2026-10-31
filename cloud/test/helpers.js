// Shared EF-CLOUD-01A test fixtures. Synthetic records only;
// nothing here is a community measurement.
import { createHash } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { openDatabase, initSchema } from "../db.js";
import { handleRequest } from "../api.js";
import { runQualification } from "../qualify.js";

export const NOW = Date.parse("2026-10-30T12:00:00Z");
export const MODEL_A = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83";
export const MODEL_B = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
export const PROMPT_SHA =
  "36104db0657a294048410f9043d4efc8510a77088ca1e2f17c0b6c8359c44382";

export function makeDeps(overrides = {}) {
  let counter = 0;
  return {
    sha256hex: (text) => createHash("sha256").update(text, "utf8").digest("hex"),
    newId: () => `SUB-${String(++counter).padStart(32, "0")}`,
    installSalt: "test-salt",
    quorumRuns: 5,
    quorumInstalls: 3,
    quotaPerDay: 20,
    maxGroups: 200,
    maxModels: 500,
    deviceClasses: ["SM-S908U", "Pixel 9", "Moto G Power"],
    ...overrides,
  };
}

/**
 * Apply every migration in cloud/migrations/ in filename order.
 * initSchema() carries the initial (v1) schema; the migration
 * chain brings a database to current (002 trigger and beyond).
 * Idempotent: every migration statement is IF NOT EXISTS.
 */
export async function applyMigrations(db) {
  const dir = join(dirname(fileURLToPath(import.meta.url)), "..",
    "migrations");
  const files = readdirSync(dir).filter((f) => f.endsWith(".sql"))
    .sort();
  for (const file of files) {
    await db.exec(readFileSync(join(dir, file), "utf8"));
  }
}

export async function openTestDb() {
  const db = openDatabase(":memory:");
  await initSchema(db);
  await applyMigrations(db);
  return db;
}

export async function seedInstall(db, deps, installId, token) {
  await db.run(
    "INSERT INTO installs (install_id, token_hash, created_at, revoked_at) VALUES (?, ?, ?, NULL)",
    installId, deps.sha256hex(token), new Date(NOW).toISOString(),
  );
}

export function tpsFor(tokens, ms) {
  return (tokens * 1000) / ms;
}

export function makeResult(overrides = {}) {
  const genMs = overrides.gen_ms ?? 4000;
  const tokens = overrides.generated_tokens ?? 64;
  const result = {
    contract: "edgefit-bench-result",
    contract_version: 1,
    run_id: overrides.run_id ?? "BENCH-TEST-001",
    device: {
      profile_ref: "0".repeat(64),
      model: "SM-S908U",
      sdk_int: 36,
      supported_abis: ["arm64-v8a"],
      logical_processors: 8,
      total_ram_bytes: 12884901888,
      ...(overrides.device ?? {}),
    },
    model: {
      display_name: "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
      size_bytes: 807143936,
      sha256: MODEL_A,
      quantization: "4",
      metadata: {},
      ...(overrides.model ?? {}),
    },
    runtime: {
      impl: "llama.cpp",
      version: "0.7.0-dev",
      backend: "cpu",
      abi: "arm64-v8a",
      ...(overrides.runtime ?? {}),
    },
    config: {
      n_ctx: 512,
      n_threads: 4,
      n_predict: 64,
      temperature: 0,
      sampler: "greedy",
      seed: null,
      timeout_ms: 600000,
      ...(overrides.config ?? {}),
    },
    workload: {
      id: "edgefit-prompt-v1",
      prompt_sha256: PROMPT_SHA,
      prompt_chars: 100,
      ...(overrides.workload ?? {}),
    },
    measurements: {
      load_ms: 1200,
      prefill_ms: 1000,
      prefill_tokens: 23,
      gen_ms: genMs,
      generated_tokens: tokens,
      tps_gen: tpsFor(tokens, genMs),
      tps_prefill: 23.0,
      eos_stop: false,
      mem: {
        pss_before_kb: 100,
        pss_after_kb: 200,
        alloc_delta_bytes: 4096,
        method: "test",
      },
      thermal: { before: "none", mid: "none", after: "none", available: true },
      ...(overrides.measurements ?? {}),
    },
    status: "COMPLETED",
    error: null,
    provenance: "on_device_measurement",
    app_version: "0.2.0",
    collected_at_utc: "2026-10-30T11:00:00Z",
    x_effective_opt: { value: "O2", status: "operator_attested" },
    x_mem_conditions: {
      avail_pct_start: 24.7,
      below_threshold_start: false,
      low_memory_start: false,
    },
  };
  for (const [key, value] of Object.entries(overrides)) {
    if (!["device", "model", "runtime", "config", "workload",
      "measurements", "gen_ms", "generated_tokens"].includes(key)) {
      result[key] = value;
    }
  }
  return result;
}

export function makeEnvelope(result, consentOverrides = {}) {
  return {
    result,
    consent: {
      policy_version: "v1",
      accepted_at: "2026-10-30T11:30:00Z",
      scopes: ["aggregates"],
      ...consentOverrides,
    },
  };
}

export async function post(db, deps, token, envelope, nowMs = NOW) {
  return await handleRequest({
    method: "POST",
    path: "/v1/submissions",
    query: {},
    headers: { authorization: `Bearer ${token}` },
    bodyText: JSON.stringify(envelope),
    db,
    nowMs,
    deps,
  });
}

export async function get(db, deps, path, query = {}, token = null) {
  return await handleRequest({
    method: "GET",
    path,
    query,
    headers: token ? { authorization: `Bearer ${token}` } : {},
    bodyText: "",
    db,
    nowMs: NOW,
    deps,
  });
}

export async function del(db, deps, path, token) {
  return await handleRequest({
    method: "DELETE",
    path,
    query: {},
    headers: { authorization: `Bearer ${token}` },
    bodyText: "",
    db,
    nowMs: NOW,
    deps,
  });
}

/** Submit one valid synthetic result from an install; returns submission_id. */
export async function submitValid(db, deps, token, overrides = {},
  consentOverrides = {}, nowMs = NOW) {
  const res = await post(db, deps, token,
    makeEnvelope(makeResult(overrides), consentOverrides), nowMs);
  if (res.status !== 201) {
    throw new Error(`fixture submit failed: ${JSON.stringify(res.body)}`);
  }
  return res.body.submission_id;
}

export async function qualify(db, deps, nowMs = NOW) {
  return await runQualification(db, nowMs, deps);
}
