package ai.edgefit.diag

import java.net.URI

/**
 * Meta Model API contract for direct BYOK (Mode A). Every wire value
 * mirrors the previously live-qualified Windows calls
 * (`ef_meta_05_live.py`, `ef_meta_06_live.py`, owner-executed PASS):
 * endpoint, Bearer auth, model id, temperature, Responses input
 * shape. Public provider write-ups corroborate the same base URL,
 * auth, and model id; the repo's own successful calls govern.
 *
 * No-tools requests: the phone cannot reach the workstation SQLite
 * store, so there is no device-side function worth exposing. Bounded
 * context is inlined in the prompt instead, and the model answers
 * with strict JSON that is validated fail-closed. Pure JVM.
 */
object MetaApi {
    const val ENDPOINT = "https://api.meta.ai/v1/responses"
    const val HOST = "api.meta.ai"
    const val MODEL = "muse-spark-1.3"
    const val CONNECT_TIMEOUT_MS = 15000
    const val READ_TIMEOUT_MS = 60000
    const val MAX_REQUEST_BYTES = 32768
    const val MAX_RESPONSE_BYTES = 65536
    const val MAX_ERROR_PREVIEW = 2000
    const val MAX_STORED_RAW_BYTES = 20000
    /** Absurdity ceiling only; excludes garbage, never real hardware. */
    const val MAX_PREDICTED_TPS = 1000000.0
    const val MAX_TEXT_FIELD = 2000
    const val MAX_NOTE_FIELD = 500
    const val MAX_LIST_ITEMS = 8
    const val MAX_PRIOR_RUNS = 3
    const val FOLLOWUP_ACCURATE_PCT = 5.0
    val CONFIDENCES = setOf("low", "medium", "high")
    val DIRECTIONS = setOf("overestimated", "underestimated", "accurate")
    val PROPOSAL_KEYS = setOf("n_ctx", "n_threads", "n_predict", "seed")

    class MetaError(val code: String, message: String) : Exception(message)

    data class ValidPrediction(
        val genTps: Double,
        val prefillTps: Double,
        val confidence: String,
        val rationale: String,
        val uncertaintyNotes: List<String>,
        val evidenceCited: List<String>,
        val limits: List<String>,
        val rawJson: String
    )

    data class ValidFollowUp(
        val direction: String,
        val summary: String,
        val limitations: List<String>,
        val supportsConclusion: Boolean,
        val nextExperiment: Map<String, Any?>?,
        val proposalValid: Boolean,
        val proposalRejection: String?,
        val rawJson: String,
        /**
         * EF-AGENT-REVIEW-01: optional bounded self-critique
         * (strongest case against the assessment's own
         * reading). Null when absent; never invented.
         */
        val counterargument: String? = null
    )

    // ---- requests ----

