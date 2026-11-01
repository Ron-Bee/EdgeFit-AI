// EF-GLOBAL-01 demo device-ingestion dispatcher (DEMO-02, route B).
//
// SEPARATE Worker from staging AND from the public reader: this is
// the ONLY device-reachable write path, and ONLY for
// owner-enrolled demo installations (no public enrollment exists).
// Exactly three source-matched operations, enforced by a route
// allowlist in front of the SAME reviewed builders staging uses
// (api.js handleRequest: validation, per-install auth, quotas,
// dedup, owner-scoped reads, authorized withdrawal):
//
//   POST   /v1/submissions
//   GET    /v1/submissions/{id}
//   DELETE /v1/submissions/{id}
//
// Everything else is 404 (unknown routes, aggregate/model/
// candidate reads, admin, enrollment); wrong methods on the known
// shapes are 405, mirroring api.js dispatch exactly. No code path
// can mint credentials, change installs.kind, touch consent
// allowlists, or reach any other table than the three handlers'
// own bounded statements. Origin stays server-assigned from the
// authenticated install's kind (migration 003 + subselect).
//
// Abuse control mirrors the public reader (PUB-03 finding: the
// Rate Limiting API binding attaches on Free but does NOT
// enforce): the binding call is a plan-upgrade path ONLY; the
// control that bites is the isolate-local per-IP budget below.
// Backstops: per-install daily quotas (server-enforced),
// 128 KiB bodies, the account 100k req/day cap, and rapid disable
// (delete this Worker; staging is unaffected).
import { createHash, randomUUID } from "node:crypto";
import { handleRequest } from "./api.js";
import { openD1 } from "./d1.js";
import { MAX_BODY_BYTES } from "./validate.js";

/** POST-only collection path. */
export const INGEST_COLLECTION = "/v1/submissions";
/** Item-id shape: identical to api.js dispatch (no oracle drift). */
export const INGEST_ITEM_RE = /^\/v1\/submissions\/([A-Za-z0-9-]+)$/;

/** Matches the ingest binding's period in wrangler.ingest.toml. */
export const INGEST_RATE_LIMIT_RETRY_SECS = 60;

/** In-isolate per-IP budget: same numbers/semantics as the public
 * reader (see PUBLIC_BUCKET_LIMIT there). Isolates are many, so
 * this stops naive single-source floods, not distributed abuse. */
export const INGEST_BUCKET_LIMIT = 120;
export const INGEST_BUCKET_WINDOW_MS = 60000;
export const INGEST_BUCKET_MAX_KEYS = 2000;
const ingestBuckets = new Map();

/** Test seam only: clear isolate rate buckets between cases. */
export function __resetIngestRateBuckets() {
  ingestBuckets.clear();
}

function isolateBucketAllows(key, nowMs) {
  let bucket = ingestBuckets.get(key);
  if (!bucket || nowMs - bucket.resetMs >= INGEST_BUCKET_WINDOW_MS) {
    bucket = { resetMs: nowMs, count: 0 };
    ingestBuckets.set(key, bucket);
  }
  bucket.count += 1;
  if (ingestBuckets.size > INGEST_BUCKET_MAX_KEYS) {
    ingestBuckets.delete(ingestBuckets.keys().next().value);
  }
  return bucket.count <= INGEST_BUCKET_LIMIT;
}

/** Backend constants. No publishConsentVersions (empty allowlist
 * default: demo uploads record their version but publish nothing
 * until a real version is separately approved + deployed). No
 * installSalt: write paths never salt identities (reads do). */
export const INGEST_DEPS = {
  quorumRuns: 5,
  quorumInstalls: 3,
  quotaPerDay: 20,
  maxGroups: 200,
  maxModels: 500,
};

function errBody(code, message) {
  return { error: { code, message } };
}

function jsonResponse(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json",
      "cache-control": "no-store", ...headers },
  });
}

function failClosed(status, code, message, headers) {
  return jsonResponse(status, errBody(code, message), headers);
}

function rateLimitKey(request) {
  const ip = request?.headers?.get?.("cf-connecting-ip");
  if (typeof ip === "string" && ip.length > 0 && ip.length <= 64) {
    return `ip:${ip}`;
  }
  return "ip:unknown";
}

function utf8Length(text) {
  return new TextEncoder().encode(text).length;
}

let missingBindingWarned = false;

export function makeIngestDepsFromEnv() {
  return {
    sha256hex: (text) =>
      createHash("sha256").update(text, "utf8").digest("hex"),
    newId: () => `SUB-${randomUUID().replace(/-/g, "")}`,
    ...INGEST_DEPS,
  };
}

/**
 * Translate one device request. ctx: { db, deps, nowMs?,
 * rateLimit?, onRateLimitError? }. Never throws for
 * request-shaped input. Only the three allowlisted operations
 * reach handleRequest, and only with their exact method+shape.
 */
