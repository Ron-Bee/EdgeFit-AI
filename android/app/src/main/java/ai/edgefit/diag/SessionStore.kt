package ai.edgefit.diag

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Durable session record. All lifecycle-nullable sections start null
 * and are filled exactly once by [SessionManager]; the store itself
 * only persists validated records atomically.
 */
data class SessionRecord(
    val sessionId: String,
    val schemaVersion: Int = SessionStore.SCHEMA_VERSION,
    val mode: String,
    val state: String,
    val createdAtUtc: String,
    val updatedAtUtc: String,
    val history: List<Map<String, String>> = emptyList(),
    val deviceModel: String? = null,
    val profileSha256: String? = null,
    val modelSha256: String,
    val modelSizeBytes: Long,
    val modelDisplay: String,
    val workloadId: String,
    val promptSha256: String,
    val config: Map<String, Any?>,
    val prediction: Map<String, Any?>? = null,
    val benchmark: Map<String, Any?>? = null,
    val comparison: Map<String, Any?>? = null,
    val followup: Map<String, Any?>? = null,
    val recoveryNotes: List<String> = emptyList(),
    val budgetMax: Int = 5,
    val budgetUsed: Int = 0,
    val outcome: String? = null,
    // EF-AGENT-01 additive links. Absent (null) on pre-agent records;
    // schema version stays 1 because every reader tolerates absence.
    val parentSessionId: String? = null,
    val experimentSeq: Int? = null,
    val agent: Map<String, Any?>? = null,
    // EF-FOLLOWUP-02 repeat link. Absent (null) on non-repeat
    // records; the carried prediction inside `prediction` keeps
    // the source timestamp plus carried-from provenance.
    val repeatOfSessionId: String? = null,
    val repeatOfRunId: String? = null,
    // EF-MEM-03 recovery link: a NEW authorized attempt
    // after a FAILED/TIMEOUT/CANCELLED run. Absent (null)
    // on ordinary records; schema stays 1 (tolerated
    // absence, same as the repeat link above).
    val recoveryOfSessionId: String? = null,
    val recoveryOfRunId: String? = null
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "schema_version" to schemaVersion,
        "session_id" to sessionId,
        "mode" to mode,
        "state" to state,
        "created_at_utc" to createdAtUtc,
        "updated_at_utc" to updatedAtUtc,
        "history" to history,
        "device_model" to deviceModel,
        "profile_sha256" to profileSha256,
        "model_sha256" to modelSha256,
        "model_size_bytes" to modelSizeBytes,
        "model_display" to modelDisplay,
        "workload_id" to workloadId,
        "prompt_sha256" to promptSha256,
        "config" to config,
        "prediction" to prediction,
        "benchmark" to benchmark,
        "comparison" to comparison,
        "followup" to followup,
        "recovery_notes" to recoveryNotes,
        "budget_max" to budgetMax,
        "budget_used" to budgetUsed,
        "outcome" to outcome,
        "parent_session_id" to parentSessionId,
        "experiment_seq" to experimentSeq,
        "agent" to agent,
        "repeat_of_session_id" to repeatOfSessionId,
        "repeat_of_run_id" to repeatOfRunId,
        "recovery_of_session_id" to recoveryOfSessionId,
        "recovery_of_run_id" to recoveryOfRunId
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(id: String, map: Map<String, Any?>): SessionRecord {
            fun bad(why: String): Nothing =
                throw SessionStore.CorruptRecord(id, why)
            val version = map["schema_version"]
            if (version is Number && version.toInt() != SessionStore.SCHEMA_VERSION) {
                throw SessionStore.UnsupportedVersion(id, version.toInt())
            }
            if (version !is Number) bad("schema_version missing")
            fun str(key: String): String {
                val value = map[key]
                if (value !is String) bad("$key must be a string")
                return value
            }
            fun optStr(key: String): String? {
                val value = map[key] ?: return null
                if (value !is String) bad("$key must be a string")
                return value
            }
            fun strMap(key: String): Map<String, Any?>? {
                val value = map[key] ?: return null
                if (value !is Map<*, *>) bad("$key must be an object")
                return value as Map<String, Any?>
            }
            val state = str("state")
            if (state !in SessionState.ALL) bad("unknown state $state")
            val mode = str("mode")
            if (mode !in SessionStore.MODES) bad("unknown mode $mode")
            val historyRaw = map["history"]
            if (historyRaw !is List<*>) bad("history must be a list")
            val history = historyRaw.map {
                if (it !is Map<*, *>) bad("history entry must be an object")
                mapOf(
                    "state" to (it["state"] as? String ?: bad("history.state")),
                    "at" to (it["at"] as? String ?: bad("history.at")),
                    "cause" to (it["cause"] as? String ?: "")
                )
            }
            val notesRaw = map["recovery_notes"]
            if (notesRaw !is List<*>) bad("recovery_notes must be a list")
            val notes = notesRaw.map {
                if (it !is String) bad("recovery note must be a string")
                it
            }
            val size = map["model_size_bytes"]
            if (size !is Number) bad("model_size_bytes must be a number")
            val budgetMax = map["budget_max"]
            val budgetUsed = map["budget_used"]
            if (budgetMax !is Number || budgetUsed !is Number) {
                bad("budget must be numeric")
            }
            val config = strMap("config") ?: bad("config missing")
            val seqRaw = map["experiment_seq"]
            if (seqRaw != null && seqRaw !is Number) {
                bad("experiment_seq must be a number")
            }
            val parent = optStr("parent_session_id")
            if (parent != null && !SessionStore.isValidId(parent)) {
                bad("parent_session_id malformed")
            }
            val repeatOfSession = optStr("repeat_of_session_id")
            if (repeatOfSession != null &&
                !SessionStore.isValidId(repeatOfSession)
            ) {
                bad("repeat_of_session_id malformed")
            }
            val repeatOfRun = optStr("repeat_of_run_id")
            if (repeatOfRun != null &&
                !SessionStore.isValidRunId(repeatOfRun)
            ) {
                bad("repeat_of_run_id malformed")
            }
            val recoveryOfSession = optStr("recovery_of_session_id")
            if (recoveryOfSession != null &&
                !SessionStore.isValidId(recoveryOfSession)
            ) {
                bad("recovery_of_session_id malformed")
            }
            val recoveryOfRun = optStr("recovery_of_run_id")
            if (recoveryOfRun != null &&
                !SessionStore.isValidRunId(recoveryOfRun)
            ) {
                bad("recovery_of_run_id malformed")
            }
            return SessionRecord(
                sessionId = str("session_id").also {
                    if (it != id) bad("session_id/filename mismatch")
                },
                schemaVersion = SessionStore.SCHEMA_VERSION,
                mode = mode,
                state = state,
                createdAtUtc = str("created_at_utc"),
                updatedAtUtc = str("updated_at_utc"),
                history = history,
                deviceModel = optStr("device_model"),
                profileSha256 = optStr("profile_sha256"),
                modelSha256 = str("model_sha256"),
                modelSizeBytes = size.toLong(),
                modelDisplay = str("model_display"),
                workloadId = str("workload_id"),
                promptSha256 = str("prompt_sha256"),
                config = config,
                prediction = strMap("prediction"),
                benchmark = strMap("benchmark"),
                comparison = strMap("comparison"),
                followup = strMap("followup"),
                recoveryNotes = notes,
                budgetMax = budgetMax.toInt(),
                budgetUsed = budgetUsed.toInt(),
                outcome = optStr("outcome"),
                parentSessionId = parent,
                experimentSeq = (map["experiment_seq"] as? Number)?.toInt(),
                agent = strMap("agent"),
                repeatOfSessionId = repeatOfSession,
                repeatOfRunId = repeatOfRun,
                recoveryOfSessionId = recoveryOfSession,
                recoveryOfRunId = recoveryOfRun
            )
        }
    }
}

