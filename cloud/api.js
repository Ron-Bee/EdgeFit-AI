// EF-CLOUD-01A versioned HTTP foundation, G1B corrections applied:
// canonical shared cohorts (D1/D2/D8), per-cohort candidates (D3),
// atomic submission + revocation writes (D4/D5), sanitized failures
// with Retry-After (D6/D7). Runtime-agnostic: handleRequest takes
// plain values and returns { status, body, headers }; a Workers/D1
// entry point maps fetch events onto it. No deployment exists here.
//
// deps: { sha256hex(text)->hex, newId()->"SUB-...",
//         installSalt, quorumRuns=5, quorumInstalls=3,
//         quotaPerDay=20, maxGroups=200 }

import {
  API_VERSION,
  STATS_VERSION,
  MAX_BODY_BYTES,
  validateSubmission,
  configHashFor,
} from "./validate.js";
import { mean, median, iqr } from "./stats.js";
import { groupCohorts, passesQuorum, saltedInstall } from "./cohort.js";

export const DEFAULTS = {
  quorumRuns: 5,
  quorumInstalls: 3,
  quotaPerDay: 20,
  maxGroups: 200,
  maxModels: 500,
};

/** Bounded regeneration when newId() collides on the primary key. */
export const SUBMISSION_ID_ATTEMPTS = 3;

const SHA64 = /^[0-9a-f]{64}$/;
const BUILD_KEY = /^[A-Za-z0-9]+:[a-z_]+$/;

function err(code, message, details) {
  const body = { error: { code, message } };
  if (details !== undefined) body.error.details = details;
  return body;
}

// node:sqlite reports every engine failure as code ERR_SQLITE_ERROR,
// so classification reads message/errstr text. D1 error shapes are
// UNVERIFIED and must be mapped in future D1 adapter work, not here.
function errorText(e) {
  return `${e?.code ?? ""} ${e?.errcode ?? ""} ${e?.message ?? ""} ${e?.errstr ?? ""}`;
}

function isBusyError(e) {
  const text = errorText(e);
  return e?.errcode === 5 || /SQLITE_BUSY/i.test(text) ||
    /database is locked/i.test(text);
}

function isUniqueConflict(e, table, column) {
  const pattern = new RegExp(`UNIQUE constraint failed: ${table}\\.${column}`);
  return pattern.test(errorText(e));
}

function busyResponse() {
  return {
    status: 503,
    body: err("temporarily_unavailable", "database busy; retry shortly"),
    headers: { "retry-after": "1" },
  };
}

/** Seconds from nowMs until the next UTC midnight (quota window). */
function retryAfterSecs(nowMs) {
  const dayStart = Date.parse(`${utcDay(nowMs)}T00:00:00Z`);
  return Math.max(1, Math.ceil((dayStart + 24 * 60 * 60 * 1000 - nowMs) / 1000));
}

function bearerToken(headers) {
  const auth = headers.authorization ?? headers.Authorization ?? "";
  const match = /^Bearer ([A-Za-z0-9._~+/-]{8,256}=*)$/.exec(auth.trim());
  return match ? match[1] : null;
}

async function findInstall(db, deps, headers) {
  const token = bearerToken(headers);
  if (token === null) return null;
  const row = await db.get(
    "SELECT install_id, revoked_at FROM installs WHERE token_hash = ?",
    deps.sha256hex(token),
  );
  if (!row || row.revoked_at !== null) return null;
  return row.install_id;
}

function utcDay(nowMs) {
  return new Date(nowMs).toISOString().slice(0, 10);
}

function utcNow(nowMs) {
  return new Date(nowMs).toISOString();
}

// ---------- POST /v1/submissions ----------