export async function ingestFetch(request, ctx) {
  const db = ctx?.db;
  const deps = ctx?.deps;
  const nowMs = ctx?.nowMs ?? Date.now();
  if (!db || !deps) {
    return failClosed(500, "internal", "temporary backend failure");
  }
  if (!isolateBucketAllows(rateLimitKey(request), nowMs)) {
    return failClosed(429, "rate_limited",
      "too many requests; retry shortly",
      { "retry-after": String(INGEST_RATE_LIMIT_RETRY_SECS) });
  }
  const limiter = ctx?.rateLimit;
  const onLimiterError = ctx?.onRateLimitError;
  if ("rateLimit" in ctx && typeof limiter?.limit !== "function") {
    if (!missingBindingWarned) {
      missingBindingWarned = true;
      if (typeof onLimiterError === "function") {
        try {
          onLimiterError(new Error("ingest rate-limit binding unusable"));
        } catch {
          // Telemetry must never break requests.
        }
      }
    }
  } else if (limiter && typeof limiter.limit === "function") {
    let allowed = true;
    try {
      const verdict = await limiter.limit({ key: rateLimitKey(request) });
      if (!verdict || typeof verdict !== "object" ||
          typeof verdict.success !== "boolean") {
        if (typeof onLimiterError === "function") {
          try {
            onLimiterError(new Error("rate limiter verdict malformed"));
          } catch { /* never break requests */ }
        }
      } else {
        allowed = verdict.success;
      }
    } catch (e) {
      if (typeof onLimiterError === "function") {
        try {
          onLimiterError(e);
        } catch { /* never break requests */ }
      }
      allowed = true;
    }
    if (!allowed) {
      return failClosed(429, "rate_limited",
        "too many requests; retry shortly",
        { "retry-after": String(INGEST_RATE_LIMIT_RETRY_SECS) });
    }
  }
  const method = String(request?.method ?? "").toUpperCase();
  let url;
  try {
    url = new URL(String(request?.url ?? ""));
  } catch {
    return failClosed(400, "bad_request", "unparseable request URL");
  }
  const path = url.pathname;
  // Route allowlist mirrors api.js dispatch decisions exactly:
  // collection POST, item GET/DELETE, item wrong-method 405,
  // everything else 404 (reads, admin, enrollment, unknown).
  const itemMatch = INGEST_ITEM_RE.exec(path);
  const allowed =
    (method === "POST" && path === INGEST_COLLECTION) ||
    (itemMatch && (method === "GET" || method === "DELETE"));
  if (!allowed) {
    if (itemMatch) {
      return failClosed(405, "method_not_allowed", "method not allowed");
    }
    return failClosed(404, "not_found", "no such route");
  }
  const headers = {};
  if (request.headers &&
      typeof request.headers.forEach === "function") {
    request.headers.forEach((value, key) => {
      headers[String(key).toLowerCase()] = value;
    });
  }
  let bodyText = "";
  if (method === "POST") {
    const declared = request.headers?.get?.("content-length");
    if (typeof declared === "string" && /^\d+$/.test(declared.trim()) &&
        Number(declared) > MAX_BODY_BYTES) {
      return failClosed(413, "body_too_large", "submission exceeds 128 KiB");
    }
    try {
      bodyText = await request.text();
    } catch {
      return failClosed(400, "bad_request", "unreadable request body");
    }
    if (utf8Length(bodyText) > MAX_BODY_BYTES) {
      return failClosed(413, "body_too_large", "submission exceeds 128 KiB");
    }
  }
  let res;
  try {
    res = await handleRequest({ db, deps, nowMs, method, path,
      query: {}, headers, bodyText });
  } catch {
    return failClosed(500, "internal", "temporary backend failure");
  }
  const out = { ...res.headers };
  if (typeof out["retry-after"] !== "string" ||
      !/^\d+$/.test(out["retry-after"])) {
    delete out["retry-after"];
  }
  return jsonResponse(res.status, res.body, out);
}

/**
 * Deployed entry point. env: { DB, RATE_LIMIT? }. Fetch ONLY: no
 * scheduled() export, no cron. No secrets: per-install bearer
 * tokens authenticate at the app layer; nothing to configure.
 */
export default {
  async fetch(request, env) {
    let db;
    if (!env || !env.DB) {
      return failClosed(500, "internal", "temporary backend failure");
    }
    try {
      db = openD1(env.DB);
    } catch {
      return failClosed(500, "internal", "temporary backend failure");
    }
    return await ingestFetch(request, { db,
      deps: makeIngestDepsFromEnv(), nowMs: Date.now(),
      rateLimit: env.RATE_LIMIT,
      onRateLimitError: (e) => console.warn(
        `ingest rate limiter fault kind=${e?.code ?? e?.name ?? "error"}`) });
  },
};
