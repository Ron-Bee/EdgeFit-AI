package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufInspectTest {

    /** Little-endian GGUF header builder for tests. */
    private class Head {
        val buf = ByteBuffer.allocate(65536)
            .order(ByteOrder.LITTLE_ENDIAN)

        fun u32(v: Long) {
            buf.putInt(v.toInt())
        }

        fun u64(v: Long) {
            buf.putLong(v)
        }

        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u64(b.size.toLong())
            buf.put(b)
        }

        fun kvString(key: String, value: String) {
            str(key)
            u32(8)
            str(value)
        }

        fun kvU32(key: String, value: Long) {
            str(key)
            u32(4)
            u32(value)
        }

        fun kvTokens(key: String, count: Int) {
            // String array: element type + length + items.
            str(key)
            u32(9)
            u32(8)
            u64(count.toLong())
            repeat(count) { str("t$it") }
        }

        fun bytes(): ByteArray =
            buf.array().copyOf(buf.position())
    }

    private fun llama(
        arch: String? = "llama",
        fileType: Long? = 15,
        tokens: Boolean = true,
        type: String? = null,
        version: Long = 3,
        kvExtra: (Head.() -> Unit)? = null
    ): ByteArray {
        val h = Head()
        h.buf.put("GGUF".toByteArray(Charsets.UTF_8))
        h.u32(version)
        h.u64(10) // tensor count
        // kv count patched below; build body first
        val body = Head()
        if (arch != null) {
            body.kvString("general.architecture", arch)
        }
        if (fileType != null) {
            body.kvU32("general.file_type", fileType)
        }
        body.kvU32("general.quantization_version", 2)
        body.kvString("tokenizer.ggml.model", "llama")
        if (tokens) {
            body.kvTokens("tokenizer.ggml.tokens", 3)
        }
        body.kvU32("llama.context_length", 8192)
        if (type != null) {
            body.kvString("general.type", type)
        }
        kvExtra?.let { body.it() }
        // count KVs by re-walking is overkill: fixed
        // layouts per test keep counts explicit below.
        return h.bytes() + countPrefix(body) + body.bytes()
    }

    private fun countPrefix(body: Head): ByteArray {
        // Caller layouts are fixed; count by parsing.
        val raw = body.bytes()
        var count = 0L
        var pos = 0
        val view = ByteBuffer.wrap(raw)
            .order(ByteOrder.LITTLE_ENDIAN)
        while (pos < raw.size) {
            val klen = view.getLong(pos)
            pos += 8 + klen.toInt() + 4
            val type = view.getInt(pos - 4)
            pos = skipTestValue(view, pos, type, raw.size)
            count++
        }
        return ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(count).array()
    }

    private fun skipTestValue(
        view: ByteBuffer,
        pos: Int,
        type: Int,
        end: Int
    ): Int {
        var p = pos
        when (type) {
            0, 1, 7 -> p += 1
            2, 3 -> p += 2
            4, 5, 6 -> p += 4
            8 -> {
                val n = view.getLong(p).toInt()
                p += 8 + n
            }
            9 -> {
                val et = view.getInt(p)
                val n = view.getLong(p + 4).toInt()
                p += 12
                repeat(n) {
                    p = skipTestValue(view, p, et, end)
                }
            }
            10, 11, 12 -> p += 8
            else -> throw IllegalStateException("t$type")
        }
        check(p <= end)
        return p
    }

    private fun assess(
        bytes: ByteArray,
        fileBytes: Long = 800L * 1024 * 1024,
        availBytes: Long? = 6L * 1024 * 1024 * 1024,
        measured: Set<String> = emptySet(),
        sha: String? = null
    ): GgufInspect.Verdict = GgufInspect.assess(
        GgufInspect.parseHeader(bytes), fileBytes,
        availBytes, measured, sha)

    @Test
    fun validLlamaHeaderParses() {
        val header = GgufInspect.parseHeader(llama())
        assertTrue(header is GgufInspect.Result.Ok)
        val ok = header as GgufInspect.Result.Ok
        assertEquals("llama", ok.header.architecture)
        assertEquals(15L, ok.header.fileType)
        assertEquals("Q4_K_M",
            GgufInspect.ftypeName(15))
        assertEquals("llama", ok.header.tokenizerModel)
        assertTrue(ok.header.hasTokenizerTokens)
        assertEquals(8192L, ok.header.contextLength)
        assertFalse(ok.header.truncated)
    }

    @Test
    fun badMagicIsInvalid() {
        val bad = llama()
        bad[0] = 'X'.code.toByte()
        val verdict = assess(bad)
        assertEquals(
            GgufInspect.Status.INVALID_MODEL,
            verdict.status)
        assertTrue(verdict.reasons.any {
            it.contains("magic")
        })
    }

    @Test
    fun shortFileIsInvalid() {
        val verdict = assess(ByteArray(10))
        assertEquals(
            GgufInspect.Status.INVALID_MODEL,
            verdict.status)
    }

    @Test
    fun wrongVersionIsInvalid() {
        val verdict = assess(llama(version = 99))
        assertEquals(
            GgufInspect.Status.INVALID_MODEL,
            verdict.status)
        assertTrue(verdict.reasons.any {
            it.contains("99")
        })
    }

    @Test
    fun truncatedHeaderStaysUsable() {
        val full = llama()
        // Cut inside the token array: arch already read.
        val cut = full.copyOf(120)
        val header = GgufInspect.parseHeader(cut)
        assertTrue(header is GgufInspect.Result.Ok)
        val ok = header as GgufInspect.Result.Ok
        assertEquals("llama", ok.header.architecture)
        assertTrue(ok.header.truncated)
    }

    @Test
    fun nestedArrayIsInvalid() {
        val h = Head()
        h.buf.put("GGUF".toByteArray(Charsets.UTF_8))
        h.u32(3)
        h.u64(1)
        h.u64(1)
        h.str("x")
        h.u32(9)
        h.u32(9) // array of arrays: forbidden
        h.u64(0)
        val verdict = assess(h.bytes())
        assertEquals(
            GgufInspect.Status.INVALID_MODEL,
            verdict.status)
    }

    @Test
    fun missingArchitectureIsInvalid() {
        val verdict = assess(llama(arch = null))
        assertEquals(
            GgufInspect.Status.INVALID_MODEL,
            verdict.status)
    }

    @Test
    fun bertFamilyIsUnsupported() {
        val verdict = assess(llama(arch = "bert"))
        assertEquals(
            GgufInspect.Status.UNSUPPORTED,
            verdict.status)
        assertTrue(verdict.reasons.any {
            it.contains("embedding")
        })
    }

    @Test
    fun visionArchIsUnsupported() {
        val verdict = assess(llama(arch = "qwen2vl"))
        assertEquals(
            GgufInspect.Status.UNSUPPORTED,
            verdict.status)
    }

    @Test
    fun unknownArchStaysUnverified() {
        val verdict = assess(llama(arch = "frobnicate-7b"))
        assertEquals(
            GgufInspect.Status.SUPPORTED_UNVERIFIED,
            verdict.status)
        assertTrue(verdict.reasons.any {
            it.contains("not in the assessed list")
        })
    }

    @Test
    fun mainstreamArchIsNoted() {
        val verdict = assess(llama(arch = "qwen3"))
        assertEquals(
            GgufInspect.Status.SUPPORTED_UNVERIFIED,
            verdict.status)
        assertTrue(verdict.reasons.any {
            it.contains("text-generation family")
        })
    }

    @Test
    fun unknownFileTypeIsUnsupported() {
        val verdict = assess(llama(fileType = 99))
        assertEquals(
            GgufInspect.Status.UNSUPPORTED,
            verdict.status)
    }

    @Test
    fun removedQuantIsUnsupported() {
        // ftype 5 (Q4_2): support removed from runtime.
        val verdict = assess(llama(fileType = 5))
        assertEquals(
            GgufInspect.Status.UNSUPPORTED,
            verdict.status)
    }

    @Test
    fun missingTokenizerIsUnsupported() {
        val verdict = assess(llama(tokens = false))
        assertEquals(
            GgufInspect.Status.UNSUPPORTED,
            verdict.status)
    }

    @Test
    fun loraAdapterIsUnsupported() {
        val verdict = assess(
            llama(type = "adapter-lora"))
        assertEquals(
            GgufInspect.Status.UNSUPPORTED,
            verdict.status)
    }

    @Test
    fun oversizeFileLacksResources() {
        val verdict = assess(llama(),
            fileBytes = BenchGuards.MAX_MODEL_BYTES + 1)
        assertEquals(
            GgufInspect.Status.INSUFFICIENT_RESOURCES,
            verdict.status)
    }

    @Test
    fun lowMemoryWarnsButAdvisory() {
        val verdict = assess(llama(), availBytes = 512L)
        assertEquals(
            GgufInspect.Status.INSUFFICIENT_RESOURCES,
            verdict.status)
        // MEM-03: advisory only — the run path
        // decides; the verdict must say so.
        assertTrue(verdict.reasons.any {
            it.contains("estimate")
        })
        assertTrue(verdict.runnable)
    }

    @Test
    fun invalidAndUnsupportedBlockRun() {
        assertFalse(assess(ByteArray(10)).runnable)
        assertFalse(
            assess(llama(arch = "bert")).runnable)
        assertFalse(
            assess(llama(fileType = 99)).runnable)
    }

    @Test
    fun unverifiedAndMeasuredAllowRun() {
        assertTrue(assess(llama()).runnable)
        val sha = "ab".repeat(32)
        assertTrue(assess(llama(),
            measured = setOf(sha), sha = sha).runnable)
    }

    @Test
    fun measuredHashPromotesToVerified() {
        val sha = "AB".repeat(32)
        val verdict = assess(llama(),
            measured = setOf("ab".repeat(32)), sha = sha)
        assertEquals(
            GgufInspect.Status.VERIFIED, verdict.status)
    }

    @Test
    fun measurementBeatsResourceEstimate() {
        val sha = "ab".repeat(32)
        val verdict = assess(llama(),
            availBytes = 512L,
            measured = setOf(sha), sha = sha)
        // Execution evidence beats the estimate.
        assertEquals(
            GgufInspect.Status.VERIFIED, verdict.status)
    }

    @Test
    fun staticRefusalStandsOverStaleClaim() {
        // A measured-hash claim cannot rescue bytes
        // that fail to parse: same bytes, same parse.
        val sha = "ab".repeat(32)
        val verdict = assess(ByteArray(10),
            measured = setOf(sha), sha = sha)
        assertEquals(
            GgufInspect.Status.INVALID_MODEL,
            verdict.status)
    }

    @Test
    fun ftypeNamesCoverCommonQuants() {
        assertEquals("F16", GgufInspect.ftypeName(1))
        assertEquals("Q4_0", GgufInspect.ftypeName(2))
        assertEquals("Q8_0", GgufInspect.ftypeName(7))
        assertEquals("Q6_K", GgufInspect.ftypeName(18))
        assertEquals("?", GgufInspect.ftypeName(99))
    }
}
