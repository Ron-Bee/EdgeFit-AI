// EdgeFit evidence data adapter (public, validated, cohort-safe).
// Consumes ONLY authorized public publication-filtered evidence in
// the v1 contract shape. Ships with the EMPTY provider active: no
// public endpoint is configured, so every consumer renders the
// NO-PUBLIC-SOURCE state. HttpProvider exists for a FUTURE
// authorized endpoint and refuses to run unconfigured.
// No staging URLs, no credentials, no fabricated values here.
"use strict";

export const API_VERSION = "1";
export const QUORUM_RUNS = 5;
export const QUORUM_INSTALLS = 3;
export const MAX_GROUPS = 200;
export const MAX_MODELS = 500;
export const MAX_NET_CLASSES = 100;
export const FETCH_TIMEOUT_MS = 10000;
export const MAX_PAYLOAD_BYTES = 1048576;
// Server-computed publication-blocker codes. Unknown codes fail the
// whole payload: new blockers must be reviewed before display.
export const NET_WHY_CODES = ["no_contributions",
  "publication_gate_closed", "quorum_unmet",
  "rows_awaiting_qualification"];

// Dashboard data states (V3-01 Phase I).
export const STATE = {
  NO_SOURCE: "no-source", // A: no public source configured/active
  BELOW_QUORUM: "below-quorum", // B: source reports thin cohorts
  FILTERED_EMPTY: "filtered-empty", // C: data exists, filters match none
  SOURCE_ERROR: "source-error", // D: authorized source unreachable/bad
  READY: "ready", // E: validated authorized data
};

export class ProviderError extends Error {
  constructor(state, message) {
    super(message);
    this.state = state;
  }
}

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function checkVersion(body, what) {
  if (!isPlainObject(body) || body.api_version !== API_VERSION) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      `${what}: missing or unsupported api_version`);
  }
}

function numOrNull(v) {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

// Allowlisted cohort identity parts: the EXACT server shape from
// cloud/cohort.js cohortOf() (v2 contract). Unknown keys are
// dropped, never rendered: private/unapproved fields cannot leak
// through future server changes. config_hash is NOT echoed by any
// v1 response, so it stays unavailable: identity detail comes from
// the nested config object plus cohort_hash (never derived here).
const COHORT_KEYS = ["model_sha", "quantization", "device_class",
  "workload_id", "prompt_sha", "runtime_impl", "runtime_backend",
  "build"];
const CONFIG_KEYS = ["n_ctx", "n_threads", "n_predict", "sampler"];
// Legacy flat keys from a shape the server never emitted. A
// present legacy key that CONTRADICTS its canonical twin fails the
// whole cohort (loud contract break, never a silent preference);
// an equal one is ignored as redundant.
const LEGACY_TWINS = [["quantization_label", "quantization"],
  ["build_key", "build"]];

function intOrNull(v) {
  return Number.isInteger(v) ? v : null;
}

function cleanConfig(c) {
  if (c === null || c === undefined) return null;
  if (!isPlainObject(c)) {
    throw new ProviderError(STATE.SOURCE_ERROR, "cohort config malformed");
  }
  return {
    n_ctx: intOrNull(c.n_ctx),
    n_threads: intOrNull(c.n_threads),
    n_predict: intOrNull(c.n_predict),
    sampler: typeof c.sampler === "string" ? c.sampler : null,
  };
}

export function cleanCohort(c) {
  if (!isPlainObject(c)) {
    throw new ProviderError(STATE.SOURCE_ERROR, "cohort not an object");
  }
  const out = {};
  for (const k of COHORT_KEYS) {
    out[k] = typeof c[k] === "string" ? c[k] : null;
  }
  for (const [legacy, canon] of LEGACY_TWINS) {
    if (c[legacy] !== undefined && c[legacy] !== out[canon]) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        `contradictory cohort identity: ${legacy}`);
    }
  }
  out.config = cleanConfig(c.config);
  return out;
}

