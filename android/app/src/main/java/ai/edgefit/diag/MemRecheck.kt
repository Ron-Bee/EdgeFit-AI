package ai.edgefit.diag

/**
 * EF-MEM-01 bounded memory recheck. Pure JVM: eligibility,
 * evidence shaping, and refusal text. The admission
 * requirement itself is NEVER lowered here — a recheck only
 * takes fresh samples and re-evaluates the SAME approved
 * guard. No Android APIs; fully unit-tested.
 */
object MemRecheck {
    /**
     * Near-threshold window: a shortfall this small may be
     * transient page-cache fluctuation rather than a real
     * deficit. 64 MiB covers the observed 28 MiB miss plus
     * typical jitter, yet is ~3% of the 2 GiB floor and far
     * below the 1 GiB headroom, so a recheck can only
     * admit runs that genuinely meet the requirement.
     */
    const val NEAR_WINDOW_BYTES = 64L * 1024 * 1024

    /** Fresh samples beyond the initial reading. */
    const val MAX_SAMPLES = 3

    /** Gap between fresh samples; total stall is ~1.5 s. */
    const val SAMPLE_GAP_MS = 500L

    /**
     * True when [availBytes] misses [requiredBytes] by a
     * positive amount within the near window. Overflow-safe:
     * non-positive readings never qualify.
     */
    fun nearThreshold(availBytes: Long, requiredBytes: Long): Boolean {
        if (availBytes <= 0 || requiredBytes <= 0) return false
        if (availBytes >= requiredBytes) return false
        val shortfall = requiredBytes - availBytes
        return shortfall in 1..NEAR_WINDOW_BYTES
    }

    /**
     * A recheck is allowed only when memory is the SOLE
     * refusal reason and the miss is near-threshold.
     * Thermal/ABI/model-size failures never recheck.
     */
    fun shouldRecheck(
        guardErrors: List<String>,
        availBytes: Long,
        requiredBytes: Long
    ): Boolean =
        guardErrors.size == 1 &&
            guardErrors[0] == BenchGuards.MEMORY_REFUSAL &&
            nearThreshold(availBytes, requiredBytes)

    /** True when a result error is a guard memory refusal. */
    fun isMemoryRefusal(error: String?): Boolean =
        error != null && error.contains(BenchGuards.MEMORY_REFUSAL)

    /** Short recheck outcome for refusal/success text. */
    fun summarize(evidence: MemRecheckEvidence): String {
        val mb = 1024L * 1024L
        val samples = evidence.samples.joinToString("→") {
            "${it.availBytes / mb}"
        }
        return if (evidence.admitted) {
            "memory recheck admitted on sample " +
                "${evidence.samples.size} " +
                "(${evidence.initialAvailBytes / mb}→$samples MB)"
        } else {
            "memory recheck ${evidence.samples.size} samples " +
                "(${evidence.initialAvailBytes / mb}→$samples MB), " +
                "still below required ${evidence.requiredBytes / mb} MB"
        }
    }

    /** Evidence shape for the `x_mem_recheck` JSON block. */
    fun toMap(evidence: MemRecheckEvidence): Map<String, Any?> =
        linkedMapOf(
            "required_bytes" to evidence.requiredBytes,
            "initial_avail_bytes" to evidence.initialAvailBytes,
            "initial_low_memory" to evidence.initialLowMemory,
            "rechecks_attempted" to evidence.samples.size,
            "admitted" to evidence.admitted,
            "recheck_ms" to evidence.recheckMs,
            "samples" to evidence.samples.map {
                linkedMapOf(
                    "avail_bytes" to it.availBytes,
                    "low_memory" to it.lowMemory,
                    "elapsed_realtime_ms" to it.elapsedRealtimeMs
                )
            }
        )
}
