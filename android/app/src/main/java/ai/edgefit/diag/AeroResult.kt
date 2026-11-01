package ai.edgefit.diag

/**
 * EF-UX-AERO-01 Step 4 result derivations. Pure builders
 * over one measured [BenchResult] plus the durable
 * session that may carry it (found by run-id scan, never
 * by assumption). The activity renders; this object never
 * touches views, storage, or the runner.
 *
 * Origin discipline: every displayed figure carries one
 * of [Origin]. Run-artifact facts (model, config,
 * timings, SHAs) are MEASURED; a stored pre-run
 * prediction is PREDICTED and always reads ESTIMATE;
 * live provisional or expected-but-unrun values are
 * ESTIMATED; anything absent is UNAVAILABLE; a refused
 * comparison is NOT_COMPARABLE with its reason. A
 * prediction is never presented as measured or verified.
 */
object AeroResult {

    enum class Origin(val label: String) {
        MEASURED("Measured"),
        PREDICTED("Predicted"),
        ESTIMATED("Estimated"),
        UNAVAILABLE("Unavailable"),
        NOT_COMPARABLE("Not comparable")
    }

    data class Hero(
        val genValue: String,
        val genCaption: String,
        val genOrigin: Origin,
        val prefillValue: String,
        val prefillCaption: String,
        val prefillOrigin: Origin,
        val statusLine: String
    )

    data class SpecRow(
        val label: String,
        val value: String,
        val origin: Origin
    )

    /**
     * Hero result: generation throughput large, prefill
     * secondary. COMPLETED runs show measured rates;
     * cancelled/timed-out runs with genuine partial
     * output show measured partial rates; anything else
     * is honestly unavailable, never a zero datapoint.
     * Non-finite rates are unavailable even on COMPLETED
     * (corrupt-data honesty).
     */
    fun hero(result: BenchResult): Hero {
        val m = result.measurements
        val completed =
            result.status == BenchContract.STATUS_COMPLETED
        val genMeasured = m.tpsGen.isFinite() &&
            (completed || m.generatedTokens > 0)
        val genPartial = genMeasured && !completed
        val genValue = if (genMeasured) InsightCards.tps(m.tpsGen)
        else "Not measured"
        val genCaption = when {
            !genMeasured -> "Generation · Unavailable"
            genPartial -> "Generation · Measured (partial)"
            else -> "Generation · Measured"
        }
        val prefillMeasured = m.tpsPrefill.isFinite() &&
            (completed || m.prefillTokens > 0)
        val prefillPartial = prefillMeasured && !completed
        val prefillValue = if (prefillMeasured) {
            InsightCards.tps(m.tpsPrefill)
        } else {
            "Not measured"
        }
        val prefillCaption = when {
            !prefillMeasured -> "Prefill · Unavailable"
            prefillPartial -> "Prefill · Measured (partial)"
            else -> "Prefill · Measured"
        }
        return Hero(
            genValue = genValue,
            genCaption = genCaption,
            genOrigin = if (genMeasured) Origin.MEASURED
            else Origin.UNAVAILABLE,
            prefillValue = prefillValue,
            prefillCaption = prefillCaption,
            prefillOrigin = if (prefillMeasured) Origin.MEASURED
            else Origin.UNAVAILABLE,
            statusLine = statusLine(result)
        )
    }

    private fun statusLine(result: BenchResult): String {
        val m = result.measurements
        if (result.status == BenchContract.STATUS_COMPLETED) {
            return "COMPLETED · ${m.generatedTokens} tokens in " +
                "${m.genMs} ms"
        }
        val error = result.error
        if (!error.isNullOrBlank()) {
            return "${result.status} · ${BenchUiText.safeError(error)}"
        }
        return "${result.status} · ${m.generatedTokens}/" +
            "${result.config.nPredict} tokens"
    }

