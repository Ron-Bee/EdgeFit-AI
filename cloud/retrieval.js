// EF-CLOUD-01A bounded Muse retrieval (master 13), G1B cohorts.
// One fixed query shape over PUBLISHED quorum-passing cohorts:
// no raw rows, no install identities, no SQL passthrough.
// The model cites cohort hashes; the backend owns the math.
// Grouping is the shared canonical implementation (D8): the
// partition here is identical to public aggregates by construction.
//
// retrievalDeps: { sha256hex, installSalt, deviceClasses[],
//                  quorumRuns, quorumInstalls }

import { mean, median } from "./stats.js";
import { groupCohorts, passesQuorum } from "./cohort.js";

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
            prompt_sha, runtime_impl, runtime_backend, build_key, config_hash,
            n_ctx, n_threads, n_predict, sampler, tps_gen, install_id
     FROM submissions
     WHERE eligibility = 'PUBLISHED' AND status = 'COMPLETED'
       AND tps_gen > 0 AND model_sha = ? AND device_class = ?`,
    modelId, deviceClass,
  );
  const cites = [];
  for (const g of groupCohorts(rows, deps)) {
    if (!passesQuorum(g, deps)) continue;
    const gens = g.members.map((r) => r.tps_gen);
    cites.push({
      model_sha: g.cohort.model_sha,
      device_class: g.cohort.device_class,
      cohort_hash: g.cohort_hash,
      prompt_sha: g.cohort.prompt_sha,
      config: {
        n_ctx: g.cohort.config.n_ctx,
        n_threads: g.cohort.config.n_threads,
        n_predict: g.cohort.config.n_predict,
        sampler: g.cohort.config.sampler,
      },
      build: g.cohort.build,
      workload_id: g.cohort.workload_id,
      median_gen_tps: median(gens),
      mean_gen_tps: mean(gens),
      n: g.n,
      installs: g.installs,
    });
  }
  return {
    ok: true,
    cites: cites.slice(0, limit),
    quorum: {
      min_runs: deps.quorumRuns, min_installs: deps.quorumInstalls,
    },
  };
}
