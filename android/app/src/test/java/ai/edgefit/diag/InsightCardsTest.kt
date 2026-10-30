package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for UX-01BC structured presentation builders. */
class InsightCardsTest {

    private fun prediction(): Map<String, Any?> = mapOf(
        "predicted_gen_tps" to 17.5,
        "predicted_prefill_tps" to 27.0,
        "source_type" to SessionManager.SOURCE_LIVE_META,
        "created_at_utc" to "2026-10-28T22:45:03Z",
        "evidence_ids" to listOf("BENCH-x"),
        "raw_json" to """{"confidence":"low",
            "rationale":"scaling guess",
            "uncertainty_notes":["one run"],
            "limits":["thermal"]}"""
    )

    @Test
    fun predictionLabelsEstimatesNotMeasurements() {
        val sections = InsightCards.predictionSections(
            prediction(), "mock.gguf",
            SessionBenchBridge.configMap(BenchConfig.default()))
        val first = sections.first()
        assertEquals("Prediction", first.title)
        assertTrue(first.rows.any {
            it.label.contains("ESTIMATE") && it.value.contains("17.5")
        })
        assertTrue(first.rows.none { it.value.contains("MEASURED") })
        assertTrue(first.rows.any { it.value.contains("low") })
    }

    @Test
    fun comparisonReadsStoredValuesOnly() {
        val rows = InsightCards.comparisonRows(mapOf(
            "comparable" to true,
            "gen_signed" to 0.238, "gen_pct" to 1.36,
            "prefill_signed" to 3.997, "prefill_pct" to 14.8
        ))
        assertEquals(2, rows.size)
        assertTrue(rows[0].value.contains("+0.238"))
        assertTrue(rows[0].value.contains("+1.36%"))
        assertTrue(rows[1].value.contains("+14.8%"))
    }

    @Test
    fun noncomparableComparisonExplains() {
        val rows = InsightCards.comparisonRows(mapOf(
            "comparable" to false, "reason" to "failed"))
        assertEquals(1, rows.size)
        assertTrue(rows[0].value.contains("noncomparable"))
    }

    @Test
    fun followUpSeparatesInterpretation() {
        val sections = InsightCards.followUpSections(mapOf(
            "provenance" to "live_meta muse-spark-1.3",
            "recommendation" to mapOf(
                "direction" to "underestimated",
                "summary" to "close match",
                "supports_conclusion" to false,
                "limitations" to listOf("single run"),
                "proposal_valid" to true,
                "run_id" to "BENCH-x",
                "next_experiment" to mapOf(
                    "description" to "try 8 threads",
                    "rationale" to "bandwidth?")
            )
        ))
        assertEquals("Follow-up", sections[0].title)
        assertTrue(sections[0].rows.any {
            it.label == "Direction" && it.value == "underestimated"
        })
        assertTrue(sections.any { it.title.contains("PROPOSAL") })
    }

    @Test
    fun proposalDiffMarksOnlyChangedKeys() {
        val sections = InsightCards.proposalSections(
            mapOf(
                "proposed_config" to mapOf(
                    "n_ctx" to 512, "n_threads" to 8,
                    "n_predict" to 64),
                "changed_keys" to listOf("n_threads"),
                "expected_gen_tps" to 18.0,
                "expected_direction" to "increase",
                "uncertainty" to "medium",
                "hypothesis" to "more threads help",
                "benefit" to "throughput",
                "supporting_evidence_ids" to listOf("BENCH-x"),
                "limitations" to listOf("thermal"),
                "proposal_hash" to "a3a3bd87cafe1234",
                "evidence_pack_hash" to "3fd6f1bc00009999",
                "host_notes" to listOf("ok")
            ),
            mapOf("n_ctx" to 512, "n_threads" to 4,
                "n_predict" to 64),
            1, 5
        )
        val diff = sections.first { it.title.contains("change") }
        val threads = diff.rows.first { it.label == "n_threads" }
        assertTrue(threads.value.contains("4 → 8"))
        assertTrue(threads.value.contains("changed"))
        val ctx = diff.rows.first { it.label == "n_ctx" }
        assertTrue(!ctx.value.contains("changed"))
        val expectation = sections.first { it.title.contains("Expected") }
        assertTrue(expectation.rows.any {
            it.label.contains("ESTIMATE") && it.value.contains("18.0")
        })
        assertTrue(expectation.rows.any {
            it.value.contains("2 of 5") })
    }

    @Test
    fun completedWorseRunIsMissedHypothesis() {
        assertTrue(InsightCards.outcomeSuffix(
            "COMPLETED", 12.79, 19.5).contains("hypothesis missed"))
        assertTrue(InsightCards.outcomeSuffix(
            "COMPLETED", 19.5, 19.5).contains("supported"))
        assertEquals("", InsightCards.outcomeSuffix(
            "FAILED", null, 19.5))
    }

