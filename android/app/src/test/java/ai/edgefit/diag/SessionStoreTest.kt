package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** JVM unit tests for the EF-SESSION-01 session layer (no Android APIs). */
class SessionStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        // Shared parity vectors. MUST match test_ef_session_parity.py.
        // (predGen, predPre, measGen, measPre,
        //  genSigned, genAbs, genPct, preSigned, preAbs, prePct)
        val PARITY_VECTORS = listOf(
            doubleArrayOf(15.0, 20.0, 9.2, 22.54901960784314,
                -5.8, 5.8, -38.67, 2.549, 2.549, 12.75),
            doubleArrayOf(0.4, 0.6, 14.382022471910112, 22.54901960784314,
                13.982, 13.982, 3495.51, 21.949, 21.949, 3658.17),
            doubleArrayOf(16.9, 24.52, 16.6, 26.65,
                -0.3, 0.3, -1.78, 2.13, 2.13, 8.69)
        )
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val PROFILE_SHA = "9c27fbbb2ab74bd73822f552feebfc5c473c7cfc9a4da59b" +
            "26e0eb6e73db2d42"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
        const val PRED_AT = "2026-10-27T00:00:00Z"
        const val BENCH_AT = "2026-10-27T01:00:00Z"
    }

    private fun manager(clock: () -> String = { PRED_AT }): SessionManager =
        SessionManager(temp.newFolder(), clock)

    private fun SessionManager.newSession(): SessionRecord = createSession(
        mode = "offline_test",
        deviceModel = "SM-S908U",
        profileSha256 = PROFILE_SHA,
        modelSha256 = MODEL_SHA,
        modelSizeBytes = 807694464L,
        modelDisplay = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        workloadId = BenchContract.WORKLOAD_ID,
        promptSha256 = PROMPT_SHA,
        config = SessionBenchBridge.configMap(BenchConfig.default())
    )

    private fun SessionManager.toRunning(id: String): SessionRecord {
        requestPrediction(id)
        recordPrediction(id, 15.0, 20.0, """{"p":1}""",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(), PRED_AT)
        authorizeBenchmark(id)
        return noteRunStarted(id)
    }

    private fun completedResult(): BenchResult = BenchResult(
        runId = "BENCH-20261027T010000Z-abcdef12",
        device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
            listOf("arm64-v8a"), 8, 12884901888L),
        model = BenchModelIdentity("Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807694464L,
            MODEL_SHA, "4", mapOf("general.architecture" to "llama")),
        runtimeVersion = "b6000-test",
        runtimeAbi = "arm64-v8a",
        config = BenchConfig.default(),
        workload = BenchWorkload(BenchContract.WORKLOAD_ID, PROMPT_SHA, 100),
        measurements = BenchMeasurements(1200L, 1020L, 23, 6957L, 64,
            9.2, 22.54901960784314, false,
            BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
            BenchThermal("none", "light", "light", true)),
        status = BenchContract.STATUS_COMPLETED,
        error = null,
        appVersion = "0.2.0",
        collectedAtUtc = BENCH_AT
    )

    // ---- states ----

    @Test
    fun legalTransitions() {
        val pairs = listOf(
            SessionState.SESSION_CREATED to SessionState.PREDICTION_PENDING,
            SessionState.PREDICTION_PENDING to SessionState.PREDICTION_RECORDED,
            SessionState.PREDICTION_RECORDED to SessionState.BENCHMARK_AUTHORIZED,
            SessionState.BENCHMARK_AUTHORIZED to SessionState.BENCHMARK_RUNNING,
            SessionState.BENCHMARK_RUNNING to SessionState.BENCHMARK_COMPLETED,
            SessionState.BENCHMARK_COMPLETED to SessionState.COMPARISON_READY,
            SessionState.COMPARISON_READY to SessionState.FOLLOWUP_PENDING,
            SessionState.FOLLOWUP_PENDING to SessionState.RECOMMENDATION_RECORDED,
            SessionState.RECOMMENDATION_RECORDED to SessionState.SESSION_FINISHED
        )
        for ((from, to) in pairs) {
            assertTrue("$from -> $to", SessionState.can(from, to))
        }
    }

    @Test
    fun illegalTransitionsRejected() {
        assertFalse(SessionState.can(
            SessionState.SESSION_CREATED, SessionState.BENCHMARK_AUTHORIZED))
        assertFalse(SessionState.can(
            SessionState.PREDICTION_RECORDED, SessionState.PREDICTION_PENDING))
        assertFalse(SessionState.can(
            SessionState.BENCHMARK_COMPLETED, SessionState.BENCHMARK_RUNNING))
        assertFalse(SessionState.can(
            SessionState.SESSION_FINISHED, SessionState.SESSION_CREATED))
        assertFalse(SessionState.can("BOGUS", SessionState.SESSION_CREATED))
        assertFalse(SessionState.isTerminal(SessionState.BENCHMARK_RUNNING))
        assertTrue(SessionState.isTerminal(SessionState.SESSION_FINISHED))
    }

    // ---- codec ----

    @Test
    fun jsonRoundTrip() {
        val value = mapOf(
            "s" to "a\"b\\c\né",
            "i" to 5,
            "l" to 807694464L,
            "d" to 14.382022471910112,
            "b" to true,
            "n" to null,
            "list" to listOf(1L, "x"),
            "obj" to mapOf("k" to "v")
        )
        // JSON has one number type: Ints legitimately return as Longs.
        // Round-trip stability is defined on serialized bytes.
        val once = SessionJson.stringify(value)
        val twice = SessionJson.stringify(SessionJson.parse(once))
        assertEquals(once, twice)
    }

    @Test
    fun jsonRejectsMalformed() {
        for (bad in listOf("", "{", "[1,]", "trailing\"x\"\"y\"", "{1:2}",
            "\"unterminated", "[01]", "1e", "\"bad\\q\"")) {
            try {
                SessionJson.parse(bad)
                fail("accepted: $bad")
            } catch (_: SessionJson.JsonError) {
            }
        }
        try {
            SessionJson.stringify(mapOf("x" to Double.NaN))
            fail("NaN accepted")
        } catch (_: SessionJson.JsonError) {
        }
        try {
            SessionJson.stringify(mapOf(1 to "non-string key"))
            fail("non-string key accepted")
        } catch (_: SessionJson.JsonError) {
        }
    }

    // ---- store ----

    @Test
    fun storeRoundTripAndIdFormat() {
        val mgr = manager()
        val record = mgr.newSession()
        assertTrue(SessionStore.isValidId(record.sessionId))
        val loaded = mgr.get(record.sessionId)
        assertEquals(
            SessionJson.stringify(record.toMap()),
            SessionJson.stringify(loaded.toMap()))
        // Atomic write leaves no temp files behind.
        val leftovers = File(temp.root.listFiles()!!.first { it.isDirectory },
            "sessions").listFiles { f -> f.name.contains(".tmp") }
        assertEquals(0, leftovers!!.size)
    }

    @Test
    fun storeRejectsBadIdsAndMissing() {
        val mgr = manager()
        try {
            mgr.get("../escape")
            fail("bad id accepted")
        } catch (_: SessionStore.BadId) {
        }
        try {
            mgr.get("SES-0000000000000000")
            fail("missing accepted")
        } catch (_: SessionStore.NotFound) {
        }
        assertFalse(mgr.deleteSession("not-an-id"))
    }

    @Test
    fun corruptRecordQuarantined() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        val file = File(File(temp.root.listFiles()!!.first { it.isDirectory },
            "sessions"), "$id.json")
        file.writeText("{truncated", Charsets.UTF_8)
        try {
            mgr.get(id)
            fail("corrupt accepted")
        } catch (e: SessionStore.CorruptRecord) {
            assertEquals(id, e.id)
        }
        assertFalse(file.exists())
        assertTrue(mgr.store.quarantineIds().contains("$id.json"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun unsupportedVersionDistinctFromCorrupt() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        val dir = File(temp.root.listFiles()!!.first { it.isDirectory }, "sessions")
        val file = File(dir, "$id.json")
        val map = SessionJson.parse(file.readText()) as Map<String, Any?>
        val bumped = map.toMutableMap()
        bumped["schema_version"] = 999L
        file.writeText(SessionJson.stringify(bumped), Charsets.UTF_8)
        try {
            mgr.get(id)
            fail("unsupported version accepted")
        } catch (e: SessionStore.UnsupportedVersion) {
            assertEquals(999, e.version)
        } catch (e: SessionStore.CorruptRecord) {
            fail("conflated into CorruptRecord: ${e.message}")
        }
    }

    @Test
    fun oversizeRecordQuarantined() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        val dir = File(temp.root.listFiles()!!.first { it.isDirectory }, "sessions")
        File(dir, "$id.json").writeText(
            "\"" + "x".repeat(SessionStore.MAX_READ_BYTES + 1) + "\"",
            Charsets.UTF_8)
        try {
            mgr.get(id)
            fail("oversize accepted")
        } catch (_: SessionStore.CorruptRecord) {
        }
    }

    @Test
    fun storeFullEnforced() {
        val mgr = manager()
        repeat(SessionStore.MAX_SESSIONS) { mgr.newSession() }
        try {
            mgr.newSession()
            fail("over capacity accepted")
        } catch (_: SessionStore.StoreFull) {
        }
    }

    @Test
    fun sessionIdFilenameMismatchRejected() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        val dir = File(temp.root.listFiles()!!.first { it.isDirectory }, "sessions")
        val file = File(dir, "$id.json")
        val map = (SessionJson.parse(file.readText()) as Map<String, Any?>)
            .toMutableMap()
        map["session_id"] = "SES-ffffffffffffffff"
        file.writeText(SessionJson.stringify(map), Charsets.UTF_8)
        try {
            mgr.get(id)
            fail("mismatch accepted")
        } catch (_: SessionStore.CorruptRecord) {
        }
    }

    // ---- manager happy path ----

    @Test
    fun fullSessionLifecycle() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.requestPrediction(id)
        mgr.recordPrediction(id, 15.0, 20.0, """{"p":1}""",
            SessionManager.SOURCE_OFFLINE_TEST, listOf("SYNTH-BENCH-001"), PRED_AT)
        mgr.authorizeBenchmark(id)
        mgr.noteRunStarted(id)
        val attached = SessionBenchBridge.attach(mgr, id, completedResult())
        assertEquals(SessionState.COMPARISON_READY, attached.state)
        @Suppress("UNCHECKED_CAST")
        val comparison = attached.comparison as Map<String, Any?>
        assertEquals(true, comparison["comparable"])
        assertEquals(-5.8, comparison["gen_signed"])
        assertEquals(5.8, comparison["gen_abs"])
        assertEquals(-38.67, comparison["gen_pct"])
        mgr.requestFollowup(id)
        mgr.recordRecommendation(id, mapOf("action" to "retest"), "mock")
        val done = mgr.finishSession(id, "completed", "test done")
        assertEquals(SessionState.SESSION_FINISHED, done.state)
        assertEquals("completed", done.outcome)
        assertEquals(1, done.budgetUsed)
        assertTrue(mgr.deleteSession(id))
        assertFalse(mgr.store.exists(id))
    }

    // ---- integrity ----

    @Test
    fun predictionImmutableAfterRecording() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.toRunning(id)
        try {
            mgr.recordPrediction(id, 99.0, 99.0, "{}", 
                SessionManager.SOURCE_OFFLINE_TEST, emptyList())
            fail("second prediction accepted")
        } catch (_: SessionManager.SessionError) {
        }
        // No retroactive prediction for a completed benchmark either.
        SessionBenchBridge.attach(mgr, id, completedResult())
        try {
            mgr.recordPrediction(id, 9.2, 22.5, "{}", 
                SessionManager.SOURCE_OFFLINE_TEST, emptyList())
            fail("retroactive prediction accepted")
        } catch (_: SessionManager.SessionError) {
        }
    }

    @Test
    fun authorizeRequiresRecordedPrediction() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        try {
            mgr.authorizeBenchmark(id)
            fail("authorized without prediction")
        } catch (_: SessionManager.SessionError) {
        }
    }

    @Test
    fun invalidPredictionValuesRejected() {
        val mgr = manager()
        for (bad in listOf(
            Double.NaN to 20.0, 15.0 to Double.POSITIVE_INFINITY,
            0.0 to 20.0, 15.0 to -1.0
        )) {
            val id = mgr.newSession().sessionId
            mgr.requestPrediction(id)
            try {
                mgr.recordPrediction(id, bad.first, bad.second, "{}",
                    SessionManager.SOURCE_OFFLINE_TEST, emptyList())
                fail("accepted $bad")
            } catch (_: SessionManager.SessionError) {
            }
        }
    }

    @Test
    fun identityMismatchesRejected() {
        val mgr = manager()
        // Model mismatch.
        var id = mgr.newSession().sessionId
        mgr.toRunning(id)
        var bad = completedResult().copy(
            model = completedResult().model.copy(sha256 = "0".repeat(64)))
        try {
            SessionBenchBridge.attach(mgr, id, bad)
            fail("model mismatch accepted")
        } catch (_: SessionManager.SessionError) {
        }
        // Workload mismatch.
        id = mgr.newSession().sessionId
        mgr.toRunning(id)
        bad = completedResult().copy(
            workload = completedResult().workload.copy(id = "other-prompt-v9"))
        try {
            SessionBenchBridge.attach(mgr, id, bad)
            fail("workload mismatch accepted")
        } catch (_: SessionManager.SessionError) {
        }
        // Config mismatch.
        id = mgr.newSession().sessionId
        mgr.toRunning(id)
        bad = completedResult().copy(
            config = completedResult().config.copy(nThreads = 8))
        try {
            SessionBenchBridge.attach(mgr, id, bad)
            fail("config mismatch accepted")
        } catch (_: SessionManager.SessionError) {
        }
        // Device mismatch.
        id = mgr.newSession().sessionId
        mgr.toRunning(id)
        bad = completedResult().copy(
            device = completedResult().device.copy(model = "Pixel-9"))
        try {
            SessionBenchBridge.attach(mgr, id, bad)
            fail("device mismatch accepted")
        } catch (_: SessionManager.SessionError) {
        }
    }

    @Test
    fun measurementPredatingPredictionRejected() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.toRunning(id)
        val early = completedResult().copy(collectedAtUtc = "2020-01-01T00:00:00Z")
        try {
            SessionBenchBridge.attach(mgr, id, early)
            fail("backdated measurement accepted")
        } catch (_: SessionManager.SessionError) {
        }
    }

    @Test
    fun replayExemptFromTimeOrderButLabeled() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.requestPrediction(id)
        mgr.recordPrediction(id, 15.0, 20.0, "{}", 
            SessionManager.SOURCE_REPLAY, emptyList(), BENCH_AT)
        mgr.authorizeBenchmark(id)
        mgr.noteRunStarted(id)
        val early = completedResult().copy(collectedAtUtc = "2020-01-01T00:00:00Z")
        val attached = SessionBenchBridge.attach(mgr, id, early)
        assertEquals(SessionState.COMPARISON_READY, attached.state)
        @Suppress("UNCHECKED_CAST")
        val prediction = attached.prediction as Map<String, Any?>
        assertEquals(SessionManager.SOURCE_REPLAY, prediction["source_type"])
    }

    @Test
    fun duplicateRunAcrossSessionsRejected() {
        val mgr = manager()
        val first = mgr.newSession().sessionId
        mgr.toRunning(first)
        SessionBenchBridge.attach(mgr, first, completedResult())
        val second = mgr.newSession().sessionId
        mgr.toRunning(second)
        try {
            SessionBenchBridge.attach(mgr, second, completedResult())
            fail("run attached to two sessions")
        } catch (_: SessionManager.SessionError) {
        }
    }

    @Test
    fun duplicateDeliveryOfSameRunIsIdempotent() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.toRunning(id)
        val result = completedResult()
        val once = SessionBenchBridge.attach(mgr, id, result)
        val twice = SessionBenchBridge.attach(mgr, id, result)
        assertEquals(
            SessionJson.stringify(once.benchmark),
            SessionJson.stringify(twice.benchmark))
        assertEquals(1, twice.budgetUsed)
    }

    @Test
    fun markBenchmarkFailedReleasesRunningWithoutAttach() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.toRunning(id)
        val failed = mgr.markBenchmarkFailed(id, "attach failed: mismatch")
        assertEquals(SessionState.BENCHMARK_FAILED, failed.state)
        assertNull(failed.benchmark)
        assertTrue(failed.history.last()["cause"]!!.contains("mismatch"))
    }

    @Test
    fun failedRunRecordedWithoutComparison() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.toRunning(id)
        val failed = completedResult().copy(
            status = BenchContract.STATUS_FAILED, error = "native boom")
        val attached = SessionBenchBridge.attach(mgr, id, failed)
        assertEquals(SessionState.BENCHMARK_FAILED, attached.state)
        assertNull(attached.comparison)
    }

    // ---- comparison ----

    @Test
    fun comparisonMatchesParityVectors() {
        for ((index, v) in PARITY_VECTORS.withIndex()) {
            val result = SessionComparison.compare(v[0], v[1], v[2], v[3])
            assertTrue("vector $index comparable", result.comparable)
            assertEquals("vector $index gen_signed", v[4], result.genSigned, 0.0)
            assertEquals("vector $index gen_abs", v[5], result.genAbs, 0.0)
            assertEquals("vector $index gen_pct", v[6], result.genPct, 0.0)
            assertEquals("vector $index pre_signed", v[7], result.prefillSigned, 0.0)
            assertEquals("vector $index pre_abs", v[8], result.prefillAbs, 0.0)
            assertEquals("vector $index pre_pct", v[9], result.prefillPct, 0.0)
        }
    }

    @Test
    fun comparisonGuardsDegenerateValues() {
        for (args in listOf(
            doubleArrayOf(0.0, 20.0, 9.2, 22.5),
            doubleArrayOf(-1.0, 20.0, 9.2, 22.5),
            doubleArrayOf(15.0, 20.0, -0.5, 22.5),
            doubleArrayOf(Double.NaN, 20.0, 9.2, 22.5),
            doubleArrayOf(15.0, 20.0, 9.2, Double.POSITIVE_INFINITY)
        )) {
            val result = SessionComparison.compare(args[0], args[1], args[2], args[3])
            assertFalse(result.comparable)
        }
        // Zero measured throughput is comparable (total failure to generate).
        val zero = SessionComparison.compare(15.0, 20.0, 0.0, 0.0)
        assertTrue(zero.comparable)
        assertEquals(-100.0, zero.genPct, 0.0)
    }

    // ---- bridge ----

    @Test
    fun sourceHashIsDeterministicAndMatchesExport() {
        val result = completedResult()
        val first = SessionBenchBridge.sourceHash(result)
        val second = SessionBenchBridge.sourceHash(result)
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertNotEquals(first, SessionBenchBridge.sourceHash(
            result.copy(runId = "BENCH-20261027T010000Z-00000000")))
    }

    @Test
    fun bridgeConfigKeysMatchContract() {
        val map = SessionBenchBridge.configMap(BenchConfig.default())
        assertEquals(512, map["n_ctx"])
        assertEquals(4, map["n_threads"])
        assertEquals(64, map["n_predict"])
        assertEquals("greedy", map["sampler"])
    }

    // ---- recovery ----

    @Test
    fun recoveryMarksInterruptedRunningFailed() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.toRunning(id)
        val actions = mgr.recoverOnLaunch()
        assertEquals(1, actions.size)
        val recovered = mgr.get(id)
        assertEquals(SessionState.BENCHMARK_FAILED, recovered.state)
        assertTrue(recovered.recoveryNotes.joinToString().contains("resumption"))
    }

    @Test
    fun recoveryResetsStuckPredictionPending() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        mgr.requestPrediction(id)
        mgr.recoverOnLaunch()
        assertEquals(SessionState.SESSION_CREATED, mgr.get(id).state)
    }

    @Test
    fun recoveryPreservesResumableAndTerminalStates() {
        val mgr = manager()
        val authorized = mgr.newSession().sessionId
        mgr.requestPrediction(authorized)
        mgr.recordPrediction(authorized, 15.0, 20.0, "{}", 
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(), PRED_AT)
        mgr.authorizeBenchmark(authorized)
        val finished = mgr.newSession().sessionId
        mgr.requestPrediction(finished)
        mgr.recordPrediction(finished, 15.0, 20.0, "{}", 
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(), PRED_AT)
        mgr.authorizeBenchmark(finished)
        mgr.noteRunStarted(finished)
        SessionBenchBridge.attach(mgr, finished, completedResult())
        mgr.requestFollowup(finished)
        mgr.recordRecommendation(finished, mapOf("a" to "b"), "mock")
        mgr.finishSession(finished, "completed", "done")
        mgr.recoverOnLaunch()
        assertEquals(SessionState.BENCHMARK_AUTHORIZED, mgr.get(authorized).state)
        assertEquals(SessionState.SESSION_FINISHED, mgr.get(finished).state)
    }

    @Test
    fun parsePredictedAcceptsFinitePositiveOnly() {
        assertEquals(15.0, SessionView.parsePredicted("15.0"))
        assertEquals(0.5, SessionView.parsePredicted("  0.5 "))
        assertNull(SessionView.parsePredicted(""))
        assertNull(SessionView.parsePredicted("fast"))
        assertNull(SessionView.parsePredicted("0"))
        assertNull(SessionView.parsePredicted("-3"))
        assertNull(SessionView.parsePredicted("NaN"))
        assertNull(SessionView.parsePredicted("Infinity"))
    }

    @Test
    fun viewSummaryReflectsLifecycle() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        assertTrue(SessionView.buildSummary(mgr.get(id))
            .contains("prediction: not recorded"))
        mgr.toRunning(id)
        val pending = SessionView.buildSummary(mgr.get(id))
        assertTrue(pending.contains("15.0 tok/s"))
        assertTrue(pending.contains("[offline_test]"))
        assertTrue(pending.contains("benchmark: not attached"))
        val attached = SessionBenchBridge.attach(mgr, id, completedResult())
        val done = SessionView.buildSummary(attached)
        assertTrue(done.contains("BENCH-20261027T010000Z-abcdef12 COMPLETED"))
        assertTrue(done.contains("comparison: gen -5.8 tok/s (-38.67%)"))
    }

    @Test
    fun viewComparisonLineHandlesNoncomparable() {
        assertEquals(
            "comparison: noncomparable (predicted throughput must be > 0)",
            SessionView.comparisonLine(mapOf(
                "comparable" to false,
                "reason" to "predicted throughput must be > 0")))
    }

    @Test
    fun viewActiveLineDistinguishesStandalone() {
        assertTrue(SessionView.buildActiveLine(null, null).contains("standalone"))
        val mgr = manager()
        val record = mgr.newSession()
        assertTrue(SessionView.buildActiveLine(record.sessionId, record)
            .contains(record.sessionId))
    }

    @Test
    fun longHistorySurvivesRoundTrip() {
        val mgr = manager()
        val id = mgr.newSession().sessionId
        val long = (1..40).map {
            mapOf("state" to SessionState.SESSION_CREATED,
                "at" to PRED_AT, "cause" to "x")
        }
        mgr.store.save(mgr.get(id).copy(history = long))
        // Load never truncates; the manager caps new pushes at 32.
        assertEquals(40, mgr.get(id).history.size)
        assertEquals(32, SessionStore.MAX_HISTORY)
    }
}
