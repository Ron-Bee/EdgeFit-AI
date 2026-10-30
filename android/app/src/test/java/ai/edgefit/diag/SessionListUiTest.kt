package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF-UX-02 pure session-list logic: ordering, labels,
 * filters, eligibility, and comparison presentation.
 */
class SessionListUiTest {

    private fun record(
        state: String,
        mode: String = "byok",
        id: String = "SES-aaaaaaaaaaaaaaaa",
        created: String = "2026-10-28T01:00:00Z"
    ): SessionRecord =
        SessionRecord(
            sessionId = id, mode = mode, state = state,
            createdAtUtc = created, updatedAtUtc = created,
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 1,
            modelDisplay = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            workloadId = "edgefit-prompt-v1",
            promptSha256 = "1".repeat(64),
            config = emptyMap(), prediction = null,
            benchmark = null, comparison = null,
            followup = null, parentSessionId = null,
            experimentSeq = null, budgetUsed = 0,
            budgetMax = 5, agent = null, history = emptyList(),
            recoveryNotes = emptyList()
        )

    private fun measured(
        runId: String = "BENCH-20261028T010000Z-aaaaaaaa",
        status: String = BenchContract.STATUS_COMPLETED,
        gen: Double = 17.738359201773836
    ): Map<String, Any?> = mapOf(
        "run_id" to runId,
        "status" to status,
        "collected_at_utc" to "2026-10-28T01:00:00Z",
        "measurements" to mapOf(
            "tps_gen" to gen,
            "tps_prefill" to 30.99,
            "thermal_after" to "none")
    )

    // ---- ordering ----

    @Test
    fun newestFirstByCreation() {
        val old = record(SessionState.SESSION_FINISHED,
            created = "2026-10-28T01:00:00Z")
        val new = record(SessionState.SESSION_CREATED,
            id = "SES-bbbbbbbbbbbbbbbb",
            created = "2026-10-28T02:00:00Z")
        val sorted = SessionListUi.sortNewestFirst(listOf(old, new))
        assertEquals(new.sessionId, sorted[0].sessionId)
        assertEquals("SES-bbbbbbbbbbbbbbbb",
            SessionListUi.latestId(sorted))
    }

    @Test
    fun sameSecondTieBreaksOnId() {
        val low = record(SessionState.SESSION_CREATED,
            id = "SES-aaaaaaaaaaaaaaaa")
        val high = record(SessionState.SESSION_CREATED,
            id = "SES-ffffffffffffffff")
        val sorted =
            SessionListUi.sortNewestFirst(listOf(low, high))
        assertEquals(high.sessionId, sorted[0].sessionId)
        // Deterministic: reshuffling the input changes nothing.
        assertEquals(sorted,
            SessionListUi.sortNewestFirst(listOf(high, low)))
    }

    @Test
    fun modificationTimeNeverReorders() {
        val old = record(SessionState.SESSION_FINISHED,
            created = "2026-10-28T01:00:00Z")
            .copy(updatedAtUtc = "2026-10-29T00:00:00Z")
        val new = record(SessionState.SESSION_CREATED,
            id = "SES-bbbbbbbbbbbbbbbb",
            created = "2026-10-28T02:00:00Z")
        val sorted = SessionListUi.sortNewestFirst(listOf(old, new))
        assertEquals(new.sessionId, sorted[0].sessionId)
    }

    @Test
    fun latestIdNullWhenEmpty() {
        assertNull(SessionListUi.latestId(emptyList()))
    }

    // ---- friendly states ----

