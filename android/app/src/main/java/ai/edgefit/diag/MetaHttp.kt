package ai.edgefit.diag

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Minimal HTTPS transport for the Meta Model API. Zero dependencies:
 * HttpsURLConnection only, fixed allowlisted endpoint, bounded
 * writes/reads, no retries, no logging of bodies or credentials.
 */
interface MetaTransport {
    /** POSTs a JSON body; returns status + bounded body. Never null. */
    @Throws(IOException::class, MetaApi.MetaError::class)
    fun post(apiKey: String, body: String): MetaHttpResult
}

data class MetaHttpResult(val status: Int, val body: String)

class HttpsMetaTransport : MetaTransport {
    override fun post(apiKey: String, body: String): MetaHttpResult {
        if (!MetaApi.checkUrl(MetaApi.ENDPOINT)) {
            throw MetaApi.MetaError("BAD_URL", "endpoint failed allowlist")
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > MetaApi.MAX_REQUEST_BYTES) {
            throw MetaApi.MetaError("REQUEST_TOO_LARGE", "request exceeds bound")
        }
        if (apiKey.isEmpty()) {
            throw MetaApi.MetaError("NO_KEY", "no API key configured")
        }
        val connection = (URL(MetaApi.ENDPOINT).openConnection()
            as HttpsURLConnection).apply {
            connectTimeout = MetaApi.CONNECT_TIMEOUT_MS
            readTimeout = MetaApi.READ_TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json")
            // The user's key authenticates to Meta; it is never logged,
            // never persisted here, and never sent anywhere else.
            setRequestProperty("Authorization", MetaApi.authHeader(apiKey))
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
            else connection.errorStream
            val text = if (stream == null) "" else stream.use {
                readBounded(it, MetaApi.MAX_RESPONSE_BYTES)
            }
            return MetaHttpResult(status, text)
        } finally {
            connection.disconnect()
        }
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
                throw MetaApi.MetaError(
                    "REPLY_TOO_LARGE", "reply exceeds bound")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
