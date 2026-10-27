package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JVM unit tests for pure benchmark logic (no Android APIs). */
class BenchLogicTest {

    private fun completedResult(): BenchResult = BenchResult(
        runId = "BENCH-20261027T000000Z-abcdef12",
        device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
            listOf("arm64-v8a"), 8, 12884901888L),
        model = BenchModelIdentity("Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807694464L,
            "1".repeat(64), "4", mapOf("general.architecture" to "llama")),
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
        collectedAtUtc = "2026-10-27T00:00:00Z"
    )

    @Test
    fun defaultConfigValid() {
        assertTrue(BenchConfig.validate(BenchConfig.default()).isEmpty())
    }

    @Test
    fun configRangesRejected() {
        val bad = listOf(
            BenchConfig.default().copy(nCtx = 64),
            BenchConfig.default().copy(nCtx = 8192),
            BenchConfig.default().copy(nThreads = 0),
            BenchConfig.default().copy(nThreads = 17),
            BenchConfig.default().copy(nPredict = 0),
            BenchConfig.default().copy(nPredict = 300),
            BenchConfig.default().copy(temperature = 0.5),
            BenchConfig.default().copy(sampler = "top_k"),
            BenchConfig.default().copy(timeoutMs = 0),
            BenchConfig.default().copy(nCtx = 128, nPredict = 128)
        )
        for (config in bad) {
            assertFalse(BenchConfig.validate(config).isEmpty())
        }
    }

    @Test
    fun effectiveThreadsClamps() {
        assertEquals(4, BenchConfig.effectiveThreads(8))
        assertEquals(2, BenchConfig.effectiveThreads(2))
        assertEquals(1, BenchConfig.effectiveThreads(0))
    }

    @Test
    fun tpsMath() {
        assertEquals(20.0, BenchConfig.tps(10, 500L), 0.001)
        assertEquals(0.0, BenchConfig.tps(10, 0L), 0.0)
        assertEquals(0.0, BenchConfig.tps(0, 100L), 0.0)
    }

    @Test
    fun workloadStable() {
        val first = BenchConfig.workload()
        val second = BenchConfig.workload()
        assertEquals(first, second)
        assertTrue(first.promptSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.promptChars > 0)
        assertEquals(BenchContract.WORKLOAD_ID, first.id)
    }

    @Test
    fun guardsPass() {
        val errors = BenchGuards.preflight(
            listOf("arm64-v8a", "armeabi-v7a"), 6L * 1024 * 1024 * 1024,
            807694464L, "light")
        assertTrue(errors.isEmpty())
    }

    @Test
    fun guardsRefuse() {
        val gb = 1024L * 1024 * 1024
        val cases = listOf(
            Triple(listOf("x86_64"), 6 * gb, "light"), // unsupported ABI
            Triple(listOf("arm64-v8a"), 6 * gb, null), // unknown thermal
            Triple(listOf("arm64-v8a"), 6 * gb, "severe"), // unsafe thermal
            Triple(listOf("arm64-v8a"), 0L, "light"), // unknown memory
            Triple(listOf("arm64-v8a"), 1 * gb, "light") // insufficient memory
        )
        for ((abis, avail, thermal) in cases) {
            assertFalse(BenchGuards.preflight(abis, avail, 807694464L, thermal).isEmpty())
        }
        assertFalse(BenchGuards.preflight(
            listOf("arm64-v8a"), 6 * gb, 3 * gb, "light").isEmpty())
    }

    @Test
    fun requiredAvailFormula() {
        val gb = 1024L * 1024 * 1024
        assertEquals(2 * gb, BenchGuards.requiredAvailBytes(100L))
        assertEquals(2 * gb, BenchGuards.requiredAvailBytes(807694464L))
        assertEquals(1610612736L + gb, BenchGuards.requiredAvailBytes(1610612736L))
    }

    @Test
    fun serializeCompleted() {
        val json = BenchResultJson.serialize(completedResult())
        assertTrue(json.contains("\"contract\":\"edgefit-bench-result\""))
        assertTrue(json.contains("\"contract_version\":1"))
        assertTrue(json.contains("\"status\":\"COMPLETED\""))
        assertTrue(json.contains("\"error\":null"))
        assertTrue(json.contains("\"tps_gen\":20.0"))
        assertTrue(json.contains("\"sampler\":\"greedy\""))
    }

    @Test
    fun serializeCancelledKeepsError() {
        val result = completedResult().copy(
            status = BenchContract.STATUS_CANCELLED, error = "cancelled by user")
        val json = BenchResultJson.serialize(result)
        assertTrue(json.contains("\"status\":\"CANCELLED\""))
        assertTrue(json.contains("cancelled by user"))
    }

    @Test
    fun requireConsistentRejects() {
        try {
            BenchResultJson.requireConsistent(
                completedResult().copy(error = " stray"))
            fail("COMPLETED with error")
        } catch (_: IllegalArgumentException) {
        }
        try {
            BenchResultJson.requireConsistent(
                completedResult().copy(status = BenchContract.STATUS_FAILED, error = null))
            fail("FAILED without error")
        } catch (_: IllegalArgumentException) {
        }
        try {
            val broken = completedResult().measurements.copy(tpsGen = 999.0)
            BenchResultJson.requireConsistent(completedResult().copy(measurements = broken))
            fail("bad tps")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun transitions() {
        assertTrue(BenchPhases.can(BenchPhases.IDLE, BenchPhases.RUNNING))
        assertFalse(BenchPhases.can(BenchPhases.IDLE, BenchContract.STATUS_COMPLETED))
        assertFalse(BenchPhases.can(BenchPhases.RUNNING, BenchPhases.IDLE))
        assertTrue(BenchPhases.can(BenchPhases.RUNNING, BenchContract.STATUS_CANCELLED))
        assertTrue(BenchPhases.can(BenchPhases.RUNNING, BenchContract.STATUS_TIMEOUT))
        assertTrue(BenchPhases.can(BenchContract.STATUS_COMPLETED, BenchPhases.IDLE))
        assertFalse(BenchPhases.can(BenchContract.STATUS_COMPLETED, BenchPhases.RUNNING))
        assertTrue(BenchPhases.isTerminal(BenchContract.STATUS_FAILED))
        assertFalse(BenchPhases.isTerminal(BenchPhases.RUNNING))
    }

    @Test
    fun runIdFormat() {
        val id = BenchRunner.formatRunId("20261027T000000Z", "abcdef12")
        assertEquals("BENCH-20261027T000000Z-abcdef12", id)
        assertTrue(id.matches(Regex("BENCH-\\d{8}T\\d{6}Z-[0-9a-f]{8}")))
    }

    @Test
    fun nativeBuildProvenanceOptional() {
        val legacy = BenchResultJson.serialize(completedResult())
        assertFalse(legacy.contains("x_native_build"))
        val stamped = BenchResultJson.serialize(completedResult().copy(
            xNativeBuild = mapOf("variant" to "benchmark", "native_opt" to "-O3 -g")))
        assertTrue(stamped.contains("\"x_native_build\":"))
        assertTrue(stamped.contains("\"variant\":\"benchmark\""))
        assertTrue(stamped.contains("\"native_opt\":\"-O3 -g\""))
    }

    @Test
    fun escaping() {
        val result = completedResult().copy(
            device = completedResult().device.copy(model = "A\"B\nC"))
        val json = BenchResultJson.serialize(result)
        assertTrue(json.contains("\"model\":\"A\\\"B\\nC\""))
    }
}
