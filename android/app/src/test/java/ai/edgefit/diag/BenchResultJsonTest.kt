package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JVM unit tests for benchmark-result JSON round-trips. */
class BenchResultJsonTest {

    private fun completedResult(): BenchResult = BenchResult(
        runId = "BENCH-20261103T000000Z-abcdef12",
        device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
            listOf("arm64-v8a"), 8, 12884901888L),
        model = BenchModelIdentity("Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            807694464L, "1".repeat(64), "4",
            mapOf("general.architecture" to "llama")),
        runtimeVersion = "b6000-test",
        runtimeAbi = "arm64-v8a",
        config = BenchConfig.default(),
        workload = BenchConfig.workload(),
        measurements = BenchMeasurements(1200L, 300L, 40, 3200L, 64,
            BenchConfig.tps(64, 3200L), BenchConfig.tps(40, 300L), false,
            BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
            BenchThermal("none", "light", "light", true)),
        status = BenchContract.STATUS_COMPLETED,
        error = null,
        appVersion = "0.2.0",
        collectedAtUtc = "2026-11-03T00:00:00Z"
    )

    private fun fullResult(): BenchResult = completedResult().copy(
        xNativeBuild = mapOf("variant" to "debug", "native_opt" to "-O3"),
        xMemRecheck = MemRecheckEvidence(
            requiredBytes = 100L, initialAvailBytes = 90L,
            initialLowMemory = false,
            samples = listOf(MemRecheckSample(91L, false, 1000L)),
            admitted = true, recheckMs = 500L),
        xMemDiag = MemDiag.RunExport(
            samples = listOf(MemDiag.Sample(
                MemDiag.Checkpoint.PRE_NATIVE, 100L, 200L, false,
                thresholdBytes = 50L, pssKb = 10,
                privateDirtyKb = 9, nativeHeapBytes = 8L,
                javaHeapBytes = 7L)),
            buildVariant = "debug", nativeOpt = "-O3"),
        xMemConditions = MemConditions(
            totalRamBytes = 1000L, availBytesStart = 800L,
            availPctStart = 80.0, warningThresholdPct = 15.0,
            belowThresholdStart = false, lowMemoryStart = false,
            lowThresholdBytes = 100L, availBytesEnd = 700L,
            availPctEnd = 70.0)
    )

    private fun failedResult(): BenchResult = completedResult().copy(
        status = BenchContract.STATUS_FAILED,
        error = "native call failed: boom",
        xFailure = FailureEvidence("VALIDATING_MODEL", false)
    )

    @Test
    fun minimalRoundTrip() {
        val result = completedResult()
        assertEquals(result, BenchResultJson.parse(
            BenchResultJson.serialize(result)))
    }

    @Test
    fun fullExtensionsRoundTrip() {
        val result = fullResult()
        assertEquals(result, BenchResultJson.parse(
            BenchResultJson.serialize(result)))
    }

    @Test
    fun failedRoundTrip() {
        val result = failedResult()
        assertEquals(result, BenchResultJson.parse(
            BenchResultJson.serialize(result)))
    }

    @Test
    fun serializeThenParseSurvivesSessionJson() {
        // The activity snapshot path re-parses through the
        // same codec: double-serialization must be stable.
        val once = BenchResultJson.serialize(fullResult())
        val twice = BenchResultJson.serialize(BenchResultJson.parse(once))
        assertEquals(once, twice)
    }

    @Test
    fun malformedInputsThrowParseError() {
        for (bad in listOf(
            "",
            "{oops",
            "{}",
            "{\"contract\":\"edgefit-bench-result\"}",
            BenchResultJson.serialize(completedResult())
                .replace("COMPLETED", "BOGUS")
        )) {
            try {
                BenchResultJson.parse(bad)
                fail("expected ParseError for: $bad")
            } catch (_: BenchResultJson.ParseError) {
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun corruptExtensionBlockFailsClosed() {
        val root = SessionJson.parse(
            BenchResultJson.serialize(fullResult()))
            as Map<String, Any?>
        val broken = (root + ("x_mem_conditions" to "junk")).let {
            SessionJson.stringify(it)
        }
        try {
            BenchResultJson.parse(broken)
            fail("expected ParseError for corrupt x_ block")
        } catch (_: BenchResultJson.ParseError) {
        }
    }

    @Test
    fun memEvidenceFromMapsRoundTrip() {
        val full = fullResult()
        assertEquals(full.xMemConditions, MemConditions.fromMap(
            full.xMemConditions!!.toMap()))
        assertEquals(full.xMemRecheck, MemRecheck.fromMap(
            MemRecheck.toMap(full.xMemRecheck!!)))
        assertEquals(full.xMemDiag, MemDiag.exportFromMap(
            MemDiag.exportToMap(full.xMemDiag!!)))
        assertEquals(failedResult().xFailure,
            FailureEvidence.fromMap(
                failedResult().xFailure!!.toMap()))
        assertNull(MemConditions.fromMap(emptyMap()))
        assertNull(FailureEvidence.fromMap(emptyMap()))
        assertNull(MemRecheck.fromMap(emptyMap()))
        assertNull(MemDiag.exportFromMap(emptyMap()))
        assertTrue(MemDiag.sampleFromMap(mapOf(
            "checkpoint" to "nope",
            "elapsed_realtime_ms" to 1L,
            "avail_bytes" to 2L,
            "low_memory" to false)) == null)
    }
}
