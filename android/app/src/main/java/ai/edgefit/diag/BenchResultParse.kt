package ai.edgefit.diag

import java.security.MessageDigest

/**
 * Parses and validates a benchmark-result JSON document (captured or
 * imported) against the field bounds the global backend enforces
 * (cloud/validate.js). Pure JVM, zero Android dependencies.
 *
 * The validated [raw] map preserves EVERY original key (including
 * x_ extensions and provenance) so a submission carries the exact
 * source document; [summary] fields drive local display and the
 * "Ready to contribute" gate.
 */
object BenchResultParse {
    class InvalidResult(message: String) : Exception(message)

    data class Summary(
        val runId: String,
        val sha256: String,
        val status: String,
        val error: String?,
        val appVersion: String,
        val collectedAtUtc: String,
        val deviceModel: String,
        val sdkInt: Int,
        val modelDisplay: String,
        val modelSizeBytes: Long,
        val modelSha256: String,
        val quantization: String,
        val nCtx: Int,
        val nThreads: Int,
        val nPredict: Int,
        val sampler: String,
        val workloadId: String,
        val promptSha256: String,
        val tpsGen: Double,
        val tpsPrefill: Double,
        val generatedTokens: Int,
        val thermalBefore: String,
        val thermalMid: String,
        val thermalAfter: String,
        val memAvailPctStart: Double?
    )

    data class Parsed(val summary: Summary, val raw: Map<String, Any?>)

