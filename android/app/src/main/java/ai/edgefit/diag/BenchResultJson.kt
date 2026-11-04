package ai.edgefit.diag

/**
 * Benchmark-result JSON serialization. Pure JVM with zero dependencies.
 * Self-consistency rules (status/error pairing, throughput math) are
 * checked by [requireConsistent]; full contract validation lives in
 * scripts/ef_bench_result_01.py.
 */
object BenchResultJson {

    fun requireConsistent(result: BenchResult) {
        require(result.status in BenchContract.STATUSES) { "unknown status" }
        if (result.status == BenchContract.STATUS_COMPLETED) {
            require(result.error == null) { "COMPLETED must carry no error" }
        } else {
            require(!result.error.isNullOrEmpty()) { "non-COMPLETED requires error" }
        }
        val measurements = result.measurements
        if (measurements.genMs > 0) {
            val expected = BenchConfig.tps(measurements.generatedTokens, measurements.genMs)
            require(kotlin.math.abs(measurements.tpsGen - expected) < 0.01) {
                "tps_gen inconsistent"
            }
        }
        if (measurements.prefillMs > 0) {
            val expected = BenchConfig.tps(measurements.prefillTokens, measurements.prefillMs)
            require(kotlin.math.abs(measurements.tpsPrefill - expected) < 0.01) {
                "tps_prefill inconsistent"
            }
        }
    }

    class ParseError(message: String) : Exception(message)

