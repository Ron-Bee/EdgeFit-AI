package ai.edgefit.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock

/**
 * EF-MEM-02 opt-in memory diagnostics. Pure JVM surface
 * (checkpoints, samples, store, readiness, shadow budget)
 * plus one Android sampler. Disabled by default; enabling
 * is explicit, in-memory only (resets each launch), and
 * never starts inference, network, or background polling.
 *
 * What each metric IS (never conflated):
 * - availBytes: system-wide available RAM (ActivityManager).
 * - thresholdBytes: Android's own low-memory threshold.
 * - pssKb: this process's proportional set size (shared
 *   pages split across sharers). NOT total device use.
 * - privateDirtyKb: this process's private dirty pages.
 * - nativeHeapBytes: native heap allocated (mallinfo-class).
 * - javaHeapBytes: JVM heap used (total - free).
 * None of these equals GGUF file size, virtual address
 * space, peak process memory, or model allocation need.
 */
object MemDiag {

    /**
     * Lifecycle checkpoints (§6 A–M). POST_NATIVE_INIT is
     * deliberately absent: native frees model/context/
     * sampler before nativeRun returns, so post-load RSS
     * is NOT separately observable without a native ABI
     * change (explicitly out of scope). POST_NATIVE covers
     * inference-complete + native-cleanup as one honest
     * point; PRE_NATIVE/POST_NATIVE deltas bound native
     * retention instead of measuring a load peak.
     */
    enum class Checkpoint(val serial: String, val label: String) {
        APP_IDLE("app_idle", "App idle after launch"),
        HOME_SHOWN("home_shown", "Home shown"),
        SESSIONS_OPENED("sessions_opened", "Sessions opened"),
        SESSIONS_CLOSED("sessions_closed", "Sessions closed"),
        MODEL_STAGED("model_staged", "Model staged"),
        CONFIG_READY("config_ready", "Benchmark config ready"),
        PRE_ADMISSION("pre_admission", "Before admission check"),
        /**
         * Retired with EF-MEM-03 (no recheck gate exists
         * anymore). Retained so old exports still parse.
         */
        POST_RECHECK("post_recheck", "After admission recheck (retired)"),
        PRE_NATIVE("pre_native", "Before native run"),
        POST_NATIVE("post_native", "After native run + cleanup"),
        FAILURE_HANDLED("failure_handled", "After failure handling");

        companion object {
            fun fromSerial(serial: String): Checkpoint? =
                entries.firstOrNull { it.serial == serial }
        }
    }

    /**
     * One diagnostic sample. Null optionals mean
     * "unavailable" (API unsupported or read failed) —
     * never zero-substituted. All byte counts are raw
     * platform values; formatting rounds down.
     */
    data class Sample(
        val checkpoint: Checkpoint,
        val elapsedRealtimeMs: Long,
        val availBytes: Long,
        val lowMemory: Boolean,
        val thresholdBytes: Long? = null,
        val pssKb: Int? = null,
        val privateDirtyKb: Int? = null,
        val nativeHeapBytes: Long? = null,
        val javaHeapBytes: Long? = null
    )

    /** Bounded in-memory store; the ONLY sampler sink. */
    object Store {
        const val MAX_SAMPLES = 64

        private val lock = Any()
        private var enabled = false
        private val samples = ArrayDeque<Sample>()

        fun setEnabled(on: Boolean) = synchronized(lock) {
            enabled = on
            if (!on) samples.clear()
        }

        fun isEnabled(): Boolean = synchronized(lock) { enabled }

        /** No-op unless enabled; drops oldest past the cap. */
        fun note(sample: Sample) = synchronized(lock) {
            if (!enabled) return
            samples.addLast(sample)
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
        }

        fun snapshot(): List<Sample> = synchronized(lock) {
            samples.toList()
        }

        fun clear() = synchronized(lock) { samples.clear() }
    }

