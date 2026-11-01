package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF-UX-AERO-01 Step 6 accessibility, performance-bounds,
 * and honest-state acceptance: navigation destinations,
 * contextual actions, not-staged and staged-after-relaunch
 * Run states, bounded session cards, empty states, long
 * names, Q4_K_M display, prediction-vs-measured wording,
 * imported labeling, no-upload-without-consent,
 * offline behavior, theme/status honesty, and
 * screen-reader labels.
 */
class AeroStep6Test {

    private fun record(
        id: String = "SES-aaaaaaaaaaaaaaaa",
        state: String = SessionState.COMPARISON_READY,
        created: String = "2026-10-28T01:00:00Z",
        sha256: String = "0".repeat(64),
        display: String =
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        prediction: Map<String, Any?>? = null,
        benchmark: Map<String, Any?>? = null,
        comparison: Map<String, Any?>? = null
    ): SessionRecord =
        SessionRecord(
            sessionId = id, mode = "offline_test",
            state = state, createdAtUtc = created,
            updatedAtUtc = created, modelSha256 = sha256,
            modelSizeBytes = 1, modelDisplay = display,
            workloadId = "edgefit-prompt-v1",
            promptSha256 = "1".repeat(64),
            config = mapOf("n_ctx" to 512,
                "n_threads" to 4, "n_predict" to 64),
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

    private fun comparison(): Map<String, Any?> =
        mapOf("comparable" to true, "gen_signed" to 2.738,
            "gen_pct" to 18.25, "prefill_signed" to 5.99,
            "prefill_pct" to 23.96)

    private fun summary(
        status: String = BenchContract.STATUS_COMPLETED,
        gen: Double = 17.738
    ): BenchResultParse.Summary =
        BenchResultParse.Summary(
            runId = "BENCH-20261028T010000Z-aaaaaaaa",
            sha256 = "2".repeat(64),
            status = status, error = null,
            appVersion = "1.0",
            collectedAtUtc = "2026-10-28T01:00:00Z",
            deviceModel = "SM-S908B", sdkInt = 34,
            modelDisplay =
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            modelSizeBytes = 1048576L * 800,
            modelSha256 = "0".repeat(64),
            quantization = "Q4_K_M",
            nCtx = 512, nThreads = 4, nPredict = 64,
            sampler = "greedy",
            workloadId = "edgefit-prompt-v1",
            promptSha256 = "1".repeat(64),
            tpsGen = gen, tpsPrefill = 30.99,
            generatedTokens = 64,
            thermalBefore = "none", thermalMid = "none",
            thermalAfter = "none", memAvailPctStart = 55.0
        )

    private fun evidence(
        status: String = BenchContract.STATUS_COMPLETED,
        provenance: String =
            EvidenceStore.PROVENANCE_CAPTURED
    ): EvidenceStore.Record =
        EvidenceStore.Record(
            summary = summary(status),
            bytes = "{}".toByteArray(Charsets.UTF_8),
            provenance = provenance,
            storedAtUtc = "2026-10-28T01:00:00Z",
            contributionState =
                GlobalContract.LOCAL_NOT_SHARED,
            submissionId = null,
            serverEligibility = null,
            lastStatusAtUtc = null,
            consentPolicyVersion = null,
            consentAcceptedAtUtc = null
        )

    private fun result(
        status: String = BenchContract.STATUS_COMPLETED
    ): BenchResult = BenchResult(
        runId = "BENCH-20261028T010000Z-aaaaaaaa",
        device = BenchDeviceSnapshot("0".repeat(64),
            "SM-S908U", 36, listOf("arm64-v8a"), 8,
            12884901888L),
        model = BenchModelIdentity(
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            800000000L, "ab".repeat(32), "Q4_K_M",
            mapOf("general.architecture" to "llama")),
        runtimeVersion = "b1234",
        runtimeAbi = "arm64-v8a",
        config = BenchConfigData(512, 4, 64, 0.0,
            "greedy", 42L, 600000L),
        workload = BenchWorkload(
            BenchContract.WORKLOAD_ID,
            "36".repeat(32), 123),
        measurements = BenchMeasurements(1200L, 744L,
            23, 3607L, 64, 17.738, 30.99, false,
            BenchMemory(410 * 1024, 438 * 1024, 4096L,
                "debug-memoryinfo+mallinfo2"),
            BenchThermal("none", "light", "none", true)),
        status = status, error = null, appVersion = "0.2.0",
        collectedAtUtc = "2026-10-28T01:00:00Z",
        xNativeBuild = null
    )

    // ---- nav destinations ----

    @Test
    fun navDestinations_titlesSubtitlesDistinctAndComplete() {
        val tabs = NavTab.values().toList()
        assertEquals(5, tabs.size)
        val titles = tabs.map { NavTab.title(it) }
        assertTrue(titles.all { it.isNotBlank() })
        assertEquals(5, titles.toSet().size)
        val subtitles = tabs.map { NavTab.subtitle(it) }
        assertTrue(subtitles.all { it.isNotBlank() })
        assertEquals(5, subtitles.toSet().size)
    }

    @Test
    fun navDestinations_backAlwaysReturnsHome() {
        for (tab in NavTab.values()) {
            if (tab == NavTab.HOME) {
                assertNull(NavTab.backTarget(tab))
            } else {
                assertEquals(NavTab.HOME,
                    NavTab.backTarget(tab))
            }
        }
    }

    @Test
    fun navDestinations_fromNameRestoresOrDefaultsHome() {
        for (tab in NavTab.values()) {
            assertEquals(tab, NavTab.fromName(tab.name))
        }
        assertEquals(NavTab.HOME, NavTab.fromName(null))
        assertEquals(NavTab.HOME,
            NavTab.fromName("NoSuchTab"))
    }

    // ---- contextual actions ----

    @Test
    fun contextualActions_primaryLabelsDistinctPerState() {
        val labels = NextAction.values().map {
            HomeDashboard.primaryAction(it)
        }
        assertEquals(5, labels.size)
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(5, labels.toSet().size)
    }

    @Test
    fun contextualActions_everyActionHasDestinationTab() {
        for (action in NextAction.values()) {
            val tab = HomeDashboard.actionTab(action)
            assertTrue(NavTab.values().contains(tab))
        }
    }

    // ---- not-staged Run state ----

    @Test
    fun runNotStaged_disabledWithHonestGuidance() {
        assertFalse(BenchWorkflow.runAllowed(false, false))
        assertEquals("Stage a model to enable Run.",
            BenchWorkflow.runHint(false, false))
        assertTrue(BenchWorkflow.modelLine(null, 0L,
            null, null).contains("none staged"))
    }

    // ---- staged-after-relaunch ----

    @Test
    fun stagedAfterRelaunch_exactHashRule() {
        val sha = "ab".repeat(32)
        val measured = record(sha256 = sha,
            benchmark = benchmark())
        val card = HomeDashboard.modelReadiness(
            "m.gguf", sha, 1048576L, listOf(measured))
        assertEquals(HomeDashboard.ModelReadiness.MEASURED,
            card.state)
        val other = record(sha256 = "cd".repeat(32),
            benchmark = benchmark())
        val staged = HomeDashboard.modelReadiness(
            "m.gguf", sha, 1048576L, listOf(other))
        assertEquals(HomeDashboard.ModelReadiness.STAGED,
            staged.state)
        val stagedText = staged.lines.joinToString("\n")
        assertTrue(stagedText.contains("no completed run"))
        assertTrue(stagedText.contains("exact artifact"))
    }

    @Test
    fun stagedAfterRelaunch_failedRunDoesNotQualify() {
        val sha = "ab".repeat(32)
        val failed = record(sha256 = sha, benchmark =
            benchmark(status = BenchContract.STATUS_FAILED))
        assertFalse(HomeDashboard.stagedMeasured(
            sha, listOf(failed)))
        assertFalse(HomeDashboard.stagedMeasured(
            sha, emptyList()))
        assertFalse(HomeDashboard.stagedMeasured(
            null, listOf(failed)))
    }

    @Test
    fun stagedAfterRelaunch_lastMeasuredHonestWhenNone() {
        assertNull(HomeDashboard.lastMeasured(
            emptyList()))
        val lines = HomeDashboard.lastRunLines(null)
        assertEquals(2, lines.size)
        val joined = lines.joinToString(" ")
        assertFalse(joined.contains("tok/s"))
        assertFalse(joined.any { it.isDigit() })
    }

    // ---- session cards ----

    @Test
    fun sessionCards_boundedAtInlineMax() {
        val records = (0 until 62).map { index ->
            record(id = "SES-%016x".format(index))
        }
        val cards = AeroSessions.visibleCards(
            records, records.first().sessionId, null,
            { null })
        assertEquals(AeroSessions.INLINE_MAX, cards.size)
        assertEquals(
            "Showing 8 of 62 — open the browser for all.",
            AeroSessions.showMoreLine(62, cards.size))
        assertNull(AeroSessions.showMoreLine(8, 8))
    }

    @Test
    fun sessionCards_emptyStateHasNoThroughput() {
        val joined =
            AeroSessions.emptyLines().joinToString(" ")
        assertFalse(joined.contains("tok/s"))
        assertFalse(joined.any { it.isDigit() })
    }

    @Test
    fun sessionCards_failedRunNeverShowsThroughput() {
        val card = AeroSessions.card(
            record(benchmark = benchmark(
                status = BenchContract.STATUS_FAILED)),
            false, false, null)
        assertFalse(card.measuredPresent)
        assertTrue(card.measuredLine.contains(
            "No measurement"))
        assertFalse(card.measuredLine.contains("tok/s"))
    }

    // ---- long names ----

    @Test
    fun longNames_shortModelNameCapped() {
        val long = "Very-Long-Model-Name-For-A-Small-" +
            "Width-Phone-Q4_K_M.gguf"
        val short = SessionListUi.shortModelName(long)
        assertTrue(short.length <= 30)
        assertTrue(short.endsWith("..."))
        assertEquals("Llama 3.2 1B",
            SessionListUi.shortModelName("Llama_3.2_1B"))
    }

    @Test
    fun longNames_fullDisplayPreservedInModelLine() {
        val long = "Very-Long-Model-Name-For-A-Small-" +
            "Width-Phone-Q4_K_M.gguf"
        val line = BenchWorkflow.modelLine(
            long, 1048576L * 800, null, null)
        assertTrue(line.contains(long))
    }

    // ---- Q4_K_M display ----

    @Test
    fun q4km_quantizationSurfacesInSpecRows() {
        val rows = AeroResult.specRows(result())
        val quant = rows.firstOrNull {
            it.label == "Quantization"
        }
        assertNotNull(quant)
        assertEquals("Q4_K_M", quant!!.value)
        val model = rows.firstOrNull {
            it.label == "Model"
        }
        assertNotNull(model)
        assertTrue(model!!.value.contains("Q4_K_M"))
    }

    @Test
    fun q4km_readinessKeepsQuantVerbatim() {
        val card = HomeDashboard.modelReadiness(
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "ab".repeat(32), 1048576L, emptyList())
        assertTrue(card.lines.first().contains("Q4_K_M"))
    }

    // ---- prediction-vs-measured ----

    @Test
    fun predictionVsMeasured_estimateDiscipline() {
        val awaiting = AeroSessions.deltaLine(
            record(prediction = prediction()))
        assertNotNull(awaiting)
        assertTrue(awaiting!!.contains("ESTIMATE"))
        assertTrue(awaiting.contains(
            "awaiting measurement"))
        val compared = AeroSessions.deltaLine(record(
            prediction = prediction(),
            benchmark = benchmark(),
            comparison = comparison()))
        assertNotNull(compared)
        assertTrue(compared!!.contains("→ measured"))
        assertFalse(compared.lowercase().contains(
            "verified"))
        assertNull(AeroSessions.deltaLine(record()))
    }

    // ---- imported labeling ----

    @Test
    fun importedLabeling_evidenceLineNamesProvenance() {
        val run = record(benchmark = benchmark())
        val imported = AeroSessions.evidenceLine(run,
            AeroSessions.EvidenceInfo(
                EvidenceStore.PROVENANCE_IMPORTED,
                GlobalContract.LOCAL_NOT_SHARED,
                null, null))
        assertTrue(imported.contains("imported"))
        val captured = AeroSessions.evidenceLine(run,
            AeroSessions.EvidenceInfo(
                EvidenceStore.PROVENANCE_CAPTURED,
                GlobalContract.LOCAL_NOT_SHARED,
                null, null))
        assertTrue(captured.contains("captured"))
        assertEquals("Evidence: no run attached",
            AeroSessions.evidenceLine(record(), null))
    }

    @Test
    fun importedLabeling_provenanceNeverProofOfInstall() {
        assertEquals(
            "Imported file (not proof of this install)",
            AeroContribute.provenanceLine(
                EvidenceStore.PROVENANCE_IMPORTED))
        assertEquals("Measured on this install",
            AeroContribute.provenanceLine(
                EvidenceStore.PROVENANCE_CAPTURED))
    }

    // ---- no upload without consent ----

    @Test
    fun noUploadWithoutConsent_explicitTapOnly() {
        val joined =
            AeroContribute.dataLines().joinToString(" ")
        assertTrue(joined.contains("Approve and upload"))
        assertTrue(joined.contains("never automatically"))
        val configured = AeroContribute.connectionLine(
            "https://staging-host.example", "…9f2c")
        assertTrue(configured.contains(
            "Nothing uploads automatically"))
    }

    // ---- offline behavior ----

    @Test
    fun offlineBehavior_unconfiguredConnectionHonest() {
        assertEquals(
            "Cloud connection not configured.",
            AeroContribute.connectionLine(null, null))
        assertEquals("Cloud connection not configured",
            AeroContribute.NOT_CONFIGURED)
        val first = Onboarding.pages().first()
        assertTrue(first.lines.joinToString(" ")
            .contains("locally"))
    }

    // ---- theme/status ----

    @Test
    fun themeStatus_appearanceHonestNoFakeToggle() {
        val ctx = AeroSettings.Context(
            metaKeyStatus = "none", globalOrigin = null,
            globalTokenSuffix = null,
            globalTokenPersisted = false,
            versionName = "0.2.0",
            packageName = "ai.edgefit.diag",
            workloadId = BenchContract.WORKLOAD_ID)
        val appearance = AeroSettings.rows(
            AeroSettings.Group.APPEARANCE, ctx)
        assertEquals(1, appearance.size)
        assertNull(appearance.single().action)
        assertTrue(appearance.single().detail!!
            .contains("no appearance options"))
        for (group in AeroSettings.groups()) {
            assertTrue(AeroSettings.title(group)
                .isNotBlank())
            assertTrue(AeroSettings.helper(group)
                .isNotBlank())
        }
    }

    @Test
    fun themeStatus_heroPairsEveryOriginWithWords() {
        val measured = AeroResult.hero(result())
        assertEquals("Generation · Measured",
            measured.genCaption)
        assertTrue(measured.genValue.contains("tok/s"))
        val failed = AeroResult.hero(
            result(status = BenchContract.STATUS_FAILED))
        assertTrue(failed.statusLine.contains("FAILED"))
        assertTrue(failed.genCaption.isNotBlank())
        assertTrue(failed.prefillCaption.isNotBlank())
    }

    // ---- screen-reader labels ----

    @Test
    fun a11yLabels_detailsDescriptionsDistinctPerCard() {
        val first = AeroSessions.card(
            record(id = "SES-aaaaaaaaaaaaaaaa"),
            true, false, null)
        val second = AeroSessions.card(
            record(id = "SES-bbbbbbbbbbbbbbbb"),
            false, true, null)
        val a = AeroSessions.detailsDescription(first)
        val b = AeroSessions.detailsDescription(second)
        assertTrue(a.startsWith("Details for "))
        assertTrue(b.startsWith("Details for "))
        assertFalse(a == b)
        assertTrue(a.contains("aaaaaaaa"))
        assertTrue(b.contains("BOUND"))
    }

    @Test
    fun a11yLabels_tileDescriptionPairsLabelValue() {
        assertEquals("Battery: 85%",
            HomeDashboard.tileDescription(
                "Battery", "85%"))
        assertEquals(
            "Available RAM (live): unavailable",
            HomeDashboard.tileDescription(
                "Available RAM (live)", "unavailable"))
    }

    @Test
    fun a11yLabels_quickAccessFullyDescribed() {
        val links = HomeDashboard.quickAccess()
        assertTrue(links.isNotEmpty())
        assertTrue(links.all {
            it.label.isNotBlank() && it.detail.isNotBlank()
        })
        assertEquals(links.size,
            links.map { it.label }.toSet().size)
    }

    // ---- empty states + previews ----

    @Test
    fun emptyStates_agentEmptyHasNoDigitsOrThroughput() {
        val joined = AeroAgent.emptyLines()
            .joinToString(" ")
        assertFalse(joined.contains("tok/s"))
        assertFalse(joined.any { it.isDigit() })
    }

    @Test
    fun contributePreview_failedRunHasNoThroughput() {
        val lines = AeroContribute.previewLines(evidence(
            status = BenchContract.STATUS_FAILED))
        val joined = lines.joinToString(" ")
        assertTrue(joined.contains("no measurement"))
        assertFalse(joined.contains("tok/s"))
    }

    @Test
    fun contributePreview_completedKeepsQuantModelLine() {
        val lines = AeroContribute.previewLines(
            evidence())
        assertTrue(lines.any {
            it.contains("Q4_K_M")
        })
    }
}
