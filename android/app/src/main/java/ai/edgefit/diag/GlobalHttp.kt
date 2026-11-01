package ai.edgefit.diag

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Bounded HTTPS transport for the EdgeFit Global backend. Mirrors
 * [HttpsMetaTransport]: HttpsURLConnection only, explicit configured
 * origin (https, validated), bounded writes/reads, no retries, no
 * logging of bodies or credentials. Retry-After (seconds) is honored
 * when the server supplies it.
 */
interface GlobalTransport {
    @Throws(IOException::class, GlobalClient.GlobalError::class)
    fun request(
        method: String,
        url: String,
        token: String,
        body: String?
    ): GlobalHttpResult
}

data class GlobalHttpResult(
    val status: Int,
    val body: String,
    /** Parsed Retry-After seconds, when valid and present. */
    val retryAfterSec: Long?
)

class HttpsGlobalTransport : GlobalTransport {
    override fun request(
        method: String,
        url: String,
        token: String,
        body: String?
    ): GlobalHttpResult {
        if (method != "POST" && method != "GET" && method != "DELETE") {
            throw GlobalClient.GlobalError("BAD_METHOD", "unsupported method")
        }
        if (!url.startsWith("https://")) {
            throw GlobalClient.GlobalError("BAD_URL", "https only")
        }
        if (!GlobalContract.tokenValid(token)) {
            throw GlobalClient.GlobalError("BAD_TOKEN", "invalid token shape")
        }
        val bytes = body?.toByteArray(Charsets.UTF_8)
        if (bytes != null && bytes.size > GlobalContract.MAX_REQUEST_BYTES) {
            throw GlobalClient.GlobalError(
                "REQUEST_TOO_LARGE", "request exceeds bound")
        }
        if (method == "GET" && bytes != null) {
            throw GlobalClient.GlobalError("BAD_CALL", "GET has no body")
        }
        val connection = (URL(url).openConnection()
            as HttpsURLConnection).apply {
            connectTimeout = GlobalContract.CONNECT_TIMEOUT_MS
            readTimeout = GlobalContract.READ_TIMEOUT_MS
            requestMethod = method
            if (bytes != null) {
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/json")
            }
            // Install upload token; never logged, never persisted here.
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (bytes != null) {
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val retry = parseRetryAfter(
                connection.getHeaderField("Retry-After"))
            val stream = if (status in 200..299) connection.inputStream
            else connection.errorStream
            val text = if (stream == null) "" else stream.use {
                readBounded(it, GlobalContract.MAX_RESPONSE_BYTES)
            }
            return GlobalHttpResult(status, text, retry)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRetryAfter(value: String?): Long? {
        if (value == null) return null
        val seconds = value.trim().toLongOrNull() ?: return null
        if (seconds < 0 || seconds > 86400) return null
        return seconds
    }

    private fun readBounded(
        stream: java.io.InputStream,
        maxBytes: Int
    ): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw GlobalClient.GlobalError(
                    "REPLY_TOO_LARGE", "reply exceeds bound")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}

/** Scripted transport for host tests. Records every call. */
class FakeGlobalTransport : GlobalTransport {
    data class Call(
        val method: String,
        val url: String,
        val tokenSuffix: String,
        val bodyBytes: Int
    )

    val calls = mutableListOf<Call>()
    /** Request bodies in order (test-only; never logged in prod). */
    val bodies = mutableListOf<String?>()
    /** Next results in order; consumed per request. */
    val script = ArrayDeque<Any>()

    override fun request(
        method: String,
        url: String,
        token: String,
        body: String?
    ): GlobalHttpResult {
        calls.add(Call(method, url, token.takeLast(4),
            body?.toByteArray(Charsets.UTF_8)?.size ?: 0))
        bodies.add(body)
        if (script.isEmpty()) {
            throw IOException("fake transport: no scripted reply")
        }
        return when (val next = script.removeFirst()) {
            is GlobalHttpResult -> next
            is IOException -> throw next
            is GlobalClient.GlobalError -> throw next
            else -> throw IOException("fake transport: bad script entry")
        }
    }
}
