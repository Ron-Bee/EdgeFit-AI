package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for benchmark-attach time order (FIX2): same-second
 * prediction/collection must attach; strictly earlier collection
 * must not. All other attach invariants stay enforced.
 */
class SessionAttachTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var stamp = "2026-10-28T21:12:52Z"

    private fun manager(): SessionManager =
        SessionManager(temp.newFolder()) { stamp }

    private fun measurements(): Map<String, Any?> = linkedMapOf(
        "tps_gen" to 16.07232546459066,
        "tps_prefill" to 20.795660036166364
    )

    private fun running(manager: SessionManager): SessionRecord {
        val created = manager.createSession(
            mode = "byok", deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        manager.requestPrediction(created.sessionId)
        manager.recordPrediction(created.sessionId, 11.0, 55.0,
            """{"predicted_gen_tps":11.0}""",
            SessionManager.SOURCE_LIVE_META, emptyList())
        manager.authorizeBenchmark(created.sessionId, "test")
        manager.noteRunStarted(created.sessionId)
        return manager.get(created.sessionId)
    }

    private fun attach(
        manager: SessionManager,
        id: String,
        collectedAt: String,
        runId: String = "BENCH-20261028T211252Z-aaaa1111"
    ): SessionRecord = manager.attachBenchmark(
        id = id,
        runId = runId,
        sourceSha256 = "ab".repeat(32),
        status = BenchContract.STATUS_COMPLETED,
        measurements = measurements(),
        collectedAtUtc = collectedAt,
        modelSha256 = "0".repeat(64),
        workloadId = BenchContract.WORKLOAD_ID,
        config = SessionBenchBridge.configMap(BenchConfig.default()),
        deviceModel = null
    )

    @Test
    fun sameSecondAttachAccepted() {
        val manager = manager()
        val session = running(manager)
        val done = attach(manager, session.sessionId, stamp)
        assertEquals(SessionState.COMPARISON_READY, done.state)
    }

    @Test
    fun earlierCollectedRejected() {
        val manager = manager()
        val session = running(manager)
        try {
            attach(manager, session.sessionId, "2026-10-28T21:12:51Z")
            fail("strictly earlier measurement attached")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("predates"))
            assertEquals(SessionState.BENCHMARK_RUNNING,
                manager.get(session.sessionId).state)
        }
    }

    @Test
    fun laterCollectedAccepted() {
        val manager = manager()
        val session = running(manager)
        val done = attach(manager, session.sessionId, "2026-10-28T21:13:08Z")
        assertEquals(SessionState.COMPARISON_READY, done.state)
    }

    @Test
    fun attachBeforePredictionRejected() {
        val manager = manager()
        val created = manager.createSession(
            mode = "byok", deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        try {
            attach(manager, created.sessionId, stamp)
            fail("attach without prediction accepted")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("illegal state"))
        }
    }

    @Test
    fun attachBeforeRunStartRejected() {
        val manager = manager()
        val created = manager.createSession(
            mode = "byok", deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        manager.requestPrediction(created.sessionId)
        manager.recordPrediction(created.sessionId, 11.0, 55.0,
            """{"predicted_gen_tps":11.0}""",
            SessionManager.SOURCE_LIVE_META, emptyList())
        manager.authorizeBenchmark(created.sessionId, "test")
        try {
            attach(manager, created.sessionId, stamp)
            fail("attach before run start accepted")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("illegal state"))
        }
    }

    @Test
    fun wrongModelRejected() {
        val manager = manager()
        val session = running(manager)
        try {
            manager.attachBenchmark(
                id = session.sessionId,
                runId = "BENCH-20261028T211252Z-bbbb2222",
                sourceSha256 = "ab".repeat(32),
                status = BenchContract.STATUS_COMPLETED,
                measurements = measurements(),
                collectedAtUtc = stamp,
                modelSha256 = "f".repeat(64),
                workloadId = BenchContract.WORKLOAD_ID,
                config = SessionBenchBridge.configMap(BenchConfig.default()),
                deviceModel = null
            )
            fail("wrong model attached")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("model mismatch"))
        }
    }

    @Test
    fun wrongWorkloadRejected() {
        val manager = manager()
        val session = running(manager)
        try {
            manager.attachBenchmark(
                id = session.sessionId,
                runId = "BENCH-20261028T211252Z-cccc3333",
                sourceSha256 = "ab".repeat(32),
                status = BenchContract.STATUS_COMPLETED,
                measurements = measurements(),
                collectedAtUtc = stamp,
                modelSha256 = "0".repeat(64),
                workloadId = "other-workload",
                config = SessionBenchBridge.configMap(BenchConfig.default()),
                deviceModel = null
            )
            fail("wrong workload attached")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("workload mismatch"))
        }
    }

    @Test
    fun wrongConfigRejected() {
        val manager = manager()
        val session = running(manager)
        val other = SessionBenchBridge.configMap(
            BenchConfig.default().copy(nThreads = 8))
        try {
            manager.attachBenchmark(
                id = session.sessionId,
                runId = "BENCH-20261028T211252Z-dddd4444",
                sourceSha256 = "ab".repeat(32),
                status = BenchContract.STATUS_COMPLETED,
                measurements = measurements(),
                collectedAtUtc = stamp,
                modelSha256 = "0".repeat(64),
                workloadId = BenchContract.WORKLOAD_ID,
                config = other,
                deviceModel = null
            )
            fail("wrong config attached")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("config mismatch"))
        }
    }

    @Test
    fun duplicateRunAcrossSessionsRejected() {
        val manager = manager()
        val first = running(manager)
        val second = running(manager)
        attach(manager, first.sessionId, stamp,
            "BENCH-20261028T211252Z-eeee5555")
        try {
            attach(manager, second.sessionId, stamp,
                "BENCH-20261028T211252Z-eeee5555")
            fail("duplicate run id attached twice")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("already attached"))
        }
    }

    @Test
    fun sameSessionReattachRejected() {
        val manager = manager()
        // First attach advances past RUNNING, so the second delivery
        // fails closed instead of replacing the measurement.
        val session = running(manager)
        attach(manager, session.sessionId, stamp,
            "BENCH-20261028T211252Z-ffff6666")
        try {
            attach(manager, session.sessionId, stamp,
                "BENCH-20261028T211252Z-ffff6666")
            fail("second attach replaced the first")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("illegal state"))
        }
    }

    @Test
    fun failedRunStaysFailed() {
        val manager = manager()
        val session = running(manager)
        val done = manager.attachBenchmark(
            id = session.sessionId,
            runId = "BENCH-20261028T211252Z-11117777",
            sourceSha256 = "ab".repeat(32),
            status = BenchContract.STATUS_FAILED,
            measurements = measurements(),
            collectedAtUtc = stamp,
            modelSha256 = "0".repeat(64),
            workloadId = BenchContract.WORKLOAD_ID,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            deviceModel = null
        )
        assertEquals(SessionState.BENCHMARK_FAILED, done.state)
        assertNull(done.comparison)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun agentChildAttachPreservesAttribution() {
        val manager = manager()
        val agent = AgentManager(manager) { stamp }
        val parent = agent.createAgent(
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64), baseConfig = BenchConfig.default())
        val child = agent.childrenOf(parent.sessionId).single()
        manager.requestPrediction(child.sessionId)
        manager.recordPrediction(child.sessionId, 11.0, 55.0,
            """{"predicted_gen_tps":11.0}""",
            SessionManager.SOURCE_LIVE_META, emptyList())
        manager.authorizeBenchmark(child.sessionId, "test")
        manager.noteRunStarted(child.sessionId)
        val done = attach(manager, child.sessionId, stamp)
        assertEquals(SessionState.COMPARISON_READY, done.state)
        assertEquals(parent.sessionId, done.parentSessionId)
        assertEquals(1, done.experimentSeq)
        assertEquals(0, done.budgetUsed)
        assertNull(agent.afterExperiment(parent.sessionId))
        val trailed = agent.getParent(parent.sessionId)
        assertEquals(1, trailed.budgetUsed)
        val experiments = trailed.agent as? Map<String, Any?>
        val items = experiments?.get("experiments") as? List<*>
        assertEquals(1, items?.size)
    }
}