    @Test
    fun friendlyStatesCoverLifecycle() {
        assertEquals("Ready for Prediction",
            SessionListUi.friendlyState(
                record(SessionState.SESSION_CREATED)))
        assertEquals("Prediction Pending",
            SessionListUi.friendlyState(
                record(SessionState.PREDICTION_PENDING)))
        assertEquals("Predicted",
            SessionListUi.friendlyState(
                record(SessionState.PREDICTION_RECORDED)))
        assertEquals("Ready to Run",
            SessionListUi.friendlyState(
                record(SessionState.BENCHMARK_AUTHORIZED)))
        assertEquals("Benchmark Running",
            SessionListUi.friendlyState(
                record(SessionState.BENCHMARK_RUNNING)))
        assertEquals("Measured",
            SessionListUi.friendlyState(
                record(SessionState.BENCHMARK_COMPLETED)))
        assertEquals("Benchmark Failed",
            SessionListUi.friendlyState(
                record(SessionState.BENCHMARK_FAILED)))
        assertEquals("Cancelled",
            SessionListUi.friendlyState(
                record(SessionState.BENCHMARK_CANCELLED)))
        assertEquals("Timed Out",
            SessionListUi.friendlyState(
                record(SessionState.BENCHMARK_TIMEOUT)))
        assertEquals("Follow-Up Pending",
            SessionListUi.friendlyState(
                record(SessionState.FOLLOWUP_PENDING)))
        assertEquals("Complete",
            SessionListUi.friendlyState(
                record(SessionState.RECOMMENDATION_RECORDED)))
        assertEquals("Finished",
            SessionListUi.friendlyState(
                record(SessionState.SESSION_FINISHED)))
    }

    @Test
    fun comparedLabelDependsOnFollowUpEligibility() {
        assertEquals("Ready for Follow-Up",
            SessionListUi.friendlyState(
                record(SessionState.COMPARISON_READY)))
        assertEquals("Compared",
            SessionListUi.friendlyState(
                record(SessionState.COMPARISON_READY,
                    mode = "offline_test")))
    }

    @Test
    fun unknownStatePassesThrough() {
        assertEquals("SOME_FUTURE_STATE",
            SessionListUi.friendlyState(
                record("SOME_FUTURE_STATE")))
    }

    // ---- follow-up gate ----

    @Test
    fun followUpGateMatchesDialogEligibility() {
        assertTrue(SessionListUi.canFollowUp(
            record(SessionState.COMPARISON_READY)))
        assertTrue(SessionListUi.canFollowUp(
            record(SessionState.FOLLOWUP_PENDING)))
        assertFalse(SessionListUi.canFollowUp(
            record(SessionState.COMPARISON_READY).copy(
                followup = mapOf("status" to "recorded"))))
        assertFalse(SessionListUi.canFollowUp(
            record(SessionState.COMPARISON_READY,
                mode = "offline_test")))
        assertFalse(SessionListUi.canFollowUp(
            record(SessionState.BENCHMARK_AUTHORIZED)))
        // Agent children are byok-family: eligible too.
        assertTrue(SessionListUi.canFollowUp(
            record(SessionState.COMPARISON_READY).copy(
                parentSessionId = "SES-cccccccccccccccc",
                experimentSeq = 2)))
    }

    // ---- row text ----

    @Test
    fun rowLabelAndTypeShort() {
        assertEquals("BYOK aaaaaaaa",
            SessionListUi.rowLabel(
                record(SessionState.SESSION_CREATED)))
        assertEquals("Experiment 2",
            SessionListUi.typeShort(
                record(SessionState.SESSION_CREATED).copy(
                    parentSessionId = "SES-cccccccccccccccc",
                    experimentSeq = 2)))
        assertEquals("Agent",
            SessionListUi.typeShort(
                record(SessionState.SESSION_CREATED,
                    mode = "agent")))
        assertEquals("Offline Test",
            SessionListUi.typeShort(
                record(SessionState.SESSION_CREATED,
                    mode = "offline_test")))
        assertEquals("Replay",
            SessionListUi.typeShort(
                record(SessionState.SESSION_CREATED,
                    mode = "replay")))
    }

