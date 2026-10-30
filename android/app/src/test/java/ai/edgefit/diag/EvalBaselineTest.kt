package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** EF-COMP-01A Stage A: Baseline R decision rules. */
class EvalBaselineTest {

    private fun config(nCtx: Int, nThreads: Int, nPredict: Int) =
        BenchConfig.default().copy(
            nCtx = nCtx, nThreads = nThreads, nPredict = nPredict)

    private fun evidence(
        config: BenchConfigData,
        vararg samples: Double,
        elevated: Int = 0,
        cohort: String = "cohort-a"
    ) = EvalBaseline.CandidateEvidence(
        config = config, genSamples = samples.toList(),
        elevatedThermal = elevated, cohortKey = cohort)

    @Test
    fun selectsBestObservedMedian() {
        val two = config(512, 2, 64)
        val four = config(512, 4, 64)
        val eight = config(512, 8, 64)
        val decision = EvalBaseline.decide(
            candidates = listOf(two, four, eight),
            evidence = listOf(
                evidence(two, 12.0, 13.0),
                evidence(four, 16.0, 16.5),
                evidence(eight, 14.0)
            ),
            deviceMaxThreads = 8
        )
        assertFalse(decision.abstained)
        assertEquals(four, decision.selected)
        assertEquals(16.25, decision.ranked[0].medianGen!!, 1e-9)
        assertEquals(16.25, decision.ranked[0].predictedGen!!, 1e-9)
        assertTrue(decision.factors.any { it.contains("best observed") })
    }

    @Test
    fun outOfBoundsAndOverThreadCandidatesAreExcluded() {
        val ok = config(512, 4, 64)
        val overThreads = config(512, 12, 64)
        val badCtx = config(99999, 4, 64)
        val decision = EvalBaseline.decide(
            candidates = listOf(ok, overThreads, badCtx),
            evidence = listOf(evidence(ok, 15.0)),
            deviceMaxThreads = 8
        )
        assertFalse(decision.abstained)
        assertEquals(ok, decision.selected)
        assertEquals(1, decision.ranked.size)
        assertTrue(decision.factors.any { it.contains("device max") })
        assertTrue(decision.factors.any { it.contains("n_ctx") })
    }

    @Test
    fun noValidCandidateAbstains() {
        val decision = EvalBaseline.decide(
            candidates = listOf(config(512, 12, 64)),
            evidence = emptyList(),
            deviceMaxThreads = 8
        )
        assertTrue(decision.abstained)
        assertEquals("no host-valid candidate", decision.abstainReason)
        assertNull(decision.selected)
    }

    @Test
    fun unmeasuredCandidatesRankBelowMeasuredWithNullPrediction() {
        val measured = config(512, 4, 64)
        val fresh = config(512, 8, 64)
        val decision = EvalBaseline.decide(
            candidates = listOf(fresh, measured),
            evidence = listOf(evidence(measured, 15.0)),
            deviceMaxThreads = 8
        )
        assertFalse(decision.abstained)
        assertEquals(measured, decision.selected)
        val last = decision.ranked.last()
        assertEquals(fresh, last.config)
        assertNull(last.medianGen)
        assertNull(last.predictedGen)
        assertTrue(last.factors.any { it.contains("unmeasured") })
    }

    @Test
    fun zeroMeasuredCandidatesAbstainsWithoutGuessing() {
        val decision = EvalBaseline.decide(
            candidates = listOf(config(512, 2, 64), config(512, 8, 64)),
            evidence = emptyList(),
            deviceMaxThreads = 8
        )
        assertTrue(decision.abstained)
        assertTrue(decision.abstainReason!!.contains("insufficient-evidence"))
        assertNull(decision.selected)
        // Ranking still exposed for inspection, all null.
        assertEquals(2, decision.ranked.size)
        assertTrue(decision.ranked.all { it.predictedGen == null })
    }

    @Test
    fun exactMedianTiesBreakDeterministically() {
        val low = config(256, 4, 64)
        val high = config(512, 4, 64)
        val first = EvalBaseline.decide(
            candidates = listOf(high, low),
            evidence = listOf(evidence(high, 15.0), evidence(low, 15.0)),
            deviceMaxThreads = 8
        )
        val second = EvalBaseline.decide(
            candidates = listOf(low, high),
            evidence = listOf(evidence(low, 15.0), evidence(high, 15.0)),
            deviceMaxThreads = 8
        )
        assertEquals(first, second)
        assertEquals(low, first.selected)
    }

