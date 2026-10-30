package ai.edgefit.diag

/**
 * Agent proposal protocol (plain validated JSON, no function calling).
 * The host owns evidence, bounds, arithmetic, and execution; Meta
 * only reasons over the supplied pack and proposes one experiment —
 * or honestly declines. Unknown extra fields are ignored (execution
 * reads allowlisted keys only); every used field is strictly
 * validated. Pure JVM.
 */
object AgentApi {
    const val AGENT_VERSION = 1
    const val MAX_PROPOSAL_RAW_BYTES = 20000
    /** KV-cache bound rationale: 1024 ctx x ~32 KB/token < 40 MB. */
    const val AGENT_MAX_CTX = 1024
    const val AGENT_MIN_CTX = 128
    val DELTA_KEYS = setOf("n_ctx", "n_threads", "n_predict")
    val DIRECTIONS = setOf("faster", "slower", "uncertain")
    const val PURPOSE_OPTIMIZE = "optimize"
    const val PURPOSE_REPEAT = "repeat"
    /**
     * EF-MEM-03 typed recovery: a new attempt citing a
     * FAILED/TIMEOUT/CANCELLED run. Never equal to a
     * completed measurement; validated separately below.
     */
    const val PURPOSE_RECOVERY = "recovery"
    val PURPOSES = setOf(PURPOSE_OPTIMIZE, PURPOSE_REPEAT, PURPOSE_RECOVERY)

    /**
     * EF-AGENT-REVIEW-01: proposal contract version. v2 adds
     * optional bounded self-critique (counterargument,
     * alternative_explanation, next_benchmark_establishes);
     * v1 replies without them still validate. A model's own
     * critique is displayed labeled and NEVER substitutes for
     * EdgeFit's independent host review.
     */
    const val PROPOSAL_CONTRACT_VERSION = 2
    const val PROPOSAL_CONTRACT_V1 = 1

    class AgentError(val code: String, message: String) : Exception(message)

    data class ProposalExpectation(
        val modelSha: String,
        val workloadId: String,
        val currentConfig: BenchConfigData,
        val nextSeq: Int,
        val allowedRunIds: Set<String>,
        /** min(16, device logical processors). */
        val deviceMaxThreads: Int,
        /**
         * EF-FOLLOWUP-02: executed config per eligible cited
         * run, for repeat verification. Empty when the caller
         * offers no repeat targets (repeats then refuse).
         */
        val repeatConfigs: Map<String, BenchConfigData> = emptyMap(),
        /**
         * EF-MEM-03: failed-run ids eligible for recovery
         * citation. Empty offers no recovery (recovery
         * then refuses, like repeats without targets).
         */
        val recoveryRunIds: Set<String> = emptySet()
    )

    data class ValidProposal(
        val proposeExperiment: Boolean,
        /** Full target config (current + deltas applied). Null when declined. */
        val proposedConfig: BenchConfigData?,
        val changedKeys: List<String>,
        /** optimize (default) or repeat; repeats cite a run below. */
        val purpose: String = PURPOSE_OPTIMIZE,
        /** Cited run for purpose=repeat; null otherwise. */
        val repeatOfRunId: String? = null,
        /** Cited failed run for purpose=recovery; null otherwise. */
        val recoveryOfRunId: String? = null,
        val hypothesis: String,
        val expectedDirection: String,
        val expectedGenTps: Double,
        val expectedPrefillTps: Double,
        val uncertainty: String,
        val supportingEvidenceIds: List<String>,
        val limitations: List<String>,
        val benefit: String,
        val noExperimentReason: String?,
        val rawJson: String,
        /**
         * EF-AGENT-REVIEW-01 v2 self-critique: strongest
         * case against this proposal. Bounded string or
         * null when absent; never invented when missing.
         */
        val counterargument: String? = null,
        /** Another reading of the same evidence. */
        val alternativeExplanation: String? = null,
        /** What one benchmark run would establish. */
        val nextBenchmarkEstablishes: String? = null,
        /** 1 for legacy replies, 2 when v2 fields arrive. */
        val contractVersion: Int = PROPOSAL_CONTRACT_V1
    )

    // ---- prompts ----

