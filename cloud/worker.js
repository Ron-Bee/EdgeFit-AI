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
import { runQualification } from "./qualify.js";
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

/**
 * Scheduler batch ceiling: at most 10 rows per Cron invocation.
 * Measured host cost is ~0.7 ms CPU/row steady-state (an UPPER
 * bound: local SQLite burns CPU that D1 spends as network I/O),
 * so 10 rows sit inside the 10 ms Free CPU budget with deploy-
 * time verification. Configurable DOWNWARD per invocation only:
 * scheduledTick clamps ctx.maxRows into [1, 10]; nothing raises
 * it above this constant without a reviewed code change.
 */
export const SCHEDULED_MAX_ROWS = 10;

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
 * One bounded qualification tick. ctx: { db, deps, nowMs?, maxRows?,
 * log? }. Runs the EXISTING runQualification state machine over at
 * most maxRows pending rows (default SCHEDULED_MAX_ROWS, clamped
 * into [1, 10]); every transition, CAS guard, revocation rule,
 * cohort definition and quorum check is runQualification's own —
 * this function adds scheduling bounds and observable logging
 * ONLY. Returns the counts summary. On database failure it logs
 * a counts-only error (no rows, ids, secrets, or evidence) and
 * rethrows, so Cron retries surface persistently instead of
 * silently succeeding; the per-row containment inside
 * runQualification already converts poison rows to holds.
 * Test seam: inject log to capture lines; production passes
 * console methods bound by the scheduled() entry below.
 */
export async function scheduledTick(ctx) {
  const db = ctx?.db;
  const deps = ctx?.deps;
  const nowMs = ctx?.nowMs ?? Date.now();
  const log = ctx?.log ?? console.log.bind(console);
  const logErr = ctx?.logErr ?? console.error.bind(console);
  if (!db || !deps) {
    logErr("qualify tick misconfigured: no database or deps");
    throw new Error("worker misconfigured");
  }
  const maxRows = Math.max(1, Math.min(
    Number.isInteger(ctx?.maxRows) ? ctx.maxRows : SCHEDULED_MAX_ROWS,
    SCHEDULED_MAX_ROWS));
  let report;
  try {
    report = await runQualification(db, nowMs, deps, maxRows);
  } catch (e) {
    // Observable AND sanitized: counts and the failure's CLASS
    // only. e.g. D1 outage text (table names, SQL fragments)
    // never reaches logs; the throw keeps the tick failed.
    const kind = e?.code ?? e?.name ?? "error";
    logErr(`qualify tick failed kind=${kind} maxRows=${maxRows}`);
    throw e;
  }
  log(`qualify tick processed=${report.processed} ` +
    `promoted=${report.promoted.length} held=${report.held.length} ` +
    `deferred=${report.deferred.length} maxRows=${maxRows}`);
  return {
    processed: report.processed,
    promoted: report.promoted.length,
    held: report.held.length,
    deferred: report.deferred.length,
    maxRows,
  };
}

/**
 * Deployed entry point. env: { DB, INSTALL_SALT }. NOT exercised
 * against real Cloudflare here: local tests drive workerFetch with
 * injected adapters, and the default export is covered with mock
 * env bindings only. Missing binding or salt fails closed as an
 * identical 500 either way (test M). scheduled() runs bounded
 * qualification ticks on the Cron Trigger; it answers NO HTTP
 * route and changes no fetch behavior.
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
  async scheduled(event, env, ctx) {
    void event;
    const finish = async () => {
      const db = openD1(env.DB);
      const deps = makeDepsFromEnv(env);
      await scheduledTick({ db, deps, nowMs: Date.now() });
    };
    // ctx.waitUntil keeps the isolate alive past the handler
    // return when the runtime offers it; awaiting covers the
    // runtimes that do not. Either way the tick is fully
    // awaited: nothing runs past settlement.
    if (ctx && typeof ctx.waitUntil === "function") {
      const done = finish();
      ctx.waitUntil(done);
      await done;
    } else {
      await finish();
    }
  },
};