    @Test
    fun unusableSamplesDoNotCountAsMeasured() {
        val candidate = config(512, 4, 64)
        val decision = EvalBaseline.decide(
            candidates = listOf(candidate),
            evidence = listOf(evidence(candidate,
                Double.NaN, -3.0, Double.POSITIVE_INFINITY)),
            deviceMaxThreads = 8
        )
        assertTrue(decision.abstained)
        assertEquals(0, decision.ranked.single().n)
    }

    @Test
    fun advisoryMemoryNeverExcludesButCaveats() {
        val candidate = config(512, 4, 64)
        val low = EvalBaseline.decide(
            candidates = listOf(candidate),
            evidence = listOf(evidence(candidate, 15.0)),
            deviceMaxThreads = 8,
            memAvailPct = 4.8,
            memLowMemory = true
        )
        assertFalse(low.abstained)
        assertEquals(candidate, low.selected)
        assertTrue(low.factors.any { it.contains("advisory only") })
        assertTrue(low.factors.any { it.contains("lowMemory") })
        val unknown = EvalBaseline.decide(
            candidates = listOf(candidate),
            evidence = listOf(evidence(candidate, 15.0)),
            deviceMaxThreads = 8
        )
        assertFalse(unknown.abstained)
        assertTrue(unknown.factors.any { it.contains("unknown") })
    }

    @Test
    fun elevatedThermalSamplesCarryACaveat() {
        val candidate = config(512, 4, 64)
        val decision = EvalBaseline.decide(
            candidates = listOf(candidate),
            evidence = listOf(evidence(candidate, 14.0, 16.0, elevated = 1)),
            deviceMaxThreads = 8
        )
        assertFalse(decision.abstained)
        assertTrue(decision.ranked.single().factors.any {
            it.contains("elevated thermal")
        })
    }

    @Test
    fun sameTripleRecordsAggregateInsteadOfOverwriting() {
        val candidate = config(512, 4, 64)
        val decision = EvalBaseline.decide(
            candidates = listOf(candidate),
            evidence = listOf(
                evidence(candidate, 12.0, elevated = 1),
                evidence(candidate, 16.0, 20.0)
            ),
            deviceMaxThreads = 8
        )
        assertFalse(decision.abstained)
        val ranked = decision.ranked.single()
        // Median of {12, 16, 20}, not last-wins median of {16, 20}.
        assertEquals(16.0, ranked.medianGen!!, 1e-9)
        assertEquals(3, ranked.n)
        assertTrue(ranked.factors.any { it.contains("elevated thermal") })
    }

    @Test
    fun evidencePermutationCannotChangeDecision() {
        val two = config(512, 2, 64)
        val four = config(512, 4, 64)
        val records = listOf(
            evidence(two, 12.0),
            evidence(four, 16.0),
            evidence(two, 14.0, elevated = 1),
            evidence(four, 15.0)
        )
        val forward = EvalBaseline.decide(
            candidates = listOf(two, four),
            evidence = records, deviceMaxThreads = 8)
        val reversed = EvalBaseline.decide(
            candidates = listOf(two, four),
            evidence = records.reversed(), deviceMaxThreads = 8)
        assertEquals(forward, reversed)
        assertEquals(four, forward.selected)
        assertEquals(15.5, forward.ranked[0].medianGen!!, 1e-9)
    }

    @Test
    fun mixedCohortsAtOneTripleThrow() {
        val candidate = config(512, 4, 64)
        try {
            EvalBaseline.decide(
                candidates = listOf(candidate),
                evidence = listOf(
                    evidence(candidate, 12.0, cohort = "cohort-a"),
                    evidence(candidate, 16.0, cohort = "cohort-b")
                ),
                deviceMaxThreads = 8
            )
            fail("mixed cohorts pooled silently")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("refusing to pool"))
        }
    }

    @Test
    fun medianOfEvenSamplesAveragesMiddlePair() {
        val candidate = config(512, 4, 64)
        val decision = EvalBaseline.decide(
            candidates = listOf(candidate),
            evidence = listOf(evidence(candidate, 10.0, 20.0)),
            deviceMaxThreads = 8
        )
        assertEquals(15.0, decision.ranked.single().medianGen!!, 1e-9)
    }
}
