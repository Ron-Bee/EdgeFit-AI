package ai.edgefit.diag

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Session <-> BenchRunner integration. Pure JVM: no timing, threading,
 * or native changes — BenchRunner still owns measurement and calls its
 * listener exactly once. This bridge only translates a finished
 * [BenchResult] into the explicit identity + integrity checks of
 * [SessionManager.attachBenchmark], off the caller thread.
 *
 * Source hash is SHA-256 over [BenchResultJson.serialize] bytes, the
 * same bytes the export path writes, so it matches EF-DATA-03's
 * source-hash semantics for the identical run.
 */
object SessionBenchBridge {
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "edgefit-session-io").apply { isDaemon = true }
    }

    /**
     * Rebuilds validated config from a stored map (JSON numbers may
     * be Long). Null when malformed or out of contract ranges.
     */
    fun configData(map: Map<String, Any?>): BenchConfigData? {
        return try {
            fun long(key: String): Long =
                (map[key] as? Number)?.toLong()
                    ?: throw IllegalArgumentException(key)
            val config = BenchConfigData(
                nCtx = long("n_ctx").toInt(),
                nThreads = long("n_threads").toInt(),
                nPredict = long("n_predict").toInt(),
                temperature = (map["temperature"] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("temperature"),
                sampler = map["sampler"] as? String
                    ?: throw IllegalArgumentException("sampler"),
                seed = long("seed"),
                timeoutMs = long("timeout_ms")
            )
            if (BenchConfig.validate(config).isNotEmpty()) null else config
        } catch (_: Exception) {
            null
        }
    }

    /** Canonical config spelling, shared by creation and attach. */
    fun configMap(config: BenchConfigData): Map<String, Any?> = linkedMapOf(
        "n_ctx" to config.nCtx,
        "n_threads" to config.nThreads,
        "n_predict" to config.nPredict,
        "temperature" to config.temperature,
        "sampler" to config.sampler,
        "seed" to config.seed,
        "timeout_ms" to config.timeoutMs
    )

    /** Bounded measured values retained in the session record. */
    fun measurementsMap(measurements: BenchMeasurements): Map<String, Any?> =
        measurementsMap(measurements, null, null, null)

    /**
     * Full variant: runtime + native-build provenance for agent
     * comparability. Pre-agent records omit the runtime_* keys.
     */
    fun measurementsMap(
        measurements: BenchMeasurements,
        runtimeVersion: String?,
        runtimeAbi: String?,
        nativeBuild: Map<String, String>?
    ): Map<String, Any?> = linkedMapOf(
        "load_ms" to measurements.loadMs,
        "prefill_ms" to measurements.prefillMs,
        "prefill_tokens" to measurements.prefillTokens,
        "gen_ms" to measurements.genMs,
        "generated_tokens" to measurements.generatedTokens,
        "tps_gen" to measurements.tpsGen,
        "tps_prefill" to measurements.tpsPrefill,
        "thermal_before" to measurements.thermal.before,
        "thermal_mid" to measurements.thermal.mid,
        "thermal_after" to measurements.thermal.after,
        "runtime_version" to runtimeVersion,
        "runtime_abi" to runtimeAbi,
        "native_variant" to nativeBuild?.get("variant"),
        "native_opt" to nativeBuild?.get("native_opt")
    )

    fun sourceHash(result: BenchResult): String =
        BenchConfig.sha256Hex(
            BenchResultJson.serialize(result).toByteArray(Charsets.UTF_8))

    /**
     * Attaches a finished run. Idempotent: a repeated delivery of the
     * SAME run returns the current record; a different run, or any
     * identity mismatch, still throws.
     */
    fun attach(
        manager: SessionManager,
        sessionId: String,
        result: BenchResult
    ): SessionRecord {
        return try {
            manager.attachBenchmark(
                id = sessionId,
                runId = result.runId,
                sourceSha256 = sourceHash(result),
                status = result.status,
                measurements = measurementsMap(
                    result.measurements, result.runtimeVersion,
                    result.runtimeAbi, result.xNativeBuild),
                collectedAtUtc = result.collectedAtUtc,
                modelSha256 = result.model.sha256,
                workloadId = result.workload.id,
                config = configMap(result.config),
                deviceModel = result.device.model
            )
        } catch (e: SessionManager.SessionError) {
            // Same run already attached (repeat delivery after a state
            // advance) is success; anything else rethrows.
            val current = try {
                manager.get(sessionId)
            } catch (_: Exception) {
                throw e
            }
            @Suppress("UNCHECKED_CAST")
            val bench = current.benchmark as? Map<String, Any?>
            if (bench?.get("run_id") == result.runId) current else throw e
        }
    }

    /**
     * Same attach on a dedicated daemon thread: disk I/O never blocks
     * the native worker or the UI thread. Callback runs on the I/O
     * thread; the activity must marshal to main.
     */
    fun attachAsync(
        manager: SessionManager,
        sessionId: String,
        result: BenchResult,
        onDone: (Result<SessionRecord>) -> Unit
    ) {
        io.execute {
            onDone(
                try {
                    Result.success(attach(manager, sessionId, result))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            )
        }
    }
}