async function postSubmissions(req) {
  const { db, deps, nowMs, headers, bodyText } = req;
  const installId = await findInstall(db, deps, headers);
  if (installId === null) {
    return {
      status: 401,
      body: err("unauthorized", "valid per-install upload token required"),
    };
  }
  if (Buffer.byteLength(bodyText, "utf8") > MAX_BODY_BYTES) {
    return {
      status: 413,
      body: err("body_too_large", "submission exceeds 128 KiB"),
    };
  }
  let envelope;
  try {
    envelope = JSON.parse(bodyText);
  } catch {
    return { status: 400, body: err("malformed_json", "body is not JSON") };
  }
  // Validation precedes quota accounting: invalid bodies never burn quota.
  const validated = validateSubmission(envelope, nowMs, deps);
  if (!validated.ok) {
    return {
      status: 422,
      body: err("invalid_submission", "schema validation failed",
        validated.rejections),
    };
  }
  const row = validated.row;
  // Fast-path dedup outside the atomic batch: replays cost no quota.
  const seen = await db.get(
    "SELECT submission_id, eligibility FROM submissions WHERE content_hash = ?",
    row.content_hash,
  );
  if (seen) {
    return {
      status: 200,
      body: {
        submission_id: seen.submission_id,
        eligibility: seen.eligibility,
        duplicate: true,
      },
    };
  }
  const day = utcDay(nowMs);
  const receivedAt = utcNow(nowMs);
  for (let attempt = 1; attempt <= SUBMISSION_ID_ATTEMPTS; attempt += 1) {
    const submissionId = deps.newId();
    // Row values in column order; the batch below appends its own
    // guard parameters after these (adapter contract: values first).
    const values = [
      submissionId, installId, row.content_hash, row.result_json, row.run_id,
      row.device_class, row.model_sha, row.model_size_bytes,
      row.quantization_label, row.workload_id, row.prompt_sha,
      row.n_ctx, row.n_threads, row.n_predict, row.sampler,
      row.config_hash, row.runtime_impl, row.runtime_backend,
      row.runtime_version, row.effective_opt, row.effective_opt_status,
      row.build_key, row.status, row.tps_gen, row.tps_prefill,
      row.gen_tokens, row.prompt_tokens, row.collected_at, row.app_version,
      row.mem_avail_pct_start, row.mem_below_advisory, row.mem_low_memory,
      row.consent_policy_version, row.consent_accepted_at, row.consent_scopes,
      receivedAt,
    ];
    let admitted;
    try {
      // One atomic batch; every decision is encoded in SQL because
      // D1 batch() cannot return mid-batch reads to JavaScript.
      // The quota verdict is the conditional INSERT's change count:
      // admitted rows insert exactly one row; rejected claims are
      // compensated in-batch, leaving the counter exact (G2).
      const results = await db.batch([
        {
          sql: `INSERT INTO daily_counts (install_id, day, count)
                VALUES (?, ?, 1)
                ON CONFLICT (install_id, day) DO UPDATE
                SET count = daily_counts.count + 1`,
          params: [installId, day],
        },
        {
          sql: `INSERT INTO submissions (
                  submission_id, install_id, content_hash, result_json, run_id,
                  device_class, model_sha, model_size_bytes, quantization_label,
                  workload_id, prompt_sha, n_ctx, n_threads, n_predict, sampler,
                  config_hash, runtime_impl, runtime_backend, runtime_version,
                  effective_opt, effective_opt_status, build_key, status,
                  tps_gen, tps_prefill, gen_tokens, prompt_tokens,
                  collected_at, app_version,
                  mem_avail_pct_start, mem_below_advisory, mem_low_memory,
                  consent_policy_version, consent_accepted_at, consent_scopes,
                  eligibility, received_at, published_at
                )
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                       ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                       ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                       ?, ?, ?, ?, ?, 'SUBMITTED', ?, NULL
                WHERE (SELECT count FROM daily_counts
                       WHERE install_id = ? AND day = ?) <= ?`,
          params: [...values, installId, day, deps.quotaPerDay],
        },
        {
          sql: `INSERT INTO eligibility_history
                  (submission_id, from_state, to_state, reason, at)
                SELECT ?, NULL, ?, ?, ?
                WHERE EXISTS (SELECT 1 FROM submissions
                              WHERE submission_id = ?)`,
          params: [submissionId, "SUBMITTED", "received", receivedAt,
            submissionId],
        },
        {
          sql: `UPDATE daily_counts SET count = count - 1
                WHERE install_id = ? AND day = ?
                  AND NOT EXISTS (SELECT 1 FROM submissions
                                  WHERE submission_id = ?)`,
          params: [installId, day, submissionId],
        },
      ]);
      admitted = results[1].changes === 1;
    } catch (e) {
      if (isUniqueConflict(e, "submissions", "content_hash")) {
        // Lost a duplicate race: the winner committed first. The
        // rolled-back claim cost nothing; resolve against committed rows.
        const winner = await db.get(
          "SELECT submission_id, eligibility FROM submissions WHERE content_hash = ?",
          row.content_hash,
        );
        if (winner) {
          return {
            status: 200,
            body: {
              submission_id: winner.submission_id,
              eligibility: winner.eligibility,
              duplicate: true,
            },
          };
        }
        return busyResponse();
      }
      if (isUniqueConflict(e, "submissions", "submission_id")) {
        continue; // Regenerate once more, bounded above.
      }
      throw e; // Dispatcher maps to a sanitized 500/503 (D6).
    }
    if (!admitted) {
      // The batch committed with zero inserted rows: quota spent,
      // counter already compensated. Nothing was stored.
      return {
        status: 429,
        body: err("quota_exceeded", "daily upload quota exceeded"),
        headers: { "retry-after": String(retryAfterSecs(nowMs)) },
      };
    }
    return {
      status: 201,
      body: {
        submission_id: submissionId,
        eligibility: "SUBMITTED",
        duplicate: false,
      },
    };
  }
  // newId() keeps colliding: fail closed without storing anything.
  return { status: 500, body: err("internal", "temporary backend failure") };
}

