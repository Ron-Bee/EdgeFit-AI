package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** EF-MEM-03 failure recovery: evidence, gate, sessions, validation. */
class MemRecoveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
        const val RUN_FAILED = "BENCH-20261029T000000Z-aaaaaaaa"
        const val RUN_OTHER = "BENCH-20261029T000001Z-bbbbbbbb"
    }

    private lateinit var manager: SessionManager
    private lateinit var agent: AgentManager

    private fun setup() {
        manager = SessionManager(temp.newFolder()) { "2026-10-29T00:00:00Z" }
        agent = AgentManager(manager) { "2026-10-29T00:00:00Z" }
    }

    private fun createParent(): SessionRecord =
        agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            baseConfig = BenchConfig.default(), budgetMax = 5)

    private fun failedResult(runId: String): BenchResult {
        val measurements = BenchMeasurements(0L, 0L, 0, 0L, 0,
            0.0, 0.0, false,
            BenchMemory(0, 0, 0L, BenchRunner.MEM_METHOD),
            BenchThermal("none", "none", "none", true))
        return BenchResult(
            runId = runId,
            device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
                listOf("arm64-v8a"), 8, 12884901888L),
            model = BenchModelIdentity("mock.gguf", 100L, MODEL_SHA, "4",
                mapOf("general.architecture" to "llama")),
            runtimeVersion = "mock", runtimeAbi = "arm64-v8a",
            config = BenchConfig.default(),
            workload = BenchWorkload(BenchContract.WORKLOAD_ID,
                PROMPT_SHA, 100),
            measurements = measurements, status = "FAILED",
            error = "model load failed",
            appVersion = "mock",
            collectedAtUtc = "2026-10-29T01:00:00Z",
            xMemConditions = MemConditions.startOf(
                8L * 1024 * 1024 * 1024, 500L * 1024 * 1024,
                false, null))
    }

    /** Failed child shell with prediction + attached FAILED run. */
    private fun failedChild(parent: SessionRecord, runId: String,
        seq: Int = 1
    ): SessionRecord {
        val child = manager.createSession(
            mode = "byok", deviceModel = "SM-S908U",
            profileSha256 = null, modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            parentSessionId = parent.sessionId, experimentSeq = seq)
        manager.requestPrediction(child.sessionId)
        manager.recordPrediction(child.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), "2026-10-29T00:00:00Z")
        manager.authorizeBenchmark(child.sessionId)
        manager.noteRunStarted(child.sessionId)
        return SessionBenchBridge.attach(
            manager, child.sessionId, failedResult(runId))
    }

    // ---- failure evidence rendering ----

    @Test
    fun describeFailureNamesCauseAndMemory() {
        setup()
        val child = failedChild(createParent(), RUN_FAILED)
        val text = AgentEvidence.describeFailure(child)
        assertNotNull(text)
        assertTrue(text!!.contains(RUN_FAILED))
        assertTrue(text.contains("model load failed"))
        assertTrue(text.contains("mem_avail_pct="))
        assertTrue(text.contains("ctx=512"))
    }

    @Test
    fun describeFailureNeverInventsThroughput() {
        setup()
        val child = failedChild(createParent(), RUN_FAILED)
        val text = AgentEvidence.describeFailure(child)!!
        assertFalse(text.contains("tps"))
        assertFalse(text.contains("tok/s"))
    }

    @Test
    fun describeFailureNullWithoutBenchmark() {
        setup()
        val parent = createParent()
        val shell = manager.createSession(
            mode = "byok", deviceModel = "SM-S908U",
            profileSha256 = null, modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            parentSessionId = parent.sessionId, experimentSeq = 1)
        assertNull(AgentEvidence.describeFailure(shell))
    }

    @Test
    fun renderFailuresEmptyWithoutFailures() {
        setup()
        assertEquals("", AgentEvidence.renderFailures(emptyList()))
    }

    // ---- recovery gate ----

    @Test
    fun gateOpensRecoveryAfterFailedChild() {
        setup()
        val parent = createParent()
        failedChild(parent, RUN_FAILED)
        val gate = agent.proposalGateEx(parent.sessionId)
        assertEquals(AgentManager.ProposalMode.RECOVERY, gate.mode)
        assertTrue(gate.ok)
        assertTrue(gate.reason.contains(RUN_FAILED))
    }

    @Test
    fun gateStaysBlockedWithNoEvidenceAtAll() {
        setup()
        val parent = createParent()
        val gate = agent.proposalGateEx(parent.sessionId)
        assertEquals(AgentManager.ProposalMode.BLOCKED, gate.mode)
        assertFalse(gate.ok)
        assertTrue(gate.reason.contains("no comparable evidence yet"))
    }

    @Test
    fun recoveryTargetIsLatestFailure() {
        setup()
        val parent = createParent()
        failedChild(parent, RUN_FAILED, seq = 1)
        failedChild(parent, RUN_OTHER, seq = 2)
        val target = agent.recoveryTarget(parent.sessionId)
        assertNotNull(target)
        assertEquals(2, target!!.experimentSeq)
    }

    @Test
    fun budgetStillGatesRecovery() {
        setup()
        val parent = agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            baseConfig = BenchConfig.default(), budgetMax = 0)
        failedChild(parent, RUN_FAILED)
        val gate = agent.proposalGateEx(parent.sessionId)
        assertFalse(gate.ok)
        assertTrue(gate.reason.contains("budget"))
    }

    // ---- recovery validation ----

    private fun recoveryExpectation() = AgentApi.ProposalExpectation(
        modelSha = MODEL_SHA,
        workloadId = BenchContract.WORKLOAD_ID,
        currentConfig = BenchConfig.default(),
        nextSeq = 2,
        allowedRunIds = emptySet(),
        deviceMaxThreads = 8,
        recoveryRunIds = setOf(RUN_FAILED))

    private fun recoveryPayload(): MutableMap<String, Any?> = mutableMapOf(
        "model_sha" to MODEL_SHA,
        "workload_id" to BenchContract.WORKLOAD_ID,
        "experiment_seq" to 2,
        "current_config" to mapOf(
            "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
        "propose_experiment" to true,
        "proposed_config" to mapOf("n_ctx" to 256),
        "purpose" to AgentApi.PURPOSE_RECOVERY,
        "recovery_of_run_id" to RUN_FAILED,
        "hypothesis" to "smaller context may fit",
        "expected_direction" to "uncertain",
        "expected_gen_tps" to 12.0,
        "expected_prefill_tps" to 20.0,
        "uncertainty" to "allocation unknown",
        "supporting_evidence_ids" to listOf(RUN_FAILED),
        "limitations" to listOf("no success proof"),
        "benefit" to "may complete",
        "no_experiment_reason" to null)

    @Test
    fun validRecoveryPasses() {
        val valid = AgentApi.validateProposal(
            recoveryPayload(), "{}", recoveryExpectation())
        assertTrue(valid.proposeExperiment)
        assertEquals(AgentApi.PURPOSE_RECOVERY, valid.purpose)
        assertEquals(RUN_FAILED, valid.recoveryOfRunId)
        assertEquals(256, valid.proposedConfig!!.nCtx)
    }

    @Test
    fun recoveryIdenticalConfigIsExplicitRetry() {
        val payload = recoveryPayload()
        @Suppress("UNCHECKED_CAST")
        val deltas = (payload["proposed_config"]
            as Map<String, Any?>).toMutableMap()
        deltas["n_ctx"] = 512
        payload["proposed_config"] = deltas
        val valid = AgentApi.validateProposal(
            payload, "{}", recoveryExpectation())
        assertTrue(valid.proposeExperiment)
        assertTrue(valid.changedKeys.isEmpty())
    }

    @Test
    fun recoveryWithoutCitationRefused() {
        val payload = recoveryPayload()
        payload.remove("recovery_of_run_id")
        try {
            AgentApi.validateProposal(payload, "{}", recoveryExpectation())
            fail("expected refusal")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("recovery_of_run_id"))
        }
    }

    @Test
    fun recoveryCitingForeignRunRefused() {
        val payload = recoveryPayload()
        payload["recovery_of_run_id"] = RUN_OTHER
        payload["supporting_evidence_ids"] = listOf(RUN_OTHER)
        try {
            AgentApi.validateProposal(payload, "{}", recoveryExpectation())
            fail("expected refusal")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("ineligible"))
        }
    }

    @Test
    fun recoveryMustCiteFailedRunInEvidence() {
        val payload = recoveryPayload()
        // Eligible but unrelated evidence: passes the
        // allowlist, then must fail the citation check.
        payload["supporting_evidence_ids"] = listOf("BENCH-2")
        val expectation = recoveryExpectation().copy(
            allowedRunIds = setOf("BENCH-2"))
        try {
            AgentApi.validateProposal(payload, "{}", expectation)
            fail("expected refusal")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("must cite"))
        }
    }

    @Test
    fun recoveryPromptForbidsInventedThroughput() {
        val system = AgentApi.proposalSystem(8, recoveryMode = true)
        assertTrue(system.contains("recovery"))
        assertTrue(system.contains("NEVER invent"))
        assertTrue(system.contains("NEVER claim"))
        val user = AgentApi.proposalUser(
            "pack", BenchConfig.default(), 2, MODEL_SHA,
            BenchContract.WORKLOAD_ID, 1, 5, "FAILED_ATTEMPTS:\nX")
        assertTrue(user.contains("FAILED_ATTEMPTS"))
    }

    // ---- storeProposal recovery verification ----

    private fun storedRecovery(parentId: String,
        mutate: (MutableMap<String, Any?>) -> Unit = {}
    ): Map<String, Any?> {
        val payload = recoveryPayload()
        mutate(payload)
        val valid = AgentApi.validateProposal(
            payload, "{}", recoveryExpectation())
        agent.storeProposal(parentId, valid, "packhash", 2)
        @Suppress("UNCHECKED_CAST")
        val agentMap = agent.getParent(parentId).agent
            as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        return agentMap["pending_proposal"] as Map<String, Any?>
    }

    @Test
    fun storeRecoveryPersistsCitation() {
        setup()
        val parent = createParent()
        failedChild(parent, RUN_FAILED)
        val pending = storedRecovery(parent.sessionId)
        assertEquals(AgentApi.PURPOSE_RECOVERY, pending["purpose"])
        assertEquals(RUN_FAILED, pending["recovery_of_run_id"])
    }

    @Test
    fun storeRecoveryRejectsCompletedRun() {
        setup()
        val parent = createParent()
        // Completed child (measured) — recovery must not
        // cite it; nothing failed, so citation is foreign.
        val child = manager.createSession(
            mode = "byok", deviceModel = "SM-S908U",
            profileSha256 = null, modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            parentSessionId = parent.sessionId, experimentSeq = 1)
        manager.requestPrediction(child.sessionId)
        manager.recordPrediction(child.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), "2026-10-29T00:00:00Z")
        manager.authorizeBenchmark(child.sessionId)
        manager.noteRunStarted(child.sessionId)
        val ok = failedResult(RUN_FAILED).copy(
            status = BenchContract.STATUS_COMPLETED, error = null,
            measurements = failedResult(RUN_FAILED).measurements.copy(
                generatedTokens = 64, genMs = 4000L,
                tpsGen = 16.0, tpsPrefill = 23.0))
        SessionBenchBridge.attach(manager, child.sessionId, ok)
        val payload = recoveryPayload()
        val valid = AgentApi.validateProposal(
            payload, "{}", recoveryExpectation())
        try {
            agent.storeProposal(parent.sessionId, valid, "packhash", 2)
            fail("expected refusal")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("not a failed attempt"))
        }
    }

    // ---- createRecovery ----

    private fun topLevelFailed(): SessionRecord {
        val session = manager.createSession(
            mode = "byok", deviceModel = "SM-S908U",
            profileSha256 = null, modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        manager.requestPrediction(session.sessionId)
        manager.recordPrediction(session.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), "2026-10-29T00:00:00Z")
        manager.authorizeBenchmark(session.sessionId)
        manager.noteRunStarted(session.sessionId)
        return SessionBenchBridge.attach(
            manager, session.sessionId, failedResult(RUN_FAILED))
    }

    @Test
    fun createRecoveryLinksAndCarries() {
        setup()
        val source = topLevelFailed()
        val adjusted = BenchConfig.default().copy(nCtx = 256)
        val recovered = manager.createRecovery(
            source.sessionId, "SM-S908U", null,
            SessionBenchBridge.configMap(adjusted),
            15.0, 23.0, """{"mock":true}""",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(),
            carriedCreatedAtUtc = "2026-10-29T00:00:00Z")
        assertEquals(source.sessionId, recovered.recoveryOfSessionId)
        assertEquals(RUN_FAILED, recovered.recoveryOfRunId)
        assertEquals(SessionState.PREDICTION_RECORDED, recovered.state)
        @Suppress("UNCHECKED_CAST")
        val prediction = recovered.prediction as Map<String, Any?>
        assertEquals("2026-10-29T00:00:00Z",
            prediction["created_at_utc"])
        assertEquals(source.sessionId,
            prediction["carried_from_session"])
        // Source untouched: still failed, never reopened.
        val reloaded = manager.get(source.sessionId)
        assertEquals(SessionState.BENCHMARK_FAILED, reloaded.state)
    }

    @Test
    fun createRecoveryRefusesCompletedSource() {
        setup()
        val source = topLevelFailed()
        // Forge a completed status via a second attach is
        // illegal (terminal); instead assert the guard by
        // completing a sibling through runChild-like flow.
        // Here the source IS failed, so first prove the
        // completed-source path with a completed record.
        val completed = manager.createSession(
            mode = "byok", deviceModel = "SM-S908U",
            profileSha256 = null, modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        manager.requestPrediction(completed.sessionId)
        manager.recordPrediction(completed.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), "2026-10-29T00:00:00Z")
        manager.authorizeBenchmark(completed.sessionId)
        manager.noteRunStarted(completed.sessionId)
        val ok = failedResult(RUN_OTHER).copy(
            status = BenchContract.STATUS_COMPLETED, error = null,
            measurements = failedResult(RUN_OTHER).measurements.copy(
                generatedTokens = 64, genMs = 4000L,
                tpsGen = 16.0, tpsPrefill = 23.0))
        SessionBenchBridge.attach(manager, completed.sessionId, ok)
        try {
            manager.createRecovery(
                completed.sessionId, "SM-S908U", null,
                SessionBenchBridge.configMap(BenchConfig.default()),
                15.0, 23.0, "{}", SessionManager.SOURCE_OFFLINE_TEST,
                emptyList())
            fail("expected refusal")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("uncompleted"))
        }
        assertEquals(SessionState.BENCHMARK_FAILED,
            manager.get(source.sessionId).state)
    }

    @Test
    fun createRecoveryRefusesBadConfig() {
        setup()
        val source = topLevelFailed()
        try {
            manager.createRecovery(
                source.sessionId, "SM-S908U", null,
                mapOf("n_ctx" to 8),
                15.0, 23.0, "{}", SessionManager.SOURCE_OFFLINE_TEST,
                emptyList())
            fail("expected refusal")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("config"))
        }
    }

    @Test
    fun configDeltaNoteReadsHonestly() {
        setup()
        val base = BenchConfig.default()
        assertEquals("same config retry",
            manager.configDeltaNote(base, base.copy()))
        val note = manager.configDeltaNote(
            base, base.copy(nCtx = 256, nThreads = 2))
        assertTrue(note.contains("ctx 512→256"))
        assertTrue(note.contains("threads 4→2"))
    }

    // ---- session UI eligibility ----

    @Test
    fun canRecoverFailedPredictedSession() {
        setup()
        assertTrue(SessionView.canRecover(topLevelFailed()))
    }

    @Test
    fun canRecoverRejectsCompletedAndParents() {
        setup()
        val parent = createParent()
        assertFalse(SessionView.canRecover(parent))
        val completed = manager.createSession(
            mode = "byok", deviceModel = "SM-S908U",
            profileSha256 = null, modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        // No benchmark at all: nothing to recover.
        assertFalse(SessionView.canRecover(completed))
    }

    // ---- bridge memory keys ----

    @Test
    fun bridgeCarriesMemKeys() {
        val map = SessionBenchBridge.measurementsMap(
            failedResult(RUN_FAILED).measurements, "mock",
            "arm64-v8a", null,
            failedResult(RUN_FAILED).xMemConditions)
        assertEquals(500L * 1024 * 1024,
            map["mem_avail_bytes_start"])
        assertTrue((map["mem_avail_pct_start"] as Double) > 6.0)
        assertEquals(true, map["mem_below_advisory"])
    }

    @Test
    fun bridgeOmitsMemKeysWithoutConditions() {
        val map = SessionBenchBridge.measurementsMap(
            failedResult(RUN_FAILED).measurements, "mock",
            "arm64-v8a", null, null)
        assertNull(map["mem_avail_pct_start"])
    }
}
