package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroDecisionTest {

    private fun record(
        id: String,
        updated: String,
        status: String?,
        modelSha: String = "0".repeat(64),
        prediction: Map<String, Any?>? = null,
        genTps: Any? = 14.5,
        prefillTps: Any? = 30.0
    ): SessionRecord = SessionRecord(
        sessionId = id, mode = "byok",
        state = SessionState.BENCHMARK_COMPLETED,
        createdAtUtc = updated, updatedAtUtc = updated,
        deviceModel = null, profileSha256 = null,
        modelSha256 = modelSha, modelSizeBytes = 1,
        modelDisplay = "m", workloadId = "w",
        promptSha256 = "1".repeat(64),
        config = emptyMap(), prediction = prediction,
        benchmark = status?.let {
            mapOf(
                "run_id" to "BENCH-$id",
                "status" to it,
                "measurements" to mapOf(
                    "tps_gen" to genTps,
                    "tps_prefill" to prefillTps),
                "collected_at_utc" to updated
            )
        },
        comparison = null, followup = null,
        parentSessionId = null, experimentSeq = null,
        budgetUsed = 0, budgetMax = 5, agent = null,
        history = emptyList(), recoveryNotes = emptyList()
    )

    private fun staged(
        sha: String = "ab".repeat(32)
    ): ModelContext.StagedRef = ModelContext.StagedRef(
        displayName = "Llama-3.2-1B.gguf",
        sha256 = sha,
        sizeBytes = 800L * 1024 * 1024
    )

    private fun assessment(
        status: String,
        firstReason: String = "arch test present (execution untested)"
    ): ModelSuitability.Assessment =
        ModelSuitability.Assessment(
            status = status,
            reasons = listOf(firstReason),
            sizeBoundBytes = 800L * 1024 * 1024,
            sizeBasis = "exact_bytes",
            requiredAvailBytes = 1024L * 1024 * 1024,
            measuredLocally = false
        )

    private fun prediction(
        rationale: String = "Small model fits the memory budget."
    ): Map<String, Any?> = mapOf(
        "predicted_gen_tps" to 12.0,
        "predicted_prefill_tps" to 28.0,
        "rationale" to rationale,
        "source_type" to "meta",
        "created_at_utc" to "2026-01-02T00:00:00Z"
    )

    private fun derive(
        staged: ModelContext.StagedRef? = staged(),
        sessions: List<SessionRecord> = emptyList(),
        assessment: ModelSuitability.Assessment? = null,
        recommendation: ModelRecommend.Recommendation? = null,
        availRamBytes: Long? = 6L * 1024 * 1024 * 1024
    ): AeroDecision.DecisionCard = AeroDecision.derive(
        staged, sessions, assessment, recommendation,
        availRamBytes
    )

    @Test
    fun brandCopyIsStatic() {
        assertEquals("EdgeFit AI", AeroDecision.BRAND_TITLE)
        assertEquals(
            "Your device, your model, your evidence.",
            AeroDecision.BRAND_TAGLINE)
    }

    @Test
    fun noModelHasHonestEmptyState() {
        val card = derive(staged = null, availRamBytes = null)
        assertEquals(AeroDecision.HeroState.NO_MODEL, card.state)
        assertEquals("No model", card.stateLabel)
        assertEquals("No model staged", card.heroName)
        val joined = (listOf(card.heroSub) +
            card.rows.map { "${it.label}: ${it.value}" })
            .joinToString("\n")
        assertFalse(joined.contains("tok/s"))
        assertFalse(joined.any { it.isDigit() })
        assertEquals(
            "No Meta explanation recorded yet.",
            card.metaInsight)
        assertFalse(card.metaPresent)
        assertNull(card.guidance)
    }

    @Test
    fun stagedUnmeasuredShowsIdentity() {
        val card = derive()
        assertEquals(
            AeroDecision.HeroState.STAGED_UNMEASURED,
            card.state)
        assertEquals(
            "Staged — not yet measured", card.stateLabel)
        assertEquals("Llama-3.2-1B.gguf", card.heroName)
        assertTrue(card.heroSub.contains("abababababab"))
        assertTrue(card.heroSub.contains("800 MB"))
        val values = card.rows.associate { it.label to it.value }
        assertTrue(values["Expected performance"]!!
            .contains("No estimate recorded"))
        assertTrue(values["Measured performance"]!!
            .contains("No completed run"))
    }

    @Test
    fun measuredNeedsExactHashCompleted() {
        val sha = "ab".repeat(32)
        val card = derive(sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z",
                BenchContract.STATUS_COMPLETED,
                modelSha = sha)))
        assertEquals(
            AeroDecision.HeroState.MEASURED, card.state)
        assertEquals(
            "Measured on this device", card.stateLabel)
        val measured = card.rows.first {
            it.label == "Measured performance"
        }
        assertEquals(AeroResult.Origin.MEASURED, measured.origin)
        assertTrue(measured.value.contains("14.5 tok/s"))
        assertTrue(measured.value.contains("Measured"))
    }

    @Test
    fun measuredIgnoresOtherHashesAndFailures() {
        val other = derive(sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z",
                BenchContract.STATUS_COMPLETED,
                modelSha = "cd".repeat(32))))
        assertEquals(
            AeroDecision.HeroState.STAGED_UNMEASURED,
            other.state)
        val failed = derive(sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z",
                BenchContract.STATUS_FAILED,
                modelSha = "ab".repeat(32))))
        assertEquals(
            AeroDecision.HeroState.STAGED_UNMEASURED,
            failed.state)
    }

    @Test
    fun measuredHashMatchIsCaseInsensitive() {
        val card = derive(
            staged = staged(sha = "AB".repeat(32)),
            sessions = listOf(
                record("SES-aaaaaaaaaaaaaaaa",
                    "2026-01-02T00:00:00Z",
                    BenchContract.STATUS_COMPLETED,
                    modelSha = "ab".repeat(32))))
        assertEquals(
            AeroDecision.HeroState.MEASURED, card.state)
    }

    @Test
    fun measuredIgnoresNonFiniteThroughput() {
        val card = derive(sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z",
                BenchContract.STATUS_COMPLETED,
                modelSha = "ab".repeat(32),
                genTps = Double.NaN)))
        assertEquals(
            AeroDecision.HeroState.STAGED_UNMEASURED,
            card.state)
    }

    @Test
    fun likelyCompatibleOverlaysEstimatedFit() {
        val card = derive(
            assessment = assessment(
                ModelSuitability.LIKELY_COMPATIBLE))
        assertEquals(
            AeroDecision.HeroState.ESTIMATED_FIT,
            card.state)
        assertEquals("Estimated fit", card.stateLabel)
        val compat = card.rows.first {
            it.label == "Hardware compatibility"
        }
        assertTrue(compat.value.contains("Likely compatible"))
    }

    @Test
    fun unsupportedShowsConstraintText() {
        val card = derive(
            assessment = assessment(
                ModelSuitability.UNSUPPORTED,
                firstReason = "device ABI lacks arm64-v8a"))
        assertEquals(
            AeroDecision.HeroState.NOT_SUPPORTED,
            card.state)
        assertEquals("Not supported", card.stateLabel)
        val constraint = card.rows.first {
            it.label == "Primary constraint"
        }
        assertTrue(constraint.value.contains("arm64-v8a"))
    }

    @Test
    fun insufficientEvidenceHasOwnState() {
        val card = derive(
            assessment = assessment(
                ModelSuitability.INSUFFICIENT_EVIDENCE,
                firstReason = "artifact size unverified"))
        assertEquals(
            AeroDecision.HeroState.INSUFFICIENT_EVIDENCE,
            card.state)
        assertEquals("Insufficient evidence", card.stateLabel)
    }

    @Test
    fun assessmentNeverOverridesMeasurement() {
        val sha = "ab".repeat(32)
        val card = derive(
            sessions = listOf(
                record("SES-aaaaaaaaaaaaaaaa",
                    "2026-01-02T00:00:00Z",
                    BenchContract.STATUS_COMPLETED,
                    modelSha = sha)),
            assessment = assessment(
                ModelSuitability.LIKELY_COMPATIBLE))
        assertEquals(
            AeroDecision.HeroState.MEASURED, card.state)
    }

    @Test
    fun expectedPerformanceBindsToStagedHash() {
        val card = derive(sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z", null,
                modelSha = "cd".repeat(32),
                prediction = prediction())))
        val expected = card.rows.first {
            it.label == "Expected performance"
        }
        assertTrue(expected.value.contains("No estimate recorded"))
    }

    @Test
    fun expectedPerformanceLabeledEstimate() {
        val card = derive(sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z", null,
                modelSha = "ab".repeat(32),
                prediction = prediction())))
        val expected = card.rows.first {
            it.label == "Expected performance"
        }
        assertEquals(
            AeroResult.Origin.ESTIMATED, expected.origin)
        assertTrue(expected.value.contains("12.0 tok/s"))
        assertTrue(expected.value.contains("ESTIMATE"))
        assertFalse(expected.value.contains("Measured"))
    }

    @Test
    fun metaInsightExcerptsStoredRationale() {
        val card = derive(sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z", null,
                modelSha = "ab".repeat(32),
                prediction = prediction(
                    rationale = "Fits easily with room to spare."))))
        assertTrue(card.metaPresent)
        assertTrue(card.metaInsight.contains("Fits easily"))
    }

    @Test
    fun metaInsightTruncatesLongRationale() {
        val card = derive(sessions = listOf(
            record("SES-aaaaaaaaaaaaaaaa",
                "2026-01-02T00:00:00Z", null,
                modelSha = "ab".repeat(32),
                prediction = prediction(
                    rationale = "x".repeat(500)))))
        assertTrue(card.metaInsight.length <= 163)
        assertTrue(card.metaInsight.endsWith("…"))
    }

    @Test
    fun memoryRowFormatsLiveSample() {
        val card = derive()
        val memory = card.rows.first {
            it.label == "Available memory"
        }
        assertEquals("6.0 GB", memory.value)
    }

    @Test
    fun memoryRowUnavailableWhenNull() {
        val card = derive(availRamBytes = null)
        val memory = card.rows.first {
            it.label == "Available memory"
        }
        assertEquals("unavailable", memory.value)
    }

    @Test
    fun memoryLimitedNowSurfacesAsConstraint() {
        val limited = assessment(
            ModelSuitability.LIKELY_COMPATIBLE,
            firstReason = "arch test present (execution untested)"
        ).copy(memoryLimitedNow = true)
        val card = derive(assessment = limited)
        val constraint = card.rows.first {
            it.label == "Primary constraint"
        }
        assertTrue(constraint.value.contains("Memory limited"))
    }

    @Test
    fun guidanceCarriesRecommendationWhenPresent() {
        val card = derive(
            recommendation = ModelRecommend.Recommendation(
                ModelRecommend.Kind.SMALLER_FOOTPRINT,
                "Smaller listed footprint.",
                "Listed artifact is smaller than current."))
        assertTrue(card.guidance!!.contains("Smaller listed"))
    }

    @Test
    fun stateLabelsAreDistinct() {
        val labels = AeroDecision.HeroState.values()
            .map { AeroDecision.stateLabel(it) }
        assertEquals(6, labels.distinct().size)
        assertTrue(labels.all { it.isNotBlank() })
    }
}
