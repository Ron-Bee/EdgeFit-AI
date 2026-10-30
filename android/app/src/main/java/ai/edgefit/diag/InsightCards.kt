package ai.edgefit.diag

/**
 * UX-01BC structured presentation builders. Every function is
 * pure: maps and data classes in, labeled sections out. The
 * activity renders sections through [CardDialog]. Comparison
 * math is never recomputed here — stored host values only.
 * Predictions are always labeled ESTIMATE, never measurement.
 */
object InsightCards {

    /**
     * UX-01BC-R2 semantic accents. MEASURED marks native
     * physical observations, PREDICTED marks Muse
     * estimates, ERROR marks failures and destructive
     * meaning. Renderers map these to color tokens; the
     * label text always carries the meaning too.
     */
    enum class Accent { NONE, MEASURED, PREDICTED, ERROR }

    data class Row(
        val label: String,
        val value: String,
        val accent: Accent = Accent.NONE
    )
    data class Section(
        val title: String,
        val rows: List<Row>,
        val details: String? = null
    )

    /**
     * Accent for a benchmark status or session state.
     * COMPLETED-family states are MEASURED regardless of
     * speed — a slower run is never an error. Prediction
     * states are PREDICTED; failures are ERROR.
     */
    fun statusAccent(token: String): Accent = when (token) {
        BenchContract.STATUS_COMPLETED,
        SessionState.BENCHMARK_COMPLETED,
        SessionState.COMPARISON_READY,
        SessionState.RECOMMENDATION_RECORDED,
        SessionState.SESSION_FINISHED -> Accent.MEASURED
        SessionState.PREDICTION_PENDING,
        SessionState.PREDICTION_RECORDED,
        SessionState.FOLLOWUP_PENDING -> Accent.PREDICTED
        BenchContract.STATUS_FAILED,
        BenchContract.STATUS_TIMEOUT,
        SessionState.BENCHMARK_FAILED,
        SessionState.BENCHMARK_TIMEOUT -> Accent.ERROR
        else -> Accent.NONE
    }

    // ---- formatting ----

    fun tps(value: Any?): String {
        val number = (value as? Number)?.toDouble()
        if (number == null || !number.isFinite()) return "?"
        return "%.1f tok/s".format(number)
    }

    fun tpsFull(value: Any?): String =
        (value as? Number)?.toDouble()?.toString() ?: "?"

    fun signed(value: Any?): String {
        val number = (value as? Number)?.toDouble()
        if (number == null || !number.isFinite()) return "?"
        val text = number.toString()
        return if (text.startsWith("-")) text else "+$text"
    }

    fun signedPct(value: Any?): String {
        val number = (value as? Number)?.toDouble()
        if (number == null || !number.isFinite()) return "?%"
        val text = number.toString()
        return (if (text.startsWith("-")) text else "+$text") + "%"
    }

    private fun str(map: Map<String, Any?>, key: String): String =
        map[key]?.toString() ?: "?"

    private fun list(map: Map<String, Any?>, key: String): String =
        (map[key] as? List<*>)?.joinToString("; ") ?: "none"

    // ---- prediction (ESTIMATE, never measurement) ----

    @Suppress("UNCHECKED_CAST")
    fun predictionSections(
        prediction: Map<String, Any?>,
        modelDisplay: String,
        config: Map<String, Any?>
    ): List<Section> {
        val raw = try {
            SessionJson.parse(
                prediction["raw_json"] as? String ?: "") as? Map<String, Any?>
        } catch (_: Exception) {
            null
        }
        val estimate = mutableListOf(
            Row("Generation (ESTIMATE)",
                tps(prediction["predicted_gen_tps"]),
                Accent.PREDICTED),
            Row("Prefill (ESTIMATE)",
                tps(prediction["predicted_prefill_tps"]),
                Accent.PREDICTED),
            Row("Confidence",
                raw?.get("confidence")?.toString() ?: "not stated"),
            Row("Model", modelDisplay),
            Row("Config",
                "ctx=${config["n_ctx"]} " +
                    "threads=${config["n_threads"]} " +
                    "tokens=${config["n_predict"]}")
        )
        val evidence =
            (prediction["evidence_ids"] as? List<*>)?.size ?: 0
        estimate.add(Row("Evidence cited", "$evidence run(s)"))
        val sections = mutableListOf(Section("Prediction", estimate))
        val rationale = raw?.get("rationale")?.toString()
        if (!rationale.isNullOrEmpty()) {
            sections.add(Section("Why this estimate",
                listOf(Row("Rationale", rationale))))
        }
        val details = buildString {
            append("source: ${str(prediction, "source_type")}\n")
            append("recorded: ${str(prediction, "created_at_utc")}\n")
            append("uncertainty: ${raw?.let { list(it, "uncertainty_notes") }
                ?: "not stated"}\n")
            append("limits: ${raw?.let { list(it, "limits") }
                ?: "not stated"}")
        }
        sections.add(Section("Technical evidence", emptyList(), details))
        return sections
    }

    // ---- benchmark result (measured) ----

