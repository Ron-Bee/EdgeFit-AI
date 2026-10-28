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

    class AgentError(val code: String, message: String) : Exception(message)

    data class ProposalExpectation(
        val modelSha: String,
        val workloadId: String,
        val currentConfig: BenchConfigData,
        val nextSeq: Int,
        val allowedRunIds: Set<String>,
        /** min(16, device logical processors). */
        val deviceMaxThreads: Int
    )

    data class ValidProposal(
        val proposeExperiment: Boolean,
        /** Full target config (current + deltas applied). Null when declined. */
        val proposedConfig: BenchConfigData?,
        val changedKeys: List<String>,
        val hypothesis: String,
        val expectedDirection: String,
        val expectedGenTps: Double,
        val expectedPrefillTps: Double,
        val uncertainty: String,
        val supportingEvidenceIds: List<String>,
        val limitations: List<String>,
        val benefit: String,
        val noExperimentReason: String?,
        val rawJson: String
    )

    // ---- prompts ----

    fun proposalSystem(deviceMaxThreads: Int): String =
        "You are an EdgeFit optimization analyst. Answer with ONLY a " +
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
            "no_experiment_reason (string or null). Echo model_sha, " +
            "workload_id, experiment_seq, and current_config EXACTLY as " +
            "given. Cite ONLY run ids from COMPARABLE_EVIDENCE. Bounds: " +
            "n_ctx $AGENT_MIN_CTX..$AGENT_MAX_CTX, n_threads 1.." +
            "$deviceMaxThreads, n_predict 1..${BenchConfig.MAX_PREDICT}. " +
            "Change at least one value when proposing. A different " +
            "n_predict forms a distinct cohort, never pooled with " +
            "64-token runs; say so in limitations when relevant. " +
            "Throughputs are finite positive tokens/second. When the " +
            "evidence cannot justify another run, set propose_experiment " +
            "false and explain in no_experiment_reason — declining is a " +
            "correct terminal answer, never a failure."

    fun proposalUser(
        packText: String,
        currentConfig: BenchConfigData,
        nextSeq: Int,
        modelSha: String,
        workloadId: String,
        attemptsUsed: Int,
        attemptsMax: Int
    ): String = listOf(
        "EVIDENCE_PACK:",
        packText,
        "CURRENT_CONFIG:",
        "n_ctx=${currentConfig.nCtx} " +
            "n_threads=${currentConfig.nThreads} " +
            "n_predict=${currentConfig.nPredict}",
        "NEXT_SEQ: $nextSeq",
        "IDENTITY: model_sha=$modelSha workload_id=$workloadId",
        "BUDGET: used=$attemptsUsed max=$attemptsMax",
        "Propose at most one experiment, or decline with reason."
    ).joinToString("\n")

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
                hypothesis = "",
                expectedDirection = "uncertain",
                expectedGenTps = Double.NaN,
                expectedPrefillTps = Double.NaN,
                uncertainty = "",
                supportingEvidenceIds = emptyList(),
                limitations = emptyList(),
                benefit = "",
                noExperimentReason = reason,
                rawJson = rawJson
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
        if (changed.isEmpty()) {
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
            if (id !in expected.allowedRunIds) {
                throw AgentError("BAD_PROPOSAL", "uncited evidence id $id")
            }
            id
        }
        return ValidProposal(
            proposeExperiment = true,
            proposedConfig = expected.currentConfig.copy(
                nCtx = nCtx, nThreads = nThreads, nPredict = nPredict),
            changedKeys = changed.sorted(),
            hypothesis = bounded(payload, "hypothesis"),
            expectedDirection = direction!!,
            expectedGenTps = throughput(payload, "expected_gen_tps"),
            expectedPrefillTps = throughput(payload, "expected_prefill_tps"),
            uncertainty = bounded(payload, "uncertainty"),
            supportingEvidenceIds = cited,
            limitations = noteList(payload, "limitations"),
            benefit = bounded(payload, "benefit"),
            noExperimentReason = null,
            rawJson = rawJson
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
