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