function cleanAggregates(a) {
  if (a === null) return null;
  if (!isPlainObject(a)) {
    throw new ProviderError(STATE.SOURCE_ERROR, "aggregates malformed");
  }
  return {
    mean_gen_tps: numOrNull(a.mean_gen_tps),
    median_gen_tps: numOrNull(a.median_gen_tps),
    min_gen_tps: numOrNull(a.min_gen_tps),
    max_gen_tps: numOrNull(a.max_gen_tps),
    iqr_gen_tps: numOrNull(a.iqr_gen_tps),
    iqr_note: typeof a.iqr_note === "string" ? a.iqr_note : null,
  };
}

export function cleanGroup(g) {
  if (!isPlainObject(g)) {
    throw new ProviderError(STATE.SOURCE_ERROR, "group not an object");
  }
  if (!Number.isInteger(g.n) || g.n < 0) {
    throw new ProviderError(STATE.SOURCE_ERROR, "group n invalid");
  }
  if (!Number.isInteger(g.installs) || g.installs < 0) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "group installs invalid");
  }
  const published = g.status === "published";
  if (!published && g.status !== "insufficient-evidence") {
    throw new ProviderError(STATE.SOURCE_ERROR, "group status invalid");
  }
  const aggregates = published ? cleanAggregates(g.aggregates) : null;
  if (published && aggregates === null) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "published group lacks aggregates");
  }
  return {
    cohort: cleanCohort(g.cohort),
    cohort_hash: typeof g.cohort_hash === "string" ? g.cohort_hash : null,
    n: g.n,
    installs: g.installs,
    status: g.status,
    aggregates,
  };
}

export function cleanArtifact(a) {
  if (!isPlainObject(a) || typeof a.model_sha !== "string") {
    throw new ProviderError(STATE.SOURCE_ERROR, "artifact malformed");
  }
  if (!Number.isInteger(a.published_runs) || a.published_runs < 0) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "artifact published_runs invalid");
  }
  if (!Number.isInteger(a.installs) || a.installs < 0) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "artifact installs invalid");
  }
  return {
    model_sha: a.model_sha,
    published_runs: a.published_runs,
    installs: a.installs,
    latest_collected_at:
      typeof a.latest_collected_at === "string"
        ? a.latest_collected_at : null,
  };
}

export function cleanCandidate(c) {
  if (!isPlainObject(c) || typeof c.model_sha !== "string") {
    throw new ProviderError(STATE.SOURCE_ERROR, "candidate malformed");
  }
  const published = c.status === "published";
  if (!published && c.status !== "insufficient-evidence") {
    throw new ProviderError(STATE.SOURCE_ERROR, "candidate status invalid");
  }
  return {
    model_sha: c.model_sha,
    size_bytes: Number.isInteger(c.size_bytes) ? c.size_bytes : null,
    matching_runs: Number.isInteger(c.matching_runs)
      ? c.matching_runs : null,
    installs: Number.isInteger(c.installs) ? c.installs : null,
    qualifying_cohorts: Number.isInteger(c.qualifying_cohorts)
      ? c.qualifying_cohorts : null,
    status: c.status,
    cohort: published ? cleanCohort(c.cohort) : null,
    cohort_hash: published && typeof c.cohort_hash === "string"
      ? c.cohort_hash : null,
    cohort_runs: published && Number.isInteger(c.cohort_runs)
      ? c.cohort_runs : null,
    cohort_installs: published && Number.isInteger(c.cohort_installs)
      ? c.cohort_installs : null,
    median_gen_tps: published ? numOrNull(c.median_gen_tps) : null,
    mean_gen_tps: published ? numOrNull(c.mean_gen_tps) : null,
  };
}

