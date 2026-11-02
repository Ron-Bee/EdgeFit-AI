// EF-GLOBAL-01 public read-only evidence dispatcher (PUB-03).
//
// SEPARATE Worker from the staging submission API: anonymous GET
// only, no writes, no credentials, no scheduler. Publication is
// enforced server-side by the SAME reviewed builders the staging
// API uses (api.js handleRequest + publicationClause), behind a
// route allowlist so submission/withdrawal/admin paths structurally
// cannot be reached. While the publication allowlist is EMPTY this
// Worker truthfully serves empty-but-valid populations, plus the
// counts-only /v1/network/status pre-publication contribution
// summary (no identifiers, payloads, or statistics).
//
// Security notes:
// * CORS allows exactly the public website origin; it is browser
//   UX only, never authorization (anonymous curl sees the same
//   publication-filtered body by design).
// * Cache-Control: no-store until revocation-safe caching of
//   genuine data is separately qualified (PUB-03 E).
// * PUBLIC_SALT is a dedicated population salt: it MUST differ
//   from the staging INSTALL_SALT (different Worker, different
//   secret) and is stable so salted install counts don't flap.
// * Rate limiting mirrors worker.js semantics: fail open with an
//   observable hook + warn-once alarm when the binding is absent
//   but expected; exceeding returns the bridge-v1.1 429 envelope.
import { createHash } from "node:crypto";
import { handleRequest } from "./api.js";
import { openD1 } from "./d1.js";

/** Exact website origin allowed CORS access. */
export const PUBLIC_WEB_ORIGIN = "https://edgefit-ai.pages.dev";

/** Routes served. Everything else is 404 (no private-route oracle). */
export const PUBLIC_ROUTES = [
  "/v1/models",
  "/v1/benchmarks/aggregates",
  "/v1/evidence/model-candidates",
  "/v1/network/status",
];

/** Methods answered (HEAD rides GET handling, body stripped). */
export const PUBLIC_METHODS = ["GET", "HEAD", "OPTIONS"];

/** Overlong URLs are rejected before parsing (abuse bound). */
export const MAX_URL_LENGTH = 8192;

/** Matches the public binding's period in wrangler.public.toml. */
export const PUBLIC_RATE_LIMIT_RETRY_SECS = 60;

/**
 * In-isolate per-IP rate budget (PUB-03 diagnostic result). The
 * Workers Rate Limiting API binding attaches on Free but does NOT
 * enforce (140-in-1.2s and limit=5 probes never tripped; binding
 * present, verdicts always success, no alarm). So the binding call
 * below is kept as a plan-upgrade path ONLY; the control that
 * actually bites on Free is this isolate-local fixed window:
 * PUBLIC_BUCKET_LIMIT requests per PUBLIC_BUCKET_WINDOW_MS per
 * client IP. Honest limits: isolates are many and ephemeral, so
 * the effective account-wide ceiling is limit x isolate count;
 * this stops naive single-source floods, not distributed abuse.
 * Backstops: tiny bounded responses, the account 100k req/day
 * cap, monitoring, and the rapid-disable procedure (set
 * workers_dev=false and redeploy). Same numbers as the binding
 * rule so semantics converge if the plan ever enforces it.
 */
export const PUBLIC_BUCKET_LIMIT = 120;
export const PUBLIC_BUCKET_WINDOW_MS = 60000;
export const PUBLIC_BUCKET_MAX_KEYS = 2000;
const publicBuckets = new Map();

/** Test seam only: clear isolate rate buckets between cases. */
export function __resetPublicRateBuckets() {
  publicBuckets.clear();
}

function isolateBucketAllows(key, nowMs) {
  let bucket = publicBuckets.get(key);
  if (!bucket || nowMs - bucket.resetMs >= PUBLIC_BUCKET_WINDOW_MS) {
    bucket = { resetMs: nowMs, count: 0 };
    publicBuckets.set(key, bucket);
  }
  bucket.count += 1;
  if (publicBuckets.size > PUBLIC_BUCKET_MAX_KEYS) {
    publicBuckets.delete(publicBuckets.keys().next().value);
  }
  return bucket.count <= PUBLIC_BUCKET_LIMIT;
}

/** Backend constants (H2 quorum; no publishConsentVersions: the
 * validate.js helpers default to an EMPTY allowlist when the key
 * is absent, so staging publishes nothing until a real consent
 * version is approved. Do not add versions without that record. */
export const PUBLIC_DEPS = {
  quorumRuns: 5,
  quorumInstalls: 3,
  quotaPerDay: 20,
  maxGroups: 200,
  maxModels: 500,
};

function errBody(code, message) {
  return { error: { code, message } };
}

function baseHeaders(extra = {}) {
  return {
    "content-type": "application/json",
    "cache-control": "no-store",
    vary: "Origin",
    ...extra,
  };
}

function jsonResponse(status, body, headers = {}) {
  return new Response(JSON.stringify(body),
    { status, headers: baseHeaders(headers) });
}

function failClosed(status, code, message, headers) {
  return jsonResponse(status, errBody(code, message), headers);
}

/** CORS headers for exactly the website origin, else none. */
function corsHeaders(request) {
  const origin = request?.headers?.get?.("origin");
  if (origin === PUBLIC_WEB_ORIGIN) {
    return { "access-control-allow-origin": PUBLIC_WEB_ORIGIN };
  }
  return {};
}

function rateLimitKey(request) {
  const ip = request?.headers?.get?.("cf-connecting-ip");
  if (typeof ip === "string" && ip.length > 0 && ip.length <= 64) {
    return `ip:${ip}`;
  }
  return "ip:unknown";
}

let missingBindingWarned = false;

