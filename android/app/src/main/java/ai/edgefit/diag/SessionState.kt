package ai.edgefit.diag

/**
 * Session lifecycle states. Distinct from [BenchExecutionState], which
 * tracks one native run: a session wraps prediction, authorization,
 * benchmark association, comparison, and follow-up. Every transition
 * is allowlisted; anything else is refused. Pure JVM.
 */
object SessionState {
    const val SESSION_CREATED = "SESSION_CREATED"
    const val PREDICTION_PENDING = "PREDICTION_PENDING"
    const val PREDICTION_RECORDED = "PREDICTION_RECORDED"
    const val BENCHMARK_AUTHORIZED = "BENCHMARK_AUTHORIZED"
    const val BENCHMARK_RUNNING = "BENCHMARK_RUNNING"
    const val BENCHMARK_COMPLETED = "BENCHMARK_COMPLETED"
    const val BENCHMARK_FAILED = "BENCHMARK_FAILED"
    const val BENCHMARK_CANCELLED = "BENCHMARK_CANCELLED"
    const val BENCHMARK_TIMEOUT = "BENCHMARK_TIMEOUT"
    const val COMPARISON_READY = "COMPARISON_READY"
    const val FOLLOWUP_PENDING = "FOLLOWUP_PENDING"
    const val RECOMMENDATION_RECORDED = "RECOMMENDATION_RECORDED"
    const val SESSION_FINISHED = "SESSION_FINISHED"

    val ALL = setOf(
        SESSION_CREATED, PREDICTION_PENDING, PREDICTION_RECORDED,
        BENCHMARK_AUTHORIZED, BENCHMARK_RUNNING, BENCHMARK_COMPLETED,
        BENCHMARK_FAILED, BENCHMARK_CANCELLED, BENCHMARK_TIMEOUT,
        COMPARISON_READY, FOLLOWUP_PENDING, RECOMMENDATION_RECORDED,
        SESSION_FINISHED
    )

    /** BENCHMARK_TIMEOUT mirrors the contract status so timeouts are
     * never misreported; justification recorded in ef-session-01.
     * The CREATED->AUTHORIZED edge exists ONLY for standalone
     * mode (UX-D02: no prediction to record); two-arg callers
     * keep the historic refusal. */
    fun can(from: String, to: String, mode: String? = null): Boolean {
        if (from == to) return true
        return when (from) {
            SESSION_CREATED -> to == PREDICTION_PENDING ||
                to == SESSION_FINISHED ||
                (to == BENCHMARK_AUTHORIZED &&
                    mode == SessionStore.MODE_STANDALONE)
            PREDICTION_PENDING -> to == PREDICTION_RECORDED ||
                to == SESSION_CREATED || to == SESSION_FINISHED
            PREDICTION_RECORDED -> to == BENCHMARK_AUTHORIZED ||
                to == SESSION_FINISHED
            BENCHMARK_AUTHORIZED -> to == BENCHMARK_RUNNING ||
                to == SESSION_FINISHED
            BENCHMARK_RUNNING -> to == BENCHMARK_COMPLETED ||
                to == BENCHMARK_FAILED || to == BENCHMARK_CANCELLED ||
                to == BENCHMARK_TIMEOUT || to == SESSION_FINISHED
            BENCHMARK_COMPLETED -> to == COMPARISON_READY ||
                to == SESSION_FINISHED
            BENCHMARK_FAILED, BENCHMARK_CANCELLED, BENCHMARK_TIMEOUT ->
                to == SESSION_FINISHED
            COMPARISON_READY -> to == FOLLOWUP_PENDING ||
                to == SESSION_FINISHED
            FOLLOWUP_PENDING -> to == RECOMMENDATION_RECORDED ||
                to == COMPARISON_READY || to == SESSION_FINISHED
            RECOMMENDATION_RECORDED -> to == SESSION_FINISHED
            SESSION_FINISHED -> false
            else -> false
        }
    }

    fun isTerminal(state: String): Boolean = state == SESSION_FINISHED

    fun isBenchTerminal(state: String): Boolean = state in setOf(
        BENCHMARK_COMPLETED, BENCHMARK_FAILED, BENCHMARK_CANCELLED,
        BENCHMARK_TIMEOUT
    )
}