// The shipped provider: no public endpoint exists, so every call
// reports NO_SOURCE. This is the verified-safe empty implementation.
export class EmptyProvider {
  get kind() {
    return "empty";
  }
  async listModels() {
    throw new ProviderError(STATE.NO_SOURCE,
      "no public evidence endpoint configured");
  }
  async getAggregates() {
    throw new ProviderError(STATE.NO_SOURCE,
      "no public evidence endpoint configured");
  }
  async getCandidates() {
    throw new ProviderError(STATE.NO_SOURCE,
      "no public evidence endpoint configured");
  }
  async getNetworkStatus() {
    throw new ProviderError(STATE.NO_SOURCE,
      "no public evidence endpoint configured");
  }
}

// Counts-only pre-publication network summary (/v1/network/status).
// Fail-closed like every other cleaner: wrong scope, unknown
// blocker codes, negative counts, or oversized listings reject the
// whole payload. Unknown object keys are dropped, never rendered,
// so future server fields cannot leak identifiers into the page.
export function cleanNetworkStatus(s) {
  if (!isPlainObject(s)) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "network status not an object");
  }
  checkVersion(s, "network status");
  if (s.scope !== "staging-contributions") {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "network status scope mismatch");
  }
  for (const k of ["accepted_submissions", "contributing_installs"]) {
    if (!Number.isInteger(s[k]) || s[k] < 0) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        `network status ${k} invalid`);
    }
  }
  if (!Array.isArray(s.consent_versions) || s.consent_versions.length > 64 ||
      !s.consent_versions.every((v) => typeof v === "string")) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "network status consent_versions invalid");
  }
  if (!Array.isArray(s.by_device_class) ||
      s.by_device_class.length > MAX_NET_CLASSES + 1) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "network status by_device_class invalid");
  }
  const classes = s.by_device_class.map((c) => {
    if (!isPlainObject(c) || typeof c.device_class !== "string" ||
        c.device_class === "" || c.device_class.length > 128 ||
        !Number.isInteger(c.accepted) || c.accepted < 0) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "network status device class invalid");
    }
    return { device_class: c.device_class, accepted: c.accepted };
  });
  const stages = {};
  for (const k of ["SUBMITTED", "VALIDATED", "QUALIFIED", "PUBLISHED",
      "REVOKED"]) {
    const v = isPlainObject(s.stages) ? s.stages[k] : undefined;
    if (!Number.isInteger(v) || v < 0) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        `network status stage ${k} invalid`);
    }
    stages[k] = v;
  }
  let closest = null;
  if (s.closest_cohort !== null) {
    if (!isPlainObject(s.closest_cohort)) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "network status closest_cohort invalid");
    }
    const q = s.closest_cohort;
    for (const k of ["runs", "installs", "awaiting_qualification"]) {
      if (!Number.isInteger(q[k]) || q[k] < 0) {
        throw new ProviderError(STATE.SOURCE_ERROR,
          `network status closest ${k} invalid`);
      }
    }
    if (typeof q.cohort_hash !== "string") {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "network status cohort_hash invalid");
    }
    closest = { cohort: cleanCohort(q.cohort),
      cohort_hash: q.cohort_hash, runs: q.runs,
      installs: q.installs,
      awaiting_qualification: q.awaiting_qualification };
  }
  if (!isPlainObject(s.quorum) ||
      !Number.isInteger(s.quorum.min_runs) || s.quorum.min_runs <= 0 ||
      !Number.isInteger(s.quorum.min_installs) ||
      s.quorum.min_installs <= 0) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "network status quorum invalid");
  }
  if (typeof s.publication_open !== "boolean") {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "network status publication_open invalid");
  }
  if (!Array.isArray(s.why_not_published) ||
      !s.why_not_published.every((w) => NET_WHY_CODES.includes(w))) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "network status why_not_published invalid");
  }
  if (typeof s.generated_at !== "string" ||
      Number.isNaN(Date.parse(s.generated_at))) {
    throw new ProviderError(STATE.SOURCE_ERROR,
      "network status generated_at invalid");
  }
  return {
    acceptedSubmissions: s.accepted_submissions,
    contributingInstalls: s.contributing_installs,
    consentVersions: [...s.consent_versions],
    byDeviceClass: classes,
    classesTruncated: s.classes_truncated === true,
    stages,
    closest,
    quorum: { minRuns: s.quorum.min_runs,
      minInstalls: s.quorum.min_installs },
    publicationOpen: s.publication_open,
    whyNotPublished: [...s.why_not_published],
    generatedAt: s.generated_at,
  };
}