/**
 * Backend deps from public-Worker env. Throws when PUBLIC_SALT is
 * absent: without the salt, install identities cannot be salted.
 * Never reads staging INSTALL_SALT (different secret by design).
 */
export function makePublicDepsFromEnv(env) {
  if (!env || typeof env.PUBLIC_SALT !== "string" ||
      env.PUBLIC_SALT.length === 0) {
    throw new Error("public worker misconfigured");
  }
  return {
    sha256hex: (text) =>
      createHash("sha256").update(text, "utf8").digest("hex"),
    installSalt: env.PUBLIC_SALT,
    ...PUBLIC_DEPS,
  };
}

/**
 * Translate one anonymous read Request. ctx: { db, deps, nowMs?,
 * rateLimit?, onRateLimitError? }. Never throws for
 * request-shaped input: every failure surfaces as a sanitized
 * JSON response with CORS + no-store headers. No code path here
 * can mutate D1: only the four GET builders run, and only with
 * GET/HEAD semantics (HEAD strips the body after handling).
 */
export async function publicFetch(request, ctx) {
  const cors = () => corsHeaders(request);
  const db = ctx?.db;
  const deps = ctx?.deps;
  const nowMs = ctx?.nowMs ?? Date.now();
  if (!db || !deps) {
    return failClosed(500, "internal", "temporary backend failure",
      cors());
  }
  // In-isolate budget FIRST (see PUBLIC_BUCKET_LIMIT): it is the
  // control that enforces on Free. Junk requests cost budget
  // before any parsing, like the staging binding check.
  if (!isolateBucketAllows(rateLimitKey(request), nowMs)) {
    return failClosed(429, "rate_limited",
      "too many requests; retry shortly",
      { ...cors(), "retry-after": String(PUBLIC_RATE_LIMIT_RETRY_SECS) });
  }
  const limiter = ctx?.rateLimit;
  const onLimiterError = ctx?.onRateLimitError;
  const reportLimiterFault = (fault) => {
    if (typeof onLimiterError !== "function") return;
    try {
      onLimiterError(fault);
    } catch {
      // Telemetry must never break requests.
    }
  };
  if ("rateLimit" in ctx && typeof limiter?.limit !== "function") {
    // Binding expected but unusable: fail open AND stay visible.
    // Warn-once per isolate; callers that never pass rateLimit
    // (host tests) stay silent, mirroring the staging tick alarm.
    if (!missingBindingWarned) {
      missingBindingWarned = true;
      reportLimiterFault(
        new Error("public rate-limit binding unusable"));
    }
  } else if (limiter && typeof limiter.limit === "function") {
    let allowed = true;
    try {
      const verdict = await limiter.limit({ key: rateLimitKey(request) });
      if (!verdict || typeof verdict !== "object" ||
          typeof verdict.success !== "boolean") {
        reportLimiterFault(new Error("rate limiter verdict malformed"));
      } else {
        allowed = verdict.success;
      }
    } catch (e) {
      reportLimiterFault(e);
      allowed = true;
    }
    if (!allowed) {
      return failClosed(429, "rate_limited",
        "too many requests; retry shortly",
        { ...cors(), "retry-after": String(PUBLIC_RATE_LIMIT_RETRY_SECS) });
    }
  }
  const rawUrl = String(request?.url ?? "");
  if (rawUrl.length > MAX_URL_LENGTH) {
    return failClosed(400, "bad_request", "request URL too long", cors());
  }
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    return failClosed(400, "bad_request", "unparseable request URL", cors());
  }
  const path = url.pathname;
  // Path before method: unknown routes are 404 for EVERY method,
  // so error codes never oracle private-route existence. Only the
  // four allowlisted paths can answer 405.
  if (!PUBLIC_ROUTES.includes(path)) {
    return failClosed(404, "not_found", "no such route", cors());
  }
  const method = String(request?.method ?? "").toUpperCase();
  if (!PUBLIC_METHODS.includes(method)) {
    return failClosed(405, "method_not_allowed", "method not allowed",
      cors());
  }
  if (method === "OPTIONS") {
    // CORS preflight: no database touch. The allowlist is about
    // browser permission, not data: actual reads still pass
    // publicationClause server-side.
    return new Response(null, {
      status: 204,
      headers: {
        ...cors(),
        "access-control-allow-methods": "GET, HEAD, OPTIONS",
        "access-control-max-age": "600",
        "cache-control": "no-store",
        vary: "Origin",
      },
    });
  }
  const query = {};
  for (const [key, value] of url.searchParams) {
    if (!(key in query)) query[key] = value;
  }
  let res;
  try {
    res = await handleRequest({ db, deps, nowMs, method: "GET", path,
      query, headers: {}, bodyText: "" });
  } catch {
    return failClosed(500, "internal", "temporary backend failure",
      cors());
  }
  if (method === "HEAD") {
    return new Response(null,
      { status: res.status, headers: { ...baseHeaders(), ...cors() } });
  }
  return jsonResponse(res.status, res.body, cors());
}

/**
 * Deployed entry point. env: { DB, PUBLIC_SALT, RATE_LIMIT? }.
 * Fetch ONLY: no scheduled() export, no cron, no writes.
 */
export default {
  async fetch(request, env) {
    let db;
    let deps;
    try {
      if (!env || !env.DB) throw new Error("public worker misconfigured");
      db = openD1(env.DB);
      deps = makePublicDepsFromEnv(env);
    } catch {
      return failClosed(500, "internal", "temporary backend failure",
        corsHeaders(request));
    }
    return await publicFetch(request, { db, deps, nowMs: Date.now(),
      rateLimit: env.RATE_LIMIT,
      onRateLimitError: (e) => console.warn(
        `public rate limiter fault kind=${e?.code ?? e?.name ?? "error"}`) });
  },
};
