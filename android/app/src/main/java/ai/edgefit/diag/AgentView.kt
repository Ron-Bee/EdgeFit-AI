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
        val gateReason: String
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
        if (!detail.gateOk && agentStatus(parent) ==
            AgentManager.STATUS_ACTIVE
        ) {
            lines.add("proposal gate: ${detail.gateReason}")
        }
        return lines.joinToString("\n")
    }

    @Suppress("UNCHECKED_CAST")
    fun experimentLines(detail: AgentDetail): List<String> {
        val byChild = (detail.pack.comparable + detail.pack.contextual)
            .associateBy { it.childId }
        return detail.children.map { child ->
            val bench = child.benchmark as? Map<String, Any?>
            val status = bench?.get("status") as? String ?: child.state
            val evidence = byChild[child.sessionId]
            val measured = if (evidence == null) {
                "no measurement yet"
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
            "exp ${child.experimentSeq}: $status — $measured; $expected"
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun proposalText(pending: Map<String, Any?>): String {
        val config = pending["proposed_config"] as? Map<String, Any?>
        val keys = (pending["changed_keys"] as? List<*>)?.joinToString(",")
        return listOf(
            "Pending proposal for experiment ${pending["experiment_seq"]}",
            "change: $keys -> " +
                "ctx=${config?.get("n_ctx")} " +
                "threads=${config?.get("n_threads")} " +
                "predict=${config?.get("n_predict")}",
            "hypothesis: ${pending["hypothesis"]}",
            "expected: ${pending["expected_gen_tps"]} tok/s " +
                "(${pending["expected_direction"]})",
            "uncertainty: ${pending["uncertainty"]}",
            "evidence: ${(pending["supporting_evidence_ids"] as? List<*>)
                ?.joinToString(",")}",
            "benefit: ${pending["benefit"]}",
            "limitations: ${(pending["limitations"] as? List<*>)
                ?.joinToString("; ")}",
            "host_notes: ${(pending["host_notes"] as? List<*>)
                ?.joinToString("; ").orEmpty().ifEmpty { "none" }}",
            "hash: ${(pending["proposal_hash"] as? String)
                ?.take(12)}... pack ${(pending["evidence_pack_hash"]
                as? String)?.take(12)}..."
        ).joinToString("\n")
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
        onFinish: (String) -> String
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
            for (line in experimentLines(snapshot)) {
                body.addView(TextView(activity).apply {
                    text = line
                    textSize = 13f
                })
            }
            @Suppress("UNCHECKED_CAST")
            val agent = snapshot.parent.agent as? Map<String, Any?>
            val pending = agent?.get("pending_proposal") as? Map<String, Any?>
            if (pending != null) {
                body.addView(TextView(activity).apply {
                    text = proposalText(pending)
                    textSize = 13f
                    setTextIsSelectable(true)
                    setPadding(0, dp(8), 0, 0)
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
            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun addButton(label: String, action: () -> Unit): Button {
                val button = Button(activity).apply {
                    text = label
                    setOnClickListener { action() }
                }
                actions.addView(button, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                return button
            }
            if (pending == null) {
                addButton("Request Proposal") {
                    dialog?.dismiss()
                    onRequest(snapshot.parent.sessionId)
                }.isEnabled = snapshot.gateOk
            } else {
                addButton("Approve & Run Once") {
                    dialog?.dismiss()
                    onApprove(snapshot.parent.sessionId)
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
    }
}
