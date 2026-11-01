package ai.edgefit.diag

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardTest {

    private fun record(
        id: String,
        updated: String,
        status: String?,
        modelSha: String = "0".repeat(64)
    ): SessionRecord = SessionRecord(
        sessionId = id, mode = "byok",
        state = SessionState.BENCHMARK_COMPLETED,
        createdAtUtc = updated, updatedAtUtc = updated,
        deviceModel = null, profileSha256 = null,
        modelSha256 = modelSha, modelSizeBytes = 1,
        modelDisplay = "m", workloadId = "w",
        promptSha256 = "1".repeat(64),
        config = emptyMap(), prediction = null,
        benchmark = status?.let {
            mapOf(
                "run_id" to "BENCH-$id",
                "status" to it,
                "measurements" to mapOf(
                    "tps_gen" to 14.5, "tps_prefill" to 30.0),
                "collected_at_utc" to updated
            )
        },
        comparison = null, followup = null,
        parentSessionId = null, experimentSeq = null,
        budgetUsed = 0, budgetMax = 5, agent = null,
        history = emptyList(), recoveryNotes = emptyList()
    )

    @Test
    fun lastMeasuredPicksLatestCompleted() {
        val runs = HomeDashboard.lastMeasured(listOf(
            record("SES-aaaaaaaaaaaaaaaa", "2026-01-01T00:00:00Z",
                BenchContract.STATUS_COMPLETED),
            record("SES-bbbbbbbbbbbbbbbb", "2026-01-02T00:00:00Z",
                BenchContract.STATUS_COMPLETED)
        ))!!
        assertEquals("SES-bbbbbbbbbbbbbbbb", runs.sessionId)
        assertEquals(14.5, runs.genTps!!, 0.0)
    }

    @Test
    fun lastMeasuredIgnoresFailed() {
        assertNull(HomeDashboard.lastMeasured(listOf(
            record("SES-aaaaaaaaaaaaaaaa", "2026-01-01T00:00:00Z",
                "FAILED")
        )))
    }

    @Test
    fun lastMeasuredIgnoresUnmeasured() {
        assertNull(HomeDashboard.lastMeasured(listOf(
            record("SES-aaaaaaaaaaaaaaaa", "2026-01-01T00:00:00Z", null)
        )))
    }

    @Test
    fun actionTabRoutes() {
        assertEquals(NavTab.BENCHMARK,
            HomeDashboard.actionTab(NextAction.SELECT_MODEL))
        assertEquals(NavTab.BENCHMARK,
            HomeDashboard.actionTab(NextAction.RUN_BENCHMARK))
        assertEquals(NavTab.AGENT,
            HomeDashboard.actionTab(NextAction.REVIEW_PROPOSAL))
        assertEquals(NavTab.SESSIONS,
            HomeDashboard.actionTab(NextAction.REQUEST_FOLLOW_UP))
        assertEquals(NavTab.SESSIONS,
            HomeDashboard.actionTab(NextAction.VIEW_RESULTS))
    }

    @Test
    fun modelCardEmpty() {
        val card = HomeDashboard.modelCard(null, null, 0L)
        assertEquals(false, card.staged)
    }

    @Test
    fun modelCardStaged() {
        val card = HomeDashboard.modelCard(
            "Llama-3.2-1B.gguf", "ab".repeat(32), 800L * 1024 * 1024)
        assertEquals(true, card.staged)
        assertEquals(2, card.lines.size)
    }

    // ---- EF-UX-AERO-01 Step 3 ----

    private fun profile(): CollectedProfile = CollectedProfile(
        device = DeviceSection(
            manufacturer = "samsung",
            model = "SM-S908U",
            deviceCodename = "dm3q",
            androidRelease = "16",
            sdkInt = 36,
            supportedAbis = listOf("arm64-v8a"),
            logicalProcessors = 8,
            totalRamBytes = 12884901888L,
            availRamBytes = 6L * 1024 * 1024 * 1024),
        thermal = null,
        battery = null,
        unavailable = emptyList(),
        collectedAtUtc = "2026-10-31T00:00:00Z")

    private fun snapshot(): DeviceTelemetry.Snapshot =
        DeviceTelemetry.Snapshot(
            availRamBytes = 6L * 1024 * 1024 * 1024,
            totalRamBytes = 12884901888L,
            lowMemory = false,
            thermalName = "none",
            batteryPct = 87.0,
            batteryStatus =
                BatteryManager.BATTERY_STATUS_CHARGING,
            batteryPlugged = BatteryManager.BATTERY_PLUGGED_USB,
            batteryTempC = 29.5)

    @Test
    fun heroCopy() {
        val hero = HomeDashboard.hero()
        assertEquals("ON-DEVICE AI", hero.overline)
        assertEquals("EdgeFit AI", hero.title)
        assertEquals("Real performance", hero.subtitle)
    }

    @Test
    fun deviceTitleComesFromProfile() {
        assertEquals("samsung SM-S908U",
            HomeDashboard.deviceTitle(profile()))
    }

    @Test
    fun metricTileLabelsAreFixedOrder() {
        assertEquals(
            listOf(
                "CPU",
                "Total RAM",
                "Available RAM (live)",
                "Battery",
                "Battery temperature",
                "Thermal status",
                "Android"),
            HomeDashboard.metricLabels)
        assertEquals(
            HomeDashboard.metricLabels,
            HomeDashboard.metricTiles(profile(), snapshot())
                .map { it.label })
    }

    @Test
    fun metricTileValues() {
        val values = HomeDashboard
            .metricTiles(profile(), snapshot())
            .associate { it.label to it.value }
        assertEquals("8 cores", values["CPU"])
        assertEquals("12.0 GB", values["Total RAM"])
        assertEquals("6.0 GB", values["Available RAM (live)"])
        assertEquals("87% · Charging (USB)",
            values["Battery"])
        assertEquals("29.5 °C",
            values["Battery temperature"])
        assertEquals("Normal", values["Thermal status"])
        assertEquals("Android 16 (API 36)",
            values["Android"])
    }

    @Test
    fun metricTilesWithoutSnapshotStayUnavailable() {
        val values = HomeDashboard
            .metricTiles(profile(), null)
            .associate { it.label to it.value }
        assertEquals("unavailable",
            values["Available RAM (live)"])
        assertEquals("unavailable", values["Battery"])
        assertEquals("unavailable",
            values["Battery temperature"])
        assertEquals("unavailable",
            values["Thermal status"])
        // Static profile tiles still render.
        assertEquals("8 cores", values["CPU"])
        assertEquals("12.0 GB", values["Total RAM"])
        assertEquals("Android 16 (API 36)",
            values["Android"])
    }

    @Test
    fun batteryTempAndThermalNeverMix() {
        val hot = snapshot().copy(
            thermalName = "moderate", batteryTempC = 29.5)
        val values = HomeDashboard
            .metricTiles(profile(), hot)
            .associate { it.label to it.value }
        assertEquals("29.5 °C",
            values["Battery temperature"])
        assertEquals("Moderate", values["Thermal status"])
    }

    @Test
    fun modelReadinessNone() {
        val card = HomeDashboard.modelReadiness(
            null, null, 0L, emptyList())
        assertEquals(HomeDashboard.ModelReadiness.NONE,
            card.state)
    }

    @Test
    fun modelReadinessStaged() {
        val card = HomeDashboard.modelReadiness(
            "m.gguf", "ab".repeat(32),
            800L * 1024 * 1024, emptyList())
        assertEquals(HomeDashboard.ModelReadiness.STAGED,
            card.state)
        assertTrue(card.lines.joinToString("\n")
            .contains("no completed run"))
    }

    @Test
    fun modelReadinessMeasuredNeedsExactHashCompleted() {
        val sha = "ab".repeat(32)
        val sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z",
                BenchContract.STATUS_COMPLETED,
                modelSha = sha))
        val card = HomeDashboard.modelReadiness(
            "m.gguf", sha, 800L * 1024 * 1024, sessions)
        assertEquals(HomeDashboard.ModelReadiness.MEASURED,
            card.state)
    }

    @Test
    fun modelReadinessIgnoresOtherHashesAndFailures() {
        assertFalse(HomeDashboard.stagedMeasured(
            "ab".repeat(32), listOf(
                record("SES-aaaaaaaaaaaaaaaa",
                    "2026-01-02T00:00:00Z",
                    BenchContract.STATUS_COMPLETED,
                    modelSha = "cd".repeat(32)))))
        assertFalse(HomeDashboard.stagedMeasured(
            "ab".repeat(32), listOf(
                record("SES-aaaaaaaaaaaaaaaa",
                    "2026-01-02T00:00:00Z",
                    BenchContract.STATUS_FAILED,
                    modelSha = "ab".repeat(32)))))
        assertFalse(HomeDashboard.stagedMeasured(
            null, emptyList()))
    }

    @Test
    fun modelReadinessHashMatchIsCaseInsensitive() {
        assertTrue(HomeDashboard.stagedMeasured(
            "AB".repeat(32), listOf(
                record("SES-aaaaaaaaaaaaaaaa",
                    "2026-01-02T00:00:00Z",
                    BenchContract.STATUS_COMPLETED,
                    modelSha = "ab".repeat(32)))))
    }

    @Test
    fun readinessLabelsAreDistinct() {
        val labels = HomeDashboard.ModelReadiness.values()
            .map { HomeDashboard.readinessLabel(it) }
        assertEquals(3, labels.distinct().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    @Test
    fun primaryActionsAreDistinct() {
        val labels = NextAction.values()
            .map { HomeDashboard.primaryAction(it) }
        assertEquals(5, labels.distinct().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    @Test
    fun lastRunLinesEmptyStateHasNoThroughput() {
        val lines = HomeDashboard.lastRunLines(null)
        assertEquals(2, lines.size)
        val joined = lines.joinToString("\n")
        assertFalse(joined.contains("tok/s"))
        assertFalse(joined.any { it.isDigit() })
    }

    @Test
    fun lastRunLinesMeasured() {
        val lines = HomeDashboard.lastRunLines(
            HomeDashboard.LastRun(
                sessionId = "SES-bbbbbbbbbbbbbbbb",
                runId = "BENCH-x",
                genTps = 14.5,
                prefillTps = 30.0,
                whenUtc = "2026-01-02T00:00:00Z"))
        assertTrue(lines.joinToString("\n")
            .contains("14.5 tok/s"))
    }

    @Test
    fun quickAccessLinks() {
        val links = HomeDashboard.quickAccess()
        assertEquals(4, links.size)
        assertEquals(4,
            links.map { it.label }.distinct().size)
        assertTrue(links.all { it.detail.isNotBlank() })
        assertEquals(1,
            links.count { it.tab == null })
    }
}
