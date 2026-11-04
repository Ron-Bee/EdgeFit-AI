package ai.edgefit.diag

/**
 * EF-ANDROID-AERO-OVERHAUL-01 Priority One: Decision Card
 * derivation. Pure JVM over already-available inputs —
 * staged artifact, loaded session records, a suitability
 * assessment and recommendation computed by the activity
 * through the existing functions, and one live memory
 * sample. Never touches views, storage, or the network;
 * never invents a verdict. Absent inputs render honest
 * empty states, and anything the card cannot evidence is
 * labeled unavailable — never estimated-as-measured.
 */
object AeroDecision {

    const val BRAND_TITLE = "EdgeFit AI"
    const val BRAND_TAGLINE = "Your device, your model, your evidence."

    /** Card hero states, each from real state only. */
    enum class HeroState {
        NO_MODEL,
        STAGED_UNMEASURED,
        MEASURED,
        ESTIMATED_FIT,
        NOT_SUPPORTED,
        INSUFFICIENT_EVIDENCE
    }

    /** One evidence row; a null origin renders untinted. */
    data class EvidenceRow(
        val label: String,
        val value: String,
        val origin: AeroResult.Origin?
    )

    data class DecisionCard(
        val heroName: String,
        val heroSub: String,
        val state: HeroState,
        val stateLabel: String,
        val rows: List<EvidenceRow>,
        val metaInsight: String,
        val metaPresent: Boolean,
        /** Recommendation headline + body, or null. */
        val guidance: String?
    )

    data class StagedMeasurement(
        val sessionId: String,
        val runId: String,
        val genTps: Double,
        val prefillTps: Double?,
        val collectedAtUtc: String
    )

    fun stateLabel(state: HeroState): String = when (state) {
        HeroState.NO_MODEL ->
            HomeDashboard.readinessLabel(
                HomeDashboard.ModelReadiness.NONE)
        HeroState.STAGED_UNMEASURED ->
            HomeDashboard.readinessLabel(
                HomeDashboard.ModelReadiness.STAGED)
        HeroState.MEASURED ->
            HomeDashboard.readinessLabel(
                HomeDashboard.ModelReadiness.MEASURED)
        HeroState.ESTIMATED_FIT -> "Estimated fit"
        HeroState.NOT_SUPPORTED -> "Not supported"
        HeroState.INSUFFICIENT_EVIDENCE ->
            "Insufficient evidence"
    }

    /**
     * Latest valid COMPLETED measurement of one exact
     * artifact hash: COMPLETED status, identified run,
     * finite generation throughput, recorded collection
     * time. Anything else is not evidence and is skipped.
     */
    @Suppress("UNCHECKED_CAST")
    fun stagedMeasurement(
        sha256: String?,
        sessions: List<SessionRecord>
    ): StagedMeasurement? {
        val want = sha256?.lowercase()?.ifBlank { null }
            ?: return null
        return sessions.mapNotNull { record ->
            if (record.modelSha256.lowercase() != want) {
                return@mapNotNull null
            }
            val bench = record.benchmark as? Map<String, Any?>
                ?: return@mapNotNull null
            if (bench["status"] != BenchContract.STATUS_COMPLETED) {
                return@mapNotNull null
            }
            val runId = bench["run_id"] as? String
                ?: return@mapNotNull null
            val measures = bench["measurements"] as? Map<String, Any?>
                ?: return@mapNotNull null
            val gen = (measures["tps_gen"] as? Number)?.toDouble()
            if (gen == null || !gen.isFinite()) {
                return@mapNotNull null
            }
            val collected = bench["collected_at_utc"] as? String
                ?: return@mapNotNull null
            StagedMeasurement(
                sessionId = record.sessionId,
                runId = runId,
                genTps = gen,
                prefillTps = (measures["tps_prefill"] as? Number)
                    ?.toDouble()?.takeIf { it.isFinite() },
                collectedAtUtc = collected
            )
        }.maxByOrNull { it.collectedAtUtc }
    }

    /**
     * Latest stored prediction bound to one exact artifact
     * hash. Predictions for other artifacts are never
     * presented as this model's expectation.
     */
    fun latestPredictionFor(
        sha256: String?,
        sessions: List<SessionRecord>
    ): Map<String, Any?>? {
        val want = sha256?.ifBlank { null } ?: return null
        return sessions
            .filter {
                it.prediction != null &&
                    it.modelSha256.equals(want, ignoreCase = true)
            }
            .maxByOrNull { it.updatedAtUtc }
            ?.prediction
    }

