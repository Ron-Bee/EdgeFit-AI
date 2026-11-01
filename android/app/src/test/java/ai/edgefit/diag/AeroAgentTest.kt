package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF-UX-AERO-01 Step 5 agent-workspace derivations:
 * role copy, honest summaries, the five card kinds,
 * budgets, abstention, and bounded inline rendering.
 */
class AeroAgentTest {

    private fun parent(
        id: String = "SES-parent",
        updated: String = "2026-10-28T01:00:00Z",
        agent: Map<String, Any?>? = mapOf(
            "status" to AgentManager.STATUS_ACTIVE),
        used: Int = 1,
        max: Int = 5
    ): SessionRecord =
        SessionRecord(
            sessionId = id, mode = "agent",
            state = SessionState.SESSION_CREATED,
            createdAtUtc = updated, updatedAtUtc = updated,
            modelSha256 = "0".repeat(64), modelSizeBytes = 1,
            modelDisplay = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            workloadId = "edgefit-prompt-v1",
            promptSha256 = "1".repeat(64),
            config = mapOf("n_ctx" to 512, "n_threads" to 4,
                "n_predict" to 64),
            budgetUsed = used, budgetMax = max, agent = agent
        )

    private fun child(
        id: String = "SES-child",
        seq: Int = 1,
        parentId: String = "SES-parent",
        prediction: Map<String, Any?>? = null,
        benchmark: Map<String, Any?>? = null,
        followup: Map<String, Any?>? = null
    ): SessionRecord =
        SessionRecord(
            sessionId = id, mode = "agent",
            state = SessionState.SESSION_CREATED,
            createdAtUtc = "2026-10-28T01:00:00Z",
            updatedAtUtc = "2026-10-28T01:00:00Z",
            modelSha256 = "0".repeat(64), modelSizeBytes = 1,
            modelDisplay = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            workloadId = "edgefit-prompt-v1",
            promptSha256 = "1".repeat(64),
            config = mapOf("n_ctx" to 512, "n_threads" to 4,
                "n_predict" to 64),
            prediction = prediction, benchmark = benchmark,
            followup = followup, parentSessionId = parentId,
            experimentSeq = seq
        )

    private fun prediction(gen: Double = 15.0): Map<String, Any?> =
        mapOf(
            "predicted_gen_tps" to gen,
            "predicted_prefill_tps" to 25.0,
            "source_type" to "byok_meta",
            "created_at_utc" to "2026-10-28T00:59:00Z")

    private fun benchmark(
        status: String = BenchContract.STATUS_COMPLETED,
        gen: Double = 17.738
    ): Map<String, Any?> = mapOf(
        "run_id" to "BENCH-20261028T010000Z-aaaaaaaa",
        "status" to status,
        "collected_at_utc" to "2026-10-28T01:00:00Z",
        "measurements" to mapOf(
            "tps_gen" to gen,
            "tps_prefill" to 30.99,
            "thermal_after" to "none"))

    @Test
    fun roleLinesStateApprovalContract() {
        val lines = AeroAgent.roleLines()
        assertTrue(lines.size >= 3)
        assertTrue(lines.all { it.isNotBlank() })
        val joined = lines.joinToString(" ")
        assertTrue(joined.contains("approve"))
        assertTrue(joined.contains("budget"))
        assertFalse(joined.contains("tok/s"))
    }

    @Test
    fun emptyStateInventsNothing() {
        val joined = AeroAgent.emptyLines().joinToString(" ")
        assertTrue(joined.contains("No optimization sessions yet."))
        assertFalse(joined.contains("tok/s"))
        assertTrue(!joined.any { it.isDigit() })
    }

    @Test
    fun summarizeEmptyHasNoActiveSession() {
        val summary = AeroAgent.summarize(emptyList())
        assertEquals(0, summary.agentCount)
        assertEquals(0, summary.pendingCount)
        assertNull(summary.activeId)
        assertNull(summary.budgetLine)
        assertEquals("No optimization sessions yet.",
            summary.headline)
    }

