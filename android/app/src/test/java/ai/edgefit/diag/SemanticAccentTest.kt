package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-01BC-R2 semantic accent tests. Accents distinguish
 * MEASURED observations from PREDICTED estimates and
 * ERROR states; label text always carries the meaning.
 */
class SemanticAccentTest {

    @Test
    fun statusAccentMapsBenchmarkOutcomes() {
        assertEquals(InsightCards.Accent.MEASURED,
            InsightCards.statusAccent(BenchContract.STATUS_COMPLETED))
        assertEquals(InsightCards.Accent.ERROR,
            InsightCards.statusAccent(BenchContract.STATUS_FAILED))
        assertEquals(InsightCards.Accent.ERROR,
            InsightCards.statusAccent(BenchContract.STATUS_TIMEOUT))
        assertEquals(InsightCards.Accent.NONE,
            InsightCards.statusAccent(BenchContract.STATUS_CANCELLED))
    }

    @Test
    fun statusAccentMapsSessionLifecycle() {
        assertEquals(InsightCards.Accent.PREDICTED,
            InsightCards.statusAccent(SessionState.PREDICTION_RECORDED))
        assertEquals(InsightCards.Accent.MEASURED,
            InsightCards.statusAccent(SessionState.COMPARISON_READY))
        assertEquals(InsightCards.Accent.MEASURED,
            InsightCards.statusAccent(
                SessionState.RECOMMENDATION_RECORDED))
        assertEquals(InsightCards.Accent.ERROR,
            InsightCards.statusAccent(SessionState.BENCHMARK_FAILED))
        assertEquals(InsightCards.Accent.NONE,
            InsightCards.statusAccent(SessionState.SESSION_CREATED))
    }

    @Test
    fun predictionThroughputRowsArePredicted() {
        val sections = InsightCards.predictionSections(
            mapOf(
                "predicted_gen_tps" to 17.5,
                "predicted_prefill_tps" to 27.0,
                "source_type" to SessionManager.SOURCE_LIVE_META,
                "created_at_utc" to "2026-10-28T22:45:03Z",
                "evidence_ids" to emptyList<String>(),
                "raw_json" to "{}"
            ),
            "m.gguf", mapOf("n_ctx" to 512, "n_threads" to 4,
                "n_predict" to 64))
        val rows = sections.first().rows
        assertEquals(InsightCards.Accent.PREDICTED, rows[0].accent)
        assertEquals(InsightCards.Accent.PREDICTED, rows[1].accent)
        assertTrue(rows[0].label.contains("ESTIMATE"))
    }

    private fun result(status: String, error: String?): BenchResult =
        BenchResult(
            runId = "BENCH-20261027T000000Z-abcdef12",
            device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
                listOf("arm64-v8a"), 8, 12884901888L),
            model = BenchModelIdentity("m.gguf", 807694464L,
                "1".repeat(64), "4", emptyMap()),
            runtimeVersion = "test",
            runtimeAbi = "arm64-v8a",
            config = BenchConfig.default(),
            workload = BenchConfig.workload(),
            measurements = BenchMeasurements(1200L, 300L, 40, 3200L, 64,
                BenchConfig.tps(64, 3200L), BenchConfig.tps(40, 300L),
                false,
                BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
                BenchThermal("none", "light", "light", true)),
            status = status,
            error = error,
            appVersion = "0.2.0",
            collectedAtUtc = "2026-10-27T00:00:00Z"
        )

    @Test
    fun benchmarkSectionsAccentMeasuredAndFailure() {
        val ok = InsightCards.benchmarkSections(
            result(BenchContract.STATUS_COMPLETED, null)).first().rows
        assertEquals(InsightCards.Accent.MEASURED,
            ok.first { it.label == "Status" }.accent)
        assertEquals(InsightCards.Accent.MEASURED,
            ok.first { it.label.startsWith("Generation") }.accent)
        val failed = InsightCards.benchmarkSections(
            result(BenchContract.STATUS_FAILED, "thermal state unsafe"))
            .first().rows
        assertEquals(InsightCards.Accent.ERROR,
            failed.first { it.label == "Status" }.accent)
        assertEquals(InsightCards.Accent.ERROR,
            failed.first { it.label == "Failure cause" }.accent)
    }

    @Test
    fun proposalExpectedOutcomeIsPredicted() {
        val sections = InsightCards.proposalSections(
            mapOf(
                "proposed_config" to mapOf("n_ctx" to 512,
                    "n_threads" to 8, "n_predict" to 64),
                "changed_keys" to listOf("n_threads"),
                "expected_gen_tps" to 18.0,
                "expected_direction" to "faster",
                "uncertainty" to "one run",
                "hypothesis" to "h", "benefit" to "b",
                "supporting_evidence_ids" to emptyList<String>(),
                "limitations" to emptyList<String>(),
                "host_notes" to emptyList<String>(),
                "proposal_hash" to "ab".repeat(32),
                "evidence_pack_hash" to "cd".repeat(32)
            ),
            mapOf("n_ctx" to 512, "n_threads" to 4,
                "n_predict" to 64),
            1, 5)
        val expected = sections
            .first { it.title == "Expected outcome" }.rows
        assertEquals(InsightCards.Accent.PREDICTED,
            expected.first { it.label.startsWith("Expected") }.accent)
    }

    @Test
    fun comparisonRowsStayNeutral() {
        val rows = InsightCards.comparisonRows(mapOf(
            "comparable" to true, "gen_signed" to 1.36,
            "gen_pct" to 7.8, "prefill_signed" to 2.0,
            "prefill_pct" to 5.0))
        assertTrue(rows.all { it.accent == InsightCards.Accent.NONE })
    }

    @Test
    fun experimentItemsExposeStatusWithSameText() {
        val parent = SessionRecord(
            sessionId = "SES-aaaaaaaaaaaaaaaa", mode = "agent",
            state = SessionState.SESSION_CREATED,
            createdAtUtc = "", updatedAtUtc = "",
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
        val detail = AgentView.AgentDetail(parent, emptyList(),
            AgentEvidence.EvidencePack(
                emptyList(), emptyList(), ""),
            true, "")
        assertTrue(AgentView.experimentItems(detail).isEmpty())
        assertEquals(
            AgentView.experimentLines(detail),
            AgentView.experimentItems(detail).map { it.text })
    }
}