    /**
     * Field validation. Negative/zero readings are
     * physically impossible from these APIs and indicate
     * a broken sampler, not a real measurement.
     */
    fun validate(sample: Sample): List<String> {
        val errors = mutableListOf<String>()
        if (sample.elapsedRealtimeMs < 0) {
            errors.add("negative monotonic timestamp")
        }
        if (sample.availBytes <= 0) {
            errors.add("avail_bytes must be positive")
        }
        sample.thresholdBytes?.let {
            if (it <= 0) errors.add("threshold_bytes must be positive")
        }
        sample.pssKb?.let {
            if (it < 0) errors.add("pss_kb must be non-negative")
        }
        sample.privateDirtyKb?.let {
            if (it < 0) errors.add("private_dirty_kb must be non-negative")
        }
        sample.nativeHeapBytes?.let {
            if (it < 0) errors.add("native_heap_bytes must be non-negative")
        }
        sample.javaHeapBytes?.let {
            if (it < 0) errors.add("java_heap_bytes must be non-negative")
        }
        return errors
    }

    /** Rounds down; never overstates headroom. */
    fun formatMb(bytes: Long): String = "${bytes / (1024L * 1024L)} MB"

    fun formatKb(kb: Int): String = "${kb / 1024} MB"

    private fun optMb(bytes: Long?): String =
        bytes?.let { formatMb(it) } ?: "unavailable"

    private fun optKb(kb: Int?): String =
        kb?.let { formatKb(it) } ?: "unavailable"

    /**
     * One human-readable sample line. Unavailable signals
     * are labeled, never zero-filled.
     */
    fun formatLine(sample: Sample, baseMs: Long = 0L): String {
        val ago = ((sample.elapsedRealtimeMs - baseMs)
            .coerceAtLeast(0) / 100.0) / 10.0
        return "${sample.checkpoint.label} (+${ago}s): " +
            "avail ${formatMb(sample.availBytes)}" +
            (if (sample.lowMemory) " [LOW-MEMORY]" else "") +
            ", threshold ${optMb(sample.thresholdBytes)}" +
            ", pss ${optKb(sample.pssKb)}" +
            ", priv-dirty ${optKb(sample.privateDirtyKb)}" +
            ", native-heap ${optMb(sample.nativeHeapBytes)}" +
            ", java-heap ${optMb(sample.javaHeapBytes)}"
    }

    /**
     * Android sampler: one cheap getMemoryInfo + process
     * Debug counters. Never throws; each signal fails
     * independently to null/zero-safe defaults. Caller
     * MUST gate on [Store.isEnabled]; call sites do.
     */
    fun sampleNow(context: Context, checkpoint: Checkpoint): Sample {
        val now = try {
            SystemClock.elapsedRealtime()
        } catch (_: Exception) {
            0L
        }
        var avail = 0L
        var low = false
        var threshold: Long? = null
        try {
            val manager = context.getSystemService(
                Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            avail = info.availMem
            low = info.lowMemory
            threshold = info.threshold.takeIf { it > 0 }
        } catch (_: Exception) {
        }
        var pss: Int? = null
        var privDirty: Int? = null
        try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            pss = info.totalPss.takeIf { it >= 0 }
            privDirty = info.totalPrivateDirty.takeIf { it >= 0 }
        } catch (_: Exception) {
        }
        var nativeHeap: Long? = null
        try {
            nativeHeap = Debug.getNativeHeapAllocatedSize()
                .takeIf { it >= 0 }
        } catch (_: Exception) {
        }
        var javaHeap: Long? = null
        try {
            val runtime = Runtime.getRuntime()
            javaHeap = (runtime.totalMemory() - runtime.freeMemory())
                .takeIf { it >= 0 }
        } catch (_: Exception) {
        }
        return Sample(checkpoint, now, avail, low, threshold,
            pss, privDirty, nativeHeap, javaHeap)
    }

    /** Run-scoped export for the `x_mem_diag` JSON block. */
    data class RunExport(
        val samples: List<Sample>,
        val buildVariant: String,
        val nativeOpt: String
    )

