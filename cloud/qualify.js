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
 * Conditional single-step transition in one atomic batch. The guard
 * names the expected from-state and every follow-on write is
 * conditional on the transition having applied, so the unit stays
 * valid on D1 batch() (no mid-batch reads). Returns true when the
 * guarded UPDATE applied exactly one row.
 */
function transition(db, submissionId, from, to, reason, at) {
  const statements = [
    {
      sql: `UPDATE submissions SET eligibility = ?
            WHERE submission_id = ? AND eligibility = ?`,
      params: [to, submissionId, from],
    },
    {
      sql: `INSERT INTO eligibility_history
              (submission_id, from_state, to_state, reason, at)
            SELECT ?, ?, ?, ?, ?
            WHERE (SELECT eligibility FROM submissions
                   WHERE submission_id = ?) = ?`,
      params: [submissionId, from, to, reason, at, submissionId, to],
    },
  ];
  if (to === "PUBLISHED") {
    statements.push({
      sql: `UPDATE submissions SET published_at = ?
            WHERE submission_id = ? AND eligibility = 'PUBLISHED'`,
      params: [at, submissionId],
    });
  }
  const [updated] = db.batch(statements);
  return updated.changes === 1;
}

/**
 * Anomaly suite for a parsed result + stored row context.
 * Returns null when qualified, else the hold reason.
 */
export function anomalyHold(db, row, result, nowMs, deps) {
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
  const earliest = db.get(
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
  const recent = db.get(
    `SELECT COUNT(*) AS n FROM submissions
     WHERE install_id = ? AND received_at >= ?`,
    row.install_id, dayAgo,
  );
  if (recent && recent.n > deps.quotaPerDay) {
    return "install velocity above review threshold";
  }
  return null;
}

export function runQualification(db, nowMs, deps) {
  const at = utcNow(nowMs);
  const promoted = [];
  const held = [];
  const deferred = [];
  // The snapshot is a worklist only: every step below re-reads its
  // row outside the batch and the batch re-guards on from-state,
  // yielding to concurrent writers.
  const pending = db.all(
    `SELECT * FROM submissions
     WHERE eligibility IN ('SUBMITTED', 'VALIDATED', 'QUALIFIED')
     ORDER BY received_at ASC, submission_id ASC LIMIT ?`,
    BATCH_LIMIT,
  );
  for (const snap of pending) {
    try {
      const id = snap.submission_id;
      const live = db.get(
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
        if (!transition(db, id, "SUBMITTED", "VALIDATED",
          "schema recheck passed", at)) {
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
        const hold = anomalyHold(db, live, parsed.value, nowMs, deps);
        if (hold !== null) {
          held.push({ submission_id: id, state: "VALIDATED", reason: hold });
          continue;
        }
        if (!transition(db, id, "VALIDATED", "QUALIFIED",
          "anomaly suite passed", at)) {
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
        if (!transition(db, id, "QUALIFIED", "PUBLISHED",
          "consent current; published", at)) {
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
