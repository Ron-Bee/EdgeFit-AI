package ai.edgefit.diag

/**
 * Benchmark-result contract v1 model. Pure JVM: no Android imports.
 * Mirrors docs/bench-result-contract.md.
 */
object BenchContract {
    const val CONTRACT = "edgefit-bench-result"
    const val CONTRACT_VERSION = 1
    const val WORKLOAD_ID = "edgefit-prompt-v1"
    const val PROVENANCE = "on_device_measurement"
    const val SAMPLER = "greedy"
    const val RUNTIME_IMPL = "llama.cpp"
    const val BACKEND = "cpu"

    // Fixed prompt workload. Byte-identical on every run.
    const val WORKLOAD_PROMPT =
        "Explain in exactly three short sentences how CPU thread count " +
        "affects autoregressive text generation speed on a phone."

    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_CANCELLED = "CANCELLED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_TIMEOUT = "TIMEOUT"
    val STATUSES = setOf(STATUS_COMPLETED, STATUS_CANCELLED, STATUS_FAILED, STATUS_TIMEOUT)
}

data class BenchDeviceSnapshot(
    val profileRef: String,
    val model: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val logicalProcessors: Int,
    val totalRamBytes: Long
)

data class BenchModelIdentity(
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val quantization: String,
    val metadata: Map<String, String>
)

data class BenchConfigData(
    val nCtx: Int,
    val nThreads: Int,
    val nPredict: Int,
    val temperature: Double,
    val sampler: String,
    val seed: Long,
    val timeoutMs: Long
)

data class BenchWorkload(
    val id: String,
    val promptSha256: String,
    val promptChars: Int
)

data class BenchMemory(
    val pssBeforeKb: Int,
    val pssAfterKb: Int,
    val allocDeltaBytes: Long,
    val method: String
)

data class BenchThermal(
    val before: String,
    val mid: String,
    val after: String,
    val available: Boolean
)

data class BenchMeasurements(
    val loadMs: Long,
    val prefillMs: Long,
    val prefillTokens: Int,
    val genMs: Long,
    val generatedTokens: Int,
    val tpsGen: Double,
    val tpsPrefill: Double,
    val eosStop: Boolean,
    val mem: BenchMemory,
    val thermal: BenchThermal
)

data class BenchResult(
    val runId: String,
    val device: BenchDeviceSnapshot,
    val model: BenchModelIdentity,
    val runtimeVersion: String,
    val runtimeAbi: String,
    val config: BenchConfigData,
    val workload: BenchWorkload,
    val measurements: BenchMeasurements,
    val status: String,
    val error: String?,
    val appVersion: String,
    val collectedAtUtc: String,
    // Optional native-build provenance, serialized as x_native_build.
    // Null on results predating EF-BENCH-02; never backfilled.
    val xNativeBuild: Map<String, String>? = null
)
