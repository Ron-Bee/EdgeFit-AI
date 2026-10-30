package ai.edgefit.diag

import kotlin.math.abs
import kotlin.math.round

/**
 * Deterministic predicted-vs-observed comparison, ported from
 * EF-LOOP-01 (`ef_loop_01.py` error semantics): signed error keeps
 * direction (observed minus predicted), abs drops it, percentage
 * uses the prediction as denominator. Never depends on Meta
 * arithmetic. Pure JVM.
 *
 * Rounding follows platform convention (half-up here, banker's in
 * CPython); parity vectors in `SessionStoreTest` and
 * `test_ef_session_parity.py` avoid exact ties, so both sides agree.
 */
object SessionComparison {
    data class Result(
        /** False when values are missing, non-finite, or predicted <= 0. */
        val comparable: Boolean,
        val reason: String,
        val genSigned: Double = Double.NaN,
        val genAbs: Double = Double.NaN,
        val genPct: Double = Double.NaN,
        val prefillSigned: Double = Double.NaN,
        val prefillAbs: Double = Double.NaN,
        val prefillPct: Double = Double.NaN
    )

    fun compare(
        predictedGen: Double,
        predictedPrefill: Double,
        measuredGen: Double,
        measuredPrefill: Double
    ): Result {
        for ((name, value) in listOf(
            "predicted_gen_tps" to predictedGen,
            "predicted_prefill_tps" to predictedPrefill,
            "measured_gen_tps" to measuredGen,
            "measured_prefill_tps" to measuredPrefill
        )) {
            if (!value.isFinite()) {
                return Result(false, "$name is not finite")
            }
        }
        if (predictedGen <= 0 || predictedPrefill <= 0) {
            return Result(false, "predicted throughput must be > 0")
        }
        if (measuredGen < 0 || measuredPrefill < 0) {
            return Result(false, "measured throughput must be >= 0")
        }
        val genSigned = measuredGen - predictedGen
        val prefillSigned = measuredPrefill - predictedPrefill
        return Result(
            comparable = true,
            reason = "ok",
            genSigned = round3(genSigned),
            genAbs = round3(abs(genSigned)),
            genPct = round2(100.0 * genSigned / predictedGen),
            prefillSigned = round3(prefillSigned),
            prefillAbs = round3(abs(prefillSigned)),
            prefillPct = round2(100.0 * prefillSigned / predictedPrefill)
        )
    }

    private fun round3(value: Double): Double =
        round(value * 1000.0) / 1000.0

    private fun round2(value: Double): Double =
        round(value * 100.0) / 100.0

    // ---- EF-FOLLOWUP-02 series comparison ----

    /**
     * One row of a same-configuration repeat series.
     * Deltas are current-minus-row; NaN when either side
     * is missing or non-finite (pct also needs row > 0).
     */
    data class SeriesRow(
        val runId: String,
        val collectedAtUtc: String,
        val genTps: Double,
        val prefillTps: Double,
        val thermalAfter: String,
        val deltaGen: Double,
        val deltaPct: Double,
        val isCurrent: Boolean
    )

    /**
     * Host-computed series summary. Descriptive values
     * only — never a statistical significance claim.
     */
    data class SeriesResult(
        val currentRunId: String,
        val rows: List<SeriesRow>,
        /** Finite gen values only; NaN when none. */
        val minGen: Double,
        val maxGen: Double
    )

    /**
     * Compares [current] against its same-configuration
     * [series] (from `SessionManager.findSeriesRuns`, oldest
     * first). Pure: reads stored maps, computes deltas.
     */
    @Suppress("UNCHECKED_CAST")
    fun compareSeries(
        current: SessionRecord,
        series: List<SessionRecord>
    ): SeriesResult {
        val currentBench = current.benchmark as? Map<String, Any?>?
        val currentMeas =
            currentBench?.get("measurements") as? Map<String, Any?>?
        val currentRun = currentBench?.get("run_id") as? String ?: "?"
        val currentGen = (currentMeas?.get("tps_gen") as? Number)
            ?.toDouble() ?: Double.NaN
        val rows = series.mapNotNull { other ->
            val bench = other.benchmark as? Map<String, Any?>
                ?: return@mapNotNull null
            val runId = bench["run_id"] as? String
                ?: return@mapNotNull null
            val meas = bench["measurements"] as? Map<String, Any?>
                ?: return@mapNotNull null
            val gen = (meas["tps_gen"] as? Number)?.toDouble()
                ?: Double.NaN
            val prefill = (meas["tps_prefill"] as? Number)?.toDouble()
                ?: Double.NaN
            val delta = if (gen.isFinite() && currentGen.isFinite()) {
                round3(currentGen - gen)
            } else {
                Double.NaN
            }
            val pct = if (delta.isFinite() && gen > 0) {
                round2(100.0 * delta / gen)
            } else {
                Double.NaN
            }
            SeriesRow(
                runId = runId,
                collectedAtUtc =
                    bench["collected_at_utc"] as? String ?: "unknown",
                genTps = gen,
                prefillTps = prefill,
                thermalAfter = meas["thermal_after"] as? String
                    ?: "unknown",
                deltaGen = delta,
                deltaPct = pct,
                isCurrent = runId == currentRun
            )
        }
        val finite = rows.map { it.genTps }.filter { it.isFinite() }
        return SeriesResult(
            currentRunId = currentRun,
            rows = rows,
            minGen = finite.minOrNull() ?: Double.NaN,
            maxGen = finite.maxOrNull() ?: Double.NaN
        )
    }
}