// Future authorized endpoint. NEVER defaults to staging: the base
// URL must be passed explicitly, must be https, and staging hosts
// are refused outright.
const FORBIDDEN_HOSTS = ["edgefit-evidence-staging-staging"];

export class HttpProvider {
  constructor(baseUrl, fetchImpl) {
    if (typeof baseUrl !== "string" || baseUrl === "") {
      throw new ProviderError(STATE.NO_SOURCE,
        "HTTP provider requires an explicit authorized base URL");
    }
    const u = new URL(baseUrl);
    if (u.protocol !== "https:") {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "HTTP provider requires https");
    }
    if (FORBIDDEN_HOSTS.some((h) => u.hostname.startsWith(h))) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "staging backend is not a public evidence source");
    }
    this.baseUrl = baseUrl.replace(/\/$/, "");
    // Bind the default fetch: browsers reject a detached global
    // fetch called as an object method ("Illegal invocation").
    this.fetchImpl = fetchImpl || fetch.bind(globalThis);
    this.inFlight = 0;
  }
  get kind() {
    return "http";
  }
  async getJson(path) {
    if (this.inFlight > 0) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "refresh already in progress");
    }
    this.inFlight += 1;
    try {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), FETCH_TIMEOUT_MS);
      let res;
      try {
        res = await this.fetchImpl(`${this.baseUrl}${path}`, {
          method: "GET",
          redirect: "manual",
          signal: ctl.signal,
        });
      } finally {
        clearTimeout(timer);
      }
      if (res.status !== 200) {
        throw new ProviderError(STATE.SOURCE_ERROR,
          `evidence source HTTP ${res.status}`);
      }
      const len = res.headers.get("content-length");
      if (len !== null && Number(len) > MAX_PAYLOAD_BYTES) {
        throw new ProviderError(STATE.SOURCE_ERROR,
          "evidence payload exceeds safe maximum");
      }
      const text = await res.text();
      if (text.length > MAX_PAYLOAD_BYTES) {
        throw new ProviderError(STATE.SOURCE_ERROR,
          "evidence payload exceeds safe maximum");
      }
      let body;
      try {
        body = JSON.parse(text);
      } catch {
        throw new ProviderError(STATE.SOURCE_ERROR,
          "evidence source returned malformed JSON");
      }
      return body;
    } catch (e) {
      if (e instanceof ProviderError) throw e;
      throw new ProviderError(STATE.SOURCE_ERROR,
        `evidence source unreachable: ${e.name || "fetch failed"}`);
    } finally {
      this.inFlight -= 1;
    }
  }
  async listModels() {
    const body = await this.getJson("/v1/models");
    checkVersion(body, "models");
    if (!Array.isArray(body.artifacts)) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "models artifacts not an array");
    }
    if (body.artifacts.length > MAX_MODELS + 1) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "models payload exceeds safe maximum");
    }
    return {
      artifacts: body.artifacts.map(cleanArtifact),
      truncated: body.truncated === true,
    };
  }
  async getAggregates(modelSha, filters) {
    if (!/^[0-9a-f]{64}$/.test(modelSha || "")) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "model_sha must be 64 lowercase hex");
    }
    const q = new URLSearchParams({ model_sha: modelSha });
    for (const k of ["device_class", "workload_id", "prompt_sha",
      "config_hash"]) {
      const v = filters ? filters[k] : undefined;
      if (v !== undefined) q.set(k, String(v));
    }
    const body = await this.getJson(
      `/v1/benchmarks/aggregates?${q.toString()}`);
    checkVersion(body, "aggregates");
    if (!Array.isArray(body.groups)) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "aggregates groups not an array");
    }
    if (body.groups.length > MAX_GROUPS + 1) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "aggregates payload exceeds safe maximum");
    }
    // Prefer the server-advertised quorum; fall back to the v1
    // contract constants only when the source omits it.
    let quorum = null;
    if (isPlainObject(body.quorum) &&
        Number.isInteger(body.quorum.min_runs) &&
        body.quorum.min_runs > 0 &&
        Number.isInteger(body.quorum.min_installs) &&
        body.quorum.min_installs > 0) {
      quorum = { minRuns: body.quorum.min_runs,
        minInstalls: body.quorum.min_installs };
    }
    return {
      groups: body.groups.map(cleanGroup),
      truncated: body.truncated === true,
      quorum,
    };
  }
  async getCandidates() {
    const body = await this.getJson("/v1/evidence/model-candidates");
    checkVersion(body, "candidates");
    if (!Array.isArray(body.candidates)) {
      throw new ProviderError(STATE.SOURCE_ERROR,
        "candidates not an array");
    }
    return { candidates: body.candidates.map(cleanCandidate) };
  }
  async getNetworkStatus() {
    const body = await this.getJson("/v1/network/status");
    return cleanNetworkStatus(body);
  }
}

