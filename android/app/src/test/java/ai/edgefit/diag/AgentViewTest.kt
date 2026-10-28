package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM tests for agent dialog text builders (Android-free). */
class AgentViewTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun parent(finished: Boolean = false): SessionRecord {
        val manager = SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }
        val agent = AgentManager(manager) { "2026-10-28T00:00:00Z" }
        val created = agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64), baseConfig = BenchConfig.default())
        if (finished) agent.finishAgent(created.sessionId)
        return agent.getParent(created.sessionId)
    }

    @Test
    fun parentLineShowsStatusAndBudget() {
        val line = AgentView.parentLine(parent())
        assertTrue(line.contains("active"))
        assertTrue(line.contains("0/5"))
        val done = AgentView.parentLine(parent(finished = true))
        assertTrue(done.contains("finished (user_finish)"))
    }

    @Test
    fun statusTextCoversState() {
        val manager = SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }
        val agent = AgentManager(manager) { "2026-10-28T00:00:00Z" }
        val created = agent.createAgent(
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64), baseConfig = BenchConfig.default())
        val detail = AgentView.AgentDetail(
            parent = agent.getParent(created.sessionId),
            children = agent.childrenOf(created.sessionId),
            pack = agent.buildPack(created.sessionId),
            gateOk = false,
            gateReason = "no comparable evidence yet"
        )
        val text = AgentView.statusText(detail)
        assertTrue(text.contains("active"))
        assertTrue(text.contains("used 0 of 5"))
        assertTrue(text.contains("mock.gguf"))
        assertTrue(text.contains("no comparable evidence yet"))
    }

    @Test
    fun experimentLinesDistinguishMeasuredAndPending() {
        val manager = SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }
        val agent = AgentManager(manager) { "2026-10-28T00:00:00Z" }
        val created = agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64), baseConfig = BenchConfig.default())
        val children = agent.childrenOf(created.sessionId)
        val pack = agent.buildPack(created.sessionId)
        val lines = AgentView.experimentLines(AgentView.AgentDetail(
            parent = created, children = children, pack = pack,
            gateOk = false, gateReason = "x"))
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("exp 1"))
        assertTrue(lines[0].contains("no measurement yet"))
        assertTrue(lines[0].contains("prediction pending"))
    }

    @Test
    fun proposalTextRendersValidatedFields() {
        val text = AgentView.proposalText(linkedMapOf(
            "experiment_seq" to 2,
            "proposed_config" to mapOf(
                "n_ctx" to 512, "n_threads" to 8, "n_predict" to 64),
            "changed_keys" to listOf("n_threads"),
            "hypothesis" to "h",
            "expected_gen_tps" to 16.0,
            "expected_direction" to "faster",
            "uncertainty" to "u",
            "supporting_evidence_ids" to listOf("BENCH-1"),
            "benefit" to "b",
            "limitations" to listOf("l"),
            "host_notes" to listOf("HOST: n_predict 64→128 distinct cohort"),
            "proposal_hash" to "ab".repeat(32),
            "evidence_pack_hash" to "cd".repeat(32)
        ))
        assertTrue(text.contains("experiment 2"))
        assertTrue(text.contains("threads=8"))
        assertTrue(text.contains("16.0 tok/s"))
        assertTrue(text.contains("BENCH-1"))
        assertTrue(text.contains("host_notes: HOST: n_predict 64→128"))
    }

    @Test
    fun summaryTextRendersTerminalCounts() {
        val text = AgentView.summaryText(parent(finished = true))
        assertTrue(text.contains("user_finish"))
        assertTrue(text.contains("attempts: 0/5"))
    }

    @Test
    fun resolveNoticeKeepsActionMessageAfterCleanRefresh() {
        assertEquals("Agent SES-1 created with experiment 1 shell.",
            AgentView.resolveNotice("",
                "Agent SES-1 created with experiment 1 shell."))
    }

    @Test
    fun resolveNoticeKeepsActionErrorAfterCleanRefresh() {
        assertEquals("Stage a model file first.",
            AgentView.resolveNotice("", "Stage a model file first."))
        assertEquals("Reject failed: no proposal pending",
            AgentView.resolveNotice("", "Reject failed: no proposal pending"))
    }

    @Test
    fun resolveNoticeRefreshErrorWins() {
        assertEquals("Detail failed: missing session",
            AgentView.resolveNotice("Detail failed: missing session",
                "Agent SES-1 created."))
        assertEquals("Agent list failed: store unreadable",
            AgentView.resolveNotice("Agent list failed: store unreadable",
                "Proposal preserved as rejected."))
    }
}
