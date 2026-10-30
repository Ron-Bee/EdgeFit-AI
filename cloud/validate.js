// EF-CLOUD-01A submission validation. Strict allowlist shapes
// for the POST /v1/submissions envelope. Returns { ok, row }
// or { ok:false, rejections:[{field, reason}] }. Nothing here
// touches the network; hashes arrive via injected deps.

export const API_VERSION = "1";
export const STATS_VERSION = "ef-stats-01";
export const RESULT_CONTRACT = "edgefit-bench-result";
export const RESULT_CONTRACT_VERSION = 1;
export const MAX_BODY_BYTES = 128 * 1024;
export const MAX_PLAUSIBLE_TPS = 100000;
export const ALLOWED_SCOPES = ["aggregates"];
export const ALLOWED_STATUSES = ["COMPLETED", "FAILED", "TIMEOUT", "CANCELLED"];
export const EFFECTIVE_OPTS = ["O0", "O1", "O2", "O3", "Os", "Oz", "unknown"];
export const EFFECTIVE_OPT_STATUSES = [
  "operator_attested",
  "self_declared",
  "unverified",
];

const SHA64 = /^[0-9a-f]{64}$/;
const RUN_ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/;
const PRINTABLE = /^[\x20-\x7E]{1,64}$/;

function isInt(v) {
  return typeof v === "number" && Number.isInteger(v);
}

function isFiniteNum(v) {
  return typeof v === "number" && Number.isFinite(v);
}

function isIsoDate(v) {
  if (typeof v !== "string") return null;
  const ms = Date.parse(v);
  return Number.isNaN(ms) ? null : new Date(ms);
}

function str(v, max) {
  return typeof v === "string" && v.length > 0 && v.length <= max;
}

