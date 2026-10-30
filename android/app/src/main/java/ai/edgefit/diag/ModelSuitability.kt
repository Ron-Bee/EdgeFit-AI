package ai.edgefit.diag

/**
 * Conservative per-device suitability over catalog entries
 * (EF-MODEL-01A, FR-MODEL-01 statuses). Pure JVM. File size
 * below free RAM never proves executability: the verdict
 * reuses the benchmark guard's own headroom rule, and only
 * a local COMPLETED run of the exact artifact hash earns
 * verified_compatible. Catalog metadata is an estimate
 * input, never proof.
 */
object ModelSuitability {
    const val VERIFIED_COMPATIBLE = "verified_compatible"
    const val LIKELY_COMPATIBLE = "likely_compatible_unverified"
    const val UNSUPPORTED = "unsupported"
    const val INSUFFICIENT_EVIDENCE = "insufficient_evidence"

    /** Below this native context no EdgeFit config can run. */
    const val MIN_CONTEXT = 128

    /**
     * EF-MODEL-01A evidence classes (Phase 7). The v1
     * bundled catalog only ever produces COMPATIBILITY_
     * ESTIMATE or PHYSICAL_MEASUREMENT; the other three
     * exist so future sources cannot be mislabeled into
     * these two. Never promote one class into another.
     */
    enum class EvidenceClass {
        COMPATIBILITY_ESTIMATE,
        CAPABILITY_DESCRIPTION,
        META_PREDICTION,
        PHYSICAL_MEASUREMENT,
        PUBLISHED_AGGREGATE
    }

    fun evidenceClass(measuredLocally: Boolean): EvidenceClass =
        if (measuredLocally) {
            EvidenceClass.PHYSICAL_MEASUREMENT
        } else {
            EvidenceClass.COMPATIBILITY_ESTIMATE
        }

    fun evidenceLabel(kind: EvidenceClass): String = when (kind) {
        EvidenceClass.COMPATIBILITY_ESTIMATE ->
            "Compatibility estimate — no measurement"
        EvidenceClass.CAPABILITY_DESCRIPTION ->
            "Model capability description — no measurement"
        EvidenceClass.META_PREDICTION ->
            "Meta Muse prediction (ESTIMATE) — no measurement"
        EvidenceClass.PHYSICAL_MEASUREMENT ->
            "Physical measurement on this device"
        EvidenceClass.PUBLISHED_AGGREGATE ->
            "Qualified published aggregate"
    }

    data class DeviceInput(
        val supportedAbis: List<String>,
        /** Null when the sample is unavailable. */
        val availBytes: Long?,
        /** Wall time of the avail sample, or null. */
        val availSampledAt: String?,
        /** Lowercase artifact hashes with local COMPLETED runs. */
        val measuredArtifactShas: Set<String> = emptySet()
    )

    data class Assessment(
        val status: String,
        val reasons: List<String>,
        val sizeBoundBytes: Long?,
        val sizeBasis: String,
        /** Guard-consistent requirement, or null when unknown. */
        val requiredAvailBytes: Long?,
        val measuredLocally: Boolean,
        /**
         * EF-MODEL-01B: free memory right now is below the
         * requirement. A flagged condition, never a status
         * change — the FR-MODEL-01 verdict stands.
         */
        val memoryLimitedNow: Boolean = false,
        /** Snapshot of the sample behind the caveat. */
        val availBytes: Long? = null,
        val availSampledAt: String? = null
    )

