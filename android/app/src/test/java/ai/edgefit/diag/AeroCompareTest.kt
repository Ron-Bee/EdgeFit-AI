package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroCompareTest {

    private fun prediction(): Map<String, Any?> = mapOf(
        "predicted_gen_tps" to 12.0,
        "predicted_prefill_tps" to 28.0,
        "source_type" to "meta"
    )

    private fun benchmark(
        status: String = BenchContract.STATUS_COMPLETED
    ): Map<String, Any?> = mapOf(
        "run_id" to "BENCH-x",
        "status" to status,
        "measurements" to mapOf(
            "tps_gen" to 14.5, "tps_prefill" to 30.0),
        "collected_at_utc" to "2026-01-02T00:00:00Z"
    )

    private fun comparison(
        comparable: Boolean = true,
        reason: String = "ok"
    ): Map<String, Any?> = mapOf(
        "comparable" to comparable,
        "reason" to reason,
        "gen_signed" to 2.5,
        "gen_pct" to 20.8,
        "prefill_signed" to 2.0,
        "prefill_pct" to 7.1
    )

    @Test
    fun fullBlockShowsAllThreeRows() {
        val block = AeroCompare.block(
            prediction(), benchmark(), comparison())
        assertEquals("12.0 tok/s", block.predictedGen)
        assertEquals("28.0 tok/s", block.predictedPrefill)
        assertTrue(block.hasPrediction)
        assertEquals("14.5 tok/s", block.measuredGen)
        assertEquals("30.0 tok/s", block.measuredPrefill)
        assertTrue(block.hasMeasurement)
        assertEquals("+2.5 tok/s (+20.8%)", block.deltaGen)
        assertEquals("+2.0 tok/s (+7.1%)",
            block.deltaPrefill)
        assertEquals("vs persisted prediction",
            block.deltaNote)
        assertTrue(block.comparable)
    }

    @Test
    fun deltaRendersStoredErrorVerbatim() {
        // 14.5 vs 12.0 would recompute to +20.83%; the
        // block must show the stored +20.8%, proving no
        // recomputation happens here.
        val block = AeroCompare.block(
            prediction(), benchmark(), comparison())
        assertTrue(block.deltaGen.contains("20.8%"))
        assertFalse(block.deltaGen.contains("20.83"))
    }

    @Test
    fun storedNoncomparableKeepsItsReason() {
        val block = AeroCompare.block(
            prediction(), benchmark(),
            comparison(
                comparable = false,
                reason = "predicted throughput must be > 0"))
        assertFalse(block.comparable)
        assertEquals("Not comparable", block.deltaGen)
        assertEquals("predicted throughput must be > 0",
            block.deltaNote)
    }

    @Test
    fun missingComparisonMapIsHonest() {
        val block = AeroCompare.block(
            prediction(), benchmark(), null)
        assertFalse(block.comparable)
        assertEquals("Not comparable", block.deltaGen)
        assertEquals("no stored comparison", block.deltaNote)
        // Prediction and measured rows still show; only
        // the difference refuses.
        assertTrue(block.hasPrediction)
        assertTrue(block.hasMeasurement)
    }

    @Test
    fun missingPredictionShowsUnknown() {
        val block = AeroCompare.block(
            null, benchmark(), null)
        assertEquals("?", block.predictedGen)
        assertFalse(block.hasPrediction)
    }

    @Test
    fun failedBenchmarkIsNotMeasurement() {
        val block = AeroCompare.block(
            prediction(),
            benchmark(status = BenchContract.STATUS_FAILED),
            null)
        assertFalse(block.hasMeasurement)
    }

    @Test
    fun standaloneNullsStayEmpty() {
        val block = AeroCompare.block(null, null, null)
        assertFalse(block.hasPrediction)
        assertFalse(block.hasMeasurement)
        assertFalse(block.comparable)
        assertEquals("?", block.predictedGen)
        assertEquals("?", block.measuredGen)
    }

    @Test
    fun runMismatchRefusesUncontrolledPairing() {
        val block = AeroCompare.block(
            prediction(), benchmark(), comparison(),
            runMismatch = true)
        assertFalse(block.comparable)
        assertFalse(block.hasMeasurement)
        assertEquals("?", block.measuredGen)
        assertEquals("session carries a different run",
            block.deltaNote)
        // The session's own stored prediction still shows.
        assertTrue(block.hasPrediction)
    }
}
