package ai.edgefit.diag

/**
 * EF-AGENT-REVIEW-01: independent host review of Agent
 * proposals. A model's self-critique is displayed but NEVER
 * counts as verification; every check below recomputes from
 * host-owned session, pack, and device state. Pure JVM: no
 * I/O, no network, no second Meta call.
 *
 * Verdicts: SUPPORTED (all checks pass with backing
 * evidence), CAUTION (structurally valid but thin,
 * uncertain, or contradictory evidence — the developer may
 * still approve explicitly), INVALID (non-negotiable
 * contract, identity, authorization, or safety violation —
 * approval is blocked until revised or rejected).
 */
object AgentReview {

    enum class Verdict { SUPPORTED, CAUTION, INVALID }
    enum class Severity { PASS, CAUTION, FAIL }

    data class Check(
        val name: String,
        val severity: Severity,
        val detail: String
    )

    data class ReviewResult(
        val verdict: Verdict,
        val checks: List<Check>
    ) {
        /** Human-readable objections (non-pass checks), in order. */
        val objections: List<String>
            get() = checks.filter { it.severity != Severity.PASS }
                .map { it.detail }
    }

    /**
     * Host-assembled review context. The activity builds it
     * from durable session state; the model supplies nothing
     * here except the already-validated pending map.
     */
    data class ReviewInput(
        val pending: Map<String, Any?>,
        val parent: SessionRecord,
        val pack: AgentEvidence.EvidencePack,
        /** Failed-run ids of THIS parent (recovery allowlist). */
        val failureRunIds: Set<String>,
        /** Stored executed config per run id. */
        val executedConfigs: Map<String, BenchConfigData>,
        /** Latest experiment config, else the parent base. */
        val current: BenchConfigData,
        /** min(16, device logical processors). */
        val deviceMaxThreads: Int,
        /** Staged model SHA, or null when nothing staged. */
        val stagedModelSha: String?,
        /** Start-of-run available pct per run; absent = unknown. */
        val memStartPct: Map<String, Double>,
        /** Run ids measured below the advisory threshold. */
        val memBelowAdvisory: Set<String>
    )

    /** Comparable runs disagree when max/min reach this ratio. */
    const val CONTRADICTION_RATIO = 2.0
    /** Expected values beyond best/[1..3]x draw a caution. */
    const val EXPECTED_PLAUSIBILITY_FACTOR = 3.0

    @Suppress("UNCHECKED_CAST")
    fun reviewProposal(input: ReviewInput): ReviewResult {
        val checks = mutableListOf<Check>()
        val pending = input.pending
        val purpose = pending["purpose"] as? String
            ?: AgentApi.PURPOSE_OPTIMIZE
        val proposed = (pending["proposed_config"] as? Map<String, Any?>)
            ?.let { SessionBenchBridge.configData(it) }
        val cited = (pending["supporting_evidence_ids"] as? List<*>)
            ?.mapNotNull { it as? String } ?: emptyList()
        val packIds = (input.pack.comparable + input.pack.contextual)
            .map { it.runId }.toSet()

        // 1. Staged-model identity (resolvable by staging:
        // caution, never invalid; approval re-verifies hard).
        checks.add(checkStagedModel(input))
        // 2. Proposed config parses and sits in bounds.
        checks.add(checkConfig(proposed, input))
        // 2b. Changed keys match the proposed-vs-current diff.
        if (proposed != null) {
            checks.add(checkChangedKeys(pending, proposed, input.current))
        }
        // 3. Purpose-specific rules (repeat/recovery/optimize).
        checks.add(checkPurpose(purpose, pending, proposed, input, packIds))
        // 4. Every citation is a known run of this session.
        val known = packIds + input.failureRunIds
        val unknown = cited.filter { it !in known }
        checks.add(if (unknown.isEmpty()) {
            Check("citations", Severity.PASS,
                "${cited.size} citation(s) all resolve to this session")
        } else {
            Check("citations", Severity.FAIL,
                "unknown citation(s): ${unknown.joinToString(",")}")
        })
        // 5. Budget still has room.
        checks.add(if (input.parent.budgetUsed < input.parent.budgetMax) {
            Check("budget", Severity.PASS,
                "attempt ${input.parent.budgetUsed + 1} " +
                    "of ${input.parent.budgetMax}")
        } else {
            Check("budget", Severity.FAIL, "experiment budget exhausted")
        })
        // 6. Evidence depth behind the proposal.
        checks.add(checkDepth(purpose, input))
        // 7. Comparable observations contradict each other.
        checkContradiction(input)?.let { checks.add(it) }
        // 8. Expected values far outside observed range.
        checkExpected(pending, input)?.let { checks.add(it) }
        // 9. Memory pressure behind cited runs.
        checkMemory(cited, input)?.let { checks.add(it) }
        // 10. Developer modification staleness.
        if ((pending["modified_by"] as? String) == "developer") {
            checks.add(Check("modified", Severity.CAUTION,
                "developer-modified config; model expected values " +
                    "predate the change"))
        }
        val verdict = when {
            checks.any { it.severity == Severity.FAIL } -> Verdict.INVALID
            checks.any { it.severity == Severity.CAUTION } ->
                Verdict.CAUTION
            else -> Verdict.SUPPORTED
        }
        return ReviewResult(verdict, checks)
    }