    /**
     * Inline spec block: model, quantization, device,
     * runtime, config, memory, thermal, timestamp, SHAs,
     * workload, provenance, native build. Identity facts
     * of the run artifact read MEASURED; missing signals
     * read UNAVAILABLE with no invented value.
     */
    fun specRows(result: BenchResult): List<SpecRow> {
        val m = result.measurements
        val rows = mutableListOf(
            SpecRow("Model", result.model.displayName,
                Origin.MEASURED),
            SpecRow("Quantization", result.model.quantization,
                Origin.MEASURED),
            SpecRow("Device",
                "${result.device.model} · API ${result.device.sdkInt}",
                Origin.MEASURED),
            SpecRow("Config",
                "${result.config.nCtx} ctx · " +
                    "${result.config.nThreads} threads · " +
                    "${result.config.nPredict} tokens",
                Origin.MEASURED)
        )
        if (result.runtimeVersion == "unavailable") {
            rows.add(SpecRow("Runtime",
                "Runtime version unavailable",
                Origin.UNAVAILABLE))
        } else {
            rows.add(SpecRow("Runtime",
                "${BenchContract.RUNTIME_IMPL} " +
                    "${result.runtimeVersion} · ${result.runtimeAbi}",
                Origin.MEASURED))
        }
        val mem = m.mem
        if (mem.pssBeforeKb >= 0 && mem.pssAfterKb >= 0) {
            rows.add(SpecRow("Memory",
                "PSS ${mem.pssBeforeKb / 1024} → " +
                    "${mem.pssAfterKb / 1024} MB (${mem.method})",
                Origin.MEASURED))
        } else {
            rows.add(SpecRow("Memory",
                "Memory readings unavailable",
                Origin.UNAVAILABLE))
        }
        val thermal = m.thermal
        if (thermal.available) {
            rows.add(SpecRow("Thermal",
                "${thermal.before} / ${thermal.mid} / ${thermal.after}",
                Origin.MEASURED))
        } else {
            rows.add(SpecRow("Thermal",
                "Thermal state unavailable on this device",
                Origin.UNAVAILABLE))
        }
        rows.add(SpecRow("Collected",
            SessionTimeline.formatLocal(result.collectedAtUtc),
            Origin.MEASURED))
        rows.add(SpecRow("Model SHA-256",
            result.model.sha256.take(12) + "… (full in Details)",
            Origin.MEASURED))
        rows.add(SpecRow("Workload",
            "${result.workload.id} · prompt " +
                result.workload.promptSha256.take(12) + "…",
            Origin.MEASURED))
        rows.add(SpecRow("Provenance", BenchContract.PROVENANCE,
            Origin.MEASURED))
        val native = result.xNativeBuild
        if (native == null) {
            rows.add(SpecRow("Native build",
                "Build stamp absent (pre-EF-BENCH-02 record)",
                Origin.UNAVAILABLE))
        } else {
            val variant = native["variant"] ?: "?"
            val opt = native["native_opt"] ?: "?"
            rows.add(SpecRow("Native build", "$variant · $opt",
                Origin.MEASURED))
        }
        return rows
    }

    /**
     * Durable session whose attached benchmark carries
     * [runId], or null. Scans already-loaded records; no
     * I/O. Identity is the run id, never the bound id:
     * a stale binding must not claim this run.
     */
    fun attachedSession(
        records: List<SessionRecord>,
        runId: String
    ): SessionRecord? =
        records.firstOrNull {
            AeroSessions.runIdOf(it) == runId
        }

    /**
     * Binding chip for one result. Attached only on a
     * run-id match; anything else is standalone. A
     * NOT AUTHORIZED binding therefore never reads as
     * attached here — the run stayed standalone and the
     * chip says so.
     */
    fun bindingChip(
        resultRunId: String,
        session: SessionRecord?
    ): String = when {
        session == null -> "Standalone measurement"
        AeroSessions.runIdOf(session) == resultRunId ->
            "Attached: ${session.sessionId}"
        else -> "Standalone measurement — " +
            "${session.sessionId} carries a different run"
    }

    /**
     * One-line prediction-vs-measured for the hero, or
     * null when no prediction exists (standalone runs
     * show no delta row at all). Shared with the
     * sessions library so both surfaces word it alike.
     */
    fun deltaLine(session: SessionRecord?): String? =
        AeroSessions.deltaLine(session)

    /**
     * Details dialog sections: measured result plus
     * technical evidence (raw IDs), the session binding,
     * then the stored prediction (ESTIMATE, never
     * measurement) and the host-computed comparison.
     * Standalone results get an explicit no-prediction
     * section instead of silence.
     */
    @Suppress("UNCHECKED_CAST")
    fun detailSections(
        result: BenchResult,
        session: SessionRecord?
    ): List<InsightCards.Section> {
        val out = InsightCards.benchmarkSections(result)
            .toMutableList()
        out.add(InsightCards.Section(
            "Session binding",
            listOf(InsightCards.Row("Binding",
                bindingChip(result.runId, session)))))
        if (session == null) {
            out.add(InsightCards.Section(
                "Prediction vs measured",
                listOf(InsightCards.Row("Comparison",
                    "No session attached — standalone " +
                        "measurement, nothing to compare."))))
            return out
        }
        val prediction = session.prediction as? Map<String, Any?>
        if (prediction == null) {
            out.add(InsightCards.Section(
                "Prediction vs measured",
                listOf(InsightCards.Row("Comparison",
                    "No prediction recorded (standalone mode) — " +
                        "nothing to compare."))))
            return out
        }
        out.addAll(InsightCards.predictionSections(
            prediction, session.modelDisplay, session.config))
        val comparison = session.comparison as? Map<String, Any?>
        if (comparison == null) {
            out.add(InsightCards.Section(
                "Prediction vs measured",
                listOf(InsightCards.Row("Comparison",
                    "Not compared yet — the comparison records " +
                        "when the run attaches."))))
        } else {
            out.add(InsightCards.Section(
                "Prediction vs measured (host-computed)",
                InsightCards.comparisonRows(comparison)))
        }
        return out
    }
}
