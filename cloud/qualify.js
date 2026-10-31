// EF-CLOUD-01A eligibility engine, G1B atomicity (D4/D7).
// Transitions run through rules only: SUBMITTED -> VALIDATED
// (schema recheck) -> QUALIFIED (anomaly/velocity suite) ->
// PUBLISHED (consent current). REVOKED is terminal. Each single
// transition is one compare-and-swap batch: a row changed
// concurrently (notably REVOKED by its owner) is never promoted,
// never resurrected, and gains no history row. runQualification
// reports promotions, holds, and deferred rows; holds and
// deferrals leave no history row (nothing transitioned).
// Corrupt stored JSON always holds its row, never aborts a pass.

import { MAX_PLAUSIBLE_TPS, validateSubmission } from "./validate.js";

export const BATCH_LIMIT = 1000;
/** Relative tolerance for tps vs tokens*1000/ms consistency. */
export const TPS_CONSISTENCY_TOL = 0.001;

function utcNow(nowMs) {
  return new Date(nowMs).toISOString();
}

function safeParse(text) {
  try {
    const value = JSON.parse(text);
    return { ok: true, value };
  } catch {
    return { ok: false, value: null };
  }
}

/**
 * Guarded single-batch CAS transition with trigger-written audit
 * (migration 002, trg_eligibility_audit). The UPDATE applies
 * eligibility (+ published_at when publishing) only from the
 * expected from-state; the trigger appends the history row in
 * the SAME statement. A committed transition can therefore
 * neither phantom (a CAS loser's 0-row UPDATE fires nothing)
 * nor lose its audit row (no inter-step crash window — the
 * FREE-03 two-step residue is gone), and a failing trigger
 * INSERT aborts the UPDATE itself. Transition reasons live in
 * the trigger's CASE, 1:1 with the target state; the history
 * `at` stamp is commit time. Requires a database at migration
 * 002 or later: code and migrations ship and deploy together
 * (H11). Returns true when the guarded UPDATE applied.
 * Success is changes > 0, NOT === 1: D1 counts the trigger's
 * audit INSERT in the statement's changes (observed 2 on the
 * FREE-04 scratch probe) while node:sqlite counts the direct
 * UPDATE only (observed 1). Both report 0 for a CAS loser, and
 * submission_id is a primary key so at most one row can match —
 * > 0 holds exactly when this attempt won its row, on either
 * engine. (GT-25 pins this. The submit-path === 1 check in
 * api.js is unaffected — INSERTs never fire the trigger. The
 * revoke path uses the same > 0 rule since FREE-05, when the
 * trigger started covering REVOKED; RW-02 pins it.)
 */
async function transition(db, submissionId, from, to, at) {
  const [updated] = await db.batch([
    {
      sql: `UPDATE submissions SET eligibility = ?, published_at = ?
            WHERE submission_id = ? AND eligibility = ?`,
      params: [to, to === "PUBLISHED" ? at : null, submissionId, from],
    },
  ]);
  return updated.changes > 0;
}

/**
 * Anomaly suite for a parsed result + stored row context.
 * Returns null when qualified, else the hold reason.
 */
export async function anomalyHold(db, row, result, nowMs, deps) {
  if (result.measurements.tps_gen > MAX_PLAUSIBLE_TPS ||
      result.measurements.tps_prefill > MAX_PLAUSIBLE_TPS) {
    return "implausible throughput above anomaly bound";
  }
  if (row.status === "COMPLETED") {
    const m = result.measurements;
    if (m.generated_tokens <= 0 || m.gen_ms <= 0 || m.tps_gen <= 0) {
      return "COMPLETED measurement lacks positive tokens/time/throughput";
    }
    // Throughput must reconcile with its own timing inputs.
    const expected = (m.generated_tokens * 1000) / m.gen_ms;
    const rel = Math.abs(m.tps_gen - expected) / expected;
    if (rel > TPS_CONSISTENCY_TOL) {
      return "tps_gen inconsistent with tokens and gen_ms";
    }
  }
  // Same run id claimed twice (dedup already rules out
  // identical bytes): only the earliest received keeps its
  // standing; later copies hold for review.
  const earliest = await db.get(
    `SELECT submission_id FROM submissions
     WHERE run_id = ? AND eligibility != 'REVOKED'
     ORDER BY received_at ASC, submission_id ASC LIMIT 1`,
    row.run_id,
  );
  if (earliest && earliest.submission_id !== row.submission_id) {
    return `run_id collision with ${earliest.submission_id}`;
  }
  // Velocity: floods wait for review even under the daily quota.
  const dayAgo = utcNow(nowMs - 24 * 60 * 60 * 1000);
  const recent = await db.get(
    `SELECT COUNT(*) AS n FROM submissions
     WHERE install_id = ? AND received_at >= ?`,
    row.install_id, dayAgo,
  );
  if (recent && recent.n > deps.quotaPerDay) {
    return "install velocity above review threshold";
  }
  return null;
}

