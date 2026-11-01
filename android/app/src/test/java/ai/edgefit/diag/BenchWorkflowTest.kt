package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchWorkflowTest {

    private fun record(
        id: String,
        state: String
    ): SessionRecord = SessionRecord(
        sessionId = id, mode = "byok",
        state = state,
        createdAtUtc = "2026-01-01T00:00:00Z",
        updatedAtUtc = "2026-01-01T00:00:00Z",
        deviceModel = null, profileSha256 = null,
        modelSha256 = "0".repeat(64), modelSizeBytes = 1,
        modelDisplay = "m", workloadId = "w",
        promptSha256 = "1".repeat(64),
        config = emptyMap(), prediction = null,
        benchmark = null, comparison = null,
        followup = null, parentSessionId = null,
        experimentSeq = null, budgetUsed = 0,
        budgetMax = 5, agent = null,
        history = emptyList(), recoveryNotes = emptyList()
    )

    @Test
    fun resolveConfigPrecedence() {
        val default = BenchConfig.default()
        val override = ManualBenchConfig.configB()
        val bound = ManualBenchConfig.configA()
        assertEquals(
            "bound session",
            BenchWorkflow.resolveConfig(
                bound, override, default).second)
        assertEquals(
            bound,
            BenchWorkflow.resolveConfig(
                bound, override, default).first)
        assertEquals(
            "manual selection",
            BenchWorkflow.resolveConfig(
                null, override, default).second)
        assertEquals(
            "device default",
            BenchWorkflow.resolveConfig(
                null, null, default).second)
        assertEquals(
            default,
            BenchWorkflow.resolveConfig(
                null, null, default).first)
    }

    @Test
    fun configLineBoundNamesSession() {
        val line = BenchWorkflow.configLine(
            "SES-aaaaaaaaaaaaaaaa", ManualBenchConfig.configB(),
            BenchConfig.default())
        assertTrue(line.contains("SES-aaaaaaaaaaaaaaaa"))
        assertTrue(line.contains("manual selection ignored"))
    }

    @Test
    fun configLineUnboundMatchesDescribe() {
        val override = ManualBenchConfig.configB()
        val default = BenchConfig.default()
        assertEquals(
            ManualBenchConfig.describe(override, default),
            BenchWorkflow.configLine(null, override, default))
        assertEquals(
            ManualBenchConfig.describe(null, default),
            BenchWorkflow.configLine(null, null, default))
    }

    @Test
    fun authStateMatrix() {
        assertEquals(BenchWorkflow.AuthState.NONE,
            BenchWorkflow.authState(null, null))
        assertEquals(BenchWorkflow.AuthState.NOT_AUTHORIZED,
            BenchWorkflow.authState("SES-x", null))
        assertEquals(BenchWorkflow.AuthState.AUTHORIZED,
            BenchWorkflow.authState("SES-x", record("SES-x",
                SessionState.BENCHMARK_AUTHORIZED)))
        assertEquals(BenchWorkflow.AuthState.RUNNING,
            BenchWorkflow.authState("SES-x", record("SES-x",
                SessionState.BENCHMARK_RUNNING)))
        assertEquals(BenchWorkflow.AuthState.NOT_AUTHORIZED,
            BenchWorkflow.authState("SES-x", record("SES-x",
                SessionState.PREDICTION_RECORDED)))
        assertEquals(BenchWorkflow.AuthState.NOT_AUTHORIZED,
            BenchWorkflow.authState("SES-x", record("SES-x",
                SessionState.BENCHMARK_COMPLETED)))
    }

    @Test
    fun authLines() {
        assertEquals(
            SessionView.buildActiveLine(null, null),
            BenchWorkflow.authLine(null, null))
        val authorized = BenchWorkflow.authLine("SES-x",
            record("SES-x",
                SessionState.BENCHMARK_AUTHORIZED))
        assertTrue(authorized.contains("authorized"))
        val running = BenchWorkflow.authLine("SES-x",
            record("SES-x", SessionState.BENCHMARK_RUNNING))
        assertTrue(running.contains("SES-x"))
        val denied = BenchWorkflow.authLine("SES-x",
            record("SES-x",
                SessionState.PREDICTION_RECORDED))
        assertTrue(denied.contains("NOT AUTHORIZED"))
        assertTrue(denied.contains(
            SessionState.PREDICTION_RECORDED))
        val missing = BenchWorkflow.authLine("SES-x", null)
        assertTrue(missing.contains("NOT AUTHORIZED"))
    }

    @Test
    fun runAllowedNeedsStagedModelAndIdleRunner() {
        assertTrue(BenchWorkflow.runAllowed(true, false))
        assertFalse(BenchWorkflow.runAllowed(false, false))
        assertFalse(BenchWorkflow.runAllowed(true, true))
        assertFalse(BenchWorkflow.runAllowed(false, true))
    }

    @Test
    fun runHintsAreDistinct() {
        val hints = setOf(
            BenchWorkflow.runHint(false, false),
            BenchWorkflow.runHint(true, false),
            BenchWorkflow.runHint(true, true))
        assertEquals(3, hints.size)
        assertTrue(hints.all { it.isNotBlank() })
    }

    @Test
    fun modelLineUnstaged() {
        val line = BenchWorkflow.modelLine(
            null, 0L, null, null)
        assertTrue(line.contains("none staged"))
    }

    @Test
    fun modelLineStagedWithOverrideMarker() {
        val override = ManualBenchConfig.configB()
        val line = BenchWorkflow.modelLine(
            "m.gguf", 800L * 1024 * 1024, override, null)
        assertTrue(line.contains("m.gguf"))
        assertTrue(line.contains("800 MB"))
        assertTrue(line.contains("[standalone 512/8/64]"))
        // A bound session parks the override: no marker.
        val bound = BenchWorkflow.modelLine(
            "m.gguf", 800L * 1024 * 1024, override,
            "SES-aaaaaaaaaaaaaaaa")
        assertFalse(bound.contains("[standalone"))
    }
}
