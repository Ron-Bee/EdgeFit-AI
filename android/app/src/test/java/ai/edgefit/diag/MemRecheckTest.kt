package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * EF-MEM-01: bounded recheck eligibility, evidence shaping,
 * refusal presentation, durable failure cause, and
 * serialization of the `x_mem_recheck` block.
 */
class MemRecheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024L

    // ---- near-threshold window ----

    @Test
    fun exactThresholdNeedsNoRecheck() {
        assertFalse(MemRecheck.nearThreshold(2 * gb, 2 * gb))
        assertFalse(MemRecheck.nearThreshold(3 * gb, 2 * gb))
    }

    @Test
    fun observed28MbShortfallQualifies() {
        // The S22 Ultra refusal: 2020 vs 2048 MB.
        assertTrue(MemRecheck.nearThreshold(
            2020 * mb, 2048 * mb))
    }

    @Test
    fun windowBoundariesExact() {
        val required = 2 * gb
        assertTrue(MemRecheck.nearThreshold(
            required - 1, required))
        assertTrue(MemRecheck.nearThreshold(
            required - MemRecheck.NEAR_WINDOW_BYTES, required))
        assertFalse(MemRecheck.nearThreshold(
            required - MemRecheck.NEAR_WINDOW_BYTES - 1, required))
        assertFalse(MemRecheck.nearThreshold(required / 2, required))
    }

    @Test
    fun invalidReadingsNeverQualify() {
        assertFalse(MemRecheck.nearThreshold(0, 2 * gb))
        assertFalse(MemRecheck.nearThreshold(-1, 2 * gb))
        assertFalse(MemRecheck.nearThreshold(gb, 0))
        assertFalse(MemRecheck.nearThreshold(gb, -1))
    }

    // ---- eligibility ----

    @Test
    fun recheckOnlyForSoleNearMemoryMiss() {
        val memory = listOf(BenchGuards.MEMORY_REFUSAL)
        assertTrue(MemRecheck.shouldRecheck(
            memory, 2020 * mb, 2048 * mb))
        assertFalse(MemRecheck.shouldRecheck(
            memory, gb, 2 * gb))
        assertFalse(MemRecheck.shouldRecheck(
            memory, 2 * gb, 2 * gb))
        assertFalse(MemRecheck.shouldRecheck(
            memory + "thermal state unsafe: severe",
            2020 * mb, 2048 * mb))
        assertFalse(MemRecheck.shouldRecheck(
            listOf("thermal state unsafe: severe"),
            2020 * mb, 2048 * mb))
        assertFalse(MemRecheck.shouldRecheck(
            emptyList(), 2020 * mb, 2048 * mb))
        assertFalse(MemRecheck.shouldRecheck(
            listOf("model size out of bounds"), 0, 2 * gb))
    }

    @Test
    fun memoryRefusalDetectedFromErrorText() {
        assertTrue(MemRecheck.isMemoryRefusal(
            "insufficient available memory " +
                "(available 2020 MB, required 2048 MB)"))
        assertTrue(MemRecheck.isMemoryRefusal(
            "insufficient available memory (available 1 MB, " +
                "required 2048 MB; memory recheck 3 samples)"))
        assertFalse(MemRecheck.isMemoryRefusal(
            "thermal state unsafe: severe"))
        assertFalse(
            MemRecheck.isMemoryRefusal("model load failed"))
        assertFalse(MemRecheck.isMemoryRefusal(
            "cancelled during memory recheck (memory recheck " +
                "1 samples (2020→2021 MB), still below " +
                "required 2048 MB)"))
        assertFalse(MemRecheck.isMemoryRefusal(null))
    }

    // ---- evidence shaping ----

    private fun evidence(admitted: Boolean): MemRecheckEvidence =
        MemRecheckEvidence(
            requiredBytes = 2048 * mb,
            initialAvailBytes = 2020 * mb,
            initialLowMemory = false,
            samples = listOf(
                MemRecheckSample(2025 * mb, false, 1000L),
                MemRecheckSample(2055 * mb, false, 1500L)),
            admitted = admitted,
            recheckMs = 1010L)

    @Test
    fun summarizeStatesOutcome() {
        val refused = MemRecheck.summarize(evidence(false))
        assertTrue(refused.contains("2 samples"))
        assertTrue(refused.contains("2020→2025→2055 MB"))
        assertTrue(refused.contains("still below required 2048 MB"))
        val admitted = MemRecheck.summarize(evidence(true))
        assertTrue(admitted.contains("admitted on sample 2"))
    }

    @Test
    fun evidenceMapCarriesSamples() {
        val map = MemRecheck.toMap(evidence(false))
        assertEquals(2048 * mb, map["required_bytes"])
        assertEquals(2020 * mb, map["initial_avail_bytes"])
        assertEquals(false, map["initial_low_memory"])
        assertEquals(2, map["rechecks_attempted"])
        assertEquals(false, map["admitted"])
        assertEquals(1010L, map["recheck_ms"])
        @Suppress("UNCHECKED_CAST")
        val samples = map["samples"] as List<Map<String, Any?>>
        assertEquals(2, samples.size)
        assertEquals(2025 * mb, samples[0]["avail_bytes"])
        assertEquals(1000L, samples[0]["elapsed_realtime_ms"])
    }

    // ---- serialization ----

    private fun completedResult(): BenchResult =
        BenchResult(
            runId = "BENCH-20261027T000000Z-abcdef12",
            device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
                listOf("arm64-v8a"), 8, 12884901888L),
            model = BenchModelIdentity(
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807694464L,
                "1".repeat(64), "4",
                mapOf("general.architecture" to "llama")),
            runtimeVersion = "b6000-test",
            runtimeAbi = "arm64-v8a",
            config = BenchConfig.default(),
            workload = BenchConfig.workload(),
            measurements = BenchMeasurements(1200L, 300L, 40, 3200L, 64,
                BenchConfig.tps(64, 3200L), BenchConfig.tps(40, 300L),
                false,
                BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
                BenchThermal("none", "light", "light", true)),
            status = BenchContract.STATUS_COMPLETED,
            error = null,
            appVersion = "0.2.0",
            collectedAtUtc = "2026-10-27T00:00:00Z")

    @Test
    fun recheckBlockSerializedWhenPresent() {
        val plain = BenchResultJson.serialize(completedResult())
        assertFalse(plain.contains("x_mem_recheck"))
        val stamped = BenchResultJson.serialize(
            completedResult().copy(xMemRecheck = evidence(true)))
        assertTrue(stamped.contains("\"x_mem_recheck\":"))
        assertTrue(stamped.contains("\"admitted\":true"))
        assertTrue(stamped.contains("\"rechecks_attempted\":2"))
        assertTrue(stamped.contains("\"elapsed_realtime_ms\":1000"))
    }

    @Test
    fun refusedResultCarriesErrorAndBlock() {
        val refused = completedResult().copy(
            status = BenchContract.STATUS_FAILED,
            error = "insufficient available memory " +
                "(available 2020 MB, required 2048 MB; " +
                "memory recheck 2 samples (2020→2025→2031 MB), " +
                "still below required 2048 MB)",
            xMemRecheck = evidence(false))
        val json = BenchResultJson.serialize(refused)
        assertTrue(json.contains("insufficient available memory"))
        assertTrue(json.contains("\"x_mem_recheck\":"))
    }

    // ---- recheck dialog content ----

    @Test
    fun recheckSectionsPassAndRefuse() {
        val pass = InsightCards.memoryRecheckSections(
            "mock.gguf", 3 * gb, 2 * gb, false, emptyList())
        val passBody = pass.flatMap { it.rows }.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(passBody.contains("Verdict=Pass"))
        assertTrue(passBody.contains("Difference=+1024 MB"))
        assertTrue(passBody.contains("No run started"))
        val refuse = InsightCards.memoryRecheckSections(
            "mock.gguf", 2020 * mb, 2048 * mb, true,
            listOf(BenchGuards.MEMORY_REFUSAL))
        val refuseBody = refuse.flatMap { it.rows }.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(refuseBody.contains("Would refuse"))
        assertTrue(refuseBody.contains(BenchGuards.MEMORY_REFUSAL))
        assertTrue(refuseBody.contains("Difference=-28 MB"))
        assertTrue(refuseBody.contains("low-memory state=yes"))
        assertTrue(refuse.any {
            it.rows.any { row -> row.label == "Verdict" } &&
                it.rows.first { row -> row.label == "Verdict" }
                    .accent == InsightCards.Accent.ERROR
        })
    }

    @Test
    fun recheckSectionsWithoutModel() {
        val sections = InsightCards.memoryRecheckSections(
            null, 3 * gb, 0L, false, emptyList())
        val body = sections.flatMap { it.rows }.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(body.contains("No model staged"))
    }

    // ---- refusal details ----

    @Test
    fun refusalDetailsWithoutRecheck() {
        val sections = InsightCards.refusalDetailSections(
            "thermal state unsafe: severe", null)
        val body = sections.flatMap { it.rows }.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(body.contains("Reason=thermal state unsafe"))
        assertTrue(body.contains("not attempted"))
    }

    @Test
    fun refusalDetailsWithRecheck() {
        val sections = InsightCards.refusalDetailSections(
            BenchGuards.MEMORY_REFUSAL, evidence(false))
        val body = sections.flatMap { it.rows }.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(body.contains("still below requirement"))
        assertTrue(body.contains("Required=2048 MB"))
        assertTrue(body.contains("Initial reading=2020 MB"))
        assertTrue(body.contains("2025 MB, 2055 MB"))
        assertTrue(body.contains("outside the measurement window"))
        val admitted = InsightCards.refusalDetailSections(
            null, evidence(true))
        val admittedBody =
            admitted.flatMap { it.rows }.joinToString("\n") {
                "${it.label}=${it.value}"
            }
        assertTrue(admittedBody.contains("admitted on a fresh sample"))
    }

    @Test
    fun refusalDetailsCancelledBeforeSampling() {
        val cancelled = evidence(false).copy(samples = emptyList())
        val sections = InsightCards.refusalDetailSections(
            "cancelled during memory recheck", cancelled)
        val body = sections.flatMap { it.rows }.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(body.contains("cancelled before sampling"))
    }

    // ---- durable failure cause ----

    private fun manager(): SessionManager =
        SessionManager(temp.newFolder()) { "2026-10-28T21:12:52Z" }

    private fun running(manager: SessionManager): SessionRecord {
        val created = manager.createSession(
            mode = "byok", deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = "1".repeat(64),
            config = SessionBenchBridge.configMap(BenchConfig.default()))
        manager.requestPrediction(created.sessionId)
        manager.recordPrediction(created.sessionId, 11.0, 55.0,
            """{"predicted_gen_tps":11.0}""",
            SessionManager.SOURCE_LIVE_META, emptyList())
        manager.authorizeBenchmark(created.sessionId, "test")
        manager.noteRunStarted(created.sessionId)
        return manager.get(created.sessionId)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun failedAttachStoresFailureCause() {
        val manager = manager()
        val session = running(manager)
        val done = manager.attachBenchmark(
            id = session.sessionId,
            runId = "BENCH-20261028T211252Z-aaaa1111",
            sourceSha256 = "ab".repeat(32),
            status = BenchContract.STATUS_FAILED,
            measurements = mapOf(
                "tps_gen" to 0.0, "tps_prefill" to 0.0),
            collectedAtUtc = "2026-10-28T21:12:52Z",
            modelSha256 = "0".repeat(64),
            workloadId = BenchContract.WORKLOAD_ID,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            deviceModel = null,
            failureCause = "insufficient available memory " +
                "(available 2020 MB, required 2048 MB)")
        assertEquals(SessionState.BENCHMARK_FAILED, done.state)
        val bench = done.benchmark as Map<String, Any?>
        assertTrue((bench["failure_cause"] as String)
            .contains("insufficient available memory"))
        // Durable reason surfaces in Session Details.
        val detail = InsightCards.sessionSections(done)
            .flatMap { it.rows }.joinToString("\n") {
                "${it.label}=${it.value}"
            }
        assertTrue(detail.contains("Cause=insufficient"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun successAttachOmitsFailureCause() {
        val manager = manager()
        val session = running(manager)
        val done = manager.attachBenchmark(
            id = session.sessionId,
            runId = "BENCH-20261028T211252Z-bbbb2222",
            sourceSha256 = "ab".repeat(32),
            status = BenchContract.STATUS_COMPLETED,
            measurements = mapOf(
                "tps_gen" to 16.0, "tps_prefill" to 20.0),
            collectedAtUtc = "2026-10-28T21:12:52Z",
            modelSha256 = "0".repeat(64),
            workloadId = BenchContract.WORKLOAD_ID,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            deviceModel = null)
        val bench = done.benchmark as Map<String, Any?>
        assertFalse(bench.containsKey("failure_cause"))
        val detail = InsightCards.sessionSections(done)
            .flatMap { it.rows }.joinToString("\n") {
                "${it.label}=${it.value}"
            }
        assertFalse(detail.contains("Cause="))
    }
}
