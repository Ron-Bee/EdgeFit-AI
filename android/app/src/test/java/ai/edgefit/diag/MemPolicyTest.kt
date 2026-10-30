package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EF-MEM-03 advisory policy + per-run evidence. */
class MemPolicyTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    // ---- §15 memory-policy matrix ----

    @Test
    fun fourGb500mbAdvisory() {
        val level = MemPolicy.assess(500 * mb, 4 * gb, false).level
        assertEquals(MemPolicy.Level.ADVISORY, level)
    }

    @Test
    fun fourGb100mbSevere() {
        val level = MemPolicy.assess(100 * mb, 4 * gb, false).level
        assertEquals(MemPolicy.Level.SEVERE, level)
    }

    @Test
    fun eightGb1000mbAdvisory() {
        val level = MemPolicy.assess(1000 * mb, 8 * gb, false).level
        assertEquals(MemPolicy.Level.ADVISORY, level)
    }

    @Test
    fun eightGb2500mbQuiet() {
        val assessment = MemPolicy.assess(2500 * mb, 8 * gb, false)
        assertEquals(MemPolicy.Level.OK, assessment.level)
        assertNull(assessment.advisory)
    }

    @Test
    fun exactly15PctIsQuiet() {
        // Exact boundary arithmetic (150/1000 = 15.0).
        val level = MemPolicy.assess(150L, 1000L, false).level
        assertEquals(MemPolicy.Level.OK, level)
        assertEquals(MemPolicy.Level.ADVISORY,
            MemPolicy.assess(149L, 1000L, false).level)
    }

    @Test
    fun justBelow15PctAdvises() {
        val avail = (4 * gb * 15) / 100 - 1
        val assessment = MemPolicy.assess(avail, 4 * gb, false)
        assertEquals(MemPolicy.Level.ADVISORY, assessment.level)
        assertTrue(assessment.advisory!!.contains("15.0%"))
    }

    @Test
    fun severeBoundaryAt5Pct() {
        assertEquals(MemPolicy.Level.ADVISORY,
            MemPolicy.assess(50L, 1000L, false).level)
        assertEquals(MemPolicy.Level.SEVERE,
            MemPolicy.assess(49L, 1000L, false).level)
    }

    @Test
    fun unknownTotalIsUnknown() {
        val assessment = MemPolicy.assess(500 * mb, null, false)
        assertEquals(MemPolicy.Level.UNKNOWN, assessment.level)
        assertNull(assessment.availPct)
        assertTrue(assessment.advisory!!.contains("unknown"))
    }

    @Test
    fun zeroTotalIsUnknown() {
        assertEquals(MemPolicy.Level.UNKNOWN,
            MemPolicy.assess(500 * mb, 0L, false).level)
    }

    @Test
    fun negativeAvailIsUnknown() {
        assertEquals(MemPolicy.Level.UNKNOWN,
            MemPolicy.assess(-1L, 4 * gb, false).level)
    }

    @Test
    fun availAboveTotalIsUnknown() {
        // Physically impossible reading: do not fabricate.
        assertEquals(MemPolicy.Level.UNKNOWN,
            MemPolicy.assess(5 * gb, 4 * gb, false).level)
    }

    @Test
    fun lowMemoryTrueIsSeparateAdvisory() {
        // Above threshold but lowMemory: stays OK (the CPU
        // guard never consults lowMemory) with a caveat.
        val assessment = MemPolicy.assess(2500 * mb, 8 * gb, true)
        assertEquals(MemPolicy.Level.OK, assessment.level)
        assertTrue(assessment.advisory!!.contains("low-memory"))
    }

    @Test
    fun lowMemoryFalseClaimsNoSafety() {
        val assessment = MemPolicy.assess(2500 * mb, 8 * gb, false)
        assertNull(assessment.advisory)
        // And the preflight still refuses unknown thermal
        // regardless of memory readings (see below).
    }

    // ---- the old gate is gone; other guards remain ----

    @Test
    fun no2GiBHardGate() {
        // The MEM-01 refusal shape (2020 MB free vs 2048
        // required) must NOT refuse anymore.
        val errors = BenchGuards.preflight(
            listOf(BenchGuards.REQUIRED_ABI),
            2020 * mb, 770 * mb, "none")
        assertTrue(errors.isEmpty())
    }

    @Test
    fun zeroAndUnknownMemoryProceed() {
        assertTrue(BenchGuards.preflight(
            listOf(BenchGuards.REQUIRED_ABI), 0L,
            770 * mb, "none").isEmpty())
        assertTrue(BenchGuards.preflight(
            listOf(BenchGuards.REQUIRED_ABI), -1L,
            770 * mb, "none").isEmpty())
    }

    @Test
    fun nonMemoryGuardsStillRefuse() {
        assertTrue(BenchGuards.preflight(
            listOf("armeabi"), 8 * gb, 770 * mb, "none")
            .any { it.contains("ABI") })
        assertTrue(BenchGuards.preflight(
            listOf(BenchGuards.REQUIRED_ABI), 8 * gb,
            0L, "none").any { it.contains("model size") })
        assertTrue(BenchGuards.preflight(
            listOf(BenchGuards.REQUIRED_ABI), 8 * gb,
            770 * mb, "severe").any { it.contains("thermal") })
        assertTrue(BenchGuards.preflight(
            listOf(BenchGuards.REQUIRED_ABI), 8 * gb,
            770 * mb, null).any { it.contains("thermal") })
    }

    // ---- evidence ----

    @Test
    fun conditionsRecordStartShare() {
        val conditions = MemConditions.startOf(
            8 * gb, 992 * mb, false, 500 * mb)
        assertEquals(12.1, conditions.availPctStart!!, 0.05)
        assertEquals(true, conditions.belowThresholdStart)
        assertEquals(MemPolicy.WARNING_PCT,
            conditions.warningThresholdPct, 0.0)
        assertEquals(8 * gb, conditions.totalRamBytes)
    }

    @Test
    fun conditionsUnknownStaysNull() {
        val conditions = MemConditions.startOf(null, null, null, null)
        assertNull(conditions.availPctStart)
        assertNull(conditions.belowThresholdStart)
        assertNull(conditions.totalRamBytes)
        // Never 0%: absence is absence.
        assertFalse(conditions.toMap().containsValue(0.0))
    }

    @Test
    fun conditionsMapRoundTrips() {
        val conditions = MemConditions.startOf(
            4 * gb, 2500 * mb, true, 100 * mb)
        val map = conditions.toMap()
        assertEquals(4 * gb, map["total_ram_bytes"])
        assertEquals(2500 * mb, map["avail_bytes_start"])
        assertEquals(false, map["below_threshold_start"])
        assertEquals(true, map["low_memory_start"])
        assertEquals(100 * mb, map["low_threshold_bytes"])
    }

    @Test
    fun failureEvidenceShape() {
        val map = FailureEvidence("GENERATING", true).toMap()
        assertEquals("GENERATING", map["stage"])
        assertEquals(true, map["native_started"])
    }
}