    fun exportToMap(export: RunExport): Map<String, Any?> =
        linkedMapOf(
            "instrumented" to true,
            "build_variant" to export.buildVariant,
            "native_opt" to export.nativeOpt,
            "non_confirmatory" to
                "instrumented run; excluded from confirmatory " +
                "cohorts until on/off overhead assessment",
            // EF-MEM-03: sparsely sampled minimum — a lower
            // bound on observed availability, never claimed
            // as the true minimum.
            "min_avail_bytes" to export.samples
                .minOfOrNull { it.availBytes },
            "samples" to export.samples.map {
                linkedMapOf(
                    "checkpoint" to it.checkpoint.serial,
                    "elapsed_realtime_ms" to it.elapsedRealtimeMs,
                    "avail_bytes" to it.availBytes,
                    "low_memory" to it.lowMemory,
                    "threshold_bytes" to it.thresholdBytes,
                    "pss_kb" to it.pssKb,
                    "private_dirty_kb" to it.privateDirtyKb,
                    "native_heap_bytes" to it.nativeHeapBytes,
                    "java_heap_bytes" to it.javaHeapBytes
                )
            }
        )
}

/**
 * EF-MEM-03 preflight readiness presentation over the
 * advisory [MemPolicy]. Nothing here blocks execution:
 * below-threshold availability warns and records, and
 * Run stays available. A quiet assessment is NOT a
 * guarantee native loading cannot fail.
 */
object MemReadiness {
    /** One-line Benchmark-tab status. Never claims more. */
    fun statusLine(
        modelName: String?,
        assessment: MemPolicy.Assessment
    ): String {
        if (modelName == null) return "Memory: stage a model first."
        return MemPolicy.statusLine(assessment)
    }

    /**
     * Informational note appended AFTER (never inside) the
     * Meta authorization field list: prediction and
     * benchmark execution are separate actions, and this
     * readiness is not transmitted to Meta.
     */
    fun authNote(
        modelName: String?,
        assessment: MemPolicy.Assessment
    ): String {
        if (modelName == null) {
            return "Memory readiness: no model staged yet, so " +
                "benchmark conditions are unknown. A prediction " +
                "may still be requested; running the benchmark " +
                "is a separate action and is never blocked by " +
                "memory availability alone."
        }
        val headline = when (assessment.level) {
            MemPolicy.Level.OK -> "at or above the " +
                "${MemPolicy.WARNING_PCT}% advisory threshold"
            MemPolicy.Level.ADVISORY -> "below the " +
                "${MemPolicy.WARNING_PCT}% advisory threshold"
            MemPolicy.Level.SEVERE -> "critically low (below " +
                "${MemPolicy.SEVERE_PCT}%)"
            MemPolicy.Level.UNKNOWN -> "unknown"
        }
        return "Memory readiness (informational only, not sent " +
            "to Meta): available memory is $headline. A " +
            "prediction may still be requested; running the " +
            "benchmark is a separate action and is never " +
            "blocked by memory availability alone."
    }

