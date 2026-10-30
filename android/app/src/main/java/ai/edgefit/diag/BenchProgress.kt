package ai.edgefit.diag

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared-memory progress mailbox between bench_jni.cpp and the sampler.
 * All cells are int32 little-endian (native writes raw ints on arm64).
 * Single native writer, polling Kotlin reader; every cell fits one
 * naturally-atomic load/store, and the reader clamps all values, so a
 * torn or racing read can only show a stale-but-plausible snapshot.
 *
 * Layout (bytes): magic, version, stage, target, generated, prefill,
 * t_load_ms, t_prefill_ms, t_gen_ms, error, 6x reserved = 64 bytes.
 * MUST match bench_jni.cpp PROG_* constants (checked by
 * scripts/test_ef_android_ui_01.py).
 */
object BenchProgressLayout {
    const val SIZE_BYTES = 64
    const val MAGIC = 0x45444231
    const val VERSION = 1

    const val OFF_MAGIC = 0
    const val OFF_VERSION = 4
    const val OFF_STAGE = 8
    const val OFF_TARGET = 12
    const val OFF_GENERATED = 16
    const val OFF_PREFILL_TOKENS = 20
    const val OFF_LOAD_MS = 24
    const val OFF_PREFILL_MS = 28
    const val OFF_GEN_MS = 32
    const val OFF_ERROR = 36

    const val STAGE_LOADING = 0
    const val STAGE_PREFILL = 1
    const val STAGE_GENERATING = 2
    const val STAGE_NATIVE_DONE = 3
    const val STAGE_NATIVE_ERROR = 4

    const val ERR_NONE = 0
    const val ERR_MODEL_LOAD = 1
    const val ERR_CONTEXT_INIT = 2
    const val ERR_TOKENIZE = 3
    const val ERR_CONTEXT_OVERFLOW = 4
    const val ERR_PREFILL_DECODE = 5
    const val ERR_GEN_DECODE = 6
}

/** One parsed mailbox sample. Unknown timings are -1, never invented. */
data class BenchProgressSnapshot(
    val valid: Boolean,
    val nativeStage: Int,
    val targetTokens: Int,
    val generatedTokens: Int,
    val prefillTokens: Int,
    val loadMs: Long,
    val prefillMs: Long,
    val genMs: Long,
    val nativeError: Int
)

object BenchProgressMailbox {
    fun allocate(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(BenchProgressLayout.SIZE_BYTES)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0 until BenchProgressLayout.SIZE_BYTES step 4) {
            buf.putInt(offset, 0)
        }
        buf.putInt(BenchProgressLayout.OFF_MAGIC, BenchProgressLayout.MAGIC)
        buf.putInt(BenchProgressLayout.OFF_VERSION, BenchProgressLayout.VERSION)
        buf.putInt(BenchProgressLayout.OFF_LOAD_MS, -1)
        buf.putInt(BenchProgressLayout.OFF_PREFILL_MS, -1)
        buf.putInt(BenchProgressLayout.OFF_GEN_MS, -1)
        return buf
    }

    fun read(buf: ByteBuffer): BenchProgressSnapshot {
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (view.capacity() < BenchProgressLayout.SIZE_BYTES) {
            return invalid()
        }
        if (view.getInt(BenchProgressLayout.OFF_MAGIC) != BenchProgressLayout.MAGIC ||
            view.getInt(BenchProgressLayout.OFF_VERSION) != BenchProgressLayout.VERSION
        ) {
            return invalid()
        }
        val stage = view.getInt(BenchProgressLayout.OFF_STAGE)
        val target = view.getInt(BenchProgressLayout.OFF_TARGET)
        val generated = view.getInt(BenchProgressLayout.OFF_GENERATED)
        return BenchProgressSnapshot(
            valid = true,
            nativeStage = stage.coerceIn(0, BenchProgressLayout.STAGE_NATIVE_ERROR),
            targetTokens = target.coerceIn(0, BenchConfig.MAX_PREDICT),
            generatedTokens = BenchProgressMath.clampGenerated(generated, target),
            prefillTokens = view.getInt(BenchProgressLayout.OFF_PREFILL_TOKENS)
                .coerceIn(0, BenchConfig.MAX_CTX),
            loadMs = view.getInt(BenchProgressLayout.OFF_LOAD_MS).toLong(),
            prefillMs = view.getInt(BenchProgressLayout.OFF_PREFILL_MS).toLong(),
            genMs = view.getInt(BenchProgressLayout.OFF_GEN_MS).toLong(),
            nativeError = view.getInt(BenchProgressLayout.OFF_ERROR)
                .coerceIn(0, BenchProgressLayout.ERR_GEN_DECODE)
        )
    }

    fun invalid(): BenchProgressSnapshot = BenchProgressSnapshot(
        valid = false, nativeStage = BenchProgressLayout.STAGE_LOADING,
        targetTokens = 0, generatedTokens = 0, prefillTokens = 0,
        loadMs = -1, prefillMs = -1, genMs = -1,
        nativeError = BenchProgressLayout.ERR_NONE
    )
}

/**
 * Display execution states. Driven by real lifecycle events only:
 * VALIDATING by Kotlin preflight, LOADING/PROCESSING/GENERATING by the
 * native mailbox stage, FINALIZING after nativeRun returns, terminal
 * states from the measured BenchResult status. TIMEOUT is included
 * because the watchdog already produces that contract status and it
 * must never be misreported as CANCELLED or FAILED.
 */
