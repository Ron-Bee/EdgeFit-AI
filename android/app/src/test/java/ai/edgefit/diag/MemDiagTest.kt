package ai.edgefit.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EF-MEM-02 diagnostics, readiness, and shadow budget. */
class MemDiagTest {

    @After
    fun resetStore() {
        MemDiag.Store.setEnabled(false)
    }

    private fun sample(
        checkpoint: MemDiag.Checkpoint = MemDiag.Checkpoint.PRE_ADMISSION,
        elapsedMs: Long = 1_000L,
        avail: Long = 2L * 1024 * 1024 * 1024,
        low: Boolean = false,
        threshold: Long? = 512L * 1024 * 1024,
        pss: Int? = 300_000,
        privDirty: Int? = 200_000,
        nativeHeap: Long? = 100L * 1024 * 1024,
        javaHeap: Long? = 32L * 1024 * 1024
    ) = MemDiag.Sample(checkpoint, elapsedMs, avail, low,
        threshold, pss, privDirty, nativeHeap, javaHeap)

    // ---- validation ----

    @Test
    fun validSamplePasses() {
        assertTrue(MemDiag.validate(sample()).isEmpty())
    }

    @Test
    fun unavailableOptionalsPass() {
        val bare = MemDiag.Sample(
            MemDiag.Checkpoint.APP_IDLE, 0L,
            1024L * 1024 * 1024, false)
        assertTrue(MemDiag.validate(bare).isEmpty())
    }

    @Test
    fun negativeAvailRejected() {
        val errors = MemDiag.validate(sample(avail = -1L))
        assertTrue(errors.any { it.contains("avail_bytes") })
    }

    @Test
    fun zeroAvailRejected() {
        val errors = MemDiag.validate(sample(avail = 0L))
        assertTrue(errors.any { it.contains("avail_bytes") })
    }

    @Test
    fun negativeOptionalsRejected() {
        val errors = MemDiag.validate(
            sample(pss = -5, nativeHeap = -1L, javaHeap = -2L,
                privDirty = -3, threshold = -4L))
        assertEquals(5, errors.size)
    }

    @Test
    fun negativeTimestampRejected() {
        val errors = MemDiag.validate(sample(elapsedMs = -1L))
        assertEquals(1, errors.size)
    }

    // ---- formatting ----

    @Test
    fun formatRoundsDownNeverUp() {
        // 2047.9 MiB must read 2047 MB, never 2048.
        val bytes = 2047L * 1024 * 1024 + 900L * 1024
        assertEquals("2047 MB", MemDiag.formatMb(bytes))
    }

    @Test
    fun unavailableSignalsLabeledNotZeroed() {
        val line = MemDiag.formatLine(
            MemDiag.Sample(MemDiag.Checkpoint.APP_IDLE, 0L,
                1024L * 1024 * 1024, false))
        assertTrue(line.contains("unavailable"))
        assertFalse(line.contains(" 0 MB"))
    }

    @Test
    fun lowMemoryFlaggedInLine() {
        val line = MemDiag.formatLine(sample(low = true))
        assertTrue(line.contains("[LOW-MEMORY]"))
    }

    @Test
    fun checkpointSerialRoundTrips() {
        for (checkpoint in MemDiag.Checkpoint.entries) {
            assertEquals(checkpoint, MemDiag.Checkpoint
                .fromSerial(checkpoint.serial))
        }
        assertNull(MemDiag.Checkpoint.fromSerial("post_native_init"))
    }

    // ---- store gating and bounds ----

    @Test
    fun storeDisabledByDefault() {
        assertFalse(MemDiag.Store.isEnabled())
    }

    @Test
    fun noteIsNoOpWhenDisabled() {
        MemDiag.Store.note(sample())
        assertTrue(MemDiag.Store.snapshot().isEmpty())
    }

    @Test
    fun disablingClearsSamples() {
        MemDiag.Store.setEnabled(true)
        MemDiag.Store.note(sample())
        MemDiag.Store.setEnabled(false)
        assertTrue(MemDiag.Store.snapshot().isEmpty())
    }

    @Test
    fun ringDropsOldestPastCap() {
        MemDiag.Store.setEnabled(true)
        for (n in 1..(MemDiag.Store.MAX_SAMPLES + 5)) {
            MemDiag.Store.note(sample(elapsedMs = n.toLong()))
        }
        val snapshot = MemDiag.Store.snapshot()
        assertEquals(MemDiag.Store.MAX_SAMPLES, snapshot.size)
        assertEquals(6L, snapshot.first().elapsedRealtimeMs)
    }