    @Test
    fun readinessVerdictStatesCause() {
        assertTrue(InsightCards.readinessVerdict(
            2300, 7220, "severe", true).value.contains("severe"))
        assertTrue(InsightCards.readinessVerdict(
            2300, 7220, null, true).value.contains("unknown"))
        assertTrue(InsightCards.readinessVerdict(
            2300, 7220, "none", false).value.contains("model"))
        assertTrue(InsightCards.readinessVerdict(
            2300, 7220, "none", true).value.contains("Ready"))
    }

    private fun record(
        state: String,
        mode: String = "byok"
    ): SessionRecord =
        SessionRecord(
            sessionId = "SES-x", mode = mode, state = state,
            createdAtUtc = "", updatedAtUtc = "",
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 1,
            modelDisplay = "m", workloadId = "w",
            promptSha256 = "1".repeat(64),
            config = emptyMap(), prediction = null,
            benchmark = null, comparison = null,
            followup = null, parentSessionId = null,
            experimentSeq = null, budgetUsed = 0,
            budgetMax = 5, agent = null, history = emptyList(),
            recoveryNotes = emptyList()
        )

    @Test
    fun pendingActionMirrorsGating() {
        assertEquals("Needs: prediction",
            InsightCards.pendingAction(
                record(SessionState.SESSION_CREATED)))
        assertEquals("Open in Agent tab",
            InsightCards.pendingAction(
                record(SessionState.SESSION_CREATED, "agent")))
        val done = record(SessionState.COMPARISON_READY).copy(
            benchmark = mapOf("status" to "COMPLETED"))
        assertEquals("Ready: request follow-up",
            InsightCards.pendingAction(done))
        val failed = record(SessionState.BENCHMARK_FAILED).copy(
            benchmark = mapOf("status" to "FAILED"))
        assertEquals("Run failed — see cause",
            InsightCards.pendingAction(failed))
    }

    @Test
    fun sessionSectionsAddTimelineAndEvidence() {
        val sections = InsightCards.sessionSections(
            record(SessionState.COMPARISON_READY).copy(
                createdAtUtc = "2026-10-28T17:45:03Z",
                updatedAtUtc = "2026-10-28T17:46:10Z",
                history = listOf(
                    mapOf("state" to SessionState.SESSION_CREATED,
                        "at" to "2026-10-28T17:45:03Z",
                        "cause" to "created"),
                    mapOf("state" to SessionState.COMPARISON_READY,
                        "at" to "2026-10-28T17:46:10Z",
                        "cause" to "attached BENCH-x")
                )))
        val titles = sections.map { it.title }
        assertTrue(titles.contains("Timeline"))
        assertTrue(titles.contains("Technical evidence"))
        val timeline =
            sections.first { it.title == "Timeline" }.rows
        assertEquals(2, timeline.size)
        assertEquals("Session created", timeline[0].label)
        assertTrue(timeline[1].value.contains("attached BENCH-x"))
        val evidence =
            sections.first { it.title == "Technical evidence" }
        assertTrue(evidence.details!!.contains("2026-10-28T17:45:03Z"))
    }

    @Test
    fun sessionSectionsOmitEmptyTimeline() {
        val titles = InsightCards.sessionSections(
            record(SessionState.SESSION_CREATED)).map { it.title }
        assertTrue(!titles.contains("Timeline"))
        assertTrue(titles.contains("Technical evidence"))
    }

    @Test
    fun sessionSectionsAddFollowUpWhenRecorded() {
        val sections = InsightCards.sessionSections(
            record(SessionState.RECOMMENDATION_RECORDED).copy(
                followup = mapOf(
                    "status" to "recorded",
                    "provenance" to "live_meta muse-spark-1.3",
                    "at" to "2026-10-28T17:50:00Z",
                    "recommendation" to mapOf(
                        "summary" to "single run, no conclusion"))))
        val titles = sections.map { it.title }
        assertTrue(titles.contains("Meta follow-up"))
        val rows = sections
            .first { it.title == "Meta follow-up" }.rows
        assertTrue(rows.any {
            it.label == "Summary" &&
                it.value.contains("single run")
        })
        assertTrue(rows.any {
            it.label == "Provenance" &&
                it.value.contains("live_meta")
        })
    }

    @Test
    fun sessionSectionsOmitMissingFollowUp() {
        val titles = InsightCards.sessionSections(
            record(SessionState.COMPARISON_READY)).map { it.title }
        assertTrue(!titles.contains("Meta follow-up"))
    }
}
