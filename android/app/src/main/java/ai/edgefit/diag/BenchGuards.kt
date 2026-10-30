package ai.edgefit.diag

/**
 * Preflight resource guards. Pure JVM: decisions over measured inputs.
 *
 * EF-MEM-03 owner policy: memory availability is ADVISORY
 * ([MemPolicy]), never a blocking gate. This guard keeps
 * only ABI, model-size, and thermal refusals. The retired
 * 2 GiB formula survives below solely for historical
 * refusal-string matching and non-blocking estimates.
 */
object BenchGuards {
    const val REQUIRED_ABI = "arm64-v8a"
    const val MIN_AVAIL_BYTES = 2L * 1024 * 1024 * 1024
    const val HEADROOM_BYTES = 1L * 1024 * 1024 * 1024
    const val MAX_MODEL_BYTES = 2L * 1024 * 1024 * 1024
    val UNSAFE_THERMAL = setOf("severe", "critical", "emergency", "shutdown")
    /**
     * Historical memory refusal string (pre-MEM-03
     * records); matched when READING old evidence. New
     * runs never produce it.
     */
    const val MEMORY_REFUSAL = "insufficient available memory"

    /**
     * Retired blocking formula, kept for non-blocking
     * estimates (catalog caveat, shadow budget) and old
     * refusal text. MUST NOT gate execution.
     */
    fun requiredAvailBytes(modelBytes: Long): Long =
        (modelBytes + HEADROOM_BYTES).coerceAtLeast(MIN_AVAIL_BYTES)

    /**
     * Source-grounded refusal detail: the measured
     * available bytes against the required minimum. Pure
     * formatting — the refusal decision itself is
     * unchanged. Rounds down; never overstates headroom.
     */
    fun refusalDetail(availBytes: Long, modelBytes: Long): String {
        val mb = 1024L * 1024L
        return "available ${availBytes / mb} MB, " +
            "required ${requiredAvailBytes(modelBytes) / mb} MB"
    }

    /**
     * Returns refusal reasons; empty means go. EF-MEM-03:
     * memory NEVER refuses — unknown, zero, or low
     * availability all proceed to an explicit run, with
     * conditions recorded. Null thermal (API < 29 or read
     * failure) still refuses: unknown thermal state is
     * not approved. [availBytes] is retained in the
     * signature so call sites keep passing the reading
     * they must record; it is intentionally unused here.
     */
    @Suppress("UNUSED_PARAMETER")
    fun preflight(
        supportedAbis: List<String>,
        availBytes: Long,
        modelBytes: Long,
        thermal: String?
    ): List<String> {
        val errors = mutableListOf<String>()
        if (REQUIRED_ABI !in supportedAbis) {
            errors.add("unsupported ABI: requires $REQUIRED_ABI")
        }
        if (modelBytes <= 0 || modelBytes > MAX_MODEL_BYTES) {
            errors.add("model size out of bounds")
        }
        if (thermal == null) {
            errors.add("thermal state unknown")
        } else if (thermal in UNSAFE_THERMAL) {
            errors.add("thermal state unsafe: $thermal")
        }
        return errors
    }
}
