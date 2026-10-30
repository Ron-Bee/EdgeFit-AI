package ai.edgefit.diag

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Live Meta prediction + follow-up orchestration over [SessionManager].
 * Blocking calls — the activity runs them off the UI thread. Owns the
 * paid-request guards: no key means no transport call, one purpose per
 * session is ever in flight, and every failure surfaces before (not
 * after) persistence. JVM-testable via [MetaTransport] fakes.
 */
class MetaSessionClient(
    val manager: SessionManager,
    val transport: MetaTransport,
    val keys: MetaKeyVault
) {
    class ClientError(val code: String, message: String) : Exception(message)

    companion object {
        /** EF-FOLLOWUP-02 D4: max prior runs per follow-up. */
        const val MAX_SERIES_PRIORS = 4
    }

    data class ProposalSpec(
        val parentId: String,
        val packText: String,
        val currentConfig: BenchConfigData,
        val nextSeq: Int,
        val modelSha: String,
        val workloadId: String,
        val attemptsUsed: Int,
        val attemptsMax: Int,
        val allowedRunIds: Set<String>,
        val deviceMaxThreads: Int,
        /**
         * EF-FOLLOWUP-02: executed config per cited run,
         * for repeat verification. Empty offers no repeats.
         */
        val repeatConfigs: Map<String, BenchConfigData> = emptyMap(),
        /**
         * EF-MEM-03 recovery: failed-run ids eligible for
         * citation, their rendered evidence, and the prompt
         * mode. Empty ids refuse recovery proposals.
         */
        val recoveryRunIds: Set<String> = emptySet(),
        val failureText: String = "",
        val recoveryMode: Boolean = false
    )

    data class PredictionSpec(
        val sessionId: String,
        val deviceLines: List<String>,
        val modelDisplay: String,
        val modelSha: String,
        val modelSizeBytes: Long,
        val quantization: String,
        val workloadId: String,
        val promptSha: String,
        val config: BenchConfigData,
        val runtimeVersion: String,
        val runtimeAbi: String
    )

    private val flightLock = Any()
    private val inFlight = mutableSetOf<String>()

    private fun <T> guardedFlight(key: String, block: () -> T): T {
        synchronized(flightLock) {
            if (!inFlight.add(key)) {
                throw ClientError("IN_PROGRESS", "request already in flight")
            }
        }
        try {
            return block()
        } finally {
            synchronized(flightLock) { inFlight.remove(key) }
        }
    }

    /**
     * Prior COMPLETED runs on this device for the same model, workload,
     * and comparable config — the only historical evidence this client
     * may cite. Newest first, bounded.
     */
    @Suppress("UNCHECKED_CAST")
    fun priorEvidence(
        modelSha: String,
        workloadId: String,
        config: BenchConfigData
    ): List<Triple<String, Double, Double>> {
        val out = mutableListOf<Triple<String, Double, Double>>()
        for (record in manager.listSessions()) {
            if (out.size >= MetaApi.MAX_PRIOR_RUNS) break
            if (record.modelSha256.lowercase() != modelSha.lowercase()) continue
            if (record.workloadId != workloadId) continue
            val bench = record.benchmark as? Map<String, Any?> ?: continue
            if (bench["status"] != BenchContract.STATUS_COMPLETED) continue
            if (!configMatches(record.config, config)) continue
            val measurements = bench["measurements"] as? Map<String, Any?>
                ?: continue
            val gen = (measurements["tps_gen"] as? Number)?.toDouble()
                ?: continue
            val prefill = (measurements["tps_prefill"] as? Number)?.toDouble()
                ?: continue
            if (!gen.isFinite() || !prefill.isFinite()) continue
            out.add(Triple(bench["run_id"] as? String ?: continue, gen, prefill))
        }
        return out
    }

    private fun configMatches(
        stored: Map<String, Any?>,
        config: BenchConfigData
    ): Boolean {
        val want = SessionBenchBridge.configMap(config)
        for (key in listOf("n_ctx", "n_threads", "n_predict")) {
            if ((stored[key] as? Number)?.toLong() !=
                (want[key] as? Number)?.toLong()
            ) {
                return false
            }
        }
        return stored["sampler"] == want["sampler"]
    }

    /**
     * Requests, validates, and durably commits a live prediction into a
     * PREDICTION_PENDING session. Any failure leaves the session
     * unrecorded (retryable) and manufactures nothing.
     */
    fun requestPrediction(spec: PredictionSpec): SessionRecord =
        guardedFlight("predict:${spec.sessionId}") {
            val apiKey = keys.getKey()
                ?: throw ClientError("NO_KEY", "no API key configured")
            val prior = priorEvidence(spec.modelSha, spec.workloadId, spec.config)
            val body = MetaApi.requestBody(
                MetaApi.predictionSystem(),
                MetaApi.predictionUser(
                    spec.deviceLines, spec.modelDisplay, spec.modelSha,
                    spec.modelSizeBytes, spec.quantization, spec.workloadId,
                    spec.promptSha, spec.config, spec.runtimeVersion,
                    spec.runtimeAbi, prior
                )
            )
            val text = postForText(apiKey, body)
            val raw = try {
                MetaApi.rawSlice(text)
            } catch (e: MetaApi.MetaError) {
                throw ClientError(e.code, e.message ?: e.code)
            }
            val payload = MetaApi.extractJsonObject(text)
                ?: throw ClientError("BAD_RESPONSE", "no JSON object in reply")
            val valid = try {
                MetaApi.validatePrediction(
                    payload, raw, spec.workloadId, spec.modelSha,
                    prior.map { it.first }.toSet()
                )
            } catch (e: MetaApi.MetaError) {
                throw ClientError(e.code, e.message ?: e.code)
            }
            manager.recordPrediction(
                spec.sessionId, valid.genTps, valid.prefillTps, valid.rawJson,
                SessionManager.SOURCE_LIVE_META, valid.evidenceCited
            )
        }

    /**
     * Requests and validates one agent proposal. Persists nothing:
     * the caller ([AgentManager.storeProposal]) stores or terminally
     * declines. Same guards as predictions: keyed, single-flight,
     * no retries.
     */
    fun requestProposal(spec: ProposalSpec): AgentApi.ValidProposal =
        guardedFlight("proposal:${spec.parentId}") {
            val apiKey = keys.getKey()
                ?: throw ClientError("NO_KEY", "no API key configured")
            val body = MetaApi.requestBody(
                AgentApi.proposalSystem(
                    spec.deviceMaxThreads, spec.recoveryMode),
                AgentApi.proposalUser(
                    spec.packText, spec.currentConfig, spec.nextSeq,
                    spec.modelSha, spec.workloadId, spec.attemptsUsed,
                    spec.attemptsMax, spec.failureText
                )
            )
            val text = postForText(apiKey, body)
            val raw = try {
                MetaApi.rawSlice(text)
            } catch (e: MetaApi.MetaError) {
                throw ClientError(e.code, e.message ?: e.code)
            }
            if (raw.toByteArray(Charsets.UTF_8).size >
                AgentApi.MAX_PROPOSAL_RAW_BYTES
            ) {
                throw ClientError("REPLY_TOO_LARGE", "reply exceeds bound")
            }
            val payload = MetaApi.extractJsonObject(text)
                ?: throw ClientError("BAD_RESPONSE", "no JSON object in reply")
            try {
                AgentApi.validateProposal(
                    payload, raw,
                    AgentApi.ProposalExpectation(
                        modelSha = spec.modelSha,
                        workloadId = spec.workloadId,
                        currentConfig = spec.currentConfig,
                        nextSeq = spec.nextSeq,
                        allowedRunIds = spec.allowedRunIds,
                        deviceMaxThreads = spec.deviceMaxThreads,
                        repeatConfigs = spec.repeatConfigs,
                        recoveryRunIds = spec.recoveryRunIds
                    )
                )
            } catch (e: AgentApi.AgentError) {
                throw ClientError(e.code, e.message ?: e.code)
            }
        }

    /**
     * Requests, validates, and durably commits a follow-up assessment
     * into a FOLLOWUP_PENDING session with a stored comparison.
     */
    @Suppress("UNCHECKED_CAST")
    fun requestFollowUp(sessionId: String): SessionRecord =
        guardedFlight("followup:$sessionId") {
            val record = manager.get(sessionId)
            val comparison = record.comparison as? Map<String, Any?>
                ?: throw ClientError("NO_COMPARISON", "no stored comparison")
            if (comparison["comparable"] != true) {
                throw ClientError("NO_COMPARISON", "evidence noncomparable")
            }
            val bench = record.benchmark as? Map<String, Any?>
                ?: throw ClientError("NO_COMPARISON", "no attached benchmark")
            val prediction = record.prediction as? Map<String, Any?>
                ?: throw ClientError("NO_COMPARISON", "no stored prediction")
            val measurements = bench["measurements"] as? Map<String, Any?>
                ?: throw ClientError("NO_COMPARISON", "no measurements")
            val apiKey = keys.getKey()
                ?: throw ClientError("NO_KEY", "no API key configured")
            val genSigned = (comparison["gen_signed"] as? Number)?.toDouble()
                ?: throw ClientError("NO_COMPARISON", "bad stored errors")
            val genPct = (comparison["gen_pct"] as? Number)?.toDouble()
                ?: throw ClientError("NO_COMPARISON", "bad stored errors")
            val runId = bench["run_id"] as? String
                ?: throw ClientError("NO_COMPARISON", "run id missing")
            val measuredGen = num(measurements, "tps_gen")
            val body = MetaApi.requestBody(
                MetaApi.followUpSystem(),
                MetaApi.followUpUser(
                    runId = runId,
                    modelDisplay = record.modelDisplay,
                    workloadId = record.workloadId,
                    config = storedConfig(record),
                    predictedGen = num(prediction, "predicted_gen_tps"),
                    predictedPrefill = num(prediction, "predicted_prefill_tps"),
                    measuredGen = measuredGen,
                    measuredPrefill = num(measurements, "tps_prefill"),
                    genSigned = genSigned,
                    genPct = genPct,
                    prefillSigned = (comparison["prefill_signed"] as? Number)
                        ?.toDouble() ?: Double.NaN,
                    prefillPct = (comparison["prefill_pct"] as? Number)
                        ?.toDouble() ?: Double.NaN,
                    thermalBefore = str(measurements, "thermal_before"),
                    thermalMid = str(measurements, "thermal_mid"),
                    thermalAfter = str(measurements, "thermal_after"),
                    memMethod = BenchRunner.MEM_METHOD,
                    seriesPriors = seriesPriors(
                        sessionId, runId, measuredGen)
                )
            )
            val text = postForText(apiKey, body)
            val raw = try {
                MetaApi.rawSlice(text)
            } catch (e: MetaApi.MetaError) {
                throw ClientError(e.code, e.message ?: e.code)
            }
            val payload = MetaApi.extractJsonObject(text)
                ?: throw ClientError("BAD_RESPONSE", "no JSON object in reply")
            val valid = try {
                MetaApi.validateFollowUp(payload, raw, runId, genSigned, genPct)
            } catch (e: MetaApi.MetaError) {
                throw ClientError(e.code, e.message ?: e.code)
            }
            manager.recordRecommendation(
                sessionId,
                linkedMapOf(
                    "direction" to valid.direction,
                    "summary" to valid.summary,
                    "limitations" to valid.limitations,
                    "supports_conclusion" to valid.supportsConclusion,
                    "next_experiment" to valid.nextExperiment,
                    "proposal_valid" to valid.proposalValid,
                    "proposal_rejection" to valid.proposalRejection,
                    "counterargument" to valid.counterargument,
                    "run_id" to runId,
                    "raw_json" to valid.rawJson
                ),
                "live_meta ${MetaApi.MODEL}"
            )
        }

    private fun num(map: Map<String, Any?>, key: String): Double =
        (map[key] as? Number)?.toDouble()
            ?: throw ClientError("NO_COMPARISON", "stored $key missing")

    private fun str(map: Map<String, Any?>, key: String): String =
        map[key] as? String ?: "unknown"

    /**
     * EF-FOLLOWUP-02 D4: bounded prior same-configuration
     * runs for this follow-up, oldest first, current run
     * excluded. Host-computed current-minus-prior deltas;
     * NaN when the prior value is missing or non-positive.
     */
    private fun seriesPriors(
        sessionId: String,
        currentRunId: String,
        measuredGen: Double
    ): List<MetaApi.SeriesPrior> {
        return try {
            manager.findSeriesRuns(sessionId)
        } catch (_: Exception) {
            return emptyList()
        }.mapNotNull { other ->
            @Suppress("UNCHECKED_CAST")
            val bench = other.benchmark as? Map<String, Any?>
                ?: return@mapNotNull null
            val otherRun = bench["run_id"] as? String
                ?: return@mapNotNull null
            if (otherRun == currentRunId) return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val meas = bench["measurements"] as? Map<String, Any?>
                ?: return@mapNotNull null
            val priorGen = (meas["tps_gen"] as? Number)?.toDouble()
                ?: return@mapNotNull null
            val priorPrefill =
                (meas["tps_prefill"] as? Number)?.toDouble()
                    ?: return@mapNotNull null
            val delta = if (priorGen.isFinite() &&
                measuredGen.isFinite()
            ) {
                measuredGen - priorGen
            } else {
                Double.NaN
            }
            val pct = if (priorGen.isFinite() && priorGen > 0 &&
                delta.isFinite()
            ) {
                delta / priorGen * 100.0
            } else {
                Double.NaN
            }
            MetaApi.SeriesPrior(
                runId = otherRun,
                collectedAtUtc =
                    bench["collected_at_utc"] as? String ?: "unknown",
                genTps = priorGen,
                prefillTps = priorPrefill,
                thermalAfter = meas["thermal_after"] as? String
                    ?: "unknown",
                deltaGen = delta,
                deltaPct = pct
            )
        }.takeLast(MAX_SERIES_PRIORS)
    }

    private fun storedConfig(record: SessionRecord): BenchConfigData {
        val config = record.config
        fun long(key: String): Long = (config[key] as? Number)?.toLong()
            ?: throw ClientError("NO_COMPARISON", "stored config missing")
        return BenchConfigData(
            nCtx = long("n_ctx").toInt(),
            nThreads = long("n_threads").toInt(),
            nPredict = long("n_predict").toInt(),
            temperature = (config["temperature"] as? Number)?.toDouble()
                ?: throw ClientError("NO_COMPARISON", "stored config missing"),
            sampler = config["sampler"] as? String
                ?: throw ClientError("NO_COMPARISON", "stored config missing"),
            seed = long("seed"),
            timeoutMs = long("timeout_ms")
        )
    }

    /**
     * One POST, no retries. Maps transport and HTTP failures to stable
     * codes with key-scrubbed, bounded detail.
     */
    private fun postForText(apiKey: String, body: String): String {
        val result = try {
            transport.post(apiKey, body)
        } catch (e: MetaApi.MetaError) {
            throw ClientError(e.code, e.message ?: e.code)
        } catch (e: UnknownHostException) {
            throw ClientError("OFFLINE", "no route to ${MetaApi.HOST}")
        } catch (e: ConnectException) {
            throw ClientError("OFFLINE", "connection refused/unreachable")
        } catch (e: NoRouteToHostException) {
            throw ClientError("OFFLINE", "no route to ${MetaApi.HOST}")
        } catch (e: SocketTimeoutException) {
            throw ClientError("TIMEOUT", "request timed out")
        } catch (e: SSLException) {
            throw ClientError("TLS_ERROR", "secure connection failed")
        } catch (e: IOException) {
            throw ClientError("NETWORK_ERROR", scrub(e.message, apiKey))
        }
        if (result.status !in 200..299) {
            val code = MetaApi.classifyStatus(result.status)
            val detail = scrub(
                result.body.take(MetaApi.MAX_ERROR_PREVIEW), apiKey)
            val message = when (code) {
                "INVALID_KEY" -> "API key rejected (HTTP ${result.status})"
                "RATE_LIMITED" -> "rate limited (HTTP 429); try later"
                "SERVER_ERROR" ->
                    "provider error (HTTP ${result.status}); try later"
                else -> "request failed (HTTP ${result.status}): $detail"
            }
            throw ClientError(code, message)
        }
        val parsed = try {
            SessionJson.parse(result.body)
        } catch (_: SessionJson.JsonError) {
            throw ClientError("BAD_RESPONSE", "reply is not JSON")
        }
        val text = MetaApi.extractText(parsed)
        if (text.isBlank()) {
            throw ClientError("BAD_RESPONSE", "reply carries no text")
        }
        return text
    }

    private fun scrub(message: String?, apiKey: String): String =
        MetaApi.redact(message ?: "network error", apiKey)
}
