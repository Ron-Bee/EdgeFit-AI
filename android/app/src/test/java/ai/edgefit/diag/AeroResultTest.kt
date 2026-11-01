package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF-UX-AERO-01 Step 4 result derivations: hero honesty,
 * spec completeness, run-id binding, and ESTIMATE
 * discipline (a prediction is never measured/verified).
 */
class AeroResultTest {

    private fun result(
        status: String = BenchContract.STATUS_COMPLETED,
        tpsGen: Double = 17.738,
        tpsPrefill: Double = 30.99,
        generated: Int = 64,
        prefillTokens: Int = 23,
        error: String? = null,
        thermalAvailable: Boolean = true,
        pssBeforeKb: Int = 410 * 1024,
        pssAfterKb: Int = 438 * 1024,
        runtimeVersion: String = "b1234",
        native: Map<String, String>? = mapOf(
            "variant" to "benchmark", "native_opt" to "-O3 -g")
    ): BenchResult = BenchResult(
        runId = "BENCH-20261028T010000Z-aaaaaaaa",
        device = BenchDeviceSnapshot("0".repeat(64),
            "SM-S908U", 36, listOf("arm64-v8a"), 8, 12884901888L),
        model = BenchModelIdentity(
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 800000000L,
            "ab".repeat(32), "Q4_K_M",
            mapOf("general.architecture" to "llama")),
        runtimeVersion = runtimeVersion,
        runtimeAbi = "arm64-v8a",
        config = BenchConfigData(512, 4, 64, 0.0, "greedy",
            42L, 600000L),
        workload = BenchWorkload(BenchContract.WORKLOAD_ID,
            "36".repeat(32), 123),
        measurements = BenchMeasurements(1200L, 744L,
            prefillTokens, 3607L, generated, tpsGen, tpsPrefill,
            false,
            BenchMemory(pssBeforeKb, pssAfterKb, 4096L,
                "debug-memoryinfo+mallinfo2"),
            BenchThermal("none", "light", "none",
                thermalAvailable)),
        status = status, error = error, appVersion = "0.2.0",
        collectedAtUtc = "2026-10-28T01:00:00Z",
        xNativeBuild = native
    )

    private fun record(
        id: String = "SES-aaaaaaaaaaaaaaaa",
        prediction: Map<String, Any?>? = null,
        benchmark: Map<String, Any?>? = null,
        comparison: Map<String, Any?>? = null
    ): SessionRecord =
        SessionRecord(
            sessionId = id, mode = "offline_test",
            state = SessionState.COMPARISON_READY,
            createdAtUtc = "2026-10-28T01:00:00Z",
            updatedAtUtc = "2026-10-28T01:05:00Z",
            modelSha256 = "0".repeat(64), modelSizeBytes = 1,
            modelDisplay = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            workloadId = "edgefit-prompt-v1",
            promptSha256 = "1".repeat(64),
            config = mapOf("n_ctx" to 512, "n_threads" to 4,
                "n_predict" to 64),
            prediction = prediction, benchmark = benchmark,
            comparison = comparison
        )