    fun proposalSystem(
        deviceMaxThreads: Int,
        recoveryMode: Boolean = false
    ): String {
        if (recoveryMode) return recoverySystem(deviceMaxThreads)
        return "You are an EdgeFit optimization analyst. Answer with ONLY a " +
            "single JSON object, no prose outside it, with exactly these " +
            "fields: model_sha (string), workload_id (string), " +
            "experiment_seq (integer), current_config (object with " +
            "n_ctx, n_threads, n_predict), propose_experiment (boolean), " +
            "proposed_config (object with a subset of n_ctx, n_threads, " +
            "n_predict, or null), hypothesis (string), " +
            "expected_direction (faster|slower|uncertain), " +
            "expected_gen_tps (number), expected_prefill_tps (number), " +
            "uncertainty (string), supporting_evidence_ids (string " +
            "array), limitations (string array), benefit (string), " +
            "no_experiment_reason (string or null), purpose " +
            "(optimize|repeat, default optimize), repeat_of_run_id " +
            "(string or null). Echo model_sha, " +
            "workload_id, experiment_seq, and current_config EXACTLY as " +
            "given. Cite ONLY run ids from COMPARABLE_EVIDENCE. Bounds: " +
            "n_ctx $AGENT_MIN_CTX..$AGENT_MAX_CTX, n_threads 1.." +
            "$deviceMaxThreads, n_predict 1..${BenchConfig.MAX_PREDICT}. " +
            "Change at least one value when proposing, unless purpose " +
            "is repeat: a repeat re-measures ONE cited run with the " +
            "identical config to assess variability, sets " +
            "repeat_of_run_id to that run, and keeps every config " +
            "value equal. A different " +
            "n_predict forms a distinct cohort, never pooled with " +
            "64-token runs; say so in limitations when relevant. " +
            "Throughputs are finite positive tokens/second. When the " +
            "evidence cannot justify another run, set propose_experiment " +
            "false and explain in no_experiment_reason — declining is a " +
            "correct terminal answer, never a failure. Optional " +
            "self-critique (strings of at most 500 chars, or null): " +
            "counterargument (strongest case AGAINST this proposal), " +
            "alternative_explanation (another reading of the same " +
            "evidence), next_benchmark_establishes (what one run " +
            "would establish). Your own critique is shown labeled " +
            "and never substitutes for EdgeFit's independent checks."
    }

    /**
     * EF-MEM-03 recovery prompt: the cited run FAILED, so
     * no throughput exists for it. The model may propose
     * an adjusted config or an explicit identical retry,
     * but must never invent a measurement, never claim a
     * setting guarantees allocation, and must ground every
     * citation in FAILED_ATTEMPTS or COMPARABLE_EVIDENCE.
     */
    fun recoverySystem(deviceMaxThreads: Int): String =
        "You are an EdgeFit failure-recovery analyst. A benchmark " +
            "attempt FAILED: no throughput was measured. Answer with ONLY " +
            "a single JSON object, no prose outside it, with exactly these " +
            "fields: model_sha (string), workload_id (string), " +
            "experiment_seq (integer), current_config (object with " +
            "n_ctx, n_threads, n_predict), propose_experiment (boolean), " +
            "proposed_config (object with a subset of n_ctx, n_threads, " +
            "n_predict, or null), hypothesis (string), " +
            "expected_direction (faster|slower|uncertain), " +
            "expected_gen_tps (number), expected_prefill_tps (number), " +
            "uncertainty (string), supporting_evidence_ids (string " +
            "array), limitations (string array), benefit (string), " +
            "no_experiment_reason (string or null), purpose " +
            "(must be recovery), recovery_of_run_id (the failed run " +
            "id from FAILED_ATTEMPTS). Echo model_sha, " +
            "workload_id, experiment_seq, and current_config EXACTLY as " +
            "given. Cite ONLY run ids from FAILED_ATTEMPTS or " +
            "COMPARABLE_EVIDENCE, and always include " +
            "recovery_of_run_id in supporting_evidence_ids. Bounds: " +
            "n_ctx $AGENT_MIN_CTX..$AGENT_MAX_CTX, n_threads 1.." +
            "$deviceMaxThreads, n_predict 1..${BenchConfig.MAX_PREDICT}. " +
            "You may adjust n_ctx, n_threads, or n_predict (for " +
            "example a smaller context after an allocation failure) " +
            "or retry the identical config explicitly when a transient " +
            "cause is plausible; say which and why. NEVER invent a " +
            "throughput for the failed run. NEVER claim a setting " +
            "guarantees successful allocation. A different n_predict " +
            "forms a distinct cohort; say so in limitations when " +
            "relevant. Throughputs are finite positive tokens/second " +
            "ESTIMATES for the next attempt, not measurements. When " +
            "no useful adjustment can be justified, set " +
            "propose_experiment false and explain in " +
            "no_experiment_reason — declining is a correct terminal " +
            "answer, never a failure. Optional self-critique " +
            "(strings of at most 500 chars, or null): " +
            "counterargument (strongest case AGAINST this proposal), " +
            "alternative_explanation (another reading of the same " +
            "evidence), next_benchmark_establishes (what one run " +
            "would establish). Your own critique is shown labeled " +
            "and never substitutes for EdgeFit's independent checks."

