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
        budgetMax: Int = 5
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
            budgetMax = budgetMax
        )
        return store.save(record)
    }

    @Synchronized
    fun requestPrediction(id: String): SessionRecord =
        transition(id, SessionState.PREDICTION_PENDING, "prediction requested")

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
        createdAtUtc: String = clock()
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
        return store.save(record.copy(
            state = SessionState.PREDICTION_RECORDED,
            updatedAtUtc = clock(),
            history = pushHistory(record, SessionState.PREDICTION_RECORDED,
                "recorded from $sourceType"),
            prediction = linkedMapOf(
                "predicted_gen_tps" to predictedGenTps,
                "predicted_prefill_tps" to predictedPrefillTps,
                "raw_json" to rawJson,
                "source_type" to sourceType,
                "created_at_utc" to createdAtUtc,
                "evidence_ids" to evidenceIds
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
        deviceModel: String?
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
        if (!isReplay && collectedAtUtc <= predictedAt) {
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
                "attached_at_utc" to clock()
            ),
            budgetUsed = record.budgetUsed + 1
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
        }.sortedByDescending { it.createdAtUtc }

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
        if (!SessionState.can(record.state, to)) {
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
