package ai.edgefit.diag

/**
 * EF-UX-01D onboarding: pure retirement predicate and page
 * model (FR-ONBOARD-01, ef-ux-01-design.md E/F). The count
 * derives from durable session records on every evaluation,
 * so it survives restarts and can never drift from files.
 * No Android APIs; fully unit-tested.
 */
object Onboarding {
    /** Auto-show retires once this many completions exist. */
    const val RETIRE_COUNT = 3
    const val PAGE_COUNT = 3

    /**
     * SHOW gate, evaluated in design order: under quota,
     * not dismissed this launch, no run executing, and no
     * host modal open (agent review, Meta progress,
     * picker, or bench overlay — combined by the caller).
     */
    fun shouldShow(
        completions: Int,
        dismissedThisLaunch: Boolean,
        benchActive: Boolean,
        modalOpen: Boolean
    ): Boolean =
        completions < RETIRE_COUNT && !dismissedThisLaunch &&
            !benchActive && !modalOpen

    fun retired(completions: Int): Boolean =
        completions >= RETIRE_COUNT

    // ---- completion counting (design F) ----

    /**
     * Distinct qualifying completions over a session list.
     * Unreadable records are skipped (fail-open display),
     * never counted, never crashing the scan.
     */
    @Suppress("UNCHECKED_CAST")
    fun countCompletions(sessions: List<SessionRecord>): Int {
        val childrenByParent =
            sessions.groupBy { it.parentSessionId }
        val keys = mutableSetOf<String>()
        for (record in sessions) {
            try {
                completionKey(
                    record,
                    childrenByParent[record.sessionId]
                        ?: emptyList())?.let { keys.add(it) }
            } catch (_: Exception) {
                // Corrupt/foreign record: skip, keep scanning.
            }
        }
        return keys.size
    }

    /**
     * Counting key for one record, or null when it does not
     * qualify. Keys dedupe by run (replays by source key,
     * agent parents by parent id); each record yields at
     * most one key.
     */
    @Suppress("UNCHECKED_CAST")
    fun completionKey(
        record: SessionRecord,
        children: List<SessionRecord>
    ): String? {
        // Standalone branch (UX-D02): measured only, no
        // prediction exists to compare against.
        if (record.mode == SessionStore.MODE_STANDALONE) {
            val bench = record.benchmark as? Map<String, Any?>
                ?: return null
            if (record.state != SessionState.BENCHMARK_COMPLETED ||
                bench["status"] != BenchContract.STATUS_COMPLETED
            ) {
                return null
            }
            val runId = bench["run_id"] as? String
                ?: return null
            return "run:$runId"
        }
        // Agent children never count directly, however
        // complete; they qualify their parent instead.
        if (record.parentSessionId != null) return null
        // Agent parent: finished with a qualifying child.
        if (record.mode == "agent") {
            val agent = record.agent as? Map<String, Any?>
                ?: return null
            if (agent["status"] != AgentManager.STATUS_FINISHED) {
                return null
            }
            if (!children.any { measuredAndComparable(it) }) {
                return null
            }
            return "agent:${record.sessionId}"
        }
        // Top-level measured session: real COMPLETED
        // benchmark plus a comparable host comparison.
        if (!measuredAndComparable(record)) return null
        val bench = record.benchmark as? Map<String, Any?>
            ?: return null
        val runId = bench["run_id"] as? String ?: return null
        // Replay branch (UX-D03, producer dormant until
        // FR-RPT-01): same source artifact counts once.
        if (isReplay(record)) {
            val prediction =
                record.prediction as? Map<String, Any?>
            val source =
                prediction?.get("replay_source_sha256") as? String
            if (source != null) return "replay:$source"
        }
        return "run:$runId"
    }

    /** Rules F1-F2: attached COMPLETED run + comparable errors. */
    @Suppress("UNCHECKED_CAST")
    fun measuredAndComparable(record: SessionRecord): Boolean {
        val bench = record.benchmark as? Map<String, Any?>
            ?: return false
        if (bench["status"] != BenchContract.STATUS_COMPLETED) {
            return false
        }
        val comparison = record.comparison as? Map<String, Any?>
            ?: return false
        return comparison["comparable"] == true
    }

    private fun isReplay(record: SessionRecord): Boolean {
        if (record.mode == "replay") return true
        @Suppress("UNCHECKED_CAST")
        val prediction = record.prediction as? Map<String, Any?>
        return prediction?.get("source_type") ==
            SessionManager.SOURCE_REPLAY
    }

    // ---- page model ----

    data class Page(
        val title: String,
        val lines: List<String>,
        val primary: String
    )

    /**
     * The three approved screens. No numbers are shown, so
     * no EXAMPLE labeling is needed; cloud, statistics, and
     * the website are never described as operational.
     */
    fun pages(): List<Page> = listOf(
        Page(
            "What EdgeFit Does",
            listOf(
                "EdgeFit measures real on-device LLM " +
                    "inference on your Android hardware.",
                "Muse outputs are labeled ESTIMATE; only " +
                    "executed runs are MEASURED.",
                "Profiling and benchmarking run locally " +
                    "and work offline."),
            "Next"),
        Page(
            "How to Benchmark",
            listOf(
                "Select a GGUF model file you provide; " +
                    "EdgeFit verifies its identity.",
                "Review memory and hardware suitability " +
                    "before running.",
                "Run the standard workload and read " +
                    "measured throughput, memory, and " +
                    "thermal evidence.",
                "No Meta API key is needed to benchmark."),
            "Next"),
        Page(
            "Optional Muse-Assisted Experiments",
            listOf(
                "With your own Meta key, Muse can " +
                    "estimate performance before you measure.",
                "Every request is explicit and may " +
                    "consume quota; nothing is automatic.",
                "Follow-ups create new linked records — " +
                    "earlier measurements are never " +
                    "overwritten.",
                "Recommended Models lists offline " +
                    "candidates; catalog notes are not " +
                    "benchmark proof."),
            "Start Benchmarking")
    )

    fun nextPage(index: Int): Int =
        (index + 1).coerceAtMost(PAGE_COUNT - 1)

    fun prevPage(index: Int): Int =
        (index - 1).coerceAtLeast(0)
}
