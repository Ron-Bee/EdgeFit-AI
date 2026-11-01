package ai.edgefit.diag

/**
 * EF-UX-AERO-01 Step 5 agent-workspace derivations. Pure
 * builders for the Agent tab: role explainer, session
 * summaries, bounded inline cards, and honest empty
 * states. This object never touches views, storage, or
 * the network — the activity renders, AgentManager still
 * enforces budgets and gates, and approval still happens
 * only through the explicit workspace actions. Nothing
 * here invents recommendations or a fake active state:
 * every line derives from the records passed in.
 */
object AeroAgent {

    /** Inline parent cards before the show-more line. */
    const val INLINE_MAX = 4

    /**
     * Workspace card kinds. Recommendation is a pending
     * Meta proposal (never approved implicitly);
     * Prediction is an expected outcome without a
     * completed measurement; Measured is a COMPLETED
     * run; Approved experiment is a child created by an
     * explicit approval and still awaiting measurement;
     * Unavailable covers shells, failed runs, and
     * finished-without-evidence abstention.
     */
    enum class CardKind {
        RECOMMENDATION,
        PREDICTION,
        MEASURED,
        APPROVED_EXPERIMENT,
        UNAVAILABLE
    }

    fun kindLabel(kind: CardKind): String = when (kind) {
        CardKind.RECOMMENDATION -> "Recommendation"
        CardKind.PREDICTION -> "Prediction"
        CardKind.MEASURED -> "Measured"
        CardKind.APPROVED_EXPERIMENT -> "Approved experiment"
        CardKind.UNAVAILABLE -> "Unavailable"
    }

    /** One inline card: kind chip, title, detail lines. */
    data class Card(
        val kind: CardKind,
        val title: String,
        val lines: List<String>
    )

    data class WorkspaceSummary(
        val agentCount: Int,
        val pendingCount: Int,
        /** Newest active parent id, or null when none is active. */
        val activeId: String?,
        val headline: String,
        /** Budget line for the active session, or null. */
        val budgetLine: String?
    )

    /**
     * Static role explainer. States the approval-first
     * contract: Muse proposes, the developer approves,
     * budgets stay explicit, nothing auto-runs.
     */
    fun roleLines(): List<String> = listOf(
        "Meta Muse proposes config changes; you approve " +
            "each experiment before it runs.",
        "Every session is budgeted — used and max " +
            "attempts stay visible here.",
        "Request, approve, modify, reject, and finish " +
            "are explicit taps; each needs your touch.",
        "Proposals wait for your review; nothing runs " +
            "on its own."
    )

    /**
     * Designed empty state: no invented sessions, no
     * sample recommendations, no tok/s, no digits.
     */
    fun emptyLines(): List<String> = listOf(
        "No optimization sessions yet.",
        "Stage a model, then open the workspace to " +
            "create one.",
        "Proposals appear here only after Meta responds."
    )

    @Suppress("UNCHECKED_CAST")
    fun hasPending(parent: SessionRecord): Boolean =
        (parent.agent as? Map<String, Any?>)
            ?.get("pending_proposal") != null

    @Suppress("UNCHECKED_CAST")
    fun isActive(parent: SessionRecord): Boolean =
        (parent.agent as? Map<String, Any?>)
            ?.get("status") == AgentManager.STATUS_ACTIVE

    /**
     * Agent status word, mirroring AgentView.agentStatus:
     * active stays bare; finished names its terminal
     * reason so abstention is never silent.
     */
    @Suppress("UNCHECKED_CAST")
    fun agentStatus(parent: SessionRecord): String {
        val agent = parent.agent as? Map<String, Any?>
        val status = agent?.get("status") as? String ?: "?"
        return if (status == AgentManager.STATUS_FINISHED) {
            "finished (${agent?.get("terminal_reason") ?: "?"})"
        } else {
            status
        }
    }

    /** Status plus explicit budget usage. */
    fun statusLine(parent: SessionRecord): String =
        "${agentStatus(parent)} · attempts " +
            "${parent.budgetUsed} of ${parent.budgetMax}"

