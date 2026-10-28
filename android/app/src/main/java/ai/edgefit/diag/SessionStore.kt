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
    val outcome: String? = null
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
        "outcome" to outcome
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
                outcome = optStr("outcome")
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
        const val MAX_SESSIONS = 50
        const val MAX_RECORD_BYTES = 65536
        const val MAX_READ_BYTES = 262144
        const val MAX_HISTORY = 32
        const val MAX_QUARANTINE = 50
        val ID_PATTERN = Regex("^SES-[0-9a-f]{16}$")
        val MODES = setOf("byok", "replay", "offline_test")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun newSessionId(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            return "SES-" + bytes.joinToString("") { "%02x".format(it) }
        }

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        fun isHex64(value: String?): Boolean =
            value != null && HEX64.matches(value.lowercase())
    }

    open class StoreError(message: String) : Exception(message)
    class CorruptRecord(val id: String, why: String) :
        StoreError("corrupt record $id: $why")
    class UnsupportedVersion(val id: String, val version: Int) :
        StoreError("unsupported schema $version in $id")
    class StoreFull : StoreError("session store full ($MAX_SESSIONS)")
    class NotFound(val id: String) : StoreError("no session $id")
    class BadId(val id: String) : StoreError("illegal session id")
    class TooLarge(val id: String) : StoreError("record too large: $id")

    private val dir = File(rootDir, "sessions")
    private val quarantineDir = File(rootDir, "sessions-quarantine")

    init {
        dir.mkdirs()
        quarantineDir.mkdirs()
        // Interrupted writes leave temp files only; sweep them so they
        // can never be mistaken for records.
        dir.listFiles { file -> file.name.endsWith(".tmp") }
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

    /** Persists a record atomically. New ids respect the store bound. */
    @Synchronized
    fun save(record: SessionRecord): SessionRecord {
        if (!isValidId(record.sessionId)) throw BadId(record.sessionId)
        val target = File(dir, "${record.sessionId}.json")
        if (!target.isFile && count() >= MAX_SESSIONS) throw StoreFull()
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
