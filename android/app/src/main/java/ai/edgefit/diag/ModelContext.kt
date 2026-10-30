package ai.edgefit.diag

import kotlin.math.round

/**
 * Current-model context for EF-MODEL-01B recommendations.
 * Pure JVM: assembles the staged artifact (exact bytes +
 * hash) and the latest valid COMPLETED measurement, and
 * computes host-side size differences. Failed, refused,
 * and non-finite runs are never evidence. Throughput is
 * NEVER compared across different artifacts here.
 */
object ModelContext {

    data class StagedRef(
        val displayName: String,
        val sha256: String,
        val sizeBytes: Long
    )

    data class LocalMeasurement(
        val sessionId: String,
        val runId: String,
        val modelSha256: String,
        val modelDisplay: String,
        /** Session-recorded artifact bytes; null when unknown. */
        val modelSizeBytes: Long?,
        val genTps: Double,
        val prefillTps: Double?,
        val configLabel: String,
        val collectedAtUtc: String
    )

    data class CurrentModel(
        val staged: StagedRef?,
        val lastMeasurement: LocalMeasurement?
    ) {
        fun isEmpty(): Boolean = staged == null && lastMeasurement == null
    }

    data class SizeDiff(
        val candidateBytes: Long,
        val currentBytes: Long,
        /** candidate minus current; negative means smaller. */
        val absBytes: Long,
        /** Percentage vs current; negative means smaller. */
        val pctVsCurrent: Double,
        /** True unless both sides are exact bytes. */
        val approximate: Boolean
    )

    /**
     * Latest valid measurement by collection time: COMPLETED
     * status, finite generation throughput, identified run.
     * Anything else is not evidence and is skipped.
     */
    @Suppress("UNCHECKED_CAST")
    fun lastCompletedMeasurement(
        sessions: List<SessionRecord>
    ): LocalMeasurement? {
        return sessions.mapNotNull { record ->
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
            val prefill = (measures["tps_prefill"] as? Number)
                ?.toDouble()?.takeIf { it.isFinite() }
            LocalMeasurement(
                sessionId = record.sessionId,
                runId = runId,
                modelSha256 = record.modelSha256,
                modelDisplay = record.modelDisplay,
                modelSizeBytes = record.modelSizeBytes
                    .takeIf { it > 0 },
                genTps = gen,
                prefillTps = prefill,
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
     * Baseline display name, mirroring [sizeVsCurrent]'s
     * choice: staged name first, else the last measured
     * artifact. Null when no baseline exists.
     */
    fun baselineName(current: CurrentModel): String? =
        current.staged?.displayName
            ?: current.lastMeasurement?.modelDisplay

    /**
     * Candidate bound vs the current baseline: staged bytes
     * when a model is staged, else the last measurement's
     * recorded artifact bytes. Null when either side is
     * unknown. Approximate unless the candidate side is
     * exact bytes (baselines are always exact).
     */
    fun sizeVsCurrent(
        candidateBound: Long?,
        candidateBasis: String,
        current: CurrentModel
    ): SizeDiff? {
        if (candidateBound == null || candidateBound <= 0) {
            return null
        }
        val baseline = current.staged?.sizeBytes
            ?.takeIf { it > 0 }
            ?: current.lastMeasurement?.modelSizeBytes
            ?: return null
        val abs = candidateBound - baseline
        return SizeDiff(
            candidateBytes = candidateBound,
            currentBytes = baseline,
            absBytes = abs,
            pctVsCurrent = round(abs * 1000.0 / baseline) / 10.0,
            approximate = candidateBasis != "exact_bytes"
        )
    }
}
