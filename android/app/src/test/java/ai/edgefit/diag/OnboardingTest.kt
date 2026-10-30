package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * EF-UX-01D: SHOW gating, retirement counting, page
 * contract, standalone persistence path, state-machine
 * edge, and honest measurement headlines.
 */
class OnboardingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun record(
        state: String = SessionState.COMPARISON_READY,
        mode: String = "byok",
        id: String = "SES-aaaaaaaaaaaaaaaa",
        benchmark: Map<String, Any?>? = null,
        comparison: Map<String, Any?>? = null,
        prediction: Map<String, Any?>? = null,
        parent: String? = null,
        seq: Int? = null,
        agent: Map<String, Any?>? = null
    ): SessionRecord =
        SessionRecord(
            sessionId = id, mode = mode, state = state,
            createdAtUtc = "2026-10-28T01:00:00Z",
            updatedAtUtc = "2026-10-28T01:00:00Z",
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 1,
            modelDisplay = "m", workloadId = "w",
            promptSha256 = "1".repeat(64),
            config = emptyMap(), prediction = prediction,
            benchmark = benchmark, comparison = comparison,
            followup = null, parentSessionId = parent,
            experimentSeq = seq, budgetUsed = 0,
            budgetMax = 5, agent = agent, history = emptyList(),
            recoveryNotes = emptyList()
        )

    private fun measured(
        runId: String,
        status: String = BenchContract.STATUS_COMPLETED
    ): Map<String, Any?> = mapOf(
        "run_id" to runId,
        "status" to status,
        "collected_at_utc" to "2026-10-28T01:00:00Z",
        "measurements" to mapOf("tps_gen" to 15.0))

    private fun comparable(comparable: Boolean = true) =
        mapOf("comparable" to comparable)

    private fun prediction(
        source: String = SessionManager.SOURCE_LIVE_META
    ) = mapOf(
        "predicted_gen_tps" to 15.0,
        "source_type" to source,
        "created_at_utc" to "2026-10-28T00:00:00Z")

    // ---- SHOW gate ----

    @Test
    fun showGateEvaluatedInOrder() {
        assertTrue(Onboarding.shouldShow(0, false, false, false))
        assertTrue(Onboarding.shouldShow(2, false, false, false))
        assertFalse(Onboarding.shouldShow(3, false, false, false))
        assertFalse(Onboarding.shouldShow(9, false, false, false))
        assertFalse(Onboarding.shouldShow(0, true, false, false))
        assertFalse(Onboarding.shouldShow(0, false, true, false))
        assertFalse(Onboarding.shouldShow(0, false, false, true))
    }

    @Test
    fun retirementBoundary() {
        assertFalse(Onboarding.retired(0))
        assertFalse(Onboarding.retired(2))
        assertTrue(Onboarding.retired(3))
        assertTrue(Onboarding.retired(30))
    }

    // ---- page contract ----

    @Test
    fun threeApprovedScreens() {
        val pages = Onboarding.pages()
        assertEquals(Onboarding.PAGE_COUNT, pages.size)
        assertEquals("What EdgeFit Does", pages[0].title)
        assertEquals("How to Benchmark", pages[1].title)
        assertEquals("Optional Muse-Assisted Experiments",
            pages[2].title)
        assertEquals("Next", pages[0].primary)
        assertEquals("Next", pages[1].primary)
        assertEquals("Start Benchmarking", pages[2].primary)
        for (page in pages) {
            assertTrue(page.lines.size in 3..5)
            assertTrue(page.lines.all { it.isNotBlank() })
        }
    }

    @Test
    fun pagesNeverDescribeFutureSystemsAsLive() {
        val body = Onboarding.pages().joinToString("\n") {
            (listOf(it.title) + it.lines).joinToString("\n")
        }.lowercase()
        assertFalse(body.contains("cloud"))
        assertFalse(body.contains("statistic"))
        assertFalse(body.contains("website"))
        assertTrue(body.contains("offline"))
    }

    @Test
    fun pageNavigationClamps() {
        assertEquals(1, Onboarding.nextPage(0))
        assertEquals(2, Onboarding.nextPage(1))
        assertEquals(2, Onboarding.nextPage(2))
        assertEquals(1, Onboarding.prevPage(2))
        assertEquals(0, Onboarding.prevPage(1))
        assertEquals(0, Onboarding.prevPage(0))
    }

    // ---- counting: measured sessions ----

    private fun complete(
        id: String,
        run: String,
        mode: String = "byok"
    ) = record(mode = mode, id = id,
        benchmark = measured(run),
        comparison = comparable(),
        prediction = prediction())

    @Test
    fun countsDistinctCompletions() {
        assertEquals(0, Onboarding.countCompletions(emptyList()))
        assertEquals(1, Onboarding.countCompletions(listOf(
            complete("SES-aaaaaaaaaaaaaaaa",
                "BENCH-20261028T010000Z-aaaaaaaa"))))
        assertEquals(3, Onboarding.countCompletions(listOf(
            complete("SES-aaaaaaaaaaaaaaaa",
                "BENCH-20261028T010000Z-aaaaaaaa"),
            complete("SES-bbbbbbbbbbbbbbbb",
                "BENCH-20261028T020000Z-bbbbbbbb",
                mode = "offline_test"),
            complete("SES-cccccccccccccccc",
                "BENCH-20261028T030000Z-cccccccc"))))
    }

    @Test
    fun excludesNonCompletions() {
        val run = "BENCH-20261028T010000Z-aaaaaaaa"
        // Failed / cancelled / prediction-only / abandoned.
        assertNull(Onboarding.completionKey(
            record(benchmark = measured(run,
                status = BenchContract.STATUS_FAILED),
                comparison = comparable(),
                prediction = prediction()), emptyList()))
        assertNull(Onboarding.completionKey(
            record(state = SessionState.BENCHMARK_CANCELLED,
                benchmark = measured(run,
                    status = BenchContract.STATUS_CANCELLED)),
            emptyList()))
        assertNull(Onboarding.completionKey(
            record(state = SessionState.PREDICTION_RECORDED,
                prediction = prediction()), emptyList()))
        assertNull(Onboarding.completionKey(
            record(state = SessionState.SESSION_FINISHED),
            emptyList()))
        // Measured but noncomparable.
        assertNull(Onboarding.completionKey(
            record(benchmark = measured(run),
                comparison = comparable(false),
                prediction = prediction()), emptyList()))
        // Compared without a benchmark (impossible, safe).
        assertNull(Onboarding.completionKey(
            record(comparison = comparable(),
                prediction = prediction()), emptyList()))
    }

    @Test
    fun duplicateRunIdsCountOnce() {
        val run = "BENCH-20261028T010000Z-aaaaaaaa"
        assertEquals(1, Onboarding.countCompletions(listOf(
            complete("SES-aaaaaaaaaaaaaaaa", run),
            complete("SES-bbbbbbbbbbbbbbbb", run))))
    }

    @Test
    fun corruptRecordsSkippedNotCounted() {
        val good = complete("SES-aaaaaaaaaaaaaaaa",
            "BENCH-20261028T010000Z-aaaaaaaa")
        // Mistyped values (as a hostile/corrupt file could
        // decode): skipped by safe casts, never counted,
        // never crashing the scan.
        val corrupt = record(
            benchmark = mapOf(
                "run_id" to 12345,
                "status" to BenchContract.STATUS_COMPLETED),
            comparison = comparable())
        assertEquals(1, Onboarding.countCompletions(
            listOf(good, corrupt)))
        assertNull(Onboarding.completionKey(corrupt, emptyList()))
    }

    // ---- counting: agent hierarchy ----

    private fun child(
        id: String,
        parent: String,
        run: String,
        ok: Boolean = true
    ) = record(state = SessionState.COMPARISON_READY,
        id = id, parent = parent, seq = 1,
        benchmark = measured(run),
        comparison = comparable(ok),
        prediction = prediction())

    private fun parent(
        id: String,
        finished: Boolean
    ) = record(state = if (finished) {
        SessionState.SESSION_FINISHED
    } else {
        SessionState.SESSION_CREATED
    }, mode = "agent", id = id,
        agent = mapOf("status" to if (finished) {
            AgentManager.STATUS_FINISHED
        } else {
            AgentManager.STATUS_ACTIVE
        }))

    @Test
    fun agentParentNeedsFinishAndQualifyingChild() {
        val run = "BENCH-20261028T010000Z-aaaaaaaa"
        val pinf = "SES-bbbbbbbbbbbbbbbb"
        val goodChild = child("SES-aaaaaaaaaaaaaaaa", pinf, run)
        // Child alone never counts directly.
        assertEquals(0,
            Onboarding.countCompletions(listOf(goodChild)))
        // Unfinished parent never counts.
        assertEquals(0, Onboarding.countCompletions(listOf(
            parent("SES-cccccccccccccccc", false), goodChild)))
        // Finished parent with no qualifying child: no count.
        val badChild = child("SES-aaaaaaaaaaaaaaaa", pinf, run,
            ok = false)
        assertEquals(0, Onboarding.countCompletions(listOf(
            parent(pinf, true), badChild)))
        // Finished + qualifying child: exactly one.
        assertEquals(1, Onboarding.countCompletions(listOf(
            parent(pinf, true), goodChild)))
        // Two qualifying children still count the parent once.
        assertEquals(1, Onboarding.countCompletions(listOf(
            parent(pinf, true), goodChild,
            child("SES-dddddddddddddddd", pinf,
                "BENCH-20261028T020000Z-bbbbbbbb"))))
    }

    // ---- counting: standalone + replay ----

    @Test
    fun standaloneCountsWhenMeasured() {
        val run = "BENCH-20261028T010000Z-aaaaaaaa"
        assertEquals(1, Onboarding.countCompletions(listOf(
            record(state = SessionState.BENCHMARK_COMPLETED,
                mode = SessionStore.MODE_STANDALONE,
                benchmark = measured(run)))))
        assertEquals(0, Onboarding.countCompletions(listOf(
            record(state = SessionState.BENCHMARK_FAILED,
                mode = SessionStore.MODE_STANDALONE,
                benchmark = measured(run,
                    status = BenchContract.STATUS_FAILED)))))
    }

    @Test
    fun replayDedupesBySourceArtifact() {
        fun replay(id: String, run: String,
            source: String?): SessionRecord {
            val base = mutableMapOf<String, Any?>(
                "predicted_gen_tps" to 15.0,
                "source_type" to SessionManager.SOURCE_REPLAY,
                "created_at_utc" to "2026-10-28T00:00:00Z")
            if (source != null) {
                base["replay_source_sha256"] = source
            }
            return record(mode = "replay", id = id,
                benchmark = measured(run),
                comparison = comparable(), prediction = base)
        }
        // Same artifact twice (distinct runs): one.
        assertEquals(1, Onboarding.countCompletions(listOf(
            replay("SES-aaaaaaaaaaaaaaaa",
                "BENCH-20261028T010000Z-aaaaaaaa", "ab".repeat(32)),
            replay("SES-bbbbbbbbbbbbbbbb",
                "BENCH-20261028T020000Z-bbbbbbbb", "ab".repeat(32)))))
        // Two artifacts: two.
        assertEquals(2, Onboarding.countCompletions(listOf(
            replay("SES-aaaaaaaaaaaaaaaa",
                "BENCH-20261028T010000Z-aaaaaaaa", "ab".repeat(32)),
            replay("SES-bbbbbbbbbbbbbbbb",
                "BENCH-20261028T020000Z-bbbbbbbb", "cd".repeat(32)))))
        // Legacy replay without a source key counts by run.
        assertEquals(1, Onboarding.countCompletions(listOf(
            replay("SES-aaaaaaaaaaaaaaaa",
                "BENCH-20261028T010000Z-aaaaaaaa", null))))
    }

    // ---- standalone persistence path ----

    private fun manager(): SessionManager =
        SessionManager(temp.newFolder()) { "2026-10-28T21:12:52Z" }

    private fun standaloneConfig() =
        SessionBenchBridge.configMap(BenchConfig.default())

    @Test
    fun standaloneLifecyclePersistsMeasurement() {
        val manager = manager()
        val created = manager.createSession(
            mode = SessionStore.MODE_STANDALONE,
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = standaloneConfig())
        assertEquals(SessionState.SESSION_CREATED, created.state)
        manager.authorizeStandalone(created.sessionId, "test")
        manager.noteRunStarted(created.sessionId)
        val done = manager.attachStandalone(
            id = created.sessionId,
            runId = "BENCH-20261028T211252Z-aaaa1111",
            sourceSha256 = "ab".repeat(32),
            measurements = mapOf(
                "tps_gen" to 16.0, "tps_prefill" to 20.0),
            collectedAtUtc = "2026-10-28T21:12:52Z",
            modelSha256 = "0".repeat(64),
            workloadId = BenchContract.WORKLOAD_ID,
            config = standaloneConfig(),
            deviceModel = null)
        assertEquals(SessionState.BENCHMARK_COMPLETED, done.state)
        assertNull(done.prediction)
        assertNull(done.comparison)
        assertEquals(1, done.budgetUsed)
        // The persisted record counts toward retirement.
        assertEquals(1, Onboarding.countCompletions(
            manager.listSessions()))
    }

    @Test
    fun standalonePathRefusesMisuse() {
        val manager = manager()
        val byok = manager.createSession(
            mode = "byok", deviceModel = null,
            profileSha256 = null, modelSha256 = "0".repeat(64),
            modelSizeBytes = 100L, modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = standaloneConfig())
        try {
            manager.authorizeStandalone(byok.sessionId, "test")
            fail("byok must refuse standalone authorization")
        } catch (_: SessionManager.SessionError) {
        }
        val created = manager.createSession(
            mode = SessionStore.MODE_STANDALONE,
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = standaloneConfig())
        // Predicted-path authorization still demands a
        // recorded prediction, even for standalone mode.
        try {
            manager.authorizeBenchmark(created.sessionId, "test")
            fail("standalone must refuse prediction authorize")
        } catch (_: SessionManager.SessionError) {
        }
        // Attach before RUNNING is refused.
        try {
            manager.attachStandalone(
                id = created.sessionId,
                runId = "BENCH-20261028T211252Z-aaaa1111",
                sourceSha256 = "ab".repeat(32),
                measurements = emptyMap(),
                collectedAtUtc = "2026-10-28T21:12:52Z",
                modelSha256 = "0".repeat(64),
                workloadId = BenchContract.WORKLOAD_ID,
                config = standaloneConfig(),
                deviceModel = null)
            fail("attach before RUNNING must fail")
        } catch (_: SessionManager.SessionError) {
        }
        manager.authorizeStandalone(created.sessionId, "test")
        manager.noteRunStarted(created.sessionId)
        // Wrong model refused.
        try {
            manager.attachStandalone(
                id = created.sessionId,
                runId = "BENCH-20261028T211252Z-aaaa1111",
                sourceSha256 = "ab".repeat(32),
                measurements = emptyMap(),
                collectedAtUtc = "2026-10-28T21:12:52Z",
                modelSha256 = "2".repeat(64),
                workloadId = BenchContract.WORKLOAD_ID,
                config = standaloneConfig(),
                deviceModel = null)
            fail("model mismatch must fail")
        } catch (_: SessionManager.SessionError) {
        }
        manager.attachStandalone(
            id = created.sessionId,
            runId = "BENCH-20261028T211252Z-aaaa1111",
            sourceSha256 = "ab".repeat(32),
            measurements = emptyMap(),
            collectedAtUtc = "2026-10-28T21:12:52Z",
            modelSha256 = "0".repeat(64),
            workloadId = BenchContract.WORKLOAD_ID,
            config = standaloneConfig(),
            deviceModel = null)
        // Second attach (duplicate run) refused.
        try {
            manager.attachStandalone(
                id = created.sessionId,
                runId = "BENCH-20261028T211252Z-aaaa1111",
                sourceSha256 = "ab".repeat(32),
                measurements = emptyMap(),
                collectedAtUtc = "2026-10-28T21:12:52Z",
                modelSha256 = "0".repeat(64),
                workloadId = BenchContract.WORKLOAD_ID,
                config = standaloneConfig(),
                deviceModel = null)
            fail("duplicate attach must fail")
        } catch (_: SessionManager.SessionError) {
        }
    }

    @Test
    fun standaloneBridgeRefusesNonCompleted() {
        val manager = manager()
        val result = BenchResult(
            runId = "BENCH-20261028T211252Z-aaaa1111",
            device = BenchDeviceSnapshot("0".repeat(64),
                "SM-S908U", 36, listOf("arm64-v8a"), 8,
                12884901888L),
            model = BenchModelIdentity("mock.gguf", 100L,
                "0".repeat(64), "4", emptyMap()),
            runtimeVersion = "test", runtimeAbi = "arm64-v8a",
            config = BenchConfig.default(),
            workload = BenchConfig.workload(),
            measurements = BenchMeasurements(0L, 0L, 0, 0L, 0,
                0.0, 0.0, false,
                BenchMemory(0, 0, 0L, BenchRunner.MEM_METHOD),
                BenchThermal("none", "unknown", "none", false)),
            status = BenchContract.STATUS_FAILED,
            error = "insufficient available memory",
            appVersion = "0.2.0",
            collectedAtUtc = "2026-10-28T21:12:52Z")
        try {
            SessionBenchBridge.recordStandalone(manager, result)
            fail("failed runs must not persist standalone")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(manager.listSessions().isEmpty())
    }

    // ---- state machine edge ----

    @Test
    fun createdAuthorizeEdgeIsStandaloneOnly() {
        assertFalse(SessionState.can(
            SessionState.SESSION_CREATED,
            SessionState.BENCHMARK_AUTHORIZED))
        assertTrue(SessionState.can(
            SessionState.SESSION_CREATED,
            SessionState.BENCHMARK_AUTHORIZED,
            SessionStore.MODE_STANDALONE))
        assertFalse(SessionState.can(
            SessionState.SESSION_CREATED,
            SessionState.BENCHMARK_AUTHORIZED, "byok"))
        // Historic edges unchanged.
        assertTrue(SessionState.can(
            SessionState.SESSION_CREATED,
            SessionState.PREDICTION_PENDING))
        assertTrue(SessionState.can(
            SessionState.BENCHMARK_AUTHORIZED,
            SessionState.BENCHMARK_RUNNING))
        assertFalse(SessionState.can(
            SessionState.BENCHMARK_RUNNING,
            SessionState.COMPARISON_READY))
    }

    // ---- honest headlines ----

    @Test
    fun tpsHeadlineSeparatesMissingFromMeasured() {
        assertEquals("Measured 12.7 tok/s",
            BenchUiText.tpsHeadline(
                BenchContract.STATUS_COMPLETED, 12.749, 64))
        // Genuine partials (mid-run cancel/timeout) measured.
        assertEquals("Measured 11.0 tok/s",
            BenchUiText.tpsHeadline(
                BenchContract.STATUS_TIMEOUT, 11.0, 40))
        assertEquals("Measured 9.5 tok/s",
            BenchUiText.tpsHeadline(
                BenchContract.STATUS_CANCELLED, 9.5, 12))
        // Zero-output outcomes are not measured.
        assertEquals("Not measured — benchmark did not complete.",
            BenchUiText.tpsHeadline(
                BenchContract.STATUS_FAILED, 0.0, 0))
        assertEquals("Not measured — benchmark did not complete.",
            BenchUiText.tpsHeadline(
                BenchContract.STATUS_CANCELLED, 0.0, 0))
        assertEquals("Not measured — benchmark did not complete.",
            BenchUiText.tpsHeadline(
                BenchContract.STATUS_TIMEOUT, 0.0, 0))
    }

    // ---- standalone labels ----

    @Test
    fun standaloneLabels() {
        val standalone = record(
            state = SessionState.BENCHMARK_COMPLETED,
            mode = SessionStore.MODE_STANDALONE)
        assertEquals("Standalone",
            SessionListUi.typeShort(standalone))
        assertEquals("Standalone measurement",
            InsightCards.sessionTypeLabel(standalone))
        assertEquals("Measured",
            SessionListUi.friendlyState(standalone))
        assertFalse(SessionListUi.needsAction(standalone))
    }
}