// ---------- GET / DELETE /v1/submissions/{id} ----------

async function ownedSubmission(db, deps, headers, submissionId) {
  const installId = await findInstall(db, deps, headers);
  if (installId === null) return { auth: false };
  const row = await db.get(
    `SELECT submission_id, install_id, run_id, content_hash,
            eligibility, received_at, published_at
     FROM submissions WHERE submission_id = ?`,
    submissionId,
  );
  // Foreign and missing ids share one 404: no cross-install oracle.
  if (!row || row.install_id !== installId) return { auth: true, row: null };
  return { auth: true, row };
}

async function getSubmission(req, submissionId) {
  const { db, deps, headers } = req;
  const owned = await ownedSubmission(db, deps, headers, submissionId);
  if (!owned.auth) {
    return {
      status: 401,
      body: err("unauthorized", "valid per-install upload token required"),
    };
  }
  if (!owned.row) {
    return { status: 404, body: err("not_found", "no such submission") };
  }
  const trail = await db.all(
    `SELECT from_state AS from_state, to_state, reason, at
     FROM eligibility_history WHERE submission_id = ? ORDER BY id ASC`,
    submissionId,
  );
  return {
    status: 200,
    body: {
      submission_id: owned.row.submission_id,
      run_id: owned.row.run_id,
      content_hash: owned.row.content_hash,
      eligibility: owned.row.eligibility,
      received_at: owned.row.received_at,
      published_at: owned.row.published_at,
      eligibility_history: trail,
    },
  };
}

async function deleteSubmission(req, submissionId, nowMs) {
  const { db, deps, headers } = req;
  const owned = await ownedSubmission(db, deps, headers, submissionId);
  if (!owned.auth) {
    return {
      status: 401,
      body: err("unauthorized", "valid per-install upload token required"),
    };
  }
  if (!owned.row) {
    return { status: 404, body: err("not_found", "no such submission") };
  }
  if (owned.row.eligibility === "REVOKED") {
    return {
      status: 200,
      body: { submission_id: submissionId, eligibility: "REVOKED" },
    };
  }
  const at = utcNow(nowMs);
  // Atomic + idempotent in batch-compatible form: read outside, then
  // one guarded batch. The guard names the observed from-state, so an
  // applied revoke records exact history (eligibility moves forward
  // only: no ABA). A lost race retries once against fresh state (G2).
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const fresh = await db.get(
      "SELECT eligibility FROM submissions WHERE submission_id = ?",
      submissionId,
    );
    if (!fresh || fresh.eligibility === "REVOKED") break;
    const [updated] = await db.batch([
      {
        sql: `UPDATE submissions SET eligibility = 'REVOKED'
              WHERE submission_id = ? AND eligibility = ?`,
        params: [submissionId, fresh.eligibility],
      },
      {
        sql: `INSERT INTO eligibility_history
                (submission_id, from_state, to_state, reason, at)
              SELECT ?, ?, 'REVOKED', ?, ?
              WHERE (SELECT eligibility FROM submissions
                     WHERE submission_id = ?) = 'REVOKED'`,
        params: [submissionId, fresh.eligibility,
          "consent withdrawn by owner", at, submissionId],
      },
    ]);
    if (updated.changes === 1) break;
  }
  const final = await db.get(
    "SELECT eligibility FROM submissions WHERE submission_id = ?",
    submissionId,
  );
  return {
    status: 200,
    body: { submission_id: submissionId, eligibility: final.eligibility },
  };
}

