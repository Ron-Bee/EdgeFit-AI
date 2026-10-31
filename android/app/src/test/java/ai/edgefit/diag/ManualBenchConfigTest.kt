package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF-COMP-01B-ROUTE: manual A/B config selection. Pure
 * JVM (the dialog is a thin host over [ManualBenchConfig];
 * startBench/runner paths are unchanged).
 */
class ManualBenchConfigTest {

    @Test
    fun defaultBehaviorPreserved() {
        val choice = ManualBenchConfig.choose(
            ManualBenchConfig.ID_DEFAULT, 8)
        assertTrue(choice is ManualBenchConfig.Choice.Selected)
        assertNull((choice as ManualBenchConfig.Choice.Selected).config)
        // Null override resolves to the live device default
        // exactly as before (4 threads on an 8-core phone).
        val resolved = BenchConfig.resolveExecutionConfig(null, 8)
        assertEquals(512, resolved.nCtx)
        assertEquals(4, resolved.nThreads)
        assertEquals(64, resolved.nPredict)
    }

    @Test
    fun explicitAMatchesFrozenStudyCandidate() {
        val choice = ManualBenchConfig.choose(
            ManualBenchConfig.ID_A, 8)
        assertTrue(choice is ManualBenchConfig.Choice.Selected)
        val config = (choice as ManualBenchConfig.Choice.Selected).config!!
        assertEquals(512, config.nCtx)
        assertEquals(4, config.nThreads)
        assertEquals(64, config.nPredict)
        assertTrue(BenchConfig.validate(config).isEmpty())
        // Byte-identical selection contract with the frozen
        // study: the manual UI cannot drift from candidate A.
        assertEquals(EvalStudy.studyCandidateA(), config)
    }

    @Test
    fun explicitBEightThreadsOnQualifyingDevice() {
        val choice = ManualBenchConfig.choose(
            ManualBenchConfig.ID_B, 8)
        assertTrue(choice is ManualBenchConfig.Choice.Selected)
        val config = (choice as ManualBenchConfig.Choice.Selected).config!!
        assertEquals(512, config.nCtx)
        assertEquals(8, config.nThreads)
        assertEquals(64, config.nPredict)
        assertTrue(BenchConfig.validate(config).isEmpty())
        assertEquals(EvalStudy.studyCandidateB(), config)
    }

    @Test
    fun unsupportedThreadCountsBlocked() {
        val fourCore = ManualBenchConfig.choose(
            ManualBenchConfig.ID_B, 4)
        assertTrue(fourCore is ManualBenchConfig.Choice.Refused)
        assertTrue((fourCore as ManualBenchConfig.Choice.Refused)
            .reason.contains("8"))
        val oneCore = ManualBenchConfig.choose(
            ManualBenchConfig.ID_B, 1)
        assertTrue(oneCore is ManualBenchConfig.Choice.Refused)
        val unknown = ManualBenchConfig.choose("c-512-16-64", 16)
        assertTrue(unknown is ManualBenchConfig.Choice.Refused)
        // Options advertise B as unavailable (not absent)
        // so the UI can explain why.
        val options = ManualBenchConfig.options(4)
        val b = options.first { it.id == ManualBenchConfig.ID_B }
        assertTrue(!b.supported)
        assertTrue(b.detail.contains("8"))
    }

    @Test
    fun preRunDisplayNamesExactTripleAndSource() {
        val live = BenchConfig.default(
            BenchConfig.effectiveThreads(8))
        val defaultLine = ManualBenchConfig.describe(null, live)
        assertTrue(defaultLine.contains("threads=4"))
        assertTrue(defaultLine.contains("device default"))
        val b = ManualBenchConfig.configB()
        val manualLine = ManualBenchConfig.describe(b, live)
        assertTrue(manualLine.contains("ctx=512"))
        assertTrue(manualLine.contains("threads=8"))
        assertTrue(manualLine.contains("predict=64"))
        assertTrue(manualLine.contains("manual selection"))
    }

    @Test
    fun executedConfigEqualsExportedConfig() {
        // The runner resolves override verbatim through the
        // single funnel, then serializes result.config from
        // the same resolved value; prove the export
        // round-trips the manual B triple exactly.
        val chosen = (ManualBenchConfig.choose(
            ManualBenchConfig.ID_B, 8)
            as ManualBenchConfig.Choice.Selected).config!!
        val resolved = BenchConfig.resolveExecutionConfig(chosen, 8)
        assertEquals(chosen, resolved)
        val result = BenchResult(
            runId = "BENCH-20261030T130000Z-aaaa0001",
            device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
                listOf("arm64-v8a"), 8, 12884901888L),
            model = BenchModelIdentity(
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807694464L,
                "1".repeat(64), "2", emptyMap()),
            runtimeVersion = "0.7.0-dev",
            runtimeAbi = "arm64-v8a",
            config = resolved,
            workload = BenchConfig.workload(),
            measurements = BenchMeasurements(1200L, 300L, 23, 4450L, 64,
                BenchConfig.tps(64, 4450L), BenchConfig.tps(23, 300L),
                false,
                BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
                BenchThermal("none", "none", "none", true)),
            status = BenchContract.STATUS_COMPLETED,
            error = null,
            appVersion = "0.2.0",
            collectedAtUtc = "2026-10-30T13:00:00Z"
        )
        val json = BenchResultJson.serialize(result)
        @Suppress("UNCHECKED_CAST")
        val root = SessionJson.parse(json) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val exported = SessionBenchBridge.configData(
            root["config"] as Map<String, Any?>)
        assertEquals(resolved, exported)
        assertEquals(8, exported!!.nThreads)
    }

    @Test
    fun displayParityExtendsToManualOptions() {
        // AUD-03 parity (display == execution) must hold
        // for the new manual options too.
        val live = BenchConfig.default(
            BenchConfig.effectiveThreads(8))
        for (id in listOf(
            ManualBenchConfig.ID_DEFAULT,
            ManualBenchConfig.ID_A,
            ManualBenchConfig.ID_B
        )) {
            val chosen = (ManualBenchConfig.choose(id, 8)
                as ManualBenchConfig.Choice.Selected).config
            assertEquals(
                BenchConfig.resolveExecutionConfig(chosen, 8),
                BenchConfig.displayConfig(chosen, live))
        }
    }
}