    /**
     * Qualified shape: model + temperature + input only. No "tools"
     * key at all — omitting tools is the supported way to disallow
     * tool calls.
     */
    fun requestBody(system: String, user: String): String {
        val body = SessionJson.stringify(linkedMapOf(
            "model" to MODEL,
            "temperature" to 0,
            "input" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user)
            )
        ))
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw MetaError("REQUEST_TOO_LARGE", "request exceeds bound")
        }
        return body
    }

    /** Fails closed: https + exact host + no userinfo + sane port. */
    fun checkUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(HOST, ignoreCase = true) &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                (uri.path ?: "").startsWith("/v1/responses")
        } catch (_: Exception) {
            false
        }
    }

    fun authHeader(apiKey: String): String = "Bearer $apiKey"

    // ---- prompts ----

    fun predictionSystem(): String =
        "You are an EdgeFit performance analyst. Answer with ONLY a " +
            "single JSON object, no prose outside it, with exactly these " +
            "fields: predicted_gen_tps (number), predicted_prefill_tps " +
            "(number), confidence (low|medium|high), rationale (string), " +
            "uncertainty_notes (string array), evidence_cited (string " +
            "array), limits (string array), workload_id (string), " +
            "model_sha (string). Throughputs are tokens/second, finite " +
            "and positive. Echo workload_id and model_sha EXACTLY as " +
            "given in the context. Cite ONLY run ids listed under " +
            "PRIOR_ON_DEVICE_EVIDENCE, or [\"none\"] when it says none. " +
            "Never invent measurements, run ids, or statistics. State " +
            "uncertainty honestly; a guess with no prior evidence is " +
            "low confidence."

    fun predictionUser(
        deviceLines: List<String>,
        modelDisplay: String,
        modelSha: String,
        modelSizeBytes: Long,
        quantization: String,
        workloadId: String,
        promptSha: String,
        config: BenchConfigData,
        runtimeVersion: String,
        runtimeAbi: String,
        priorRuns: List<Triple<String, Double, Double>>
    ): String {
        val lines = mutableListOf(
            "DEVICE_PROFILE:",
            *deviceLines.take(12).toTypedArray(),
            "MODEL:",
            "display=$modelDisplay",
            "sha256=$modelSha",
            "size_bytes=$modelSizeBytes",
            "quantization=$quantization",
            "WORKLOAD:",
            "id=$workloadId",
            "prompt_sha256=$promptSha",
            "CONFIG:",
            "n_ctx=${config.nCtx} n_threads=${config.nThreads} " +
                "n_predict=${config.nPredict} sampler=${config.sampler} " +
                "temperature=${config.temperature} seed=${config.seed}",
            "RUNTIME:",
            "impl=${BenchContract.RUNTIME_IMPL} " +
                "version=$runtimeVersion backend=${BenchContract.BACKEND} " +
                "abi=$runtimeAbi",
            "PRIOR_ON_DEVICE_EVIDENCE:"
        )
        if (priorRuns.isEmpty()) {
            lines.add("none")
        } else {
            for ((runId, gen, prefill) in priorRuns.take(MAX_PRIOR_RUNS)) {
                lines.add("$runId gen_tps=$gen prefill_tps=$prefill " +
                    "status=COMPLETED")
            }
        }
        lines.add("Predict generation and prefill throughput for exactly " +
            "this device, model, workload, and configuration.")
        return lines.joinToString("\n")
    }

    fun followUpSystem(): String =
        "You are an EdgeFit performance analyst. Answer with ONLY a " +
            "single JSON object, no prose outside it, with exactly these " +
            "fields: direction (overestimated|underestimated|accurate), " +
            "summary (string), limitations (string array), " +
            "supports_conclusion (boolean), next_experiment (object or " +
            "null), run_id (string). Echo run_id EXACTLY as given. Quote " +
            "the host-computed errors; do not recompute them. direction " +
            "must agree with the signed error (negative means " +
            "overestimated). Never claim a cause from one " +
            "measurement; with SERIES_PRIORS, describe observed " +
            "variability only. next_experiment, when present, has " +
            "description " +
            "(string), config_changes (object with only n_ctx, " +
            "n_threads, n_predict, seed), rationale (string), and stays " +
            "within n_ctx 128..4096, n_threads 1..16, n_predict 1..256. " +
            "Optional self-critique (string of at most 500 chars, or " +
            "null): counterargument (strongest case AGAINST your own " +
            "reading of this run). It is shown labeled and never " +
            "substitutes for EdgeFit's independent checks."

    /**
     * EF-FOLLOWUP-02: one prior same-configuration run sent
     * with a follow-up. Deltas are host-computed; the model
     * quotes them, never recomputes them.
     */
    data class SeriesPrior(
        val runId: String,
        val collectedAtUtc: String,
        val genTps: Double,
        val prefillTps: Double,
        val thermalAfter: String,
        /** Current minus prior, in tok/s. NaN when undefined. */
        val deltaGen: Double,
        /** Percentage vs prior. NaN when undefined. */
        val deltaPct: Double
    )

    fun followUpUser(
        runId: String,
        modelDisplay: String,
        workloadId: String,
        config: BenchConfigData,
        predictedGen: Double,
        predictedPrefill: Double,
        measuredGen: Double,
        measuredPrefill: Double,
        genSigned: Double,
        genPct: Double,
        prefillSigned: Double,
        prefillPct: Double,
        thermalBefore: String,
        thermalMid: String,
        thermalAfter: String,
        memMethod: String,
        /** Bounded prior same-config runs, oldest first. */
        seriesPriors: List<SeriesPrior> = emptyList()
    ): String {
        val lines = mutableListOf(
            "RUN:",
            "run_id=$runId model=$modelDisplay workload=$workloadId",
            "n_ctx=${config.nCtx} n_threads=${config.nThreads} " +
                "n_predict=${config.nPredict} sampler=${config.sampler}",
            "PREDICTED_TPS: gen=$predictedGen prefill=$predictedPrefill",
            "OBSERVED_TPS: gen=$measuredGen prefill=$measuredPrefill",
            "HOST_ERRORS: gen_signed=$genSigned gen_pct=$genPct " +
                "prefill_signed=$prefillSigned prefill_pct=$prefillPct",
            "CONDITIONS: thermal=$thermalBefore/$thermalMid/$thermalAfter " +
                "memory_method=$memMethod"
        )
        if (seriesPriors.isNotEmpty()) {
            lines.add("SERIES_PRIORS " +
                "(same model/workload/config/runtime, host deltas " +
                "current-minus-prior):")
            for (prior in seriesPriors) {
                lines.add("run_id=${prior.runId} " +
                    "at=${prior.collectedAtUtc} gen=${prior.genTps} " +
                    "prefill=${prior.prefillTps} " +
                    "thermal_after=${prior.thermalAfter} " +
                    "delta_gen=${prior.deltaGen} " +
                    "delta_pct=${prior.deltaPct}")
            }
            lines.add("Assess this paired prediction against " +
                "measurement in light of ${seriesPriors.size} prior " +
                "same-configuration runs; quote host deltas, do not " +
                "recompute them.")
        } else {
            lines.add("Assess this single paired prediction against measurement.")
        }
        return lines.joinToString("\n")
    }

    // ---- responses ----

    /** Mirrors the qualified `_extract_text`: output_text + message parts. */
    @Suppress("UNCHECKED_CAST")
    fun extractText(response: Any?): String {
        val chunks = mutableListOf<String>()
        if (response !is Map<*, *>) return ""
        (response as Map<String, Any?>)["output_text"]?.let {
            if (it is String) chunks.add(it)
        }
        val output = response["output"]
        if (output is List<*>) {
            for (item in output) {
                if (item !is Map<*, *>) continue
                val map = item as Map<String, Any?>
                if (map["type"] != "message") continue
                val content = map["content"]
                if (content is List<*>) {
                    for (part in content) {
                        if (part !is Map<*, *>) continue
                        val partMap = part as Map<String, Any?>
                        if (partMap["type"] in listOf("output_text", "text") &&
                            partMap["text"] is String
                        ) {
                            chunks.add(partMap["text"] as String)
                        }
                    }
                }
            }
        }
        return chunks.joinToString("\n")
    }

    /**
     * Bracket slice: first '{' through last '}'. Accepts pure-JSON
     * and wrapped answers; unparseable or non-object content fails.
     */
    fun extractJsonObject(text: String): Map<String, Any?>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val parsed = SessionJson.parse(text.substring(start, end + 1))
            @Suppress("UNCHECKED_CAST")
            parsed as? Map<String, Any?>
        } catch (_: SessionJson.JsonError) {
            null
        }
    }

    /** Raw slice for durable storage; enforced before any persist. */
    fun rawSlice(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw MetaError("BAD_RESPONSE", "no JSON object in reply")
        }
        val slice = text.substring(start, end + 1)
        if (slice.toByteArray(Charsets.UTF_8).size > MAX_STORED_RAW_BYTES) {
            throw MetaError("REPLY_TOO_LARGE", "reply exceeds storage bound")
        }
        return slice
    }

    // ---- validation ----

    private fun num(map: Map<String, Any?>, key: String): Double {
        val value = (map[key] as? Number)?.toDouble()
            ?: throw MetaError("BAD_PREDICTION", "$key must be a number")
        if (!value.isFinite() || value <= 0) {
            throw MetaError("BAD_PREDICTION", "$key must be finite and > 0")
        }
        if (value > MAX_PREDICTED_TPS) {
            throw MetaError("BAD_PREDICTION", "$key out of bounds")
        }
        return value
    }

    private fun str(map: Map<String, Any?>, key: String, max: Int): String {
        val value = map[key] as? String
            ?: throw MetaError("BAD_PREDICTION", "$key must be a string")
        if (value.length > max) {
            throw MetaError("BAD_PREDICTION", "$key too long")
        }
        return value
    }

    private fun strList(map: Map<String, Any?>, key: String): List<String> {
        val value = map[key] as? List<*>
            ?: throw MetaError("BAD_PREDICTION", "$key must be an array")
        if (value.size > MAX_LIST_ITEMS) {
            throw MetaError("BAD_PREDICTION", "$key too long")
        }
        return value.map {
            val item = it as? String
                ?: throw MetaError("BAD_PREDICTION", "$key holds strings")
            if (item.length > MAX_NOTE_FIELD) {
                throw MetaError("BAD_PREDICTION", "$key item too long")
            }
            item
        }
    }

    fun validatePrediction(
        payload: Map<String, Any?>,
        rawJson: String,
        expectedWorkloadId: String,
        expectedModelSha: String,
        allowedEvidenceIds: Set<String>
    ): ValidPrediction {
        val gen = num(payload, "predicted_gen_tps")
        val prefill = num(payload, "predicted_prefill_tps")
        val confidence = str(payload, "confidence", 16)
        if (confidence !in CONFIDENCES) {
            throw MetaError("BAD_PREDICTION", "confidence must be low|medium|high")
        }
        val rationale = str(payload, "rationale", MAX_TEXT_FIELD)
        if (rationale.isBlank()) {
            throw MetaError("BAD_PREDICTION", "rationale must be non-empty")
        }
        val cited = strList(payload, "evidence_cited")
        for (id in cited) {
            if (id != "none" && id !in allowedEvidenceIds) {
                throw MetaError("BAD_PREDICTION", "uncited evidence id $id")
            }
        }
        if (str(payload, "workload_id", 64) != expectedWorkloadId) {
            throw MetaError("BAD_PREDICTION", "workload identity mismatch")
        }
        if (str(payload, "model_sha", 64).lowercase() !=
            expectedModelSha.lowercase()
        ) {
            throw MetaError("BAD_PREDICTION", "model identity mismatch")
        }
        return ValidPrediction(
            genTps = gen,
            prefillTps = prefill,
            confidence = confidence,
            rationale = rationale,
            uncertaintyNotes = strList(payload, "uncertainty_notes"),
            evidenceCited = cited,
            limits = strList(payload, "limits"),
            rawJson = rawJson
        )
    }

    fun validateFollowUp(
        payload: Map<String, Any?>,
        rawJson: String,
        expectedRunId: String,
        genSigned: Double,
        genPct: Double
    ): ValidFollowUp {
        val direction = payload["direction"] as? String
            ?: throw MetaError("BAD_FOLLOWUP", "direction must be a string")
        if (direction !in DIRECTIONS) {
            throw MetaError("BAD_FOLLOWUP", "direction must be $DIRECTIONS")
        }
        // Host-checkable consistency: direction must agree with our errors.
        val agrees = when (direction) {
            "overestimated" -> genSigned < 0
            "underestimated" -> genSigned > 0
            else -> kotlin.math.abs(genPct) <= FOLLOWUP_ACCURATE_PCT
        }
        if (!agrees) {
            throw MetaError(
                "BAD_FOLLOWUP", "direction disagrees with host errors")
        }
        if ((payload["run_id"] as? String) != expectedRunId) {
            throw MetaError("BAD_FOLLOWUP", "run identity mismatch")
        }
        val summary = payload["summary"] as? String
            ?: throw MetaError("BAD_FOLLOWUP", "summary must be a string")
        if (summary.isBlank() || summary.length > MAX_TEXT_FIELD) {
            throw MetaError("BAD_FOLLOWUP", "summary must be 1..$MAX_TEXT_FIELD")
        }
        val limitations = payload["limitations"] as? List<*>
            ?: throw MetaError("BAD_FOLLOWUP", "limitations must be an array")
        if (limitations.size > MAX_LIST_ITEMS) {
            throw MetaError("BAD_FOLLOWUP", "limitations too long")
        }
        val notes = limitations.map {
            val item = it as? String
                ?: throw MetaError("BAD_FOLLOWUP", "limitations hold strings")
            if (item.length > MAX_NOTE_FIELD) {
                throw MetaError("BAD_FOLLOWUP", "limitation too long")
            }
            item
        }
        val supports = payload["supports_conclusion"] as? Boolean
            ?: throw MetaError("BAD_FOLLOWUP", "supports_conclusion boolean")
        // Proposed experiments are validated but quarantined in-field
        // when invalid: the assessment persists, the proposal never acts.
        var experiment: Map<String, Any?>? = null
        var proposalValid = true
        var proposalRejection: String? = null
        val raw = payload["next_experiment"]
        if (raw != null) {
            if (raw !is Map<*, *>) {
                throw MetaError("BAD_FOLLOWUP", "next_experiment object|null")
            }
            @Suppress("UNCHECKED_CAST")
            val proposal = raw as Map<String, Any?>
            val verdict = checkProposal(proposal)
            proposalValid = verdict == null
            proposalRejection = verdict
            @Suppress("UNCHECKED_CAST")
            experiment = (proposal as Map<String, Any?>).toMap() +
                ("host_proposal_valid" to proposalValid)
        }
        return ValidFollowUp(
            direction = direction,
            summary = summary,
            limitations = notes,
            supportsConclusion = supports,
            nextExperiment = experiment,
            proposalValid = proposalValid,
            proposalRejection = proposalRejection,
            rawJson = rawJson,
            counterargument = optionalCritique(payload, "counterargument")
        )
    }

    /**
     * EF-AGENT-REVIEW-01: optional bounded self-critique.
     * Absent or null stays null (never invented); blank
     * normalizes to null; wrong type or oversized fails
     * closed like other follow-up fields.
     */
    private fun optionalCritique(payload: Map<String, Any?>, key: String): String? {
        if (!payload.containsKey(key)) return null
        val raw = payload[key] ?: return null
        if (raw !is String) {
            throw MetaError("BAD_FOLLOWUP", "$key must be a string or null")
        }
        if (raw.length > MAX_NOTE_FIELD) {
            throw MetaError("BAD_FOLLOWUP", "$key must be 0..500 chars")
        }
        return raw.ifBlank { null }
    }

    /** Null when the proposal is in-bounds, else the rejection reason. */
    private fun checkProposal(proposal: Map<String, Any?>): String? {
        val description = proposal["description"] as? String
        if (description.isNullOrBlank() || description.length > MAX_NOTE_FIELD) {
            return "description must be 1..$MAX_NOTE_FIELD chars"
        }
        val rationale = proposal["rationale"] as? String
        if (rationale.isNullOrBlank() || rationale.length > MAX_NOTE_FIELD) {
            return "rationale must be 1..$MAX_NOTE_FIELD chars"
        }
        val changes = proposal["config_changes"]
        if (changes !is Map<*, *>) return "config_changes must be an object"
        if (changes.isEmpty()) return "config_changes must be non-empty"
        for ((key, value) in changes) {
            if (key !is String || key !in PROPOSAL_KEYS) {
                return "unsupported config key $key"
            }
            val number = (value as? Number)?.toLong()
                ?: return "$key must be an integer"
            val ok = when (key) {
                "n_ctx" -> number in BenchConfig.MIN_CTX..BenchConfig.MAX_CTX
                "n_threads" -> number in
                    BenchConfig.MIN_THREADS..BenchConfig.MAX_THREADS
                "n_predict" -> number in
                    BenchConfig.MIN_PREDICT..BenchConfig.MAX_PREDICT
                else -> true // seed: any integer
            }
            if (!ok) return "$key=$number out of range"
        }
        return null
    }

    // ---- transport mapping ----

    /** Maps HTTP status to a stable failure code. */
    fun classifyStatus(status: Int): String = when (status) {
        in 200..299 -> "OK"
        401, 403 -> "INVALID_KEY"
        429 -> "RATE_LIMITED"
        in 500..599 -> "SERVER_ERROR"
        else -> "HTTP_$status"
    }

    /** Defense in depth: scrub key material from any surfaced text. */
    fun redact(text: String, apiKey: String?): String {
        if (apiKey.isNullOrEmpty() || apiKey.length < 8) return text
        return text.replace(apiKey, "[REDACTED]")
    }
}
