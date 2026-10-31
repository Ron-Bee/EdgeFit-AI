package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** EF-COMP-01B-PREP: study contract, freeze, preflight, ordering, scoring. */
class EvalStudyTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
        const val CUTOFF = "2026-10-30T00:00:00Z"
        const val NOW = "2026-10-30T12:00:00Z"
    }

    private fun candidateA() = BenchConfig.default().copy(nThreads = 4)
    private fun candidateB() = BenchConfig.default().copy(nThreads = 8)

    private fun constraints() = EvalArms.ArmConstraints(
        modelSha256 = MODEL_SHA,
        modelSizeBytes = 807694464L,
        quantization = "2",
        workloadId = BenchContract.WORKLOAD_ID,
        promptSha256 = PROMPT_SHA,
        runtimeVersion = "0.7.0-dev",
        runtimeAbi = "arm64-v8a",
        nativeVariant = "benchmark",
        deviceMaxThreads = 8,
        memAvailPct = 24.7,
        memLowMemory = false
    )

    private fun expectation(allowed: Set<String> = emptySet()) =
        EvalStudy.StudyExpectation(
            candidateA = candidateA(),
            candidateB = candidateB(),
            modelSha256 = MODEL_SHA,
            workloadId = BenchContract.WORKLOAD_ID,
            allowedRunIds = allowed,
            deviceMaxThreads = 8
        )

    private fun reply(
        selection: String,
        cited: List<String> = emptyList(),
        gen: Double? = null,
        prefill: Double? = null,
        reason: String? = null
    ): Map<String, Any?> = linkedMapOf(
        "model_sha" to MODEL_SHA,
        "workload_id" to BenchContract.WORKLOAD_ID,
        "selection" to selection,
        "abstain_reason" to reason,
        "expected_gen_tps" to gen,
        "expected_prefill_tps" to prefill,
        "uncertainty" to null,
        "supporting_evidence_ids" to cited,
        "limitations" to listOf("synthetic fixture")
    )

    // ---- Task 1: study contract ----

    @Test
    fun selectingBaselineAWithoutChangeIsLegal() {
        // The study has no change-gate: selecting A is a
        // first-class answer, not a production violation.
        val decision = EvalStudy.validateStudyDecision(
            reply("A"), """{"synthetic":"a"}""",
            expectation(), EvalArms.ARM_M0, NOW,
            EvalStudy.TRANSPORT_MOCK)
        assertEquals("A", decision.selection)
        assertEquals(candidateA(), decision.selectedConfig)
        assertNull(decision.abstainReason)
    }

    @Test
    fun studyRepliesValidateIdentitySelectionAndCitations() {
        val allowed = setOf("BENCH-1", "BENCH-2")
        val good = EvalStudy.validateStudyDecision(
            reply("B", cited = listOf("BENCH-1"), gen = 15.0,
                prefill = 22.0),
            """{"synthetic":"b"}""", expectation(allowed),
            EvalArms.ARM_M1, NOW, EvalStudy.TRANSPORT_MOCK)
        assertEquals(candidateB(), good.selectedConfig)
        assertEquals(15.0, good.expectedGenTps!!, 1e-9)
        // M0 allows no citations: any citation refuses.
        try {
            EvalStudy.validateStudyDecision(
                reply("A", cited = listOf("BENCH-1")),
                """{"synthetic":"m0cite"}""", expectation(),
                EvalArms.ARM_M0, NOW, EvalStudy.TRANSPORT_MOCK)
            fail("M0 citation accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_CITATIONS", e.code)
        }
        // Unknown selection, identity mismatch, and
        // abstain-with-estimates all refuse.
        for ((bad, code) in listOf(
            reply("C") to "STUDY_SELECTION",
            reply("A").toMutableMap().also {
                it["model_sha"] = "00".repeat(32)
            } to "STUDY_IDENTITY",
            reply("abstain", reason = "thin evidence",
                gen = 9.0) to "STUDY_ABSTAIN",
            reply("abstain") to "STUDY_ABSTAIN"
        )) {
            try {
                EvalStudy.validateStudyDecision(bad,
                    """{"synthetic":"bad"}""", expectation(allowed),
                    EvalArms.ARM_M1, NOW, EvalStudy.TRANSPORT_MOCK)
                fail("bad reply accepted: $bad")
            } catch (e: EvalStudy.StudyError) {
                assertEquals(code, e.code)
            }
        }
        val abstain = EvalStudy.validateStudyDecision(
            reply("abstain", reason = "no basis"),
            """{"synthetic":"abs"}""", expectation(allowed),
            EvalArms.ARM_M0, NOW, EvalStudy.TRANSPORT_MOCK)
        assertNull(abstain.selectedConfig)
        assertEquals("no basis", abstain.abstainReason)
    }

    @Test
    fun studyDecisionsAreNotProductionProposals() {
        // Type-level separation, asserted behaviorally: a
        // study decision carries no seq/budget/child and
        // production's optimize change-gate still refuses
        // no-change proposals (production NOT weakened).
        val study = EvalStudy.validateStudyDecision(
            reply("A"), """{"synthetic":"a"}""",
            expectation(), EvalArms.ARM_M0, NOW,
            EvalStudy.TRANSPORT_MOCK)
        // The study decision's runtime type is NOT the
        // production proposal type: no seq, budget, or
        // session linkage can cross this boundary.
        assertEquals(EvalStudy.StudyDecision::class.java, study.javaClass)
        try {
            AgentApi.validateProposal(
                mapOf(
                    "model_sha" to MODEL_SHA,
                    "workload_id" to BenchContract.WORKLOAD_ID,
                    "experiment_seq" to 2,
                    "current_config" to mapOf("n_ctx" to 512,
                        "n_threads" to 4, "n_predict" to 64),
                    "propose_experiment" to true,
                    "proposed_config" to mapOf("n_threads" to 4),
                    "hypothesis" to "synthetic",
                    "expected_direction" to "faster",
                    "expected_gen_tps" to 15.0,
                    "expected_prefill_tps" to 20.0,
                    "uncertainty" to "synthetic",
                    "supporting_evidence_ids" to listOf("BENCH-1"),
                    "limitations" to listOf("synthetic"),
                    "benefit" to "synthetic",
                    "no_experiment_reason" to null
                ),
                """{"synthetic":"nochange"}""",
                AgentApi.ProposalExpectation(
                    modelSha = MODEL_SHA,
                    workloadId = BenchContract.WORKLOAD_ID,
                    currentConfig = candidateA(),
                    nextSeq = 2,
                    allowedRunIds = setOf("BENCH-1"),
                    deviceMaxThreads = 8
                )
            )
            fail("production no-change optimize accepted")
        } catch (e: AgentApi.AgentError) {
            assertTrue(e.message!!.contains("changes nothing"))
        }
    }

    @Test
    fun productionProposalGateStillNeedsEvidence() {
        val manager = SessionManager(temp.newFolder()) { NOW }
        val agent = AgentManager(manager) { NOW }
        val parent = agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            baseConfig = BenchConfig.default())
        // No completed or attempted experiment: the study
        // must NOT route through this gate, and the gate
        // must NOT open for it.
        val gate = agent.proposalGateEx(parent.sessionId)
        assertTrue(!gate.ok)
        assertTrue(gate.reason.contains("no comparable evidence"))
    }

    // ---- Task 2: freeze ----

    private fun artifactJson(
        runId: String,
        gen: Double = 14.0,
        prefill: Double = 22.0,
        collected: String = "2026-10-27T17:00:02Z",
        variant: String? = "benchmark",
        status: String = BenchContract.STATUS_COMPLETED,
        modelSha: String = MODEL_SHA,
        thermalAfter: String = "none",
        threads: Int = 4
    ): String = SessionJson.stringify(linkedMapOf(
        "contract" to BenchContract.CONTRACT,
        "contract_version" to BenchContract.CONTRACT_VERSION,
        "run_id" to runId,
        "model" to linkedMapOf(
            "display_name" to "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "size_bytes" to 807694464L,
            "sha256" to modelSha,
            "quantization" to "2"),
        "runtime" to linkedMapOf(
            "impl" to BenchContract.RUNTIME_IMPL,
            "version" to "0.7.0-dev",
            "backend" to BenchContract.BACKEND,
            "abi" to "arm64-v8a"),
        "config" to SessionBenchBridge.configMap(
            BenchConfig.default().copy(nThreads = threads)),
        "workload" to linkedMapOf(
            "id" to BenchContract.WORKLOAD_ID,
            "prompt_sha256" to PROMPT_SHA,
            "prompt_chars" to 118),
        "measurements" to linkedMapOf(
            "tps_gen" to gen,
            "tps_prefill" to prefill,
            "thermal" to linkedMapOf(
                "before" to "none", "mid" to "none",
                "after" to thermalAfter, "available" to true)),
        "status" to status,
        "collected_at_utc" to collected,
        "x_native_build" to variant?.let {
            linkedMapOf("native_opt" to "-O3 -g", "variant" to it)
        }
    ))

    private fun artifact(
        corpusId: String,
        json: String,
        sha: String? = null
    ): EvalStudy.ArtifactInput {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return EvalStudy.ArtifactInput(corpusId,
            sha ?: BenchConfig.sha256Hex(bytes), bytes)
    }

    @Test
    fun freezeAdmitsQualifiedSetDeterministically() {
        val offered = listOf(
            artifact("run-x", artifactJson("BENCH-20261027T170002Z-aaaa0001",
                gen = 14.0)),
            artifact("run-y", artifactJson("BENCH-20261027T203446Z-bbbb0002",
                gen = 16.0, thermalAfter = "light"))
        )
        val first = EvalStudy.freezeStudy(constraints(), candidateA(),
            candidateB(), offered, CUTOFF, NOW)
        val second = EvalStudy.freezeStudy(constraints(), candidateA(),
            candidateB(), offered.reversed(), CUTOFF, NOW)
        assertEquals(first.manifestHash, second.manifestHash)
        assertEquals(2, first.admitted.size)
        assertEquals(
            first.admitted.map { it.runId }, first.m0WithheldRunIds)
        assertEquals(64, first.m0PackHash.length)
        assertEquals(64, first.m1PackHash.length)
        assertTrue(first.m1Exclusions.isEmpty())
        // Round-trip: re-parse verifies the hash.
        val parsed = EvalStudy.parseManifest(
            EvalStudy.manifestJson(first))
        assertEquals(first.manifestHash, parsed.manifestHash)
        assertEquals(first.admitted.map { it.runId },
            parsed.admitted.map { it.runId })
        // Tamper with one byte: the hash check refuses.
        val tampered = EvalStudy.manifestJson(first)
            .replace("14.0", "94.0")
        try {
            EvalStudy.parseManifest(tampered)
            fail("tampered manifest accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_MANIFEST", e.code)
        }
    }

    @Test
    fun freezeRejectsEveryUnqualifiedOffer() {
        val good = artifactJson("BENCH-20261027T170002Z-aaaa0001")
        val cases = listOf(
            // Hash mismatch (bytes changed after manifest).
            artifact("tampered", good,
                sha = "00".repeat(32)) to "STUDY_HASH",
            artifact("corrupt", "{not json") to "STUDY_CORRUPT",
            artifact("contract", good.replace(
                BenchContract.CONTRACT, "other")) to "STUDY_CONTRACT",
            artifact("failed", artifactJson(
                "BENCH-20261027T170002Z-aaaa0001",
                gen = 14.0).replace(
                BenchContract.STATUS_COMPLETED, "FAILED")) to "STUDY_STATUS",
            artifact("model", artifactJson(
                "BENCH-20261027T170002Z-aaaa0001",
                modelSha = "00".repeat(32))) to "STUDY_IDENTITY",
            // Run-a class: Debug record without build provenance.
            artifact("debug", artifactJson(
                "BENCH-20261027T170002Z-aaaa0001",
                variant = null)) to "STUDY_BUILD",
            artifact("variant", artifactJson(
                "BENCH-20261027T170002Z-aaaa0001",
                variant = "debug")) to "STUDY_BUILD",
            // Future observation (at/after the identical cutoff).
            artifact("future", artifactJson(
                "BENCH-20261027T170002Z-aaaa0001",
                collected = CUTOFF)) to "STUDY_TIME",
            artifact("thermal", artifactJson(
                "BENCH-20261027T170002Z-aaaa0001",
                thermalAfter = "")) to "STUDY_THROUGHPUT",
            artifact("tps", good.replace(
                "\"tps_gen\":14.0",
                "\"tps_gen\":\"fast\"")) to "STUDY_THROUGHPUT"
        )
        for ((bad, code) in cases) {
            try {
                EvalStudy.freezeStudy(constraints(), candidateA(),
                    candidateB(), listOf(bad), CUTOFF, NOW)
                fail("unqualified offer accepted: ${bad.corpusId}")
            } catch (e: EvalStudy.StudyError) {
                assertEquals("STUDY_FREEZE", e.code)
                assertTrue("missing $code in ${e.message}",
                    e.message!!.contains(code))
            }
        }
        // Duplicates fail the whole freeze, listing both.
        val dup = artifact("dup", good)
        try {
            EvalStudy.freezeStudy(constraints(), candidateA(),
                candidateB(), listOf(dup, dup.copy(corpusId = "dup2")),
                CUTOFF, NOW)
            fail("duplicate offer accepted")
        } catch (e: EvalStudy.StudyError) {
            assertTrue(e.message!!.contains("duplicate run id"))
            assertTrue(e.message!!.contains("duplicate artifact"))
        }
        // Degenerate study shapes refuse.
        try {
            EvalStudy.freezeStudy(constraints(), candidateA(),
                candidateB(), emptyList(), CUTOFF, NOW)
            fail("empty offer accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_FREEZE", e.code)
        }
        try {
            EvalStudy.freezeStudy(constraints(), candidateA(),
                candidateA(), listOf(artifact("x", good)), CUTOFF, NOW)
            fail("identical candidates accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_CANDIDATE", e.code)
        }
    }

    // ---- Task 3: preflight + transports ----

    private fun preflightInput(
        artifacts: List<EvalStudy.ArtifactInput>
    ) = EvalStudy.PreflightInput(
        constraints = constraints(),
        candidateA = candidateA(),
        candidateB = candidateB(),
        artifacts = artifacts,
        evidenceCutoffUtc = CUTOFF,
        nowUtc = NOW
    )

    private fun twoRunOffer() = listOf(
        artifact("run-x", artifactJson("BENCH-20261027T170002Z-aaaa0001",
            gen = 14.0, prefill = 22.0)),
        artifact("run-y", artifactJson("BENCH-20261027T203446Z-bbbb0002",
            gen = 16.0, prefill = 24.0, thermalAfter = "light"))
    )

    @Test
    fun preflightFreezesDecidesRAndBuildsPayloads() {
        val out = EvalStudy.runPreflight(preflightInput(twoRunOffer()))
        assertEquals(EvalStudy.VERDICT_READY, out.verdict)
        assertTrue(out.problems.isEmpty())
        val manifest = EvalStudy.parseManifest(out.manifestJson!!)
        assertEquals(2, manifest.admitted.size)
        // R selects measured A with the sample median.
        val r = SessionJson.parse(out.decisionRJson!!) as Map<String, Any?>
        assertEquals("R", r["arm"])
        assertEquals("A", r["selection"])
        assertEquals(EvalStudy.TRANSPORT_OFFLINE, r["transport"])
        assertEquals(15.0, (r["expected_gen_tps"] as Number).toDouble(),
            1e-9)
        // M0 payload carries constraints but NO
        // measurements; M1 carries the manifested runs.
        assertTrue(out.requestM0Json!!.contains("api.meta.ai"))
        val m0body = (SessionJson.parse(out.requestM0Json!!)
            as Map<String, Any?>)["body"] as String
        assertTrue(!m0body.contains("aaaa0001"))
        assertTrue(!m0body.contains("14.0"))
        assertTrue(m0body.contains("withheld"))
        val m1body = (SessionJson.parse(out.requestM1Json!!)
            as Map<String, Any?>)["body"] as String
        assertTrue(m1body.contains("aaaa0001"))
        assertTrue(m1body.contains("bbbb0002"))
        assertTrue(out.promptsText!!.contains("CANDIDATE_A"))
    }

    @Test
    fun preflightBlocksOnStudyProblems() {
        val out = EvalStudy.runPreflight(preflightInput(listOf(
            artifact("bad", "{not json}")
        )))
        assertEquals(EvalStudy.VERDICT_BLOCKED, out.verdict)
        assertTrue(out.problems.single().contains("STUDY_CORRUPT"))
        assertNull(out.manifestJson)
        assertNull(out.decisionRJson)
    }

    @Test
    fun disabledTransportRefusesLiveCalls() {
        try {
            EvalStudy.DisabledStudyTransport.post("key", "{}")
            fail("disabled transport posted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("LIVE_NOT_AUTHORIZED", e.code)
        }
    }

    @Test
    fun mockTransportRecordsLabeledSyntheticDecisions() {
        val canned = SessionJson.stringify(linkedMapOf(
            "id" to "resp_synthetic_1",
            "output_text" to SessionJson.stringify(reply("B",
                cited = listOf("BENCH-20261027T170002Z-aaaa0001"),
                gen = 18.0, prefill = 26.0)),
            "usage" to linkedMapOf(
                "input_tokens" to 100, "output_tokens" to 40)
        ))
        val decision = EvalStudy.recordArmDecision(
            EvalArms.ARM_M1, "{}", expectation(setOf(
                "BENCH-20261027T170002Z-aaaa0001")),
            EvalStudy.MockStudyTransport(canned), "mock-key", { NOW },
            EvalStudy.TRANSPORT_MOCK, latencyMs = 1234L)
        assertEquals("B", decision.selection)
        assertEquals(EvalStudy.TRANSPORT_MOCK, decision.transport)
        assertEquals("resp_synthetic_1", decision.providerRequestId)
        assertEquals(100, decision.promptTokens)
        assertEquals(40, decision.completionTokens)
        assertEquals(1234L, decision.latencyMs)
        // A reply violating the contract refuses even via mock.
        val badCanned = SessionJson.stringify(linkedMapOf(
            "output_text" to SessionJson.stringify(reply("C"))))
        try {
            EvalStudy.recordArmDecision(
                EvalArms.ARM_M1, "{}", expectation(),
                EvalStudy.MockStudyTransport(badCanned), "mock-key",
                { NOW }, EvalStudy.TRANSPORT_MOCK)
            fail("contract-violating reply accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_SELECTION", e.code)
        }
    }

    // ---- Task 5: START ordering ----

    @Test
    fun runStartStampsParseToUtc() {
        assertEquals("2026-10-27T17:00:02Z",
            EvalStudy.runStartUtc("BENCH-20261027T170002Z-36227bc0"))
        assertNull(EvalStudy.runStartUtc("BENCH-NOT-A-STAMP"))
        assertNull(EvalStudy.runStartUtc(""))
    }

    private fun studyDecision(
        arm: String,
        selection: String,
        decidedAt: String
    ) = EvalStudy.StudyDecision(
        arm = arm, selection = selection,
        selectedConfig = if (selection == "A") candidateA()
        else if (selection == "B") candidateB() else null,
        abstainReason = if (selection == "abstain") "thin" else null,
        expectedGenTps = 15.0, expectedPrefillTps = null,
        uncertainty = null, supportingEvidenceIds = emptyList(),
        limitations = emptyList(), rawJson = "{}",
        decidedAtUtc = decidedAt, transport = EvalStudy.TRANSPORT_MOCK
    )

    private fun newRun(
        runId: String,
        collected: String,
        threads: Int = 4,
        gen: Double = 15.0,
        status: String = BenchContract.STATUS_COMPLETED
    ) = EvalStudy.NewRun(
        runId = runId,
        artifactSha256 = "ab".repeat(32),
        config = BenchConfig.default().copy(nThreads = threads),
        genTps = gen, prefillTps = 23.0, thermalAfter = "none",
        collectedAtUtc = collected, status = status
    )

    @Test
    fun orderingComparesDecisionsAgainstRunStart() {
        val decisions = listOf(
            studyDecision("R", "A", "2026-10-30T12:00:00Z"),
            studyDecision("M0", "B", "2026-10-30T12:05:00Z"))
        // Clean: both decisions predate the START.
        val ok = EvalStudy.verifyRunOrdering(decisions, listOf(
            newRun("BENCH-20261030T130000Z-aaaa0001",
                "2026-10-30T13:00:00Z")))
        assertTrue(ok.single().ok)
        // Same-second decision proves nothing: BLOCKED.
        val tied = EvalStudy.verifyRunOrdering(
            listOf(studyDecision("R", "A", "2026-10-30T13:00:00Z")),
            listOf(newRun("BENCH-20261030T130000Z-aaaa0001",
                "2026-10-30T13:00:00Z")))
        assertTrue(!tied.single().ok)
        assertTrue(tied.single().detail.contains("not before START"))
        // Inversion: BLOCKED with the offending arm named.
        val inverted = EvalStudy.verifyRunOrdering(
            listOf(studyDecision("M1", "A", "2026-10-30T14:00:00Z")),
            listOf(newRun("BENCH-20261030T130000Z-aaaa0001",
                "2026-10-30T13:00:00Z")))
        assertTrue(!inverted.single().ok)
        assertTrue(inverted.single().detail.contains("M1@"))
        // Collection stamp far from the START stamp means
        // corrupted evidence, not a slow run.
        val skewed = EvalStudy.verifyRunOrdering(decisions, listOf(
            newRun("BENCH-20261030T130000Z-aaaa0001",
                "2026-10-30T15:00:00Z")))
        assertTrue(!skewed.single().ok)
        assertTrue(skewed.single().detail.contains("inconsistent"))
        // Unparseable run id: no verifiable START.
        val stub = EvalStudy.verifyRunOrdering(decisions, listOf(
            newRun("BENCH-???", "2026-10-30T13:00:00Z")))
        assertTrue(!stub.single().ok)
    }

    // ---- Task 6: prospective scoring ----

    private fun frozenManifest(): EvalStudy.StudyManifest =
        EvalStudy.freezeStudy(constraints(), candidateA(),
            candidateB(), twoRunOffer(), CUTOFF, NOW)

    @Test
    fun scoringReportsMediansSpreadRegretAndErrors() {
        val manifest = frozenManifest()
        val decisions = listOf(
            studyDecision("R", "A", NOW),
            studyDecision("M0", "B", NOW).copy(expectedGenTps = 18.0),
            studyDecision("M1", "A", NOW).copy(expectedGenTps = 14.5))
        val runs = listOf(
            newRun("BENCH-20261030T130000Z-aaaa0001",
                "2026-10-30T13:00:00Z", threads = 4, gen = 15.0),
            newRun("BENCH-20261030T131000Z-aaaa0002",
                "2026-10-30T13:10:00Z", threads = 4, gen = 17.0),
            newRun("BENCH-20261030T132000Z-aaaa0003",
                "2026-10-30T13:20:00Z", threads = 4, gen = 16.0),
            newRun("BENCH-20261030T133000Z-bbbb0001",
                "2026-10-30T13:30:00Z", threads = 8, gen = 12.0),
            newRun("BENCH-20261030T134000Z-bbbb0002",
                "2026-10-30T13:40:00Z", threads = 8, gen = 13.0),
            newRun("BENCH-20261030T135000Z-bbbb0003",
                "2026-10-30T13:50:00Z", threads = 8, gen = 12.5))
        val score = EvalStudy.scoreProspective(manifest, decisions, runs)
        assertEquals(16.0, score.candidateA.medianGen.value!!, 1e-9)
        assertEquals(3, score.candidateA.n)
        assertEquals(15.0, score.candidateA.minGen!!, 1e-9)
        assertEquals(17.0, score.candidateA.maxGen!!, 1e-9)
        assertEquals(12.5, score.candidateB.medianGen.value!!, 1e-9)
        assertTrue(score.excludedRuns.isEmpty())
        // R and M1 picked the better median (regret 0);
        // M0 picked B (regret 16 - 12.5 = 3.5), each a
        // single study-level observation (n=1).
        assertEquals(0.0, score.regretByArm["R"]!!.value!!, 1e-9)
        assertEquals(1, score.regretByArm["R"]!!.n)
        assertEquals(3.5, score.regretByArm["M0"]!!.value!!, 1e-9)
        assertEquals(1, score.regretByArm["M0"]!!.n)
        // Decision level: exactly one case per arm.
        assertEquals(1, score.decisionReports["M0"]!!.n)
        // Run level: errors pair each decision against
        // runs of its selected config only.
        assertEquals(3, score.errorReports["M0"]!!.meanAbsErrorTps.n)
        assertTrue(score.labels.any { it.contains("exploratory") })
        // Study decisions authorize nothing: rejection
        // evidence is N/A, so enforcement stays
        // NOT_EVALUABLE rather than 0% or 100%.
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            score.decisionReports["R"]!!.hostRejectionRate.status)
    }

    @Test
    fun abstentionIsNeverScoredAsAFailedSelection() {
        val manifest = frozenManifest()
        val score = EvalStudy.scoreProspective(
            manifest,
            listOf(studyDecision("M0", "abstain", NOW)),
            listOf(newRun("BENCH-20261030T130000Z-aaaa0001",
                "2026-10-30T13:00:00Z", gen = 15.0)))
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            score.regretByArm["M0"]!!.status)
        assertEquals(1.0, score.decisionReports["M0"]!!
            .abstentionRate.value!!, 1e-9)
        assertTrue(score.labels.any {
            it.contains("abstention is not a failed selection")
        })
    }

    @Test
    fun scoringExcludesUnscorableRunsWithReasons() {
        val manifest = frozenManifest()
        val decisions = listOf(studyDecision("R", "A", NOW))
        val score = EvalStudy.scoreProspective(manifest, decisions, listOf(
            newRun("BENCH-20261030T130000Z-aaaa0001",
                "2026-10-30T13:00:00Z", gen = 15.0),
            // FAILED: no measurement.
            newRun("BENCH-20261030T131000Z-aaaa0002",
                "2026-10-30T13:10:00Z", status = "FAILED"),
            // Predates the freeze: cannot be a study outcome.
            newRun("BENCH-20261029T130000Z-aaaa0003",
                "2026-10-29T13:00:00Z"),
            // Unregistered config: outside {A, B}.
            newRun("BENCH-20261030T132000Z-aaaa0004",
                "2026-10-30T13:20:00Z", threads = 2),
            // Unusable throughput.
            newRun("BENCH-20261030T133000Z-aaaa0005",
                "2026-10-30T13:30:00Z", gen = -1.0)
        ))
        assertEquals(4, score.excludedRuns.size)
        assertTrue(score.excludedRuns.any { it.contains("not COMPLETED") })
        assertTrue(score.excludedRuns.any { it.contains("postdate freeze") })
        assertTrue(score.excludedRuns.any { it.contains("unregistered") })
        assertTrue(score.excludedRuns.any { it.contains("unusable") })
        // B unmeasured: median NOT_EVALUABLE, regret needs
        // two measured medians so it withholds too.
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            score.candidateB.medianGen.status)
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            score.regretByArm["R"]!!.status)
    }

    // ---- FINAL Task 2: freeze verification ----

    private fun frozenFiles(): EvalStudy.FrozenFileSet {
        val out = EvalStudy.runPreflight(preflightInput(twoRunOffer()))
        assertEquals(EvalStudy.VERDICT_READY, out.verdict)
        val files = EvalStudy.FrozenFileSet(
            manifestJson = out.manifestJson!!,
            decisionRJson = out.decisionRJson!!,
            requestM0Json = out.requestM0Json!!,
            requestM1Json = out.requestM1Json!!,
            verdictJson = SessionJson.stringify(linkedMapOf(
                "study_id" to EvalStudy.STUDY_ID,
                "verdict" to EvalStudy.VERDICT_READY,
                "artifacts" to linkedMapOf(
                    "study-manifest.json" to BenchConfig.sha256Hex(
                        out.manifestJson!!.toByteArray(Charsets.UTF_8)),
                    "study-decision-r.json" to BenchConfig.sha256Hex(
                        out.decisionRJson!!.toByteArray(Charsets.UTF_8)),
                    "study-request-m0.json" to BenchConfig.sha256Hex(
                        out.requestM0Json!!.toByteArray(Charsets.UTF_8)),
                    "study-request-m1.json" to BenchConfig.sha256Hex(
                        out.requestM1Json!!.toByteArray(Charsets.UTF_8)),
                    "study-prompts.txt" to BenchConfig.sha256Hex(
                        out.promptsText!!.toByteArray(Charsets.UTF_8))))),
            promptsText = out.promptsText)
        assertTrue(EvalStudy.verifyFrozenStudy(files).isEmpty())
        return files
    }

    @Test
    fun frozenStudyVerificationDetectsTampering() {
        val clean = frozenFiles()
        // Manifest byte edit: hash check fires.
        assertTrue(EvalStudy.verifyFrozenStudy(
            clean.copy(manifestJson = clean.manifestJson
                .replace("14.0", "94.0")))
            .any { it.contains("manifest:") })
        // Decision selection flip: re-derivation disagrees.
        assertTrue(EvalStudy.verifyFrozenStudy(
            clean.copy(decisionRJson = clean.decisionRJson
                .replace("\"selection\":\"A\"",
                    "\"selection\":\"B\"")))
            .any { it.contains("decision-r:") })
        // Request body edit: body hash fires.
        assertTrue(EvalStudy.verifyFrozenStudy(
            clean.copy(requestM0Json = clean.requestM0Json
                .replace("CANDIDATE_A", "CANDIDATE_X")))
            .any { it.contains("body hash") })
        // Endpoint swap refuses even with recomputed hash.
        val swapped = clean.requestM0Json
            .replace(MetaApi.ENDPOINT, "https://evil.example/v1/responses")
        assertTrue(EvalStudy.verifyFrozenStudy(
            clean.copy(requestM0Json = swapped))
            .any { it.contains("endpoint") })
        // M0 contamination: inject an admitted run id and
        // re-hash the wrapper so ONLY the sweep fires.
        val dirtyBody = (SessionJson.parse(clean.requestM0Json)
            as Map<String, Any?>)["body"] as String +
            " BENCH-20261027T170002Z-aaaa0001"
        val dirtyReq = SessionJson.stringify(linkedMapOf(
            "study_id" to EvalStudy.STUDY_ID,
            "arm" to EvalArms.ARM_M0,
            "endpoint" to MetaApi.ENDPOINT,
            "model" to MetaApi.MODEL,
            "built_at_utc" to NOW,
            "body_sha256" to BenchConfig.sha256Hex(
                dirtyBody.toByteArray(Charsets.UTF_8)),
            "body" to dirtyBody))
        assertTrue(EvalStudy.verifyFrozenStudy(
            clean.copy(requestM0Json = dirtyReq))
            .any { it.contains("leaks run id") })
    }

    @Test
    fun outputDirGuardsProtectExistingStudies() {
        val fresh = temp.newFolder("fresh")
        assertNull(EvalStudy.checkOutputDir(fresh, false, null))
        val study = temp.newFolder("study")
        java.io.File(study, "study-verdict.json").writeText("{}")
        assertTrue(EvalStudy.checkOutputDir(study, false, null)!!
            .contains("immutable"))
        // Even an explicit new instance never writes into
        // an existing study directory.
        assertTrue(EvalStudy.checkOutputDir(study, true, "x")!!
            .contains("immutable"))
        val cluttered = temp.newFolder("cluttered")
        java.io.File(cluttered, "notes.txt").writeText("x")
        assertTrue(EvalStudy.checkOutputDir(cluttered, false, null)!!
            .contains("EF_STUDY_NEW_INSTANCE"))
        assertTrue(EvalStudy.checkOutputDir(cluttered, true, null)!!
            .contains("EF_STUDY_INSTANCE_ID"))
        assertNull(EvalStudy.checkOutputDir(cluttered, true, "s2"))
    }

    // ---- FINAL Task 3: live capture paths (offline) ----

    @Test
    fun decisionTimestampIsReadAfterValidation() {
        val events = mutableListOf<String>()
        val transport = object : EvalStudy.StudyTransport {
            override fun post(
                apiKey: String,
                body: String
            ): EvalStudy.StudyHttpResult {
                events.add("post")
                return EvalStudy.StudyHttpResult(200,
                    SessionJson.stringify(linkedMapOf(
                        "output_text" to SessionJson.stringify(
                            reply("A")))))
            }
        }
        val decision = EvalStudy.recordArmDecision(
            EvalArms.ARM_M0, "{}", expectation(), transport,
            "mock-key", { events.add("clock"); NOW },
            EvalStudy.TRANSPORT_MOCK)
        assertEquals(NOW, decision.decidedAtUtc)
        // POST first, clock only after receipt+validation.
        assertEquals(listOf("post", "clock", "clock"), events)
    }

    @Test
    fun frozenRequestLoadingEnforcesIdentity() {
        val out = EvalStudy.runPreflight(preflightInput(twoRunOffer()))
        val manifest = EvalStudy.parseManifest(out.manifestJson!!)
        val frozen = EvalStudy.loadFrozenRequest(
            out.requestM1Json!!, EvalArms.ARM_M1, manifest)
        assertEquals(MetaApi.MODEL, frozen.model)
        // Body edit without hash update: caught.
        try {
            EvalStudy.loadFrozenRequest(
                out.requestM1Json!!.replace(
                    "CANDIDATE_A", "CANDIDATE_X"),
                EvalArms.ARM_M1, manifest)
            fail("edited body accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_REQUEST", e.code)
        }
        // Body from another manifest: unbound.
        val other = EvalStudy.freezeStudy(constraints(), candidateA(),
            candidateB(),
            listOf(twoRunOffer().first().copy(corpusId = "solo")),
            CUTOFF, NOW)
        try {
            EvalStudy.loadFrozenRequest(
                out.requestM1Json!!, EvalArms.ARM_M1, other)
            fail("unbound body accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_REQUEST", e.code)
        }
    }

    @Test
    fun armCitationRechecksCatchForgery() {
        val manifest = frozenManifest()
        val admitted = manifest.admitted.first().runId
        val m0clean = studyDecision("M0", "A", NOW)
        assertTrue(EvalStudy.verifyArmCitations(m0clean, manifest)
            .isEmpty())
        val m0dirty = m0clean.copy(
            supportingEvidenceIds = listOf(admitted))
        assertTrue(EvalStudy.verifyArmCitations(m0dirty, manifest)
            .single().contains("M0 cites"))
        val m1good = studyDecision("M1", "A", NOW).copy(
            supportingEvidenceIds = listOf(admitted))
        assertTrue(EvalStudy.verifyArmCitations(m1good, manifest)
            .isEmpty())
        val m1forged = m1good.copy(
            supportingEvidenceIds = listOf("BENCH-FORGED"))
        assertTrue(EvalStudy.verifyArmCitations(m1forged, manifest)
            .single().contains("unmanifested"))
    }

    // ---- FINAL Task 4: qualified scoring ----

    private fun liveDecision(
        arm: String,
        selection: String,
        decidedAt: String,
        manifest: EvalStudy.StudyManifest,
        cited: List<String> = emptyList(),
        gen: Double? = 15.0,
        cost: Double? = null,
        latency: Long? = null
    ): String {
        val payload = reply(selection, cited = cited, gen = gen,
            reason = if (selection == "abstain") "thin" else null)
        val raw = SessionJson.stringify(payload)
        val allowed = if (arm == EvalArms.ARM_M0) {
            emptySet()
        } else {
            manifest.admitted.map { it.runId }.toSet()
        }
        val decision = EvalStudy.validateStudyDecision(
            payload, raw,
            EvalStudy.StudyExpectation(
                candidateA = manifest.candidateA,
                candidateB = manifest.candidateB,
                modelSha256 = manifest.constraints.modelSha256,
                workloadId = manifest.constraints.workloadId,
                allowedRunIds = allowed,
                deviceMaxThreads =
                    manifest.constraints.deviceMaxThreads),
            arm, decidedAt, EvalStudy.TRANSPORT_LIVE)
            .copy(costUsd = cost, latencyMs = latency)
        return EvalStudy.decisionJson(decision)
    }

    private fun sixRunInputs(): List<EvalStudy.StudyRunInput> {
        val specs = listOf(
            Triple("BENCH-20261030T130000Z-aaaa0001",
                "2026-10-30T13:00:00Z", 4 to 15.0),
            Triple("BENCH-20261030T131000Z-bbbb0001",
                "2026-10-30T13:10:00Z", 8 to 12.0),
            Triple("BENCH-20261030T132000Z-bbbb0002",
                "2026-10-30T13:20:00Z", 8 to 13.0),
            Triple("BENCH-20261030T133000Z-aaaa0002",
                "2026-10-30T13:30:00Z", 4 to 17.0),
            Triple("BENCH-20261030T134000Z-aaaa0003",
                "2026-10-30T13:40:00Z", 4 to 16.0),
            Triple("BENCH-20261030T135000Z-bbbb0003",
                "2026-10-30T13:50:00Z", 8 to 12.5)
        )
        return specs.mapIndexed { i, (runId, collected, config) ->
            val (threads, gen) = config
            EvalStudy.StudyRunInput("run-$i",
                artifactJson(runId, gen = gen, collected = collected,
                    threads = threads)
                    .toByteArray(Charsets.UTF_8))
        }
    }

    private fun qualifiedScore(): EvalStudy.StudyScore {
        val out = EvalStudy.runPreflight(preflightInput(twoRunOffer()))
        val manifest = EvalStudy.parseManifest(out.manifestJson!!)
        return EvalStudy.scoreStudy(
            out.manifestJson!!,
            listOf(
                out.decisionRJson!!,
                liveDecision("M0", "B", "2026-10-30T12:05:00Z",
                    manifest, gen = 18.0, cost = 0.05,
                    latency = 2000L),
                liveDecision("M1", "A", "2026-10-30T12:10:00Z",
                    manifest,
                    cited = listOf(manifest.admitted.first().runId),
                    gen = 14.5, cost = 0.07, latency = 3000L)
            ),
            sixRunInputs())
    }

    @Test
    fun qualifiedScoringIngestsSourceBytesEndToEnd() {
        val score = qualifiedScore()
        assertEquals(16.0, score.candidateA.medianGen.value!!, 1e-9)
        assertEquals(12.5, score.candidateB.medianGen.value!!, 1e-9)
        assertEquals(0.0, score.regretByArm["M1"]!!.value!!, 1e-9)
        assertEquals(3.5, score.regretByArm["M0"]!!.value!!, 1e-9)
        // One cost/latency record per provider call: M0's
        // single $0.05 call is not tripled over 3 runs.
        val m0 = score.decisionReports["M0"]!!
        assertEquals(0.05, m0.totalCostUsd.value!!, 1e-9)
        assertEquals(1, m0.totalCostUsd.n)
        assertEquals(2000.0, m0.meanLatencyMs.value!!, 1e-9)
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            score.errorReports["M0"]!!.totalCostUsd.status)
        // Six trials are not six decisions.
        assertEquals(1, m0.n)
        assertEquals(3, score.errorReports["M0"]!!.n)
    }

    @Test
    fun qualifiedScoringBlocksOnBadArtifacts() {
        val out = EvalStudy.runPreflight(preflightInput(twoRunOffer()))
        val manifest = EvalStudy.parseManifest(out.manifestJson!!)
        val m0 = liveDecision("M0", "B", "2026-10-30T12:05:00Z",
            manifest, gen = 18.0)
        val m1 = liveDecision("M1", "A", "2026-10-30T12:10:00Z",
            manifest, cited = listOf(manifest.admitted.first().runId),
            gen = 14.5)
        val decisions = listOf(out.decisionRJson!!, m0, m1)
        fun expectBlock(
            label: String,
            decs: List<String> = decisions,
            runs: List<EvalStudy.StudyRunInput> = sixRunInputs(),
            fragment: String
        ) {
            try {
                EvalStudy.scoreStudy(out.manifestJson!!, decs, runs)
                fail("scoring accepted $label")
            } catch (e: EvalStudy.StudyError) {
                assertTrue("$label: ${e.message}",
                    e.message!!.contains(fragment))
            }
        }
        // Missing export.
        expectBlock("5 runs", runs = sixRunInputs().take(5),
            fragment = "expected 6")
        // Corrupt bytes.
        expectBlock("corrupt run", runs = sixRunInputs().toMutableList()
            .also { it[0] = EvalStudy.StudyRunInput("run-0", "{bad".toByteArray()) },
            fragment = "not valid JSON")
        // Wrong model identity.
        val wrongModel = artifactJson("BENCH-20261030T130000Z-aaaa0001",
            gen = 15.0, collected = "2026-10-30T13:00:00Z",
            modelSha = "00".repeat(32))
        expectBlock("wrong model", runs = sixRunInputs().toMutableList()
            .also {
                it[0] = EvalStudy.StudyRunInput("run-0",
                    wrongModel.toByteArray(Charsets.UTF_8))
            },
            fragment = "model SHA-256 mismatch")
        // Wrong runtime.
        val wrongRuntime = artifactJson(
            "BENCH-20261030T130000Z-aaaa0001",
            gen = 15.0, collected = "2026-10-30T13:00:00Z")
            .replace(""""version":"0.7.0-dev"""",
                """"version":"9.9.9"""")
        expectBlock("wrong runtime", runs = sixRunInputs().toMutableList()
            .also {
                it[0] = EvalStudy.StudyRunInput("run-0",
                    wrongRuntime.toByteArray(Charsets.UTF_8))
            },
            fragment = "runtime identity mismatch")
        // Duplicate run ids.
        expectBlock("dup ids", runs = sixRunInputs().toMutableList()
            .also { it[1] = it[0] }, fragment = "duplicate run id")
        // Decision inversion: a run STARTs before M1 decided.
        val early = artifactJson("BENCH-20261030T120500Z-eeee0001",
            gen = 15.0, collected = "2026-10-30T12:05:00Z")
        expectBlock("inversion", runs = sixRunInputs().toMutableList()
            .also {
                it[0] = EvalStudy.StudyRunInput("run-0",
                    early.toByteArray(Charsets.UTF_8))
            },
            fragment = "does not predate START")
        // Collected/START skew.
        val skewed = artifactJson("BENCH-20261030T130000Z-aaaa0001",
            gen = 15.0, collected = "2026-10-30T15:00:00Z")
        expectBlock("skew", runs = sixRunInputs().toMutableList()
            .also {
                it[0] = EvalStudy.StudyRunInput("run-0",
                    skewed.toByteArray(Charsets.UTF_8))
            },
            fragment = "inconsistent with START")
        // Altered M0 selection (record says B, payload says A).
        val altered = m0.replace("\"selection\":\"B\"",
            "\"selection\":\"A\"")
        expectBlock("altered decision",
            decs = listOf(out.decisionRJson!!, altered, m1),
            fragment = "re-validated")
        // Mock-synthetic decisions can never qualify.
        val mock = m0.replace("\"transport\":\"live\"",
            "\"transport\":\"mock-synthetic\"")
        expectBlock("mock decision",
            decs = listOf(out.decisionRJson!!, mock, m1),
            fragment = "mock-synthetic")
        // Missing arm.
        expectBlock("missing arm",
            decs = listOf(out.decisionRJson!!, m0),
            fragment = "exactly R, M0, M1")
        // R selection flipped: re-derivation disagrees.
        val rFlip = out.decisionRJson!!.replace("\"selection\":\"A\"",
            "\"selection\":\"B\"")
        expectBlock("R flip",
            decs = listOf(rFlip, m0, m1), fragment = "re-derived")
    }

    @Test
    fun qualifiedScoringExcludesFailedRunsWithoutBlocking() {
        val out = EvalStudy.runPreflight(preflightInput(twoRunOffer()))
        val manifest = EvalStudy.parseManifest(out.manifestJson!!)
        val failed = artifactJson("BENCH-20261030T135000Z-bbbb0003",
            gen = 12.5, collected = "2026-10-30T13:50:00Z",
            threads = 8).replace(
            """"status":"COMPLETED"""", """"status":"FAILED"""")
        val score = EvalStudy.scoreStudy(
            out.manifestJson!!,
            listOf(
                out.decisionRJson!!,
                liveDecision("M0", "B", "2026-10-30T12:05:00Z",
                    manifest, gen = 18.0),
                liveDecision("M1", "A", "2026-10-30T12:10:00Z",
                    manifest,
                    cited = listOf(manifest.admitted.first().runId),
                    gen = 14.5)),
            sixRunInputs().toMutableList().also {
                it[5] = EvalStudy.StudyRunInput("run-5",
                    failed.toByteArray(Charsets.UTF_8))
            })
        assertEquals(1, score.excludedRuns.size)
        assertTrue(score.excludedRuns.single().contains("not COMPLETED"))
        // B still scores from its 2 surviving runs.
        assertEquals(2, score.candidateB.medianGen.n)
    }
}
