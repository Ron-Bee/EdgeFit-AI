package ai.edgefit.diag

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Developer key custody. Session-only by default: the key lives in
 * this process's memory and dies with it. Optional persistence seals
 * the key with an Android Keystore AES/GCM key and stores IV +
 * ciphertext in one app-private file (excluded from backups).
 *
 * The seal is injected so vault logic is JVM-testable; on device the
 * activity passes [AndroidKeySeal]. No key material is ever logged,
 * and only a last-4 suffix is ever displayed.
 */
interface KeySeal {
    /** Returns (iv, ciphertext). */
    @Throws(Exception::class)
    fun seal(plain: ByteArray): Pair<ByteArray, ByteArray>

    @Throws(Exception::class)
    fun open(iv: ByteArray, cipher: ByteArray): ByteArray
}

class MetaKeyVault(
    filesDir: File,
    private val seal: KeySeal?
) {
    class VaultError(message: String) : Exception(message)

    companion object {
        const val KEY_FILE = "meta_key.bin"
        const val MAX_KEY_BYTES = 4096
    }

    @Volatile private var sessionKey: String? = null
    private val file = File(filesDir, KEY_FILE)

    @Synchronized
    fun setSessionKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) throw VaultError("empty key")
        if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_KEY_BYTES) {
            throw VaultError("key too long")
        }
        sessionKey = trimmed
    }

    @Synchronized
    fun clearSession() {
        sessionKey = null
    }

    fun hasSession(): Boolean = sessionKey != null

    fun hasPersisted(): Boolean = file.isFile

    /** Last-4 suffix of the session key only; persisted keys are not
     * decrypted merely to render status. */
    fun sessionSuffix(): String? =
        sessionKey?.let { "…" + it.takeLast(4) }

    /**
     * Session key preferred; else decrypt persisted. A persisted file
     * that no longer decrypts (Keystore wipe) is deleted so the user
     * gets a clean re-entry instead of a stuck failure.
     */
    @Synchronized
    fun getKey(): String? {
        sessionKey?.let { return it }
        if (!file.isFile) return null
        val engine = seal ?: throw VaultError("secure storage unavailable")
        val blob = try {
            file.readBytes()
        } catch (_: Exception) {
            throw VaultError("saved key unreadable")
        }
        if (blob.size < 13) {
            file.delete()
            return null
        }
        return try {
            val plain = engine.open(blob.copyOfRange(0, 12), blob.copyOfRange(12, blob.size))
            String(plain, Charsets.UTF_8).trim().ifEmpty { null }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    /** Seals the current session key to disk. Fails closed. */
    @Synchronized
    fun persistSessionKey() {
        val key = sessionKey ?: throw VaultError("no session key to save")
        val engine = seal ?: throw VaultError("secure storage unavailable")
        val (iv, cipher) = try {
            engine.seal(key.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw VaultError("seal failed: ${e.message}")
        }
        if (iv.size != 12) throw VaultError("bad seal parameters")
        val tmp = File(file.parentFile, "$KEY_FILE.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(iv)
                out.write(cipher)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    /** Removes every local copy. Returns true if anything was held. */
    @Synchronized
    fun forget(): Boolean {
        val had = sessionKey != null || file.isFile
        sessionKey = null
        try {
            file.delete()
        } catch (_: Exception) {
        }
        return had
    }
}
