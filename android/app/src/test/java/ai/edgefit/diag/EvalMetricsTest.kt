package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EF-COMP-01A Stage A: shared metric semantics + NOT_EVALUABLE. */
class EvalMetricsTest {

    private fun case(
        arm: String = "M1",
        proposed: Boolean = true,
        valid: Boolean? = true,
        rejectedByHost: Boolean? = null,
        abstained: Boolean = false,
        abstainAppropriate: Boolean? = null,
        citedIds: List<String> = emptyList(),
        knownIds: Set<String> = emptySet(),
        predictedGen: Double? = null,
        predictedAtUtc: String? = null,
        measuredGen: Double? = null,
        measuredAtUtc: String? = null,
        selectedTriple: Triple<Int, Int, Int>? = null,
        testedOptions: Map<Triple<Int, Int, Int>, Double> = emptyMap(),
        latencyMs: Long? = null,
        costUsd: Double? = null
    ) = EvalMetrics.ProposalCase(
        arm = arm, proposed = proposed, valid = valid,
        rejectedByHost = rejectedByHost,
        abstained = abstained, abstainAppropriate = abstainAppropriate,
        citedIds = citedIds, knownIds = knownIds,
        predictedGen = predictedGen, predictedAtUtc = predictedAtUtc,
        measuredGen = measuredGen, measuredAtUtc = measuredAtUtc,
        selectedTriple = selectedTriple, testedOptions = testedOptions,
        latencyMs = latencyMs, costUsd = costUsd
    )