    fun proposalUser(
        packText: String,
        currentConfig: BenchConfigData,
        nextSeq: Int,
        modelSha: String,
        workloadId: String,
        attemptsUsed: Int,
        attemptsMax: Int,
        failureText: String = ""
    ): String {
        val lines = mutableListOf(
            "EVIDENCE_PACK:",
            packText,
            "CURRENT_CONFIG:",
            "n_ctx=${currentConfig.nCtx} " +
                "n_threads=${currentConfig.nThreads} " +
                "n_predict=${currentConfig.nPredict}",
            "NEXT_SEQ: $nextSeq",
            "IDENTITY: model_sha=$modelSha workload_id=$workloadId",
            "BUDGET: used=$attemptsUsed max=$attemptsMax"
        )
        // Failure evidence rides every proposal (it may
        // explain variance); recovery prompts REQUIRE it.
        if (failureText.isNotBlank()) {
            lines.add(failureText)
        }
        lines.add("Propose at most one experiment, or decline with reason.")
        return lines.joinToString("\n")
    }

    // ---- validation ----

    fun validateProposal(
        payload: Map<String, Any?>,
        rawJson: String,
        expected: ProposalExpectation
    ): ValidProposal {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_PROPOSAL_RAW_BYTES) {
            throw AgentError("BAD_PROPOSAL", "proposal exceeds bound")
        }
        if ((payload["model_sha"] as? String)?.lowercase() !=
            expected.modelSha.lowercase()
        ) {
            throw AgentError("BAD_PROPOSAL", "model identity mismatch")
        }
        if ((payload["workload_id"] as? String) != expected.workloadId) {
            throw AgentError("BAD_PROPOSAL", "workload identity mismatch")
        }
        val seq = (payload["experiment_seq"] as? Number)?.toInt()
        if (seq != expected.nextSeq) {
            throw AgentError("BAD_PROPOSAL", "stale or foreign seq $seq")
        }
        checkCurrentEcho(payload["current_config"], expected.currentConfig)
        val propose = payload["propose_experiment"] as? Boolean
            ?: throw AgentError("BAD_PROPOSAL", "propose_experiment boolean")
        if (!propose) {
            val reason = payload["no_experiment_reason"] as? String
            if (reason.isNullOrBlank() || reason.length > MetaApi.MAX_NOTE_FIELD) {
                throw AgentError(
                    "BAD_PROPOSAL", "no_experiment_reason must be 1..500")
            }
            return ValidProposal(
                proposeExperiment = false,
                proposedConfig = null,
                changedKeys = emptyList(),
                purpose = PURPOSE_OPTIMIZE,
                repeatOfRunId = null,
                hypothesis = "",
                expectedDirection = "uncertain",
                expectedGenTps = Double.NaN,
                expectedPrefillTps = Double.NaN,
                uncertainty = "",
                supportingEvidenceIds = emptyList(),
                limitations = emptyList(),
                benefit = "",
                noExperimentReason = reason,
                rawJson = rawJson,
                counterargument = optionalCritique(payload, "counterargument"),
                alternativeExplanation =
                    optionalCritique(payload, "alternative_explanation"),
                nextBenchmarkEstablishes =
                    optionalCritique(payload, "next_benchmark_establishes"),
                contractVersion = contractVersionOf(payload)
            )
        }
        val deltas = payload["proposed_config"]
        if (deltas !is Map<*, *>) {
            throw AgentError("BAD_PROPOSAL", "proposed_config must be object")
        }
        if (deltas.isEmpty()) {
            throw AgentError("BAD_PROPOSAL", "proposed_config must be non-empty")
        }
        val changed = mutableListOf<String>()
        var nCtx = expected.currentConfig.nCtx
        var nThreads = expected.currentConfig.nThreads
        var nPredict = expected.currentConfig.nPredict
        for ((key, value) in deltas) {
            if (key !is String || key !in DELTA_KEYS) {
                throw AgentError("BAD_PROPOSAL", "unsupported key $key")
            }
            val number = (value as? Number)?.toLong()
                ?: throw AgentError("BAD_PROPOSAL", "$key must be integer")
            val ok = when (key) {
                "n_ctx" -> number in AGENT_MIN_CTX..AGENT_MAX_CTX
                "n_threads" -> number in 1..expected.deviceMaxThreads
                else -> number in
                    BenchConfig.MIN_PREDICT..BenchConfig.MAX_PREDICT
            }
            if (!ok) throw AgentError("BAD_PROPOSAL", "$key=$number out of range")
            val current = when (key) {
                "n_ctx" -> expected.currentConfig.nCtx.toLong()
                "n_threads" -> expected.currentConfig.nThreads.toLong()
                else -> expected.currentConfig.nPredict.toLong()
            }
            if (number != current) changed.add(key)
            when (key) {
                "n_ctx" -> nCtx = number.toInt()
                "n_threads" -> nThreads = number.toInt()
                else -> nPredict = number.toInt()
            }
        }
        // EF-FOLLOWUP-02 purpose gate. Absent purpose means
        // optimize (existing prompts); unknown values refuse.
        val purpose = (payload["purpose"] as? String) ?: PURPOSE_OPTIMIZE
        if (purpose !in PURPOSES) {
            throw AgentError("BAD_PROPOSAL", "unknown purpose $purpose")
        }
        var repeatOf: String? = null
        if (purpose == PURPOSE_REPEAT) {
            val cited = payload["repeat_of_run_id"] as? String
            if (cited.isNullOrBlank()) {
                throw AgentError(
                    "BAD_PROPOSAL", "repeat needs repeat_of_run_id")
            }
            if (cited !in expected.allowedRunIds) {
                throw AgentError(
                    "BAD_PROPOSAL", "repeat cites ineligible run")
            }
            val target = expected.repeatConfigs[cited]
                ?: throw AgentError(
                    "BAD_PROPOSAL", "repeat run has no verified config")
            // Exact re-measurement: resolved config must equal
            // the cited run's executed config on every key.
            // Sampler rides on current_config (deltas cannot
            // change it), so it must match the cited run too.
            if (nCtx != target.nCtx || nThreads != target.nThreads ||
                nPredict != target.nPredict ||
                expected.currentConfig.sampler != target.sampler
            ) {
                throw AgentError(
                    "BAD_PROPOSAL", "repeat config differs from cited run")
            }
            repeatOf = cited
        }
        var recoveryOf: String? = null
        if (purpose == PURPOSE_RECOVERY) {
            val cited = payload["recovery_of_run_id"] as? String
            if (cited.isNullOrBlank()) {
                throw AgentError(
                    "BAD_PROPOSAL", "recovery needs recovery_of_run_id")
            }
            if (cited !in expected.recoveryRunIds) {
                throw AgentError(
                    "BAD_PROPOSAL", "recovery cites ineligible run")
            }
            // Identical config is an explicit retry under
            // chosen conditions; changed config is an
            // adjustment. Bounds already checked above.
            recoveryOf = cited
        } else if (purpose != PURPOSE_REPEAT && changed.isEmpty()) {
            throw AgentError("BAD_PROPOSAL", "proposal changes nothing")
        }
        if (nPredict >= nCtx) {
            throw AgentError("BAD_PROPOSAL", "n_predict must fit in n_ctx")
        }
        val direction = payload["expected_direction"] as? String
        if (direction !in DIRECTIONS) {
            throw AgentError("BAD_PROPOSAL", "expected_direction $DIRECTIONS")
        }
        val supporting = payload["supporting_evidence_ids"] as? List<*>
            ?: throw AgentError("BAD_PROPOSAL", "supporting ids array")
        if (supporting.isEmpty()) {
            throw AgentError("BAD_PROPOSAL", "supporting ids non-empty")
        }
        val cited = supporting.map {
            val id = it as? String
                ?: throw AgentError("BAD_PROPOSAL", "supporting ids strings")
            if (id !in expected.allowedRunIds &&
                id !in expected.recoveryRunIds
            ) {
                throw AgentError("BAD_PROPOSAL", "uncited evidence id $id")
            }
            id
        }
        // Recovery must actually cite its failed run, not
        // merely name it in recovery_of_run_id.
        if (purpose == PURPOSE_RECOVERY && recoveryOf !in cited) {
            throw AgentError(
                "BAD_PROPOSAL", "recovery must cite its failed run")
        }
        return ValidProposal(
            proposeExperiment = true,
            proposedConfig = expected.currentConfig.copy(
                nCtx = nCtx, nThreads = nThreads, nPredict = nPredict),
            changedKeys = changed.sorted(),
            purpose = purpose,
            repeatOfRunId = repeatOf,
            recoveryOfRunId = recoveryOf,
            hypothesis = bounded(payload, "hypothesis"),
            expectedDirection = direction!!,
            expectedGenTps = throughput(payload, "expected_gen_tps"),
            expectedPrefillTps = throughput(payload, "expected_prefill_tps"),
            uncertainty = bounded(payload, "uncertainty"),
            supportingEvidenceIds = cited,
            limitations = noteList(payload, "limitations"),
            benefit = bounded(payload, "benefit"),
            noExperimentReason = null,
            rawJson = rawJson,
            counterargument = optionalCritique(payload, "counterargument"),
            alternativeExplanation =
                optionalCritique(payload, "alternative_explanation"),
            nextBenchmarkEstablishes =
                optionalCritique(payload, "next_benchmark_establishes"),
            contractVersion = contractVersionOf(payload)
        )
    }

    private fun checkCurrentEcho(raw: Any?, current: BenchConfigData) {
        if (raw !is Map<*, *>) {
            throw AgentError("BAD_PROPOSAL", "current_config must be object")
        }
        val nCtx = (raw["n_ctx"] as? Number)?.toInt()
        val nThreads = (raw["n_threads"] as? Number)?.toInt()
        val nPredict = (raw["n_predict"] as? Number)?.toInt()
        if (nCtx != current.nCtx || nThreads != current.nThreads ||
            nPredict != current.nPredict
        ) {
            throw AgentError("BAD_PROPOSAL", "current config echo mismatch")
        }
    }

    /**
     * EF-AGENT-REVIEW-01 v2: optional bounded self-critique.
     * Absent or null stays null (never invented); a blank
     * string normalizes to null; any other non-string or
     * oversized value fails closed like other fields.
     */
    private fun optionalCritique(payload: Map<String, Any?>, key: String): String? {
        if (!payload.containsKey(key)) return null
        val raw = payload[key] ?: return null
        if (raw !is String) {
            throw AgentError("BAD_PROPOSAL", "$key must be a string or null")
        }
        if (raw.length > MetaApi.MAX_NOTE_FIELD) {
            throw AgentError("BAD_PROPOSAL", "$key must be 0..500 chars")
        }
        return raw.ifBlank { null }
    }

    /** v2 when any self-critique field arrived, else v1. */
    private fun contractVersionOf(payload: Map<String, Any?>): Int {
        val keys = setOf("counterargument", "alternative_explanation",
            "next_benchmark_establishes")
        return if (keys.any { payload.containsKey(it) && payload[it] != null }) {
            PROPOSAL_CONTRACT_VERSION
        } else {
            PROPOSAL_CONTRACT_V1
        }
    }

    private fun bounded(payload: Map<String, Any?>, key: String): String {
        val value = payload[key] as? String
            ?: throw AgentError("BAD_PROPOSAL", "$key must be a string")
        if (value.isBlank() || value.length > MetaApi.MAX_NOTE_FIELD) {
            throw AgentError("BAD_PROPOSAL", "$key must be 1..500 chars")
        }
        return value
    }

    private fun throughput(payload: Map<String, Any?>, key: String): Double {
        val value = (payload[key] as? Number)?.toDouble()
            ?: throw AgentError("BAD_PROPOSAL", "$key must be a number")
        if (!value.isFinite() || value <= 0 ||
            value > MetaApi.MAX_PREDICTED_TPS
        ) {
            throw AgentError("BAD_PROPOSAL", "$key out of bounds")
        }
        return value
    }

    private fun noteList(payload: Map<String, Any?>, key: String): List<String> {
        val value = payload[key] as? List<*>
            ?: throw AgentError("BAD_PROPOSAL", "$key must be an array")
        if (value.size > MetaApi.MAX_LIST_ITEMS) {
            throw AgentError("BAD_PROPOSAL", "$key too long")
        }
        return value.map {
            val item = it as? String
                ?: throw AgentError("BAD_PROPOSAL", "$key holds strings")
            if (item.length > MetaApi.MAX_NOTE_FIELD) {
                throw AgentError("BAD_PROPOSAL", "$key item too long")
            }
            item
        }
    }
}