    fun sections(
        modelName: String?,
        configLabel: String,
        assessment: MemPolicy.Assessment,
        lowThresholdBytes: Long? = null
    ): List<InsightCards.Section> {
        val accent = when (assessment.level) {
            MemPolicy.Level.SEVERE -> InsightCards.Accent.ERROR
            else -> InsightCards.Accent.NONE
        }
        val headline = when (assessment.level) {
            MemPolicy.Level.OK -> "Above advisory threshold"
            MemPolicy.Level.ADVISORY -> "Below advisory threshold"
            MemPolicy.Level.SEVERE -> "Critically low"
            MemPolicy.Level.UNKNOWN -> "Memory Status Unavailable"
        }
        val rows = mutableListOf(
            InsightCards.Row("Readiness", headline, accent),
            InsightCards.Row("Model",
                modelName ?: "none staged"),
            InsightCards.Row("Available now",
                "${MemPolicy.formatPct(assessment.availPct)} " +
                    "(${assessment.availBytes?.let {
                        MemDiag.formatMb(it)
                    } ?: "unavailable"})"),
            InsightCards.Row("Total RAM",
                assessment.totalBytes?.let {
                    MemDiag.formatMb(it)
                } ?: "unavailable"),
            InsightCards.Row("Advisory threshold",
                "${MemPolicy.WARNING_PCT}% available " +
                    "(warn-only, never blocks)"),
            InsightCards.Row("Android low-memory",
                when (assessment.lowMemory) {
                    true -> "yes"
                    false -> "no"
                    null -> "unavailable"
                }),
            InsightCards.Row("Android low-memory threshold",
                lowThresholdBytes?.takeIf { it > 0 }?.let {
                    MemDiag.formatMb(it)
                } ?: "unavailable"),
            InsightCards.Row("Configuration", configLabel)
        )
        val details = buildString {
            if (assessment.advisory != null) {
                append(assessment.advisory)
                append(" ")
            }
            append("Memory availability never blocks Run. A " +
                "quiet check is not a guarantee: native " +
                "loading can still fail. No inference starts " +
                "from this screen.")
        }
        return listOf(InsightCards.Section(
            "Memory Readiness", rows, details))
    }
}

/**
 * EF-MEM-02 model-specific budget framework, shadow mode,
 * reframed by EF-MEM-03: memory is advisory-only, so this
 * object only names what is known vs unknown for a future
 * verified formula. By construction [ShadowEstimate]
 * carries NO admission decision — there is no boolean to
 * consult. Conclusion stays `insufficient_data` until a
 * documented formula + headroom rationale + measurements
 * + review + explicit owner approval exist.
 */
object MemBudget {
    const val CONCLUSION_INSUFFICIENT_DATA = "insufficient_data"

    data class ShadowEstimate(
        /** Advisory threshold echoed for context. */
        val advisoryThresholdPct: Double,
        val knownInputs: Map<String, String>,
        val unknownInputs: List<String>,
        val conclusion: String = CONCLUSION_INSUFFICIENT_DATA,
        val note: String = "No verified per-model formula " +
            "exists; memory availability is advisory-only."
    )

    fun shadowEstimate(
        modelBytes: Long,
        modelSha256: String?,
        quantization: String?,
        nCtx: Int,
        nThreads: Int,
        runtimeVersion: String?
    ): ShadowEstimate {
        val known = linkedMapOf(
            "artifact_bytes" to "$modelBytes",
            "context_tokens" to "$nCtx",
            "threads" to "$nThreads"
        )
        modelSha256?.let { known["artifact_sha256"] = it }
        quantization?.let { known["quantization"] = it }
        runtimeVersion?.let { known["runtime"] = it }
        return ShadowEstimate(
            advisoryThresholdPct = MemPolicy.WARNING_PCT,
            knownInputs = known,
            unknownInputs = listOf(
                "resident KV-cache bytes for this arch/context",
                "touched (faulted-in) weight pages under mmap",
                "compute-buffer peak for this backend/threads",
                "verified headroom above observed peak",
                "low-memory-kill behavior at the boundary"
            )
        )
    }

    fun sections(estimate: ShadowEstimate): List<InsightCards.Section> =
        listOf(InsightCards.Section(
            "Investigational Budget (diagnostic only)",
            listOf(
                InsightCards.Row("Advisory threshold",
                    "${estimate.advisoryThresholdPct}% available"),
                InsightCards.Row("Conclusion",
                    "Unknown — insufficient verified data"),
                InsightCards.Row("Known inputs",
                    estimate.knownInputs.entries.joinToString(
                        "; ") { "${it.key}=${it.value}" }),
                InsightCards.Row("Unknown inputs",
                    "${estimate.unknownInputs.size} open items " +
                        "(see details)")
            ),
            estimate.unknownInputs.joinToString("\n") { "• $it" } +
                "\n\n${estimate.note} This estimate never " +
                "admits or refuses a run."
        ))
}