    @Test
    fun snapshotPreservesArrivalOrder() {
        MemDiag.Store.setEnabled(true)
        MemDiag.Store.note(
            sample(MemDiag.Checkpoint.APP_IDLE, elapsedMs = 10L))
        MemDiag.Store.note(
            sample(MemDiag.Checkpoint.HOME_SHOWN, elapsedMs = 5L))
        val snapshot = MemDiag.Store.snapshot()
        assertEquals(MemDiag.Checkpoint.APP_IDLE,
            snapshot[0].checkpoint)
    }

    // ---- readiness presentation (policy in MemPolicyTest) ----

    @Test
    fun statusLineNeedsModel() {
        assertTrue(MemReadiness.statusLine(null,
            MemPolicy.assess(1024L, 4096L, false))
            .contains("stage a model"))
    }

    @Test
    fun statusLineShowsAdvisoryShare() {
        val line = MemReadiness.statusLine("m.gguf",
            MemPolicy.assess(500L * 1024 * 1024,
                4L * 1024 * 1024 * 1024, false))
        assertTrue(line.contains("12."))
        assertTrue(line.contains("Run stays available"))
    }

    @Test
    fun authNoteNeverBlocksPrediction() {
        // Both branches permit requesting; neither refuses.
        val noModel = MemReadiness.authNote(null,
            MemPolicy.assess(null, null, null))
        assertTrue(noModel.contains("may still be requested"))
        val short = MemReadiness.authNote("m.gguf",
            MemPolicy.assess(100L * 1024 * 1024,
                8L * 1024 * 1024 * 1024, false))
        assertTrue(short.contains("may still be requested"))
        assertTrue(short.contains("not sent to Meta"))
        assertTrue(short.contains("never blocked"))
    }

    @Test
    fun sectionsNeverMentionBlocking() {
        val sections = MemReadiness.sections("m.gguf",
            "ctx 512, 4 threads, 64 tokens, greedy",
            MemPolicy.assess(100L, 8L * 1024 * 1024 * 1024,
                false))
        val text = sections.flatMap { section ->
            section.rows.map { it.label + it.value } +
                listOf(section.details ?: "")
        }.joinToString(" ")
        assertTrue(text.contains("warn-only"))
        assertFalse(text.contains("will refuse"))
    }

    // ---- shadow budget ----

    @Test
    fun shadowEstimateStaysUnknown() {
        val estimate = MemBudget.shadowEstimate(
            770L * 1024 * 1024, "abc", "Q4_K_M", 512, 4,
            "0.7.0-dev")
        assertEquals(MemBudget.CONCLUSION_INSUFFICIENT_DATA,
            estimate.conclusion)
        assertEquals(MemPolicy.WARNING_PCT,
            estimate.advisoryThresholdPct, 0.0)
        assertTrue(estimate.unknownInputs.size >= 5)
    }

    @Test
    fun shadowEchoesAdvisoryThreshold() {
        for (modelBytes in listOf(
            100L * 1024 * 1024, 770L * 1024 * 1024,
            1500L * 1024 * 1024)
        ) {
            val estimate = MemBudget.shadowEstimate(
                modelBytes, null, null, 512, 4, null)
            assertEquals(MemPolicy.WARNING_PCT,
                estimate.advisoryThresholdPct, 0.0)
            assertTrue(estimate.note.contains("advisory-only"))
        }
    }

    // ---- export ----

    @Test
    fun exportMarksNonConfirmatory() {
        val map = MemDiag.exportToMap(MemDiag.RunExport(
            listOf(sample()), "benchmark", "-O2"))
        assertEquals(true, map["instrumented"])
        assertTrue((map["non_confirmatory"] as String)
            .contains("on/off"))
        @Suppress("UNCHECKED_CAST")
        val samples = map["samples"] as List<Map<String, Any?>>
        assertEquals(1, samples.size)
        assertEquals("pre_admission", samples[0]["checkpoint"])
    }

    @Test
    fun exportSerializesUnavailableAsNull() {
        val bare = MemDiag.Sample(MemDiag.Checkpoint.PRE_NATIVE,
            5L, 1024L, false)
        val map = MemDiag.exportToMap(
            MemDiag.RunExport(listOf(bare), "benchmark", "-O2"))
        @Suppress("UNCHECKED_CAST")
        val samples = map["samples"] as List<Map<String, Any?>>
        assertNull(samples[0]["threshold_bytes"])
        assertNull(samples[0]["pss_kb"])
    }
}
