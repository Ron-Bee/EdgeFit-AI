package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF-UX-AERO-01 Step 4 sessions-library derivations:
 * counts, honest measurement lines, delta wording,
 * evidence identity, and bounded inline rendering.
 */
class AeroSessionsTest {

    private fun record(
        id: String = "SES-aaaaaaaaaaaaaaaa",
        mode: String = "offline_test",
        state: String = SessionState.COMPARISON_READY,
        created: String = "2026-10-28T01:00:00Z",
        prediction: Map<String, Any?>? = null,
        benchmark: Map<String, Any?>? = null,
        comparison: Map<String, Any?>? = null
    ): SessionRecord =
        SessionRecord(
            sessionId = id, mode = mode, state = state,
            createdAtUtc = created, updatedAtUtc = created,
            modelSha256 = "0".repeat(64), modelSizeBytes = 1,
            modelDisplay = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            workloadId = "edgefit-prompt-v1",
            promptSha256 = "1".repeat(64),
            config = mapOf("n_ctx" to 512, "n_threads" to 4,
                "n_predict" to 64),
            prediction = prediction, benchmark = benchmark,
            comparison = comparison
        )

    private fun prediction(
        gen: Double = 15.0
    ): Map<String, Any?> = mapOf(
        "predicted_gen_tps" to gen,
        "predicted_prefill_tps" to 25.0,
        "source_type" to "offline_test",
        "created_at_utc" to "2026-10-28T00:59:00Z",
        "raw_json" to "{}",
        "evidence_ids" to emptyList<String>()
    )

    private fun benchmark(
        runId: String = "BENCH-20261028T010000Z-aaaaaaaa",
        status: String = BenchContract.STATUS_COMPLETED,
        gen: Double = 17.738
    ): Map<String, Any?> = mapOf(
        "run_id" to runId,
        "status" to status,
        "collected_at_utc" to "2026-10-28T01:00:00Z",
        "measurements" to mapOf(
            "tps_gen" to gen,
            "tps_prefill" to 30.99,
            "thermal_after" to "none")
    )

    private fun comparison(
        comparable: Boolean = true
    ): Map<String, Any?> = if (comparable) {
        mapOf("comparable" to true, "gen_signed" to 2.738,
            "gen_pct" to 18.25, "prefill_signed" to 5.99,
            "prefill_pct" to 23.96)
    } else {
        mapOf("comparable" to false, "reason" to "test fixture")
    }

    private fun evidence(
        provenance: String = EvidenceStore.PROVENANCE_CAPTURED,
        state: String = GlobalContract.LOCAL_NOT_SHARED,
        eligibility: String? = null,
        submission: String? = null
    ): AeroSessions.EvidenceInfo =
        AeroSessions.EvidenceInfo(
            provenance = provenance,
            contributionState = state,
            serverEligibility = eligibility,
            submissionId = submission
        )

    // ---- counts ----

    @Test
    fun headerLineCounts() {
        assertEquals("No local sessions yet.",
            AeroSessions.headerLine(0))
        assertEquals("No local sessions yet.",
            AeroSessions.headerLine(-3))
        assertEquals("1 local session.",
            AeroSessions.headerLine(1))
        assertEquals("62 local sessions.",
            AeroSessions.headerLine(62))
        assertEquals("1250 local sessions.",
            AeroSessions.headerLine(1250))
    }

    @Test
    fun emptyStateCarriesNoThroughput() {
        val lines = AeroSessions.emptyLines()
        assertTrue(lines.isNotEmpty())
        assertFalse(lines.joinToString("\n").contains("tok/s"))
    }

    // ---- measurement honesty ----

    @Test
    fun measuredLineStates() {
        assertEquals("17.74 tok/s (MEASURED)",
            AeroSessions.measuredLine(
                record(benchmark = benchmark())))
        assertEquals("No run attached yet",
            AeroSessions.measuredLine(record()))
        assertEquals("No measurement — run FAILED",
            AeroSessions.measuredLine(record(
                state = SessionState.BENCHMARK_FAILED,
                benchmark = benchmark(
                    status = BenchContract.STATUS_FAILED,
                    gen = 0.0))))
        // A failed run is never a zero datapoint.
        assertFalse(AeroSessions.measuredLine(record(
            state = SessionState.BENCHMARK_FAILED,
            benchmark = benchmark(
                status = BenchContract.STATUS_FAILED,
                gen = 0.0))).contains("tok/s"))
        // Non-finite completed rates never surface.
        assertFalse(AeroSessions.measuredLine(
            record(benchmark = benchmark(gen = Double.NaN)))
            .contains("tok/s"))
    }

