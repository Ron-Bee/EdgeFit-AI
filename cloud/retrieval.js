// EF-CLOUD-01A bounded Muse retrieval (master 13). One
// fixed query shape over PUBLISHED quorum-passing cohorts:
// no raw rows, no install identities, no SQL passthrough.
// The model cites cohort hashes; the backend owns the math.
//
// retrievalDeps: { sha256hex, installSalt, deviceClasses[],
//                  quorumRuns, quorumInstalls }

import { mean, median } from "./stats.js";

const SHA64 = /^[0-9a-f]{64}$/;

export const RETRIEVAL_MAX_LIMIT = 3;

export function evidenceRetrieve(db, params, deps) {
  const { device_class: deviceClass, model_id: modelId } = params;
  let limit = params.limit === undefined ? RETRIEVAL_MAX_LIMIT
    : Number(params.limit);
  if (typeof modelId !== "string" || !SHA64.test(modelId)) {
    return { ok: false, code: "bad_request", message: "model_id must be 64 hex" };
  }
  if (!deps.deviceClasses.includes(deviceClass)) {
    return {
      ok: false, code: "bad_request",
      message: "device_class outside the pinned set",
    };
  }
  if (!Number.isInteger(limit) || limit < 1 ||
      limit > RETRIEVAL_MAX_LIMIT) {
    return {
      ok: false, code: "bad_request",
      message: `limit must be 1..${RETRIEVAL_MAX_LIMIT}`,
    };
  }
  const rows = db.all(
    `SELECT model_sha, quantization_label, device_class, workload_id,
            runtime_impl, runtime_backend, build_key, config_hash,
            n_ctx, n_threads, n_predict, sampler, tps_gen, install_id
     FROM submissions
     WHERE eligibility = 'PUBLISHED' AND status = 'COMPLETED'
       AND tps_gen > 0 AND model_sha = ? AND device_class = ?`,
    modelId, deviceClass,
  );
  const byCohort = new Map();
  for (const row of rows) {
    const key = [
      row.model_sha, row.quantization_label, row.device_class,
      row.workload_id, row.runtime_impl, row.runtime_backend,
      row.build_key, row.config_hash,
    ].join("|");
    if (!byCohort.has(key)) byCohort.set(key, { sample: row, rows: [] });
    byCohort.get(key).rows.push(row);
  }
  const cites = [];
  for (const [key, { sample, rows: members }] of byCohort) {
    const installs = new Set(members.map((r) =>
      deps.sha256hex(`${r.install_id}:${deps.installSalt}`)));
    if (members.length < deps.quorumRuns ||
        installs.size < deps.quorumInstalls) {
      continue;
    }
    const gens = members.map((r) => r.tps_gen);
    cites.push({
      model_sha: sample.model_sha,
      device_class: sample.device_class,
      cohort_hash: deps.sha256hex(key),
      config: {
        n_ctx: sample.n_ctx,
        n_threads: sample.n_threads,
        n_predict: sample.n_predict,
        sampler: sample.sampler,
      },
      build: sample.build_key,
      workload_id: sample.workload_id,
      median_gen_tps: median(gens),
      mean_gen_tps: mean(gens),
      n: members.length,
      installs: installs.size,
    });
  }
  cites.sort((a, b) => (a.cohort_hash < b.cohort_hash ? -1 : 1));
  return {
    ok: true,
    cites: cites.slice(0, limit),
    quorum: {
      min_runs: deps.quorumRuns, min_installs: deps.quorumInstalls,
    },
  };
}
