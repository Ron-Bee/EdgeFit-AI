package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for EF-FOLLOWUP-02 repeatable benchmark + Muse
 * follow-up loop: repeat records, carried predictions, repeat
 * proposals, budget-charged approval, series comparison, and
 * follow-up priors. No network, no native code, no auto-runs.
 */
class FollowUp02Test {
    @get:Rule
    val temp = TemporaryFolder()

    private var stamp = "2026-10-28T00:00:00Z"

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
        const val RUN_A = "BENCH-20261028T010000Z-aaaa1111"
        const val RUN_B = "BENCH-20261028T020000Z-bbbb2222"
        const val RUN_C = "BENCH-20261028T030000Z-cccc3333"
    }

    private fun managers(): Pair<SessionManager, AgentManager> {
        val dir = temp.newFolder()
        val sessions = SessionManager(dir) { stamp }
        return sessions to AgentManager(sessions) { stamp }
    }

    private fun byok(
        manager: SessionManager,
        parentId: String? = null,
        seq: Int? = null,
        config: BenchConfigData = BenchConfig.default()
    ): SessionRecord = manager.createSession(
        mode = "byok",
        deviceModel = "SM-S908U",
        profileSha256 = null,
        modelSha256 = MODEL_SHA,
        modelSizeBytes = 100L,
        modelDisplay = "mock.gguf",
        workloadId = BenchContract.WORKLOAD_ID,
        promptSha256 = PROMPT_SHA,
        config = SessionBenchBridge.configMap(config),
        parentSessionId = parentId,
        experimentSeq = seq
    )

    private fun predict(
        manager: SessionManager,
        id: String,
        gen: Double = 15.0,
        prefill: Double = 23.0
    ) {
        manager.requestPrediction(id)
        manager.recordPrediction(id, gen, prefill, """{"mock":true}""",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList())
    }

    private fun attach(
        manager: SessionManager,
        id: String,
        runId: String,
        collectedAt: String,
        gen: Double = 16.0,
        prefill: Double = 24.0,
        status: String = BenchContract.STATUS_COMPLETED,
        config: BenchConfigData = BenchConfig.default()
    ): SessionRecord {
        manager.authorizeBenchmark(id, "test")
        manager.noteRunStarted(id)
        return manager.attachBenchmark(
            id = id,
            runId = runId,
            sourceSha256 = "ab".repeat(32),
            status = status,
            measurements = linkedMapOf(
                "tps_gen" to gen,
                "tps_prefill" to prefill,
                "thermal_after" to "none"
            ),
            collectedAtUtc = collectedAt,
            modelSha256 = MODEL_SHA,
            workloadId = BenchContract.WORKLOAD_ID,
            config = SessionBenchBridge.configMap(config),
            deviceModel = null
        )
    }

    /** One standalone predicted + completed session. */
    private fun completed(manager: SessionManager): SessionRecord {
        val session = byok(manager)
        predict(manager, session.sessionId)
        return attach(manager, session.sessionId, RUN_A,
            "2026-10-28T01:00:00Z")
    }

    // ---- repeat records ----

    @Test
    @Suppress("UNCHECKED_CAST")
    fun repeatLinksSourceAndCarriesPrediction() {
        val (manager, _) = managers()
        val source = completed(manager)
        stamp = "2026-10-28T01:30:00Z"
        val repeat = manager.createRepeat(
            source.sessionId, "SM-S908U", null)
        assertEquals(source.sessionId, repeat.repeatOfSessionId)
        assertEquals(RUN_A, repeat.repeatOfRunId)
        assertEquals(SessionState.PREDICTION_RECORDED, repeat.state)
        assertEquals(source.config, repeat.config)
        val prediction = repeat.prediction as Map<String, Any?>
        // Carried values + carried provenance; the ORIGINAL
        // commit timestamp survives untouched.
        assertEquals(15.0, prediction["predicted_gen_tps"])
        assertEquals(23.0, prediction["predicted_prefill_tps"])
        assertEquals("2026-10-28T00:00:00Z",
            prediction["created_at_utc"])
        assertEquals(source.sessionId,
            prediction["carried_from_session"])
        assertEquals(RUN_A, prediction["carried_from_run"])
        assertEquals("2026-10-28T01:30:00Z",
            prediction["carried_at_utc"])
        // Source record itself is byte-stable: still
        // completed, still owns RUN_A.
        val reread = manager.get(source.sessionId)
        assertEquals(SessionState.COMPARISON_READY, reread.state)
        assertEquals(RUN_A,
            (reread.benchmark as Map<String, Any?>)["run_id"])
    }

    @Test
    fun repeatRefusesMissingPrediction() {
        val (manager, _) = managers()
        val session = byok(manager)
        try {
            manager.createRepeat(session.sessionId, null, null)
            fail("repeat without prediction accepted")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("no prediction"))
        }
    }

    @Test
    fun repeatRefusesMissingBenchmark() {
        val (manager, _) = managers()
        val session = byok(manager)
        predict(manager, session.sessionId)
        try {
            manager.createRepeat(session.sessionId, null, null)
            fail("repeat without benchmark accepted")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("no benchmark"))
        }
    }

    @Test
    fun repeatRefusesAgentParent() {
        val (manager, agent) = managers()
        val parent = agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            baseConfig = BenchConfig.default())
        try {
            manager.createRepeat(parent.sessionId, null, null)
            fail("repeat of agent parent accepted")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("prediction") ||
                e.message!!.contains("agent parent"))
        }
    }

    @Test
    fun repeatAttachBindsNewRunAndKeepsSource() {
        val (manager, _) = managers()
        val source = completed(manager)
        stamp = "2026-10-28T01:30:00Z"
        val repeat = manager.createRepeat(
            source.sessionId, "SM-S908U", null)
        val done = attach(manager, repeat.sessionId, RUN_B,
            "2026-10-28T02:00:00Z", gen = 16.5)
        assertEquals(SessionState.COMPARISON_READY, done.state)
        // Comparison runs against the CARRIED prediction.
        @Suppress("UNCHECKED_CAST")
        val comparison = done.comparison as Map<String, Any?>
        assertEquals(true, comparison["comparable"])
        assertEquals(1.5, (comparison["gen_signed"] as Number).toDouble(),
            0.001)
        // Source untouched: same state, same run, same values.
        val reread = manager.get(source.sessionId)
        assertEquals(SessionState.COMPARISON_READY, reread.state)
        @Suppress("UNCHECKED_CAST")
        val bench = reread.benchmark as Map<String, Any?>
        assertEquals(RUN_A, bench["run_id"])
    }

    @Test
    fun repeatSameSecondBoundaryAttaches() {
        val (manager, _) = managers()
        val source = completed(manager)
        stamp = "2026-10-28T01:30:00Z"
        val repeat = manager.createRepeat(
            source.sessionId, "SM-S908U", null)
        // Collected exactly at the carried commit second:
        // FIX2 boundary still permits attachment.
        val done = attach(manager, repeat.sessionId, RUN_B,
            "2026-10-28T00:00:00Z")
        assertEquals(SessionState.COMPARISON_READY, done.state)
    }

    @Test
    fun repeatDuplicateRunRefused() {
        val (manager, _) = managers()
        val source = completed(manager)
        stamp = "2026-10-28T01:30:00Z"
        val repeat = manager.createRepeat(
            source.sessionId, "SM-S908U", null)
        manager.authorizeBenchmark(repeat.sessionId, "test")
        manager.noteRunStarted(repeat.sessionId)
        try {
            manager.attachBenchmark(
                id = repeat.sessionId,
                runId = RUN_A,
                sourceSha256 = "ab".repeat(32),
                status = BenchContract.STATUS_COMPLETED,
                measurements = linkedMapOf(
                    "tps_gen" to 16.0, "tps_prefill" to 24.0),
                collectedAtUtc = "2026-10-28T02:00:00Z",
                modelSha256 = MODEL_SHA,
                workloadId = BenchContract.WORKLOAD_ID,
                config = SessionBenchBridge.configMap(
                    BenchConfig.default()),
                deviceModel = null
            )
            fail("duplicate run id attached")
        } catch (e: SessionManager.SessionError) {
            assertTrue(e.message!!.contains("already attached"))
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun repeatNeverReopensTerminalSource() {
        val (manager, _) = managers()
        val source = completed(manager)
        manager.requestFollowup(source.sessionId)
        manager.recordRecommendation(source.sessionId,
            linkedMapOf("direction" to "underestimated"),
            "offline test")
        val terminal = manager.get(source.sessionId)
        assertEquals(SessionState.RECOMMENDATION_RECORDED,
            terminal.state)
        stamp = "2026-10-28T01:30:00Z"
        val repeat = manager.createRepeat(
            source.sessionId, "SM-S908U", null)
        attach(manager, repeat.sessionId, RUN_B,
            "2026-10-28T02:00:00Z")
        // Terminal source + its follow-up are immutable.
        val reread = manager.get(source.sessionId)
        assertEquals(SessionState.RECOMMENDATION_RECORDED,
            reread.state)
        assertEquals((terminal.followup as Map<String, Any?>),
            (reread.followup as Map<String, Any?>))
        // The repeat is a fresh session: no follow-up yet,
        // but eligible once compared.
        val done = manager.get(repeat.sessionId)
        assertEquals(SessionState.COMPARISON_READY, done.state)
        assertEquals(null, done.followup)
        manager.requestFollowup(repeat.sessionId)
        manager.recordRecommendation(repeat.sessionId,
            linkedMapOf("direction" to "accurate"), "offline test")
        assertEquals(SessionState.RECOMMENDATION_RECORDED,
            manager.get(repeat.sessionId).state)
    }

    // ---- repeat proposals ----

    private fun repeatExpectation() = AgentApi.ProposalExpectation(
        modelSha = MODEL_SHA,
        workloadId = BenchContract.WORKLOAD_ID,
        currentConfig = BenchConfig.default(),
        nextSeq = 2,
        allowedRunIds = setOf(RUN_A),
        deviceMaxThreads = 8,
        repeatConfigs = mapOf(RUN_A to BenchConfig.default())
    )

    private fun repeatPayload(): MutableMap<String, Any?> = mutableMapOf(
        "model_sha" to MODEL_SHA,
        "workload_id" to BenchContract.WORKLOAD_ID,
        "experiment_seq" to 2,
        "current_config" to mapOf(
            "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
        "propose_experiment" to true,
        "proposed_config" to mapOf(
            "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
        "purpose" to AgentApi.PURPOSE_REPEAT,
        "repeat_of_run_id" to RUN_A,
        "hypothesis" to "variability check",
        "expected_direction" to "uncertain",
        "expected_gen_tps" to 16.0,
        "expected_prefill_tps" to 24.0,
        "uncertainty" to "single prior run",
        "supporting_evidence_ids" to listOf(RUN_A),
        "limitations" to listOf("n=1 so far"),
        "benefit" to "assess run-to-run spread",
        "no_experiment_reason" to null
    )

    @Test
    fun repeatProposalValidWithExactConfig() {
        val valid = AgentApi.validateProposal(
            repeatPayload(), "{}", repeatExpectation())
        assertTrue(valid.proposeExperiment)
        assertEquals(AgentApi.PURPOSE_REPEAT, valid.purpose)
        assertEquals(RUN_A, valid.repeatOfRunId)
        assertTrue(valid.changedKeys.isEmpty())
        assertEquals(512, valid.proposedConfig!!.nCtx)
    }

    @Test
    fun repeatCitingIneligibleRunRefused() {
        val payload = repeatPayload()
        payload["repeat_of_run_id"] = RUN_B
        try {
            AgentApi.validateProposal(payload, "{}", repeatExpectation())
            fail("ineligible repeat cite accepted")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("ineligible"))
        }
    }

    @Test
    fun repeatConfigMismatchRefused() {
        val payload = repeatPayload()
        payload["proposed_config"] = mapOf(
            "n_ctx" to 512, "n_threads" to 8, "n_predict" to 64)
        try {
            AgentApi.validateProposal(payload, "{}", repeatExpectation())
            fail("changed-config repeat accepted")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("differs from cited"))
        }
    }

    @Test
    fun repeatWithoutVerifiedConfigRefused() {
        val bare = repeatExpectation().copy(repeatConfigs = emptyMap())
        try {
            AgentApi.validateProposal(repeatPayload(), "{}", bare)
            fail("repeat without verified config accepted")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("no verified config"))
        }
    }

    @Test
    fun optimizeNoChangeStillRefused() {
        val payload = repeatPayload()
        payload.remove("purpose")
        payload.remove("repeat_of_run_id")
        try {
            AgentApi.validateProposal(payload, "{}", repeatExpectation())
            fail("ordinary no-change proposal accepted")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("changes nothing"))
        }
    }

    @Test
    fun unknownPurposeRefused() {
        val payload = repeatPayload()
        payload["purpose"] = "retrain"
        try {
            AgentApi.validateProposal(payload, "{}", repeatExpectation())
            fail("unknown purpose accepted")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("unknown purpose"))
        }
    }

    // ---- repeat approval + budget ----

    private fun agentParent(agent: AgentManager): SessionRecord =
        agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            baseConfig = BenchConfig.default())

    /** Completed experiment-1 child owning a run. */
    private fun experimentOne(
        manager: SessionManager,
        parent: SessionRecord,
        runId: String = RUN_A
    ): SessionRecord {
        val child = byok(manager, parent.sessionId, 1)
        predict(manager, child.sessionId)
        return attach(manager, child.sessionId, runId,
            "2026-10-28T01:00:00Z")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun repeatApprovalChargesBudgetExactlyOnce() {
        val (manager, agent) = managers()
        val parent = agentParent(agent)
        experimentOne(manager, parent)
        val valid = AgentApi.validateProposal(
            repeatPayload(), "{}", repeatExpectation())
        stamp = "2026-10-28T01:30:00Z"
        agent.storeProposal(parent.sessionId, valid, "pack", 2)
        val source = agent.childForRun(parent.sessionId, RUN_A)!!
        val repeat = manager.createRepeat(source.sessionId,
            "SM-S908U", null, parent.sessionId, 2)
        manager.authorizeBenchmark(repeat.sessionId, "test")
        val approved = agent.approveProposal(
            parent.sessionId, repeat.sessionId)
        assertEquals(1, approved.budgetUsed)
        assertEquals(null, (approved.agent as Map<String, Any?>)
            ["pending_proposal"])
        // Pending slot consumed exactly once.
        try {
            agent.approveProposal(parent.sessionId, repeat.sessionId)
            fail("second approval accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("NO_PENDING", e.code)
        }
        assertEquals(1, agent.getParent(parent.sessionId).budgetUsed)
    }

    @Test
    fun repeatApprovalRejectsUnlinkedChild() {
        val (manager, agent) = managers()
        val parent = agentParent(agent)
        experimentOne(manager, parent)
        val valid = AgentApi.validateProposal(
            repeatPayload(), "{}", repeatExpectation())
        agent.storeProposal(parent.sessionId, valid, "pack", 2)
        // Fresh child with matching config but no carried link.
        val impostor = byok(manager, parent.sessionId, 2)
        predict(manager, impostor.sessionId)
        manager.authorizeBenchmark(impostor.sessionId, "test")
        try {
            agent.approveProposal(parent.sessionId, impostor.sessionId)
            fail("unlinked repeat child approved")
        } catch (e: AgentApi.AgentError) {
            assertEquals("REPEAT_LINK", e.code)
        }
        assertEquals(0, agent.getParent(parent.sessionId).budgetUsed)
    }

    @Test
    fun childForRunFindsOwnerOnly() {
        val (manager, agent) = managers()
        val parent = agentParent(agent)
        val child = experimentOne(manager, parent)
        assertEquals(child.sessionId,
            agent.childForRun(parent.sessionId, RUN_A)!!.sessionId)
        assertEquals(null, agent.childForRun(parent.sessionId, RUN_B))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun executedConfigMapMirrorsStoredRun() {
        val (manager, agent) = managers()
        val parent = agentParent(agent)
        experimentOne(manager, parent)
        val executed =
            agent.executedConfigMapForRun(parent.sessionId, RUN_A)!!
        assertEquals(512L, (executed["n_ctx"] as Number).toLong())
        assertEquals(4L, (executed["n_threads"] as Number).toLong())
        assertEquals(64L, (executed["n_predict"] as Number).toLong())
        assertEquals(null,
            agent.executedConfigMapForRun(parent.sessionId, RUN_B))
    }

    // ---- series comparison ----

    @Test
    fun findSeriesRunsSelectsComparableCompleted() {
        val (manager, _) = managers()
        val first = byok(manager)
        predict(manager, first.sessionId)
        attach(manager, first.sessionId, RUN_A,
            "2026-10-28T01:00:00Z", gen = 15.0)
        val second = byok(manager)
        predict(manager, second.sessionId)
        attach(manager, second.sessionId, RUN_B,
            "2026-10-28T02:00:00Z", gen = 16.0)
        // Failed run: never pooled.
        val failed = byok(manager)
        predict(manager, failed.sessionId)
        attach(manager, failed.sessionId, RUN_C,
            "2026-10-28T03:00:00Z", gen = 0.0,
            status = BenchContract.STATUS_FAILED)
        val series = manager.findSeriesRuns(second.sessionId)
        assertEquals(2, series.size)
        // Oldest first by collection time.
        assertEquals(first.sessionId, series[0].sessionId)
        assertEquals(second.sessionId, series[1].sessionId)
    }

    @Test
    fun findSeriesRunsExcludesOtherConfigs() {
        val (manager, _) = managers()
        val base = byok(manager)
        predict(manager, base.sessionId)
        attach(manager, base.sessionId, RUN_A,
            "2026-10-28T01:00:00Z")
        val wide = byok(manager,
            config = BenchConfig.default().copy(nThreads = 8))
        predict(manager, wide.sessionId)
        attach(manager, wide.sessionId, RUN_B,
            "2026-10-28T02:00:00Z",
            config = BenchConfig.default().copy(nThreads = 8))
        assertEquals(1, manager.findSeriesRuns(base.sessionId).size)
        assertEquals(1, manager.findSeriesRuns(wide.sessionId).size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun compareSeriesComputesHostDeltas() {
        val (manager, _) = managers()
        val first = byok(manager)
        predict(manager, first.sessionId)
        attach(manager, first.sessionId, RUN_A,
            "2026-10-28T01:00:00Z", gen = 15.0, prefill = 21.0)
        val second = byok(manager)
        predict(manager, second.sessionId)
        attach(manager, second.sessionId, RUN_B,
            "2026-10-28T02:00:00Z", gen = 16.5, prefill = 22.0)
        val current = manager.get(second.sessionId)
        val result = SessionComparison.compareSeries(
            current, manager.findSeriesRuns(second.sessionId))
        assertEquals(RUN_B, result.currentRunId)
        assertEquals(2, result.rows.size)
        assertEquals(15.0, result.minGen, 0.0)
        assertEquals(16.5, result.maxGen, 0.0)
        val prior = result.rows[0]
        assertEquals(RUN_A, prior.runId)
        assertEquals(false, prior.isCurrent)
        assertEquals(1.5, prior.deltaGen, 0.001)
        assertEquals(10.0, prior.deltaPct, 0.01)
        val self = result.rows[1]
        assertEquals(true, self.isCurrent)
        assertEquals(0.0, self.deltaGen, 0.0)
        assertEquals(0.0, self.deltaPct, 0.0)
    }

    // ---- follow-up evidence package ----

    @Test
    fun followUpUserRendersBoundedPriors() {
        val text = MetaApi.followUpUser(
            runId = RUN_B, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            config = BenchConfig.default(),
            predictedGen = 15.0, predictedPrefill = 23.0,
            measuredGen = 16.5, measuredPrefill = 22.0,
            genSigned = 1.5, genPct = 10.0,
            prefillSigned = -1.0, prefillPct = -4.35,
            thermalBefore = "none", thermalMid = "none",
            thermalAfter = "none", memMethod = "mock",
            seriesPriors = listOf(MetaApi.SeriesPrior(
                runId = RUN_A,
                collectedAtUtc = "2026-10-28T01:00:00Z",
                genTps = 15.0, prefillTps = 21.0,
                thermalAfter = "none",
                deltaGen = 1.5, deltaPct = 10.0))
        )
        assertTrue(text.contains("SERIES_PRIORS"))
        assertTrue(text.contains(RUN_A))
        assertTrue(text.contains("delta_gen=1.5"))
        assertTrue(text.contains("quote host deltas"))
        assertFalse(text.contains("single paired prediction"))
    }

    @Test
    fun followUpUserWithoutPriorsUnchanged() {
        val text = MetaApi.followUpUser(
            runId = RUN_A, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            config = BenchConfig.default(),
            predictedGen = 15.0, predictedPrefill = 23.0,
            measuredGen = 16.0, measuredPrefill = 24.0,
            genSigned = 1.0, genPct = 6.67,
            prefillSigned = 1.0, prefillPct = 4.35,
            thermalBefore = "none", thermalMid = "none",
            thermalAfter = "none", memMethod = "mock")
        assertTrue(text.contains("single paired prediction"))
        assertFalse(text.contains("SERIES_PRIORS"))
    }

    // ---- UI eligibility + presentation (pure) ----

    @Test
    fun repeatStandaloneGate() {
        val (manager, agent) = managers()
        val solo = completed(manager)
        assertTrue(SessionView.canRepeatStandalone(
            manager.get(solo.sessionId)))
        assertTrue(SessionView.canCompare(manager.get(solo.sessionId)))
        // Agent parents never repeat standalone.
        val parent = agentParent(agent)
        assertFalse(SessionView.canRepeatStandalone(
            agent.getParent(parent.sessionId)))
        assertFalse(SessionView.canCompare(
            agent.getParent(parent.sessionId)))
        // Agent children repeat via proposals, not the button —
        // but their completed runs still compare.
        val child = experimentOne(manager, parent, RUN_B)
        assertFalse(SessionView.canRepeatStandalone(
            manager.get(child.sessionId)))
        assertTrue(SessionView.canCompare(manager.get(child.sessionId)))
        // No prediction, no repeat.
        val bare = byok(manager)
        assertFalse(SessionView.canRepeatStandalone(
            manager.get(bare.sessionId)))
        assertFalse(SessionView.canCompare(manager.get(bare.sessionId)))
        // Failed runs neither repeat nor compare.
        val failed = byok(manager)
        predict(manager, failed.sessionId)
        attach(manager, failed.sessionId, RUN_C,
            "2026-10-28T02:00:00Z", gen = 0.0,
            status = BenchContract.STATUS_FAILED)
        assertFalse(SessionView.canRepeatStandalone(
            manager.get(failed.sessionId)))
        assertFalse(SessionView.canCompare(manager.get(failed.sessionId)))
    }

    @Test
    fun proposalTextLabelsRepeatPurpose() {
        val pending = mapOf<String, Any?>(
            "experiment_seq" to 2,
            "purpose" to AgentApi.PURPOSE_REPEAT,
            "repeat_of_run_id" to RUN_A,
            "proposed_config" to mapOf(
                "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
            "changed_keys" to emptyList<String>(),
            "hypothesis" to "h", "expected_gen_tps" to 16.0,
            "expected_direction" to "uncertain",
            "uncertainty" to "u",
            "supporting_evidence_ids" to listOf(RUN_A),
            "benefit" to "b", "limitations" to emptyList<String>(),
            "host_notes" to emptyList<String>(),
            "proposal_hash" to "00".repeat(32),
            "evidence_pack_hash" to "11".repeat(32))
        val text = AgentView.proposalText(pending)
        assertTrue(text.contains("REPEAT of $RUN_A"))
        assertTrue(text.contains("variability check"))
    }

    @Test
    fun proposalSectionsLabelRepeatPurpose() {
        val pending = mapOf<String, Any?>(
            "purpose" to AgentApi.PURPOSE_REPEAT,
            "repeat_of_run_id" to RUN_A,
            "proposed_config" to mapOf(
                "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64,
                "temperature" to 0.0, "sampler" to "greedy",
                "seed" to 1L),
            "changed_keys" to emptyList<String>(),
            "expected_gen_tps" to 16.0,
            "expected_direction" to "uncertain",
            "uncertainty" to "u",
            "hypothesis" to "h", "benefit" to "b",
            "supporting_evidence_ids" to listOf(RUN_A),
            "limitations" to emptyList<String>(),
            "host_notes" to emptyList<String>(),
            "proposal_hash" to "00".repeat(32),
            "evidence_pack_hash" to "11".repeat(32))
        val sections = InsightCards.proposalSections(
            pending, SessionBenchBridge.configMap(BenchConfig.default()),
            1, 5)
        val first = sections[0].rows.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(first.contains("REPEAT of $RUN_A"))
    }

    @Test
    fun seriesSectionsRenderHostValues() {
        val (manager, _) = managers()
        val first = byok(manager)
        predict(manager, first.sessionId)
        attach(manager, first.sessionId, RUN_A,
            "2026-10-28T01:00:00Z", gen = 15.0)
        val second = byok(manager)
        predict(manager, second.sessionId)
        attach(manager, second.sessionId, RUN_B,
            "2026-10-28T02:00:00Z", gen = 16.5)
        val current = manager.get(second.sessionId)
        val sections = InsightCards.seriesSections(
            SessionComparison.compareSeries(current,
                manager.findSeriesRuns(second.sessionId)))
        assertEquals("Current selected run (MEASURED)",
            sections[0].title)
        assertEquals("Other comparable runs", sections[1].title)
        assertEquals("Series summary", sections[2].title)
        assertEquals("Technical evidence", sections[3].title)
        val selected = sections[0].rows.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(selected.contains("Reference"))
        assertTrue(sections[0].rows.all {
            it.accent == InsightCards.Accent.MEASURED
        })
        val compared = sections[1].rows.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(compared.contains(
            InsightCards.runShort(RUN_A)))
        assertTrue(compared.contains("selected run minus " +
            "compared run"))
        // Selected 16.5 minus compared 15.0: +1.5, never
        // a "+0.0%" pseudo-comparison on the reference.
        assertTrue(compared.contains("+1.5"))
        assertFalse(selected.contains("+0.0%"))
        assertTrue(sections[3].details!!.contains(RUN_B))
    }
}