/**
 * Bounded atomic-file session store under an app-private directory.
 * Chosen over SQLite/Room (see ef-session-01 rationale): zero new
 * dependencies, one-writer file semantics, EF-LOOP-01 manifest parity,
 * and every record is a bounded, inspectable, hash-checkable file.
 * Pure java.io over an injected root, so the whole store is JVM-testable;
 * callers pass `context.filesDir`.
 *
 * Integrity: temp-file + fsync + atomic rename means a crash leaves
 * either the old record or the new one, never a torn file. Unparseable
 * or unsupported files are quarantined, never silently dropped.
 */
class SessionStore(rootDir: File) {
    companion object {
        const val SCHEMA_VERSION = 1
        // EF-SESSION-SCALE-01: no global session-count
        // ceiling. The pre-scale MAX_SESSIONS = 50 bound
        // blocked legitimate Agent approvals and recovery
        // once a developer accumulated 50 experiments; a
        // count cap is the wrong tool because records are
        // small, independent files and evidence must never
        // be auto-deleted to make room. Storage safety now
        // rests on justified per-record bounds below plus
        // explicit write-failure errors — never a count.
        const val MAX_RECORD_BYTES = 65536
        const val MAX_READ_BYTES = 262144
        const val MAX_HISTORY = 32
        // Retained: corrupt/unreadable files (only) rotate
        // here so a wedged writer cannot fill storage with
        // garbage. Valid evidence is never quarantined.
        const val MAX_QUARANTINE = 50
        val ID_PATTERN = Regex("^SES-[0-9a-f]{16}$")
        /** UX-D02 standalone measurement record (no prediction). */
        const val MODE_STANDALONE = "standalone"
        val MODES = setOf("byok", "replay", "offline_test", "agent",
            MODE_STANDALONE)
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun newSessionId(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            return "SES-" + bytes.joinToString("") { "%02x".format(it) }
        }

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        private val RUN_ID_PATTERN =
            Regex("^BENCH-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")

        fun isValidRunId(id: String): Boolean = RUN_ID_PATTERN.matches(id)

        fun isHex64(value: String?): Boolean =
            value != null && HEX64.matches(value.lowercase())
    }