    private fun prediction(): Map<String, Any?> = mapOf(
        "predicted_gen_tps" to 15.0,
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

    // ---- hero ----

    @Test
    fun heroCompletedIsMeasured() {
        val hero = AeroResult.hero(result())
        assertEquals("17.7 tok/s", hero.genValue)
        assertEquals("Generation · Measured", hero.genCaption)
        assertEquals(AeroResult.Origin.MEASURED, hero.genOrigin)
        assertEquals("31.0 tok/s", hero.prefillValue)
        assertEquals("Prefill · Measured", hero.prefillCaption)
        assertEquals(AeroResult.Origin.MEASURED,
            hero.prefillOrigin)
        assertEquals("COMPLETED · 64 tokens in 3607 ms",
            hero.statusLine)
    }

    @Test
    fun heroFailedZeroOutputIsUnavailable() {
        val hero = AeroResult.hero(result(
            status = BenchContract.STATUS_FAILED,
            tpsGen = 0.0, tpsPrefill = 0.0, generated = 0,
            prefillTokens = 0, error = "model load failed"))
        assertEquals("Not measured", hero.genValue)
        assertEquals(AeroResult.Origin.UNAVAILABLE,
            hero.genOrigin)
        assertEquals("Not measured", hero.prefillValue)
        assertEquals(AeroResult.Origin.UNAVAILABLE,
            hero.prefillOrigin)
        assertTrue(hero.statusLine.startsWith("FAILED · "))
        assertTrue(hero.statusLine.contains("model load failed"))
    }

    @Test
    fun heroPartialIsMeasuredPartial() {
        val hero = AeroResult.hero(result(
            status = BenchContract.STATUS_CANCELLED,
            tpsGen = 16.2, generated = 12))
        assertEquals("16.2 tok/s", hero.genValue)
        assertEquals("Generation · Measured (partial)",
            hero.genCaption)
        assertEquals(AeroResult.Origin.MEASURED, hero.genOrigin)
    }

    @Test
    fun heroNonFiniteRateIsUnavailable() {
        val hero = AeroResult.hero(
            result(tpsGen = Double.NaN))
        assertEquals("Not measured", hero.genValue)
        assertEquals(AeroResult.Origin.UNAVAILABLE,
            hero.genOrigin)
    }

    // ---- spec rows ----

    @Test
    fun specRowsCoverRequiredSignals() {
        val rows = AeroResult.specRows(result())
        val labels = rows.map { it.label }
        for (want in listOf("Model", "Quantization", "Device",
            "Runtime", "Config", "Memory", "Thermal",
            "Collected", "Model SHA-256", "Workload",
            "Provenance", "Native build")) {
            assertTrue("missing $want", want in labels)
        }
        val byLabel = rows.associateBy { it.label }
        assertEquals("Q4_K_M",
            byLabel.getValue("Quantization").value)
        assertTrue(byLabel.getValue("Config").value
            .contains("512 ctx"))
        assertTrue(byLabel.getValue("Memory").value
            .contains("PSS 410 → 438 MB"))
        assertEquals("none / light / none",
            byLabel.getValue("Thermal").value)
        assertEquals(BenchContract.PROVENANCE,
            byLabel.getValue("Provenance").value)
        assertTrue(byLabel.getValue("Native build").value
            .contains("benchmark"))
        val joined = rows.joinToString("\n") {
            "${it.label} ${it.value}"
        }
        assertFalse(joined.contains("verif", ignoreCase = true))
    }

    @Test
    fun specRowsMarkMissingSignalsUnavailable() {
        val rows = AeroResult.specRows(result(
            thermalAvailable = false, pssBeforeKb = -1,
            pssAfterKb = -1, runtimeVersion = "unavailable",
            native = null))
        val byLabel = rows.associateBy { it.label }
        assertEquals(AeroResult.Origin.UNAVAILABLE,
            byLabel.getValue("Thermal").origin)
        assertEquals(AeroResult.Origin.UNAVAILABLE,
            byLabel.getValue("Memory").origin)
        assertEquals(AeroResult.Origin.UNAVAILABLE,
            byLabel.getValue("Runtime").origin)
        assertEquals(AeroResult.Origin.UNAVAILABLE,
            byLabel.getValue("Native build").origin)
        assertTrue(byLabel.getValue("Memory").value
            .contains("unavailable", ignoreCase = true))
    }

    // ---- binding ----

    @Test
    fun attachedSessionMatchesRunIdOnly() {
        val runId = "BENCH-20261028T010000Z-aaaaaaaa"
        val carrying = record(
            id = "SES-cccccccccccccccc",
            benchmark = benchmark(runId = runId))
        val other = record(id = "SES-dddddddddddddddd",
            benchmark = benchmark(runId = "BENCH-other"))
        val bound = record(id = "SES-bbbbbbbbbbbbbbbb")
        assertEquals(carrying.sessionId,
            AeroResult.attachedSession(
                listOf(other, bound, carrying), runId)
                ?.sessionId)
        assertNull(AeroResult.attachedSession(
            listOf(other, bound), runId))
    }

    @Test
    fun bindingChipNeverClaimsWithoutMatch() {
        val runId = "BENCH-20261028T010000Z-aaaaaaaa"
        assertEquals("Standalone measurement",
            AeroResult.bindingChip(runId, null))
        assertEquals("Attached: SES-cccccccccccccccc",
            AeroResult.bindingChip(runId, record(
                id = "SES-cccccccccccccccc",
                benchmark = benchmark(runId = runId))))
        val chip = AeroResult.bindingChip(runId, record(
            id = "SES-bbbbbbbbbbbbbbbb",
            benchmark = benchmark(runId = "BENCH-other")))
        assertTrue(chip.startsWith("Standalone measurement"))
        assertTrue(chip.contains("SES-bbbbbbbbbbbbbbbb"))
    }

    // ---- delta + details ----

    @Test
    fun deltaLineStates() {
        assertNull(AeroResult.deltaLine(null))
        assertNull(AeroResult.deltaLine(record()))
        val awaiting = AeroResult.deltaLine(
            record(prediction = prediction()))
        assertTrue(awaiting!!.contains("ESTIMATE"))
        assertTrue(awaiting.contains("awaiting measurement"))
        val compared = AeroResult.deltaLine(record(
            prediction = prediction(), benchmark = benchmark(),
            comparison = comparison()))
        assertTrue(compared!!.contains("Predicted"))
        assertTrue(compared.contains("measured"))
        assertTrue(compared.contains("Δ"))
        assertFalse(compared.contains("verif", ignoreCase = true))
        val refused = AeroResult.deltaLine(record(
            prediction = prediction(), benchmark = benchmark(),
            comparison = comparison(comparable = false)))
        assertTrue(refused!!.startsWith("Not comparable"))
        assertTrue(refused.contains("test fixture"))
    }

    @Test
    fun detailSectionsKeepEstimateDiscipline() {
        val res = result()
        val standalone = AeroResult.detailSections(res, null)
        val titles = standalone.map { it.title }
        assertTrue("Measured result" in titles)
        assertTrue("Session binding" in titles)
        val binding = standalone.first {
            it.title == "Session binding"
        }.rows.single().value
        assertEquals("Standalone measurement", binding)

        val attached = AeroResult.detailSections(res, record(
            prediction = prediction(), benchmark = benchmark(),
            comparison = comparison()))
        val joined = attached.flatMap { section ->
            section.rows.map { "${it.label} ${it.value}" }
        }.joinToString("\n")
        assertTrue(joined.contains("ESTIMATE"))
        assertTrue(joined.contains("host-computed") ||
            attached.any {
                it.title.contains("host-computed")
            })
        assertFalse(
            joined.contains("verif", ignoreCase = true))
        // No prediction row may carry the measured accent.
        for (section in attached) {
            for (row in section.rows) {
                if (row.accent == InsightCards.Accent.MEASURED) {
                    assertFalse(row.label.contains("ESTIMATE"))
                }
                if (row.label.contains("ESTIMATE")) {
                    assertEquals(InsightCards.Accent.PREDICTED,
                        row.accent)
                }
            }
        }
    }

    @Test
    fun originLabelsAreExact() {
        assertEquals("Measured",
            AeroResult.Origin.MEASURED.label)
        assertEquals("Predicted",
            AeroResult.Origin.PREDICTED.label)
        assertEquals("Estimated",
            AeroResult.Origin.ESTIMATED.label)
        assertEquals("Unavailable",
            AeroResult.Origin.UNAVAILABLE.label)
        assertEquals("Not comparable",
            AeroResult.Origin.NOT_COMPARABLE.label)
    }
}
