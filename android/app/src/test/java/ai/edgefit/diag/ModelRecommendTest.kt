package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for EF-MODEL-01B contextual recommendations:
 * current-model context, host size comparison, exact-hash
 * evidence matching, deterministic explanations, and the
 * memory-limited flag. No network, no Meta, no ranking.
 */
class ModelRecommendTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var stamp = "2026-10-28T00:00:00Z"

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        val OTHER_SHA = "aa".repeat(32)
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
        const val RUN_A = "BENCH-20261028T010000Z-aaaa1111"
        const val RUN_B = "BENCH-20261028T020000Z-bbbb2222"
    }

    private fun manager(): SessionManager =
        SessionManager(temp.newFolder()) { stamp }

    private fun entryJson(
        id: String = "tiny-test-q4_k_m",
        sizeDisplay: String = "386 MB",
        sha: String = "null",
        runtime: String = "true"
    ): String = """
        {"id": "$id",
         "display_name": "Tiny Test (Q4_K_M)",
         "base_publisher": "TestOrg",
         "base_model": "TestOrg/Tiny",
         "gguf_publisher": "TestOrg (official)",
         "repo_url": "https://huggingface.co/TestOrg/Tiny-GGUF",
         "artifact_path": "tiny-q4_k_m.gguf",
         "source_url": "https://huggingface.co/TestOrg/Tiny-GGUF/blob/main/tiny-q4_k_m.gguf",
         "quantization": "Q4_K_M",
         "size_bytes": null,
         "size_display": "$sizeDisplay",
         "sha256": $sha,
         "license": "apache-2.0",
         "license_note": "Verify before use.",
         "verified_utc": "2026-10-29",
         "arch": "llama",
         "context_length": 8192,
         "runtime_supported": $runtime,
         "runtime_ref": "llama.cpp v0.7.0 @ 620d2225",
         "runtime_method": "arch enum present; untested.",
         "suitability_rationale": "Small artifact worth evaluating.",
         "limitations": "Unverified hash; unmeasured.",
         "performance_evidence": "unmeasured"}
    """.trimIndent()

    private fun entry(
        sizeDisplay: String = "386 MB",
        sha: String = "null",
        runtime: String = "true"
    ): ModelCatalog.Entry = ModelCatalog.parse(
        """{"catalog_version": 1, "entries": [${entryJson(
            sizeDisplay = sizeDisplay, sha = sha,
            runtime = runtime)}]}""")[0]

    private fun device(
        avail: Long? = 3L * 1024 * 1024 * 1024,
        measured: Set<String> = emptySet()
    ) = ModelSuitability.DeviceInput(
        supportedAbis = listOf("arm64-v8a"),
        availBytes = avail,
        availSampledAt = "00:00:01 local",
        measuredArtifactShas = measured
    )

    private fun measured(
        manager: SessionManager,
        modelSha: String = MODEL_SHA,
        modelSize: Long = 800L * 1024 * 1024,
        runId: String = RUN_A,
        collected: String = "2026-10-28T01:00:00Z",
        gen: Double = 16.0,
        status: String = BenchContract.STATUS_COMPLETED
    ): SessionRecord {
        val created = manager.createSession(
            mode = "byok", deviceModel = null, profileSha256 = null,
            modelSha256 = modelSha, modelSizeBytes = modelSize,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(
                BenchConfig.default()))
        manager.requestPrediction(created.sessionId)
        manager.recordPrediction(created.sessionId, 15.0, 23.0,
            """{"mock":true}""",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList())
        manager.authorizeBenchmark(created.sessionId, "test")
        manager.noteRunStarted(created.sessionId)
        return manager.attachBenchmark(
            id = created.sessionId, runId = runId,
            sourceSha256 = "ab".repeat(32), status = status,
            measurements = linkedMapOf(
                "tps_gen" to gen, "tps_prefill" to 24.0),
            collectedAtUtc = collected, modelSha256 = modelSha,
            workloadId = BenchContract.WORKLOAD_ID,
            config = SessionBenchBridge.configMap(
                BenchConfig.default()),
            deviceModel = null)
    }

    private fun current(
        stagedBytes: Long? = 800L * 1024 * 1024,
        sessions: List<SessionRecord> = emptyList()
    ) = ModelContext.CurrentModel(
        staged = stagedBytes?.let {
            ModelContext.StagedRef("mock.gguf", MODEL_SHA, it)
        },
        lastMeasurement =
            ModelContext.lastCompletedMeasurement(sessions))

    // ---- current-model context ----

    @Test
    fun lastMeasurementPicksLatestValidRun() {
        val manager = manager()
        measured(manager, collected = "2026-10-28T01:00:00Z",
            runId = RUN_A, gen = 15.0)
        measured(manager, collected = "2026-10-28T02:00:00Z",
            runId = RUN_B, gen = 16.5)
        val last = ModelContext.lastCompletedMeasurement(
            manager.listSessions())!!
        assertEquals(RUN_B, last.runId)
        assertEquals(16.5, last.genTps, 0.0)
        assertEquals("ctx=512 threads=4 predict=64",
            last.configLabel)
    }

    @Test
    fun failedAndEmptyRunsAreNotEvidence() {
        val manager = manager()
        measured(manager, runId = RUN_A, status =
            BenchContract.STATUS_FAILED, gen = 0.0)
        assertNull(ModelContext.lastCompletedMeasurement(
            manager.listSessions()))
        assertNull(ModelContext.lastCompletedMeasurement(
            emptyList()))
    }

    @Test
    fun sizeDiffUsesStagedBaselineFirst() {
        val diff = ModelContext.sizeVsCurrent(
            386L * 1024 * 1024, "listed_display_upper_bound",
            current())!!
        assertEquals(800L * 1024 * 1024, diff.currentBytes)
        assertTrue(diff.absBytes < 0)
        assertEquals(-51.8, diff.pctVsCurrent, 0.05)
        assertTrue(diff.approximate)
    }

    @Test
    fun sizeDiffFallsBackToLastMeasurement() {
        val manager = manager()
        measured(manager, modelSize = 900L * 1024 * 1024)
        val noStage = ModelContext.CurrentModel(staged = null,
            lastMeasurement = ModelContext.lastCompletedMeasurement(
                manager.listSessions()))
        val diff = ModelContext.sizeVsCurrent(
            386L * 1024 * 1024, "listed_display_upper_bound",
            noStage)!!
        assertEquals(900L * 1024 * 1024, diff.currentBytes)
    }

    @Test
    fun sizeDiffNullWhenEitherSideUnknown() {
        assertNull(ModelContext.sizeVsCurrent(null, "unknown",
            current()))
        assertNull(ModelContext.sizeVsCurrent(
            386L * 1024 * 1024, "listed_display_upper_bound",
            ModelContext.CurrentModel(null, null)))
    }

    @Test
    fun baselineNameMirrorsBaselineChoice() {
        assertEquals("mock.gguf",
            ModelContext.baselineName(current()))
        val manager = manager()
        measured(manager)
        val noStage = ModelContext.CurrentModel(staged = null,
            lastMeasurement = ModelContext.lastCompletedMeasurement(
                manager.listSessions()))
        assertEquals("mock.gguf",
            ModelContext.baselineName(noStage))
        assertNull(ModelContext.baselineName(
            ModelContext.CurrentModel(null, null)))
    }

    // ---- exact evidence matching ----

    @Test
    fun evidenceRequiresKnownHashAndCompletedRun() {
        val manager = manager()
        measured(manager, runId = RUN_A, gen = 16.0)
        val sessions = manager.listSessions()
        // Null catalog hash never matches, even with a
        // same-named local run present.
        assertNull(ModelRecommend.localEvidenceFor(
            entry(), sessions))
        val known = ModelRecommend.localEvidenceFor(
            entry(sha = "\"$MODEL_SHA\""), sessions)!!
        assertEquals(RUN_A, known.runId)
        assertEquals(16.0, known.genTps, 0.0)
    }

    @Test
    fun evidenceRejectsFailedRunsAndForeignHashes() {
        val manager = manager()
        measured(manager, runId = RUN_A,
            status = BenchContract.STATUS_FAILED, gen = 0.0)
        assertNull(ModelRecommend.localEvidenceFor(
            entry(sha = "\"$MODEL_SHA\""),
            manager.listSessions()))
        val manager2 = manager()
        measured(manager2, modelSha = OTHER_SHA, runId = RUN_B)
        assertNull(ModelRecommend.localEvidenceFor(
            entry(sha = "\"$MODEL_SHA\""),
            manager2.listSessions()))
    }

    // ---- recommendation logic ----

    private fun recommend(
        entry: ModelCatalog.Entry,
        status: String = ModelSuitability.LIKELY_COMPATIBLE,
        current: ModelContext.CurrentModel = current(),
        sizeDiff: ModelContext.SizeDiff? = ModelContext.SizeDiff(
            386L * 1024 * 1024, 800L * 1024 * 1024,
            -414L * 1024 * 1024, -51.8, true),
        evidence: ModelRecommend.MatchedEvidence? = null
    ): ModelRecommend.Recommendation {
        val assessment = ModelSuitability.assess(entry,
            device())
        val fixed = assessment.copy(status = status)
        return ModelRecommend.recommend(
            entry, fixed, evidence, current, sizeDiff)
    }

    @Test
    fun smallerFootprintRecommendsMemoryHeadroom() {
        val rec = recommend(entry())
        assertEquals(ModelRecommend.Kind.SMALLER_FOOTPRINT,
            rec.kind)
        assertTrue(rec.body.contains("headroom"))
        assertTrue(rec.body.contains("not been verified"))
        assertTrue(rec.body.contains("about"))
    }

    @Test
    fun largerFootprintWarnsAndDisclaimsSpeed() {
        val big = entry(sizeDisplay = "1.5 GB")
        val diff = ModelContext.sizeVsCurrent(
            ModelCatalog.sizeBoundBytes(big),
            ModelCatalog.sizeBasis(big), current())!!
        assertEquals(156.0, diff.pctVsCurrent, 0.001)
        val rec = recommend(big, sizeDiff = diff)
        assertEquals(ModelRecommend.Kind.LARGER_FOOTPRINT,
            rec.kind)
        assertTrue(rec.body.contains("headroom"))
        assertTrue(rec.body.contains("nothing about speed"))
    }

    @Test
    fun measuredCandidatePointsAtItsRun() {
        val manager = manager()
        measured(manager, runId = RUN_A, gen = 16.0)
        val sessions = manager.listSessions()
        val withHash = entry(sha = "\"$MODEL_SHA\"")
        val evidence = ModelRecommend.localEvidenceFor(
            withHash, sessions)!!
        val rec = ModelRecommend.recommend(withHash,
            ModelSuitability.assess(withHash,
                device(measured = setOf(MODEL_SHA))),
            evidence, current(), sizeDiff = null)
        assertEquals(ModelRecommend.Kind.ALREADY_MEASURED,
            rec.kind)
        assertTrue(rec.body.contains(RUN_A))
    }

    @Test
    fun unsupportedAndUncertainRefuseToRecommend() {
        val no = recommend(entry(runtime = "false"),
            status = ModelSuitability.UNSUPPORTED)
        assertEquals(ModelRecommend.Kind.UNSUPPORTED, no.kind)
        assertTrue(no.body.contains("Do not assume"))
        val unknown = recommend(entry(),
            status = ModelSuitability.INSUFFICIENT_EVIDENCE)
        assertEquals(ModelRecommend.Kind.UNCERTAIN, unknown.kind)
    }

    @Test
    fun noBaselineStaysHonest() {
        val rec = recommend(entry(),
            current = ModelContext.CurrentModel(null, null),
            sizeDiff = null)
        assertEquals(ModelRecommend.Kind.NO_BASELINE, rec.kind)
        assertTrue(rec.body.contains("No staged or measured"))
    }

    @Test
    fun recommendationsAreDeterministic() {
        val first = recommend(entry())
        val second = recommend(entry())
        assertEquals(first, second)
    }

    @Test
    fun noCrossModelThroughputRanking() {
        // Only ALREADY_MEASURED may cite a tok/s value —
        // and only the candidate's OWN run.
        val big = entry(sizeDisplay = "1.5 GB")
        for (rec in listOf(
            recommend(entry()),
            recommend(big, sizeDiff = ModelContext.sizeVsCurrent(
                ModelCatalog.sizeBoundBytes(big),
                ModelCatalog.sizeBasis(big), current())),
            recommend(entry(),
                current = ModelContext.CurrentModel(null, null),
                sizeDiff = null)
        )) {
            assertFalse(rec.kind.toString(),
                "tok/s" in rec.body)
        }
    }

    // ---- memory-limited flag ----

    @Test
    fun memoryLimitedFlagLeavesStatusStanding() {
        val assessed = ModelSuitability.assess(entry(),
            device(avail = 512L * 1024 * 1024))
        assertEquals(ModelSuitability.LIKELY_COMPATIBLE,
            assessed.status)
        assertTrue(assessed.memoryLimitedNow)
        assertEquals(512L * 1024 * 1024, assessed.availBytes)
        val fits = ModelSuitability.assess(entry(), device())
        assertFalse(fits.memoryLimitedNow)
        val unknown = ModelSuitability.assess(entry(),
            device(avail = null))
        assertFalse(unknown.memoryLimitedNow)
    }

    // ---- presentation ----

    @Test
    fun currentModelSectionsShowStagedAndMeasured() {
        val manager = manager()
        measured(manager, runId = RUN_A, gen = 16.0)
        val full = current(sessions = manager.listSessions())
        val body = InsightCards.currentModelSections(full)
            .flatMap { it.rows }
            .joinToString("\n") { "${it.label}=${it.value}" }
        assertTrue(body.contains("mock.gguf"))
        assertTrue(body.contains(RUN_A))
        assertTrue(body.contains("16.0 tok/s"))
        val empty = InsightCards.currentModelSections(
            ModelContext.CurrentModel(null, null))
            .flatMap { it.rows }
            .joinToString("\n") { it.value }
        assertTrue(empty.contains("none staged"))
        assertTrue(empty.contains("none completed"))
    }

    @Test
    fun detailSectionsCarryRecommendationAndEvidence() {
        val manager = manager()
        measured(manager, runId = RUN_A, gen = 16.0)
        val sessions = manager.listSessions()
        val withHash = entry(sha = "\"$MODEL_SHA\"")
        val assessment = ModelSuitability.assess(withHash,
            device(measured = setOf(MODEL_SHA)))
        val evidence = ModelRecommend.localEvidenceFor(
            withHash, sessions)!!
        val present = current(sessions = sessions)
        val sections = InsightCards.modelEntrySections(
            withHash, assessment,
            ModelRecommend.recommend(withHash, assessment,
                evidence, present,
                ModelContext.sizeVsCurrent(
                    assessment.sizeBoundBytes,
                    assessment.sizeBasis, present)),
            evidence,
            ModelContext.sizeVsCurrent(
                assessment.sizeBoundBytes, assessment.sizeBasis,
                present),
            ModelContext.baselineName(present))
        val titles = sections.map { it.title }
        assertEquals(listOf("Candidate", "Recommendation",
            "Comparison vs current", "Compatibility",
            "Memory considerations", "Local evidence",
            "Evidence & limits", "Technical evidence"), titles)
        val local = sections.first {
            it.title == "Local evidence"
        }.rows.joinToString("\n") { it.value }
        assertTrue(local.contains(RUN_A))
        val comparison = sections.first {
            it.title == "Comparison vs current"
        }.rows.joinToString("\n") { it.value }
        assertTrue(comparison.contains("about"))
        assertTrue(comparison.contains("Not compared"))
    }

    @Test
    fun cardLineShowsHeadlineAndLimitedBanner() {
        val assessed = ModelSuitability.assess(entry(),
            device(avail = 512L * 1024 * 1024))
        val line = InsightCards.modelCardLine(entry(), assessed,
            recommend(entry()))
        assertTrue(line.contains("Smaller listed footprint"))
        assertTrue(line.contains(
            "Currently limited by available memory"))
    }

    @Test
    fun catalogOrderIsPreservedWithoutRanking() {
        val parsed = ModelCatalog.parse(
            """{"catalog_version": 1, "entries": [${
                entryJson(id = "zeta-entry")}, ${
                entryJson(id = "alpha-entry")}]}""")
        // File order, not sorted, scored, or ranked.
        assertEquals("zeta-entry", parsed[0].id)
        assertEquals("alpha-entry", parsed[1].id)
    }
}
