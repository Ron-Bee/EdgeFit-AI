package ai.edgefit.diag

/**
 * EF-ANDROID-AERO-OVERHAUL-01 Priority Two: predicted-
 * vs-measured comparison derivation. Pure JVM over the
 * bound session's stored maps only. The difference row
 * renders the ALREADY-STORED host-computed error and
 * names it as persisted; when no stored comparison
 * exists the block says so with its reason instead of
 * computing, estimating, or borrowing one. Never a
 * cross-configuration comparison: prediction, measured
 * rates, and error all come from one bound record.
 */
object AeroCompare {

    data class CompareBlock(
        val predictedGen: String,
        val predictedPrefill: String,
        val hasPrediction: Boolean,
        val measuredGen: String,
        val measuredPrefill: String,
        val hasMeasurement: Boolean,
        val deltaGen: String,
        val deltaPrefill: String,
        val deltaNote: String,
        val comparable: Boolean
    )

    /**
     * One comparison block. [runMismatch] marks a session
     * bound to a different run than the displayed result:
     * the session's stored prediction still shows, but
     * measured and delta rows refuse the uncontrolled
     * pairing by name.
     */
    @Suppress("UNCHECKED_CAST")
    fun block(
        prediction: Map<String, Any?>?,
        benchmark: Map<String, Any?>?,
        comparison: Map<String, Any?>?,
        runMismatch: Boolean = false
    ): CompareBlock {
        val predictedGen = InsightCards.tps(
            prediction?.get("predicted_gen_tps"))
        val predictedPrefill = InsightCards.tps(
            prediction?.get("predicted_prefill_tps"))
        val hasPrediction = prediction != null &&
            (predictedGen != "?" || predictedPrefill != "?")
        val measures =
            benchmark?.get("measurements") as? Map<String, Any?>
        val measuredGen = InsightCards.tps(
            measures?.get("tps_gen"))
        val measuredPrefill = InsightCards.tps(
            measures?.get("tps_prefill"))
        val hasMeasurement = !runMismatch &&
            benchmark?.get("status") ==
                BenchContract.STATUS_COMPLETED &&
            (measuredGen != "?" || measuredPrefill != "?")
        if (runMismatch) {
            return CompareBlock(
                predictedGen = predictedGen,
                predictedPrefill = predictedPrefill,
                hasPrediction = hasPrediction,
                measuredGen = "?",
                measuredPrefill = "?",
                hasMeasurement = false,
                deltaGen = "Not comparable",
                deltaPrefill = "Not comparable",
                deltaNote = "session carries a different run",
                comparable = false)
        }
        if (comparison?.get("comparable") != true) {
            val reason = (comparison?.get("reason")
                as? String)?.ifBlank { null }
                ?: if (comparison == null) {
                    "no stored comparison"
                } else {
                    "comparison not marked comparable"
                }
            return CompareBlock(
                predictedGen = predictedGen,
                predictedPrefill = predictedPrefill,
                hasPrediction = hasPrediction,
                measuredGen = measuredGen,
                measuredPrefill = measuredPrefill,
                hasMeasurement = hasMeasurement,
                deltaGen = "Not comparable",
                deltaPrefill = "Not comparable",
                deltaNote = reason,
                comparable = false)
        }
        // Stored host-computed error, formatted exactly
        // like the comparison dialog (same helpers, same
        // stored inputs) — never recomputed here.
        return CompareBlock(
            predictedGen = predictedGen,
            predictedPrefill = predictedPrefill,
            hasPrediction = hasPrediction,
            measuredGen = measuredGen,
            measuredPrefill = measuredPrefill,
            hasMeasurement = hasMeasurement,
            deltaGen = "${InsightCards.signed(
                comparison["gen_signed"])} tok/s " +
                "(${InsightCards.signedPct(
                    comparison["gen_pct"])})",
            deltaPrefill = "${InsightCards.signed(
                comparison["prefill_signed"])} tok/s " +
                "(${InsightCards.signedPct(
                    comparison["prefill_pct"])})",
            deltaNote = "vs persisted prediction",
            comparable = true)
    }
}