    @Test
    fun deltaLineStates() {
        assertNull(AeroSessions.deltaLine(null))
        assertNull(AeroSessions.deltaLine(record()))
        val awaiting = AeroSessions.deltaLine(
            record(prediction = prediction()))
        assertTrue(awaiting!!.contains("ESTIMATE"))
        assertTrue(awaiting.contains("awaiting measurement"))
        val compared = AeroSessions.deltaLine(record(
            prediction = prediction(),
            benchmark = benchmark(),
            comparison = comparison()))
        assertTrue(compared!!.contains("Predicted 15.0 tok/s"))
        assertTrue(compared.contains("measured 17.7 tok/s"))
        assertTrue(compared.contains("Δ +2.738 tok/s, +18.25%"))
        val refused = AeroSessions.deltaLine(record(
            prediction = prediction(),
            benchmark = benchmark(),
            comparison = comparison(comparable = false)))
        assertEquals("Not comparable (test fixture)", refused)
    }

    // ---- evidence identity ----

    @Test
    fun evidenceLineStates() {
        assertEquals("Evidence: no run attached",
            AeroSessions.evidenceLine(record(), null))
        assertEquals("Evidence: not preserved on this install",
            AeroSessions.evidenceLine(
                record(benchmark = benchmark()), null))
        assertEquals("Evidence: captured · Not shared",
            AeroSessions.evidenceLine(
                record(benchmark = benchmark()),
                evidence()))
        assertEquals("Evidence: imported · Shared (Validated)",
            AeroSessions.evidenceLine(
                record(benchmark = benchmark()),
                evidence(
                    provenance =
                        EvidenceStore.PROVENANCE_IMPORTED,
                    state = GlobalContract.LOCAL_SHARED,
                    eligibility = GlobalContract.VALIDATED,
                    submission = "sub-1")))
        assertEquals("Evidence: captured · Submission uncertain",
            AeroSessions.evidenceLine(
                record(benchmark = benchmark()),
                evidence(state = GlobalContract.LOCAL_UNCERTAIN)))
    }

    @Test
    fun contributionLabels() {
        assertEquals("Not shared",
            AeroSessions.contributionLabel(evidence()))
        assertEquals("Awaiting consent",
            AeroSessions.contributionLabel(evidence(
                state = GlobalContract.LOCAL_AWAITING_CONSENT)))
        assertEquals("Retry available",
            AeroSessions.contributionLabel(evidence(
                state = GlobalContract.LOCAL_RETRY)))
        assertEquals("Connection unavailable",
            AeroSessions.contributionLabel(evidence(
                state = GlobalContract.LOCAL_NO_CONNECTION)))
        // SHARED without a submission id claims no server state.
        assertEquals("Shared",
            AeroSessions.contributionLabel(evidence(
                state = GlobalContract.LOCAL_SHARED)))
        assertEquals("Shared (Published)",
            AeroSessions.contributionLabel(evidence(
                state = GlobalContract.LOCAL_SHARED,
                eligibility = GlobalContract.PUBLISHED,
                submission = "sub-9")))
        // Unknown states pass through, never upgraded.
        assertEquals("SOMETHING_NEW",
            AeroSessions.contributionLabel(evidence(
                state = "SOMETHING_NEW")))
    }

