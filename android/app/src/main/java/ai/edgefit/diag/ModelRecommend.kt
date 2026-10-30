package ai.edgefit.diag

/**
 * Deterministic host-side recommendation explanations
 * (EF-MODEL-01B). Every verdict derives from catalog
 * metadata, suitability output, and exact-hash local
 * evidence. No ranking scores, no opaque weights, no
 * speed claims: smaller never means faster here. The
 * staged artifact's architecture is NOT parsed, so no
 * claim about "different architecture vs current" is
 * ever made. Pure JVM.
 */
object ModelRecommend {

    enum class Kind {
        ALREADY_MEASURED,
        SMALLER_FOOTPRINT,
        LARGER_FOOTPRINT,
        SIMILAR_SIZE,
        UNSUPPORTED,
        UNCERTAIN,
        NO_BASELINE
    }

    data class Recommendation(
        val kind: Kind,
        val headline: String,
        val body: String
    )

    data class MatchedEvidence(
        val sessionId: String,
        val runId: String,
        val genTps: Double,
        val prefillTps: Double?,
        val configLabel: String,
        val collectedAtUtc: String
    )

    /**
     * Exact-artifact local evidence: the catalog hash must
     * be known AND match a COMPLETED run's artifact hash.
     * Null catalog hash never matches — name, filename,
     * and size are not identity. Latest run wins.
     */
    @Suppress("UNCHECKED_CAST")
    fun localEvidenceFor(
        entry: ModelCatalog.Entry,
        sessions: List<SessionRecord>
    ): MatchedEvidence? {
        val want = entry.sha256?.lowercase() ?: return null
        return sessions.mapNotNull { record ->
            if (record.modelSha256.lowercase() != want) {
                return@mapNotNull null
            }
            val bench = record.benchmark as? Map<String, Any?>
                ?: return@mapNotNull null
            if (bench["status"] != BenchContract.STATUS_COMPLETED) {
                return@mapNotNull null
            }
            val runId = bench["run_id"] as? String
                ?: return@mapNotNull null
            val measures = bench["measurements"] as? Map<String, Any?>
                ?: return@mapNotNull null
            val gen = (measures["tps_gen"] as? Number)?.toDouble()
            if (gen == null || !gen.isFinite()) {
                return@mapNotNull null
            }
            val collected = bench["collected_at_utc"] as? String
                ?: return@mapNotNull null
            MatchedEvidence(
                sessionId = record.sessionId,
                runId = runId,
                genTps = gen,
                prefillTps = (measures["tps_prefill"] as? Number)
                    ?.toDouble()?.takeIf { it.isFinite() },
                configLabel = configLabel(record.config),
                collectedAtUtc = collected
            )
        }.maxByOrNull { it.collectedAtUtc }
    }

    private fun configLabel(config: Map<String, Any?>): String {
        fun num(key: String): String =
            (config[key] as? Number)?.toLong()?.toString() ?: "?"
        return "ctx=${num("n_ctx")} threads=${num("n_threads")} " +
            "predict=${num("n_predict")}"
    }

    /**
     * One deterministic recommendation per entry. Ordered
     * gates: measured evidence first, then hard
     * suitability verdicts, then size-vs-baseline
     * guidance. Model text never overrides these facts.
     */
    fun recommend(
        entry: ModelCatalog.Entry,
        assessment: ModelSuitability.Assessment,
        evidence: MatchedEvidence?,
        current: ModelContext.CurrentModel,
        sizeDiff: ModelContext.SizeDiff?
    ): Recommendation {
        if (evidence != null) {
            return Recommendation(
                Kind.ALREADY_MEASURED,
                "Already measured on this device.",
                "This exact artifact hash has a completed " +
                    "local run (${evidence.runId}, " +
                    "${evidence.genTps} tok/s). View the " +
                    "recorded run and its configuration; a " +
                    "further run would be a same-artifact " +
                    "repeat, not a new comparison.")
        }
        if (assessment.status == ModelSuitability.UNSUPPORTED) {
            return Recommendation(
                Kind.UNSUPPORTED,
                "Not recommended for this device.",
                (assessment.reasons.firstOrNull()
                    ?: "unsupported under known constraints") +
                    ". Do not assume this model can execute " +
                    "with EdgeFit's pinned runtime.")
        }
        if (assessment.status ==
            ModelSuitability.INSUFFICIENT_EVIDENCE
        ) {
            return Recommendation(
                Kind.UNCERTAIN,
                "Compatibility evidence is insufficient.",
                (assessment.reasons.firstOrNull()
                    ?: "required metadata is missing") +
                    ". Do not assume this model can execute " +
                    "successfully.")
        }
        if (current.isEmpty() || sizeDiff == null) {
            return Recommendation(
                Kind.NO_BASELINE,
                "Worth investigating.",
                entry.rationale + " No staged or measured " +
                    "model is available for size comparison; " +
                    "the listed size below stands alone.")
        }
        val mb = 1024L * 1024L
        val approx = if (sizeDiff.approximate) "about " else ""
        val pctText = formatPct(sizeDiff.pctVsCurrent)
        return when {
            sizeDiff.absBytes < 0 -> Recommendation(
                Kind.SMALLER_FOOTPRINT,
                "Smaller listed footprint.",
                "Listed artifact is $approx$pctText smaller " +
                    "than the current model " +
                    "(${absMb(sizeDiff.absBytes, mb)} MB). " +
                    "May be worth investigating when memory " +
                    "headroom is limited. Actual execution " +
                    "compatibility and throughput have not " +
                    "been verified — benchmark locally.")
            sizeDiff.absBytes > 0 -> Recommendation(
                Kind.LARGER_FOOTPRINT,
                "Larger listed footprint.",
                "Listed artifact is $approx$pctText larger " +
                    "than the current model " +
                    "(${absMb(sizeDiff.absBytes, mb)} MB). " +
                    "Verify memory headroom before testing. " +
                    "Size says nothing about speed: measure " +
                    "throughput locally before judging it.")
            else -> Recommendation(
                Kind.SIMILAR_SIZE,
                "Similar size class.",
                "Roughly the same footprint as the current " +
                    "model. Any reason to test it is the " +
                    "different model itself, not the size — " +
                    "benchmark locally and compare quality.")
        }
    }

    private fun formatPct(pct: Double): String {
        val abs = kotlin.math.abs(pct)
        val text = if (abs == kotlin.math.floor(abs)) {
            abs.toLong().toString()
        } else {
            abs.toString()
        }
        return "$text%"
    }

    private fun absMb(absBytes: Long, mb: Long): Long =
        kotlin.math.abs(absBytes) / mb
}