    open class StoreError(message: String) : Exception(message)
    class CorruptRecord(val id: String, why: String) :
        StoreError("corrupt record $id: $why")
    class UnsupportedVersion(val id: String, val version: Int) :
        StoreError("unsupported schema $version in $id")
    class NotFound(val id: String) : StoreError("no session $id")
    class BadId(val id: String) : StoreError("illegal session id")
    class TooLarge(val id: String) : StoreError("record too large: $id")

    private val dir = File(rootDir, "sessions")
    private val quarantineDir = File(rootDir, "sessions-quarantine")

    init {
        dir.mkdirs()
        quarantineDir.mkdirs()
        // Interrupted writes leave "<id>.json.tmp-<nanos>"
        // files only; sweep them so they can neither
        // accumulate nor (defensively) be mistaken for
        // records. tmp files never match the ".json"
        // record filter, so the sweep is purely hygiene.
        dir.listFiles { file -> file.name.contains(".json.tmp-") }
            ?.forEach { it.delete() }
    }

    private fun fileFor(id: String): File {
        if (!isValidId(id)) throw BadId(id)
        return File(dir, "$id.json")
    }

    @Synchronized
    fun exists(id: String): Boolean {
        if (!isValidId(id)) return false
        return File(dir, "$id.json").isFile
    }

    @Synchronized
    fun count(): Int =
        dir.listFiles { file -> file.name.endsWith(".json") }?.size ?: 0

    @Synchronized
    fun listIds(): List<String> =
        (dir.listFiles { file ->
            file.name.endsWith(".json") &&
                isValidId(file.name.removeSuffix(".json"))
        } ?: emptyArray()).map { it.name.removeSuffix(".json") }.sorted()

    @Synchronized
    fun quarantineIds(): List<String> =
        (quarantineDir.listFiles { file -> file.name.endsWith(".json") }
            ?: emptyArray()).map { it.name }.sorted()

    /**
     * Persists a record atomically (tmp + fsync + atomic
     * rename). No count ceiling: new ids always persist
     * when the filesystem accepts them. Genuine storage
     * failures surface as explicit errors naming the
     * record — never silent loss, never auto-deletion.
     */
    @Synchronized
    fun save(record: SessionRecord): SessionRecord {
        if (!isValidId(record.sessionId)) throw BadId(record.sessionId)
        val target = File(dir, "${record.sessionId}.json")
        val text: String
        try {
            text = SessionJson.stringify(record.toMap())
        } catch (e: SessionJson.JsonError) {
            throw CorruptRecord(record.sessionId, "unserializable: ${e.message}")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECORD_BYTES) throw TooLarge(record.sessionId)
        val tmp = File(dir, "${record.sessionId}.json.tmp-${System.nanoTime()}")
        try {
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                // File.renameTo cannot replace an existing target on Windows;
                // NIO move with REPLACE_EXISTING works everywhere, atomically
                // for same-directory renames on NTFS/ext4/f2fs/APFS.
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                throw StoreError(
                    "session write failed for ${record.sessionId}: " +
                        "${e.message}. Free device storage or export " +
                        "sessions, then retry; no record was changed.")
            }
        } finally {
            tmp.delete()
        }
        return record
    }

    /** Loads and strictly validates; quarantines corrupt/unsupported. */
    @Synchronized
    fun load(id: String): SessionRecord {
        val file = fileFor(id)
        if (!file.isFile) throw NotFound(id)
        if (file.length() > MAX_READ_BYTES) {
            return quarantined(id, file, "oversize")
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return quarantined(id, file, "unreadable: ${e.message}")
        }
        val parsed = try {
            SessionJson.parse(text)
        } catch (e: SessionJson.JsonError) {
            return quarantined(id, file, "unparseable: ${e.message}")
        }
        if (parsed !is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return quarantined(id, file, "top level not an object")
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            SessionRecord.fromMap(id, parsed as Map<String, Any?>)
        } catch (e: UnsupportedVersion) {
            moveToQuarantine(id, file, "schema v${e.version}")
            throw e
        } catch (e: CorruptRecord) {
            quarantined(id, file, e.message ?: "invalid")
        }
    }

    private fun quarantined(id: String, file: File, reason: String): Nothing {
        moveToQuarantine(id, file, reason)
        throw CorruptRecord(id, reason)
    }

    private fun moveToQuarantine(id: String, file: File, reason: String) {
        try {
            val dest = File(quarantineDir, "$id.json")
            Files.move(file.toPath(), dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING)
            File(quarantineDir, "$id.reason").writeText(
                reason, Charsets.UTF_8)
            rotateQuarantine()
        } catch (_: Exception) {
        }
    }

    private fun rotateQuarantine() {
        val files = quarantineDir.listFiles { file ->
            file.name.endsWith(".json")
        }?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_QUARANTINE) return
        files.take(files.size - MAX_QUARANTINE).forEach { victim ->
            victim.delete()
            File(quarantineDir, victim.nameWithoutExtension + ".reason").delete()
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        return File(dir, "$id.json").delete()
    }
}
