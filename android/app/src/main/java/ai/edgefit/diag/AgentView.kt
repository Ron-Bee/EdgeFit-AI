package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Bounded optimization-loop dialog: parent status, experiment
 * history, evidence considered, pending proposal with validated
 * state, budget, and Approve / Reject / Finish controls. Execution
 * itself runs through the existing benchmark screen. Main thread
 * only; text builders are pure and unit-tested.
 */
object AgentView {

    /** UI-ready snapshot; built by the activity from [AgentManager]. */
    data class AgentDetail(
        val parent: SessionRecord,
        val children: List<SessionRecord>,
        val pack: AgentEvidence.EvidencePack,
        val gateOk: Boolean,
        val gateReason: String,
        /**
         * EF-AGENT-REVIEW-01: independent host review of the
         * pending proposal, or null when none is pending.
         */
        val review: AgentReview.ReviewResult? = null
    )

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

    fun parentLine(parent: SessionRecord): String =
        "${parent.sessionId} ${agentStatus(parent)} " +
            "attempts ${parent.budgetUsed}/${parent.budgetMax}"

    /**
     * Notice arbitration after an in-place action + refresh
     * (AUD-02): a refresh failure (list/detail error) wins, since
     * it describes the visible state; otherwise the action's own
     * success/error message survives instead of being wiped by the
     * refresh. Pure for unit tests.
     */
    fun resolveNotice(existing: String, actionMessage: String): String =
        if (existing.isEmpty()) actionMessage else existing

    @Suppress("UNCHECKED_CAST")
    fun statusText(detail: AgentDetail): String {
        val parent = detail.parent
        val lines = mutableListOf(
            "Agent session ${parent.sessionId}",
            "status: ${agentStatus(parent)}",
            "budget: used ${parent.budgetUsed} of ${parent.budgetMax}",
            "model: ${parent.modelDisplay}",
            "workload: ${parent.workloadId}"
        )
        val agent = parent.agent as? Map<String, Any?>
        val strikes = (agent?.get("invalid_strikes") as? Number)?.toInt() ?: 0
        if (strikes > 0) lines.add("invalid proposal strikes: $strikes/2")
        lines.add("comparable evidence: ${detail.pack.comparable.size} " +
            "runs, contextual: ${detail.pack.contextual.size}")
        val best = detail.pack.comparable.maxByOrNull { it.genTps }
        if (best == null) {
            lines.add("best comparable: none yet")
        } else {
            // Highest measured generation throughput among
            // comparable runs. Display only; the pack
            // itself is unchanged.
            lines.add("best comparable: ${best.runId} " +
                "${best.genTps} tok/s (ctx=${best.nCtx} " +
                "t=${best.nThreads} n=${best.nPredict})")
        }
        // EF-MEM-03: blocked gates name the missing
        // prerequisite; an open recovery gate names the
        // failed attempt it will reason about.
        if (agentStatus(parent) == AgentManager.STATUS_ACTIVE &&
            (!detail.gateOk || detail.gateReason != "ok")
        ) {
            lines.add(if (detail.gateOk) {
                "proposal mode: ${detail.gateReason}"
            } else {
                "proposal gate: ${detail.gateReason}"
            })
        }
        return lines.joinToString("\n")
    }

    /**
     * Experiment line plus its status token, so the
     * renderer can tint the status word. Text unchanged.
     */
    data class ExperimentItem(val status: String, val text: String)

    fun experimentLines(detail: AgentDetail): List<String> =
        experimentItems(detail).map { it.text }

    @Suppress("UNCHECKED_CAST")
    fun experimentItems(detail: AgentDetail): List<ExperimentItem> {
        val byChild = (detail.pack.comparable + detail.pack.contextual)
            .associateBy { it.childId }
        return detail.children.map { child ->
            val bench = child.benchmark as? Map<String, Any?>
            val status = bench?.get("status") as? String ?: child.state
            val evidence = byChild[child.sessionId]
            val measured = if (evidence == null) {
                "no measurement yet"
            } else if (status != BenchContract.STATUS_COMPLETED) {
                // Failed runs carry zeroed counters, not
                // observations: never render them as tok/s.
                "no measurement (run $status)"
            } else {
                "${evidence.genTps} tok/s " +
                    "(ctx=${evidence.nCtx} t=${evidence.nThreads} " +
                    "n=${evidence.nPredict}" +
                    (if (evidence.comparable) "" else
                        "; contextual: ${evidence.contextReason}") + ")"
            }
            val prediction = child.prediction as? Map<String, Any?>
            val expected = prediction?.get("predicted_gen_tps")?.let {
                "expected $it tok/s [${prediction["source_type"]}]"
            } ?: "prediction pending"
            // A COMPLETED run that underperforms is a missed
            // hypothesis — never a failed benchmark.
            val suffix = if (evidence?.comparable == true) {
                InsightCards.outcomeSuffix(
                    status, evidence.genTps,
                    (prediction?.get("predicted_gen_tps") as? Number)
                        ?.toDouble())
            } else {
                ""
            }
            ExperimentItem(status,
                "exp ${child.experimentSeq}: $status — $measured; " +
                    "$expected$suffix")
        }
    }