    @Test
    fun ratesComputeOverTheirDenominators() {
        val report = EvalMetrics.summarize(listOf(
            case(valid = true),
            case(valid = true),
            case(valid = false),
            case(proposed = false, valid = null, abstained = true,
                abstainAppropriate = true)
        ), "M1")
        assertEquals(4, report.n)
        assertEquals(2.0 / 3.0, report.validProposalRate.value!!, 1e-9)
        assertEquals(3, report.validProposalRate.n)
        assertEquals(1.0 / 3.0, report.invalidProposalRate.value!!, 1e-9)
        // The invalid case carries no rejection evidence, so
        // enforcement stays NOT_EVALUABLE instead of 100%.
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            report.hostRejectionRate.status)
        assertEquals(0.25, report.abstentionRate.value!!, 1e-9)
        assertEquals(1.0, report.appropriateAbstentionRate.value!!, 1e-9)
    }

    @Test
    fun emptyDenominatorsAreNotEvaluableNeverZero() {
        val report = EvalMetrics.summarize(emptyList(), "R")
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            report.validProposalRate.status)
        assertNull(report.validProposalRate.value)
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            report.meanSignedErrorTps.status)
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            report.meanSelectionRegretTps.status)
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            report.totalCostUsd.status)
        assertTrue(report.limitations.any { it.contains("no cases") })
    }

    @Test
    fun fabricatedCitationsCountAgainstKnownIds() {
        val report = EvalMetrics.summarize(listOf(
            case(citedIds = listOf("BENCH-1", "BENCH-GHOST"),
                knownIds = setOf("BENCH-1")),
            case(citedIds = listOf("BENCH-1"),
                knownIds = setOf("BENCH-1"))
        ), "M1")
        assertEquals(1.0 / 3.0, report.fabricatedCitationRate.value!!, 1e-9)
        assertEquals(3, report.fabricatedCitationRate.n)
        val none = EvalMetrics.summarize(listOf(case()), "M1")
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            none.fabricatedCitationRate.status)
    }

    @Test
    fun predictionErrorNeedsGenuineTemporalOrder() {
        val report = EvalMetrics.summarize(listOf(
            case(arm = "M0", predictedGen = 15.0,
                predictedAtUtc = "2026-10-28T00:00:00Z",
                measuredGen = 12.0, measuredAtUtc = "2026-10-28T01:00:00Z"),
            // Same stamp proves nothing: excluded.
            case(arm = "M0", predictedGen = 15.0,
                predictedAtUtc = "2026-10-28T01:00:00Z",
                measuredGen = 12.0, measuredAtUtc = "2026-10-28T01:00:00Z"),
            // Outcome leaked before the prediction: excluded.
            case(arm = "M0", predictedGen = 15.0,
                predictedAtUtc = "2026-10-28T02:00:00Z",
                measuredGen = 12.0, measuredAtUtc = "2026-10-28T01:00:00Z")
        ), "M0")
        assertEquals(-3.0, report.meanSignedErrorTps.value!!, 1e-9)
        assertEquals(3.0, report.meanAbsErrorTps.value!!, 1e-9)
        assertEquals(1, report.meanSignedErrorTps.n)
    }

    @Test
    fun nonFiniteOrNonPositivePredictionsAreExcluded() {
        val report = EvalMetrics.summarize(listOf(
            case(predictedGen = Double.NaN,
                predictedAtUtc = "2026-10-28T00:00:00Z",
                measuredGen = 12.0, measuredAtUtc = "2026-10-28T01:00:00Z"),
            case(predictedGen = 0.0,
                predictedAtUtc = "2026-10-28T00:00:00Z",
                measuredGen = 12.0, measuredAtUtc = "2026-10-28T01:00:00Z")
        ), "M1")
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            report.meanAbsErrorTps.status)
        assertTrue(report.meanAbsErrorTps.reason!!.contains("predates"))
    }

    @Test
    fun regretComparesSelectedAgainstBestTested() {
        val two = Triple(512, 2, 64)
        val four = Triple(512, 4, 64)
        val eight = Triple(512, 8, 64)
        val report = EvalMetrics.summarize(listOf(
            case(arm = "R", selectedTriple = four, testedOptions = mapOf(
                two to 12.0, four to 16.0, eight to 14.0)),
            case(arm = "R", selectedTriple = two, testedOptions = mapOf(
                two to 12.0, four to 16.0))
        ), "R")
        // Regrets 0.0 and 4.0 average to 2.0.
        assertEquals(2.0, report.meanSelectionRegretTps.value!!, 1e-9)
        assertEquals(2, report.meanSelectionRegretTps.n)
    }

    @Test
    fun regretNeedsSelectionAmongTestedOptions() {
        val two = Triple(512, 2, 64)
        val missing = EvalMetrics.summarize(listOf(
            // Selected config never tested: no regret basis.
            case(arm = "R", selectedTriple = Triple(512, 8, 64),
                testedOptions = mapOf(two to 12.0,
                    Triple(512, 4, 64) to 16.0)),
            // Single tested option: nothing to compare.
            case(arm = "R", selectedTriple = two,
                testedOptions = mapOf(two to 12.0))
        ), "R")
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            missing.meanSelectionRegretTps.status)
        assertTrue(missing.meanSelectionRegretTps.reason!!
            .contains("tested options"))
    }

    @Test
    fun historicalCorpusWithoutVariationYieldsNoRegret() {
        // The four science runs share one config: regret
        // over a single tested option is NOT_EVALUABLE, and
        // superiority claims have no basis.
        val only = Triple(512, 4, 64)
        val report = EvalMetrics.summarize(listOf(
            case(selectedTriple = only,
                testedOptions = mapOf(only to 14.38))
        ), "M1")
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            report.meanSelectionRegretTps.status)
    }

    @Test
    fun latencyAndCostPassThroughOrWithhold() {
        val report = EvalMetrics.summarize(listOf(
            case(arm = "M0", latencyMs = 1000L, costUsd = 0.02),
            case(arm = "M0", latencyMs = 3000L)
        ), "M0")
        assertEquals(2000.0, report.meanLatencyMs.value!!, 1e-9)
        assertEquals(0.02, report.totalCostUsd.value!!, 1e-9)
        assertEquals(1, report.totalCostUsd.n)
        val silent = EvalMetrics.summarize(listOf(case(arm = "R")), "R")
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            silent.meanLatencyMs.status)
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            silent.totalCostUsd.status)
        assertTrue(silent.observedThroughputs.isEmpty())
    }

    @Test
    fun observedThroughputsPassThroughFinitePositivesOnly() {
        val report = EvalMetrics.summarize(listOf(
            case(measuredGen = 16.0),
            case(measuredGen = Double.NaN),
            case(measuredGen = -1.0),
            case()
        ), "M1")
        assertEquals(listOf(16.0), report.observedThroughputs)
    }

    @Test
    fun crossCaseCitationIsolation() {
        // BENCH-X is cited in case A but authorized only in
        // case B: case A's citation is unsupported. Pooling
        // known ids across cases would wrongly clear it.
        val report = EvalMetrics.summarize(listOf(
            case(citedIds = listOf("BENCH-X"),
                knownIds = setOf("BENCH-A")),
            case(citedIds = listOf("BENCH-X"),
                knownIds = setOf("BENCH-X"))
        ), "M1")
        assertEquals(0.5, report.fabricatedCitationRate.value!!, 1e-9)
        assertEquals(2, report.fabricatedCitationRate.n)
    }

    @Test
    fun hostRejectionNeedsRecordedEvidence() {
        val enforced = EvalMetrics.summarize(listOf(
            case(valid = false, rejectedByHost = true),
            case(valid = false, rejectedByHost = true)
        ), "M1")
        assertEquals(1.0, enforced.invalidProposalRate.value!!, 1e-9)
        assertEquals(1.0, enforced.hostRejectionRate.value!!, 1e-9)
        // An invalid submission the host did NOT refuse is a
        // visible safety failure (0.0), not missing data.
        val escaped = EvalMetrics.summarize(listOf(
            case(valid = false, rejectedByHost = false)
        ), "M1")
        assertEquals(0.0, escaped.hostRejectionRate.value!!, 1e-9)
        // Fixture-only invalid flags prove no enforcement.
        val unknown = EvalMetrics.summarize(listOf(
            case(valid = false, rejectedByHost = null)
        ), "M1")
        assertEquals(1.0, unknown.invalidProposalRate.value!!, 1e-9)
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            unknown.hostRejectionRate.status)
    }

    @Test
    fun armsAreScopedIndependently() {
        val cases = listOf(case(arm = "R"), case(arm = "M1"))
        assertEquals(1, EvalMetrics.summarize(cases, "R").n)
        assertEquals(1, EvalMetrics.summarize(cases, "M1").n)
        assertEquals(0, EvalMetrics.summarize(cases, "M0").n)
    }
}
