package ai.edgefit.diag

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable app-private repository for the user-selected GGUF model
 * (EF-ANDROID-LIFECYCLE-PERSISTENCE-P0-01). The selected model must
 * survive rotation, backgrounding, relaunch, and reboot; the old
 * cache-backed slot (`cacheDir/bench-model.gguf`) plus an
 * Activity-instance reference guaranteed the opposite.
 *
 * Layout under [rootDir]/models/:
 * - `<sha256>.gguf` — the retained model bytes (content-addressed,
 *   so re-selecting the same file never duplicates gigabytes).
 * - `selected_model.json` — display identity, size, hash, source
 *   URI, GGUF metadata, and the compatibility verdict (atomic
 *   tmp+fsync+rename writes, mirroring [SessionStore]).
 *
 * Pure JVM over [File] (no Android APIs): fully unit-tested.
 * Never deletes a retained model file; only abandoned staging
 * temp files and byte-identical duplicates are removed.
 */
class ModelStore(rootDir: File, private val cacheDir: File) {

    class StoreError(message: String) : Exception(message)

    companion object {
        const val DIR_NAME = "models"
        const val SELECTION_NAME = "selected_model.json"
        const val LEGACY_SLOT_NAME = "bench-model.gguf"
        const val STAGE_TMP_NAME = "bench-import.tmp"
        private val SHA64 = Regex("[0-9a-f]{64}")
        private const val MAX_META_BYTES = 64 * 1024

        fun sha256HexOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Persisted selection record. [fileName] always derives from
     * [sha256]; anything else on disk is refused at restore.
     */
    data class SelectedModel(
        val displayName: String,
        val sha256: String,
        val sizeBytes: Long,
        val fileName: String,
        val sourceUri: String?,
        val ggufVersion: Long?,
        val architecture: String?,
        val fileType: Long?,
        val quantization: String?,
        val hasTokenizer: Boolean?,
        val contextLength: Long?,
        val verdictStatus: String,
        val verdictReasons: List<String>,
        val stagedAtUtc: String
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "display_name" to displayName,
            "sha256" to sha256,
            "size_bytes" to sizeBytes,
            "file_name" to fileName,
            "source_uri" to sourceUri,
            "gguf_version" to ggufVersion,
            "architecture" to architecture,
            "file_type" to fileType,
            "quantization" to quantization,
            "has_tokenizer" to hasTokenizer,
            "context_length" to contextLength,
            "verdict_status" to verdictStatus,
            "verdict_reasons" to verdictReasons,
            "staged_at_utc" to stagedAtUtc
        )

        companion object {
            fun fromMap(map: Map<String, Any?>): SelectedModel? {
                val sha = map["sha256"] as? String
                if (sha == null || !SHA64.matches(sha)) return null
                val bytes = (map["size_bytes"] as? Number)?.toLong()
                if (bytes == null || bytes <= 0 ||
                    bytes > BenchGuards.MAX_MODEL_BYTES
                ) return null
                val name = map["file_name"] as? String
                if (name != "$sha.gguf") return null
                val display = map["display_name"] as? String
                if (display.isNullOrBlank()) return null
                val status = map["verdict_status"] as? String
                if (status.isNullOrBlank()) return null
                @Suppress("UNCHECKED_CAST")
                val reasons = (map["verdict_reasons"] as? List<*>)
                    ?.mapNotNull { it as? String } ?: return null
                val stagedAt = map["staged_at_utc"] as? String
                if (stagedAt.isNullOrBlank()) return null
                return SelectedModel(
                    displayName = display,
                    sha256 = sha,
                    sizeBytes = bytes,
                    fileName = name,
                    sourceUri = map["source_uri"] as? String,
                    ggufVersion = (map["gguf_version"] as? Number)?.toLong(),
                    architecture = map["architecture"] as? String,
                    fileType = (map["file_type"] as? Number)?.toLong(),
                    quantization = map["quantization"] as? String,
                    hasTokenizer = map["has_tokenizer"] as? Boolean,
                    contextLength =
                        (map["context_length"] as? Number)?.toLong(),
                    verdictStatus = status,
                    verdictReasons = reasons,
                    stagedAtUtc = stagedAt
                )
            }
        }
    }