    /**
     * Parses a document produced by [serialize] back into a
     * [BenchResult] (EF-ANDROID-LIFECYCLE-PERSISTENCE-P0-01:
     * completed-result displays survive rotation and
     * process death). Structural only — contract
     * acceptance still belongs to [BenchResultParse].
     * Anything unexpected throws [ParseError]; callers fail
     * closed to an unrestored display, never a partial
     * fabrication.
     */
    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): BenchResult {
        fun bad(field: String): Nothing =
            throw ParseError("malformed $field")
        val root = try {
            SessionJson.parse(text) as? Map<String, Any?>
        } catch (e: SessionJson.JsonError) {
            throw ParseError("malformed JSON: ${e.message}")
        } ?: bad("root")
        if (root["contract"] != BenchContract.CONTRACT) bad("contract")
        if ((root["contract_version"] as? Number)?.toInt() !=
            BenchContract.CONTRACT_VERSION
        ) bad("contract_version")
        val device = root["device"] as? Map<String, Any?> ?: bad("device")
        val model = root["model"] as? Map<String, Any?> ?: bad("model")
        val runtime = root["runtime"] as? Map<String, Any?>
            ?: bad("runtime")
        val config = root["config"] as? Map<String, Any?> ?: bad("config")
        val workload = root["workload"] as? Map<String, Any?>
            ?: bad("workload")
        val measures = root["measurements"] as? Map<String, Any?>
            ?: bad("measurements")
        val mem = measures["mem"] as? Map<String, Any?> ?: bad("mem")
        val thermal = measures["thermal"] as? Map<String, Any?>
            ?: bad("thermal")
        val result = BenchResult(
            runId = root["run_id"] as? String ?: bad("run_id"),
            device = BenchDeviceSnapshot(
                profileRef = device["profile_ref"] as? String
                    ?: bad("device.profile_ref"),
                model = device["model"] as? String ?: bad("device.model"),
                sdkInt = (device["sdk_int"] as? Number)?.toInt()
                    ?: bad("device.sdk_int"),
                supportedAbis = (device["supported_abis"] as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?: bad("device.supported_abis"),
                logicalProcessors =
                    (device["logical_processors"] as? Number)?.toInt()
                        ?: bad("device.logical_processors"),
                totalRamBytes =
                    (device["total_ram_bytes"] as? Number)?.toLong()
                        ?: bad("device.total_ram_bytes")
            ),
            model = BenchModelIdentity(
                displayName = model["display_name"] as? String
                    ?: bad("model.display_name"),
                sizeBytes = (model["size_bytes"] as? Number)?.toLong()
                    ?: bad("model.size_bytes"),
                sha256 = model["sha256"] as? String ?: bad("model.sha256"),
                quantization = model["quantization"] as? String
                    ?: bad("model.quantization"),
                metadata = (model["metadata"] as? Map<String, Any?>)
                    ?.mapValues { it.value?.toString() ?: "" }
                    ?: bad("model.metadata")
            ),
            runtimeVersion = runtime["version"] as? String
                ?: bad("runtime.version"),
            runtimeAbi = runtime["abi"] as? String ?: bad("runtime.abi"),
            config = BenchConfigData(
                nCtx = (config["n_ctx"] as? Number)?.toInt()
                    ?: bad("config.n_ctx"),
                nThreads = (config["n_threads"] as? Number)?.toInt()
                    ?: bad("config.n_threads"),
                nPredict = (config["n_predict"] as? Number)?.toInt()
                    ?: bad("config.n_predict"),
                temperature = (config["temperature"] as? Number)
                    ?.toDouble() ?: bad("config.temperature"),
                sampler = config["sampler"] as? String
                    ?: bad("config.sampler"),
                seed = (config["seed"] as? Number)?.toLong()
                    ?: bad("config.seed"),
                timeoutMs = (config["timeout_ms"] as? Number)?.toLong()
                    ?: bad("config.timeout_ms")
            ),
            workload = BenchWorkload(
                id = workload["id"] as? String ?: bad("workload.id"),
                promptSha256 = workload["prompt_sha256"] as? String
                    ?: bad("workload.prompt_sha256"),
                promptChars = (workload["prompt_chars"] as? Number)
                    ?.toInt() ?: bad("workload.prompt_chars")
            ),
            measurements = BenchMeasurements(
                loadMs = (measures["load_ms"] as? Number)?.toLong()
                    ?: bad("measurements.load_ms"),
                prefillMs = (measures["prefill_ms"] as? Number)?.toLong()
                    ?: bad("measurements.prefill_ms"),
                prefillTokens = (measures["prefill_tokens"] as? Number)
                    ?.toInt() ?: bad("measurements.prefill_tokens"),
                genMs = (measures["gen_ms"] as? Number)?.toLong()
                    ?: bad("measurements.gen_ms"),
                generatedTokens =
                    (measures["generated_tokens"] as? Number)?.toInt()
                        ?: bad("measurements.generated_tokens"),
                tpsGen = (measures["tps_gen"] as? Number)?.toDouble()
                    ?: bad("measurements.tps_gen"),
                tpsPrefill = (measures["tps_prefill"] as? Number)
                    ?.toDouble() ?: bad("measurements.tps_prefill"),
                eosStop = measures["eos_stop"] as? Boolean
                    ?: bad("measurements.eos_stop"),
                mem = BenchMemory(
                    pssBeforeKb = (mem["pss_before_kb"] as? Number)
                        ?.toInt() ?: bad("mem.pss_before_kb"),
                    pssAfterKb = (mem["pss_after_kb"] as? Number)
                        ?.toInt() ?: bad("mem.pss_after_kb"),
                    allocDeltaBytes =
                        (mem["alloc_delta_bytes"] as? Number)?.toLong()
                            ?: bad("mem.alloc_delta_bytes"),
                    method = mem["method"] as? String ?: bad("mem.method")
                ),
                thermal = BenchThermal(
                    before = thermal["before"] as? String
                        ?: bad("thermal.before"),
                    mid = thermal["mid"] as? String ?: bad("thermal.mid"),
                    after = thermal["after"] as? String
                        ?: bad("thermal.after"),
                    available = thermal["available"] as? Boolean
                        ?: bad("thermal.available")
                )
            ),
            status = root["status"] as? String ?: bad("status"),
            error = root["error"] as? String,
            appVersion = root["app_version"] as? String
                ?: bad("app_version"),
            collectedAtUtc = root["collected_at_utc"] as? String
                ?: bad("collected_at_utc"),
            xNativeBuild = if (!root.containsKey("x_native_build")) {
                null
            } else {
                (root["x_native_build"] as? Map<String, Any?>)
                    ?.mapValues { it.value?.toString() ?: "" }
                    ?: bad("x_native_build")
            },
            xMemRecheck = if (!root.containsKey("x_mem_recheck")) {
                null
            } else {
                MemRecheck.fromMap(
                    root["x_mem_recheck"] as? Map<String, Any?>
                        ?: bad("x_mem_recheck"))
                    ?: bad("x_mem_recheck")
            },
            xMemDiag = if (!root.containsKey("x_mem_diag")) {
                null
            } else {
                MemDiag.exportFromMap(
                    root["x_mem_diag"] as? Map<String, Any?>
                        ?: bad("x_mem_diag"))
                    ?: bad("x_mem_diag")
            },
            xMemConditions = if (!root.containsKey("x_mem_conditions")) {
                null
            } else {
                MemConditions.fromMap(
                    root["x_mem_conditions"] as? Map<String, Any?>
                        ?: bad("x_mem_conditions"))
                    ?: bad("x_mem_conditions")
            },
            xFailure = if (!root.containsKey("x_failure")) {
                null
            } else {
                FailureEvidence.fromMap(
                    root["x_failure"] as? Map<String, Any?>
                        ?: bad("x_failure"))
                    ?: bad("x_failure")
            }
        )
        try {
            requireConsistent(result)
        } catch (e: IllegalArgumentException) {
            throw ParseError("inconsistent result: ${e.message}")
        }
        return result
    }

    fun serialize(result: BenchResult): String {
        requireConsistent(result)
        val root = linkedMapOf<String, Any?>(
            "contract" to BenchContract.CONTRACT,
            "contract_version" to BenchContract.CONTRACT_VERSION,
            "run_id" to result.runId,
            "device" to linkedMapOf(
                "profile_ref" to result.device.profileRef,
                "model" to result.device.model,
                "sdk_int" to result.device.sdkInt,
                "supported_abis" to result.device.supportedAbis,
                "logical_processors" to result.device.logicalProcessors,
                "total_ram_bytes" to result.device.totalRamBytes,
                "provenance" to ProfileContract.PROVENANCE_PLATFORM
            ),
            "model" to linkedMapOf(
                "display_name" to result.model.displayName,
                "size_bytes" to result.model.sizeBytes,
                "sha256" to result.model.sha256,
                "quantization" to result.model.quantization,
                "metadata" to result.model.metadata.toSortedMap()
            ),
            "runtime" to linkedMapOf(
                "impl" to BenchContract.RUNTIME_IMPL,
                "version" to result.runtimeVersion,
                "backend" to BenchContract.BACKEND,
                "abi" to result.runtimeAbi
            ),
            "config" to linkedMapOf(
                "n_ctx" to result.config.nCtx,
                "n_threads" to result.config.nThreads,
                "n_predict" to result.config.nPredict,
                "temperature" to result.config.temperature,
                "sampler" to result.config.sampler,
                "seed" to result.config.seed,
                "timeout_ms" to result.config.timeoutMs
            ),
            "workload" to linkedMapOf(
                "id" to result.workload.id,
                "prompt_sha256" to result.workload.promptSha256,
                "prompt_chars" to result.workload.promptChars
            ),
            "measurements" to linkedMapOf(
                "load_ms" to result.measurements.loadMs,
                "prefill_ms" to result.measurements.prefillMs,
                "prefill_tokens" to result.measurements.prefillTokens,
                "gen_ms" to result.measurements.genMs,
                "generated_tokens" to result.measurements.generatedTokens,
                "tps_gen" to result.measurements.tpsGen,
                "tps_prefill" to result.measurements.tpsPrefill,
                "eos_stop" to result.measurements.eosStop,
                "mem" to linkedMapOf(
                    "pss_before_kb" to result.measurements.mem.pssBeforeKb,
                    "pss_after_kb" to result.measurements.mem.pssAfterKb,
                    "alloc_delta_bytes" to result.measurements.mem.allocDeltaBytes,
                    "method" to result.measurements.mem.method
                ),
                "thermal" to linkedMapOf(
                    "before" to result.measurements.thermal.before,
                    "mid" to result.measurements.thermal.mid,
                    "after" to result.measurements.thermal.after,
                    "available" to result.measurements.thermal.available
                )
            ),
            "status" to result.status,
            "error" to result.error,
            "provenance" to BenchContract.PROVENANCE,
            "app_version" to result.appVersion,
            "collected_at_utc" to result.collectedAtUtc
        )
        result.xNativeBuild?.let { root["x_native_build"] = it.toSortedMap() }
        result.xMemRecheck?.let { root["x_mem_recheck"] = MemRecheck.toMap(it) }
        result.xMemDiag?.let { root["x_mem_diag"] = MemDiag.exportToMap(it) }
        result.xMemConditions?.let { root["x_mem_conditions"] = it.toMap() }
        result.xFailure?.let { root["x_failure"] = it.toMap() }
        return render(root)
    }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + escape(value) + "\""
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> {
            require(value.isFinite()) { "non-finite number" }
            value.toString()
        }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, entry) ->
            render(key.toString()) + ":" + render(entry)
        }
        is List<*> -> value.joinToString(",", "[", "]") { render(it) }
        else -> throw IllegalArgumentException("unsupported JSON value: $value")
    }

    private fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (char in text) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char < '\u0020') {
                    out.append(String.format("\\u%04x", char.code))
                } else {
                    out.append(char)
                }
            }
        }
        return out.toString()
    }
}
