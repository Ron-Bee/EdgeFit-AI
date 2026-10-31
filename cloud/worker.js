// EF-GLOBAL-01 G2-B1 Cloudflare Worker entry point. Converts fetch()
// requests into the existing backend handler format and handler
// results into Responses. Routing, validation, quorum, consent,
// revocation and privacy semantics ALL stay in cloud/api.js: the
// Worker is a thin, independently testable translation layer, not
// a second implementation.
//
// Edge checks performed here (before handleRequest):
//   methods other than GET/POST/DELETE -> 405 (unsupported ops)
//   paths outside /v1/                 -> 404 (no admin surface)
//   Content-Length over the cap        -> 413 without reading a byte
//   actual POST body over the cap      -> 413 after reading
// Everything finer (exact routes, auth, quorum) is handleRequest's
// single source of truth, so edge and handler can never disagree
// about what an endpoint means.
//
// Response mapping preserves status, JSON body, and retry-after;
// the ONLY passthrough header is a numeric retry-after, and the
// content type is always application/json. Error envelopes reuse
// the handler's { error: { code, message } } vocabulary.
//
// Hashing stays SYNCHRONOUS (node:crypto) under the wrangler
// template's nodejs_compat flag: the smallest justified Worker
// adaptation, avoiding an async-hash re-plumbing of every caller.
// INSTALL_SALT and the D1 binding arrive via env; both missing and
// misconfigured bindings fail closed as sanitized 500s that never
// name which secret or binding is at fault.

import { createHash } from "node:crypto";
import { handleRequest } from "./api.js";
import { MAX_BODY_BYTES } from "./validate.js";
import { openD1 } from "./d1.js";

/** Methods the v1 API speaks; anything else is 405. */
export const WORKER_METHODS = ["GET", "POST", "DELETE"];

/** Staging-pinned backend constants (H2: quorum stays 5/3). */
export const WORKER_DEPS = {
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
    headers: { "content-type": "application/json", ...headers },
  });
}

function failClosed(status, code, message, headers) {
  return jsonResponse(status, errBody(code, message), headers);
}

/**
 * Backend deps from Worker env. Throws when INSTALL_SALT is absent:
 * without the salt, install identities cannot be salted, so the
 * Worker must not serve at all. Callers map this to a 500 that
 * never names the missing secret.
 */
export function makeDepsFromEnv(env) {
  if (!env || typeof env.INSTALL_SALT !== "string" ||
      env.INSTALL_SALT.length === 0) {
    throw new Error("worker misconfigured");
  }
  return {
    sha256hex: (text) =>
      createHash("sha256").update(text, "utf8").digest("hex"),
    newId: () => `SUB-${crypto.randomUUID().replace(/-/g, "")}`,
    installSalt: env.INSTALL_SALT,
    ...WORKER_DEPS,
  };
}

function utf8Length(text) {
  return new TextEncoder().encode(text).length;
}

function retryHeaders(res) {
  // Only a numeric retry-after crosses the boundary: quota (429)
  // and contention (503) stay retryable with honest delays, and no
  // other handler header can leak through by accident.
  const value = res.headers?.["retry-after"];
  if (typeof value === "string" && /^\d+$/.test(value)) {
    return { "retry-after": value };
  }
  return {};
}

/**
 * Translate one fetch Request through the backend. ctx: { db, deps,
 * nowMs? }. db is ANY adapter honoring the async contract (D1 in
 * staging, node:sqlite or the D1-shape mock in tests); deps is a
 * backend deps object. Returns a Response. Never throws for
 * request-shaped input: handler and translation failures both
 * surface as sanitized JSON responses.
 */
export async function workerFetch(request, ctx) {
  const db = ctx?.db;
  const deps = ctx?.deps;
  const nowMs = ctx?.nowMs ?? Date.now();
  if (!db || !deps) return failClosed(500, "internal",
    "temporary backend failure");
  const method = String(request?.method ?? "").toUpperCase();
  if (!WORKER_METHODS.includes(method)) {
    return failClosed(405, "method_not_allowed", "method not allowed");
  }
  let url;
  try {
    url = new URL(request.url);
  } catch {
    return failClosed(400, "bad_request", "unparseable request URL");
  }
  const path = url.pathname;
  if (!path.startsWith("/v1/")) {
    return failClosed(404, "not_found", "no such route");
  }
  const query = {};
  for (const [key, value] of url.searchParams) {
    if (!(key in query)) query[key] = value;
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
    // Pre-read guard: a lying-or-huge Content-Length never forces
    // the Worker to buffer the body to find out.
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
    res = await handleRequest({
      db,
      deps,
      nowMs,
      method,
      path,
      query,
      headers,
      bodyText,
    });
  } catch {
    // handleRequest already sanitizes; this is unreachable-by-
    // contract belt and braces, kept silent and generic.
    return failClosed(500, "internal", "temporary backend failure");
  }
  return jsonResponse(res.status, res.body, retryHeaders(res));
}

/**
 * Deployed entry point. env: { DB, INSTALL_SALT }. NOT exercised
 * against real Cloudflare here: local tests drive workerFetch with
 * injected adapters, and the default export is covered with mock
 * env bindings only. Missing binding or salt fails closed as an
 * identical 500 either way (test M).
 */
export default {
  async fetch(request, env) {
    let db;
    let deps;
    try {
      if (!env || !env.DB) throw new Error("worker misconfigured");
      db = openD1(env.DB);
      deps = makeDepsFromEnv(env);
    } catch {
      return failClosed(500, "internal", "temporary backend failure");
    }
    return await workerFetch(request, { db, deps, nowMs: Date.now() });
  },
};