    @Test
    fun sessionRunAndEvidenceIdsStayDistinct() {
        val rec = record(
            id = "SES-cccccccccccccccc",
            benchmark = benchmark(
                runId = "BENCH-20261028T010000Z-aaaaaaaa"))
        assertEquals("BENCH-20261028T010000Z-aaaaaaaa",
            AeroSessions.runIdOf(rec))
        assertNull(AeroSessions.runIdOf(record()))
        val card = AeroSessions.card(rec, false, false,
            evidence())
        // The card names the session; the run id is not
        // substituted for it, and the evidence line names
        // neither id as a stand-in for the other.
        assertEquals("SES-cccccccccccccccc", card.sessionId)
        assertTrue(card.title.contains("cccccccc"))
        assertFalse(card.title.contains("BENCH-"))
        assertTrue(card.evidenceLine.contains("captured"))
    }

    // ---- cards + bounds ----

    @Test
    fun cardFields() {
        val rec = record(
            benchmark = benchmark(),
            prediction = prediction(),
            comparison = comparison())
        val card = AeroSessions.card(rec, true, true,
            evidence())
        assertTrue(card.title.startsWith("Latest · "))
        assertTrue(card.title.endsWith(" · BOUND"))
        assertTrue(card.modelLine.contains("512 ctx"))
        assertEquals(SessionListUi.friendlyState(rec),
            card.stateLine)
        assertTrue(card.measuredLine.contains("MEASURED"))
        assertTrue(card.measuredPresent)
        val failed = AeroSessions.card(
            record(state = SessionState.BENCHMARK_FAILED,
                benchmark = benchmark(
                    status = BenchContract.STATUS_FAILED,
                    gen = 0.0)),
            false, false, null)
        assertFalse(failed.measuredPresent)
        assertTrue(card.deltaLine!!.contains("Δ"))
        assertTrue(card.evidenceLine.contains("captured"))
        assertFalse(card.createdLocal ==
            SessionTimeline.NOT_RECORDED)
        val badStamp = AeroSessions.card(
            record(created = "not-a-stamp"), false, false, null)
        assertEquals(SessionTimeline.NOT_RECORDED,
            badStamp.createdLocal)
    }

    private fun sixtyTwo(): List<SessionRecord> =
        (0 until 62).map { i ->
            record(
                id = "SES-%016x".format(i),
                created = "2026-10-%02dT%02d:00:00Z".format(
                    1 + (i % 28), i % 24),
                benchmark = benchmark(
                    runId = "BENCH-run-$i"))
        }.let { SessionListUi.sortNewestFirst(it) }

    @Test
    fun visibleCardsBoundSixtyTwo() {
        val sorted = sixtyTwo()
        assertEquals(62, sorted.size)
        var resolved = 0
        val cards = AeroSessions.visibleCards(sorted,
            SessionListUi.latestId(sorted), null, {
                resolved++
                null
            })
        assertEquals(AeroSessions.INLINE_MAX, cards.size)
        assertEquals(AeroSessions.INLINE_MAX, resolved)
        assertEquals(sorted[0].sessionId, cards[0].sessionId)
        assertTrue(cards[0].isLatest)
        assertEquals("Showing 8 of 62 — open the browser for all.",
            AeroSessions.showMoreLine(62, cards.size))
    }

    @Test
    fun visibleCardsHandleSmallHistories() {
        assertTrue(AeroSessions.visibleCards(emptyList(), null,
            null, { null }).isEmpty())
        assertNull(AeroSessions.showMoreLine(0, 0))
        val one = listOf(record())
        val cards = AeroSessions.visibleCards(one,
            SessionListUi.latestId(one), null, { null })
        assertEquals(1, cards.size)
        assertNull(AeroSessions.showMoreLine(1, 1))
        assertNull(AeroSessions.showMoreLine(8, 8))
        assertTrue(AeroSessions.visibleCards(one, null, null,
            { null }, limit = 0).isEmpty())
    }

    @Test
    fun visibleCardsCapLargeHistories() {
        val many = (0 until 500).map { i ->
            record(id = "SES-%016x".format(i),
                created = "2026-10-28T%02d:%02d:00Z".format(
                    (i / 60) % 24, i % 60))
        }.let { SessionListUi.sortNewestFirst(it) }
        val cards = AeroSessions.visibleCards(many,
            SessionListUi.latestId(many), null, { null })
        assertEquals(AeroSessions.INLINE_MAX, cards.size)
        assertEquals("Showing 8 of 500 — open the browser for all.",
            AeroSessions.showMoreLine(500, cards.size))
    }
}