/** Canonical JSON: sorted keys, for content hashing. */
export function stableStringify(value) {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }
  const keys = Object.keys(value).sort();
  const parts = keys.map(
    (k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`,
  );
  return `{${parts.join(",")}}`;
}

export function configHashFor(deps, config) {
  const canonical =
    `v1|${config.n_ctx}|${config.n_threads}|${config.n_predict}|${config.sampler}`;
  return deps.sha256hex(canonical);
}

/**
 * Validate the submission envelope. nowMs bounds timestamps.
 * Returns { ok:true, row } with normalized columns, or
 * { ok:false, rejections } with itemized field reasons.
 */
export function validateSubmission(envelope, nowMs, deps) {
  const rejections = [];
  const reject = (field, reason) => rejections.push({ field, reason });

  if (envelope === null || typeof envelope !== "object" || Array.isArray(envelope)) {
    return { ok: false, rejections: [{ field: "", reason: "envelope must be an object" }] };
  }
  const { result, consent } = envelope;
  if (result === null || typeof result !== "object" || Array.isArray(result)) {
    reject("result", "result must be an object");
  }
  if (consent === null || typeof consent !== "object" || Array.isArray(consent)) {
    reject("consent", "consent must be an object");
  }
  if (rejections.length > 0) return { ok: false, rejections };

  // ---- result identity ----
  if (result.contract !== RESULT_CONTRACT) {
    reject("result.contract", `must be ${RESULT_CONTRACT}`);
  }
  if (result.contract_version !== RESULT_CONTRACT_VERSION) {
    reject("result.contract_version", "unsupported contract version");
  }
  if (typeof result.run_id !== "string" || !RUN_ID.test(result.run_id)) {
    reject("result.run_id", "malformed run id");
  }
  const collected = isIsoDate(result.collected_at_utc);
  if (collected === null) {
    reject("result.collected_at_utc", "must be ISO-8601");
  } else if (collected.getTime() > nowMs + 5 * 60 * 1000) {
    reject("result.collected_at_utc", "timestamp is in the future");
  } else if (collected.getTime() < Date.parse("2020-01-01T00:00:00Z")) {
    reject("result.collected_at_utc", "timestamp predates EdgeFit");
  }
  if (!ALLOWED_STATUSES.includes(result.status)) {
    reject("result.status", "unknown measurement status");
  }
  if (result.error !== null && result.error !== undefined) {
    if (typeof result.error !== "string" || result.error.length > 2048) {
      reject("result.error", "must be a string of at most 2048 chars");
    }
  }
  if (!str(result.app_version, 64)) {
    reject("result.app_version", "must be 1..64 chars");
  }

  // ---- device (class = exact model string in v1) ----
  const device = result.device;
  if (device === null || typeof device !== "object" || Array.isArray(device)) {
    reject("result.device", "device must be an object");
  } else {
    if (typeof device.model !== "string" || !PRINTABLE.test(device.model.trim())) {
      reject("result.device.model", "must be 1..64 printable chars");
    }
    if (!isInt(device.sdk_int) || device.sdk_int < 21 || device.sdk_int > 99) {
      reject("result.device.sdk_int", "must be an integer 21..99");
    }
    if (!Array.isArray(device.supported_abis) || device.supported_abis.length === 0 ||
        device.supported_abis.length > 8 ||
        !device.supported_abis.every((a) => str(a, 32))) {
      reject("result.device.supported_abis", "must be 1..8 ABI strings");
    }
    if (!isInt(device.logical_processors) || device.logical_processors < 1 ||
        device.logical_processors > 1024) {
      reject("result.device.logical_processors", "must be an integer 1..1024");
    }
    if (!isInt(device.total_ram_bytes) || device.total_ram_bytes < 0 ||
        device.total_ram_bytes > 2 ** 48) {
      reject("result.device.total_ram_bytes", "out of range");
    }
  }

  // ---- model artifact ----
  const model = result.model;
  if (model === null || typeof model !== "object" || Array.isArray(model)) {
    reject("result.model", "model must be an object");
  } else {
    if (!str(model.display_name, 256)) {
      reject("result.model.display_name", "must be 1..256 chars");
    }
    if (!isInt(model.size_bytes) || model.size_bytes < 0 ||
        model.size_bytes > 2 ** 48) {
      reject("result.model.size_bytes", "out of range");
    }
    if (typeof model.sha256 !== "string" || !SHA64.test(model.sha256)) {
      reject("result.model.sha256", "must be 64 lowercase hex chars");
    }
    if (!str(model.quantization, 64)) {
      reject("result.model.quantization", "must be 1..64 chars");
    }
    if (model.metadata !== undefined) {
      const meta = model.metadata;
      if (meta === null || typeof meta !== "object" || Array.isArray(meta) ||
          Object.keys(meta).length > 64 ||
          !Object.entries(meta).every(([k, v]) =>
            k.length <= 128 &&
            (typeof v === "string" ? v.length <= 1024 :
              typeof v === "number" || typeof v === "boolean"))) {
        reject("result.model.metadata", "bounded scalar map only");
      }
    }
  }

  // ---- runtime ----
  const runtime = result.runtime;
  if (runtime === null || typeof runtime !== "object" || Array.isArray(runtime)) {
    reject("result.runtime", "runtime must be an object");
  } else {
    if (!str(runtime.impl, 64)) reject("result.runtime.impl", "required");
    if (!str(runtime.version, 64)) reject("result.runtime.version", "required");
    if (!str(runtime.backend, 32)) reject("result.runtime.backend", "required");
    if (!str(runtime.abi, 32)) reject("result.runtime.abi", "required");
  }

  // ---- config ----
  const config = result.config;
  if (config === null || typeof config !== "object" || Array.isArray(config)) {
    reject("result.config", "config must be an object");
  } else {
    if (!isInt(config.n_ctx) || config.n_ctx < 1 || config.n_ctx > 1048576) {
      reject("result.config.n_ctx", "out of range");
    }
    if (!isInt(config.n_threads) || config.n_threads < 1 ||
        config.n_threads > 1024) {
      reject("result.config.n_threads", "out of range");
    }
    if (!isInt(config.n_predict) || config.n_predict < 1 ||
        config.n_predict > 1048576) {
      reject("result.config.n_predict", "out of range");
    }
    if (!isFiniteNum(config.temperature)) {
      reject("result.config.temperature", "must be a finite number");
    }
    if (!str(config.sampler, 32)) {
      reject("result.config.sampler", "required");
    }
    if (config.seed !== undefined && config.seed !== null &&
        !isInt(config.seed)) {
      reject("result.config.seed", "must be an integer or null");
    }
    if (!isInt(config.timeout_ms) || config.timeout_ms <= 0 ||
        config.timeout_ms > 2 ** 48) {
      reject("result.config.timeout_ms", "out of range");
    }
  }

  // ---- workload ----
  const workload = result.workload;
  if (workload === null || typeof workload !== "object" ||
      Array.isArray(workload)) {
    reject("result.workload", "workload must be an object");
  } else {
    if (!str(workload.id, 64)) reject("result.workload.id", "required");
    if (typeof workload.prompt_sha256 !== "string" ||
        !SHA64.test(workload.prompt_sha256)) {
      reject("result.workload.prompt_sha256", "must be 64 lowercase hex");
    }
    if (!isInt(workload.prompt_chars) || workload.prompt_chars < 0 ||
        workload.prompt_chars > 2 ** 32) {
      reject("result.workload.prompt_chars", "out of range");
    }
  }

  // ---- measurements ----
  const m = result.measurements;
  if (m === null || typeof m !== "object" || Array.isArray(m)) {
    reject("result.measurements", "measurements must be an object");
  } else {
    for (const key of ["load_ms", "prefill_ms", "gen_ms"]) {
      if (!isInt(m[key]) || m[key] < 0 || m[key] > 2 ** 48) {
        reject(`result.measurements.${key}`, "out of range");
      }
    }
    for (const key of ["prefill_tokens", "generated_tokens"]) {
      if (!isInt(m[key]) || m[key] < 0 || m[key] > 2 ** 32) {
        reject(`result.measurements.${key}`, "out of range");
      }
    }
    for (const key of ["tps_gen", "tps_prefill"]) {
      if (!isFiniteNum(m[key]) || m[key] < 0) {
        reject(`result.measurements.${key}`, "must be finite and >= 0");
      }
    }
    if (typeof m.eos_stop !== "boolean") {
      reject("result.measurements.eos_stop", "must be a boolean");
    }
    const mem = m.mem;
    if (mem === null || typeof mem !== "object" || Array.isArray(mem)) {
      reject("result.measurements.mem", "mem must be an object");
    } else {
      for (const key of ["pss_before_kb", "pss_after_kb"]) {
        if (!isInt(mem[key]) || mem[key] < 0 || mem[key] > 2 ** 48) {
          reject(`result.measurements.mem.${key}`, "out of range");
        }
      }
      if (!isInt(mem.alloc_delta_bytes) ||
          Math.abs(mem.alloc_delta_bytes) > 2 ** 48) {
        reject("result.measurements.mem.alloc_delta_bytes", "out of range");
      }
      if (!str(mem.method, 64)) {
        reject("result.measurements.mem.method", "required");
      }
    }
    const thermal = m.thermal;
    if (thermal === null || typeof thermal !== "object" ||
        Array.isArray(thermal)) {
      reject("result.measurements.thermal", "thermal must be an object");
    } else {
      for (const key of ["before", "mid", "after"]) {
        if (!str(thermal[key], 32)) {
          reject(`result.measurements.thermal.${key}`, "required");
        }
      }
      if (typeof thermal.available !== "boolean") {
        reject("result.measurements.thermal.available", "must be a boolean");
      }
    }
  }

  // ---- x_ extensions (additive, optional) ----
  let effectiveOpt = "unknown";
  let effectiveOptStatus = "unverified";
  if (result.x_effective_opt !== undefined && result.x_effective_opt !== null) {
    const eff = result.x_effective_opt;
    if (eff === null || typeof eff !== "object" || Array.isArray(eff) ||
        !EFFECTIVE_OPTS.includes(eff.value) ||
        !EFFECTIVE_OPT_STATUSES.includes(eff.status)) {
      reject("result.x_effective_opt", "value/status outside allowlist");
    } else {
      effectiveOpt = eff.value;
      effectiveOptStatus = eff.status;
    }
  }
  let memPctStart = null;
  let memBelow = null;
  let memLow = null;
  if (result.x_mem_conditions !== undefined &&
      result.x_mem_conditions !== null) {
    const mc = result.x_mem_conditions;
    if (mc === null || typeof mc !== "object" || Array.isArray(mc)) {
      reject("result.x_mem_conditions", "must be an object");
    } else {
      if (mc.avail_pct_start !== undefined && mc.avail_pct_start !== null) {
        if (!isFiniteNum(mc.avail_pct_start) || mc.avail_pct_start < 0 ||
            mc.avail_pct_start > 100) {
          reject("result.x_mem_conditions.avail_pct_start",
            "must be 0..100");
        } else {
          memPctStart = mc.avail_pct_start;
        }
      }
      for (const key of ["below_threshold_start", "low_memory_start"]) {
        if (mc[key] !== undefined && mc[key] !== null &&
            typeof mc[key] !== "boolean") {
          reject(`result.x_mem_conditions.${key}`, "must be a boolean");
        }
      }
      if (typeof mc.below_threshold_start === "boolean") {
        memBelow = mc.below_threshold_start ? 1 : 0;
      }
      if (typeof mc.low_memory_start === "boolean") {
        memLow = mc.low_memory_start ? 1 : 0;
      }
    }
  }

  // ---- consent (per-submission, explicit) ----
  if (typeof consent.policy_version !== "string" ||
      consent.policy_version.length === 0 ||
      consent.policy_version.length > 32) {
    reject("consent.policy_version", "must be 1..32 chars");
  }
  const accepted = isIsoDate(consent.accepted_at);
  if (accepted === null) {
    reject("consent.accepted_at", "must be ISO-8601");
  } else if (accepted.getTime() > nowMs + 5 * 60 * 1000) {
    reject("consent.accepted_at", "consent timestamp is in the future");
  }
  if (!Array.isArray(consent.scopes) || consent.scopes.length === 0) {
    reject("consent.scopes", "at least one scope is required");
  } else if (!consent.scopes.every((s) => ALLOWED_SCOPES.includes(s))) {
    if (consent.scopes.includes("runs")) {
      reject("consent.scopes",
        "runs disclosure is disabled until its policy is approved");
    } else {
      reject("consent.scopes", "unknown scope requested");
    }
  }

  if (rejections.length > 0) return { ok: false, rejections };

  const canonical = stableStringify(result);
  return {
    ok: true,
    row: {
      content_hash: deps.sha256hex(canonical),
      result_json: canonical,
      run_id: result.run_id,
      device_class: device.model.trim(),
      model_sha: model.sha256,
      model_size_bytes: model.size_bytes,
      quantization_label: model.quantization,
      workload_id: workload.id,
      prompt_sha: workload.prompt_sha256,
      n_ctx: config.n_ctx,
      n_threads: config.n_threads,
      n_predict: config.n_predict,
      sampler: config.sampler,
      config_hash: configHashFor(deps, config),
      runtime_impl: runtime.impl,
      runtime_backend: runtime.backend,
      runtime_version: runtime.version,
      effective_opt: effectiveOpt,
      effective_opt_status: effectiveOptStatus,
      build_key: `${effectiveOpt}:${effectiveOptStatus}`,
      status: result.status,
      tps_gen: m.tps_gen,
      tps_prefill: m.tps_prefill,
      gen_tokens: m.generated_tokens,
      prompt_tokens: m.prefill_tokens,
      gen_ms: m.gen_ms,
      collected_at: result.collected_at_utc,
      app_version: result.app_version,
      mem_avail_pct_start: memPctStart,
      mem_below_advisory: memBelow,
      mem_low_memory: memLow,
      consent_policy_version: consent.policy_version,
      consent_accepted_at: consent.accepted_at,
      consent_scopes: JSON.stringify(consent.scopes),
    },
  };
}