    fun summarize(parents: List<SessionRecord>): WorkspaceSummary {
        val agents = parents.filter { it.mode == "agent" }
        val pending = agents.count { hasPending(it) }
        val active = agents.filter { isActive(it) }
            .maxByOrNull { it.updatedAtUtc }
        val noun = if (agents.size == 1) "session" else "sessions"
        val proposalNoun = if (pending == 1) "proposal" else "proposals"
        val headline = when {
            agents.isEmpty() -> "No optimization sessions yet."
            pending > 0 -> "${agents.size} optimization $noun · " +
                "$pending $proposalNoun awaiting review"
            else -> "${agents.size} optimization $noun · " +
                "no pending proposals"
        }
        return WorkspaceSummary(
            agentCount = agents.size,
            pendingCount = pending,
            activeId = active?.sessionId,
            headline = headline,
            budgetLine = active?.let {
                "Active ${it.sessionId}: used ${it.budgetUsed} " +
                    "of ${it.budgetMax} attempts"
            }
        )
    }

    /**
     * Inline parent block: status/budget, identity,
     * experiment count, and the pending-or-terminal
     * line. Approval itself stays in the workspace
     * dialog; this text never approves.
     */
    fun sessionLines(
        parent: SessionRecord,
        childCount: Int
    ): List<String> {
        val lines = mutableListOf(
            statusLine(parent),
            "model: ${parent.modelDisplay} · " +
                "workload ${parent.workloadId}",
            if (childCount == 0) "no experiments yet"
            else "$childCount experiment" +
                (if (childCount == 1) "" else "s") + " recorded"
        )
        if (hasPending(parent)) {
            lines.add("Recommendation awaiting review — " +
                "open the workspace to approve or reject.")
        } else if (!isActive(parent)) {
            lines.add("Closed; full history stays in the workspace.")
        } else {
            lines.add("No proposal pending.")
        }
        lastHistoryLine(parent)?.let { lines.add(it) }
        return lines
    }

    /**
     * Most recent history entry, or null when the
     * record carries no history. Keeps the durable
     * audit trail visible inline.
     */
    fun lastHistoryLine(parent: SessionRecord): String? {
        val last = parent.history.lastOrNull() ?: return null
        return "history: ${last["state"]} — ${last["cause"]}"
    }

    /**
     * Inline proposal-gate line for an active parent
     * without a pending proposal, mirroring the
     * workspace dialog: blocked gates name the missing
     * prerequisite, recovery mode names itself, and a
     * plain open gate renders nothing.
     */
    fun gateLine(gateOk: Boolean, gateReason: String): String? =
        if (gateOk && gateReason == "ok") {
            null
        } else if (gateOk) {
            "proposal mode: $gateReason"
        } else {
            "proposal gate: $gateReason"
        }

    /**
     * Pending proposal as a Recommendation card, or null
     * when none is pending. Carries the expected
     * outcome and the developer-modified flag; review
     * and approval stay in the workspace dialog.
     */
    @Suppress("UNCHECKED_CAST")
    fun proposalCard(parent: SessionRecord): Card? {
        val agent = parent.agent as? Map<String, Any?>
            ?: return null
        val pending = agent["pending_proposal"] as? Map<String, Any?>
            ?: return null
        val seq = pending["experiment_seq"]
        val config = pending["proposed_config"] as? Map<String, Any?>
        val keys = (pending["changed_keys"] as? List<*>)
            ?.joinToString(",")
        val lines = mutableListOf<String>()
        if ((pending["modified_by"] as? String) == "developer") {
            lines.add("Modified by developer after Muse's " +
                "proposal; expected values predate the change.")
        }
        lines.addAll(listOf(
            "change: $keys -> ctx=${config?.get("n_ctx")} " +
                "threads=${config?.get("n_threads")} " +
                "predict=${config?.get("n_predict")}",
            "expected: ${pending["expected_gen_tps"]} tok/s " +
                "(${pending["expected_direction"]})",
            "hypothesis: ${pending["hypothesis"]}",
            "Open the workspace to review, modify, " +
                "approve, or reject."
        ))
        return Card(
            CardKind.RECOMMENDATION,
            "Recommendation · experiment $seq",
            lines
        )
    }

