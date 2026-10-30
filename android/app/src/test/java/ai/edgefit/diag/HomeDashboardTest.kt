package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDashboardTest {

    private fun record(
        id: String,
        updated: String,
        status: String?
    ): SessionRecord = SessionRecord(
        sessionId = id, mode = "byok",
        state = SessionState.BENCHMARK_COMPLETED,
        createdAtUtc = updated, updatedAtUtc = updated,
        deviceModel = null, profileSha256 = null,
        modelSha256 = "0".repeat(64), modelSizeBytes = 1,
        modelDisplay = "m", workloadId = "w",
        promptSha256 = "1".repeat(64),
        config = emptyMap(), prediction = null,
        benchmark = status?.let {
            mapOf(
                "run_id" to "BENCH-$id",
                "status" to it,
                "measurements" to mapOf(
                    "tps_gen" to 14.5, "tps_prefill" to 30.0),
                "collected_at_utc" to updated
            )
        },
        comparison = null, followup = null,
        parentSessionId = null, experimentSeq = null,
        budgetUsed = 0, budgetMax = 5, agent = null,
        history = emptyList(), recoveryNotes = emptyList()
    )

    @Test
    fun lastMeasuredPicksLatestCompleted() {
        val runs = HomeDashboard.lastMeasured(listOf(
            record("SES-aaaaaaaaaaaaaaaa", "2026-01-01T00:00:00Z",
                BenchContract.STATUS_COMPLETED),
            record("SES-bbbbbbbbbbbbbbbb", "2026-01-02T00:00:00Z",
                BenchContract.STATUS_COMPLETED)
        ))!!
        assertEquals("SES-bbbbbbbbbbbbbbbb", runs.sessionId)
        assertEquals(14.5, runs.genTps!!, 0.0)
    }

    @Test
    fun lastMeasuredIgnoresFailed() {
        assertNull(HomeDashboard.lastMeasured(listOf(
            record("SES-aaaaaaaaaaaaaaaa", "2026-01-01T00:00:00Z",
                "FAILED")
        )))
    }

    @Test
    fun lastMeasuredIgnoresUnmeasured() {
        assertNull(HomeDashboard.lastMeasured(listOf(
            record("SES-aaaaaaaaaaaaaaaa", "2026-01-01T00:00:00Z", null)
        )))
    }

    @Test
    fun actionTabRoutes() {
        assertEquals(NavTab.BENCHMARK,
            HomeDashboard.actionTab(NextAction.SELECT_MODEL))
        assertEquals(NavTab.BENCHMARK,
            HomeDashboard.actionTab(NextAction.RUN_BENCHMARK))
        assertEquals(NavTab.AGENT,
            HomeDashboard.actionTab(NextAction.REVIEW_PROPOSAL))
        assertEquals(NavTab.SESSIONS,
            HomeDashboard.actionTab(NextAction.REQUEST_FOLLOW_UP))
        assertEquals(NavTab.SESSIONS,
            HomeDashboard.actionTab(NextAction.VIEW_RESULTS))
    }

    @Test
    fun modelCardEmpty() {
        val card = HomeDashboard.modelCard(null, null, 0L)
        assertEquals(false, card.staged)
    }

    @Test
    fun modelCardStaged() {
        val card = HomeDashboard.modelCard(
            "Llama-3.2-1B.gguf", "ab".repeat(32), 800L * 1024 * 1024)
        assertEquals(true, card.staged)
        assertEquals(2, card.lines.size)
    }
}
