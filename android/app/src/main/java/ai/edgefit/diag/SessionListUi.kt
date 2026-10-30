package ai.edgefit.diag

import java.util.Locale

/**
 * EF-UX-02 pure session-list logic: newest-first ordering,
 * friendly state labels, filters, Action Needed eligibility,
 * and compact row text. No Android views; fully unit-tested.
 */
object SessionListUi {
    /** Outcome of a create/repeat request: notice text
     * plus the new session id on success (null when
     * refused/failed). */
    data class CreateOutcome(
        val message: String,
        val newSessionId: String?
    )

    enum class SessionFilter(val label: String) {
        ALL("All Sessions"),
        AGENT("Agent Experiments"),
        OFFLINE_TEST("Offline Tests"),
        BYOK("Live BYOK"),
        ACTION_NEEDED("Action Needed"),
        COMPLETED("Completed")
    }

    /**
     * Newest first by durable creation stamp. Ties (same-second
     * creation) break on session id so ordering is stable
     * across refreshes and relaunches. Never uses the
     * modification stamp: a follow-up must not reorder history.
     */
    fun sortNewestFirst(
        sessions: List<SessionRecord>
    ): List<SessionRecord> =
        sessions.sortedWith(
            compareByDescending<SessionRecord> { it.createdAtUtc }
                .thenByDescending { it.sessionId })

    /** Id of the first row of a newest-first list, or null. */
    fun latestId(sortedNewestFirst: List<SessionRecord>): String? =
        sortedNewestFirst.firstOrNull()?.sessionId

    /** Human-friendly lifecycle label. Unknown states pass
     * through unchanged so new states stay visible. */
    fun friendlyState(record: SessionRecord): String =
        when (record.state) {
            SessionState.SESSION_CREATED -> "Ready for Prediction"
            SessionState.PREDICTION_PENDING -> "Prediction Pending"
            SessionState.PREDICTION_RECORDED -> "Predicted"
            SessionState.BENCHMARK_AUTHORIZED -> "Ready to Run"
            SessionState.BENCHMARK_RUNNING -> "Benchmark Running"
            SessionState.BENCHMARK_COMPLETED -> "Measured"
            SessionState.BENCHMARK_FAILED -> "Benchmark Failed"
            SessionState.BENCHMARK_CANCELLED -> "Cancelled"
            SessionState.BENCHMARK_TIMEOUT -> "Timed Out"
            SessionState.COMPARISON_READY ->
                if (canFollowUp(record)) "Ready for Follow-Up"
                else "Compared"
            SessionState.FOLLOWUP_PENDING -> "Follow-Up Pending"
            SessionState.RECOMMENDATION_RECORDED -> "Complete"
            SessionState.SESSION_FINISHED -> "Finished"
            else -> record.state
        }

    /** Follow-up gate shared by the session list and the
     * benchmark-completion result dialog: a byok-family record
     * (standalone or agent child) with a comparison and no
     * recorded assessment yet. */
    fun canFollowUp(record: SessionRecord): Boolean =
        record.mode == "byok" && record.followup == null &&
            (record.state == SessionState.COMPARISON_READY ||
                record.state == SessionState.FOLLOWUP_PENDING)

    /** Compact type label for collapsed rows. */
    fun typeShort(record: SessionRecord): String = when {
        record.mode == "agent" -> "Agent"
        record.parentSessionId != null ->
            "Experiment ${record.experimentSeq ?: "?"}"
        record.mode == "byok" -> "BYOK"
        record.mode == "offline_test" -> "Offline Test"
        record.mode == "replay" -> "Replay"
        record.mode == SessionStore.MODE_STANDALONE -> "Standalone"
        else -> record.mode
    }

    /** Recognizable row label: type plus an 8-hex id suffix. */
    fun rowLabel(record: SessionRecord): String =
        "${typeShort(record)} " +
            record.sessionId.removePrefix("SES-").take(8)

