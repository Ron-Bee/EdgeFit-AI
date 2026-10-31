// G1B canonical cohort identity (D1/D2/D8). The single shared
// implementation behind public aggregates, model candidates, and
// internal Muse retrieval. Nothing else may define cohort grouping.
//
// A cohort is the exact-match group over ALL authoritative fields
// (ef-stats-01 "Cohort definition"):
//   model_sha, quantization_label, device_class, workload_id,
//   prompt_sha, runtime_impl, runtime_backend, build_key, config_hash
// where build_key is effective_opt:effective_opt_status and
// config_hash covers (n_ctx, n_threads, n_predict, sampler).
//
// Serialization is a version-tagged JSON array of strings. JSON
// string encoding is unambiguous, so delimiter collisions (D2) are
// impossible by construction, and prompt_sha (D1) is a full member.
//
// IDENTIFIER CHANGE (documented, owner-visible): v1 cohort_hash was
// sha256 of an ambiguous `|`-join and therefore semantically invalid;
// v2 hashes are not comparable to v1. No production data exists, so
// no migration is required. config_hash and build_key are unchanged.

export const COHORT_IDENTITY_VERSION = 2;

export function cohortParts(row) {
  return [
    row.model_sha,
    row.quantization_label,
    row.device_class,
    row.workload_id,
    row.prompt_sha,
    row.runtime_impl,
    row.runtime_backend,
    row.build_key,
    row.config_hash,
  ];
}

export function cohortKey(row) {
  return `v${COHORT_IDENTITY_VERSION}:${JSON.stringify(cohortParts(row))}`;
}

export function cohortHash(deps, row) {
  return deps.sha256hex(cohortKey(row));
}

export function saltedInstall(deps, installId) {
  return deps.sha256hex(`${installId}:${deps.installSalt}`);
}

export function cohortOf(row) {
  return {
    model_sha: row.model_sha,
    quantization: row.quantization_label,
    device_class: row.device_class,
    workload_id: row.workload_id,
    prompt_sha: row.prompt_sha,
    runtime_impl: row.runtime_impl,
    runtime_backend: row.runtime_backend,
    build: row.build_key,
    config: {
      n_ctx: row.n_ctx,
      n_threads: row.n_threads,
      n_predict: row.n_predict,
      sampler: row.sampler,
    },
  };
}

/**
 * Partition rows into exact cohorts, deterministically ordered by
 * cohort_hash. Each group carries its members for per-cohort
 * statistics; callers must never pool members across groups.
 */
export function groupCohorts(rows, deps) {
  const byKey = new Map();
  for (const row of rows) {
    const key = cohortKey(row);
    if (!byKey.has(key)) byKey.set(key, { sample: row, members: [] });
    byKey.get(key).members.push(row);
  }
  const groups = [...byKey.values()].map(({ sample, members }) => {
    const installs = new Set(
      members.map((r) => saltedInstall(deps, r.install_id)));
    return {
      cohort: cohortOf(sample),
      cohort_hash: cohortHash(deps, sample),
      n: members.length,
      installs: installs.size,
      members,
    };
  });
  groups.sort((a, b) => (a.cohort_hash < b.cohort_hash ? -1 : 1));
  return groups;
}

export function passesQuorum(group, deps) {
  return group.n >= deps.quorumRuns && group.installs >= deps.quorumInstalls;
}
