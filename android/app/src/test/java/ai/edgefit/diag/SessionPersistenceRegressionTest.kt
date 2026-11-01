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
 * EF-ANDROID-RECOVERY-INSTALL-01 persistence regression suite.
 * Proves historic records survive restarts, upgrades, recovery,
 * and repeated initialization — and that deletes, quarantine,
 * and the install marker never silently destroy evidence.
 *
 * Deliberately NOT duplicated here: withdrawal retention
 * (EvidenceStoreTest), consent blocking while unapproved
 * (GlobalClientTest "submit blocked while consent policy
 * unapproved"), and quarantine-on-load (SessionStoreTest,
 * SessionScaleTest). The Sessions DIALOG half of secondary
 * discovery needs a physical device (explicit owner gate);
 * the pure filter half is covered below.
 */
class SessionPersistenceRegressionTest {
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

    private fun measuredSession(
        manager: SessionManager, runId: String
    ): SessionRecord {
        val session = seed(manager)
        manager.requestPrediction(session.sessionId)
        manager.recordPrediction(session.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), STAMP)
        manager.authorizeBenchmark(session.sessionId)
        manager.noteRunStarted(session.sessionId)
        return SessionBenchBridge.attach(
            manager, session.sessionId, completedResult(runId))
    }

    // D1/D2: reinit + simulated upgrade over the same root.

    @Test
    fun existingSessionsSurviveStoreReinitialization() {
        val root = temp.newFolder()
        val first = manager(root)
        val created = seed(first)
        val measured = measuredSession(
            first, "BENCH-20261029T010000Z-abcdef12")
        val before = File(root, "sessions/${measured.sessionId}.json")
            .readBytes()
        // Simulated upgrade: a brand-new manager over the same root.
        val second = manager(root)
        val ids = second.listSessions().map { it.sessionId }.toSet()
        assertTrue(ids.contains(created.sessionId))
        assertTrue(ids.contains(measured.sessionId))
        assertEquals(2, second.listSessions().size)
        val after = File(root, "sessions/${measured.sessionId}.json")
            .readBytes()
        assertTrue(before.contentEquals(after))
    }

    // D3: the main UI state model sees reinitialized records.

    @Test
    fun reinitializedRecordsFeedMainUiModel() {
        val root = temp.newFolder()
        val first = manager(root)
        seed(first)
        val measured = measuredSession(
            first, "BENCH-20261029T010000Z-abcdef12")
        val second = manager(root)
        val records = second.listSessions()
        // Newest-first: the measured record (touched last) leads.
        assertEquals(
            measured.sessionId,
            SessionListUi.latestId(records))
        assertEquals(
            records.map { it.sessionId },
            SessionListUi.sortNewestFirst(records).map { it.sessionId })
    }

    // D4 (pure half): secondary filter sees every record.

    @Test
    fun secondaryFilterSeesAllRecordsAfterReinit() {
        val root = temp.newFolder()
        val first = manager(root)
        seed(first)
        seed(first)
        measuredSession(first, "BENCH-20261029T010000Z-abcdef12")
        val records = manager(root).listSessions()
        val shown = SessionListUi.filterSessions(
            records, SessionListUi.SessionFilter.ALL)
        assertEquals(3, shown.size)
        // The Sessions DIALOG refresh half needs a physical device.
    }

    // D5: legacy minimal records (no optional keys) stay readable.

    @Test
    fun legacyMinimalRecordLoadsWithoutOptionalKeys() {
        val root = temp.newFolder()
        val id = "SES-0123456789abcdef"
        val minimal = linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "session_id" to id,
            "mode" to "offline_test",
            "state" to SessionState.SESSION_CREATED,
            "created_at_utc" to STAMP,
            "updated_at_utc" to STAMP,
            "history" to emptyList<Map<String, String>>(),
            "model_sha256" to MODEL_SHA,
            "model_size_bytes" to 100L,
            "model_display" to "mock.gguf",
            "workload_id" to BenchContract.WORKLOAD_ID,
            "prompt_sha256" to PROMPT_SHA,
            "config" to emptyMap<String, Any?>(),
            "recovery_notes" to emptyList<String>(),
            "budget_max" to 5,
            "budget_used" to 0)
        File(root, "sessions").mkdirs()
        File(root, "sessions/$id.json")
            .writeText(SessionJson.stringify(minimal), Charsets.UTF_8)
        val loaded = manager(root).get(id)
        assertEquals(id, loaded.sessionId)
        assertEquals("mock.gguf", loaded.modelDisplay)
        assertEquals(1, manager(root).listSessions().size)
    }

    /**
     * JSON-contract-aware deep equality. SessionJson decodes every
     * integer token as Long and every decimal token as Double, so
     * Kotlin Int/Float wrappers do NOT survive a persist/load cycle.
     * Numbers compare by exact double value; maps require identical
     * key sets; lists require identical size; strings, booleans,
     * and nulls compare exactly (a String "512" never equals 512).
     */
    private fun assertJsonEquivalent(
        expected: Any?,
        actual: Any?,
        path: String = "$"
    ) {
        if (expected is Number && actual is Number) {
            assertEquals("numeric mismatch at $path",
                expected.toDouble(), actual.toDouble(), 0.0)
            return
        }
        if (expected is Map<*, *> && actual is Map<*, *>) {
            assertEquals("key set at $path", expected.keys, actual.keys)
            for (key in expected.keys) {
                assertJsonEquivalent(expected[key], actual[key], "$path[$key]")
            }
            return
        }
        if (expected is List<*> && actual is List<*>) {
            assertEquals("list size at $path", expected.size, actual.size)
            expected.forEachIndexed { index, item ->
                assertJsonEquivalent(item, actual[index], "$path[$index]")
            }
            return
        }
        assertEquals("value mismatch at $path", expected, actual)
    }

    // The codec contract behind D6, pinned explicitly: integer
    // tokens decode as Long, decimals as Double. Values survive;
    // Kotlin wrappers do not. A direct assertEquals across the
    // boundary would be a test bug, never a production signal.

    @Test
    fun jsonCodecNormalizesIntegersToLongWithoutLosingValue() {
        val original = linkedMapOf<String, Any?>(
            "int" to 512,
            "long" to 807694464L,
            "double" to 16.0,
            "negative" to -3,
            "zero_double" to 0.0,
            "nested" to linkedMapOf<String, Any?>(
                "n" to 4, "list" to listOf(1, 2L, 3.0)))
        val parsed = SessionJson.parse(SessionJson.stringify(original))
        assertJsonEquivalent(original, parsed)
        @Suppress("UNCHECKED_CAST")
        val map = parsed as Map<String, Any?>
        assertTrue(map["int"] is Long)
        assertEquals(512L, map["int"])
        assertTrue(map["double"] is Double)
    }

    // D6/D9: measurements value-identical across reinit + recovery
    // under the JSON numeric contract (Int normalizes to Long).

    @Test
    fun measurementsPreservedAcrossReinitAndRecovery() {
        val root = temp.newFolder()
        val runId = "BENCH-20261029T010000Z-abcdef12"
        val measured = measuredSession(manager(root), runId)
        val before = measured.benchmark
        val second = manager(root)
        second.recoverOnLaunch()
        val reloaded = second.get(measured.sessionId)
        // Every key survives with an equivalent value; nothing is
        // dropped, added, or silently changed by the cycle.
        assertJsonEquivalent(before, reloaded.benchmark)
        @Suppress("UNCHECKED_CAST")
        val bench = reloaded.benchmark as Map<String, Any?>
        assertEquals(runId, bench["run_id"])
        // Model/config/workload identity persists exactly.
        assertEquals(measured.modelSha256, reloaded.modelSha256)
        assertEquals(measured.workloadId, reloaded.workloadId)
        assertEquals(measured.modelSizeBytes, reloaded.modelSizeBytes)
        assertJsonEquivalent(measured.config, reloaded.config)
        assertEquals(measured.history, reloaded.history)
        assertEquals(measured.sessionId, reloaded.sessionId)
    }

    // D7: first-launch recovery preserves inventory count + ids.

    @Test
    fun firstLaunchRecoveryPreservesInventory() {
        val root = temp.newFolder()
        val first = manager(root)
        val created = seed(first)
        val running = seed(first)
        first.requestPrediction(running.sessionId)
        first.recordPrediction(running.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), STAMP)
        first.authorizeBenchmark(running.sessionId)
        first.noteRunStarted(running.sessionId)
        val authorized = seed(first)
        first.requestPrediction(authorized.sessionId)
        first.recordPrediction(authorized.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), STAMP)
        first.authorizeBenchmark(authorized.sessionId)
        val second = manager(root)
        second.recoverOnLaunch()
        val ids = second.listSessions().map { it.sessionId }.toSet()
        assertEquals(3, ids.size)
        assertTrue(ids.contains(created.sessionId))
        assertTrue(ids.contains(running.sessionId))
        assertTrue(ids.contains(authorized.sessionId))
        assertEquals(SessionState.BENCHMARK_FAILED,
            second.get(running.sessionId).state)
        assertEquals(SessionState.BENCHMARK_AUTHORIZED,
            second.get(authorized.sessionId).state)
    }

    // D8: one corrupt record cannot take down its neighbors.

    @Test
    fun corruptRecordIsolatedWithoutTouchingValid() {
        val root = temp.newFolder()
        val first = manager(root)
        val keepA = seed(first)
        val keepB = seed(first)
        File(root, "sessions/SES-deadbeefdeadbeef.json")
            .writeText("{not valid json", Charsets.UTF_8)
        val second = manager(root)
        second.recoverOnLaunch()
        val ids = second.listSessions().map { it.sessionId }.toSet()
        assertEquals(2, ids.size)
        assertTrue(ids.contains(keepA.sessionId))
        assertTrue(ids.contains(keepB.sessionId))
        assertEquals(
            "mock.gguf", second.get(keepA.sessionId).modelDisplay)
        assertEquals(1, second.quarantineCount())
    }

    // D10: delete is explicit and removes only its target.

    @Test
    fun localDeleteRemovesOnlyTargetSession() {
        val root = temp.newFolder()
        val mgr = manager(root)
        val drop = seed(mgr)
        val keep = seed(mgr)
        assertTrue(mgr.deleteSession(drop.sessionId))
        assertFalse(mgr.store.exists(drop.sessionId))
        assertTrue(mgr.store.exists(keep.sessionId))
        assertEquals("mock.gguf", mgr.get(keep.sessionId).modelDisplay)
        assertEquals(1, mgr.listSessions().size)
    }

    // D13: install marker mismatch never rewrites or deletes.

    @Test
    fun installMarkerMismatchNeverResetsEvidence() {
        val root = temp.newFolder()
        val mgr = manager(root)
        val keep = seed(mgr)
        val first = InstallIdentity.ensure(
            root, "ai.edgefit.diag", 2, STAMP) { "fixed-id-001" }
        val markerBefore =
            File(root, "install.json").readText(Charsets.UTF_8)
        // A "different" launch (upgrade or variant mix-up) must not
        // rotate the marker and must not touch sessions.
        val second = InstallIdentity.ensure(
            root, "ai.edgefit.diag.bench", 3, STAMP) { "intruder-id" }
        assertEquals(first.installId, second.installId)
        assertEquals(
            markerBefore,
            File(root, "install.json").readText(Charsets.UTF_8))
        assertTrue(InstallIdentity.matches(first, "ai.edgefit.diag", 2))
        assertFalse(
            InstallIdentity.matches(first, "ai.edgefit.diag.bench", 3))
        assertEquals("mock.gguf", mgr.get(keep.sessionId).modelDisplay)
        assertEquals(1, mgr.listSessions().size)
    }

    // D14: repeated construction over one root is idempotent.

    @Test
    fun repeatedInitializationIsIdempotent() {
        val root = temp.newFolder()
        val ids = setOf(seed(manager(root)).sessionId,
            seed(manager(root)).sessionId)
        repeat(3) {
            val mgr = manager(root)
            assertEquals(
                ids, mgr.listSessions().map { it.sessionId }.toSet())
        }
        val leftovers = File(root, "sessions").listFiles { file ->
            file.name.contains(".json.tmp-")
        } ?: emptyArray()
        assertEquals(0, leftovers.size)
    }

    // D15: repeated relaunch never shrinks the inventory.

    @Test
    fun repeatedRelaunchNeverReducesInventory() {
        val root = temp.newFolder()
        val first = manager(root)
        val running = seed(first)
        first.requestPrediction(running.sessionId)
        first.recordPrediction(running.sessionId, 15.0, 23.0,
            """{"mock":true}""", SessionManager.SOURCE_OFFLINE_TEST,
            emptyList(), STAMP)
        first.authorizeBenchmark(running.sessionId)
        first.noteRunStarted(running.sessionId)
        val keep = seed(first)
        repeat(3) {
            val mgr = manager(root)
            mgr.recoverOnLaunch()
            assertEquals(2, mgr.listSessions().size)
        }
        val last = manager(root)
        assertEquals(SessionState.BENCHMARK_FAILED,
            last.get(running.sessionId).state)
        assertEquals(SessionState.SESSION_CREATED,
            last.get(keep.sessionId).state)
    }

    // D16: a failed write leaves the only copy untouched.

    @Test
    fun failedSavePreservesPriorRecordBytes() {
        val root = temp.newFolder()
        val mgr = manager(root)
        val record = seed(mgr)
        val file = File(root, "sessions/${record.sessionId}.json")
        val before = file.readBytes()
        try {
            mgr.store.save(record.copy(
                config = mapOf("pad" to "x".repeat(70000))))
            fail("oversize save must throw")
        } catch (_: SessionStore.TooLarge) {
            // Expected: the store refuses before touching the file.
        }
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals("mock.gguf", mgr.get(record.sessionId).modelDisplay)
    }

    // New behavior: the quarantine count the UI surfaces.

    @Test
    fun quarantineCountTracksCorruptRecords() {
        val root = temp.newFolder()
        seed(manager(root))
        File(root, "sessions/SES-aaaaaaaaaaaaaaaa.json")
            .writeText("garbage", Charsets.UTF_8)
        File(root, "sessions/SES-bbbbbbbbbbbbbbbb.json")
            .writeText("garbage", Charsets.UTF_8)
        val mgr = manager(root)
        assertEquals(0, mgr.quarantineCount())
        mgr.recoverOnLaunch()
        assertEquals(2, mgr.quarantineCount())
        assertEquals(1, mgr.listSessions().size)
    }

    // New behavior: About installation row with honest fallback.

    @Test
    fun aboutRowShowsInstallMarkerOrHonestFallback() {
        fun ctx(line: String?) = AeroSettings.Context(
            metaKeyStatus = "k",
            globalOrigin = null,
            globalTokenSuffix = null,
            globalTokenPersisted = false,
            versionName = "0.2.0",
            packageName = "ai.edgefit.diag",
            workloadId = BenchContract.WORKLOAD_ID,
            installLine = line)
        val withMarker = AeroSettings.rows(
            AeroSettings.Group.ABOUT, ctx("ai.edgefit.diag · #12345678"))
        assertEquals("ai.edgefit.diag · #12345678",
            withMarker.first { it.label == "Installation" }.detail)
        // Existing callers omit installLine (default null): same rows,
        // honest fallback text, no crash.
        val legacy = AeroSettings.rows(
            AeroSettings.Group.ABOUT, ctx(null))
        assertEquals("Install marker unavailable.",
            legacy.first { it.label == "Installation" }.detail)
    }

    // InstallIdentity one-liner rendering.

    @Test
    fun installStatusLineNamesVariantAndShortId() {
        val line = InstallIdentity.statusLine(InstallIdentity.Record(
            installId = "fixed-id-001",
            applicationId = "ai.edgefit.diag.bench",
            versionCode = 2,
            firstLaunchUtc = STAMP))
        assertTrue(line.contains("ai.edgefit.diag.bench"))
        assertTrue(line.contains(STAMP))
        assertTrue(line.contains("#fixed-id"))
        assertFalse(line.contains("fixed-id-001"))
    }
}