// ---------- public aggregates ----------

function aggregateRows(rows, deps) {
  const groups = [];
  for (const g of groupCohorts(rows, deps)) {
    const entry = {
      cohort: g.cohort,
      cohort_hash: g.cohort_hash,
      n: g.n,
      installs: g.installs,
    };
    if (passesQuorum(g, deps)) {
      const gens = g.members.map((r) => r.tps_gen);
      const spread = iqr(gens);
      entry.status = "published";
      entry.aggregates = {
        mean_gen_tps: mean(gens),
        median_gen_tps: median(gens),
        min_gen_tps: Math.min(...gens),
        max_gen_tps: Math.max(...gens),
        iqr_gen_tps: spread,
        iqr_note: spread === null
          ? "IQR needs 8 samples; this cohort is thinner"
          : null,
        mean_abs_pct_error: null,
        error_note: "no prediction-loop coverage in v1 submissions",
      };
    } else {
      entry.status = "insufficient-evidence";
      entry.reason = "below publication quorum";
      entry.aggregates = null;
    }
    groups.push(entry);
  }
  return groups;
}

async function getAggregates(req) {
  const { db, deps, query } = req;
  if (typeof query.model_sha !== "string" || !SHA64.test(query.model_sha)) {
    return {
      status: 400,
      body: err("bad_request", "model_sha must be 64 lowercase hex chars"),
    };
  }
  const filters = ["model_sha = ?"];
  const params = [query.model_sha];
  if (query.config_hash !== undefined) {
    if (typeof query.config_hash !== "string" ||
        !SHA64.test(query.config_hash)) {
      return {
        status: 400,
        body: err("bad_request", "config_hash must be 64 lowercase hex"),
      };
    }
    filters.push("config_hash = ?");
    params.push(query.config_hash);
  }
  if (query.build !== undefined) {
    if (typeof query.build !== "string" || !BUILD_KEY.test(query.build)) {
      return {
        status: 400,
        body: err("bad_request", "build must look like O2:operator_attested"),
      };
    }
    filters.push("build_key = ?");
    params.push(query.build);
  }
  if (query.device_class !== undefined) {
    if (typeof query.device_class !== "string" ||
        query.device_class.length === 0 ||
        query.device_class.length > 64) {
      return {
        status: 400,
        body: err("bad_request", "device_class must be 1..64 chars"),
      };
    }
    filters.push("device_class = ?");
    params.push(query.device_class);
  }
  if (query.workload_id !== undefined) {
    if (typeof query.workload_id !== "string" ||
        query.workload_id.length === 0 ||
        query.workload_id.length > 64) {
      return {
        status: 400,
        body: err("bad_request", "workload_id must be 1..64 chars"),
      };
    }
    filters.push("workload_id = ?");
    params.push(query.workload_id);
  }
  if (query.prompt_sha !== undefined) {
    if (typeof query.prompt_sha !== "string" ||
        !SHA64.test(query.prompt_sha)) {
      return {
        status: 400,
        body: err("bad_request", "prompt_sha must be 64 lowercase hex"),
      };
    }
    filters.push("prompt_sha = ?");
    params.push(query.prompt_sha);
  }
  // Throughput aggregates use completed measurements only;
  // failed attempts stay stored evidence, never averages.
  const rows = await db.all(
    `SELECT model_sha, quantization_label, device_class, workload_id,
            prompt_sha, runtime_impl, runtime_backend, build_key, config_hash,
            n_ctx, n_threads, n_predict, sampler, tps_gen, install_id
     FROM submissions
     WHERE eligibility = 'PUBLISHED' AND status = 'COMPLETED'
       AND tps_gen > 0 AND ${filters.join(" AND ")}`,
    ...params,
  );
  const groups = aggregateRows(rows, deps);
  const truncated = groups.length > deps.maxGroups;
  return {
    status: 200,
    body: {
      api_version: API_VERSION,
      stats_version: STATS_VERSION,
      methodology: STATS_VERSION,
      tier: "global_eligible",
      quorum: {
        min_runs: deps.quorumRuns,
        min_installs: deps.quorumInstalls,
      },
      groups: truncated ? groups.slice(0, deps.maxGroups) : groups,
      truncated,
    },
  };
}

