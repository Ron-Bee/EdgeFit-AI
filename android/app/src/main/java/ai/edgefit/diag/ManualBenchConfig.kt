package ai.edgefit.diag

/**
 * EF-COMP-01B-ROUTE: explicit developer-controlled manual
 * benchmark configuration for UNBOUND runs.
 *
 * Gap closed: unbound runs previously always executed the
 * device default (4 threads on every phone with >= 4
 * cores); 8-thread runs were reachable only through
 * Agent-approved children. This selector offers exactly
 * two bounded manual options beside the unchanged
 * default — no other config surface changes.
 *
 * Authority: the selection writes the SAME
 * standaloneOverride consumed by startBench, which
 * resolves boundConfig ?: standaloneOverride ?: default.
 * A bound session therefore always wins verbatim; the
 * manual selection only affects unbound runs and never
 * touches Agent gates, budgets, or BYOK. Selecting a
 * config never starts a run (fresh Run press required).
 *
 * Pure JVM for unit tests; MainActivity hosts the dialog.
 */
object ManualBenchConfig {

    const val ID_DEFAULT = "default"
    const val ID_A = "a-512-4-64"
    const val ID_B = "b-512-8-64"

    /** Manual A == study candidate A == S22 default triple. */
    fun configA(): BenchConfigData = BenchConfigData(
        nCtx = BenchConfig.N_CTX,
        nThreads = BenchConfig.N_THREADS,
        nPredict = BenchConfig.N_PREDICT,
        temperature = 0.0,
        sampler = BenchContract.SAMPLER,
        seed = 42,
        timeoutMs = 600000L
    )

    /** Manual B == study candidate B (8 threads). */
    fun configB(): BenchConfigData = configA().copy(nThreads = 8)

    data class Option(
        val id: String,
        val label: String,
        /** Capability note or unsupported reason. */
        val detail: String,
        val supported: Boolean,
        /** Null = device default (no override). */
        val config: BenchConfigData?
    )

    /**
     * The three options for a device with [deviceThreads]
     * logical processors. B is listed but unsupported
     * below 8 threads so the UI can explain why.
     */
    fun options(deviceThreads: Int): List<Option> {
        val safe = deviceThreads.coerceAtLeast(1)
        val defaultThreads =
            BenchConfig.effectiveThreads(safe)
        val bMax = BenchConfig.MAX_THREADS.coerceAtMost(safe)
        return listOf(
            Option(
                id = ID_DEFAULT,
                label = "Device default " +
                    "(ctx=512 threads=$defaultThreads predict=64)",
                detail = "Unchanged behavior: threads follow " +
                    "the device ($safe processors).",
                supported = true,
                config = null
            ),
            Option(
                id = ID_A,
                label = "Manual A (ctx=512 threads=4 predict=64)",
                detail = "Explicit 4-thread selection.",
                supported = true,
                config = configA()
            ),
            Option(
                id = ID_B,
                label = "Manual B (ctx=512 threads=8 predict=64)",
                detail = if (bMax >= 8) {
                    "Explicit 8-thread selection."
                } else {
                    "Needs >= 8 processors (this device: " +
                        "$safe)."
                },
                supported = bMax >= 8,
                config = configB()
            )
        )
    }

    sealed class Choice {
        /** Null config = device default (clear override). */
        data class Selected(val config: BenchConfigData?) : Choice()
        data class Refused(val reason: String) : Choice()
    }

    /**
     * Validates one manual selection through the
     * production rules ([BenchConfig.validate] + hardware
     * thread max). Unknown ids and unsupported options
     * refuse with reasons.
     */
    fun choose(id: String, deviceThreads: Int): Choice {
        val safe = deviceThreads.coerceAtLeast(1)
        val option = options(safe).firstOrNull { it.id == id }
            ?: return Choice.Refused("unknown config option")
        if (!option.supported) {
            return Choice.Refused(option.detail)
        }
        val config = option.config ?: return Choice.Selected(null)
        val errors = BenchConfig.validate(config).toMutableList()
        val max = BenchConfig.MAX_THREADS.coerceAtMost(safe)
        if (config.nThreads > max) {
            errors.add("n_threads exceeds device max $max")
        }
        if (errors.isNotEmpty()) {
            return Choice.Refused(errors.joinToString("; "))
        }
        return Choice.Selected(config)
    }

    /**
     * Exact pre-run line for the benchmark tab. Mirrors
     * execution resolution (override ?: default) without
     * duplicating it: callers pass the live default.
     */
    fun describe(
        override: BenchConfigData?,
        default: BenchConfigData
    ): String {
        val shown = override ?: default
        val source = if (override == null) {
            "device default"
        } else {
            "manual selection"
        }
        return "Config: ctx=${shown.nCtx} " +
            "threads=${shown.nThreads} " +
            "predict=${shown.nPredict} ($source)"
    }
}