    @Test
    fun summarizeCountsPendingAndPicksNewestActive() {
        val pending = mapOf(
            "status" to AgentManager.STATUS_ACTIVE,
            "pending_proposal" to mapOf("experiment_seq" to 2))
        val parents = listOf(
            parent(id = "SES-old",
                updated = "2026-10-27T01:00:00Z"),
            parent(id = "SES-new",
                updated = "2026-10-28T01:00:00Z",
                agent = pending, used = 2))
        val summary = AeroAgent.summarize(parents)
        assertEquals(2, summary.agentCount)
        assertEquals(1, summary.pendingCount)
        assertEquals("SES-new", summary.activeId)
        assertEquals("2 optimization sessions · " +
            "1 proposal awaiting review", summary.headline)
        assertEquals("Active SES-new: used 2 of 5 attempts",
            summary.budgetLine)
    }

    @Test
    fun finishedStatusNamesTerminalReason() {
        val done = parent(agent = mapOf(
            "status" to AgentManager.STATUS_FINISHED,
            "terminal_reason" to AgentManager.END_BUDGET_EXHAUSTED),
            used = 5)
        assertEquals("finished (budget_exhausted) · " +
            "attempts 5 of 5", AeroAgent.statusLine(done))
        assertFalse(AeroAgent.isActive(done))
    }

    @Test
    fun sessionLinesNamePendingReview() {
        val pending = parent(agent = mapOf(
            "status" to AgentManager.STATUS_ACTIVE,
            "pending_proposal" to mapOf("experiment_seq" to 2)))
        val lines = AeroAgent.sessionLines(pending, 1)
        assertTrue(lines.any { it.contains("attempts 1 of 5") })
        assertTrue(lines.any { it.contains("awaiting review") })
    }

    @Test
    fun proposalCardAbsentWithoutPending() {
        assertNull(AeroAgent.proposalCard(parent()))
    }

    @Test
    fun proposalCardCarriesExpectation() {
        val pending = parent(agent = mapOf(
            "status" to AgentManager.STATUS_ACTIVE,
            "pending_proposal" to mapOf(
                "experiment_seq" to 2,
                "changed_keys" to listOf("n_threads"),
                "proposed_config" to mapOf("n_ctx" to 512,
                    "n_threads" to 8, "n_predict" to 64),
                "hypothesis" to "more threads help",
                "expected_gen_tps" to 19.5,
                "expected_direction" to "higher")))
        val card = AeroAgent.proposalCard(pending)!!
        assertEquals(AeroAgent.CardKind.RECOMMENDATION, card.kind)
        assertEquals("Recommendation", AeroAgent.kindLabel(card.kind))
        val joined = card.lines.joinToString("\n")
        assertTrue(joined.contains("19.5 tok/s"))
        assertTrue(joined.contains("threads=8"))
        assertFalse(joined.contains("Modified by developer"))
    }

    @Test
    fun proposalCardFlagsDeveloperModification() {
        val pending = parent(agent = mapOf(
            "status" to AgentManager.STATUS_ACTIVE,
            "pending_proposal" to mapOf(
                "experiment_seq" to 2,
                "modified_by" to "developer",
                "changed_keys" to listOf("n_ctx"),
                "proposed_config" to mapOf("n_ctx" to 1024,
                    "n_threads" to 4, "n_predict" to 64),
                "hypothesis" to "h",
                "expected_gen_tps" to 10.0,
                "expected_direction" to "higher")))
        val card = AeroAgent.proposalCard(pending)!!
        assertTrue(card.lines.joinToString("\n")
            .contains("Modified by developer"))
    }

    @Test
    fun measuredCardNeedsCompletedRun() {
        val card = AeroAgent.experimentCard(
            child(prediction = prediction(), benchmark = benchmark()))
        assertEquals(AeroAgent.CardKind.MEASURED, card.kind)
        assertEquals("Measured", AeroAgent.kindLabel(card.kind))
        val joined = card.lines.joinToString("\n")
        assertTrue(joined.contains("17.74 tok/s"))
        assertTrue(joined.contains("expected 15.0 tok/s"))
    }

    @Test
    fun firstExperimentPredictionReadsPrediction() {
        val card = AeroAgent.experimentCard(
            child(seq = 1, prediction = prediction()))
        assertEquals(AeroAgent.CardKind.PREDICTION, card.kind)
        assertTrue(card.lines.joinToString("\n")
            .contains("awaiting measurement"))
    }

