package ai.edgefit.diag

/**
 * EF-MEM-03 advisory memory policy. Memory availability
 * ADVISES the developer; it never blocks an explicitly
 * requested benchmark. Pure JVM, fully unit-tested.
 *
 * The pre-MEM-03 hard gate (`max(modelBytes + 1 GiB,
 * 2 GiB)`) is retired as a blocking rule by explicit
 * owner decision; it survives only inside historical
 * refusal strings and the non-blocking catalog caveat.
 */
object MemPolicy {
    /** Below this available share, warn. At/above: quiet. */
    const val WARNING_PCT = 15.0

    /**
     * Below this share the language strengthens
     * (allocation failure is plausible), but execution
     * still stays the developer's explicit choice.
     */
    const val SEVERE_PCT = 5.0

    enum class Level {
        /** At/above threshold, or the unknown state. */
        OK,
        /** Below threshold: advisory warning. */
        ADVISORY,
        /** Below severe band: stronger warning. */
        SEVERE,
        /** Inputs missing/invalid: no fabricated value. */
        UNKNOWN
    }

    data class Assessment(
        val level: Level,
        /** Exact share when computable, else null. */
        val availPct: Double?,
        val availBytes: Long?,
        val totalBytes: Long?,
        /** Android's own signal, reported separately. */
        val lowMemory: Boolean?,
        /** Human guidance; null when nothing to say. */
        val advisory: String?
    )

    /**
     * Percentage-first assessment. Invalid inputs
     * (non-positive total, negative avail, avail above
     * total) yield UNKNOWN, never a fabricated share.
     */
    fun assess(
        availBytes: Long?,
        totalBytes: Long?,
        lowMemory: Boolean?
    ): Assessment {
        val avail = availBytes?.takeIf { it >= 0 }
        val total = totalBytes?.takeIf { it > 0 }
        if (avail == null || total == null || avail > total) {
            return Assessment(Level.UNKNOWN, null,
                availBytes?.takeIf { it >= 0 }, total,
                lowMemory,
                "Memory availability is unknown on this " +
                    "device; admission cannot be predicted.")
        }
        val pct = 100.0 * avail / total
        if (pct >= WARNING_PCT) {
            val advisory = if (lowMemory == true) {
                "Android reports low-memory state despite " +
                    "$WARNING_PCT%+ availability: conditions " +
                    "may change during the run."
            } else {
                null
            }
            return Assessment(Level.OK, pct, avail, total,
                lowMemory, advisory)
        }
        if (pct >= SEVERE_PCT) {
            return Assessment(Level.ADVISORY, pct, avail,
                total, lowMemory,
                "Available memory is below the recommended " +
                    "$WARNING_PCT% threshold. Memory pressure " +
                    "may influence throughput, loading time " +
                    "or benchmark completion.")
        }
        return Assessment(Level.SEVERE, pct, avail, total,
            lowMemory,
            "Available memory is critically low (below " +
                "$SEVERE_PCT%). Native allocation may fail; " +
                "the attempt and its outcome will be recorded.")
    }

    /** One decimal; null renders as Unknown. */
    fun formatPct(pct: Double?): String =
        if (pct == null || !pct.isFinite()) "Unknown"
        else "%.1f%%".format(pct)

    /**
     * Short Benchmark-tab line. Always names the action
     * state honestly: Run stays available regardless.
     */
    fun statusLine(assessment: Assessment): String {
        if (assessment.level == Level.UNKNOWN) {
            return "Memory: Unknown availability — Run stays available."
        }
        val base = "Memory available: " +
            formatPct(assessment.availPct)
        return when (assessment.level) {
            Level.OK -> base
            Level.ADVISORY -> "$base — below the " +
                "$WARNING_PCT% advisory; Run stays available."
            Level.SEVERE -> "$base — critically low; Run " +
                "stays available, allocation may fail."
            Level.UNKNOWN -> base
        }
    }
}

/**
 * EF-MEM-03 per-run memory conditions, recorded for
 * EVERY run (success or failure) with default
 * diagnostics disabled. Serialized as `x_mem_conditions`.
 * Unknown stays null/NaN-shaped — never 0%.
 */
data class MemConditions(
    val totalRamBytes: Long?,
    val availBytesStart: Long?,
    val availPctStart: Double?,
    val warningThresholdPct: Double,
    val belowThresholdStart: Boolean?,
    val lowMemoryStart: Boolean?,
    val lowThresholdBytes: Long?,
    /** Optional cheap end-of-run re-read. */
    val availBytesEnd: Long?,
    val availPctEnd: Double?,
    val method: String = "activity-manager-memoryinfo"
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "total_ram_bytes" to totalRamBytes,
        "avail_bytes_start" to availBytesStart,
        "avail_pct_start" to availPctStart,
        "warning_threshold_pct" to warningThresholdPct,
        "below_threshold_start" to belowThresholdStart,
        "low_memory_start" to lowMemoryStart,
        "low_threshold_bytes" to lowThresholdBytes,
        "avail_bytes_end" to availBytesEnd,
        "avail_pct_end" to availPctEnd,
        "method" to method
    )

    companion object {
        fun startOf(
            totalBytes: Long?,
            availBytes: Long?,
            lowMemory: Boolean?,
            lowThresholdBytes: Long?
        ): MemConditions {
            val pct = if (availBytes != null && availBytes >= 0 &&
                totalBytes != null && totalBytes > 0 &&
                availBytes <= totalBytes
            ) {
                100.0 * availBytes / totalBytes
            } else {
                null
            }
            return MemConditions(
                totalRamBytes = totalBytes?.takeIf { it > 0 },
                availBytesStart = availBytes?.takeIf { it >= 0 },
                availPctStart = pct,
                warningThresholdPct = MemPolicy.WARNING_PCT,
                belowThresholdStart =
                    pct?.let { it < MemPolicy.WARNING_PCT },
                lowMemoryStart = lowMemory,
                lowThresholdBytes =
                    lowThresholdBytes?.takeIf { it > 0 },
                availBytesEnd = null,
                availPctEnd = null
            )
        }
    }
}

/**
 * EF-MEM-03 failure-stage evidence, serialized as
 * `x_failure` on non-completed runs. Records WHERE the
 * run stopped and whether native code was entered —
 * never a fabricated cause.
 */
data class FailureEvidence(
    /** BenchExecutionState at terminal transition. */
    val stage: String,
    val nativeStarted: Boolean
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "stage" to stage,
        "native_started" to nativeStarted
    )
}
