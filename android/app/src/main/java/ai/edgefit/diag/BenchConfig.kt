package ai.edgefit.diag

import java.security.MessageDigest

/**
 * Fixed benchmark configuration and validation. Pure JVM.
 * Values target a mid-range baseline, not just the S22 Ultra.
 */
object BenchConfig {
    const val N_CTX = 512
    const val N_THREADS = 4
    const val N_PREDICT = 64
    const val TIMEOUT_MS = 600_000L
    const val SEED = 42L
    const val TEMPERATURE = 0.0

    const val MIN_CTX = 128
    const val MAX_CTX = 4096
    const val MIN_THREADS = 1
    const val MAX_THREADS = 16
    const val MIN_PREDICT = 1
    const val MAX_PREDICT = 256

    fun default(nThreads: Int = N_THREADS): BenchConfigData = BenchConfigData(
        nCtx = N_CTX,
        nThreads = nThreads,
        nPredict = N_PREDICT,
        temperature = TEMPERATURE,
        sampler = BenchContract.SAMPLER,
        seed = SEED,
        timeoutMs = TIMEOUT_MS
    )

    /** Clamp fixed thread count to what the device offers. Recorded as used. */
    fun effectiveThreads(logicalProcessors: Int): Int =
        N_THREADS.coerceAtMost(logicalProcessors.coerceAtLeast(1))

    /**
     * Single resolution point: an explicit override wins verbatim,
     * otherwise the device-derived default. Pure for unit tests.
     */
    fun resolveExecutionConfig(
        override: BenchConfigData?,
        logicalProcessors: Int
    ): BenchConfigData = override
        ?: default(effectiveThreads(logicalProcessors))

    /**
     * Pre-run display resolution (AUD-03): the run screen must show
     * the config the runner will execute — a bound session override
     * wins verbatim, else the live default. Mirrors
     * [resolveExecutionConfig], whose null branch equals
     * `default(effectiveThreads(...))` by construction. Pure for
     * unit tests.
     */
    fun displayConfig(
        bound: BenchConfigData?,
        liveDefault: BenchConfigData
    ): BenchConfigData = bound ?: liveDefault

    /**
     * Native workload arguments in exact JNI parameter order
     * (nCtx, nThreads, nPredict). The runner's SOLE producer of
     * native parameters: result-config and JNI args both derive
     * from one validated instance through this funnel, so the
     * recorded config cannot silently diverge from what native
     * executed. Pure for unit tests.
     */
    fun nativeArgs(config: BenchConfigData): List<Int> =
        listOf(config.nCtx, config.nThreads, config.nPredict)

    fun validate(config: BenchConfigData): List<String> {
        val errors = mutableListOf<String>()
        if (config.nCtx !in MIN_CTX..MAX_CTX) errors.add("n_ctx out of range")
        if (config.nThreads !in MIN_THREADS..MAX_THREADS) errors.add("n_threads out of range")
        if (config.nPredict !in MIN_PREDICT..MAX_PREDICT) errors.add("n_predict out of range")
        if (config.temperature != TEMPERATURE) errors.add("temperature must be 0")
        if (config.sampler != BenchContract.SAMPLER) errors.add("sampler must be greedy")
        if (config.timeoutMs <= 0) errors.add("timeout must be positive")
        if (config.nPredict >= config.nCtx) errors.add("n_predict must fit in n_ctx")
        return errors
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun workload(): BenchWorkload {
        val prompt = BenchContract.WORKLOAD_PROMPT
        return BenchWorkload(
            id = BenchContract.WORKLOAD_ID,
            promptSha256 = sha256Hex(prompt.toByteArray(Charsets.UTF_8)),
            promptChars = prompt.length
        )
    }

    fun tps(tokens: Int, ms: Long): Double =
        if (ms > 0) tokens * 1000.0 / ms else 0.0
}