    /**
     * One experiment card for an agent child. Kind
     * derives from observable record state only: a
     * COMPLETED run is Measured; an approved child
     * still awaiting measurement is an Approved
     * experiment (experiment 1 predictions come from
     * explicit Predict, so they read Prediction); a
     * failed run keeps its prediction but reports no
     * measurement; a bare follow-up recommendation with
     * nothing approved yet is a Recommendation;
     * anything else is Unavailable.
     */
    @Suppress("UNCHECKED_CAST")
    fun experimentCard(child: SessionRecord): Card {
        val title = "Experiment ${child.experimentSeq} · " +
            child.sessionId.takeLast(8)
        val bench = child.benchmark as? Map<String, Any?>
        val status = bench?.get("status") as? String
        val prediction = child.prediction as? Map<String, Any?>
        val expected = prediction?.get("predicted_gen_tps")
        val source = prediction?.get("source_type")
        if (status == BenchContract.STATUS_COMPLETED) {
            val measured = SessionListUi.primaryMeasurement(child)
                ?: "measured (MEASURED)"
            val lines = mutableListOf(measured)
            if (expected != null) {
                lines.add("expected $expected tok/s [$source]")
            }
            return Card(CardKind.MEASURED, title, lines)
        }
        if (prediction != null && bench == null) {
            val line = "expected $expected tok/s [$source] — " +
                "awaiting measurement"
            return if (child.experimentSeq == 1) {
                Card(CardKind.PREDICTION, title,
                    listOf("predicted $expected tok/s " +
                        "[$source] — awaiting measurement"))
            } else {
                Card(CardKind.APPROVED_EXPERIMENT, title,
                    listOf("approved — $line"))
            }
        }
        if (prediction != null) {
            // Attempted but not completed: failed runs
            // carry zeroed counters, never a measurement.
            return Card(CardKind.PREDICTION, title, listOf(
                "run $status — no measurement; prediction " +
                    "stands: expected $expected tok/s [$source]"))
        }
        val followup = child.followup as? Map<String, Any?>
        val recommendation = followup?.get("recommendation")
            as? Map<String, Any?>
        if (recommendation != null) {
            return Card(CardKind.RECOMMENDATION, title, listOf(
                "recommendation recorded " +
                    "(${recommendation["direction"]}): " +
                    "${recommendation["summary"]}",
                "Nothing approved from it yet."))
        }
        if (bench != null) {
            return Card(CardKind.UNAVAILABLE, title, listOf(
                "run $status — no measurement available"))
        }
        return Card(CardKind.UNAVAILABLE, title, listOf(
            "No prediction or measurement yet."))
    }

    /**
     * Newest-first experiment cards for inline display.
     * The workspace dialog keeps the full history; this
     * is a bounded preview.
     */
    fun recentCards(
        children: List<SessionRecord>,
        max: Int = 2
    ): List<Card> = children
        .sortedByDescending { it.experimentSeq ?: 0 }
        .take(max.coerceAtLeast(0))
        .map { experimentCard(it) }

    /**
     * Finished-without-evidence abstention line, or null
     * when the parent is active or has a completed
     * measurement. The agent draws no conclusion from
     * zero completed runs.
     */
    @Suppress("UNCHECKED_CAST")
    fun abstentionLine(
        parent: SessionRecord,
        children: List<SessionRecord>
    ): String? {
        if (isActive(parent)) return null
        val anyMeasured = children.any {
            (it.benchmark as? Map<String, Any?>)
                ?.get("status") == BenchContract.STATUS_COMPLETED
        }
        if (anyMeasured) return null
        val agent = parent.agent as? Map<String, Any?>
        val reason = agent?.get("terminal_reason") ?: "?"
        return "Finished ($reason) with no completed " +
            "measurements — no conclusion drawn."
    }

    fun showMoreLine(total: Int, shown: Int): String? =
        if (total > shown) {
            "Showing $shown of $total — open the workspace for all."
        } else {
            null
        }
}