    private fun checkStagedModel(input: ReviewInput): Check {
        val staged = input.stagedModelSha
        if (staged == null) {
            return Check("identity", Severity.CAUTION,
                "no model staged; stage ${input.parent.modelDisplay} " +
                    "before approving")
        }
        if (!staged.equals(input.parent.modelSha256, ignoreCase = true)) {
            return Check("identity", Severity.CAUTION,
                "staged model differs from the session model; stage " +
                    "the session model before approving")
        }
        return Check("identity", Severity.PASS,
            "staged model matches the session artifact")
    }

    private fun checkConfig(
        proposed: BenchConfigData?,
        input: ReviewInput
    ): Check {
        if (proposed == null) {
            return Check("config", Severity.FAIL,
                "proposed config is unreadable")
        }
        val errors = BenchConfig.validate(proposed).toMutableList()
        if (proposed.nThreads > input.deviceMaxThreads) {
            errors.add("n_threads ${proposed.nThreads} exceeds this " +
                "device's max ${input.deviceMaxThreads}")
        }
        return if (errors.isEmpty()) {
            Check("config", Severity.PASS,
                "ctx=${proposed.nCtx} threads=${proposed.nThreads} " +
                    "predict=${proposed.nPredict} in bounds")
        } else {
            Check("config", Severity.FAIL, errors.joinToString("; "))
        }
    }

