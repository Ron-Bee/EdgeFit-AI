package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM tests for the Sessions Predict gate + agent-child ordering (AUD-01). */
class SessionViewTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun manager(): SessionManager =
        SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }

    private fun agentChild(manager: SessionManager): SessionRecord {
        val agent = AgentManager(manager) { "2026-10-28T00:00:00Z" }
        val parent = agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64), baseConfig = BenchConfig.default())
        return agent.childrenOf(parent.sessionId).single()
    }

    @Test
    fun firstChildOffersPredictWhileCreated() {
        val child = agentChild(manager())
        assertEquals(SessionState.SESSION_CREATED, child.state)
        assertEquals("byok", child.mode)
        assertNull(child.prediction)
        assertTrue(SessionView.canPredict(child))
    }

    @Test
    fun pendingRetryStillOffersPredict() {
        val manager = manager()
        val child = agentChild(manager)
        manager.requestPrediction(child.sessionId)
        val pending = manager.get(child.sessionId)
        assertEquals(SessionState.PREDICTION_PENDING, pending.state)
        assertTrue(SessionView.canPredict(pending))
    }

    @Test
    fun recordedPredictionRefusesPredict() {
        val manager = manager()
        val child = agentChild(manager)
        manager.requestPrediction(child.sessionId)
        manager.recordPrediction(child.sessionId, 15.0, 20.0,
            """{"predicted_gen_tps":15.0}""",
            SessionManager.SOURCE_LIVE_META, emptyList())
        assertFalse(SessionView.canPredict(manager.get(child.sessionId)))
    }

    @Test
    fun authorizedSessionRefusesPredict() {
        val manager = manager()
        val child = agentChild(manager)
        manager.requestPrediction(child.sessionId)
        manager.recordPrediction(child.sessionId, 15.0, 20.0,
            """{"predicted_gen_tps":15.0}""",
            SessionManager.SOURCE_LIVE_META, emptyList())
        manager.authorizeBenchmark(child.sessionId, "test")
        val record = manager.get(child.sessionId)
        assertEquals(SessionState.BENCHMARK_AUTHORIZED, record.state)
        assertFalse(SessionView.canPredict(record))
    }

    @Test
    fun stalePredictionPayloadRefusesPredict() {
        val child = agentChild(manager())
        val stale = child.copy(prediction = mapOf("predicted_gen_tps" to 1.0))
        assertFalse(SessionView.canPredict(stale))
    }

    @Test
    fun agentParentRefusesPredict() {
        val manager = manager()
        val agent = AgentManager(manager) { "2026-10-28T00:00:00Z" }
        val parent = agent.createAgent(
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64), baseConfig = BenchConfig.default())
        assertEquals("agent", parent.mode)
        assertEquals(SessionState.SESSION_CREATED, parent.state)
        assertFalse(SessionView.canPredict(parent))
    }

    @Test
    fun offlineTestRefusesPredict() {
        val manager = manager()
        val created = manager.createSession(
            mode = "offline_test", deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        assertFalse(SessionView.canPredict(created))
    }

    @Test
    fun childPredictionOrderingAndAssociation() {
        val manager = manager()
        val child = agentChild(manager)
        assertEquals(1, child.experimentSeq)
        manager.requestPrediction(child.sessionId)
        assertEquals(SessionState.PREDICTION_PENDING,
            manager.get(child.sessionId).state)
        manager.recordPrediction(child.sessionId, 12.0, 45.0,
            """{"predicted_gen_tps":12.0}""",
            SessionManager.SOURCE_LIVE_META, emptyList())
        manager.authorizeBenchmark(child.sessionId, "live prediction recorded")
        val done = manager.get(child.sessionId)
        assertEquals(SessionState.BENCHMARK_AUTHORIZED, done.state)
        assertEquals(child.parentSessionId, done.parentSessionId)
        assertEquals(1, done.experimentSeq)
    }

    @Test
    fun authorizeWithoutPredictionRefused() {
        val manager = manager()
        val child = agentChild(manager)
        try {
            manager.authorizeBenchmark(child.sessionId, "must fail")
            fail("benchmark authorized without a durable prediction")
        } catch (e: SessionManager.SessionError) {
            assertEquals(SessionState.SESSION_CREATED,
                manager.get(child.sessionId).state)
        }
    }

    @Test
    fun untouchedChildStaysCreated() {
        // Cancel-path invariant: no confirm means no transition and
        // no manufactured prediction.
        val child = agentChild(manager())
        assertEquals(SessionState.SESSION_CREATED, child.state)
        assertNull(child.prediction)
        assertNull(child.benchmark)
    }
}
