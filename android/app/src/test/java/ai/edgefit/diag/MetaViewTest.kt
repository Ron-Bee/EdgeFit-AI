package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM tests for Meta dialog text builders (Android-free). */
class MetaViewTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun keyStatusLinesCoverAllStates() {
        assertEquals("Meta API key: none configured.",
            MetaView.keyStatusLine(false, null, false))
        val session = MetaView.keyStatusLine(true, "…AB12", false)
        assertTrue(session.contains("session-only"))
        assertTrue(session.contains("…AB12"))
        val saved = MetaView.keyStatusLine(false, null, true)
        assertTrue(saved.contains("securely saved"))
    }

    @Test
    fun authorizeTextDisclosesActionModelFieldsCost() {
        val text = MetaView.authorizeText(
            "Live throughput prediction",
            listOf("device profile summary", "model SHA-256"),
            "your session-only key")
        assertTrue(text.contains("Live throughput prediction"))
        assertTrue(text.contains(MetaApi.MODEL))
        assertTrue(text.contains(MetaApi.ENDPOINT))
        assertTrue(text.contains("device profile summary"))
        assertTrue(text.contains("model SHA-256"))
        assertTrue(text.contains("your session-only key"))
        assertTrue(text.contains("charges or quota"))
        assertTrue(text.contains("Cancel") || text.contains("Nothing else"))
    }

    @Test
    fun predictionTextRendersStoredRecord() {
        val manager = SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }
        val record = manager.createSession(
            mode = "byok", deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 1L,
            modelDisplay = "m", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = SessionBenchBridge.configMap(BenchConfig.default())
        )
        assertEquals("No prediction recorded.",
            MetaView.predictionText(manager.get(record.sessionId)))
        manager.requestPrediction(record.sessionId)
        val stored = manager.recordPrediction(
            record.sessionId, 15.0, 23.0,
            """{"confidence":"low","rationale":"r","uncertainty_notes":["u"],"limits":["l"]}""",
            SessionManager.SOURCE_LIVE_META, listOf("none"))
        val text = MetaView.predictionText(stored)
        assertTrue(text.contains("15.0 tok/s"))
        assertTrue(text.contains("live_meta"))
        assertTrue(text.contains("low"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun followUpTextRendersAssessmentAndProposal() {
        val manager = SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }
        val record = manager.createSession(
            mode = "byok", deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 1L,
            modelDisplay = "m", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = SessionBenchBridge.configMap(BenchConfig.default())
        )
        assertEquals("No follow-up recorded.",
            MetaView.followUpText(manager.get(record.sessionId)))
        // Text rendering only reads the followup section.
        val withFollowUp = record.copy(
            followup = mapOf(
                "status" to "recorded",
                "recommendation" to mapOf(
                    "direction" to "overestimated",
                    "summary" to "s",
                    "limitations" to listOf("l"),
                    "supports_conclusion" to false,
                    "next_experiment" to mapOf("description" to "d"),
                    "proposal_valid" to false,
                    "proposal_rejection" to "n_threads out of range"
                ),
                "provenance" to "live_meta test"
            )
        )
        val text = MetaView.followUpText(withFollowUp)
        assertTrue(text.contains("overestimated"))
        assertTrue(text.contains("proposal_valid: false"))
        assertTrue(text.contains("n_threads out of range"))
    }
}
