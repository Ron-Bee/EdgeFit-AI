package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for Standard Benchmark Workload transparency. */
class BenchWorkloadTest {

    private val recordedSha =
        "36104db0657a294048410f9043d4efc8510a77088ca1e2f17c0b6c8359c44382"

    @Test
    fun promptLiteralIsExactAndUnnormalized() {
        // Any whitespace edit breaks this on purpose: the prompt is frozen.
        assertEquals(
            "Explain in exactly three short sentences how CPU thread count " +
                "affects autoregressive text generation speed on a phone.",
            BenchContract.WORKLOAD_PROMPT
        )
        assertEquals(BenchContract.WORKLOAD_PROMPT.trim(),
            BenchContract.WORKLOAD_PROMPT)
        assertTrue(!BenchContract.WORKLOAD_PROMPT.contains("  "))
    }

    @Test
    fun promptHashMatchesRecordedWorkloadIdentity() {
        assertEquals("edgefit-prompt-v1", BenchContract.WORKLOAD_ID)
        assertEquals(recordedSha, BenchConfig.sha256Hex(
            BenchContract.WORKLOAD_PROMPT.toByteArray(Charsets.UTF_8)))
        val workload = BenchConfig.workload()
        assertEquals(recordedSha, workload.promptSha256)
        assertEquals(BenchContract.WORKLOAD_PROMPT.length, workload.promptChars)
    }

    @Test
    fun displayedTextEmbedsInferencePromptVerbatim() {
        val config = BenchConfig.default(BenchConfig.N_THREADS)
        val text = BenchWorkloadView.buildText(config, null, null)
        assertTrue(text.contains(BenchContract.WORKLOAD_PROMPT))
        assertTrue(text.contains(BenchContract.WORKLOAD_ID))
        assertTrue(text.contains(recordedSha))
        assertTrue(text.contains("tokenize"))
    }

    @Test
    fun displayedConfigMatchesRunnerConfig() {
        val config = BenchConfig.default(BenchConfig.N_THREADS)
        assertTrue(BenchConfig.validate(config).isEmpty())
        val text = BenchWorkloadView.buildText(config, "m.gguf (100 MB)", 23)
        assertTrue(text.contains("Output-token limit: ${config.nPredict}"))
        assertTrue(text.contains("Context size: ${config.nCtx}"))
        assertTrue(text.contains(
            "Sampling: ${config.sampler}, temperature ${config.temperature}"))
        assertTrue(text.contains("m.gguf (100 MB)"))
        assertTrue(text.contains("Prompt tokens: 23"))
    }

    @Test
    fun missingModelAndTokensLabeledHonestly() {
        val config = BenchConfig.default(BenchConfig.N_THREADS)
        val text = BenchWorkloadView.buildText(config, null, null)
        assertTrue(text.contains("not staged yet"))
        assertTrue(text.contains("measured at runtime"))
    }
}