    private val SHA64 = Regex("[0-9a-f]{64}")
    private val RUN_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:\\-]{0,127}")

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Parses [text] (UTF-8 JSON, at most [maxBytes]) and validates it.
     * [nowMs] bounds timestamps (collected/accepted skew tolerance).
     */
    fun parse(
        text: String,
        nowMs: Long,
        maxBytes: Int = GlobalContract.MAX_REQUEST_BYTES
    ): Parsed {
        if (text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            throw InvalidResult("document exceeds $maxBytes bytes")
        }
        val root = try {
            SessionJson.parse(text)
        } catch (e: SessionJson.JsonError) {
            throw InvalidResult("malformed JSON: ${e.message}")
        }
        if (root !is Map<*, *>) throw InvalidResult("root must be an object")
        @Suppress("UNCHECKED_CAST")
        val raw = root as Map<String, Any?>
        fun bad(field: String, why: String): Nothing =
            throw InvalidResult("$field: $why")

        if (raw["contract"] != BenchContract.CONTRACT) {
            bad("contract", "must be ${BenchContract.CONTRACT}")
        }
        if ((raw["contract_version"] as? Number)?.toInt() !=
            BenchContract.CONTRACT_VERSION
        ) bad("contract_version", "unsupported contract version")
        val runId = raw["run_id"] as? String
        if (runId == null || !RUN_ID.matches(runId)) {
            bad("run_id", "malformed run id")
        }
        val collected = raw["collected_at_utc"] as? String
            ?: bad("collected_at_utc", "missing")
        val collectedMs = parseIsoUtc(collected)
            ?: bad("collected_at_utc", "must be ISO-8601")
        if (collectedMs > nowMs + 5 * 60 * 1000) {
            bad("collected_at_utc", "timestamp is in the future")
        }
        val status = raw["status"] as? String
        if (status == null || status !in BenchContract.STATUSES) {
            bad("status", "unknown measurement status")
        }
        val error = raw["error"] as? String
        if (status == BenchContract.STATUS_COMPLETED && error != null) {
            bad("error", "COMPLETED must carry no error")
        }
        if (status != BenchContract.STATUS_COMPLETED && error.isNullOrEmpty()) {
            bad("error", "non-COMPLETED requires error")
        }
        if (error != null && error.length > 2048) {
            bad("error", "must be at most 2048 chars")
        }
        val appVersion = raw["app_version"] as? String
        if (appVersion.isNullOrEmpty() || appVersion.length > 64) {
            bad("app_version", "must be 1..64 chars")
        }

        val device = raw["device"] as? Map<*, *>
            ?: bad("device", "device must be an object")
        val deviceModel = str(device["model"], 1, 64)
            ?: bad("device.model", "must be 1..64 chars")
        val sdkInt = intIn(device["sdk_int"], 21, 99)
            ?: bad("device.sdk_int", "must be an integer 21..99")
        val abis = device["supported_abis"] as? List<*>
        if (abis == null || abis.isEmpty() || abis.size > 8 ||
            !abis.all { it is String && it.length in 1..32 }
        ) bad("device.supported_abis", "must be 1..8 ABI strings")
        if (intIn(device["logical_processors"], 1, 1024) == null) {
            bad("device.logical_processors", "must be an integer 1..1024")
        }
        if (longIn(device["total_ram_bytes"], 0, (1L shl 48)) == null) {
            bad("device.total_ram_bytes", "out of range")
        }

        val model = raw["model"] as? Map<*, *>
            ?: bad("model", "model must be an object")
        val display = str(model["display_name"], 1, 256)
            ?: bad("model.display_name", "must be 1..256 chars")
        val sizeBytes = longIn(model["size_bytes"], 0, (1L shl 48))
            ?: bad("model.size_bytes", "out of range")
        val modelSha = model["sha256"] as? String
        if (modelSha == null || !SHA64.matches(modelSha)) {
            bad("model.sha256", "must be 64 lowercase hex chars")
        }
        val quant = str(model["quantization"], 1, 64)
            ?: bad("model.quantization", "must be 1..64 chars")

        val runtime = raw["runtime"] as? Map<*, *>
            ?: bad("runtime", "runtime must be an object")
        for (key in listOf("impl", "version", "backend", "abi")) {
            if (str(runtime[key], 1, 64) == null) bad("runtime.$key", "required")
        }

        val config = raw["config"] as? Map<*, *>
            ?: bad("config", "config must be an object")
        val nCtx = intIn(config["n_ctx"], 1, 1048576)
            ?: bad("config.n_ctx", "out of range")
        val nThreads = intIn(config["n_threads"], 1, 1024)
            ?: bad("config.n_threads", "out of range")
        val nPredict = intIn(config["n_predict"], 1, 1048576)
            ?: bad("config.n_predict", "out of range")
        val temperature = config["temperature"] as? Number
        if (temperature == null || !temperature.toDouble().isFinite()) {
            bad("config.temperature", "must be a finite number")
        }
        val sampler = str(config["sampler"], 1, 32)
            ?: bad("config.sampler", "required")
        val timeout = longIn(config["timeout_ms"], 1, (1L shl 48))
        if (timeout == null) bad("config.timeout_ms", "out of range")

        val workload = raw["workload"] as? Map<*, *>
            ?: bad("workload", "workload must be an object")
        val workloadId = str(workload["id"], 1, 64)
            ?: bad("workload.id", "required")
        val promptSha = workload["prompt_sha256"] as? String
        if (promptSha == null || !SHA64.matches(promptSha)) {
            bad("workload.prompt_sha256", "must be 64 lowercase hex")
        }

        val m = raw["measurements"] as? Map<*, *>
            ?: bad("measurements", "measurements must be an object")
        for (key in listOf("load_ms", "prefill_ms", "gen_ms")) {
            if (longIn(m[key], 0, (1L shl 48)) == null) {
                bad("measurements.$key", "out of range")
            }
        }
        for (key in listOf("prefill_tokens", "generated_tokens")) {
            if (intIn(m[key], 0, Int.MAX_VALUE) == null) {
                bad("measurements.$key", "out of range")
            }
        }
        val tpsGen = finiteNonNeg(m["tps_gen"])
            ?: bad("measurements.tps_gen", "must be finite and >= 0")
        val tpsPrefill = finiteNonNeg(m["tps_prefill"])
            ?: bad("measurements.tps_prefill", "must be finite and >= 0")
        if (m["eos_stop"] !is Boolean) {
            bad("measurements.eos_stop", "must be a boolean")
        }
        val mem = m["mem"] as? Map<*, *>
            ?: bad("measurements.mem", "mem must be an object")
        for (key in listOf("pss_before_kb", "pss_after_kb")) {
            if (longIn(mem[key], 0, (1L shl 48)) == null) {
                bad("measurements.mem.$key", "out of range")
            }
        }
        val allocDelta = mem["alloc_delta_bytes"] as? Number
        if (allocDelta == null ||
            kotlin.math.abs(allocDelta.toLong()) > (1L shl 48)
        ) bad("measurements.mem.alloc_delta_bytes", "out of range")
        if (str(mem["method"], 1, 64) == null) {
            bad("measurements.mem.method", "required")
        }
        val thermal = m["thermal"] as? Map<*, *>
            ?: bad("measurements.thermal", "thermal must be an object")
        val tBefore = str(thermal["before"], 1, 32)
            ?: bad("measurements.thermal.before", "required")
        val tMid = str(thermal["mid"], 1, 32)
            ?: bad("measurements.thermal.mid", "required")
        val tAfter = str(thermal["after"], 1, 32)
            ?: bad("measurements.thermal.after", "required")
        if (thermal["available"] !is Boolean) {
            bad("measurements.thermal.available", "must be a boolean")
        }

        var memPct: Double? = null
        val mc = raw["x_mem_conditions"] as? Map<*, *>
        val pct = mc?.get("avail_pct_start") as? Number
        if (pct != null) {
            val v = pct.toDouble()
            if (!v.isFinite() || v < 0 || v > 100) {
                bad("x_mem_conditions.avail_pct_start", "must be 0..100")
            }
            memPct = v
        }

        // Throughput self-consistency (same tolerance as requireConsistent).
        val genMs = (m["gen_ms"] as Number).toLong()
        if (genMs > 0) {
            val expected = BenchConfig.tps(
                (m["generated_tokens"] as Number).toInt(), genMs)
            if (kotlin.math.abs(tpsGen - expected) >= 0.01) {
                bad("measurements.tps_gen", "inconsistent with tokens/time")
            }
        }
        val sha = sha256Hex(text.toByteArray(Charsets.UTF_8))
        val summary = Summary(
            runId = runId, sha256 = sha, status = status, error = error,
            appVersion = appVersion, collectedAtUtc = collected,
            deviceModel = deviceModel, sdkInt = sdkInt,
            modelDisplay = display, modelSizeBytes = sizeBytes,
            modelSha256 = modelSha, quantization = quant,
            nCtx = nCtx, nThreads = nThreads, nPredict = nPredict,
            sampler = sampler, workloadId = workloadId,
            promptSha256 = promptSha, tpsGen = tpsGen,
            tpsPrefill = tpsPrefill,
            generatedTokens = (m["generated_tokens"] as Number).toInt(),
            thermalBefore = tBefore, thermalMid = tMid, thermalAfter = tAfter,
            memAvailPctStart = memPct
        )
        return Parsed(summary, raw)
    }

    private fun str(value: Any?, min: Int, max: Int): String? {
        if (value !is String) return null
        if (value.length < min || value.length > max) return null
        return value
    }

    private fun intIn(value: Any?, min: Int, max: Int): Int? {
        if (value !is Number) return null
        val lawful = value is Int || value is Long ||
            (value is Double && value % 1.0 == 0.0)
        if (!lawful) return null
        val v = value.toLong()
        if (v < min || v > max) return null
        return v.toInt()
    }

    private fun longIn(value: Any?, min: Long, max: Long): Long? {
        if (value !is Number) return null
        if (value is Double && !(value % 1.0 == 0.0)) return null
        if (value is Float && !(value % 1.0f == 0.0f)) return null
        val v = value.toLong()
        if (v < min || v > max) return null
        return v
    }

    private fun finiteNonNeg(value: Any?): Double? {
        if (value !is Number) return null
        val v = value.toDouble()
        if (!v.isFinite() || v < 0) return null
        return v
    }

    /** Strict UTC ISO-8601 (yyyy-MM-ddTHH:mm:ss, optional fraction + Z). */
    fun parseIsoUtc(text: String): Long? {
        val match = Regex(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})" +
                "(\\.\\d{1,9})?Z$"
        ).matchEntire(text) ?: return null
        return try {
            val cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"))
            cal.isLenient = false
            cal.clear()
            cal.set(
                match.groupValues[1].toInt(), match.groupValues[2].toInt() - 1,
                match.groupValues[3].toInt(), match.groupValues[4].toInt(),
                match.groupValues[5].toInt(), match.groupValues[6].toInt())
            cal.timeInMillis
        } catch (_: Exception) {
            null
        }
    }

    fun formatIsoUtc(ms: Long): String {
        val cal = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND))
    }
}
