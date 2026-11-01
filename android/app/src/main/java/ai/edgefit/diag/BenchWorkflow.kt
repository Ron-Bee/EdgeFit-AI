package ai.edgefit.diag

/**
 * EF-UX-AERO-01 Step 3 guided-benchmark derivations.
 * Pure builders for the Model/Configure/Run/Results
 * workflow: config-source precedence, session
 * authorization display, and run availability. This
 * object never touches views, storage, or the runner —
 * the activity renders and the guards still enforce.
 */
object BenchWorkflow {

    /**
     * Effective config source, mirroring startBench
     * resolution exactly: a bound session's config wins
     * verbatim, then an explicit standalone override,
     * then the live device default.
     */
    fun resolveConfig(
        bound: BenchConfigData?,
        override: BenchConfigData?,
        default: BenchConfigData
    ): Pair<BenchConfigData, String> = when {
        bound != null -> bound to "bound session"
        override != null -> override to "manual selection"
        else -> default to "device default"
    }

    /**
     * Exact pre-run config line for the Configure step.
     * A bound session names itself (manual selection is
     * parked there); unbound runs show the effective
     * triple plus its source.
     */
    fun configLine(
        boundId: String?,
        override: BenchConfigData?,
        default: BenchConfigData
    ): String {
        if (boundId != null) {
            return "Config: from bound session $boundId " +
                "(manual selection ignored)"
        }
        return ManualBenchConfig.describe(override, default)
    }

    /** Session authorization as the Run step shows it. */
    enum class AuthState {
        /** No session bound: standalone measurement. */
        NONE,
        /** Bound and BENCHMARK_AUTHORIZED. */
        AUTHORIZED,
        /** Bound and currently BENCHMARK_RUNNING. */
        RUNNING,
        /** Bound but anything else: never attaches. */
        NOT_AUTHORIZED
    }

    fun authState(
        activeId: String?,
        record: SessionRecord?
    ): AuthState = when {
        activeId == null -> AuthState.NONE
        record == null -> AuthState.NOT_AUTHORIZED
        record.state == SessionState.BENCHMARK_AUTHORIZED ->
            AuthState.AUTHORIZED
        record.state == SessionState.BENCHMARK_RUNNING ->
            AuthState.RUNNING
        else -> AuthState.NOT_AUTHORIZED
    }

    /**
     * Honest one-line authorization for the Run step.
     * NOT AUTHORIZED shows plainly instead of implying
     * attachment; nothing here authorizes or binds —
     * bindSession and the state machine still own that,
     * and noteRunStarted still refuses a non-authorized
     * session (standalone fallback in the activity).
     */
    fun authLine(
        activeId: String?,
        record: SessionRecord?
    ): String {
        return when (authState(activeId, record)) {
            AuthState.NONE ->
                SessionView.buildActiveLine(null, null)
            AuthState.AUTHORIZED ->
                "Session: ${record?.sessionId} authorized — " +
                    "the next run attaches here."
            AuthState.RUNNING ->
                "Session: ${record?.sessionId} " +
                    "benchmark running…"
            AuthState.NOT_AUTHORIZED ->
                "Session: $activeId NOT AUTHORIZED " +
                    "(${record?.state ?: "record unavailable"}) — " +
                    "runs stay standalone."
        }
    }

    /**
     * Run availability for the Run step. A staged model
     * plus an idle runner is the whole rule —
     * contribution and evidence state are not inputs,
     * so sharing can never become a required pre-step.
     */
    fun runAllowed(
        modelStaged: Boolean,
        running: Boolean
    ): Boolean = modelStaged && !running

    /** Readable one-line Run-step guidance. */
    fun runHint(
        modelStaged: Boolean,
        running: Boolean
    ): String = when {
        running -> "A benchmark is running…"
        !modelStaged -> "Stage a model to enable Run."
        else -> "Ready — Run starts one measured benchmark."
    }

    /**
     * Model-step line with the standalone override
     * marker. The marker is the ONLY override
     * indicator, so callers must never bypass this by
     * direct assignment.
     */
    fun modelLine(
        display: String?,
        sizeBytes: Long,
        override: BenchConfigData?,
        boundId: String?
    ): String {
        if (display == null) {
            return "Model: none staged — " +
                "select a GGUF file to begin."
        }
        val marker = if (override != null && boundId == null) {
            " [standalone ${override.nCtx}/" +
                "${override.nThreads}/${override.nPredict}]"
        } else {
            ""
        }
        return "Model: $display " +
            "(${sizeBytes / 1048576} MB)$marker"
    }
}