    fun derive(
        staged: ModelContext.StagedRef?,
        sessions: List<SessionRecord>,
        assessment: ModelSuitability.Assessment?,
        recommendation: ModelRecommend.Recommendation?,
        availRamBytes: Long?
    ): DecisionCard {
        if (staged == null) {
            return DecisionCard(
                heroName = "No model staged",
                heroSub = "Select a GGUF file to begin.",
                state = HeroState.NO_MODEL,
                stateLabel = stateLabel(HeroState.NO_MODEL),
                rows = listOf(
                    EvidenceRow("Model identity",
                        "No model staged", null),
                    EvidenceRow("Hardware compatibility",
                        "unavailable", null),
                    EvidenceRow("Available memory",
                        memoryValue(availRamBytes), null),
                    EvidenceRow("Expected performance",
                        "No estimate recorded", null),
                    EvidenceRow("Measured performance",
                        "No completed run recorded", null),
                    EvidenceRow("Primary constraint",
                        "No constraint identified", null)
                ),
                metaInsight = "No Meta explanation recorded yet.",
                metaPresent = false,
                guidance = null
            )
        }
        val mb = staged.sizeBytes / (1024L * 1024L)
        val heroSub = "SHA " +
            "${staged.sha256.ifBlank { null }?.take(12) ?: "?"} · " +
            "$mb MB"
        val measurement = stagedMeasurement(staged.sha256, sessions)
        val prediction =
            latestPredictionFor(staged.sha256, sessions)
        val state = heroState(measurement, assessment)
        return DecisionCard(
            heroName = staged.displayName,
            heroSub = heroSub,
            state = state,
            stateLabel = stateLabel(state),
            rows = listOf(
                EvidenceRow("Model identity", heroSub, null),
                EvidenceRow("Hardware compatibility",
                    compatibilityValue(assessment), null),
                EvidenceRow("Available memory",
                    memoryValue(availRamBytes), null),
                EvidenceRow("Expected performance",
                    expectedValue(prediction).first,
                    expectedValue(prediction).second),
                EvidenceRow("Measured performance",
                    measuredValue(measurement).first,
                    measuredValue(measurement).second),
                EvidenceRow("Primary constraint",
                    constraintValue(
                        measurement, assessment), null)
            ),
            metaInsight = metaValue(prediction).first,
            metaPresent = metaValue(prediction).second,
            guidance = recommendation?.let {
                truncate("${it.headline} ${it.body}", 280)
            }
        )
    }

    private fun heroState(
        measurement: StagedMeasurement?,
        assessment: ModelSuitability.Assessment?
    ): HeroState {
        // Measured evidence always wins; a suitability
        // verdict overlays the unmeasured states only.
        if (measurement != null) return HeroState.MEASURED
        return when (assessment?.status) {
            ModelSuitability.LIKELY_COMPATIBLE ->
                HeroState.ESTIMATED_FIT
            ModelSuitability.UNSUPPORTED ->
                HeroState.NOT_SUPPORTED
            ModelSuitability.INSUFFICIENT_EVIDENCE ->
                HeroState.INSUFFICIENT_EVIDENCE
            else -> HeroState.STAGED_UNMEASURED
        }
    }

    private fun compatibilityValue(
        assessment: ModelSuitability.Assessment?
    ): String {
        if (assessment == null) {
            return "Not assessed — staged file is not in the catalog."
        }
        val verdict = when (assessment.status) {
            ModelSuitability.VERIFIED_COMPATIBLE ->
                "Verified compatible"
            ModelSuitability.LIKELY_COMPATIBLE ->
                "Likely compatible (unverified)"
            ModelSuitability.UNSUPPORTED -> "Not supported"
            ModelSuitability.INSUFFICIENT_EVIDENCE ->
                "Insufficient evidence"
            else -> assessment.status
        }
        val reason = assessment.reasons.firstOrNull()
        return if (reason == null) verdict
        else "$verdict — $reason"
    }

    private fun memoryValue(availRamBytes: Long?): String {
        val avail = availRamBytes?.takeIf { it > 0 }
            ?: return "unavailable"
        return DeviceTelemetry.formatRam(avail)
    }

    private fun expectedValue(
        prediction: Map<String, Any?>?
    ): Pair<String, AeroResult.Origin?> {
        if (prediction == null) {
            return "No estimate recorded" to null
        }
        val gen = (prediction["predicted_gen_tps"] as? Number)
            ?.toDouble()?.takeIf { it.isFinite() }
        val prefill = (prediction["predicted_prefill_tps"]
            as? Number)?.toDouble()?.takeIf { it.isFinite() }
        if (gen == null && prefill == null) {
            return "Recorded estimate has no throughput " +
                "figures" to null
        }
        val parts = mutableListOf<String>()
        if (gen != null) {
            parts.add("Generation ${InsightCards.tps(gen)}")
        }
        if (prefill != null) {
            parts.add("Prefill ${InsightCards.tps(prefill)}")
        }
        return "${parts.joinToString(" · ")} (ESTIMATE)" to
            AeroResult.Origin.ESTIMATED
    }

    private fun measuredValue(
        measurement: StagedMeasurement?
    ): Pair<String, AeroResult.Origin?> {
        if (measurement == null) {
            return "No completed run of this exact artifact " +
                "yet." to null
        }
        val parts = mutableListOf(
            "Generation ${InsightCards.tps(measurement.genTps)}")
        measurement.prefillTps?.let {
            parts.add("Prefill ${InsightCards.tps(it)}")
        }
        return "${parts.joinToString(" · ")} — Measured on " +
            "this device" to AeroResult.Origin.MEASURED
    }

    private fun constraintValue(
        measurement: StagedMeasurement?,
        assessment: ModelSuitability.Assessment?
    ): String {
        if (assessment == null) {
            return "Not assessed — staged file is not in the catalog."
        }
        if (assessment.memoryLimitedNow) {
            return "Memory limited right now. " +
                (assessment.reasons.firstOrNull()
                    ?: "free memory is below the requirement.")
        }
        if (measurement != null &&
            assessment.status ==
                ModelSuitability.VERIFIED_COMPATIBLE
        ) {
            return "None — verified compatible on this device."
        }
        return assessment.reasons.firstOrNull()
            ?: "No constraint identified."
    }

    private fun metaValue(
        prediction: Map<String, Any?>?
    ): Pair<String, Boolean> {
        val rationale = (prediction?.get("rationale")
            as? String)?.ifBlank { null }
        if (rationale == null) {
            return "No Meta explanation recorded yet." to false
        }
        return truncate(rationale, 160) to true
    }

    private fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.take(max).trimEnd() + "…"
    }
}