export async function runQualification(db, nowMs, deps, maxRows = BATCH_LIMIT) {
  const at = utcNow(nowMs);
  const promoted = [];
  const held = [];
  const deferred = [];
  // Bounded worklist: callers (notably the Free-tier scheduler)
  // cap rows per pass. Clamped to [1, BATCH_LIMIT]; the default
  // preserves every existing caller exactly.
  const limit = Math.max(1, Math.min(
    Number.isInteger(maxRows) ? maxRows : BATCH_LIMIT, BATCH_LIMIT));
  // The snapshot is a worklist only: every step below re-reads its
  // row outside the batch and the batch re-guards on from-state,
  // yielding to concurrent writers.
  const pending = await db.all(
    `SELECT * FROM submissions
     WHERE eligibility IN ('SUBMITTED', 'VALIDATED', 'QUALIFIED')
     ORDER BY received_at ASC, submission_id ASC LIMIT ?`,
    limit,
  );
  for (const snap of pending) {
    try {
      const id = snap.submission_id;
      const live = await db.get(
        "SELECT * FROM submissions WHERE submission_id = ?", id);
      if (!live || live.eligibility === "REVOKED") continue;
      if (live.eligibility === "SUBMITTED") {
        const parsed = safeParse(live.result_json);
        const consent = safeParse(live.consent_scopes);
        if (!parsed.ok) {
          held.push({
            submission_id: id,
            state: "SUBMITTED",
            reason: "stored result is not JSON",
          });
          continue;
        }
        if (!consent.ok) {
          held.push({
            submission_id: id,
            state: "SUBMITTED",
            reason: "stored consent record is not JSON",
          });
          continue;
        }
        const envelope = {
          result: parsed.value,
          consent: {
            policy_version: live.consent_policy_version,
            accepted_at: live.consent_accepted_at,
            scopes: consent.value,
          },
        };
        const checked = validateSubmission(envelope, nowMs, deps);
        if (!checked.ok) {
          held.push({
            submission_id: id,
            state: "SUBMITTED",
            reason: `schema recheck: ${checked.rejections[0].field} ${checked.rejections[0].reason}`,
          });
          continue;
        }
        if (!await transition(db, id, "SUBMITTED", "VALIDATED", at)) {
          deferred.push({
            submission_id: id,
            reason: "concurrent modification; retry next pass",
          });
          continue;
        }
        live.eligibility = "VALIDATED";
        promoted.push({ submission_id: id, from: "SUBMITTED", to: "VALIDATED" });
      }
      if (live.eligibility === "VALIDATED") {
        const parsed = safeParse(live.result_json);
        if (!parsed.ok) {
          held.push({
            submission_id: id,
            state: "VALIDATED",
            reason: "stored result is not JSON",
          });
          continue;
        }
        const hold = await anomalyHold(db, live, parsed.value, nowMs, deps);
        if (hold !== null) {
          held.push({ submission_id: id, state: "VALIDATED", reason: hold });
          continue;
        }
        if (!await transition(db, id, "VALIDATED", "QUALIFIED", at)) {
          deferred.push({
            submission_id: id,
            reason: "concurrent modification; retry next pass",
          });
          continue;
        }
        live.eligibility = "QUALIFIED";
        promoted.push({ submission_id: id, from: "VALIDATED", to: "QUALIFIED" });
      }
      if (live.eligibility === "QUALIFIED") {
        const consent = safeParse(live.consent_scopes);
        if (!consent.ok) {
          held.push({
            submission_id: id,
            state: "QUALIFIED",
            reason: "stored consent record is not JSON",
          });
          continue;
        }
        if (!consent.value.includes("aggregates")) {
          held.push({
            submission_id: id,
            state: "QUALIFIED",
            reason: "aggregate consent scope missing",
          });
          continue;
        }
        if (!await transition(db, id, "QUALIFIED", "PUBLISHED", at)) {
          deferred.push({
            submission_id: id,
            reason: "concurrent modification; retry next pass",
          });
          continue;
        }
        promoted.push({ submission_id: id, from: "QUALIFIED", to: "PUBLISHED" });
      }
    } catch {
      // One poison row (or a transient failure) retains its state
      // and its generic hold; the pass continues (D7).
      held.push({
        submission_id: snap.submission_id,
        state: snap.eligibility,
        reason: "qualification error; retained for review",
      });
    }
  }
  return { promoted, held, deferred, processed: pending.length };
}