    @Suppress("UNCHECKED_CAST")
    /**
     * @param review independent host verdict; null shows the
     * proposal without review (legacy callers, tests).
     */
    fun proposalText(
        pending: Map<String, Any?>,
        review: AgentReview.ReviewResult? = null
    ): String {
        val config = pending["proposed_config"] as? Map<String, Any?>
        val keys = (pending["changed_keys"] as? List<*>)?.joinToString(",")
        val lines = mutableListOf(
            "Pending proposal for experiment ${pending["experiment_seq"]}")
        if ((pending["modified_by"] as? String) == "developer") {
            lines.add("MODIFIED by developer after Muse's proposal; " +
                "expected values predate the change")
        }
        if (review != null) {
            lines.add("Independent host review: ${review.verdict}")
            review.objections.forEach { lines.add("HOST: $it") }
            if (review.verdict == AgentReview.Verdict.INVALID) {
                lines.add("Approval is blocked: revise via Modify, or Reject.")
            }
        }
        // EF-FOLLOWUP-02: repeats re-measure one cited run
        // with the identical config; label the purpose.
        if ((pending["purpose"] as? String) == AgentApi.PURPOSE_REPEAT) {
            lines.add("REPEAT of ${pending["repeat_of_run_id"]} " +
                "(same config; variability check)")
        }
        lines.add(
            "change: $keys -> " +
                "ctx=${config?.get("n_ctx")} " +
                "threads=${config?.get("n_threads")} " +
                "predict=${config?.get("n_predict")}")
        lines.addAll(listOf(
            "hypothesis: ${pending["hypothesis"]}",
            "expected: ${pending["expected_gen_tps"]} tok/s " +
                "(${pending["expected_direction"]})",
            "uncertainty: ${pending["uncertainty"]}",
            "evidence: ${(pending["supporting_evidence_ids"] as? List<*>)
                ?.joinToString(",")}",
            "benefit: ${pending["benefit"]}",
            "limitations: ${(pending["limitations"] as? List<*>)
                ?.joinToString("; ")}",
            // EF-AGENT-REVIEW-01: model self-critique, always
            // labeled as the model's own words — never host
            // verification. Absent in v1 replies.
            "model self-critique (not verification): " +
                "${(pending["counterargument"] as? String)
                    ?.ifBlank { null } ?: "not stated"}",
            "alternative reading: " +
                "${(pending["alternative_explanation"] as? String)
                    ?.ifBlank { null } ?: "not stated"}",
            "one run would establish: " +
                "${(pending["next_benchmark_establishes"] as? String)
                    ?.ifBlank { null } ?: "not stated"}",
            "host_notes: ${(pending["host_notes"] as? List<*>)
                ?.joinToString("; ").orEmpty().ifEmpty { "none" }}",
            "hash: ${(pending["proposal_hash"] as? String)
                ?.take(12)}... pack ${(pending["evidence_pack_hash"]
                as? String)?.take(12)}..."
        ))
        return lines.joinToString("\n")
    }

    @Suppress("UNCHECKED_CAST")
    fun summaryText(parent: SessionRecord): String {
        val agent = parent.agent as? Map<String, Any?>
        val summary = agent?.get("summary") as? Map<String, Any?>
            ?: return "No summary recorded."
        return listOf(
            "Terminal summary (${agent?.get("terminal_reason")})",
            "attempts: ${summary["attempts_used"]}/${summary["attempts_max"]}",
            "completed=${summary["completed"]} " +
                "failed=${summary["failed"]} " +
                "cancelled=${summary["cancelled"]}",
            "best: ${summary["best_run_id"]} " +
                "${summary["best_gen_tps"]} tok/s " +
                "(${summary["best_config"]})"
        ).joinToString("\n")
    }

    /**
     * @param parents newest-first agent sessions.
     * @param detail snapshot for the selected parent, or null.
     * In-place actions return a status message and refresh;
     * [onRequest]/[onApprove] dismiss first (they open their own UI).
     */
    fun show(
        activity: Activity,
        initialId: String?,
        parents: () -> List<SessionRecord>,
        detail: (String) -> AgentDetail?,
        onNew: () -> String,
        onRequest: (String) -> Unit,
        onApprove: (String) -> Unit,
        onReject: (String) -> String,
        onFinish: (String) -> String,
        // EF-UX-01D: lets the host track review-modal
        // visibility for onboarding gating. Optional;
        // existing callers unaffected.
        onClose: (() -> Unit)? = null,
        // EF-AGENT-REVIEW-01: developer Modify action for a
        // pending proposal; null hides the button.
        onModify: ((String) -> Unit)? = null
    ) {
        var dialog: AlertDialog? = null
        var selected = initialId
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val notice = TextView(activity).apply { textSize = 14f }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun refresh() {
            body.removeAllViews()
            notice.text = ""
            val list = try {
                parents()
            } catch (e: Exception) {
                notice.text = "Agent list failed: ${e.message}"
                return
            }
            if (selected == null) {
                selected = list.firstOrNull()?.sessionId
            }
            val switcher = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            for (parent in list.take(10)) {
                val row = Button(activity).apply {
                    text = (if (parent.sessionId == selected) "> " else "") +
                        parentLine(parent)
                    textSize = 12f
                    setOnClickListener {
                        selected = parent.sessionId
                        refresh()
                    }
                }
                switcher.addView(row)
            }
            body.addView(switcher)
            body.addView(Button(activity).apply {
                text = "New Agent Session"
                SemanticUi.stylePrimary(activity, this)
                setOnClickListener {
                    val message = onNew()
                    selected = null
                    refresh()
                    notice.text = resolveNotice(
                        notice.text.toString(), message)
                }
            })
            val id = selected
            val snapshot = id?.let {
                try {
                    detail(it)
                } catch (e: Exception) {
                    notice.text = "Detail failed: ${e.message}"
                    null
                }
            } ?: return
            body.addView(TextView(activity).apply {
                text = statusText(snapshot)
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
            })
            for (item in experimentItems(snapshot)) {
                // One card per experiment: number, config,
                // prediction, outcome stay grouped.
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background =
                        activity.getDrawable(R.drawable.card_bg)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val row = TextView(activity).apply { textSize = 13f }
                SemanticUi.tintToken(
                    row, item.text, item.status,
                    SemanticUi.accentColor(activity,
                        InsightCards.statusAccent(item.status)))
                card.addView(row)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, dp(4), 0, dp(4))
                body.addView(card, params)
            }
            @Suppress("UNCHECKED_CAST")
            val agent = snapshot.parent.agent as? Map<String, Any?>
            val pending = agent?.get("pending_proposal") as? Map<String, Any?>
            if (pending != null) {
                body.addView(TextView(activity).apply {
                    text = proposalText(pending, snapshot.review)
                    textSize = 13f
                    setTextIsSelectable(true)
                    setPadding(0, dp(8), 0, 0)
                })
                body.addView(Button(activity).apply {
                    text = "Review Details"
                    setOnClickListener {
                        CardDialog.show(activity, "Proposal Review",
                            InsightCards.proposalSections(
                                pending, snapshot.parent.config,
                                snapshot.parent.budgetUsed,
                                snapshot.parent.budgetMax,
                                snapshot.review))
                    }
                })
            }
            if (agentStatus(snapshot.parent) != AgentManager.STATUS_ACTIVE) {
                body.addView(TextView(activity).apply {
                    text = summaryText(snapshot.parent)
                    textSize = 13f
                    setPadding(0, dp(8), 0, 0)
                })
                return
            }
            // UX-01E: vertical stack — multi-word labels
            // ("Request Proposal", "Approve & Run Once")
            // wrapped in a horizontal row on narrow screens.
            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            fun addButton(label: String, action: () -> Unit): Button {
                val button = Button(activity).apply {
                    text = label
                    isSingleLine = true
                    setOnClickListener { action() }
                }
                actions.addView(button, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
                return button
            }
            if (pending == null) {
                val recoveryMode = snapshot.gateOk &&
                    snapshot.gateReason != "ok"
                val request = addButton(
                    if (recoveryMode) "Request Recovery Proposal"
                    else "Request Proposal"
                ) {
                    dialog?.dismiss()
                    onRequest(snapshot.parent.sessionId)
                }
                request.isEnabled = snapshot.gateOk
                SemanticUi.stylePrimary(activity, request)
            } else {
                val approve = addButton("Approve & Run Once") {
                    dialog?.dismiss()
                    onApprove(snapshot.parent.sessionId)
                }
                // EF-AGENT-REVIEW-01: INVALID verdicts block
                // approval in the UI; the approve path itself
                // re-verifies fail-closed. CAUTION stays
                // approvable by explicit developer choice.
                if (snapshot.review?.verdict ==
                    AgentReview.Verdict.INVALID
                ) {
                    approve.isEnabled = false
                }
                SemanticUi.stylePrimary(activity, approve)
                if (onModify != null) {
                    addButton("Modify") {
                        dialog?.dismiss()
                        onModify.invoke(snapshot.parent.sessionId)
                    }
                }
                addButton("Reject") {
                    val message = onReject(snapshot.parent.sessionId)
                    refresh()
                    notice.text = resolveNotice(
                        notice.text.toString(), message)
                }
            }
            addButton("Finish") {
                val message = onFinish(snapshot.parent.sessionId)
                refresh()
                notice.text = resolveNotice(
                    notice.text.toString(), message)
            }
            body.addView(actions)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(notice)
            addView(body)
        }
        refresh()
        dialog = AlertDialog.Builder(activity)
            .setTitle("Optimization Agent")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
        dialog?.setOnDismissListener { onClose?.invoke() }
    }
}
