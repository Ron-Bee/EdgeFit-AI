package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * EF-SESSION-SCALE-01: the store has no count ceiling.
 * Bulk fixtures are planted as files (test-only
 * synthetic records, never owner data); lifecycle paths
 * always go through the real manager APIs.
 */
class SessionScaleTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
        const val STAMP = "2026-10-29T00:00:00Z"
    }

    private fun manager(root: File): SessionManager =
        SessionManager(root) { STAMP }

    private fun seed(manager: SessionManager): SessionRecord =
        manager.createSession(
            mode = "offline_test", deviceModel = "SM-S908U",
            profileSha256 = "ab".repeat(32), modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()))

    /**
     * Plants [count] additional synthetic records as files
     * beside one manager-created seed (which anchors valid
     * schema). Stamps increase with index so newest-first
     * order is deterministic.
     */
    private fun plant(root: File, manager: SessionManager, count: Int) {
        val seed = seed(manager)
        val dir = File(root, "sessions")
        val template = File(dir, "${seed.sessionId}.json")
            .readText(Charsets.UTF_8)
        for (i in 1..count) {
            val id = "SES-%016x".format(i)
            if (id == seed.sessionId) continue
            val totalSeconds = i
            val stamp = "2026-10-29T%02d:%02d:%02dZ".format(
                totalSeconds / 3600, (totalSeconds / 60) % 60,
                totalSeconds % 60)
            val text = template
                .replace(seed.sessionId, id)
                .replace(STAMP, stamp)
            File(dir, "$id.json").writeText(text, Charsets.UTF_8)
        }
    }

    private fun completedResult(runId: String): BenchResult {
        val measurements = BenchMeasurements(1200L, 1000L, 23, 4000L, 64,
            16.0, 23.0, false,
            BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
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
            measurements = measurements,
            status = BenchContract.STATUS_COMPLETED, error = null,
            appVersion = "mock", collectedAtUtc = "2026-10-29T01:00:00Z")
    }

    private fun failedResult(runId: String): BenchResult {
        val zero = BenchMeasurements(0L, 0L, 0, 0L, 0,
            0.0, 0.0, false,
            BenchMemory(0, 0, 0L, BenchRunner.MEM_METHOD),
            BenchThermal("none", "none", "none", true))
        return completedResult(runId).copy(
            status = BenchContract.STATUS_FAILED,
            error = "model load failed",
            measurements = zero)
    }

    private fun measuredSession(
        manager: SessionManager, result: BenchResult
    ): SessionRecord {
        val session = seed(manager)
        manager.requestPrediction(session.sessionId)
        manager.recordPrediction(session.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), STAMP)
        manager.authorizeBenchmark(session.sessionId)
        manager.noteRunStarted(session.sessionId)
        return SessionBenchBridge.attach(
            manager, session.sessionId, result)
    }

    // ---- the reported blocker ----

    @Test
    fun fiftyFirstSessionSucceeds() {
        val root = temp.newFolder()
        val mgr = manager(root)
        repeat(50) { seed(mgr) }
        val extra = seed(mgr)
        // Same fixed test clock: latest is a
        // deterministic tie-break, so assert count +
        // lookup here; ordering has distinct stamps below.
        assertEquals(51, mgr.listSessions().size)
        assertEquals(extra.sessionId,
            mgr.get(extra.sessionId).sessionId)
    }

    // ---- scale matrix ----

    @Test
    fun hundredSessionsListLookupAndFilter() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 99)
        val all = mgr.listSessions()
        assertEquals(100, all.size)
        assertEquals("SES-%016x".format(99), all.first().sessionId)
        assertEquals("SES-%016x".format(99),
            SessionListUi.latestId(all))
        // Exact lookup among many.
        assertEquals("SES-%016x".format(42),
            mgr.get("SES-%016x".format(42)).sessionId)
        // Filters see the whole set.
        val filtered = SessionListUi.filterSessions(
            all, SessionListUi.SessionFilter.OFFLINE_TEST)
        assertEquals(100, filtered.size)
    }

    @Test
    fun fiveHundredSessionsStable() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 499)
        val all = mgr.listSessions()
        assertEquals(500, all.size)
        val ids = all.map { it.sessionId }
        assertEquals(500, ids.toSet().size)
        // Newest-first by stamp: planted 499..1, then the
        // seed (oldest stamp) last.
        val expected = (499 downTo 1).map {
            "SES-%016x".format(it)
        } + mgr.get(all.last().sessionId).sessionId
        assertEquals(expected, ids)
    }

    @Test
    fun thousandSessionsListAndTimeBounded() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 999)
        val start = System.nanoTime()
        val all = mgr.listSessions()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("SCALE-PROBE listSessions(1000): ${ms}ms")
        assertEquals(1000, all.size)
        assertEquals("SES-%016x".format(999),
            SessionListUi.latestId(all))
        // Generous liveness bound only; device timing is
        // owner-observed, never asserted here.
        assertTrue("listSessions(1000) took ${ms}ms", ms < 60_000)
    }

    @Test
    fun createPastFiveHundredViaManager() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 500)
        val extra = seed(mgr)
        assertEquals(502, mgr.listSessions().size)
        assertEquals(extra.sessionId,
            mgr.get(extra.sessionId).sessionId)
    }

    // ---- lifecycle over the old cap ----

    @Test
    fun agentApprovalPastFiftyChargesOnce() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 51)
        val agent = AgentManager(mgr) { STAMP }
        val parent = agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            baseConfig = BenchConfig.default(), budgetMax = 5)
        val config = BenchConfig.default().copy(nThreads = 8)
        // Real order: proposal stored at live nextSeq, then
        // the approved child shell is created with it.
        val seq = agent.nextSeq(parent.sessionId)
        agent.storeProposal(parent.sessionId,
            AgentApi.ValidProposal(
                proposeExperiment = true, proposedConfig = config,
                changedKeys = listOf("n_threads"),
                hypothesis = "h", expectedDirection = "faster",
                expectedGenTps = 16.0, expectedPrefillTps = 23.0,
                uncertainty = "u", supportingEvidenceIds = emptyList(),
                limitations = emptyList(), benefit = "b",
                noExperimentReason = null, rawJson = "{}"),
            "packhash", seq)
        val child = mgr.createSession(
            mode = "byok", deviceModel = "SM-S908U",
            profileSha256 = null, modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(config),
            parentSessionId = parent.sessionId, experimentSeq = seq)
        mgr.requestPrediction(child.sessionId)
        mgr.recordPrediction(child.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), STAMP)
        mgr.authorizeBenchmark(child.sessionId)
        val updated = agent.approveProposal(
            parent.sessionId, child.sessionId)
        assertEquals(1, updated.budgetUsed)
        assertTrue(mgr.listSessions().size > 50)
    }

    @Test
    fun recoveryPastFiftyLinksSource() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 51)
        val source = measuredSession(mgr,
            failedResult("BENCH-20261029T000000Z-aaaaaaaa"))
        val recovered = mgr.createRecovery(
            source.sessionId, "SM-S908U", null,
            SessionBenchBridge.configMap(
                BenchConfig.default().copy(nCtx = 256)),
            15.0, 23.0, "{}", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), carriedCreatedAtUtc = STAMP)
        assertEquals(source.sessionId, recovered.recoveryOfSessionId)
        assertEquals(SessionState.BENCHMARK_FAILED,
            mgr.get(source.sessionId).state)
    }

    @Test
    fun repeatPastFiftyPreservesLineage() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 51)
        val source = measuredSession(mgr,
            completedResult("BENCH-20261029T000000Z-bbbbbbbb"))
        val repeat = mgr.createRepeat(
            source.sessionId, "SM-S908U", null)
        assertEquals(source.sessionId, repeat.repeatOfSessionId)
        assertEquals("BENCH-20261029T000000Z-bbbbbbbb",
            repeat.repeatOfRunId)
    }

    @Test
    fun standalonePastFiftyPersists() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 51)
        val created = mgr.createSession(
            mode = SessionStore.MODE_STANDALONE,
            deviceModel = null, profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        mgr.authorizeStandalone(created.sessionId, "test")
        mgr.noteRunStarted(created.sessionId)
        val done = mgr.attachStandalone(
            id = created.sessionId,
            runId = "BENCH-20261029T000000Z-cccccccc",
            sourceSha256 = "ab".repeat(32),
            measurements = mapOf(
                "tps_gen" to 16.0, "tps_prefill" to 20.0),
            collectedAtUtc = "2026-10-29T01:00:00Z",
            modelSha256 = MODEL_SHA,
            workloadId = BenchContract.WORKLOAD_ID,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            deviceModel = "SM-S908U")
        assertEquals(SessionState.BENCHMARK_COMPLETED, done.state)
    }

    @Test
    fun parentChildAssociationAtScale() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 99)
        val agent = AgentManager(mgr) { STAMP }
        val parent = agent.createAgent(
            deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            baseConfig = BenchConfig.default(), budgetMax = 5)
        val child = mgr.createSession(
            mode = "byok", deviceModel = "SM-S908U",
            profileSha256 = null, modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            parentSessionId = parent.sessionId, experimentSeq = 1)
        val children = agent.childrenOf(parent.sessionId)
        assertTrue(children.map { it.sessionId }
            .contains(child.sessionId))
    }

    // ---- integrity at scale ----

    @Test
    fun noAutoDeleteAcrossReads() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 100)
        val before = mgr.listSessions().size
        mgr.listSessions()
        mgr.get("SES-%016x".format(50))
        SessionListUi.filterSessions(mgr.listSessions(),
            SessionListUi.SessionFilter.ALL)
        assertEquals(before, mgr.listSessions().size)
        assertEquals(before, SessionStore(root).count())
    }

    @Test
    fun collisionOverwritesWithoutDuplicating() {
        val root = temp.newFolder()
        val store = SessionStore(root)
        val mgr = manager(root)
        val seed = seed(mgr)
        store.save(seed.copy(updatedAtUtc = "2026-10-30T00:00:00Z"))
        assertEquals(1, store.count())
        assertEquals("2026-10-30T00:00:00Z",
            store.load(seed.sessionId).updatedAtUtc)
    }

    @Test
    fun exportHashStable() {
        val root = temp.newFolder()
        val mgr = manager(root)
        val first = SessionJson.stringify(seed(mgr).toMap())
        val second = SessionJson.stringify(
            mgr.get(mgr.listSessions().single().sessionId).toMap())
        assertEquals(first, second)
    }

    @Test
    fun corruptRecordQuarantinedAndSkipped() {
        val root = temp.newFolder()
        val mgr = manager(root)
        plant(root, mgr, 2)
        File(File(root, "sessions"), "SES-ffffffffffffffff.json")
            .writeText("{not json", Charsets.UTF_8)
        val all = mgr.listSessions()
        assertEquals(3, all.size)
        assertTrue(SessionStore(root).quarantineIds().isNotEmpty())
    }

    @Test
    fun interruptedWriteTempSwept() {
        val root = temp.newFolder()
        manager(root)
        val stray = File(File(root, "sessions"), "SES-abc.json.tmp-123")
        stray.writeText("partial", Charsets.UTF_8)
        SessionStore(root)
        assertFalse(stray.exists())
    }

    @Test
    fun storageExhaustionIsExplicit() {
        val fileRoot = temp.newFile()
        val store = SessionStore(fileRoot)
        try {
            store.save(SessionRecord(
                sessionId = "SES-0000000000000001",
                mode = "offline_test",
                state = SessionState.SESSION_CREATED,
                createdAtUtc = STAMP, updatedAtUtc = STAMP,
                modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
                modelDisplay = "mock.gguf",
                workloadId = BenchContract.WORKLOAD_ID,
                promptSha256 = PROMPT_SHA,
                config = SessionBenchBridge.configMap(
                    BenchConfig.default())))
            fail("unwritable root accepted")
        } catch (e: SessionStore.StoreError) {
            assertTrue(e.message!!.contains("write failed"))
            assertTrue(e.message!!.contains("SES-0000000000000001"))
        }
    }
}
