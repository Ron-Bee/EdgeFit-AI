package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroAgentStepsTest {

    private fun parent(
        updated: String = "2026-01-02T00:00:00Z",
        status: String = AgentManager.STATUS_ACTIVE,
        terminal: String? = null,
        pending: Map<String, Any?>? = null
    ): SessionRecord = SessionRecord(
        sessionId = "SES-parent", mode = "agent",
        state = SessionState.SESSION_CREATED,
        createdAtUtc = updated, updatedAtUtc = updated,
        deviceModel = null, profileSha256 = null,
        modelSha256 = "0".repeat(64), modelSizeBytes = 1,
        modelDisplay = "m", workloadId = "w",
        promptSha256 = "1".repeat(64),
        config = emptyMap(), prediction = null,
        benchmark = null, comparison = null,
        followup = null, parentSessionId = null,
        experimentSeq = null, budgetUsed = 1,
        budgetMax = 5,
        agent = mapOf(
            "status" to status,
            "terminal_reason" to terminal,
            "pending_proposal" to pending),
        history = emptyList(), recoveryNotes = emptyList()
    )

    private fun child(
        id: String,
        updated: String,
        prediction: Map<String, Any?>? = null,
        benchmark: Map<String, Any?>? = null
    ): SessionRecord = SessionRecord(
        sessionId = id, mode = "agent",
        state = SessionState.SESSION_CREATED,
        createdAtUtc = updated, updatedAtUtc = updated,
        deviceModel = null, profileSha256 = null,
        modelSha256 = "0".repeat(64), modelSizeBytes = 1,
        modelDisplay = "m", workloadId = "w",
        promptSha256 = "1".repeat(64),
        config = emptyMap(), prediction = prediction,
        benchmark = benchmark, comparison = null,
        followup = null, parentSessionId = "SES-parent",
        experimentSeq = 1, budgetUsed = 1, budgetMax = 5,
        agent = null, history = emptyList(),
        recoveryNotes = emptyList()
    )

    private fun prediction(
        rationale: String? = "Try more threads.",
        cited: List<String>? = listOf("BENCH-a", "BENCH-b")
    ): Map<String, Any?> = mapOf(
        "predicted_gen_tps" to 12.0,
        "rationale" to rationale,
        "evidence_cited" to cited
    )

    private fun benchmark(
        status: String = BenchContract.STATUS_COMPLETED
    ): Map<String, Any?> = mapOf(
        "run_id" to "BENCH-x",
        "status" to status,
        "measurements" to mapOf(
            "tps_gen" to 14.5, "tps_prefill" to 30.0),
        "collected_at_utc" to "2026-01-02T00:00:00Z"
    )

    private fun states(
        workflow: AeroAgentSteps.Workflow
    ): List<AeroAgentSteps.StepState> =
        workflow.steps.map { it.state }

    @Test
    fun activeWithNothingPointsAtRecommendation() {
        val flow = AeroAgentSteps.workflow(
            parent(), emptyList())
        assertEquals(
            listOf(
                AeroAgentSteps.StepState.CURRENT,
                AeroAgentSteps.StepState.TODO,
                AeroAgentSteps.StepState.TODO,
                AeroAgentSteps.StepState.TODO,
                AeroAgentSteps.StepState.TODO,
                AeroAgentSteps.StepState.TODO),
            states(flow))
        assertEquals(6, flow.steps.size)
        assertTrue(flow.sessionCaption.contains("SES-parent"))
        val joined = flow.steps.joinToString("\n") {
            "${it.label}: ${it.caption}"
        }
        assertTrue(joined.contains("Awaiting first proposal"))
    }

    @Test
    fun pendingProposalParksApprovalAsCurrent() {
        val flow = AeroAgentSteps.workflow(
            parent(pending = mapOf(
                "proposal_hash" to "ab".repeat(32),
                "hypothesis" to
                    "More threads raise throughput.")),
            emptyList())
        assertEquals(
            listOf(
                AeroAgentSteps.StepState.DONE,
                AeroAgentSteps.StepState.DONE,
                AeroAgentSteps.StepState.CURRENT,
                AeroAgentSteps.StepState.DONE,
                AeroAgentSteps.StepState.CURRENT,
                AeroAgentSteps.StepState.TODO),
            states(flow))
        assertEquals("Awaiting your review",
            flow.steps[4].caption)
        assertTrue(flow.steps[3].caption.contains(
            "More threads"))
    }

    @Test
    fun storedRationaleAndCitedEvidenceLightUp() {
        val flow = AeroAgentSteps.workflow(
            parent(),
            listOf(child("SES-c1", "2026-01-03T00:00:00Z",
                prediction = prediction())) )
        assertEquals(AeroAgentSteps.StepState.DONE,
            flow.steps[1].state)
        assertEquals(AeroAgentSteps.StepState.DONE,
            flow.steps[2].state)
        assertEquals("2 cited", flow.steps[2].caption)
    }

    @Test
    fun approvedAwaitingRunIsNeverExecuted() {
        val flow = AeroAgentSteps.workflow(
            parent(),
            listOf(child("SES-c1", "2026-01-03T00:00:00Z",
                prediction = prediction(),
                benchmark = null)))
        assertEquals(AeroAgentSteps.StepState.DONE,
            flow.steps[4].state)
        assertEquals("Approved", flow.steps[4].caption)
        assertEquals(AeroAgentSteps.StepState.CURRENT,
            flow.steps[5].state)
        assertEquals("Approved — awaiting run",
            flow.steps[5].caption)
    }

    @Test
    fun measuredChildCompletesTheStrip() {
        val flow = AeroAgentSteps.workflow(
            parent(),
            listOf(child("SES-c1", "2026-01-03T00:00:00Z",
                prediction = prediction(),
                benchmark = benchmark())))
        assertTrue(states(flow).all {
            it == AeroAgentSteps.StepState.DONE
        })
        assertEquals("Measured", flow.steps[5].caption)
    }

    @Test
    fun finishedWithoutMeasurementNamesTerminalReason() {
        val flow = AeroAgentSteps.workflow(
            parent(status = AgentManager.STATUS_FINISHED,
                terminal = "budget_exhausted"),
            emptyList())
        assertEquals(AeroAgentSteps.StepState.DONE,
            flow.steps[5].state)
        assertTrue(flow.steps[5].caption.contains(
            "budget_exhausted"))
    }

    @Test
    fun stepLabelsAreFixedOrder() {
        val flow = AeroAgentSteps.workflow(
            parent(), emptyList())
        assertEquals(
            listOf("Recommendation", "Why", "Evidence",
                "Proposed action", "Approval", "Result"),
            flow.steps.map { it.label })
    }
}