// KPI derivations over VALIDATED data only. Each returns null when
// the inputs cannot honestly support it (never invent, never pool
// statistics across cohorts).
export function summarizeModels(artifacts) {
  if (!Array.isArray(artifacts)) return null;
  let runs = 0;
  let withEvidence = 0;
  let latest = null;
  for (const a of artifacts) {
    runs += a.published_runs;
    if (a.published_runs > 0) withEvidence += 1;
    if (a.latest_collected_at !== null &&
        (latest === null || a.latest_collected_at > latest)) {
      latest = a.latest_collected_at;
    }
  }
  return { publishedRuns: runs, modelsWithEvidence: withEvidence,
    latestCollectedAt: latest };
}

export function countPublishedGroups(groups) {
  if (!Array.isArray(groups)) return null;
  return groups.filter((g) => g.status === "published").length;
}

// Facet values present in validated groups (client-side subsetting
// of exact cohorts; never merges cohorts). Top-level cohort keys
// plus the nested config parts are addressable; numbers compare by
// their decimal form, missing values never match a set filter.
function facetReader(key) {
  if (COHORT_KEYS.includes(key)) return (g) => g.cohort[key];
  if (CONFIG_KEYS.includes(key)) {
    return (g) => g.cohort.config ? g.cohort.config[key] : null;
  }
  return null;
}

function facetString(v) {
  if (typeof v === "string") return v;
  return Number.isInteger(v) ? String(v) : null;
}

export function facetValues(groups, key) {
  if (!Array.isArray(groups)) return [];
  const read = facetReader(key);
  if (!read) return [];
  const seen = [];
  for (const g of groups) {
    const s = facetString(read(g));
    if (s !== null && !seen.includes(s)) seen.push(s);
  }
  if (seen.every((s) => /^-?\d+$/.test(s))) {
    return seen.sort((a, b) => Number(a) - Number(b));
  }
  return seen.sort();
}

export function applyFacets(groups, facets) {
  if (!Array.isArray(groups)) return [];
  const keys = Object.keys(facets || {})
    .filter((k) => facetReader(k) !== null &&
      typeof facets[k] === "string" && facets[k] !== "");
  if (keys.length === 0) return [...groups];
  return groups.filter((g) =>
    keys.every((k) => facetString(facetReader(k)(g)) === facets[k]));
}
