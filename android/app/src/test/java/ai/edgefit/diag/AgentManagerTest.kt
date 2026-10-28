package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM tests for agent lifecycle, budget, approval, and termination. */
class AgentManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
    }

    private lateinit var manager: SessionManager
    private lateinit var agent: AgentManager

    private fun setup() {
        manager = SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }
        agent = AgentManager(manager) { "2026-10-28T00:00:00Z" }
    }

    private fun createParent(budgetMax: Int = 5): SessionRecord =
        agent.createAgent(
            deviceModel = "SM-S908U",
            profileSha256 = null,
            modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            baseConfig = BenchConfig.default(),
            budgetMax = budgetMax
        )

    private fun result(
        runId: String,
        genMs: Long,
        tokens: Int = 64,
        status: String = BenchContract.STATUS_COMPLETED,
        config: BenchConfigData = BenchConfig.default()
    ): BenchResult = BenchResult(
        runId = runId,
        device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
            listOf("arm64-v8a"), 8, 12884901888L),
        model = BenchModelIdentity("mock.gguf", 100L, MODEL_SHA, "4",
            mapOf("general.architecture" to "llama")),
        runtimeVersion = "mock",
        runtimeAbi = "arm64-v8a",
        config = config,
        workload = BenchWorkload(BenchContract.WORKLOAD_ID, PROMPT_SHA, 100),
        measurements = BenchMeasurements(1200L, 1000L, 23, genMs, tokens,
            BenchConfig.tps(tokens, genMs), 23.0, false,
            BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
            BenchThermal("none", "none", "none", true)),
        status = status,
        error = if (status == BenchContract.STATUS_COMPLETED) null else "mock",
        appVersion = "mock",
        collectedAtUtc = "2026-10-28T01:00:00Z"
    )

    /** Drives a child shell through prediction/authorize/attach. */
    private fun runChild(childId: String, bench: BenchResult): SessionRecord {
        manager.requestPrediction(childId)
        manager.recordPrediction(childId, 15.0, 23.0, """{"mock":true}""",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(),
            "2026-10-28T00:00:00Z")
        manager.authorizeBenchmark(childId)
        manager.noteRunStarted(childId)
        return SessionBenchBridge.attach(manager, childId, bench)
    }

    /** Builds a child shell with the given config (unpredicted). */
    private fun childShell(
        parent: SessionRecord,
        seq: Int,
        config: BenchConfigData = BenchConfig.default()
    ): String = manager.createSession(
        mode = "byok",
        deviceModel = "SM-S908U",
        profileSha256 = null,
        modelSha256 = MODEL_SHA,
        modelSizeBytes = 100L,
        modelDisplay = "mock.gguf",
        workloadId = parent.workloadId,
        promptSha256 = PROMPT_SHA,
        config = SessionBenchBridge.configMap(config),
        parentSessionId = parent.sessionId,
        experimentSeq = seq
    ).sessionId

    private fun proposal(
        config: BenchConfigData = BenchConfig.default().copy(nThreads = 8),
        seq: Int = 2
    ): AgentApi.ValidProposal {
        val current = BenchConfig.default()
        return AgentApi.ValidProposal(
            proposeExperiment = true,
            proposedConfig = config,
            changedKeys = listOf("n_threads"),
            hypothesis = "mock hypothesis",
            expectedDirection = "faster",
            expectedGenTps = 16.0,
            expectedPrefillTps = 24.0,
            uncertainty = "mock uncertainty",
            supportingEvidenceIds = listOf("BENCH-1"),
            limitations = listOf("mock limit"),
            benefit = "mock benefit",
            noExperimentReason = null,
            rawJson = """{"mock":"proposal"}"""
        )
    }

    // ---- creation ----

    @Test
    fun createAgentMakesParentPlusBaseShell() {
        setup()
        val parent = createParent()
        assertEquals("agent", parent.mode)
        assertEquals(SessionState.SESSION_CREATED, parent.state)
        assertEquals(0, parent.budgetUsed)
        val children = agent.childrenOf(parent.sessionId)
        assertEquals(1, children.size)
        assertEquals("byok", children[0].mode)
        assertEquals(1, children[0].experimentSeq)
        assertEquals(parent.sessionId, children[0].parentSessionId)
        assertEquals(2, agent.nextSeq(parent.sessionId))
    }

    @Test
    fun getParentRejectsNonAgent() {
        setup()
        val parent = createParent()
        val child = agent.childrenOf(parent.sessionId).single()
        try {
            agent.getParent(child.sessionId)
            fail("child accepted as parent")
        } catch (e: AgentApi.AgentError) {
            assertEquals("NOT_AGENT", e.code)
        }
    }

    // ---- gate ----

    @Test
    fun gateRequiresEvidenceBudgetAndNoPending() {
        setup()
        val parent = createParent()
        var gate = agent.proposalGate(parent.sessionId)
        assertFalse(gate.first)
        assertTrue(gate.second.contains("no comparable"))
        // Run experiment 1.
        val child1 = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(child1, result("BENCH-1", 5000L))
        agent.afterExperiment(parent.sessionId)
        gate = agent.proposalGate(parent.sessionId)
        assertTrue(gate.second, gate.first)
        agent.storeProposal(parent.sessionId, proposal(), "pack1", 2)
        gate = agent.proposalGate(parent.sessionId)
        assertFalse(gate.first)
        assertTrue(gate.second.contains("pending"))
    }

    // ---- proposals ----

    @Test
    @Suppress("UNCHECKED_CAST")
    fun storeProposalPendsWithHashes() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        val stored = agent.storeProposal(
            parent.sessionId, proposal(), "packhash1", 2)
        val pending = (stored.agent as Map<String, Any?>)["pending_proposal"]
            as Map<String, Any?>
        assertEquals(2, (pending["experiment_seq"] as Number).toInt())
        assertEquals("packhash1", pending["evidence_pack_hash"])
        assertEquals(64, (pending["proposal_hash"] as String).length)
        assertEquals(8,
            ((pending["proposed_config"] as Map<String, Any?>)["n_threads"]
                as Number).toInt())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun nPredictChangeGetsHostCohortNote() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        agent.afterExperiment(parent.sessionId)
        val changed = proposal(
            BenchConfig.default().copy(nPredict = 128), seq = 2)
        val stored = agent.storeProposal(
            parent.sessionId, changed, "pack1", 2)
        val pending = (stored.agent as Map<String, Any?>)["pending_proposal"]
            as Map<String, Any?>
        val notes = pending["host_notes"] as List<String>
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("64→128"))
        assertTrue(notes[0].contains("distinct configuration cohort"))
        // Model limitations untouched by the host note.
        assertEquals(listOf("mock limit"), pending["limitations"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun unchangedNPredictGetsNoCohortNote() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        agent.afterExperiment(parent.sessionId)
        val stored = agent.storeProposal(
            parent.sessionId, proposal(), "pack1", 2)
        val pending = (stored.agent as Map<String, Any?>)["pending_proposal"]
            as Map<String, Any?>
        assertTrue((pending["host_notes"] as List<*>).isEmpty())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun returnToStandardLengthStillLabeled() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        agent.afterExperiment(parent.sessionId)
        // Latest child now uses 128 tokens; propose back to 64.
        childShell(agent.getParent(parent.sessionId), 2,
            BenchConfig.default().copy(nPredict = 128))
        val back = proposal(BenchConfig.default(), seq = 3)
        val stored = agent.storeProposal(
            parent.sessionId, back, "pack1", 3)
        val pending = (stored.agent as Map<String, Any?>)["pending_proposal"]
            as Map<String, Any?>
        val notes = pending["host_notes"] as List<String>
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("128→64"))
    }

    @Test
    fun honestDeclineTerminatesCleanly() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        val decline = proposal().copy(
            proposeExperiment = false,
            proposedConfig = null,
            noExperimentReason = "too thin")
        val stored = agent.storeProposal(parent.sessionId, decline, "p", 2)
        assertEquals(SessionState.SESSION_FINISHED, stored.state)
        assertEquals("completed", stored.outcome)
        @Suppress("UNCHECKED_CAST")
        val agentMap = stored.agent as Map<String, Any?>
        assertEquals(AgentManager.END_NO_FURTHER_TESTING,
            agentMap["terminal_reason"])
        assertTrue((agentMap["summary"] as Map<String, Any?>).isNotEmpty())
    }

    @Test
    fun invalidStrikesTerminateOnSecond() {
        setup()
        val parent = createParent()
        val first = agent.recordInvalidProposal(
            parent.sessionId, "schema boom")
        assertEquals(SessionState.SESSION_CREATED, first.state)
        val second = agent.recordInvalidProposal(
            parent.sessionId, "schema boom again")
        assertEquals(SessionState.SESSION_FINISHED, second.state)
        assertEquals("failed", second.outcome)
        @Suppress("UNCHECKED_CAST")
        assertEquals(AgentManager.END_INVALID_PROPOSAL,
            (second.agent as Map<String, Any?>)["terminal_reason"])
    }

    // ---- approval ----

    private var runCounter = 0

    private fun pendingParent(): Pair<SessionRecord, String> {
        val parent = createParent()
        runCounter++
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-PP-$runCounter", 5000L))
        agent.afterExperiment(parent.sessionId)
        agent.storeProposal(parent.sessionId, proposal(), "pack1", 2)
        return agent.getParent(parent.sessionId) to parent.sessionId
    }

    private fun authorizedChild(
        parentId: String,
        seq: Int,
        config: BenchConfigData
    ): String {
        val parent = agent.getParent(parentId)
        val id = childShell(parent, seq, config)
        manager.requestPrediction(id)
        manager.recordPrediction(id, 16.0, 24.0, """{"mock":"exp"}""",
            SessionManager.SOURCE_LIVE_META, listOf("BENCH-1"),
            "2026-10-28T00:00:00Z")
        manager.authorizeBenchmark(id)
        return id
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun approveLinksChargesExactlyOnce() {
        setup()
        val (_, parentId) = pendingParent()
        val childId = authorizedChild(parentId, 2,
            BenchConfig.default().copy(nThreads = 8))
        val approved = agent.approveProposal(parentId, childId)
        // Experiment 1 trailed (1) plus this approval (1).
        assertEquals(2, approved.budgetUsed)
        val agentMap = approved.agent as Map<String, Any?>
        assertNull(agentMap["pending_proposal"])
        // Experiment 1 trailed plus this approval.
        assertEquals(2, (agentMap["experiments"] as List<*>).size)
        // Second approval finds no pending slot.
        try {
            agent.approveProposal(parentId, childId)
            fail("double approval admitted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("NO_PENDING", e.code)
        }
        assertEquals(2, agent.getParent(parentId).budgetUsed)
    }

    @Test
    fun approveRejectsMismatches() {
        setup()
        // Wrong config.
        var pair = pendingParent()
        var childId = authorizedChild(pair.second, 2, BenchConfig.default())
        try {
            agent.approveProposal(pair.second, childId)
            fail("config mismatch approved")
        } catch (e: AgentApi.AgentError) {
            assertEquals("CONFIG_MISMATCH", e.code)
        }
        // Not authorized.
        pair = pendingParent()
        val parent = agent.getParent(pair.second)
        val idle = childShell(parent, 2, BenchConfig.default().copy(nThreads = 8))
        try {
            agent.approveProposal(pair.second, idle)
            fail("non-authorized approved")
        } catch (e: AgentApi.AgentError) {
            assertEquals("CHILD_NOT_READY", e.code)
        }
        // Foreign child.
        val other = createParent()
        val foreign = authorizedChild(
            other.sessionId, 2, BenchConfig.default().copy(nThreads = 8))
        try {
            agent.approveProposal(pair.second, foreign)
            fail("foreign child approved")
        } catch (e: AgentApi.AgentError) {
            assertEquals("NOT_CHILD", e.code)
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun rejectPreservesProposalAsRejected() {
        setup()
        val (_, parentId) = pendingParent()
        val rejected = agent.rejectProposal(parentId, "too risky")
        val agentMap = rejected.agent as Map<String, Any?>
        assertNull(agentMap["pending_proposal"])
        val list = agentMap["rejected"] as List<Map<String, Any?>>
        assertEquals(1, list.size)
        assertEquals("too risky", list[0]["reason"])
        // Rejection charges nothing; experiment 1 was already trailed.
        assertEquals(1, rejected.budgetUsed)
        // Session stays active; another proposal may be requested.
        val gate = agent.proposalGate(parentId)
        assertTrue(gate.second, gate.first)
    }

    // ---- post-experiment + termination ----

    @Test
    fun afterExperimentTrailsBaseOnce() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        assertNull(agent.afterExperiment(parent.sessionId))
        val trailed = agent.getParent(parent.sessionId)
        assertEquals(1, trailed.budgetUsed)
        // Idempotent re-entry.
        assertNull(agent.afterExperiment(parent.sessionId))
        assertEquals(1, agent.getParent(parent.sessionId).budgetUsed)
    }

    @Test
    fun budgetExhaustionTerminates() {
        setup()
        val parent = createParent(budgetMax = 1)
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        assertEquals(AgentManager.END_BUDGET_EXHAUSTED,
            agent.afterExperiment(parent.sessionId))
        val finished = agent.getParent(parent.sessionId)
        assertEquals(SessionState.SESSION_FINISHED, finished.state)
    }

    @Test
    fun stagnationTerminatesAfterThreeFlat() {
        setup()
        val parent = createParent()
        // 12.8 baseline then three runs that never exceed it.
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        agent.afterExperiment(parent.sessionId)
        val configs = listOf(2, 6, 8).map {
            BenchConfig.default().copy(nThreads = it)
        }
        var seq = 2
        for ((index, config) in configs.withIndex()) {
            val id = childShell(agent.getParent(parent.sessionId), seq, config)
            runChild(id, result("BENCH-S$seq", 5000L, config = config))
            val terminal = agent.afterExperiment(parent.sessionId)
            if (index < 2) {
                assertNull(terminal)
            } else {
                assertEquals(AgentManager.END_STAGNATION, terminal)
            }
            seq++
        }
    }

    @Test
    fun improvementResetsStagnation() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L)) // 12.8
        agent.afterExperiment(parent.sessionId)
        val better = childShell(
            agent.getParent(parent.sessionId), 2,
            BenchConfig.default().copy(nThreads = 8))
        runChild(better, result("BENCH-2", 4000L, // 16.0, exceeds
            config = BenchConfig.default().copy(nThreads = 8)))
        assertNull(agent.afterExperiment(parent.sessionId))
    }

    @Test
    fun stagnationStreakPureCases() {
        assertTrue(AgentManager.stagnationStreak(listOf(10.0, 9.0, 8.0, 7.0)))
        assertTrue(AgentManager.stagnationStreak(listOf(10.0, 10.0, 10.0, 10.0)))
        assertFalse(AgentManager.stagnationStreak(listOf(10.0, 9.0, 11.0, 10.5)))
        assertFalse(AgentManager.stagnationStreak(listOf(10.0, 20.0)))
        assertFalse(AgentManager.stagnationStreak(emptyList()))
        assertFalse(AgentManager.stagnationStreak(listOf(5.0, 6.0, 7.0)))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun summaryReportsCountsAndBest() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L)) // 12.8
        agent.afterExperiment(parent.sessionId)
        val better = childShell(
            agent.getParent(parent.sessionId), 2,
            BenchConfig.default().copy(nThreads = 8))
        runChild(better, result("BENCH-2", 4000L,
            config = BenchConfig.default().copy(nThreads = 8))) // 16.0
        agent.afterExperiment(parent.sessionId)
        agent.finishAgent(parent.sessionId)
        val summary = (agent.getParent(parent.sessionId).agent
            as Map<String, Any?>)["summary"] as Map<String, Any?>
        assertEquals(2, (summary["attempts_used"] as Number).toInt())
        assertEquals(2, (summary["completed"] as Number).toInt())
        assertEquals("BENCH-2", summary["best_run_id"])
        assertEquals(16.0, summary["best_gen_tps"])
        assertEquals(AgentManager.END_USER_FINISH,
            summary["terminal_reason"])
    }

    // ---- reconcile ----

    @Test
    fun reconcileLinksOrphanExactlyOnce() {
        setup()
        val (_, parentId) = pendingParent()
        // Crash window: child authorized, approval never ran.
        authorizedChild(parentId, 2, BenchConfig.default().copy(nThreads = 8))
        val actions = agent.reconcile(parentId)
        assertEquals(1, actions.size)
        val parent = agent.getParent(parentId)
        assertEquals(2, parent.budgetUsed)
        // Second pass finds nothing (pending consumed).
        assertTrue(agent.reconcile(parentId).isEmpty())
        assertEquals(2, agent.getParent(parentId).budgetUsed)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun reconcileCompletesUnfinishedFinish() {
        setup()
        val parent = createParent()
        runChild(agent.childrenOf(parent.sessionId).single().sessionId,
            result("BENCH-1", 5000L))
        // Simulate crash between trail-finalize and state transition.
        val loaded = agent.getParent(parent.sessionId)
        val agentMap = (loaded.agent as Map<String, Any?>).toMutableMap()
        agentMap["status"] = AgentManager.STATUS_FINISHED
        agentMap["terminal_reason"] = AgentManager.END_USER_FINISH
        manager.store.save(loaded.copy(agent = agentMap))
        val actions = agent.reconcile(parent.sessionId)
        assertEquals(1, actions.size)
        assertEquals(SessionState.SESSION_FINISHED,
            agent.getParent(parent.sessionId).state)
    }

    // ---- current config ----

    @Test
    fun currentConfigTracksLatestChild() {
        setup()
        val parent = createParent()
        assertEquals(4, agent.currentConfig(parent.sessionId).nThreads)
        childShell(agent.getParent(parent.sessionId), 2,
            BenchConfig.default().copy(nThreads = 6))
        assertEquals(6, agent.currentConfig(parent.sessionId).nThreads)
    }
}