// ---------- public models ----------

async function getModels(req) {
  const { db, deps } = req;
  const rows = await db.all(
    `SELECT model_sha, install_id, collected_at
     FROM submissions
     WHERE eligibility = 'PUBLISHED' AND status = 'COMPLETED'
       AND tps_gen > 0`,
  );
  const byModel = new Map();
  for (const row of rows) {
    if (!byModel.has(row.model_sha)) {
      byModel.set(row.model_sha, { runs: 0, installs: new Set(), latest: "" });
    }
    const entry = byModel.get(row.model_sha);
    entry.runs += 1;
    entry.installs.add(saltedInstall(deps, row.install_id));
    if (row.collected_at > entry.latest) entry.latest = row.collected_at;
  }
  const artifacts = [...byModel.entries()]
    .map(([model_sha, entry]) => ({
      model_sha,
      published_runs: entry.runs,
      installs: entry.installs.size,
      latest_collected_at: entry.latest,
    }))
    .sort((a, b) => (a.model_sha < b.model_sha ? -1 : 1));
  const truncated = artifacts.length > deps.maxModels;
  return {
    status: 200,
    body: {
      api_version: API_VERSION,
      artifacts: truncated ? artifacts.slice(0, deps.maxModels) : artifacts,
      truncated,
    },
  };
}

// ---------- public model candidates ----------