    @Test
    fun laterApprovedChildReadsApprovedExperiment() {
        val card = AeroAgent.experimentCard(
            child(seq = 3, prediction = prediction()))
        assertEquals(AeroAgent.CardKind.APPROVED_EXPERIMENT,
            card.kind)
        assertEquals("Approved experiment",
            AeroAgent.kindLabel(card.kind))
    }

    @Test
    fun failedRunKeepsPredictionWithoutMeasurement() {
        val card = AeroAgent.experimentCard(child(seq = 2,
            prediction = prediction(),
            benchmark = benchmark(
                status = BenchContract.STATUS_FAILED, gen = 0.0)))
        assertEquals(AeroAgent.CardKind.PREDICTION, card.kind)
        val joined = card.lines.joinToString("\n")
        assertTrue(joined.contains("no measurement"))
        assertTrue(joined.contains("FAILED"))
        assertFalse(joined.contains("0.0 tok/s"))
    }

    @Test
    fun bareShellReadsUnavailable() {
        val card = AeroAgent.experimentCard(child())
        assertEquals(AeroAgent.CardKind.UNAVAILABLE, card.kind)
        assertEquals("Unavailable", AeroAgent.kindLabel(card.kind))
        assertFalse(card.lines.joinToString("\n").contains("tok/s"))
    }

    @Test
    fun abstentionNeedsFinishedWithoutCompleted() {
        val done = parent(agent = mapOf(
            "status" to AgentManager.STATUS_FINISHED,
            "terminal_reason" to AgentManager.END_NO_FURTHER_TESTING))
        assertTrue(AeroAgent.abstentionLine(done, emptyList())!!
            .contains("no conclusion drawn"))
        assertNull(AeroAgent.abstentionLine(parent(), emptyList()))
        assertNull(AeroAgent.abstentionLine(done,
            listOf(child(benchmark = benchmark()))))
    }

    @Test
    fun historyLineShowsLastEntry() {
        assertNull(AeroAgent.lastHistoryLine(parent()))
        val withHistory = parent().copy(history = listOf(
            mapOf("state" to "SESSION_CREATED",
                "at" to "2026-10-28T01:00:00Z",
                "cause" to "agent created"),
            mapOf("state" to "BENCHMARK_AUTHORIZED",
                "at" to "2026-10-28T02:00:00Z",
                "cause" to "developer approved")))
        assertEquals("history: BENCHMARK_AUTHORIZED — " +
            "developer approved",
            AeroAgent.lastHistoryLine(withHistory))
        assertTrue(AeroAgent.sessionLines(withHistory, 0)
            .any { it.contains("developer approved") })
    }

    @Test
    fun gateLineMirrorsDialog() {
        assertNull(AeroAgent.gateLine(true, "ok"))
        assertEquals("proposal mode: recovery",
            AeroAgent.gateLine(true, "recovery"))
        assertEquals("proposal gate: experiment 1 shell " +
            "has no prediction yet",
            AeroAgent.gateLine(false,
                "experiment 1 shell has no prediction yet"))
    }

    @Test
    fun showMoreMath() {
        assertNull(AeroAgent.showMoreLine(2, 4))
        assertNull(AeroAgent.showMoreLine(4, 4))
        assertEquals("Showing 4 of 6 — open the workspace for all.",
            AeroAgent.showMoreLine(6, 4))
    }

    @Test
    fun recentCardsAreNewestFirstAndBounded() {
        val children = listOf(
            child(id = "SES-c1", seq = 1,
                prediction = prediction()),
            child(id = "SES-c2", seq = 2,
                prediction = prediction(),
                benchmark = benchmark()),
            child(id = "SES-c3", seq = 3))
        val cards = AeroAgent.recentCards(children, max = 2)
        assertEquals(2, cards.size)
        assertTrue(cards[0].title.contains("Experiment 3"))
        assertTrue(cards[1].title.contains("Experiment 2"))
        assertEquals(AeroAgent.CardKind.MEASURED, cards[1].kind)
    }
}