    sealed interface Restore {
        /** Validated selection with a readable model file. */
        data class Ready(val model: SelectedModel, val file: File) : Restore
        /** A selection exists but cannot be honored; never runnable. */
        data class Unavailable(
            val reason: String,
            val model: SelectedModel?
        ) : Restore
        /** No selection was ever persisted. */
        data object None : Restore
    }

    private val dir = File(rootDir, DIR_NAME).apply { mkdirs() }
    private val selectionFile = File(dir, SELECTION_NAME)

    /**
     * Retains [tmpFile] (a fully staged, hashed, runnable copy) as
     * the selected model. A byte-identical retained file dedups
     * (the temp is dropped, no second copy); otherwise the temp
     * moves into place. Refused verdicts never reach the slot.
     */
    fun select(
        tmpFile: File,
        sha256: String,
        sizeBytes: Long,
        displayName: String,
        sourceUri: String?,
        verdict: GgufInspect.Verdict,
        stagedAtUtc: String
    ): SelectedModel {
        if (!verdict.runnable) {
            throw StoreError("refused model cannot be selected")
        }
        if (!SHA64.matches(sha256)) throw StoreError("sha malformed")
        if (sizeBytes <= 0 || sizeBytes > BenchGuards.MAX_MODEL_BYTES) {
            throw StoreError("model size out of bounds")
        }
        if (displayName.isBlank()) throw StoreError("display name blank")
        if (!tmpFile.isFile || tmpFile.length() != sizeBytes) {
            throw StoreError("staged temp missing or short")
        }
        val header = verdict.header
        val dest = File(dir, "$sha256.gguf")
        if (dest.isFile && dest.length() == sizeBytes) {
            // Byte-identical content already retained: drop the
            // temp instead of a second multi-gigabyte copy.
            try {
                tmpFile.delete()
            } catch (_: Exception) {
            }
        } else {
            moveFile(tmpFile, dest)
        }
        dropLegacyDuplicate(sha256, sizeBytes)
        val model = SelectedModel(
            displayName = displayName,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            fileName = dest.name,
            sourceUri = sourceUri,
            ggufVersion = header?.version,
            architecture = header?.architecture,
            fileType = header?.fileType,
            quantization = header?.fileType?.let {
                GgufInspect.ftypeName(it)
            },
            hasTokenizer = header?.hasTokenizerTokens,
            contextLength = header?.contextLength,
            verdictStatus = verdict.status.name,
            verdictReasons = verdict.reasons,
            stagedAtUtc = stagedAtUtc
        )
        writeSelection(model)
        return model
    }

    /**
     * Reads the persisted selection and re-validates it against
     * the retained bytes (existence, size, GGUF container). Any
     * failure is an honest [Restore.Unavailable], never a
     * runnable model and never an exception to the caller.
     */
    fun restore(): Restore {
        if (!selectionFile.isFile) return Restore.None
        val model = try {
            if (selectionFile.length() > MAX_META_BYTES) {
                return Restore.Unavailable(
                    "Saved model record is oversized; " +
                        "please reselect the model file.", null)
            }
            val root = SessionJson.parse(
                selectionFile.readText(Charsets.UTF_8))
            @Suppress("UNCHECKED_CAST")
            SelectedModel.fromMap((root as? Map<String, Any?>)
                ?: return Restore.Unavailable(
                    "Saved model record is unreadable; " +
                        "please reselect the model file.", null))
                ?: return Restore.Unavailable(
                    "Saved model record failed validation; " +
                        "please reselect the model file.", null)
        } catch (e: Exception) {
            return Restore.Unavailable(
                "Saved model record is unreadable " +
                    "(${e.message}); please reselect the model file.",
                null)
        }
        val file = File(dir, model.fileName)
        if (!file.isFile) {
            return Restore.Unavailable(
                "Model file \"${model.displayName}\" is missing " +
                    "from app storage.", model)
        }
        if (file.length() != model.sizeBytes) {
            return Restore.Unavailable(
                "Model file \"${model.displayName}\" changed " +
                    "(expected ${model.sizeBytes / 1048576} MB, " +
                    "found ${file.length() / 1048576} MB).", model)
        }
        when (val parse = GgufInspect.inspectFile(file)) {
            is GgufInspect.Result.Invalid -> return Restore.Unavailable(
                "Model file \"${model.displayName}\" is no longer " +
                    "a readable GGUF (${parse.reason}).", model)
            is GgufInspect.Result.Ok -> Unit
        }
        return Restore.Ready(model, file)
    }