    /** Short model name for collapsed rows; the full display
     * name stays in Details. */
    fun shortModelName(modelDisplay: String): String {
        val name = modelDisplay.removeSuffix(".gguf")
            .replace('_', ' ')
        return if (name.length > 30) name.take(27) + "..." else name
    }

    /** "512 ctx · 4 threads · 64 tokens"; "?" per unknown key. */
    fun configShort(config: Map<String, Any?>): String {
        fun num(key: String): String =
            (config[key] as? Number)?.toLong()?.toString() ?: "?"
        return "${num("n_ctx")} ctx · ${num("n_threads")} " +
            "threads · ${num("n_predict")} tokens"
    }

    /** Most useful completed measurement ("17.74 tok/s"), or
     * null when no valid completed observation exists. Failed
     * runs never surface as zero-throughput readings. */
    @Suppress("UNCHECKED_CAST")
    fun primaryMeasurement(record: SessionRecord): String? {
        val bench = record.benchmark as? Map<String, Any?>
            ?: return null
        if (bench["status"] != BenchContract.STATUS_COMPLETED) {
            return null
        }
        val meas = bench["measurements"] as? Map<String, Any?>
            ?: return null
        val gen = (meas["tps_gen"] as? Number)?.toDouble()
            ?: return null
        if (!gen.isFinite()) return null
        return String.format(Locale.US, "%.2f tok/s", gen)
    }

    /**
     * Genuinely actionable states: a prediction can be
     * requested or retried, an authorized benchmark is waiting
     * to run, or a follow-up is eligible. Terminal states
     * (Complete, Finished, failed/cancelled runs) never count,
     * even though their details remain viewable.
     */
    fun needsAction(record: SessionRecord): Boolean {
        if (SessionView.canPredict(record)) return true
        return when (record.state) {
            SessionState.BENCHMARK_AUTHORIZED,
            SessionState.PREDICTION_PENDING,
            SessionState.FOLLOWUP_PENDING -> true
            SessionState.COMPARISON_READY -> canFollowUp(record)
            else -> false
        }
    }

    /** True when a completed benchmark is attached. */
    @Suppress("UNCHECKED_CAST")
    fun completedBenchmark(record: SessionRecord): Boolean {
        val bench = record.benchmark as? Map<String, Any?>
            ?: return false
        return bench["status"] == BenchContract.STATUS_COMPLETED
    }

    /** Filter preserves the caller's (newest-first) order. */
    fun filterSessions(
        sessions: List<SessionRecord>,
        filter: SessionFilter
    ): List<SessionRecord> = when (filter) {
        SessionFilter.ALL -> sessions
        SessionFilter.AGENT -> sessions.filter {
            it.mode == "agent" || it.parentSessionId != null
        }
        SessionFilter.OFFLINE_TEST -> sessions.filter {
            it.mode == "offline_test"
        }
        SessionFilter.BYOK -> sessions.filter {
            it.mode == "byok" && it.parentSessionId == null
        }
        SessionFilter.ACTION_NEEDED -> sessions.filter {
            needsAction(it)
        }
        SessionFilter.COMPLETED -> sessions.filter {
            completedBenchmark(it)
        }
    }

    fun emptyStateText(filter: SessionFilter): String =
        when (filter) {
            SessionFilter.ALL -> "No local sessions yet. Create an " +
                "offline test below, or stage a model and run a " +
                "benchmark."
            SessionFilter.AGENT -> "No Agent sessions yet. Open the " +
                "Agent tab to start an optimization session."
            SessionFilter.OFFLINE_TEST -> "No offline test sessions " +
                "yet. Use the button below to create one."
            SessionFilter.BYOK -> "No live Meta (BYOK) sessions yet. " +
                "Connect a key in Settings to request predictions."
            SessionFilter.ACTION_NEEDED -> "Nothing needs action. All " +
                "sessions are complete, finished, or waiting on no " +
                "input."
            SessionFilter.COMPLETED -> "No completed measurements yet."
        }
}
