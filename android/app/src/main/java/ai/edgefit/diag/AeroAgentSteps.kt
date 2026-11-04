package ai.edgefit.diag

/**
 * EF-ANDROID-AERO-OVERHAUL-01 Priority Three: Meta AI
 * workflow-stepper derivation. Pure JVM over one agent
 * parent plus its children: six fixed steps from
 * recommendation to execution result, each state
 * derived from stored record facts only. Approval is
 * status display here — the dialog keeps the only
 * approval controls. An approved experiment NEVER
 * renders as executed: approval and measurement stay
 * distinct steps with distinct captions.
 */
object AeroAgentSteps {

    enum class StepState { DONE, CURRENT, TODO }

    data class Step(
        val label: String,
        val state: StepState,
        val caption: String
    )

    data class Workflow(
        val sessionCaption: String,
        val steps: List<Step>
    )

    @Suppress("UNCHECKED_CAST")
    fun workflow(
        parent: SessionRecord,
        children: List<SessionRecord>
    ): Workflow {
        val agent = parent.agent as? Map<String, Any?>
        val pending =
            agent?.get("pending_proposal") as? Map<String, Any?>
        val finished = agent?.get("status") ==
            AgentManager.STATUS_FINISHED
        val terminal =
            (agent?.get("terminal_reason") as? String)
                ?.ifBlank { null } ?: "?"
        val active = AeroAgent.isActive(parent)
        val newestPrediction = children
            .filter { it.prediction != null }
            .maxByOrNull { it.updatedAtUtc }
            ?.prediction as? Map<String, Any?>
        val rationale = (newestPrediction?.get("rationale")
            as? String)?.ifBlank { null }
        val hypothesis = (pending?.get("hypothesis")
            as? String)?.ifBlank { null }
        val cited =
            (newestPrediction?.get("evidence_cited")
                as? List<*>)?.filterNotNull() ?: emptyList<Any?>()
        val measured = children.any { child ->
            val bench = child.benchmark as? Map<String, Any?>
            bench?.get("status") ==
                BenchContract.STATUS_COMPLETED
        }
        val awaitingRun = !measured && children.any { child ->
            child.prediction != null && child.benchmark == null
        }
        val hasSignal = pending != null ||
            newestPrediction != null || measured ||
            awaitingRun
        val recommend = when {
            pending != null -> Step("Recommendation",
                StepState.DONE, "Awaiting review")
            hasSignal -> Step("Recommendation",
                StepState.DONE, "On record")
            active -> Step("Recommendation",
                StepState.CURRENT, "Awaiting first proposal")
            else -> Step("Recommendation",
                StepState.TODO, "No proposals recorded")
        }
        val why = when {
            rationale != null || hypothesis != null ->
                Step("Why", StepState.DONE,
                    "Rationale recorded")
            pending != null -> Step("Why",
                StepState.CURRENT, "Under review")
            active -> Step("Why", StepState.TODO,
                "None recorded")
            else -> Step("Why", StepState.TODO,
                "None recorded")
        }
        val evidence = when {
            cited.isNotEmpty() -> Step("Evidence",
                StepState.DONE,
                if (cited.size == 1) "1 cited"
                else "${cited.size} cited")
            pending != null -> Step("Evidence",
                StepState.CURRENT, "Under review")
            else -> Step("Evidence", StepState.TODO,
                "None cited")
        }
        val action = when {
            pending != null -> Step("Proposed action",
                StepState.DONE,
                hypothesis?.take(64) ?: "Proposal recorded")
            hasSignal -> Step("Proposed action",
                StepState.DONE, "Proposal recorded")
            active -> Step("Proposed action",
                StepState.TODO, "None proposed")
            else -> Step("Proposed action",
                StepState.TODO, "None proposed")
        }
        val approval = when {
            pending != null -> Step("Approval",
                StepState.CURRENT, "Awaiting your review")
            measured || awaitingRun || hasSignal ->
                Step("Approval", StepState.DONE, "Approved")
            active -> Step("Approval", StepState.TODO,
                "No proposal to review")
            else -> Step("Approval", StepState.TODO,
                "No proposal to review")
        }
        val result = when {
            measured -> Step("Result", StepState.DONE,
                "Measured")
            awaitingRun -> Step("Result",
                StepState.CURRENT, "Approved — awaiting run")
            finished -> Step("Result", StepState.DONE,
                "Finished ($terminal)")
            hasSignal -> Step("Result", StepState.TODO,
                "No run yet")
            active -> Step("Result", StepState.TODO,
                "No run yet")
            else -> Step("Result", StepState.TODO,
                "No run yet")
        }
        return Workflow(
            sessionCaption = "Agent ${parent.sessionId}",
            steps = listOf(
                recommend, why, evidence, action, approval,
                result))
    }
}
