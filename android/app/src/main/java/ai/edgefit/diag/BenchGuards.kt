package ai.edgefit.diag

/**
 * Preflight resource guards. Pure JVM: decisions over measured inputs.
 * Conservative mid-range baseline; refuses unsafe or unknown conditions.
 */
object BenchGuards {
    const val REQUIRED_ABI = "arm64-v8a"
    const val MIN_AVAIL_BYTES = 2L * 1024 * 1024 * 1024
    const val HEADROOM_BYTES = 1L * 1024 * 1024 * 1024
    const val MAX_MODEL_BYTES = 2L * 1024 * 1024 * 1024
    val UNSAFE_THERMAL = setOf("severe", "critical", "emergency", "shutdown")

    /** Bytes that must be free: model size plus headroom, with a floor. */
    fun requiredAvailBytes(modelBytes: Long): Long =
        (modelBytes + HEADROOM_BYTES).coerceAtLeast(MIN_AVAIL_BYTES)

    /**
     * Returns refusal reasons; empty means go. Null thermal (API < 29 or
     * read failure) refuses: unknown thermal state is not approved.
     */
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
        if (availBytes <= 0) {
            errors.add("available memory unknown")
        } else if (modelBytes in 1..MAX_MODEL_BYTES &&
            availBytes < requiredAvailBytes(modelBytes)
        ) {
            errors.add("insufficient available memory")
        }
        if (thermal == null) {
            errors.add("thermal state unknown")
        } else if (thermal in UNSAFE_THERMAL) {
            errors.add("thermal state unsafe: $thermal")
        }
        return errors
    }
}