    fun benchmarkSections(result: BenchResult): List<Section> {
        val measurements = result.measurements
        val measured = mutableListOf(
            Row("Status", result.status,
                statusAccent(result.status)),
            Row("Generation (MEASURED)", tps(measurements.tpsGen),
                Accent.MEASURED),
            Row("Prefill (MEASURED)", tps(measurements.tpsPrefill),
                Accent.MEASURED),
            Row("Tokens",
                "${measurements.generatedTokens} in " +
                    "${measurements.genMs} ms"),
            Row("Config",
                "ctx=${result.config.nCtx} " +
                    "threads=${result.config.nThreads} " +
                    "tokens=${result.config.nPredict}"),
            Row("Thermal",
                "${measurements.thermal.before} / " +
                    "${measurements.thermal.mid} / " +
                    "${measurements.thermal.after}")
        )
        if (result.error != null) {
            measured.add(0, Row("Failure cause",
                BenchUiText.safeError(result.error), Accent.ERROR))
        }
        val details =
            "run: ${result.runId}\n" +
                "model: ${result.model.displayName}\n" +
                "model sha: ${result.model.sha256}\n" +
                "runtime: ${result.runtimeVersion} " +
                "${result.runtimeAbi}\n" +
                "workload: ${result.workload.id}\n" +
                "collected: ${result.collectedAtUtc}"
        return listOf(
            Section("Measured result", measured),
            Section("Technical evidence", emptyList(), details)
        )
    }

    // ---- comparison (stored host values, never recomputed) ----

    fun comparisonRows(comparison: Map<String, Any?>): List<Row> {
        if (comparison["comparable"] != true) {
            return listOf(Row("Comparison",
                "noncomparable (${str(comparison, "reason")})"))
        }
        return listOf(
            Row("Generation error",
                "${signed(comparison["gen_signed"])} tok/s " +
                    "(${signedPct(comparison["gen_pct"])})"),
            Row("Prefill error",
                "${signed(comparison["prefill_signed"])} tok/s " +
                    "(${signedPct(comparison["prefill_pct"])})")
        )
    }

    // ---- follow-up (interpretation, not measurement) ----

    @Suppress("UNCHECKED_CAST")
    fun followUpSections(followup: Map<String, Any?>): List<Section> {
        val recommendation =
            followup["recommendation"] as? Map<String, Any?> ?: emptyMap()
        val direction = recommendation["direction"]?.toString() ?: "?"
        val assessment = mutableListOf(
            Row("Direction", direction),
            Row("Assessment",
                recommendation["summary"]?.toString() ?: "?"),
            Row("Supports a conclusion?",
                recommendation["supports_conclusion"]?.toString() ?: "?")
        )
        (recommendation["limitations"] as? List<*>)?.let {
            assessment.add(Row("Limitations", it.joinToString("; ")))
        }
        // EF-AGENT-REVIEW-01: model self-critique, labeled as
        // the model's own words — never host verification.
        (recommendation["counterargument"] as? String)
            ?.ifBlank { null }?.let {
                assessment.add(Row(
                    "Model self-critique (not verification)", it))
            }
        val sections = mutableListOf(Section("Follow-up", assessment))
        val experiment =
            recommendation["next_experiment"] as? Map<String, Any?>
        if (experiment != null) {
            sections.add(Section("Suggested next experiment (PROPOSAL)",
                listOf(
                    Row("What", experiment["description"]?.toString() ?: "?"),
                    Row("Why", experiment["rationale"]?.toString() ?: "?"),
                    Row("Host-valid",
                        recommendation["proposal_valid"]?.toString() ?: "?")
                )))
        }
        sections.add(Section("Technical evidence", emptyList(),
            "provenance: ${str(followup, "provenance")}\n" +
                "run: ${recommendation["run_id"] ?: "?"}"))
        return sections
    }

    // ---- agent proposal review ----