    fun assess(
        entry: ModelCatalog.Entry,
        device: DeviceInput
    ): Assessment {
        val reasons = mutableListOf<String>()
        val bound = ModelCatalog.sizeBoundBytes(entry)
        val basis = ModelCatalog.sizeBasis(entry)
        val required = bound?.let { BenchGuards.requiredAvailBytes(it) }
        val measured = entry.sha256 != null &&
            entry.sha256.lowercase() in device.measuredArtifactShas
        val avail = device.availBytes?.takeIf { it > 0 }
        // Currently-limited flag: known sample below a known
        // requirement. Unknown either side means unknown.
        val limited = avail != null && required != null &&
            avail < required

        // Hard gates first: ABI, runtime arch, context floor,
        // guard ceiling. Each names its specific basis.
        if (BenchGuards.REQUIRED_ABI !in device.supportedAbis) {
            reasons.add("device ABI lacks ${BenchGuards.REQUIRED_ABI}")
            return Assessment(UNSUPPORTED, reasons, bound, basis,
                required, measuredLocally = false,
                memoryLimitedNow = limited, availBytes = avail,
                availSampledAt = device.availSampledAt)
        }
        if (!entry.runtimeSupported) {
            reasons.add("arch ${entry.arch} not in ${entry.runtimeRef}")
            return Assessment(UNSUPPORTED, reasons, bound, basis,
                required, measuredLocally = false,
                memoryLimitedNow = limited, availBytes = avail,
                availSampledAt = device.availSampledAt)
        }
        if (entry.contextLength != null &&
            entry.contextLength < MIN_CONTEXT
        ) {
            reasons.add("context ${entry.contextLength} below " +
                "minimum $MIN_CONTEXT")
            return Assessment(UNSUPPORTED, reasons, bound, basis,
                required, measuredLocally = false,
                memoryLimitedNow = limited, availBytes = avail,
                availSampledAt = device.availSampledAt)
        }
        if (bound != null && bound > BenchGuards.MAX_MODEL_BYTES) {
            reasons.add("artifact bound exceeds the " +
                "${BenchGuards.MAX_MODEL_BYTES / (1024 * 1024 * 1024)} " +
                "GiB guard ceiling")
            return Assessment(UNSUPPORTED, reasons, bound, basis,
                required, measuredLocally = false,
                memoryLimitedNow = limited, availBytes = avail,
                availSampledAt = device.availSampledAt)
        }
        // Missing size evidence: cannot judge memory fit.
        if (bound == null) {
            reasons.add("artifact size unverified: no exact " +
                "bytes and no parseable listed size")
            return Assessment(INSUFFICIENT_EVIDENCE, reasons, null,
                basis, requiredAvailBytes = null,
                measuredLocally = false, memoryLimitedNow = false,
                availBytes = avail,
                availSampledAt = device.availSampledAt)
        }
        if (measured) {
            reasons.add("a COMPLETED local run of this exact " +
                "artifact hash exists on this device")
            memoryCaveat(device, required!!, reasons)
            return Assessment(VERIFIED_COMPATIBLE, reasons, bound,
                basis, required, measuredLocally = true,
                memoryLimitedNow = limited, availBytes = avail,
                availSampledAt = device.availSampledAt)
        }
        reasons.add("arch ${entry.arch} present in " +
            "${entry.runtimeRef} (execution untested)")
        if (basis == "listed_display_upper_bound") {
            reasons.add("size is a listed-display upper bound " +
                "(${entry.sizeDisplay}), not exact bytes")
        }
        if (entry.sha256 == null) {
            reasons.add("artifact SHA-256 unverified")
        }
        memoryCaveat(device, required!!, reasons)
        return Assessment(LIKELY_COMPATIBLE, reasons, bound, basis,
            required, measuredLocally = false,
            memoryLimitedNow = limited, availBytes = avail,
            availSampledAt = device.availSampledAt)
    }

    /**
     * Current-memory caveat. Volatile by nature: always
     * qualified with the sample time, never a verdict.
     */
    private fun memoryCaveat(
        device: DeviceInput,
        required: Long,
        reasons: MutableList<String>
    ) {
        val avail = device.availBytes
        if (avail == null || avail <= 0) {
            reasons.add("current free memory unknown " +
                "(sample unavailable)")
            return
        }
        val mb = 1024L * 1024L
        val whenSampled = device.availSampledAt ?: "unknown time"
        if (avail < required) {
            reasons.add("insufficient memory right now: " +
                "${avail / mb} MB free at $whenSampled, " +
                "${required / mb} MB required " +
                "(artifact + 1 GiB headroom)")
        } else {
            reasons.add("memory fits now: ${avail / mb} MB free " +
                "at $whenSampled, ${required / mb} MB required")
        }
    }
}