    private fun checkChangedKeys(
        pending: Map<String, Any?>,
        proposed: BenchConfigData,
        current: BenchConfigData
    ): Check {
        val recomputed = mutableListOf<String>()
        if (proposed.nCtx != current.nCtx) recomputed.add("n_ctx")
        if (proposed.nThreads != current.nThreads) {
            recomputed.add("n_threads")
        }
        if (proposed.nPredict != current.nPredict) {
            recomputed.add("n_predict")
        }
        val stored = (pending["changed_keys"] as? List<*>)
            ?.mapNotNull { it as? String }?.sorted() ?: emptyList()
        return if (stored == recomputed.sorted()) {
            Check("changed_keys", Severity.PASS,
                if (recomputed.isEmpty()) "no change (repeat/retry)"
                else "changes: ${recomputed.sorted().joinToString(",")}")
        } else {
            Check("changed_keys", Severity.FAIL,
                "changed_keys ${stored} mismatch the " +
                    "proposed-vs-current diff ${recomputed.sorted()}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkPurpose(
        purpose: String,
        pending: Map<String, Any?>,
        proposed: BenchConfigData?,
        input: ReviewInput,
        packIds: Set<String>
    ): Check {
        if (proposed == null) {
            return Check("purpose", Severity.FAIL,
                "no proposed config to check purpose against")
        }
        return when (purpose) {
            AgentApi.PURPOSE_REPEAT -> {
                val cited = pending["repeat_of_run_id"] as? String
                if (cited.isNullOrBlank()) {
                    Check("purpose", Severity.FAIL,
                        "repeat cites no run")
                } else if (cited !in packIds) {
                    Check("purpose", Severity.FAIL,
                        "repeat cites unknown run $cited")
                } else {
                    val executed = input.executedConfigs[cited]
                    if (executed == null) {
                        Check("purpose", Severity.FAIL,
                            "cited run $cited has no stored executed config")
                    } else if (executed.nCtx != proposed.nCtx ||
                        executed.nThreads != proposed.nThreads ||
                        executed.nPredict != proposed.nPredict
                    ) {
                        Check("purpose", Severity.FAIL,
                            "repeat config differs from run $cited's " +
                                "stored executed config")
                    } else {
                        Check("purpose", Severity.PASS,
                            "repeat of $cited with identical config")
                    }
                }
            }
            AgentApi.PURPOSE_RECOVERY -> {
                val cited = pending["recovery_of_run_id"] as? String
                if (cited.isNullOrBlank()) {
                    Check("purpose", Severity.FAIL,
                        "recovery cites no failed run")
                } else if (cited !in input.failureRunIds) {
                    Check("purpose", Severity.FAIL,
                        "recovery cites $cited, not a failed attempt " +
                            "of this session")
                } else {
                    Check("purpose", Severity.PASS,
                        "recovery from failed attempt $cited")
                }
            }
            AgentApi.PURPOSE_OPTIMIZE -> {
                val changed = proposed.nCtx != input.current.nCtx ||
                    proposed.nThreads != input.current.nThreads ||
                    proposed.nPredict != input.current.nPredict
                if (!changed) {
                    Check("purpose", Severity.FAIL,
                        "optimize changes nothing; only an explicit " +
                            "repeat may re-measure the same config")
                } else {
                    Check("purpose", Severity.PASS,
                        "optimize with a changed config")
                }
            }
            else -> Check("purpose", Severity.FAIL,
                "unknown purpose $purpose")
        }
    }

    private fun checkDepth(purpose: String, input: ReviewInput): Check {
        val n = input.pack.comparable.size
        if (purpose == AgentApi.PURPOSE_RECOVERY && n == 0) {
            return Check("evidence", Severity.CAUTION,
                "no completed baseline; expected values are " +
                    "unanchored estimates from a failed attempt")
        }
        return when {
            n == 0 -> Check("evidence", Severity.CAUTION,
                "no comparable measurement yet; the proposal " +
                    "is unanchored")
            n == 1 -> Check("evidence", Severity.CAUTION,
                "a single comparable observation; variability unknown")
            else -> Check("evidence", Severity.PASS,
                "$n comparable observations")
        }
    }

    private fun checkContradiction(input: ReviewInput): Check? {
        val gens = input.pack.comparable.map { it.genTps }
            .filter { it.isFinite() && it > 0 }
        if (gens.size < 2) return null
        val ratio = gens.max() / gens.min()
        if (ratio < CONTRADICTION_RATIO) return null
        return Check("contradiction", Severity.CAUTION,
            "comparable runs disagree " +
                "${"%.1f".format(ratio)}x " +
                "(${"%.1f".format(gens.min())}–" +
                "${"%.1f".format(gens.max())} tok/s); treat the " +
                "expected value as uncertain")
    }

    private fun checkExpected(
        pending: Map<String, Any?>,
        input: ReviewInput
    ): Check? {
        val best = input.pack.comparable.map { it.genTps }
            .filter { it.isFinite() && it > 0 }.maxOrNull() ?: return null
        val expected = (pending["expected_gen_tps"] as? Number)
            ?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: return null
        if (expected < best / EXPECTED_PLAUSIBILITY_FACTOR ||
            expected > best * EXPECTED_PLAUSIBILITY_FACTOR
        ) {
            return Check("expected", Severity.CAUTION,
                "expected ${"%.1f".format(expected)} tok/s sits far " +
                    "outside observed comparable runs " +
                    "(best ${"%.1f".format(best)} tok/s)")
        }
        return null
    }

    private fun checkMemory(
        cited: List<String>,
        input: ReviewInput
    ): Check? {
        val flagged = cited.filter { it in input.memBelowAdvisory }
        if (flagged.isEmpty()) return null
        val detail = flagged.joinToString("; ") { id ->
            val pct = input.memStartPct[id]
            if (pct != null && pct.isFinite()) {
                "$id started at ${"%.1f".format(pct)}% available RAM"
            } else {
                "$id measured under memory pressure"
            }
        }
        return Check("memory", Severity.CAUTION,
            "cited run(s) measured below the memory advisory: $detail")
    }
}
