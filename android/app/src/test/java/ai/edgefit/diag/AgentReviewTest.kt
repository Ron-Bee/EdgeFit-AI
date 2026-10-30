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

/** EF-AGENT-REVIEW-01: contract v2, host review, modify, and cards. */
class AgentReviewTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val OTHER_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaa"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
    }

    private lateinit var manager: SessionManager
    private lateinit var agent: AgentManager

    private fun setup() {
        manager = SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }
        agent = AgentManager(manager) { "2026-10-28T00:00:00Z" }
    }

    private fun createParent(): SessionRecord = agent.createAgent(
        deviceModel = "SM-S908U",
        profileSha256 = null,
        modelSha256 = MODEL_SHA,
        modelSizeBytes = 100L,
        modelDisplay = "mock.gguf",
        workloadId = BenchContract.WORKLOAD_ID,
        promptSha256 = PROMPT_SHA,
        baseConfig = BenchConfig.default(),
        budgetMax = 5
    )

    private fun result(
        runId: String,
        genMs: Long,
        tokens: Int = 64,
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
        status = BenchContract.STATUS_COMPLETED,
        error = null,
        appVersion = "mock",
        collectedAtUtc = "2026-10-28T01:00:00Z"
    )

    private fun runChild(childId: String, bench: BenchResult) {
        manager.requestPrediction(childId)
        manager.recordPrediction(childId, 15.0, 23.0, """{"mock":true}""",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(),
            "2026-10-28T00:00:00Z")
        manager.authorizeBenchmark(childId)
        manager.noteRunStarted(childId)
        SessionBenchBridge.attach(manager, childId, bench)
    }

    private fun childShell(parent: SessionRecord, seq: Int): String =
        manager.createSession(
            mode = "byok",
            deviceModel = "SM-S908U",
            profileSha256 = null,
            modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = parent.workloadId,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            parentSessionId = parent.sessionId,
            experimentSeq = seq
        ).sessionId

    private fun proposal(
        config: BenchConfigData = BenchConfig.default().copy(nThreads = 8),
        changed: List<String> = listOf("n_threads"),
        purpose: String = AgentApi.PURPOSE_OPTIMIZE,
        repeatOf: String? = null,
        cited: List<String> = listOf("BENCH-1"),
        expectedGen: Double = 16.0
    ): AgentApi.ValidProposal = AgentApi.ValidProposal(
        proposeExperiment = true,
        proposedConfig = config,
        changedKeys = changed,
        purpose = purpose,
        repeatOfRunId = repeatOf,
        hypothesis = "mock hypothesis",
        expectedDirection = "faster",
        expectedGenTps = expectedGen,
        expectedPrefillTps = 24.0,
        uncertainty = "mock uncertainty",
        supportingEvidenceIds = cited,
        limitations = listOf("mock limit"),
        benefit = "mock benefit",
        noExperimentReason = null,
        rawJson = """{"mock":"proposal"}"""
    )

    @Suppress("UNCHECKED_CAST")
    private fun pendingOf(parentId: String): Map<String, Any?> =
        ((agent.getParent(parentId).agent as Map<String, Any?>)
            ["pending_proposal"] as Map<String, Any?>)

    private fun reviewInput(
        parent: SessionRecord,
        pending: Map<String, Any?>,
        staged: String? = MODEL_SHA,
        failures: Set<String> = emptySet(),
        memBelow: Set<String> = emptySet(),
        memPct: Map<String, Double> = emptyMap()
    ): AgentReview.ReviewInput {
        val pack = agent.buildPack(parent.sessionId)
        val executed = pack.comparable.mapNotNull { ev ->
            val data = agent.executedConfigMapForRun(
                parent.sessionId, ev.runId)?.let {
                SessionBenchBridge.configData(it)
            }
            if (data != null) ev.runId to data else null
        }.toMap()
        return AgentReview.ReviewInput(
            pending = pending,
            parent = parent,
            pack = pack,
            failureRunIds = failures,
            executedConfigs = executed,
            current = agent.currentConfig(parent.sessionId),
            deviceMaxThreads = 8,
            stagedModelSha = staged,
            memStartPct = memPct,
            memBelowAdvisory = memBelow
        )
    }

    private fun checkOf(
        result: AgentReview.ReviewResult,
        name: String
    ): AgentReview.Check =
        result.checks.first { it.name == name }

    // ---- proposal contract v2 ----

    private fun expectation() = AgentApi.ProposalExpectation(
        modelSha = MODEL_SHA,
        workloadId = BenchContract.WORKLOAD_ID,
        currentConfig = BenchConfig.default(),
        nextSeq = 2,
        allowedRunIds = setOf("BENCH-1"),
        deviceMaxThreads = 8
    )

    private fun validPayload(): MutableMap<String, Any?> = mutableMapOf(
        "model_sha" to MODEL_SHA,
        "workload_id" to BenchContract.WORKLOAD_ID,
        "experiment_seq" to 2,
        "current_config" to mapOf(
            "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
        "propose_experiment" to true,
        "proposed_config" to mapOf("n_threads" to 8),
        "hypothesis" to "threads bind throughput",
        "expected_direction" to "faster",
        "expected_gen_tps" to 16.0,
        "expected_prefill_tps" to 24.0,
        "uncertainty" to "single-device guess",
        "supporting_evidence_ids" to listOf("BENCH-1"),
        "limitations" to listOf("thermal unknown"),
        "benefit" to "may raise throughput",
        "no_experiment_reason" to null
    )

    @Test
    fun v1ReplyWithoutCritiqueStillValidates() {
        val valid = AgentApi.validateProposal(
            validPayload(), "{}", expectation())
        assertNull(valid.counterargument)
        assertNull(valid.alternativeExplanation)
        assertNull(valid.nextBenchmarkEstablishes)
        assertEquals(1, valid.contractVersion)
    }

    @Test
    fun v2ReplyParsesSelfCritique() {
        val payload = validPayload()
        payload["counterargument"] = "thermal throttling may dominate"
        payload["alternative_explanation"] = "variance, not threads"
        payload["next_benchmark_establishes"] = "whether 8 threads hold up"
        val valid = AgentApi.validateProposal(payload, "{}", expectation())
        assertEquals("thermal throttling may dominate", valid.counterargument)
        assertEquals("variance, not threads", valid.alternativeExplanation)
        assertEquals("whether 8 threads hold up",
            valid.nextBenchmarkEstablishes)
        assertEquals(2, valid.contractVersion)
    }

    @Test
    fun critiqueNullAndBlankStayNull() {
        val payload = validPayload()
        payload["counterargument"] = null
        payload["alternative_explanation"] = "  "
        val valid = AgentApi.validateProposal(payload, "{}", expectation())
        assertNull(valid.counterargument)
        assertNull(valid.alternativeExplanation)
    }

    @Test
    fun critiqueOversizedOrMistypedRefuses() {
        val big = validPayload()
        big["counterargument"] = "x".repeat(501)
        try {
            AgentApi.validateProposal(big, "{}", expectation())
            fail("oversized critique accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
        val typed = validPayload()
        typed["counterargument"] = 42
        try {
            AgentApi.validateProposal(typed, "{}", expectation())
            fail("mistyped critique accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
    }

    @Test
    fun declineBranchParsesCritique() {
        val payload = validPayload()
        payload["propose_experiment"] = false
        payload["no_experiment_reason"] = "evidence too thin"
        payload["counterargument"] = "a retry might still teach us"
        val valid = AgentApi.validateProposal(payload, "{}", expectation())
        assertFalse(valid.proposeExperiment)
        assertEquals("a retry might still teach us", valid.counterargument)
        assertEquals(2, valid.contractVersion)
    }

    @Test
    fun bothPromptsRequestSelfCritique() {
        val standard = AgentApi.proposalSystem(8)
        val recovery = AgentApi.proposalSystem(8, recoveryMode = true)
        for (prompt in listOf(standard, recovery)) {
            assertTrue(prompt.contains("counterargument"))
            assertTrue(prompt.contains("alternative_explanation"))
            assertTrue(prompt.contains("next_benchmark_establishes"))
            assertTrue(prompt.contains("never substitutes"))
        }
    }

    // ---- follow-up counterargument ----

    private fun validFollowUp(): MutableMap<String, Any?> = mutableMapOf(
        "direction" to "overestimated",
        "summary" to "measured below prediction",
        "limitations" to listOf("single pair"),
        "supports_conclusion" to false,
        "next_experiment" to null,
        "run_id" to "BENCH-1"
    )

    @Test
    fun followUpWithoutCounterargumentStaysNull() {
        val valid = MetaApi.validateFollowUp(
            validFollowUp(), "{}", "BENCH-1", -3.825, -25.5)
        assertNull(valid.counterargument)
    }

    @Test
    fun followUpCounterargumentParses() {
        val payload = validFollowUp()
        payload["counterargument"] = "one slow run proves no trend"
        val valid = MetaApi.validateFollowUp(
            payload, "{}", "BENCH-1", -3.825, -25.5)
        assertEquals("one slow run proves no trend", valid.counterargument)
    }

    @Test
    fun followUpCounterargumentOversizedRefuses() {
        val payload = validFollowUp()
        payload["counterargument"] = "x".repeat(501)
        try {
            MetaApi.validateFollowUp(payload, "{}", "BENCH-1", -3.825, -25.5)
            fail("oversized follow-up critique accepted")
        } catch (e: MetaApi.MetaError) {
            assertEquals("BAD_FOLLOWUP", e.code)
        }
    }

    @Test
    fun followUpPromptMentionsCounterargument() {
        assertTrue(MetaApi.followUpSystem().contains("counterargument"))
    }

    // ---- review verdicts ----

    @Test
    fun cleanProposalIsSupported() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        runChild(childShell(parent, 2), result("BENCH-2", 3800L))
        agent.storeProposal(parent.sessionId,
            proposal(cited = listOf("BENCH-1", "BENCH-2")), "pack", 3)
        val pending = pendingOf(parent.sessionId)
        val verdict = AgentReview.reviewProposal(
            reviewInput(agent.getParent(parent.sessionId), pending))
        assertEquals(AgentReview.Verdict.SUPPORTED, verdict.verdict)
        assertTrue(verdict.objections.isEmpty())
    }

    @Test
    fun singleObservationIsCaution() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        agent.storeProposal(parent.sessionId, proposal(), "pack", 2)
        val verdict = AgentReview.reviewProposal(reviewInput(
            agent.getParent(parent.sessionId), pendingOf(parent.sessionId)))
        assertEquals(AgentReview.Verdict.CAUTION, verdict.verdict)
        assertEquals(AgentReview.Severity.CAUTION,
            checkOf(verdict, "evidence").severity)
    }

    @Test
    fun contradictingRunsDrawCaution() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 5000L))
        runChild(childShell(parent, 2), result("BENCH-2", 1500L))
        agent.storeProposal(parent.sessionId,
            proposal(cited = listOf("BENCH-1", "BENCH-2")), "pack", 3)
        val verdict = AgentReview.reviewProposal(reviewInput(
            agent.getParent(parent.sessionId), pendingOf(parent.sessionId)))
        assertEquals(AgentReview.Verdict.CAUTION, verdict.verdict)
        val check = checkOf(verdict, "contradiction")
        assertEquals(AgentReview.Severity.CAUTION, check.severity)
        assertTrue(check.detail.contains("disagree"))
    }

    @Test
    fun expectedFarOutsideObservedIsCaution() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        runChild(childShell(parent, 2), result("BENCH-2", 3800L))
        agent.storeProposal(parent.sessionId,
            proposal(cited = listOf("BENCH-1", "BENCH-2"),
                expectedGen = 200.0),
            "pack", 3)
        val verdict = AgentReview.reviewProposal(reviewInput(
            agent.getParent(parent.sessionId), pendingOf(parent.sessionId)))
        assertEquals(AgentReview.Verdict.CAUTION, verdict.verdict)
        assertEquals(AgentReview.Severity.CAUTION,
            checkOf(verdict, "expected").severity)
    }

    @Test
    fun stagedMismatchIsCautionNotInvalid() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        runChild(childShell(parent, 2), result("BENCH-2", 3800L))
        agent.storeProposal(parent.sessionId,
            proposal(cited = listOf("BENCH-1", "BENCH-2")), "pack", 3)
        val stored = agent.getParent(parent.sessionId)
        val wrong = AgentReview.reviewProposal(
            reviewInput(stored, pendingOf(parent.sessionId),
                staged = OTHER_SHA))
        assertEquals(AgentReview.Verdict.CAUTION, wrong.verdict)
        assertTrue(checkOf(wrong, "identity").detail.contains("differs"))
        val none = AgentReview.reviewProposal(
            reviewInput(stored, pendingOf(parent.sessionId), staged = null))
        assertEquals(AgentReview.Verdict.CAUTION, none.verdict)
        assertTrue(checkOf(none, "identity").detail.contains("no model"))
    }

    @Test
    fun memoryPressureBehindCitedRunIsCaution() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        runChild(childShell(parent, 2), result("BENCH-2", 3800L))
        agent.storeProposal(parent.sessionId,
            proposal(cited = listOf("BENCH-1", "BENCH-2")), "pack", 3)
        val verdict = AgentReview.reviewProposal(reviewInput(
            agent.getParent(parent.sessionId), pendingOf(parent.sessionId),
            memBelow = setOf("BENCH-2"),
            memPct = mapOf("BENCH-2" to 12.4)))
        assertEquals(AgentReview.Verdict.CAUTION, verdict.verdict)
        val check = checkOf(verdict, "memory")
        assertEquals(AgentReview.Severity.CAUTION, check.severity)
        assertTrue(check.detail.contains("12.4%"))
    }

    @Test
    fun unknownCitationIsInvalid() {
        setup()
        val parent = createParent()
        val verdict = AgentReview.reviewProposal(reviewInput(
            parent,
            pendingMap(cited = listOf("BENCH-GHOST"))))
        assertEquals(AgentReview.Verdict.INVALID, verdict.verdict)
        assertEquals(AgentReview.Severity.FAIL,
            checkOf(verdict, "citations").severity)
    }

    @Test
    fun exhaustedBudgetIsInvalid() {
        setup()
        val parent = createParent().copy(budgetUsed = 5, budgetMax = 5)
        val verdict = AgentReview.reviewProposal(
            reviewInput(parent, pendingMap()))
        assertEquals(AgentReview.Verdict.INVALID, verdict.verdict)
        assertEquals(AgentReview.Severity.FAIL,
            checkOf(verdict, "budget").severity)
    }

    @Test
    fun repeatConfigMismatchIsInvalid() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        // Repeat proposes 8 threads; the cited run used 4.
        val verdict = AgentReview.reviewProposal(reviewInput(
            agent.getParent(parent.sessionId),
            pendingMap(
                config = BenchConfig.default().copy(nThreads = 8),
                changed = listOf("n_threads"),
                purpose = AgentApi.PURPOSE_REPEAT,
                cited = listOf("BENCH-1"),
                extra = mapOf("repeat_of_run_id" to "BENCH-1"))))
        assertEquals(AgentReview.Verdict.INVALID, verdict.verdict)
        assertTrue(checkOf(verdict, "purpose").detail.contains("differs"))
    }

    @Test
    fun faithfulRepeatPassesPurposeCheck() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        val stored = agent.getParent(parent.sessionId)
        val verdict = AgentReview.reviewProposal(reviewInput(
            stored,
            pendingMap(
                config = BenchConfig.default(),
                changed = emptyList(),
                purpose = AgentApi.PURPOSE_REPEAT,
                cited = listOf("BENCH-1"),
                extra = mapOf("repeat_of_run_id" to "BENCH-1"))))
        assertEquals(AgentReview.Severity.PASS,
            checkOf(verdict, "purpose").severity)
        // Single observation still cautions overall.
        assertEquals(AgentReview.Verdict.CAUTION, verdict.verdict)
    }

    @Test
    fun recoveryMustCiteAFailedAttempt() {
        setup()
        val parent = createParent()
        val good = AgentReview.reviewProposal(reviewInput(
            parent,
            pendingMap(
                purpose = AgentApi.PURPOSE_RECOVERY,
                cited = listOf("BENCH-F"),
                extra = mapOf("recovery_of_run_id" to "BENCH-F")),
            failures = setOf("BENCH-F")))
        assertEquals(AgentReview.Severity.PASS,
            checkOf(good, "purpose").severity)
        val bad = AgentReview.reviewProposal(reviewInput(
            parent,
            pendingMap(
                purpose = AgentApi.PURPOSE_RECOVERY,
                cited = listOf("BENCH-1"),
                extra = mapOf("recovery_of_run_id" to "BENCH-1")),
            failures = setOf("BENCH-F")))
        assertEquals(AgentReview.Verdict.INVALID, bad.verdict)
        assertEquals(AgentReview.Severity.FAIL,
            checkOf(bad, "purpose").severity)
    }

    @Test
    fun optimizeWithNoChangeIsInvalid() {
        setup()
        val parent = createParent()
        val verdict = AgentReview.reviewProposal(reviewInput(
            parent, pendingMap(
                config = BenchConfig.default(), changed = emptyList())))
        assertEquals(AgentReview.Verdict.INVALID, verdict.verdict)
        assertTrue(checkOf(verdict, "purpose").detail.contains("nothing"))
    }

    @Test
    fun threadsOverDeviceMaxIsInvalid() {
        setup()
        val parent = createParent()
        val verdict = AgentReview.reviewProposal(reviewInput(
            parent, pendingMap(
                config = BenchConfig.default().copy(nThreads = 12),
                changed = listOf("n_threads"))))
        assertEquals(AgentReview.Verdict.INVALID, verdict.verdict)
        assertTrue(checkOf(verdict, "config").detail.contains("max 8"))
    }

    @Test
    fun unreadableConfigIsInvalid() {
        setup()
        val parent = createParent()
        val pending = pendingMap().toMutableMap()
        pending["proposed_config"] = mapOf("nonsense" to true)
        val verdict = AgentReview.reviewProposal(reviewInput(parent, pending))
        assertEquals(AgentReview.Verdict.INVALID, verdict.verdict)
        assertEquals(AgentReview.Severity.FAIL,
            checkOf(verdict, "config").severity)
    }

    @Test
    fun changedKeysMismatchIsInvalid() {
        setup()
        val parent = createParent()
        val verdict = AgentReview.reviewProposal(reviewInput(
            parent, pendingMap(
                config = BenchConfig.default().copy(nThreads = 8),
                changed = listOf("n_ctx"))))
        assertEquals(AgentReview.Verdict.INVALID, verdict.verdict)
        assertEquals(AgentReview.Severity.FAIL,
            checkOf(verdict, "changed_keys").severity)
    }

    @Test
    fun reviewIsDeterministic() {
        setup()
        val parent = createParent()
        val childId = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(childId, result("BENCH-1", 4000L))
        agent.storeProposal(parent.sessionId, proposal(), "pack", 2)
        val stored = agent.getParent(parent.sessionId)
        val pending = pendingOf(parent.sessionId)
        val first = AgentReview.reviewProposal(reviewInput(stored, pending))
        val second = AgentReview.reviewProposal(reviewInput(stored, pending))
        assertEquals(first, second)
    }

    @Test
    fun reviewOverheadStaysSmall() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        runChild(childShell(parent, 2), result("BENCH-2", 3800L))
        agent.storeProposal(parent.sessionId,
            proposal(cited = listOf("BENCH-1", "BENCH-2")), "pack", 3)
        val stored = agent.getParent(parent.sessionId)
        val input = reviewInput(stored, pendingOf(parent.sessionId))
        repeat(20) { AgentReview.reviewProposal(input) }
        val start = System.nanoTime()
        val iterations = 200
        repeat(iterations) { AgentReview.reviewProposal(input) }
        val avgMs = (System.nanoTime() - start) / 1_000_000.0 / iterations
        assertTrue("review too slow: $avgMs ms", avgMs < 10.0)
    }

    private fun pendingMap(
        config: BenchConfigData = BenchConfig.default().copy(nThreads = 8),
        changed: List<String> = listOf("n_threads"),
        purpose: String = AgentApi.PURPOSE_OPTIMIZE,
        cited: List<String> = listOf("BENCH-1"),
        expectedGen: Double = 16.0,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = mutableMapOf<String, Any?>(
        "experiment_seq" to 2,
        "proposed_config" to SessionBenchBridge.configMap(config),
        "changed_keys" to changed,
        "purpose" to purpose,
        "repeat_of_run_id" to null,
        "recovery_of_run_id" to null,
        "expected_gen_tps" to expectedGen,
        "supporting_evidence_ids" to cited
    ).also { it.putAll(extra) }

    // ---- developer modify ----

    @Test
    fun modifyUpdatesConfigAndFlagsExpectedStale() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        agent.storeProposal(parent.sessionId, proposal(), "pack", 2)
        val before = pendingOf(parent.sessionId)["proposal_hash"]
        val updated = agent.updatePendingConfig(
            parent.sessionId, 512, 12, 64, 16)
        assertEquals(0, updated.budgetUsed)
        val pending = pendingOf(parent.sessionId)
        @Suppress("UNCHECKED_CAST")
        val config = pending["proposed_config"] as Map<String, Any?>
        assertEquals(12, (config["n_threads"] as Number).toInt())
        assertEquals(listOf("n_threads"), pending["changed_keys"])
        assertEquals("developer", pending["modified_by"])
        @Suppress("UNCHECKED_CAST")
        val notes = pending["host_notes"] as List<String>
        assertTrue(notes.any { it.contains("developer-modified") })
        assertTrue(notes.any { it.contains("predate the change") })
        assertFalse(pending["proposal_hash"] == before)
    }

    @Test
    fun modifiedProposalDrawsStalenessCaution() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        runChild(childShell(parent, 2), result("BENCH-2", 3800L))
        agent.storeProposal(parent.sessionId,
            proposal(cited = listOf("BENCH-1", "BENCH-2")), "pack", 3)
        agent.updatePendingConfig(parent.sessionId, 512, 6, 64, 16)
        val verdict = AgentReview.reviewProposal(reviewInput(
            agent.getParent(parent.sessionId), pendingOf(parent.sessionId)))
        assertEquals(AgentReview.Verdict.CAUTION, verdict.verdict)
        assertEquals(AgentReview.Severity.CAUTION,
            checkOf(verdict, "modified").severity)
    }

    @Test
    fun modifyRefusesRepeatConfigs() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        agent.storeProposal(
            parent.sessionId,
            proposal(
                config = BenchConfig.default(),
                changed = emptyList(),
                purpose = AgentApi.PURPOSE_REPEAT,
                repeatOf = "BENCH-1"),
            "pack", 2)
        try {
            agent.updatePendingConfig(parent.sessionId, 512, 8, 64, 16)
            fail("repeat modify accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("REPEAT_FIXED", e.code)
        }
    }

    @Test
    fun modifyEnforcesBoundsAndDeviceMax() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        agent.storeProposal(parent.sessionId, proposal(), "pack", 2)
        try {
            agent.updatePendingConfig(parent.sessionId, 99999, 8, 64, 16)
            fail("out-of-range modify accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_CONFIG", e.code)
        }
        try {
            agent.updatePendingConfig(parent.sessionId, 512, 12, 64, 8)
            fail("over-device-max modify accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_CONFIG", e.code)
        }
        try {
            agent.updatePendingConfig(parent.sessionId, 512, 4, 64, 16)
            fail("no-change optimize modify accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("NO_CHANGE", e.code)
        }
        try {
            agent.updatePendingConfig(createParent().sessionId, 512, 8, 64, 16)
            fail("modify without pending accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("NO_PENDING", e.code)
        }
    }

    @Test
    fun modifyToNewPredictRollsCohortNote() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        agent.storeProposal(parent.sessionId, proposal(), "pack", 2)
        agent.updatePendingConfig(parent.sessionId, 512, 8, 32, 16)
        val pending = pendingOf(parent.sessionId)
        @Suppress("UNCHECKED_CAST")
        val notes = pending["host_notes"] as List<String>
        assertTrue(notes.any { it.contains("distinct configuration cohort") })
        assertEquals(listOf("n_predict", "n_threads"), pending["changed_keys"])
    }

    @Test
    fun storeProposalPersistsCritiqueAndVersion() {
        setup()
        val parent = createParent()
        val first = agent.childrenOf(parent.sessionId).single().sessionId
        runChild(first, result("BENCH-1", 4000L))
        val v1 = proposal()
        agent.storeProposal(parent.sessionId, v1, "pack", 2)
        val legacy = pendingOf(parent.sessionId)
        assertNull(legacy["counterargument"])
        assertEquals(1, (legacy["contract_version"] as Number).toInt())
        agent.rejectProposal(parent.sessionId, "mock reject")
        val v2 = v1.copy(
            counterargument = "mock counter",
            alternativeExplanation = "mock alt",
            nextBenchmarkEstablishes = "mock next",
            contractVersion = 2)
        agent.storeProposal(parent.sessionId, v2, "pack", 2)
        val stored = pendingOf(parent.sessionId)
        assertEquals("mock counter", stored["counterargument"])
        assertEquals("mock alt", stored["alternative_explanation"])
        assertEquals("mock next", stored["next_benchmark_establishes"])
        assertEquals(2, (stored["contract_version"] as Number).toInt())
    }

    // ---- cards and proposal text ----

    @Test
    fun proposalSectionsLeadWithReviewWhenSupplied() {
        setup()
        val parent = createParent()
        val pending = pendingMap()
        val plain = InsightCards.proposalSections(
            pending, parent.config, parent.budgetUsed, parent.budgetMax)
        assertEquals("Configuration change", plain.first().title)
        val review = AgentReview.ReviewResult(
            AgentReview.Verdict.CAUTION,
            listOf(AgentReview.Check("evidence",
                AgentReview.Severity.CAUTION, "thin evidence")))
        val reviewed = InsightCards.proposalSections(
            pending, parent.config, parent.budgetUsed, parent.budgetMax,
            review)
        assertEquals("Independent review", reviewed.first().title)
        assertEquals("CAUTION", reviewed.first().rows.first().value)
        assertTrue(reviewed.first().rows.any {
            it.value.contains("thin evidence")
        })
        val rationale = reviewed.first { it.title == "Rationale" }
        assertTrue(rationale.rows.any {
            it.label == "Model self-critique (not verification)" &&
                it.value == "not stated"
        })
    }

    @Test
    fun proposalSectionsShowStoredSelfCritique() {
        setup()
        val parent = createParent()
        val pending = pendingMap(
            extra = mapOf("counterargument" to "mock counter"))
        val sections = InsightCards.proposalSections(
            pending, parent.config, parent.budgetUsed, parent.budgetMax)
        val rationale = sections.first { it.title == "Rationale" }
        assertTrue(rationale.rows.any {
            it.label == "Model self-critique (not verification)" &&
                it.value == "mock counter"
        })
        val tech = sections.first { it.title == "Technical evidence" }
        assertTrue(tech.details!!.contains("contract: v1"))
    }

    @Test
    fun followUpSectionsShowCounterargument() {
        val with = InsightCards.followUpSections(mapOf(
            "provenance" to "live_meta mock",
            "recommendation" to mapOf(
                "direction" to "overestimated",
                "summary" to "mock",
                "supports_conclusion" to false,
                "run_id" to "BENCH-1",
                "counterargument" to "mock counter")))
        assertTrue(with.first().rows.any {
            it.label == "Model self-critique (not verification)" &&
                it.value == "mock counter"
        })
        val without = InsightCards.followUpSections(mapOf(
            "provenance" to "live_meta mock",
            "recommendation" to mapOf(
                "direction" to "overestimated",
                "summary" to "mock",
                "supports_conclusion" to false,
                "run_id" to "BENCH-1")))
        assertFalse(without.first().rows.any {
            it.label == "Model self-critique (not verification)"
        })
    }

    @Test
    fun proposalTextRendersReviewAndCritique() {
        val pending = pendingMap(
            extra = mapOf("counterargument" to "mock counter"))
        val plain = AgentView.proposalText(pending)
        assertFalse(plain.contains("Independent host review"))
        assertTrue(plain.contains("mock counter"))
        val review = AgentReview.ReviewResult(
            AgentReview.Verdict.INVALID,
            listOf(AgentReview.Check("budget",
                AgentReview.Severity.FAIL, "budget exhausted")))
        val reviewed = AgentView.proposalText(pending, review)
        assertTrue(reviewed.contains("Independent host review: INVALID"))
        assertTrue(reviewed.contains("HOST: budget exhausted"))
        assertTrue(reviewed.contains("Approval is blocked"))
        val modified = AgentView.proposalText(
            pendingMap(extra = mapOf("modified_by" to "developer")))
        assertTrue(modified.contains("MODIFIED by developer"))
    }
}
