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
}
