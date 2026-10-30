// EF-CLOUD-01A eligibility engine. Transitions run through
// rules only: SUBMITTED -> VALIDATED (schema recheck) ->
// QUALIFIED (anomaly/velocity suite) -> PUBLISHED (consent
// current). REVOKED is terminal. runQualification processes
// one pass and reports promotions plus holds; holds leave
// no history row (nothing transitioned).

import { MAX_PLAUSIBLE_TPS, validateSubmission } from "./validate.js";

export const BATCH_LIMIT = 1000;
/** Relative tolerance for tps vs tokens*1000/ms consistency. */
export const TPS_CONSISTENCY_TOL = 0.001;

function utcNow(nowMs) {
  return new Date(nowMs).toISOString();
}

function transition(db, submissionId, from, to, reason, at) {
  db.run("UPDATE submissions SET eligibility = ? WHERE submission_id = ?",
    to, submissionId);
  db.run(
    `INSERT INTO eligibility_history
       (submission_id, from_state, to_state, reason, at)
     VALUES (?, ?, ?, ?, ?)`,
    submissionId, from, to, reason, at,
  );
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
  const pending = db.all(
    `SELECT * FROM submissions
     WHERE eligibility IN ('SUBMITTED', 'VALIDATED', 'QUALIFIED')
     ORDER BY received_at ASC, submission_id ASC LIMIT ?`,
    BATCH_LIMIT,
  );
  for (const row of pending) {
    if (row.eligibility === "SUBMITTED") {
      let result;
      try {
        result = JSON.parse(row.result_json);
      } catch {
        held.push({
          submission_id: row.submission_id,
          state: "SUBMITTED",
          reason: "stored result is not JSON",
        });
        continue;
      }
      const envelope = {
        result,
        consent: {
          policy_version: row.consent_policy_version,
          accepted_at: row.consent_accepted_at,
          scopes: JSON.parse(row.consent_scopes),
        },
      };
      const checked = validateSubmission(envelope, nowMs, deps);
      if (!checked.ok) {
        held.push({
          submission_id: row.submission_id,
          state: "SUBMITTED",
          reason: `schema recheck: ${checked.rejections[0].field} ${checked.rejections[0].reason}`,
        });
        continue;
      }
      transition(db, row.submission_id, "SUBMITTED", "VALIDATED",
        "schema recheck passed", at);
      row.eligibility = "VALIDATED";
      promoted.push({
        submission_id: row.submission_id, from: "SUBMITTED", to: "VALIDATED",
      });
    }
    if (row.eligibility === "VALIDATED") {
      const result = JSON.parse(row.result_json);
      const hold = anomalyHold(db, row, result, nowMs, deps);
      if (hold !== null) {
        held.push({
          submission_id: row.submission_id,
          state: "VALIDATED",
          reason: hold,
        });
        continue;
      }
      transition(db, row.submission_id, "VALIDATED", "QUALIFIED",
        "anomaly suite passed", at);
      row.eligibility = "QUALIFIED";
      promoted.push({
        submission_id: row.submission_id, from: "VALIDATED", to: "QUALIFIED",
      });
    }
    if (row.eligibility === "QUALIFIED") {
      const scopes = JSON.parse(row.consent_scopes);
      if (!scopes.includes("aggregates")) {
        held.push({
          submission_id: row.submission_id,
          state: "QUALIFIED",
          reason: "aggregate consent scope missing",
        });
        continue;
      }
      transition(db, row.submission_id, "QUALIFIED", "PUBLISHED",
        "consent current; published", at);
      db.run(
        "UPDATE submissions SET published_at = ? WHERE submission_id = ?",
        at, row.submission_id,
      );
      promoted.push({
        submission_id: row.submission_id, from: "QUALIFIED", to: "PUBLISHED",
      });
    }
  }
  return { promoted, held, processed: pending.length };
}
