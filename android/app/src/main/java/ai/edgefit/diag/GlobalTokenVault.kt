package ai.edgefit.diag

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Upload-credential custody for EdgeFit Global contributions.
 * Deliberately SEPARATE from [MetaKeyVault]: different file, different
 * Keystore alias (see [GlobalKeySeal]), never a Meta API key.
 *
 * Session-only by default; optional Keystore-backed persistence via
 * an injected [KeySeal] (JVM-testable). Only a last-4 suffix is ever
 * displayed; tokens never reach logs, exports, or screenshots of
 * settings (callers must use [suffix]).
 */
class GlobalTokenVault(
    filesDir: File,
    private val seal: KeySeal?
) {
    class VaultError(message: String) : Exception(message)

    companion object {
        const val TOKEN_FILE = "global_upload_token.bin"
    }

    @Volatile private var sessionToken: String? = null
    private val file = File(filesDir, TOKEN_FILE)

    @Synchronized
    fun setSessionToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) throw VaultError("empty token")
        if (trimmed.toByteArray(Charsets.UTF_8).size >
            GlobalContract.MAX_TOKEN_BYTES
        ) throw VaultError("token too large")
        if (!GlobalContract.tokenValid(trimmed)) {
            throw VaultError("token has invalid shape")
        }
        sessionToken = trimmed
    }

    /** Persists the CURRENT session token (explicit opt-in only). */
    @Synchronized
    fun persistSessionToken() {
        val token = sessionToken ?: throw VaultError("no token to persist")
        val engine = seal ?: throw VaultError("secure storage unavailable")
        val (iv, cipher) = try {
            engine.seal(token.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw VaultError("seal failed: ${e.message}")
        }
        // Format: "<ivLen>:<iv><cipher>" (ivLen ASCII decimal).
        val framed = iv.size.toString().toByteArray(Charsets.UTF_8) +
            byteArrayOf(':'.code.toByte()) + iv + cipher
        val tmp = File(file.parentFile, TOKEN_FILE + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(framed)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    @Synchronized
    fun getToken(): String? {
        sessionToken?.let { return it }
        if (!file.isFile) return null
        val engine = seal ?: throw VaultError("secure storage unavailable")
        val framed = try {
            file.readBytes()
        } catch (e: Exception) {
            throw VaultError("token file unreadable")
        }
        val colon = framed.indexOf(':'.code.toByte())
        if (colon < 0) throw VaultError("token file corrupt")
        val ivLen = try {
            framed.copyOfRange(0, colon).toString(Charsets.UTF_8).toInt()
        } catch (_: Exception) {
            throw VaultError("token file corrupt")
        }
        if (ivLen <= 0 || colon + 1 + ivLen > framed.size) {
            throw VaultError("token file corrupt")
        }
        val iv = framed.copyOfRange(colon + 1, colon + 1 + ivLen)
        val cipher = framed.copyOfRange(colon + 1 + ivLen, framed.size)
        val plain = try {
            engine.open(iv, cipher)
        } catch (e: Exception) {
            throw VaultError("token unseal failed")
        }
        val token = plain.toString(Charsets.UTF_8)
        if (!GlobalContract.tokenValid(token)) {
            throw VaultError("persisted token has invalid shape")
        }
        sessionToken = token
        return token
    }

    @Synchronized
    fun hasPersisted(): Boolean = file.isFile

    /** Last-4 suffix for display; never the token itself. */
    fun suffix(): String? {
        val token = try {
            getToken()
        } catch (_: Exception) {
            return null
        } ?: return null
        return "…" + token.takeLast(4)
    }

    /** Removes session and persisted copies. Returns true if any existed. */
    @Synchronized
    fun forget(): Boolean {
        val had = sessionToken != null || file.isFile
        sessionToken = null
        if (file.isFile) file.delete()
        return had
    }
}
