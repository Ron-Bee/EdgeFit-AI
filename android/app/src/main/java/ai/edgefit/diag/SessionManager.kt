package ai.edgefit.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Session lifecycle enforcement over [SessionStore]. Owns state
 * transitions, prediction-before-measurement integrity, benchmark
 * association, deterministic comparison, and recovery. No Android
 * framework types: takes a store rooted anywhere, so the whole
 * manager is JVM-testable with temp directories.
 *
 * Timestamps are device wall-clock UTC. They establish LOCAL
 * consistency (prediction persisted before measurement attached),
 * never trusted time or tamper-proof hardware attestation.
 */
class SessionManager(
    filesDir: File,
    private val clock: () -> String = { utcNow() }
) {
    class SessionError(message: String) : Exception(message)

    companion object {
        const val SOURCE_LIVE_META = "live_meta"
        const val SOURCE_OFFLINE_TEST = "offline_test"
        const val SOURCE_REPLAY = "historical_replay"
        val SOURCES = setOf(SOURCE_LIVE_META, SOURCE_OFFLINE_TEST, SOURCE_REPLAY)

        fun utcNow(): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date())
        }
    }

    val store = SessionStore(filesDir)

    // Every public op is @Synchronized: dialog actions on the UI thread
    // race benchmark attach on the session I/O thread, and each op is a
    // file-backed read-modify-write that must be atomic.

    // ---- lifecycle ----

    @Synchronized
    fun createSession(
        mode: String,
        deviceModel: String?,
        profileSha256: String?,
        modelSha256: String,
        modelSizeBytes: Long,
        modelDisplay: String,
        workloadId: String,
        promptSha256: String,
        config: Map<String, Any?>,
        budgetMax: Int = 5,
        parentSessionId: String? = null,
        experimentSeq: Int? = null
    ): SessionRecord {
        if (mode !in SessionStore.MODES) throw SessionError("unknown mode $mode")
        if (!SessionStore.isHex64(modelSha256)) {
            throw SessionError("model_sha256 must be 64 hex")
        }
        if (profileSha256 != null && !SessionStore.isHex64(profileSha256)) {
            throw SessionError("profile_sha256 must be 64 hex")
        }
        if (!SessionStore.isHex64(promptSha256)) {
            throw SessionError("prompt_sha256 must be 64 hex")
        }
        if (modelSizeBytes <= 0) throw SessionError("model size must be > 0")
        if (parentSessionId != null && !SessionStore.isValidId(parentSessionId)) {
            throw SessionError("parent session id malformed")
        }
        if (experimentSeq != null && experimentSeq < 1) {
            throw SessionError("experiment seq must be >= 1")
        }
        if ((parentSessionId == null) != (experimentSeq == null)) {
            throw SessionError("parent link and seq travel together")
        }
        val now = clock()
        val record = SessionRecord(
            sessionId = SessionStore.newSessionId(),
            mode = mode,
            state = SessionState.SESSION_CREATED,
            createdAtUtc = now,
            updatedAtUtc = now,
            history = listOf(mapOf(
                "state" to SessionState.SESSION_CREATED,
                "at" to now, "cause" to "created")),
            deviceModel = deviceModel,
            profileSha256 = profileSha256,
            modelSha256 = modelSha256.lowercase(),
            modelSizeBytes = modelSizeBytes,
            modelDisplay = modelDisplay,
            workloadId = workloadId,
            promptSha256 = promptSha256.lowercase(),
            config = config,
            budgetMax = budgetMax,
            parentSessionId = parentSessionId,
            experimentSeq = experimentSeq
        )
        return store.save(record)
    }

    @Synchronized
    fun requestPrediction(id: String): SessionRecord =
        transition(id, SessionState.PREDICTION_PENDING, "prediction requested")

    /**
     * EF-FOLLOWUP-02 repeat: a NEW linked record that carries the
     * source prediction (original timestamp immutable, carried-from
     * provenance added) for one new physical run. The source record
     * is never modified. Eligible sources have a recorded prediction
     * AND an attached benchmark (completed or failed — a failed
     * source makes this a retry, still a new measurement, never a
     * replay). Agent repeats additionally link parent + seq.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun createRepeat(
        sourceId: String,
        deviceModel: String?,
        profileSha256: String?,
        parentSessionId: String? = null,
        experimentSeq: Int? = null
    ): SessionRecord {
        val source = store.load(sourceId)
        val prediction = source.prediction as? Map<String, Any?>
            ?: throw SessionError("repeat source has no prediction")
        val bench = source.benchmark as? Map<String, Any?>
            ?: throw SessionError("repeat source has no benchmark")
        val sourceRunId = bench["run_id"] as? String
            ?: throw SessionError("repeat source run id missing")
        if (!SessionStore.isValidRunId(sourceRunId)) {
            throw SessionError("repeat source run id malformed")
        }
        if (source.mode == "agent" && source.parentSessionId == null) {
            throw SessionError("cannot repeat an agent parent")
        }
        if ((parentSessionId == null) != (experimentSeq == null)) {
            throw SessionError("parent link and seq travel together")
        }
        val gen = (prediction["predicted_gen_tps"] as? Number)?.toDouble()
            ?: throw SessionError("repeat source prediction malformed")
        val prefill =
            (prediction["predicted_prefill_tps"] as? Number)?.toDouble()
                ?: throw SessionError("repeat source prediction malformed")
        val sourceType = prediction["source_type"] as? String
            ?: throw SessionError("repeat source type missing")
        val now = clock()
        val created = SessionRecord(
            sessionId = SessionStore.newSessionId(),
            mode = source.mode,
            state = SessionState.SESSION_CREATED,
            createdAtUtc = now,
            updatedAtUtc = now,
            history = listOf(mapOf(
                "state" to SessionState.SESSION_CREATED,
                "at" to now, "cause" to "repeat of $sourceId created")),
            deviceModel = deviceModel,
            profileSha256 = profileSha256,
            modelSha256 = source.modelSha256,
            modelSizeBytes = source.modelSizeBytes,
            modelDisplay = source.modelDisplay,
            workloadId = source.workloadId,
            promptSha256 = source.promptSha256,
            config = source.config,
            parentSessionId = parentSessionId,
            experimentSeq = experimentSeq,
            repeatOfSessionId = sourceId,
            repeatOfRunId = sourceRunId
        )
        store.save(created)
        requestPrediction(created.sessionId)
        return recordPrediction(
            created.sessionId, gen, prefill,
            prediction["raw_json"] as? String ?: "{}",
            sourceType,
            (prediction["evidence_ids"] as? List<*>)
                ?.mapNotNull { it as? String } ?: emptyList(),
            createdAtUtc = prediction["created_at_utc"] as? String
                ?: throw SessionError("repeat source timestamp missing"),
            carriedFromSession = sourceId,
            carriedFromRun = sourceRunId,
            carriedAtUtc = now
        )
    }

    /**
     * EF-MEM-03 durable config-delta note for recovery
     * history. Pure and unit-tested: identical configs
     * read as an explicit retry, never as an adjustment.
     */
    fun configDeltaNote(
        from: BenchConfigData,
        to: BenchConfigData
    ): String {
        if (from == to) return "same config retry"
        val parts = mutableListOf<String>()
        if (from.nCtx != to.nCtx) {
            parts.add("ctx ${from.nCtx}→${to.nCtx}")
        }
        if (from.nThreads != to.nThreads) {
            parts.add("threads ${from.nThreads}→${to.nThreads}")
        }
        if (from.nPredict != to.nPredict) {
            parts.add("tokens ${from.nPredict}→${to.nPredict}")
        }
        if (from.temperature != to.temperature) {
            parts.add("temperature adjusted")
        }
        if (from.sampler != to.sampler) {
            parts.add("sampler ${from.sampler}→${to.sampler}")
        }
        return "config adjusted (${parts.joinToString(", ")})"
    }

    /**
     * EF-MEM-03 recovery: a NEW authorized attempt after an
     * uncompleted run. Unlike [createRepeat] (identical
     * config, COMPLETED source), the source run must be
     * FAILED/TIMEOUT/CANCELLED and the caller supplies a
     * host-validated config (adjusted or explicit retry)
     * plus the prediction payload to record. The source
     * record is never reopened or rewritten; the new
     * record links via recovery_of_* and carries the
     * source prediction with carried-from provenance when
     * the caller passes the source values back.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun createRecovery(
        sourceId: String,
        deviceModel: String?,
        profileSha256: String?,
        config: Map<String, Any?>,
        gen: Double,
        prefill: Double,
        rawJson: String,
        sourceType: String,
        evidenceIds: List<String>,
        carriedCreatedAtUtc: String? = null,
        parentSessionId: String? = null,
        experimentSeq: Int? = null
    ): SessionRecord {
        val source = store.load(sourceId)
        val bench = source.benchmark as? Map<String, Any?>
            ?: throw SessionError("recovery source has no benchmark")
        val sourceRunId = bench["run_id"] as? String
            ?: throw SessionError("recovery source run id missing")
        if (!SessionStore.isValidRunId(sourceRunId)) {
            throw SessionError("recovery source run id malformed")
        }
        val status = bench["status"] as? String
        if (status != BenchContract.STATUS_FAILED &&
            status != BenchContract.STATUS_TIMEOUT &&
            status != BenchContract.STATUS_CANCELLED
        ) {
            throw SessionError(
                "recovery needs an uncompleted run (got $status)")
        }
        if (source.mode == "agent" && source.parentSessionId == null) {
            throw SessionError("cannot recover an agent parent")
        }
        if ((parentSessionId == null) != (experimentSeq == null)) {
            throw SessionError("parent link and seq travel together")
        }
        val parsed = SessionBenchBridge.configData(config)
            ?: throw SessionError("recovery config invalid")
        if (BenchConfig.validate(parsed).isNotEmpty()) {
            throw SessionError("recovery config out of bounds")
        }
        val sourceParsed =
            SessionBenchBridge.configData(source.config)
        val delta = if (sourceParsed == null) {
            "config unparsed on source"
        } else {
            configDeltaNote(sourceParsed, parsed)
        }
        val now = clock()
        val created = SessionRecord(
            sessionId = SessionStore.newSessionId(),
            mode = source.mode,
            state = SessionState.SESSION_CREATED,
            createdAtUtc = now,
            updatedAtUtc = now,
            history = listOf(mapOf(
                "state" to SessionState.SESSION_CREATED,
                "at" to now,
                "cause" to "recovery of $sourceId " +
                    "($sourceRunId) created; $delta")),
            deviceModel = deviceModel,
            profileSha256 = profileSha256,
            modelSha256 = source.modelSha256,
            modelSizeBytes = source.modelSizeBytes,
            modelDisplay = source.modelDisplay,
            workloadId = source.workloadId,
            promptSha256 = source.promptSha256,
            config = SessionBenchBridge.configMap(parsed),
            parentSessionId = parentSessionId,
            experimentSeq = experimentSeq,
            recoveryOfSessionId = sourceId,
            recoveryOfRunId = sourceRunId
        )
        store.save(created)
        requestPrediction(created.sessionId)
        return if (carriedCreatedAtUtc != null) {
            recordPrediction(
                created.sessionId, gen, prefill, rawJson,
                sourceType, evidenceIds,
                createdAtUtc = carriedCreatedAtUtc,
                carriedFromSession = sourceId,
                carriedFromRun = sourceRunId,
                carriedAtUtc = now)
        } else {
            recordPrediction(created.sessionId, gen, prefill,
                rawJson, sourceType, evidenceIds)
        }
    }

    /**
     * Same-configuration COMPLETED series for a record: same
     * model, workload, config, and runtime identity, ordered by
     * collection time. Powers repeat comparison and bounded
     * follow-up priors. Read-only; never mutates the store.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun findSeriesRuns(id: String): List<SessionRecord> {
        val record = store.load(id)
        fun stampOf(other: SessionRecord): String {
            val bench = other.benchmark as? Map<String, Any?>
            return bench?.get("collected_at_utc") as? String ?: ""
        }
        return store.listIds().mapNotNull {
            try {
                store.load(it)
            } catch (_: Exception) {
                null
            }
        }.filter { other ->
            val bench = other.benchmark as? Map<String, Any?> ?: return@filter false
            if (bench["status"] != BenchContract.STATUS_COMPLETED) {
                return@filter false
            }
            if (other.modelSha256 != record.modelSha256 ||
                other.workloadId != record.workloadId
            ) {
                return@filter false
            }
            if (!configEqualsSeries(other.config, record.config)) {
                return@filter false
            }
            if (runtimeKeysOf(other) != runtimeKeysOf(record)) {
                return@filter false
            }
            true
        }.sortedBy { stampOf(it) }
    }

    private fun configEqualsSeries(
        a: Map<String, Any?>,
        b: Map<String, Any?>
    ): Boolean {
        for (key in listOf("n_ctx", "n_threads", "n_predict")) {
            val x = (a[key] as? Number)?.toLong()
            val y = (b[key] as? Number)?.toLong()
            if (x == null || y == null || x != y) return false
        }
        return a["sampler"] == b["sampler"]
    }

    @Suppress("UNCHECKED_CAST")
    private fun runtimeKeysOf(
        record: SessionRecord
    ): Triple<String?, String?, String?> {
        val bench = record.benchmark as? Map<String, Any?>
        val measurements = bench?.get("measurements") as? Map<String, Any?>
        return Triple(
            measurements?.get("runtime_version") as? String,
            measurements?.get("runtime_abi") as? String,
            measurements?.get("native_variant") as? String
        )
    }

    /**
     * Records the immutable original prediction. Only legal from
     * PREDICTION_PENDING; afterwards the values can never change.
     */
    @Synchronized
    fun recordPrediction(
        id: String,
        predictedGenTps: Double,
        predictedPrefillTps: Double,
        rawJson: String,
        sourceType: String,
        evidenceIds: List<String>,
        createdAtUtc: String = clock(),
        carriedFromSession: String? = null,
        carriedFromRun: String? = null,
        carriedAtUtc: String? = null
    ): SessionRecord {
        if (sourceType !in SOURCES) throw SessionError("unknown source $sourceType")
        for ((name, value) in listOf(
            "predicted_gen_tps" to predictedGenTps,
            "predicted_prefill_tps" to predictedPrefillTps
        )) {
            if (!value.isFinite() || value <= 0) {
                throw SessionError("$name must be finite and > 0")
            }
        }
        if (rawJson.toByteArray(Charsets.UTF_8).size > 20000) {
            throw SessionError("prediction payload too large")
        }
        val record = store.load(id)
        requireState(record, SessionState.PREDICTION_PENDING)
        if (record.prediction != null) {
            throw SessionError("prediction already recorded")
        }
        if ((carriedFromSession == null) != (carriedFromRun == null) ||
            (carriedFromSession == null) != (carriedAtUtc == null)
        ) {
            throw SessionError("carried provenance travels together")
        }
        if (carriedFromSession != null &&
            !SessionStore.isValidId(carriedFromSession)
        ) {
            throw SessionError("carried session id malformed")
        }
        if (carriedFromRun != null &&
            !SessionStore.isValidRunId(carriedFromRun)
        ) {
            throw SessionError("carried run id malformed")
        }
        val cause = if (carriedFromSession == null) {
            "recorded from $sourceType"
        } else {
            "carried from $carriedFromSession"
        }
        return store.save(record.copy(
            state = SessionState.PREDICTION_RECORDED,
            updatedAtUtc = clock(),
            history = pushHistory(record, SessionState.PREDICTION_RECORDED,
                cause),
            prediction = linkedMapOf(
                "predicted_gen_tps" to predictedGenTps,
                "predicted_prefill_tps" to predictedPrefillTps,
                "raw_json" to rawJson,
                "source_type" to sourceType,
                "created_at_utc" to createdAtUtc,
                "evidence_ids" to evidenceIds,
                "carried_from_session" to carriedFromSession,
                "carried_from_run" to carriedFromRun,
                "carried_at_utc" to carriedAtUtc
            )
        ))
    }

    @Synchronized
    fun authorizeBenchmark(id: String, cause: String = "user approved"): SessionRecord {
        val record = store.load(id)
        requireState(record, SessionState.PREDICTION_RECORDED)
        if (record.prediction == null) {
            throw SessionError("no prediction to authorize against")
        }
        return transition(id, SessionState.BENCHMARK_AUTHORIZED, cause)
    }

    @Synchronized
    fun noteRunStarted(id: String): SessionRecord =
        transition(id, SessionState.BENCHMARK_RUNNING, "native run started")

    /**
     * Attaches one physical run: explicit identity + integrity checks,
     * time-order enforcement (replay exempt, labeled), duplicate-run
     * refusal, and deterministic comparison for COMPLETED runs.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun attachBenchmark(
        id: String,
        runId: String,
        sourceSha256: String,
        status: String,
        measurements: Map<String, Any?>,
        collectedAtUtc: String,
        modelSha256: String,
        workloadId: String,
        config: Map<String, Any?>,
        deviceModel: String?,
        // EF-MEM-01: durable refusal/failure reason (the
        // result error string). Stored only when non-null;
        // pre-change records omit the key and all readers
        // tolerate its absence, so schema_version stays 1.
        failureCause: String? = null
    ): SessionRecord {
        val record = store.load(id)
        requireState(record, SessionState.BENCHMARK_RUNNING)
        if (record.benchmark != null) {
            throw SessionError("benchmark already attached")
        }
        if (!SessionStore.isHex64(sourceSha256)) {
            throw SessionError("benchmark source hash malformed")
        }
        if (modelSha256.lowercase() != record.modelSha256) {
            throw SessionError("model mismatch")
        }
        if (workloadId != record.workloadId) {
            throw SessionError("workload mismatch")
        }
        // Numeric compare: JSON reload turns Ints into Longs, so compare
        // values, not JVM types.
        for (key in listOf("n_ctx", "n_threads", "n_predict")) {
            val want = (record.config[key] as? Number)?.toLong()
            val got = (config[key] as? Number)?.toLong()
            if (want == null || got == null || want != got) {
                throw SessionError("config mismatch on $key")
            }
        }
        if (config["sampler"] != record.config["sampler"]) {
            throw SessionError("config mismatch on sampler")
        }
        if (deviceModel != null && record.deviceModel != null &&
            deviceModel != record.deviceModel
        ) {
            throw SessionError("device mismatch")
        }
        val prediction = record.prediction
            ?: throw SessionError("no prediction recorded")
        val isReplay = prediction["source_type"] == SOURCE_REPLAY
        val predictedAt = prediction["created_at_utc"] as? String ?: ""
        // Strictly earlier only (FIX2): both stamps are
        // second-precision, and agent-approved runs routinely start
        // in their commit second. State order (RUNNING requires a
        // recorded prediction) already enforces real precedence.
        if (!isReplay && collectedAtUtc < predictedAt) {
            throw SessionError("measurement predates prediction")
        }
        for (other in store.listIds()) {
            if (other == id) continue
            val sibling = try {
                store.load(other)
            } catch (_: Exception) {
                continue
            }
            val bench = sibling.benchmark as? Map<String, Any?>
            if (bench != null && bench["run_id"] == runId) {
                throw SessionError("run already attached to $other")
            }
        }
        val benchState = when (status) {
            BenchContract.STATUS_COMPLETED -> SessionState.BENCHMARK_COMPLETED
            BenchContract.STATUS_FAILED -> SessionState.BENCHMARK_FAILED
            BenchContract.STATUS_CANCELLED -> SessionState.BENCHMARK_CANCELLED
            BenchContract.STATUS_TIMEOUT -> SessionState.BENCHMARK_TIMEOUT
            else -> throw SessionError("unknown benchmark status $status")
        }
        var updated = record.copy(
            state = benchState,
            updatedAtUtc = clock(),
            history = pushHistory(record, benchState, "attached $runId"),
            benchmark = linkedMapOf(
                "run_id" to runId,
                "source_sha256" to sourceSha256.lowercase(),
                "status" to status,
                "measurements" to measurements,
                "collected_at_utc" to collectedAtUtc,
                "attached_at_utc" to clock(),
                // The configuration the runner actually executed
                // (identity-verified above); pre-agent records omit it.
                "executed_config" to config
            ).also {
                // Durable refusal evidence; absent on older
                // records and on runs without an error.
                if (failureCause != null) {
                    it["failure_cause"] = failureCause
                }
            },
            // Agent children account against the parent budget; the
            // parent charges exactly once per approved attempt.
            budgetUsed = if (record.parentSessionId == null) {
                record.budgetUsed + 1
            } else {
                record.budgetUsed
            }
        )
        if (benchState == SessionState.BENCHMARK_COMPLETED) {
            val predictedGen = (prediction["predicted_gen_tps"] as Number).toDouble()
            val predictedPrefill =
                (prediction["predicted_prefill_tps"] as Number).toDouble()
            val measuredGen = (measurements["tps_gen"] as? Number)?.toDouble()
                ?: Double.NaN
            val measuredPrefill =
                (measurements["tps_prefill"] as? Number)?.toDouble()
                    ?: Double.NaN
            val result = SessionComparison.compare(
                predictedGen, predictedPrefill, measuredGen, measuredPrefill)
            updated = updated.copy(
                state = SessionState.COMPARISON_READY,
                updatedAtUtc = clock(),
                history = pushHistory(updated, SessionState.COMPARISON_READY,
                    if (result.comparable) "errors computed"
                    else "noncomparable: ${result.reason}"),
                // Null, never NaN: the codec refuses non-finite doubles.
                comparison = linkedMapOf(
                    "comparable" to result.comparable,
                    "reason" to result.reason,
                    "gen_signed" to result.genSigned
                        .takeIf { result.comparable },
                    "gen_abs" to result.genAbs.takeIf { result.comparable },
                    "gen_pct" to result.genPct.takeIf { result.comparable },
                    "prefill_signed" to result.prefillSigned
                        .takeIf { result.comparable },
                    "prefill_abs" to result.prefillAbs
                        .takeIf { result.comparable },
                    "prefill_pct" to result.prefillPct
                        .takeIf { result.comparable }
                )
            )
        }
        return store.save(updated)
    }

    /**
     * UX-D02 standalone authorization: a standalone record
     * (no prediction will ever exist) moves CREATED to
     * AUTHORIZED directly. The state machine permits this
     * edge for standalone mode only; all other modes keep
     * the prediction-first path untouched.
     */
    @Synchronized
    fun authorizeStandalone(id: String, cause: String): SessionRecord {
        val record = store.load(id)
        if (record.mode != SessionStore.MODE_STANDALONE) {
            throw SessionError("standalone authorization needs mode standalone")
        }
        return transition(id, SessionState.BENCHMARK_AUTHORIZED, cause)
    }

    /**
     * UX-D02 standalone attach: persists one COMPLETED
     * unbound run as a durable measurement record. Reuses
     * the RUNNING gate, model/workload/config identity
     * checks, and cross-session run-id uniqueness sweep,
     * but skips prediction, time-order, and comparison —
     * there is no prediction to compare. Terminal state
     * stays BENCHMARK_COMPLETED (never COMPARISON_READY).
     * Only COMPLETED runs persist; failures leave no
     * record, exactly as before.
     */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun attachStandalone(
        id: String,
        runId: String,
        sourceSha256: String,
        measurements: Map<String, Any?>,
        collectedAtUtc: String,
        modelSha256: String,
        workloadId: String,
        config: Map<String, Any?>,
        deviceModel: String?
    ): SessionRecord {
        val record = store.load(id)
        if (record.mode != SessionStore.MODE_STANDALONE) {
            throw SessionError("standalone attach needs mode standalone")
        }
        requireState(record, SessionState.BENCHMARK_RUNNING)
        if (record.benchmark != null) {
            throw SessionError("benchmark already attached")
        }
        if (!SessionStore.isHex64(sourceSha256)) {
            throw SessionError("benchmark source hash malformed")
        }
        if (modelSha256.lowercase() != record.modelSha256) {
            throw SessionError("model mismatch")
        }
        if (workloadId != record.workloadId) {
            throw SessionError("workload mismatch")
        }
        for (key in listOf("n_ctx", "n_threads", "n_predict")) {
            val want = (record.config[key] as? Number)?.toLong()
            val got = (config[key] as? Number)?.toLong()
            if (want == null || got == null || want != got) {
                throw SessionError("config mismatch on $key")
            }
        }
        if (config["sampler"] != record.config["sampler"]) {
            throw SessionError("config mismatch on sampler")
        }
        if (deviceModel != null && record.deviceModel != null &&
            deviceModel != record.deviceModel
        ) {
            throw SessionError("device mismatch")
        }
        for (other in store.listIds()) {
            if (other == id) continue
            val sibling = try {
                store.load(other)
            } catch (_: Exception) {
                continue
            }
            val bench = sibling.benchmark as? Map<String, Any?>
            if (bench != null && bench["run_id"] == runId) {
                throw SessionError("run already attached to $other")
            }
        }
        return store.save(record.copy(
            state = SessionState.BENCHMARK_COMPLETED,
            updatedAtUtc = clock(),
            history = pushHistory(record,
                SessionState.BENCHMARK_COMPLETED, "attached $runId"),
            benchmark = linkedMapOf(
                "run_id" to runId,
                "source_sha256" to sourceSha256.lowercase(),
                "status" to BenchContract.STATUS_COMPLETED,
                "measurements" to measurements,
                "collected_at_utc" to collectedAtUtc,
                "attached_at_utc" to clock(),
                "executed_config" to config
            ),
            budgetUsed = record.budgetUsed + 1
        ))
    }

    /** Marks a running session failed without attaching (e.g. the run
     * failed association after completing natively). */
    @Synchronized
    fun markBenchmarkFailed(id: String, cause: String): SessionRecord {
        val record = store.load(id)
        requireState(record, SessionState.BENCHMARK_RUNNING)
        return store.save(record.copy(
            state = SessionState.BENCHMARK_FAILED,
            updatedAtUtc = clock(),
            history = pushHistory(record, SessionState.BENCHMARK_FAILED, cause)
        ))
    }

    @Synchronized
    fun requestFollowup(id: String): SessionRecord =
        transition(id, SessionState.FOLLOWUP_PENDING, "follow-up requested")

    @Synchronized
    fun recordRecommendation(
        id: String,
        recommendation: Map<String, Any?>,
        provenance: String
    ): SessionRecord {
        val record = store.load(id)
        requireState(record, SessionState.FOLLOWUP_PENDING)
        return store.save(record.copy(
            state = SessionState.RECOMMENDATION_RECORDED,
            updatedAtUtc = clock(),
            history = pushHistory(record, SessionState.RECOMMENDATION_RECORDED,
                "from $provenance"),
            followup = linkedMapOf(
                "status" to "recorded",
                "recommendation" to recommendation,
                "provenance" to provenance,
                "at" to clock()
            )
        ))
    }

    @Synchronized
    fun finishSession(id: String, outcome: String, cause: String): SessionRecord {
        if (outcome !in setOf("completed", "abandoned", "failed", "cancelled")) {
            throw SessionError("unknown outcome $outcome")
        }
        val record = store.load(id)
        if (!SessionState.can(record.state, SessionState.SESSION_FINISHED)) {
            throw SessionError("cannot finish from ${record.state}")
        }
        return store.save(record.copy(
            state = SessionState.SESSION_FINISHED,
            updatedAtUtc = clock(),
            history = pushHistory(record, SessionState.SESSION_FINISHED, cause),
            outcome = outcome
        ))
    }

    @Synchronized
    fun abandonSession(id: String, reason: String): SessionRecord =
        finishSession(id, "abandoned", reason)

    @Synchronized
    fun deleteSession(id: String): Boolean = store.delete(id)

    @Synchronized
    fun get(id: String): SessionRecord = store.load(id)

    @Synchronized
    fun listSessions(): List<SessionRecord> =
        store.listIds().mapNotNull {
            try {
                store.load(it)
            } catch (_: Exception) {
                null
            }
        }.let { SessionListUi.sortNewestFirst(it) }

    /**
     * Launch recovery. Stuck RUNNING becomes FAILED (inference cannot
     * resume); stuck PENDING prediction returns to CREATED (nothing
     * was obtained); AUTHORIZED and FOLLOWUP_PENDING stay resumable.
     * Never invents COMPLETED. Returns human-readable actions taken.
     */
    @Synchronized
    fun recoverOnLaunch(): List<String> {
        val actions = mutableListOf<String>()
        for (id in store.listIds()) {
            val record = try {
                store.load(id)
            } catch (e: Exception) {
                actions.add("$id: quarantined on load (${e.message})")
                continue
            }
            when (record.state) {
                SessionState.BENCHMARK_RUNNING -> {
                    store.save(record.copy(
                        state = SessionState.BENCHMARK_FAILED,
                        updatedAtUtc = clock(),
                        history = pushHistory(record,
                            SessionState.BENCHMARK_FAILED,
                            "recovery: process died during native inference; " +
                                "resumption unsupported"),
                        recoveryNotes = record.recoveryNotes +
                            "interrupted during native inference; " +
                            "resumption unsupported"
                    ))
                    actions.add("$id: RUNNING -> FAILED (interrupted)")
                }
                SessionState.PREDICTION_PENDING -> {
                    store.save(record.copy(
                        state = SessionState.SESSION_CREATED,
                        updatedAtUtc = clock(),
                        history = pushHistory(record,
                            SessionState.SESSION_CREATED,
                            "recovery: prediction never obtained"),
                        recoveryNotes = record.recoveryNotes +
                            "prediction pending at shutdown; nothing recorded"
                    ))
                    actions.add("$id: PREDICTION_PENDING -> CREATED")
                }
                else -> {
                }
            }
        }
        return actions
    }

    // ---- internals ----

    private fun requireState(record: SessionRecord, vararg allowed: String) {
        if (record.state !in allowed) {
            throw SessionError(
                "illegal state ${record.state} for this operation")
        }
    }

    private fun transition(id: String, to: String, cause: String): SessionRecord {
        val record = store.load(id)
        if (!SessionState.can(record.state, to, record.mode)) {
            throw SessionError("illegal transition ${record.state} -> $to")
        }
        return store.save(record.copy(
            state = to,
            updatedAtUtc = clock(),
            history = pushHistory(record, to, cause)
        ))
    }

    private fun pushHistory(
        record: SessionRecord,
        state: String,
        cause: String
    ): List<Map<String, String>> {
        val entry = mapOf("state" to state, "at" to clock(), "cause" to cause)
        return (record.history + entry).takeLast(SessionStore.MAX_HISTORY)
    }
}