    @Suppress("UNCHECKED_CAST")
    /**
     * @param review independent host verdict; when supplied
     * it leads the card as its own section.
     */
    fun proposalSections(
        pending: Map<String, Any?>,
        currentConfig: Map<String, Any?>,
        budgetUsed: Int,
        budgetMax: Int,
        review: AgentReview.ReviewResult? = null
    ): List<Section> {
        val proposed =
            pending["proposed_config"] as? Map<String, Any?> ?: emptyMap()
        val changed =
            (pending["changed_keys"] as? List<*>)?.map { it.toString() }
                ?: emptyList()
        val diff = mutableListOf<Row>()
        // EF-FOLLOWUP-02: repeat proposals re-measure one
        // cited run with the identical config.
        if ((pending["purpose"] as? String) == AgentApi.PURPOSE_REPEAT) {
            diff.add(Row("Purpose",
                "REPEAT of ${pending["repeat_of_run_id"]} " +
                    "(same config; variability check)"))
        }
        for (key in listOf("n_ctx", "n_threads", "n_predict",
            "temperature", "sampler", "seed")) {
            val before = currentConfig[key]?.toString() ?: "?"
            val after = proposed[key]?.toString() ?: "?"
            val mark = if (key in changed) "  ★ changed" else ""
            diff.add(Row(key, "$before → $after$mark"))
        }
        val expectation = mutableListOf(
            Row("Expected generation (ESTIMATE)",
                tps(pending["expected_gen_tps"]), Accent.PREDICTED),
            Row("Direction",
                pending["expected_direction"]?.toString() ?: "?"),
            Row("Uncertainty",
                pending["uncertainty"]?.toString() ?: "not stated"),
            Row("Budget after approval",
                "${budgetUsed + 1} of $budgetMax attempts")
        )
        val rationale = mutableListOf(
            Row("Hypothesis", pending["hypothesis"]?.toString() ?: "?"),
            Row("Why", pending["benefit"]?.toString() ?: "?"),
            Row("Evidence",
                list(pending, "supporting_evidence_ids")),
            Row("Limitations", list(pending, "limitations"))
        )
        val sections = mutableListOf<Section>()
        // EF-AGENT-REVIEW-01: the independent host verdict
        // leads; the model's own critique rides labeled in
        // Rationale, never as verification.
        if (review != null) {
            val accent = when (review.verdict) {
                AgentReview.Verdict.SUPPORTED -> Accent.MEASURED
                AgentReview.Verdict.CAUTION -> Accent.PREDICTED
                AgentReview.Verdict.INVALID -> Accent.ERROR
            }
            val rows = mutableListOf(
                Row("Independent host review",
                    review.verdict.toString(), accent)
            )
            review.objections.forEachIndexed { index, objection ->
                rows.add(Row("Finding ${index + 1}", objection))
            }
            if (review.objections.isEmpty()) {
                rows.add(Row("Findings",
                    "all deterministic checks pass with backing evidence"))
            }
            sections.add(Section("Independent review", rows))
        }
        rationale.add(Row("Model self-critique (not verification)",
            (pending["counterargument"] as? String)
                ?.ifBlank { null } ?: "not stated"))
        rationale.add(Row("Alternative reading",
            (pending["alternative_explanation"] as? String)
                ?.ifBlank { null } ?: "not stated"))
        rationale.add(Row("One run would establish",
            (pending["next_benchmark_establishes"] as? String)
                ?.ifBlank { null } ?: "not stated"))
        sections.addAll(listOf(
            Section("Configuration change", diff),
            Section("Expected outcome", expectation),
            Section("Rationale", rationale),
            Section("Technical evidence", emptyList(),
                "proposal: ${(pending["proposal_hash"] as? String)
                    ?.take(12)}...\n" +
                    "pack: ${(pending["evidence_pack_hash"] as? String)
                        ?.take(12)}...\n" +
                    "contract: v${pending["contract_version"] ?: 1}\n" +
                    "host notes: ${list(pending, "host_notes")}")
        ))
        return sections
    }

    // ---- EF-FOLLOWUP-02 repeat series (host values only) ----

    /**
     * Renders a host-computed [SessionComparison.SeriesResult].
     * No math here: every number arrives precomputed. The
     * selected run reads as the Reference; other rows show
     * selected-minus-compared differences; range rows are
     * descriptive, never a significance claim.
     */
    /** Short run tag for summary rows; full ids stay in
     * Technical evidence. */
    fun runShort(runId: String): String =
        if (runId.length > 12) runId.takeLast(8) else runId

    fun seriesSections(
        result: SessionComparison.SeriesResult
    ): List<Section> {
        val current =
            result.rows.firstOrNull { it.isCurrent }
        val others = result.rows.filter { !it.isCurrent }
        val selected = mutableListOf<Row>()
        if (current == null) {
            selected.add(Row("Selected run",
                "not in this series (${result.currentRunId})"))
        } else {
            selected.add(Row("Run ${runShort(current.runId)}",
                "${tps(current.genTps)} (Reference)",
                Accent.MEASURED))
        }
        val compared = mutableListOf(
            Row("Difference means",
                "selected run minus compared run; a positive " +
                    "difference means the selected run measured faster.")
        )
        if (others.isEmpty()) {
            compared.add(Row("Other runs",
                "none yet; repeat this configuration to assess " +
                    "variability"))
        }
        for (row in others) {
            compared.add(Row("Run ${runShort(row.runId)}",
                "${tps(row.genTps)} " +
                    "(Δ ${signed(row.deltaGen)}, " +
                    "${signedPct(row.deltaPct)} vs selected)",
                Accent.MEASURED))
        }
        val summary = mutableListOf(
            Row("Completed comparable measurements",
                result.rows.size.toString()),
            Row("Observed range",
                "${tps(result.minGen)} – ${tps(result.maxGen)}")
        )
        if (result.rows.size < 2) {
            summary.add(Row("Variability",
                "single run; repeat to assess variability"))
        }
        val details = result.rows.joinToString("\n") { row ->
            "${row.runId} @ " +
                "${SessionTimeline.formatLocal(row.collectedAtUtc)} " +
                "gen=${tpsFull(row.genTps)} " +
                "prefill=${tpsFull(row.prefillTps)} " +
                "thermal=${row.thermalAfter}"
        }
        return listOf(
            Section("Current selected run (MEASURED)", selected),
            Section("Other comparable runs", compared),
            Section("Series summary", summary),
            Section("Technical evidence", emptyList(),
                "selected: ${result.currentRunId}\n$details")
        )
    }

    // ---- EF-UX-02 repeat preparation (host values only) ----

    /**
     * Repeat-prep screen for a newly created linked session:
     * source relationship, required model/config, staging
     * status, and the next action. [stagedName] is null when
     * no model is staged; [stagedMatches] is host-verified
     * SHA equality against the source artifact.
     */
    @Suppress("UNCHECKED_CAST")
    fun repeatPrepSections(
        source: SessionRecord,
        created: SessionRecord,
        stagedName: String?,
        stagedMatches: Boolean
    ): List<Section> {
        val sourceBench = source.benchmark as? Map<String, Any?>
        val sourceRun = sourceBench?.get("run_id") as? String ?: "?"
        val prediction =
            created.prediction as? Map<String, Any?> ?: emptyMap()
        val recorded = prediction["created_at_utc"] as? String
        val recordedText = if (recorded == null) "date not recorded"
        else SessionTimeline.formatLocal(recorded)
        val identity = mutableListOf(
            Row("New session",
                "${SessionListUi.rowLabel(created)} · " +
                    SessionListUi.friendlyState(created)),
            Row("Repeats",
                "${SessionListUi.rowLabel(source)} · run " +
                    runShort(sourceRun)),
            Row("Prediction (ESTIMATE)",
                "${tps(prediction["predicted_gen_tps"])} gen, " +
                    "${tps(prediction["predicted_prefill_tps"])} " +
                    "prefill — carried forward, recorded " +
                    recordedText)
        )
        val required = mutableListOf(
            Row("Required model", source.modelDisplay),
            Row("Artifact size",
                "${source.modelSizeBytes / 1048576} MB"),
            Row("Configuration",
                SessionListUi.configShort(created.config)),
            Row("Workload", source.workloadId)
        )
        val staging = mutableListOf<Row>()
        val next: String
        if (stagedMatches && stagedName != null) {
            staging.add(Row("Status",
                "Required model staged and verified: $stagedName",
                Accent.MEASURED))
            next = "Run Repeat Benchmark below to execute this " +
                "repeat once. The source record stays unchanged."
        } else if (stagedName == null) {
            staging.add(Row("Status",
                "No model staged. Select the required model " +
                    "below, then run."))
            next = "Select the required model, then run the " +
                "repeat benchmark."
        } else {
            staging.add(Row("Status",
                "Staged model differs ($stagedName). Select " +
                    "${source.modelDisplay} before running.",
                Accent.ERROR))
            next = "Select the required model first; the repeat " +
                "cannot run against a different artifact."
        }
        return listOf(
            Section("Repeat Experiment — Ready to Run", identity),
            Section("Required model & configuration", required),
            Section("Model staging", staging),
            Section("Next step", listOf(Row("Action", next))),
            Section("Technical evidence", emptyList(),
                "new: ${created.sessionId}\n" +
                    "source: ${source.sessionId}\n" +
                    "source run: $sourceRun\n" +
                    "model sha: ${source.modelSha256}")
        )
    }

    // ---- EF-MEM-01 memory refusal + recheck (host values only) ----

    /**
     * Fresh guard-evaluation dialog content. Evaluates only;
     * the caller must never start inference from this path.
     * [modelDisplay] null means no model is staged.
     */
    fun memoryRecheckSections(
        modelDisplay: String?,
        availBytes: Long,
        requiredBytes: Long,
        lowMemory: Boolean,
        errors: List<String>
    ): List<Section> {
        val mb = 1024L * 1024L
        if (modelDisplay == null) {
            return listOf(Section("Memory Check", listOf(
                Row("Verdict", "No model staged — stage a " +
                    "model before checking memory."),
                Row("Available now",
                    "${availBytes / mb} MB"))))
        }
        val verdict = if (errors.isEmpty()) {
            Row("Verdict", "Pass — a run started now would " +
                "clear the preflight guards.")
        } else {
            Row("Verdict", "Would refuse: " +
                errors.joinToString("; "), Accent.ERROR)
        }
        // Integer MB difference; each subtraction runs
        // non-negative, so no overflow is possible.
        val diffMb = if (availBytes >= requiredBytes) {
            "+${(availBytes - requiredBytes) / mb}"
        } else {
            "-${(requiredBytes - availBytes) / mb}"
        }
        return listOf(
            Section("Memory Check", listOf(
                verdict,
                Row("Model", modelDisplay),
                Row("Available now", "${availBytes / mb} MB"),
                Row("Required", "${requiredBytes / mb} MB"),
                Row("Difference", "$diffMb MB"),
                Row("Android low-memory state",
                    if (lowMemory) "yes" else "no"))),
            Section("Note", listOf(Row("No run started",
                "This check only re-reads current memory. " +
                    "Starting a benchmark still needs its " +
                    "explicit run action and a new run identity.")))
        )
    }

    /**
     * Refusal detail dialog: the recorded reason plus any
     * admission-recheck evidence. Nothing is recomputed.
     */
    fun refusalDetailSections(
        error: String?,
        recheck: MemRecheckEvidence?
    ): List<Section> {
        val mb = 1024L * 1024L
        val reason = mutableListOf(
            Row("Reason", error ?: "unknown"))
        if (recheck == null) {
            reason.add(Row("Recheck",
                "not attempted (the miss was outside the " +
                    "near-threshold window, or another guard " +
                    "also failed)"))
            return listOf(Section("Refusal", reason))
        }
        val samples = if (recheck.samples.isEmpty()) {
            "none recorded (cancelled before sampling)"
        } else {
            recheck.samples.joinToString(", ") {
                "${it.availBytes / mb} MB" +
                    if (it.lowMemory) " (low)" else ""
            }
        }
        reason.add(Row("Recheck",
            if (recheck.admitted) "admitted on a fresh sample"
            else "attempted, still below requirement"))
        return listOf(
            Section("Refusal", reason),
            Section("Recheck evidence", listOf(
                Row("Required", "${recheck.requiredBytes / mb} MB"),
                Row("Initial reading",
                    "${recheck.initialAvailBytes / mb} MB" +
                        if (recheck.initialLowMemory) " (low-memory)"
                        else ""),
                Row("Fresh samples", samples),
                Row("Recheck time",
                    "${recheck.recheckMs} ms (outside the " +
                        "measurement window)"))))
    }

    /**
     * EF-MEM-03 failure details for any uncompleted run:
     * status, cause, stage, native entry, and memory
     * conditions. States what is known; never assigns a
     * definitive cause the evidence does not support.
     */
    fun failureDetailSections(result: BenchResult): List<Section> {
        val reason = mutableListOf(
            Row("Status", result.status, Accent.ERROR),
            Row("Reason", result.error ?: "unknown"))
        val failure = result.xFailure
        if (failure != null) {
            reason.add(Row("Native started",
                if (failure.nativeStarted) "yes" else "no"))
            reason.add(Row("Stopped at", failure.stage))
        }
        val mem = result.xMemConditions
        val memRows = if (mem == null) {
            listOf(Row("Memory at start", "Unknown"))
        } else {
            listOf(
                Row("Available at start",
                    "${MemPolicy.formatPct(mem.availPctStart)} " +
                        "(${mem.availBytesStart?.let {
                            MemDiag.formatMb(it)
                        } ?: "unavailable"})"),
                Row("Total RAM",
                    mem.totalRamBytes?.let {
                        MemDiag.formatMb(it)
                    } ?: "unavailable"),
                Row("Advisory threshold",
                    "${mem.warningThresholdPct}% (warn-only)"),
                Row("Below threshold",
                    when (mem.belowThresholdStart) {
                        true -> "yes"
                        false -> "no"
                        null -> "unknown"
                    }),
                Row("Android low-memory",
                    when (mem.lowMemoryStart) {
                        true -> "yes"
                        false -> "no"
                        null -> "unknown"
                    }),
                Row("Available at end",
                    if (mem.availPctEnd != null) {
                        MemPolicy.formatPct(mem.availPctEnd)
                    } else {
                        "not re-read (run never reached teardown)"
                    }))
        }
        return listOf(
            Section("Failure", reason,
                "Only the recorded reason and stage are " +
                    "established. Low memory at start is a " +
                    "possible factor, never a proven cause " +
                    "unless the runtime reported an " +
                    "allocation failure."),
            Section("Memory conditions", memRows))
    }

    // ---- EF-MODEL-01A catalog entries (host values only) ----

    /**
     * Accent for an FR-MODEL-01 compatibility status.
     * Verified reads as fact, likely as estimate,
     * unsupported as a safety warning; unknown evidence
     * stays neutral. Text always carries the meaning.
     */
    fun suitabilityAccent(status: String): Accent = when (status) {
        ModelSuitability.VERIFIED_COMPATIBLE -> Accent.MEASURED
        ModelSuitability.LIKELY_COMPATIBLE -> Accent.PREDICTED
        ModelSuitability.UNSUPPORTED -> Accent.ERROR
        else -> Accent.NONE
    }

    fun suitabilityLabel(status: String): String = when (status) {
        ModelSuitability.VERIFIED_COMPATIBLE ->
            "Compatible (measured on this device)"
        ModelSuitability.LIKELY_COMPATIBLE ->
            "Likely compatible (unverified)"
        ModelSuitability.UNSUPPORTED -> "Unsupported here"
        ModelSuitability.INSUFFICIENT_EVIDENCE ->
            "Unknown (insufficient evidence)"
        else -> status
    }

    /**
     * One-line card summary for a catalog entry. No math,
     * no promises: status, size, license, evidence class,
     * plus the host recommendation headline and a
     * currently-limited banner when it applies.
     */
    fun modelCardLine(
        entry: ModelCatalog.Entry,
        assessment: ModelSuitability.Assessment,
        recommendation: ModelRecommend.Recommendation? = null
    ): String {
        val lines = mutableListOf(
            "${entry.displayName} — ${entry.sizeDisplay}",
            suitabilityLabel(assessment.status),
            "License: ${entry.license} — ${entry.ggufPublisher}",
            ModelSuitability.evidenceLabel(
                ModelSuitability.evidenceClass(
                    assessment.measuredLocally)))
        if (recommendation != null) {
            lines.add("Recommendation: ${recommendation.headline}")
        }
        if (assessment.memoryLimitedNow) {
            lines.add("Currently limited by available memory.")
        }
        return lines.joinToString("\n")
    }

    /**
     * Current-model overview: staged artifact and latest
     * valid measurement, each honestly absent when
     * missing. Throughput shown is this artifact's own —
     * never compared across artifacts here.
     */
    fun currentModelSections(
        current: ModelContext.CurrentModel
    ): List<Section> {
        val staged = current.staged
        val stagedRows = if (staged == null) {
            listOf(Row("Staged model", "none staged"))
        } else {
            listOf(
                Row("Staged model", staged.displayName),
                Row("Artifact size",
                    "${staged.sizeBytes / (1024 * 1024)} MB (exact)"),
                Row("Artifact SHA-256",
                    staged.sha256.take(12) + "…"))
        }
        val measured = current.lastMeasurement
        val measureRows = if (measured == null) {
            listOf(Row("Last measurement", "none completed"))
        } else {
            listOfNotNull(
                Row("Last measured", measured.modelDisplay),
                Row("Generation (MEASURED)", tps(measured.genTps),
                    Accent.MEASURED),
                measured.prefillTps?.let {
                    Row("Prefill (MEASURED)", tps(it),
                        Accent.MEASURED)
                },
                Row("Config", measured.configLabel),
                Row("Collected",
                    SessionTimeline.formatLocal(measured.collectedAtUtc)),
                Row("Run", measured.runId))
        }
        return listOf(
            Section("Current model", stagedRows),
            Section("Last valid measurement", measureRows))
    }

    /**
     * Full detail sections. Every number arrives
     * precomputed; the rationale is curator-written copy
     * that the parser already screened for speed claims.
     * Size differences are labeled approximate unless
     * both sides are exact bytes.
     */
    fun modelEntrySections(
        entry: ModelCatalog.Entry,
        assessment: ModelSuitability.Assessment,
        recommendation: ModelRecommend.Recommendation? = null,
        evidence: ModelRecommend.MatchedEvidence? = null,
        sizeDiff: ModelContext.SizeDiff? = null,
        baselineName: String? = null
    ): List<Section> {
        val candidate = listOf(
            Row("Model", entry.displayName),
            Row("Quantization", entry.quantization),
            Row("Architecture", entry.arch),
            Row("Artifact size", entry.sizeDisplay +
                if (assessment.sizeBasis == "exact_bytes") {
                    " (exact)"
                } else {
                    " (listed; upper bound)"
                }),
            Row("License", "${entry.license} — ${entry.licenseNote}"),
            Row("Source", "${entry.ggufPublisher}: ${entry.repoUrl}")
        )
        val sections = mutableListOf(Section("Candidate", candidate))
        if (recommendation != null) {
            sections.add(Section("Recommendation", listOf(
                Row("Verdict", recommendation.headline),
                Row("Why", recommendation.body))))
        }
        sections.add(Section("Comparison vs current",
            comparisonRows(entry, assessment, sizeDiff, baselineName)))
        val compat = mutableListOf(
            Row("Status", suitabilityLabel(assessment.status),
                suitabilityAccent(assessment.status)))
        for ((index, reason) in assessment.reasons.withIndex()) {
            compat.add(Row("Basis ${index + 1}", reason))
        }
        sections.add(Section("Compatibility", compat))
        sections.add(Section("Memory considerations",
            memoryRows(entry, assessment)))
        sections.add(Section("Local evidence",
            localEvidenceRows(evidence)))
        val evidenceSection = listOf(
            Row("Performance evidence",
                ModelSuitability.evidenceLabel(
                    ModelSuitability.evidenceClass(
                        assessment.measuredLocally))),
            Row("Why consider", entry.rationale),
            Row("Limitations", entry.limitations),
            Row("Verified", "${entry.verifiedUtc} (source pages read)"),
            Row("Runtime check", entry.runtimeMethod)
        )
        sections.add(Section("Evidence & limits", evidenceSection))
        sections.add(Section("Technical evidence", emptyList(),
            "id: ${entry.id}\n" +
                "base: ${entry.baseModel}\n" +
                "artifact: ${entry.artifactPath}\n" +
                "arch: ${entry.arch} " +
                "ctx: ${entry.contextLength ?: "unknown"}\n" +
                "sha256: ${entry.sha256 ?: "unverified"}\n" +
                "source: ${entry.sourceUrl}"))
        return sections
    }

    private fun comparisonRows(
        entry: ModelCatalog.Entry,
        assessment: ModelSuitability.Assessment,
        sizeDiff: ModelContext.SizeDiff?,
        baselineName: String?
    ): List<Row> {
        if (sizeDiff == null) {
            return listOf(Row("Size comparison",
                if (baselineName == null) {
                    "No staged or measured model to compare " +
                        "against; ${entry.sizeDisplay} stands alone."
                } else {
                    "Size comparison unavailable for " +
                        "$baselineName (unknown bytes)."
                }))
        }
        val mb = 1024L * 1024L
        val approx = if (sizeDiff.approximate) "about " else ""
        val direction = when {
            sizeDiff.absBytes < 0 -> "smaller"
            sizeDiff.absBytes > 0 -> "larger"
            else -> "the same size"
        }
        return listOf(
            Row("Candidate", "${entry.sizeDisplay}" +
                if (assessment.sizeBasis == "exact_bytes") {
                    " (exact)"
                } else {
                    " (listed upper bound)"
                }),
            Row("Current (${baselineName ?: "model"})",
                "${sizeDiff.currentBytes / mb} MB (exact)"),
            Row("Difference",
                if (sizeDiff.absBytes == 0L) {
                    "same size class"
                } else {
                    "$approx${signedPct(sizeDiff.pctVsCurrent)} " +
                        "$direction " +
                        "(${kotlin.math.abs(sizeDiff.absBytes) / mb} MB)"
                }),
            Row("Throughput",
                "Not compared: different artifacts are never " +
                    "ranked by speed without controlled runs."))
    }

    private fun memoryRows(
        entry: ModelCatalog.Entry,
        assessment: ModelSuitability.Assessment
    ): List<Row> {
        val mb = 1024L * 1024L
        val rows = mutableListOf(
            Row("Artifact bound",
                assessment.sizeBoundBytes?.let {
                    "${it / mb} MB (${assessment.sizeBasis})"
                } ?: "unknown"),
            Row("Required free",
                assessment.requiredAvailBytes?.let {
                    "${it / mb} MB (artifact + 1 GiB headroom)"
                } ?: "unknown"))
        val avail = assessment.availBytes
        rows.add(Row("Free now",
            if (avail == null) {
                "unknown (sample unavailable)"
            } else {
                "${avail / mb} MB " +
                    "at ${assessment.availSampledAt ?: "unknown time"}"
            }))
        rows.add(Row("Verdict",
            when {
                assessment.requiredAvailBytes == null ||
                    avail == null ->
                    "Unknown — free memory or size unverified."
                assessment.memoryLimitedNow ->
                    "Currently limited by available memory. " +
                        "Free memory or retry later; the guard " +
                        "will refuse an unsafe run."
                else ->
                    "Fits current memory. Fit is necessary, " +
                        "not sufficient: execution is still " +
                        "untested for ${entry.displayName}."
            }))
        return rows
    }

    private fun localEvidenceRows(
        evidence: ModelRecommend.MatchedEvidence?
    ): List<Row> {
        if (evidence == null) {
            return listOf(Row("Exact-artifact runs",
                "No exact-artifact local measurement. Name, " +
                    "filename, and size matches never qualify."))
        }
        return listOfNotNull(
            Row("Run", evidence.runId),
            Row("Generation (MEASURED)", tps(evidence.genTps),
                Accent.MEASURED),
            evidence.prefillTps?.let {
                Row("Prefill (MEASURED)", tps(it), Accent.MEASURED)
            },
            Row("Config", evidence.configLabel),
            Row("Collected", SessionTimeline.formatLocal(
                evidence.collectedAtUtc)),
            Row("Session", evidence.sessionId))
    }

    // ---- session detail ----

    @Suppress("UNCHECKED_CAST")
    fun sessionSections(record: SessionRecord): List<Section> {
        val overview = mutableListOf(
            Row("ID", record.sessionId),
            Row("Type", sessionTypeLabel(record)),
            Row("State", record.state,
                statusAccent(record.state)),
            Row("Model", record.modelDisplay),
            Row("Config", SessionListUi.configShort(record.config)),
            Row("Workload", record.workloadId),
            Row("Created",
                SessionTimeline.formatLocal(record.createdAtUtc)),
            Row("Last update",
                SessionTimeline.formatLocal(record.updatedAtUtc))
        )
        // EF-MODEL-01A Phase 8: one-run-per-session is an
        // intentional evidence boundary. Repeats arrive as
        // NEW linked sessions; sources stay immutable.
        if (record.repeatOfRunId != null) {
            overview.add(Row("Continue experimenting",
                "New linked session repeating " +
                    "${record.repeatOfRunId}; earlier results " +
                    "and recommendations are preserved."))
        }
        if (record.recoveryOfRunId != null) {
            overview.add(Row("Recovery attempt",
                "New linked session after uncompleted run " +
                    "${record.recoveryOfRunId}; the failed " +
                    "attempt is preserved unchanged."))
        }
        val sections = mutableListOf(Section("Session", overview))
        (record.prediction as? Map<String, Any?>)?.let {
            sections.add(Section("Prediction (ESTIMATE)", listOf(
                Row("Generation", tps(it["predicted_gen_tps"]),
                    Accent.PREDICTED),
                Row("Prefill", tps(it["predicted_prefill_tps"]),
                    Accent.PREDICTED),
                Row("Source", str(it, "source_type"))
            )))
        }
        (record.benchmark as? Map<String, Any?>)?.let {
            val rows = mutableListOf(
                Row("Run", str(it, "run_id")),
                Row("Status", str(it, "status"),
                    statusAccent(str(it, "status"))))
            // EF-MEM-01 durable refusal reason; absent on
            // older records and successful runs.
            (it["failure_cause"] as? String)?.let { cause ->
                rows.add(Row("Cause", cause))
            }
            // EF-MEM-03 memory conditions; absent on
            // pre-MEM-03 records (Unknown, never 0%).
            @Suppress("UNCHECKED_CAST")
            val measurements =
                it["measurements"] as? Map<String, Any?>
            val memPct = measurements
                ?.get("mem_avail_pct_start") as? Number
            rows.add(Row("Memory at start",
                if (memPct != null) {
                    MemPolicy.formatPct(memPct.toDouble()) +
                        " available" +
                        if (measurements["mem_below_advisory"] == true) {
                            " (below ${MemPolicy.WARNING_PCT}% advisory)"
                        } else {
                            ""
                        }
                } else {
                    "Unknown"
                }))
            sections.add(Section("Benchmark", rows))
        }
        (record.comparison as? Map<String, Any?>)?.let {
            sections.add(Section("Host comparison", comparisonRows(it)))
        }
        (record.followup as? Map<String, Any?>)?.let {
            val recommendation = it["recommendation"] as? Map<String, Any?>
            sections.add(Section("Meta follow-up", listOf(
                Row("Status", str(it, "status")),
                Row("Recorded",
                    SessionTimeline.formatLocal(
                        it["at"]?.toString())),
                Row("Summary",
                    recommendation?.get("summary")?.toString() ?: "?"),
                Row("Provenance", str(it, "provenance"))
            )))
        }
        val timeline = SessionTimeline.events(record)
        if (timeline.isNotEmpty()) {
            sections.add(Section("Timeline", timeline.map {
                Row(it.label, it.local +
                    (it.cause?.let { cause -> " — $cause" } ?: ""))
            }))
        }
        sections.add(Section("Technical evidence", emptyList(),
            SessionTimeline.evidenceUtc(record)))
        return sections
    }

    fun sessionTypeLabel(record: SessionRecord): String = when {
        record.mode == "agent" -> "AGENT parent"
        record.parentSessionId != null ->
            "AGENT experiment ${record.experimentSeq ?: "?"}"
        record.mode == "byok" -> "BYOK session"
        record.mode == "offline_test" -> "Offline test"
        record.mode == SessionStore.MODE_STANDALONE ->
            "Standalone measurement"
        else -> record.mode
    }

    /**
     * One-line pending action for list rows. Pure derivation
     * from state + attachments; mirrors the dialog's real
     * button gating (canPredict / follow-up eligibility).
     */
    @Suppress("UNCHECKED_CAST")
    fun pendingAction(record: SessionRecord): String {
        if (record.mode == "agent") return "Open in Agent tab"
        if (record.benchmark != null) {
            val status = (record.benchmark as? Map<String, Any?>)
                ?.get("status")?.toString()
            if (status != BenchContract.STATUS_COMPLETED) {
                return "Run failed — see cause"
            }
            if (record.followup == null) {
                return if (record.mode == "byok") {
                    "Ready: request follow-up"
                } else if (SessionView.canRepeatStandalone(record)) {
                    "Ready: repeat or compare"
                } else {
                    "Complete"
                }
            }
            return "Complete"
        }
        if (record.prediction == null) {
            return if (SessionView.canPredict(record)) "Needs: prediction"
            else "Waiting"
        }
        if (record.state == SessionState.BENCHMARK_AUTHORIZED) {
            return "Ready: run benchmark"
        }
        return when (record.state) {
            SessionState.BENCHMARK_RUNNING -> "Benchmark running…"
            SessionState.BENCHMARK_FAILED -> "Run failed — see cause"
            else -> "Waiting"
        }
    }

    // ---- home dashboard ----

    /** Compact readiness verdict from live collector inputs. */
    fun readinessVerdict(
        availMb: Long,
        totalMb: Long,
        thermal: String?,
        modelStaged: Boolean
    ): Row {
        if (thermal == null) {
            return Row("Readiness", "Not ready — thermal unknown")
        }
        if (thermal in BenchGuards.UNSAFE_THERMAL) {
            return Row("Readiness", "Not ready — thermal $thermal")
        }
        if (!modelStaged) {
            return Row("Readiness",
                "Ready when a model is staged ($availMb of " +
                    "${totalMb} MB free)")
        }
        return Row("Readiness", "Ready — $availMb of ${totalMb} MB free")
    }

    /**
     * Hypothesis outcome for a finished experiment. A COMPLETED
     * run that underperforms is a missed hypothesis — never a
     * failed benchmark.
     */
    fun outcomeSuffix(
        status: String,
        measuredGenTps: Double?,
        expectedGenTps: Double?
    ): String {
        if (status != BenchContract.STATUS_COMPLETED) return ""
        if (measuredGenTps == null || expectedGenTps == null) return ""
        return if (measuredGenTps < expectedGenTps) {
            "; hypothesis missed (expected $expectedGenTps, " +
                "measured $measuredGenTps)"
        } else {
            "; hypothesis supported"
        }
    }
}