async function getCandidates(req) {
  const { db, deps, query } = req;
  let limit = 5;
  if (query.limit !== undefined) {
    limit = Number(query.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 5) {
      return {
        status: 400,
        body: err("bad_request", "limit must be an integer 1..5"),
      };
    }
  }
  for (const key of ["device_class", "abi", "runtime_impl",
    "runtime_backend", "workload_id"]) {
    if (query[key] !== undefined &&
        (typeof query[key] !== "string" || query[key].length === 0 ||
          query[key].length > 64)) {
      return {
        status: 400,
        body: err("bad_request", `${key} must be 1..64 chars`),
      };
    }
  }
  if (query.intended_n_ctx !== undefined) {
    const n = Number(query.intended_n_ctx);
    if (!Number.isInteger(n) || n < 1 || n > 1048576) {
      return {
        status: 400,
        body: err("bad_request", "intended_n_ctx out of range"),
      };
    }
  }
  const filters = ["eligibility = 'PUBLISHED'", "status = 'COMPLETED'",
    "tps_gen > 0"];
  const params = [];
  if (query.device_class !== undefined) {
    filters.push("device_class = ?");
    params.push(query.device_class);
  }
  if (query.runtime_impl !== undefined) {
    filters.push("runtime_impl = ?");
    params.push(query.runtime_impl);
  }
  if (query.runtime_backend !== undefined) {
    filters.push("runtime_backend = ?");
    params.push(query.runtime_backend);
  }
  if (query.workload_id !== undefined) {
    filters.push("workload_id = ?");
    params.push(query.workload_id);
  }
  // v1 has no catalog table: candidates derive from observed
  // published artifacts. License and source URL are unknown
  // until catalog curation; the response says so. Catalog facts
  // (size, license, provenance) stay distinct from measured
  // cohort evidence below: means always come from ONE exact
  // quorum-passing cohort, never from a pooled set (D3).
  const rows = await db.all(
    `SELECT model_sha, model_size_bytes, tps_gen, install_id, collected_at,
            quantization_label, device_class, workload_id, prompt_sha,
            runtime_impl, runtime_backend, build_key, config_hash,
            n_ctx, n_threads, n_predict, sampler
     FROM submissions WHERE ${filters.join(" AND ")}`,
    ...params,
  );
  const byModel = new Map();
  for (const row of rows) {
    if (!byModel.has(row.model_sha)) byModel.set(row.model_sha, []);
    byModel.get(row.model_sha).push(row);
  }
  const candidates = [...byModel.entries()]
    .sort(([a], [b]) => (a < b ? -1 : 1))
    .slice(0, limit)
    .map(([model_sha, members]) => {
      const installs = new Set(members.map((r) =>
        saltedInstall(deps, r.install_id)));
      const newest = members.reduce((a, b) =>
        a.collected_at > b.collected_at ? a : b);
      const cohorts = groupCohorts(members, deps);
      const qualifying = cohorts.filter((g) => passesQuorum(g, deps));
      const entry = {
        model_sha,
        size_bytes: newest.model_size_bytes,
        source_provenance: "observed_submissions",
        license: "unknown",
        matching_runs: members.length,
        installs: installs.size,
        qualifying_cohorts: qualifying.length,
      };
      if (qualifying.length > 0) {
        // Representative cohort: the largest quorum-passing exact
        // cohort, deterministic tie-break on cohort_hash. Thin
        // cohorts contribute no statistics (D3).
        const rep = [...qualifying].sort((a, b) =>
          (b.n - a.n) || (a.cohort_hash < b.cohort_hash ? -1 : 1))[0];
        const gens = rep.members.map((r) => r.tps_gen);
        entry.status = "published";
        entry.cohort = rep.cohort;
        entry.cohort_hash = rep.cohort_hash;
        entry.cohort_runs = rep.n;
        entry.cohort_installs = rep.installs;
        entry.median_gen_tps = median(gens);
        entry.mean_gen_tps = mean(gens);
      } else {
        entry.status = "insufficient-evidence";
        entry.median_gen_tps = null;
        entry.mean_gen_tps = null;
      }
      return entry;
    });
  return {
    status: 200,
    body: {
      api_version: API_VERSION,
      candidates,
      note: "ordered by model_sha; no performance ranking is implied",
      quorum: {
        min_runs: deps.quorumRuns,
        min_installs: deps.quorumInstalls,
      },
    },
  };
}

// ---------- dispatcher ----------

export function withDefaults(deps) {
  return { ...DEFAULTS, ...deps };
}

async function dispatch(req) {
  const { method, path } = req;
  if (method === "POST" && path === "/v1/submissions") {
    return await postSubmissions(req);
  }
  const submissionMatch = /^\/v1\/submissions\/([A-Za-z0-9-]+)$/.exec(path);
  if (submissionMatch) {
    if (method === "GET") return await getSubmission(req, submissionMatch[1]);
    if (method === "DELETE") {
      return await deleteSubmission(req, submissionMatch[1], req.nowMs);
    }
    return { status: 405, body: err("method_not_allowed", "method not allowed") };
  }
  if (method === "GET" && path === "/v1/benchmarks/aggregates") {
    return await getAggregates(req);
  }
  if (method === "GET" && path === "/v1/models") return await getModels(req);
  if (method === "GET" && path === "/v1/evidence/model-candidates") {
    return await getCandidates(req);
  }
  if (method === "GET" && path === "/v1/benchmarks/runs") {
    // Scaffolding present, explicitly inactive: individual-run
    // public disclosure awaits its separate policy approval.
    return {
      status: 404,
      body: err("runs_disclosure_disabled",
        "individual-run public disclosure is disabled until its policy is approved"),
    };
  }
  return { status: 404, body: err("not_found", "no such route") };
}

export async function handleRequest(input) {
  const req = { ...input, deps: withDefaults(input.deps) };
  let res;
  try {
    res = await dispatch(req);
  } catch (e) {
    // Backstop (D6): database and unexpected failures surface as
    // bounded envelopes. Internal text (SQL, paths, ids) NEVER
    // leaves the backend; contention is retryable, the rest is not.
    if (isBusyError(e)) return busyResponse();
    return {
      status: 500,
      body: err("internal", "temporary backend failure"),
      headers: {},
    };
  }
  return { status: res.status, body: res.body, headers: res.headers ?? {} };
}

export { configHashFor };