    @Test
    fun shortModelNameStripsAndTruncates() {
        assertEquals("Llama-3.2-1B-Instruct-Q4 K M",
            SessionListUi.shortModelName(
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf"))
        val long = SessionListUi.shortModelName(
            "Very-Long-Model-Name-That-Keeps-Going-Forever.gguf")
        assertTrue(long.length <= 30)
        assertTrue(long.endsWith("..."))
    }

    @Test
    fun configShortFormatsKeys() {
        assertEquals("512 ctx · 4 threads · 64 tokens",
            SessionListUi.configShort(mapOf(
                "n_ctx" to 512, "n_threads" to 4,
                "n_predict" to 64)))
        assertEquals("? ctx · ? threads · ? tokens",
            SessionListUi.configShort(emptyMap()))
    }

    @Test
    fun primaryMeasurementOnlyWhenValid() {
        assertEquals("17.74 tok/s",
            SessionListUi.primaryMeasurement(
                record(SessionState.COMPARISON_READY).copy(
                    benchmark = measured())))
        assertNull(SessionListUi.primaryMeasurement(
            record(SessionState.COMPARISON_READY)))
        assertNull(SessionListUi.primaryMeasurement(
            record(SessionState.BENCHMARK_FAILED).copy(
                benchmark = measured(
                    status = BenchContract.STATUS_FAILED,
                    gen = 0.0))))
        assertNull(SessionListUi.primaryMeasurement(
            record(SessionState.COMPARISON_READY).copy(
                benchmark = measured(gen = Double.NaN))))
    }

    // ---- action needed + filters ----

    @Test
    fun needsActionCoversPendingWorkOnly() {
        assertTrue(SessionListUi.needsAction(
            record(SessionState.SESSION_CREATED)))
        assertTrue(SessionListUi.needsAction(
            record(SessionState.PREDICTION_PENDING)))
        assertTrue(SessionListUi.needsAction(
            record(SessionState.BENCHMARK_AUTHORIZED)))
        assertTrue(SessionListUi.needsAction(
            record(SessionState.COMPARISON_READY)))
        assertTrue(SessionListUi.needsAction(
            record(SessionState.FOLLOWUP_PENDING)))
        assertFalse(SessionListUi.needsAction(
            record(SessionState.COMPARISON_READY,
                mode = "offline_test")))
        assertFalse(SessionListUi.needsAction(
            record(SessionState.RECOMMENDATION_RECORDED)))
        assertFalse(SessionListUi.needsAction(
            record(SessionState.SESSION_FINISHED)))
        assertFalse(SessionListUi.needsAction(
            record(SessionState.BENCHMARK_FAILED)))
        assertFalse(SessionListUi.needsAction(
            record(SessionState.BENCHMARK_RUNNING)))
        assertFalse(SessionListUi.needsAction(
            record(SessionState.BENCHMARK_CANCELLED)))
    }

    @Test
    fun filtersGroupAndPreserveOrder() {
        val agentChild =
            record(SessionState.COMPARISON_READY,
                id = "SES-cccccccccccccccc").copy(
                parentSessionId = "SES-dddddddddddddddd",
                experimentSeq = 1)
        val offline = record(SessionState.BENCHMARK_AUTHORIZED,
            mode = "offline_test", id = "SES-bbbbbbbbbbbbbbbb")
        val byok = record(SessionState.SESSION_CREATED,
            id = "SES-aaaaaaaaaaaaaaaa")
        val agentParent = record(SessionState.SESSION_CREATED,
            mode = "agent", id = "SES-9999999999999999")
        val all = SessionListUi.sortNewestFirst(
            listOf(agentChild, offline, byok, agentParent))
        assertEquals(2, SessionListUi.filterSessions(
            all, SessionListUi.SessionFilter.AGENT).size)
        assertEquals(1, SessionListUi.filterSessions(
            all, SessionListUi.SessionFilter.OFFLINE_TEST).size)
        // Agent children count as Agent, not standalone BYOK.
        assertEquals(1, SessionListUi.filterSessions(
            all, SessionListUi.SessionFilter.BYOK).size)
        // The agent parent (SESSION_CREATED, no prediction
        // gate) is not itself actionable.
        assertEquals(3, SessionListUi.filterSessions(
            all, SessionListUi.SessionFilter.ACTION_NEEDED).size)
        assertEquals(0, SessionListUi.filterSessions(
            all, SessionListUi.SessionFilter.COMPLETED).size)
        val done = byok.copy(benchmark = measured())
        assertEquals(1, SessionListUi.filterSessions(
            listOf(done, offline),
            SessionListUi.SessionFilter.COMPLETED).size)
        // Order preserved: newest first inside the filter.
        val action = SessionListUi.filterSessions(
            all, SessionListUi.SessionFilter.ACTION_NEEDED)
        assertEquals(
            all.filter { it.sessionId != agentParent.sessionId }
                .map { it.sessionId },
            action.map { it.sessionId })
    }

    @Test
    fun emptyStatesGuideNextStep() {
        for (filter in SessionListUi.SessionFilter.values()) {
            assertTrue(SessionListUi.emptyStateText(filter)
                .isNotBlank())
        }
        assertTrue(SessionListUi.emptyStateText(
            SessionListUi.SessionFilter.ACTION_NEEDED)
            .contains("Nothing needs action"))
    }

    // ---- series presentation ----

    @Test
    fun seriesSectionsLabelReferenceAndDirection() {
        val rows = listOf(
            SessionComparison.SeriesRow(
                runId = "BENCH-20261028T010000Z-aaaaaaaa",
                collectedAtUtc = "2026-10-28T01:00:00Z",
                genTps = 15.0, prefillTps = 25.0,
                thermalAfter = "none",
                deltaGen = 1.5, deltaPct = 10.0,
                isCurrent = false),
            SessionComparison.SeriesRow(
                runId = "BENCH-20261028T020000Z-bbbbbbbb",
                collectedAtUtc = "2026-10-28T02:00:00Z",
                genTps = 16.5, prefillTps = 27.0,
                thermalAfter = "none",
                deltaGen = 0.0, deltaPct = 0.0,
                isCurrent = true)
        )
        val sections = InsightCards.seriesSections(
            SessionComparison.SeriesResult(
                currentRunId =
                    "BENCH-20261028T020000Z-bbbbbbbb",
                rows = rows, minGen = 15.0, maxGen = 16.5))
        assertEquals("Current selected run (MEASURED)",
            sections[0].title)
        assertEquals("Other comparable runs", sections[1].title)
        assertEquals("Series summary", sections[2].title)
        assertEquals("Technical evidence", sections[3].title)
        val selected = sections[0].rows.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(selected.contains("Reference"))
        assertTrue(selected.contains("bbbbbbbb"))
        assertFalse(selected.contains("aaaaaaaa"))
        val compared = sections[1].rows.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(compared.contains("selected run minus " +
            "compared run"))
        assertTrue(compared.contains("aaaaaaaa"))
        // Selected-minus-compared: 16.5 - 15.0 = +1.5.
        assertTrue(compared.contains("+1.5"))
        assertFalse(compared.contains("+0.0%"))
        val summary = sections[2].rows.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(summary.contains(
            "Completed comparable measurements=2"))
        assertTrue(summary.contains("Observed range"))
        assertTrue(sections[3].details!!.contains(
            "BENCH-20261028T010000Z-aaaaaaaa"))
    }

    @Test
    fun seriesSectionsHandleSingleRun() {
        val sections = InsightCards.seriesSections(
            SessionComparison.SeriesResult(
                currentRunId = "BENCH solo",
                rows = listOf(SessionComparison.SeriesRow(
                    runId = "BENCH solo",
                    collectedAtUtc = "2026-10-28T01:00:00Z",
                    genTps = 15.0, prefillTps = 25.0,
                    thermalAfter = "none",
                    deltaGen = 0.0, deltaPct = 0.0,
                    isCurrent = true)),
                minGen = 15.0, maxGen = 15.0))
        val compared = sections[1].rows.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(compared.contains("none yet"))
        val summary = sections[2].rows.joinToString("\n") {
            "${it.label}=${it.value}"
        }
        assertTrue(summary.contains("single run"))
    }

    @Test
    fun runShortKeepsTagsCompact() {
        assertEquals("aaaaaaaa", InsightCards.runShort(
            "BENCH-20261028T010000Z-aaaaaaaa"))
        assertEquals("short", InsightCards.runShort("short"))
    }

    // ---- repeat prep ----

    private fun repeatPair(): Pair<SessionRecord, SessionRecord> {
        val source =
            record(SessionState.RECOMMENDATION_RECORDED).copy(
                benchmark = measured(),
                prediction = mapOf(
                    "predicted_gen_tps" to 15.0,
                    "predicted_prefill_tps" to 20.0,
                    "source_type" to "offline_test",
                    "created_at_utc" to "2026-10-28T00:30:00Z"))
        val created = record(SessionState.BENCHMARK_AUTHORIZED,
            mode = "offline_test",
            id = "SES-bbbbbbbbbbbbbbbb",
            created = "2026-10-28T03:00:00Z").copy(
            repeatOfSessionId = source.sessionId,
            repeatOfRunId =
                "BENCH-20261028T010000Z-aaaaaaaa",
            prediction = (source.prediction ?: emptyMap()) +
                mapOf(
                    "carried_from_session" to source.sessionId,
                    "carried_from_run" to
                        "BENCH-20261028T010000Z-aaaaaaaa",
                    "carried_at_utc" to "2026-10-28T03:00:00Z"))
        return source to created
    }

    @Test
    fun repeatPrepReadyWhenStagedMatches() {
        val (source, created) = repeatPair()
        val sections = InsightCards.repeatPrepSections(
            source, created,
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf", true)
        assertEquals("Repeat Experiment — Ready to Run",
            sections[0].title)
        val body = sections.joinToString("\n") { section ->
            section.rows.joinToString("\n") {
                "${it.label}=${it.value}"
            }
        }
        assertTrue(body.contains("Run Repeat Benchmark"))
        assertTrue(body.contains("staged and verified"))
        assertTrue(body.contains("carried forward"))
        val evidence =
            sections.first { it.title == "Technical evidence" }
        assertTrue(evidence.details!!.contains(source.sessionId))
        assertTrue(evidence.details!!.contains(
            created.sessionId))
    }

    @Test
    fun repeatPrepAsksForModelWhenUnstaged() {
        val (source, created) = repeatPair()
        val sections = InsightCards.repeatPrepSections(
            source, created, null, false)
        val body = sections.joinToString("\n") { section ->
            section.rows.joinToString("\n") {
                "${it.label}=${it.value}"
            }
        }
        assertTrue(body.contains("No model staged"))
        assertTrue(body.contains("Select the required model"))
        assertFalse(body.contains("Run Repeat Benchmark"))
    }

    @Test
    fun repeatPrepBlocksOnMismatch() {
        val (source, created) = repeatPair()
        val sections = InsightCards.repeatPrepSections(
            source, created, "other-model.gguf", false)
        val staging = sections
            .first { it.title == "Model staging" }.rows
        assertTrue(staging[0].value.contains("differs"))
        assertEquals(InsightCards.Accent.ERROR, staging[0].accent)
    }

    // ---- pending action ----

    @Test
    fun pendingActionNamesOfflineNextStep() {
        val offlineDone =
            record(SessionState.COMPARISON_READY,
                mode = "offline_test").copy(
                benchmark = measured(),
                prediction = mapOf("predicted_gen_tps" to 15.0))
        assertEquals("Ready: repeat or compare",
            InsightCards.pendingAction(offlineDone))
    }
}