object BenchExecutionState {
    const val IDLE = "IDLE"
    const val VALIDATING_MODEL = "VALIDATING_MODEL"
    const val LOADING_MODEL = "LOADING_MODEL"
    const val PROCESSING_PROMPT = "PROCESSING_PROMPT"
    const val GENERATING = "GENERATING"
    const val FINALIZING = "FINALIZING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val TIMEOUT = "TIMEOUT"

    private val TERMINAL = setOf(COMPLETED, FAILED, CANCELLED, TIMEOUT)

    /** Active pipeline order. The 4 Hz sampler may skip states that pass
     * between polls, so any forward step is legal; backward steps never. */
    private val ORDER = listOf(
        VALIDATING_MODEL, LOADING_MODEL, PROCESSING_PROMPT, GENERATING,
        FINALIZING
    )

    fun can(from: String, to: String): Boolean {
        if (from == to) return true
        if (from == IDLE) return to == VALIDATING_MODEL
        if (from in TERMINAL) return to == IDLE
        if (to in TERMINAL) return from in ORDER
        val fromIndex = ORDER.indexOf(from)
        val toIndex = ORDER.indexOf(to)
        return fromIndex >= 0 && toIndex > fromIndex
    }

    fun isTerminal(state: String): Boolean = state in TERMINAL

    /** Maps a native mailbox stage to a display state, or null when the
     * native side is done and the terminal result owns the state. */
    fun fromNativeStage(nativeStage: Int): String? = when (nativeStage) {
        BenchProgressLayout.STAGE_LOADING -> LOADING_MODEL
        BenchProgressLayout.STAGE_PREFILL -> PROCESSING_PROMPT
        BenchProgressLayout.STAGE_GENERATING -> GENERATING
        else -> null
    }
}

object BenchProgressMath {
    /** Provisional tok/s from real counts only; 0.0 when unknowable. */
    fun provisionalTps(generated: Int, elapsedMs: Long): Double {
        if (generated <= 0 || elapsedMs <= 0) return 0.0
        return generated * 1000.0 / elapsedMs
    }

    /** Displayed count never exceeds the reported target or reality. */
    fun clampGenerated(generated: Int, target: Int): Int {
        if (target <= 0) return 0
        return generated.coerceIn(0, target)
    }
}

/** Live update delivered on the sampler thread; the activity must
 * marshal to the main thread before touching views. */
data class BenchLiveUpdate(
    val execState: String,
    val progress: BenchProgressSnapshot,
    /** Monotonic ms since run start (elapsedRealtime basis). */
    val elapsedMs: Long,
    val thermal: String?,
    /** Process PSS KiB, or -1 when unavailable. */
    val pssKb: Int
)

/** Display strings. Pure JVM so every label is unit-tested. */
object BenchUiText {
    fun stageLabel(execState: String): String = when (execState) {
        BenchExecutionState.IDLE -> "Idle"
        BenchExecutionState.VALIDATING_MODEL -> "Validating model"
        BenchExecutionState.LOADING_MODEL -> "Loading model"
        BenchExecutionState.PROCESSING_PROMPT -> "Processing prompt"
        BenchExecutionState.GENERATING -> "Generating"
        BenchExecutionState.FINALIZING -> "Finalizing"
        BenchExecutionState.COMPLETED -> "Completed"
        BenchExecutionState.FAILED -> "Failed"
        BenchExecutionState.CANCELLED -> "Cancelled"
        BenchExecutionState.TIMEOUT -> "Timed out"
        else -> execState
    }

    /** mm:ss from monotonic ms; never negative. */
    fun elapsedLabel(elapsedMs: Long): String {
        val total = (elapsedMs.coerceAtLeast(0) / 1000).coerceAtMost(5999)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    fun tpsLabel(tps: Double): String = "%.1f tok/s".format(tps)

    /**
     * EF-UX-01D result headline. Completed runs — and runs
     * cancelled or timed out AFTER generating tokens, whose
     * partial rates are genuine measurements — show measured
     * throughput. Anything else (refusals, pre-execution
     * cancels, failures with zero output) is honestly "not
     * measured", never a zero dressed as data.
     */
    fun tpsHeadline(
        status: String,
        tpsGen: Double,
        generatedTokens: Int
    ): String =
        if (status == BenchContract.STATUS_COMPLETED ||
            generatedTokens > 0
        ) {
            "Measured ${tpsLabel(tpsGen)}"
        } else {
            "Not measured — benchmark did not complete."
        }

    /**
     * User-facing error text. Fixed guard/native strings pass through;
     * loader and JVM exception details are replaced so internal paths
     * can never reach the screen.
     */
    fun safeError(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown error."
        if (raw.startsWith("runtime unavailable:")) {
            return "Benchmark runtime unavailable (native library failed to load)."
        }
        if (raw.startsWith("native call failed:")) {
            return "Native benchmark call failed. See device logs for details."
        }
        return raw
    }
}

/** Bounded trailing (elapsedMs, generated) samples for the live chart. */
class BenchTpsSamples(private val capacity: Int = 2048) {
    data class Sample(val elapsedMs: Long, val generated: Int)

    private val deque = ArrayDeque<Sample>()

    fun add(elapsedMs: Long, generated: Int) {
        val last = deque.lastOrNull()
        if (last != null && elapsedMs < last.elapsedMs) return
        if (last != null && elapsedMs == last.elapsedMs) {
            deque[deque.lastIndex] = Sample(elapsedMs, generated)
            return
        }
        deque.addLast(Sample(elapsedMs, generated))
        while (deque.size > capacity) deque.removeFirst()
    }

    fun snapshot(): List<Sample> = deque.toList()

    val size: Int get() = deque.size

    fun clear() {
        deque.clear()
    }
}