    /**
     * One-way adoption of a pre-persistence cache slot
     * (`cacheDir/bench-model.gguf`). Runs only when no
     * selection exists yet, so an explicit selection always
     * wins. The legacy bytes MOVE (no duplicate copy). The
     * verdict is recomputed honestly: [measuredShas] promotes
     * to VERIFIED exactly like a fresh import.
     */
    fun migrateLegacyCache(
        stagedAtUtc: String,
        availBytes: Long?,
        measuredShas: Set<String>
    ): SelectedModel? {
        if (selectionFile.isFile) return null
        val legacy = File(cacheDir, LEGACY_SLOT_NAME)
        if (!legacy.isFile) return null
        val bytes = legacy.length()
        if (bytes <= 0 || bytes > BenchGuards.MAX_MODEL_BYTES) return null
        val sha = try {
            sha256HexOf(legacy)
        } catch (_: Exception) {
            return null
        }
        val verdict = try {
            GgufInspect.assess(
                GgufInspect.inspectFile(legacy), bytes,
                availBytes, measuredShas, sha)
        } catch (_: Exception) {
            return null
        }
        if (!verdict.runnable) return null
        // The legacy slot had no display name recorded; the
        // short hash keeps the line honest, never invented.
        return try {
            select(legacy, sha, bytes,
                "Restored model (${sha.take(8)}…)", null,
                verdict, stagedAtUtc)
        } catch (_: Exception) {
            null
        }
    }

    /** Drops an abandoned staging temp; never the retained model. */
    fun clearStaleTemp() {
        try {
            File(cacheDir, STAGE_TMP_NAME).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Removes the legacy cache slot only when it is a
     * byte-identical duplicate of the retained file (same
     * hash and size). Anything else is left untouched: this
     * store never deletes a model the user might still want.
     */
    private fun dropLegacyDuplicate(sha256: String, sizeBytes: Long) {
        try {
            val legacy = File(cacheDir, LEGACY_SLOT_NAME)
            if (!legacy.isFile || legacy.length() != sizeBytes) return
            if (sha256HexOf(legacy) != sha256) return
            legacy.delete()
        } catch (_: Exception) {
        }
    }

    private fun writeSelection(model: SelectedModel) {
        val text = try {
            SessionJson.stringify(model.toMap())
        } catch (e: SessionJson.JsonError) {
            throw StoreError("unserializable selection: ${e.message}")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_META_BYTES) throw StoreError("selection oversize")
        val tmp = File(
            dir, "$SELECTION_NAME.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), selectionFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), selectionFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun moveFile(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(),
                StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
        }
        // Cross-volume fallback: copy then drop the source.
        try {
            FileInputStream(from).use { input ->
                FileOutputStream(to).use { output ->
                    input.copyTo(output)
                }
            }
            from.delete()
        } catch (e: Exception) {
            try {
                to.delete()
            } catch (_: Exception) {
            }
            throw StoreError("cannot retain model: ${e.message}")
        }
    }
}
