package ai.edgefit.diag

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EF-ANDROID-AERO-BYOLLM-FINAL-02 genuine BYOLLM: GGUF
 * header inspection plus honest compatibility verdicts.
 * Pure JVM over header bytes — no inference, no storage,
 * no network. Never infers runtime support from the
 * file extension: the verdict reads the container
 * (magic/version/architecture/quantization/tokenizer),
 * checks resources against the existing guards, and
 * promotes to VERIFIED only on an exact-hash COMPLETED
 * run. Unknown architectures stay UNVERIFIED (never
 * UNSUPPORTED) with the load attempt as arbiter; only
 * provably unrunnable files (bad container, removed or
 * unknown quant family, non-generative arch, missing
 * tokenizer) refuse. Memory estimates stay advisory
 * per MEM-03 — the run path decides.
 */
object GgufInspect {

    /** Bytes of header scanned at most (vocabs are big). */
    const val HEADER_CAP_BYTES = 8L * 1024 * 1024
    const val MAX_KV = 100_000L
    const val MAX_STR = 10L * 1024 * 1024
    const val MAX_ARRAY = 5_000_000L

    enum class Status {
        VERIFIED,
        SUPPORTED_UNVERIFIED,
        UNSUPPORTED,
        INSUFFICIENT_RESOURCES,
        INVALID_MODEL
    }

    data class Header(
        val version: Long,
        val tensorCount: Long,
        val kvCount: Long,
        val architecture: String?,
        val modelType: String?,
        val fileType: Long?,
        val quantVersion: Long?,
        val tokenizerModel: String?,
        val hasTokenizerTokens: Boolean,
        val contextLength: Long?,
        /** True when KVs remained past the scanned bytes. */
        val truncated: Boolean
    )

    sealed interface Result {
        data class Ok(val header: Header) : Result
        data class Invalid(val reason: String) : Result
    }

    data class Verdict(
        val status: Status,
        val reasons: List<String>,
        /** False only when staging/running must refuse. */
        val runnable: Boolean,
        val header: Header?
    )

    /** ftype enum mirrored from llama.h (v0.7.0 vendored). */
    private val FTYPE_NAMES = mapOf(
        0L to "ALL_F32", 1L to "F16", 2L to "Q4_0",
        3L to "Q4_1", 7L to "Q8_0", 8L to "Q5_0",
        9L to "Q5_1", 10L to "Q2_K", 11L to "Q3_K_S",
        12L to "Q3_K_M", 13L to "Q3_K_L", 14L to "Q4_K_S",
        15L to "Q4_K_M", 16L to "Q5_K_S", 17L to "Q5_K_M",
        18L to "Q6_K", 19L to "IQ2_XXS", 20L to "IQ2_XS",
        21L to "Q2_K_S", 22L to "IQ3_XS", 23L to "IQ3_XXS",
        24L to "IQ1_S", 25L to "IQ4_NL", 26L to "IQ3_S",
        27L to "IQ3_M", 28L to "IQ2_S", 29L to "IQ2_M",
        30L to "IQ4_XS", 31L to "IQ1_M", 32L to "BF16",
        36L to "TQ1_0", 37L to "TQ2_0",
        38L to "MXFP4_MOE", 39L to "NVFP4"
    )
    private val FTYPE_REMOVED = setOf(4L, 5L, 6L, 33L, 34L, 35L)

    fun ftypeName(ftype: Long): String =
        FTYPE_NAMES[ftype] ?: "?"

    /**
     * Common causal text-generation families (subset of
     * the vendored llama-arch.h enum). Anything else that
     * is not provably non-generative stays UNVERIFIED —
     * never UNSUPPORTED — with the load attempt deciding.
     */
    private val MAINSTREAM = setOf(
        "llama", "llama4", "mistral", "mistral3",
        "mistral4", "qwen", "qwen2", "qwen2moe",
        "qwen3", "qwen3moe", "qwen3next", "qwen35",
        "qwen35moe", "gemma", "gemma2", "gemma3",
        "gemma4", "phi2", "phi3", "deepseek",
        "deepseek2", "deepseek32", "falcon",
        "falcon_h1", "mpt", "gpt2", "gptj", "gptneox",
        "starcoder", "starcoder2", "stablelm",
        "internlm2", "orion", "baichuan", "minicpm",
        "minicpm3", "command_r", "cohere2",
        "cohere2moe", "olmo", "olmo2", "olmoe",
        "glm4", "glm4_moe", "chatglm", "jais",
        "jais2", "nemotron", "exaone", "exaone4",
        "exaone_moe", "rwkv6", "rwkv7", "mamba",
        "mamba2", "jamba", "plamo", "plamo2",
        "plamo3", "smollm3", "lfm2", "smallthinker",
        "seed_oss", "ernie4_5", "hunyuan_moe",
        "hunyuan_dense", "granite", "granite_moe",
        "granite_hybrid", "granite_switch",
        "granite_swa", "dbrx", "arctic", "openelm",
        "grok", "deci", "xverse", "maple"
    )

    /**
     * Substring matchers for architectures that cannot
     * generate text through the decode-only JNI path.
     * Each pair is (fragment, human class).
     */
    private val NON_GENERATIVE = listOf(
        "bert" to "embedding/encoder family (no text generation)",
        "clip" to "vision encoder (no text generation)",
        "t5" to "encoder-decoder family (needs an encode path)",
        "cogvlm" to "vision/multimodal family",
        "chameleon" to "multimodal family",
        "vl" to "vision/multimodal family",
        "ocr" to "vision (OCR) family",
        "tts" to "audio synthesis family",
        "wavtokenizer" to "audio family",
        "talkie" to "audio family",
        "embed" to "embedding family (no text generation)",
        "llada" to "diffusion family (needs a diffusion path)",
        "dream" to "diffusion family (needs a diffusion path)",
        "dflash" to "diffusion family (needs a diffusion path)",
        "eagle" to "speculative-draft family (not standalone)"
    )

    fun parseHeader(bytes: ByteArray): Result {
        if (bytes.size < 24) {
            return Result.Invalid(
                "file too short for a GGUF header " +
                    "(${bytes.size} bytes)")
        }
        val view = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        if (bytes[0] != 'G'.code.toByte() ||
            bytes[1] != 'G'.code.toByte() ||
            bytes[2] != 'U'.code.toByte() ||
            bytes[3] != 'F'.code.toByte()
        ) {
            return Result.Invalid("bad GGUF magic")
        }
        pos = 4
        val version = view.getInt(pos).toLong() and 0xFFFFFFFFL
        pos += 4
        if (version != 3L) {
            return Result.Invalid(
                "unsupported GGUF version $version (need v3)")
        }
        val tensors = view.getLong(pos)
        pos += 8
        if (tensors <= 0) {
            return Result.Invalid("tensor count is zero")
        }
        if (tensors > 10_000_000L) {
            return Result.Invalid("tensor count absurd")
        }
        val kvTotal = view.getLong(pos)
        pos += 8
        if (kvTotal < 0 || kvTotal > MAX_KV) {
            return Result.Invalid("metadata count absurd")
        }
        val scalars = mutableMapOf<String, Any>()
        var hasTokens = false
        var truncated = false
        var read = 0L
        try {
        while (read < kvTotal) {
            if (pos + 8 > bytes.size) {
                truncated = true
                break
            }
            val klen = view.getLong(pos)
            pos += 8
            if (klen < 0 || klen > MAX_STR) {
                return Result.Invalid(
                    "metadata key length absurd")
            }
            if (pos + klen + 4 > bytes.size) {
                truncated = true
                break
            }
            val key = String(
                bytes, pos, klen.toInt(), Charsets.UTF_8)
            pos += klen.toInt()
            val type = view.getInt(pos)
            pos += 4
            if (type < 0 || type > 12) {
                return Result.Invalid(
                    "bad metadata value type")
            }
            if (key == "tokenizer.ggml.tokens" && type == 9) {
                hasTokens = true
            }
            val after = skipValue(view, bytes.size, pos, type)
            if (after < 0) {
                truncated = true
                break
            }
            if (isScalarInterest(key, type)) {
                scalars[key] = readScalar(view, pos, type)
            }
            pos = after
            read++
        }
        } catch (c: Corrupt) {
            return Result.Invalid(c.message ?: "corrupt header")
        }
        val arch = scalars["general.architecture"] as? String
        return Result.Ok(Header(
            version = version,
            tensorCount = tensors,
            kvCount = kvTotal,
            architecture = arch,
            modelType = scalars["general.type"] as? String,
            fileType = scalars["general.file_type"] as? Long,
            quantVersion =
                scalars["general.quantization_version"] as? Long,
            tokenizerModel =
                scalars["tokenizer.ggml.model"] as? String,
            hasTokenizerTokens = hasTokens,
            contextLength = arch?.let {
                scalars["$it.context_length"] as? Long
            },
            truncated = truncated
        ))
    }

    fun inspectFile(file: File): Result {
        if (!file.exists()) {
            return Result.Invalid("file not found")
        }
        if (file.length() == 0L) {
            return Result.Invalid("file is empty")
        }
        val cap = HEADER_CAP_BYTES.coerceAtMost(file.length())
            .toInt()
        val prefix = ByteArray(cap)
        var filled = 0
        try {
            FileInputStream(file).use { input ->
                while (filled < cap) {
                    val n = input.read(
                        prefix, filled, cap - filled)
                    if (n < 0) break
                    filled += n
                }
            }
        } catch (e: Exception) {
            return Result.Invalid(
                "cannot read file (${e.message})")
        }
        return parseHeader(
            if (filled == cap) prefix
            else prefix.copyOf(filled))
    }

    fun assess(
        parse: Result,
        fileBytes: Long,
        availBytes: Long?,
        measuredShas: Set<String>,
        sha256: String?
    ): Verdict {
        if (parse is Result.Invalid) {
            return Verdict(Status.INVALID_MODEL,
                listOf("invalid GGUF: ${parse.reason}"),
                false, null)
        }
        val header = (parse as Result.Ok).header
        val notes = mutableListOf<String>()
        if (header.truncated) {
            notes.add("header extends past the 8 MiB " +
                "scan window (partial read)")
        }
        val arch = header.architecture
        if (arch.isNullOrBlank()) {
            return Verdict(Status.INVALID_MODEL,
                notes + "missing general.architecture",
                false, header)
        }
        if (header.fileType == null) {
            return Verdict(Status.INVALID_MODEL,
                notes + "missing general.file_type",
                false, header)
        }
        val type = header.modelType
        if (type != null && type != "model") {
            return Verdict(Status.UNSUPPORTED,
                notes + "not a full model " +
                    "(general.type=$type)",
                false, header)
        }
        val ftype = header.fileType
        if (ftype in FTYPE_REMOVED) {
            return Verdict(Status.UNSUPPORTED,
                notes + "quantization family " +
                    "file_type=$ftype had runtime support " +
                    "removed",
                false, header)
        }
        if (FTYPE_NAMES[ftype] == null) {
            return Verdict(Status.UNSUPPORTED,
                notes + "unrecognized quantization " +
                    "family (file_type=$ftype)",
                false, header)
        }
        if (!header.hasTokenizerTokens) {
            return Verdict(Status.UNSUPPORTED,
                notes + "no embedded tokenizer " +
                    "(tokenizer.ggml.tokens missing)",
                false, header)
        }
        val lowered = arch.lowercase()
        val blocked = NON_GENERATIVE.firstOrNull { (frag, _) ->
            lowered.contains(frag)
        }
        if (blocked != null) {
            return Verdict(Status.UNSUPPORTED,
                notes + "unsupported architecture " +
                    "'$arch': ${blocked.second}",
                false, header)
        }
        notes.add("quantization ${ftypeName(ftype)} " +
            "(file_type=$ftype)")
        if (lowered in MAINSTREAM) {
            notes.add("architecture '$arch' is a common " +
                "text-generation family (execution still " +
                "untested)")
        } else {
            notes.add("architecture '$arch' is not in " +
                "the assessed list (execution untested; " +
                "load attempt decides)")
        }
        header.contextLength?.let {
            notes.add("native context length $it tokens")
        }
        val measured = !sha256.isNullOrBlank() &&
            measuredShas.any { it.equals(sha256, true) }
        if (measured) {
            return Verdict(Status.VERIFIED,
                notes + "exact-hash COMPLETED run on record",
                true, header)
        }
        if (fileBytes > BenchGuards.MAX_MODEL_BYTES) {
            return Verdict(Status.INSUFFICIENT_RESOURCES,
                notes + "model exceeds bound " +
                    "($fileBytes > " +
                    "${BenchGuards.MAX_MODEL_BYTES} bytes)",
                false, header)
        }
        if (availBytes != null && availBytes <
            BenchGuards.requiredAvailBytes(fileBytes)
        ) {
            return Verdict(Status.INSUFFICIENT_RESOURCES,
                notes +
                    BenchGuards.refusalDetail(
                        availBytes, fileBytes) +
                    " memory estimate only — the run " +
                    "path decides",
                true, header)
        }
        return Verdict(Status.SUPPORTED_UNVERIFIED,
            notes, true, header)
    }

    private fun isScalarInterest(key: String, type: Int): Boolean {
        if (type != 8 && type != 4) return false
        if (type == 8) {
            return key == "general.architecture" ||
                key == "general.type" ||
                key == "tokenizer.ggml.model"
        }
        return key == "general.file_type" ||
            key == "general.quantization_version" ||
            key.endsWith(".context_length")
    }

    private fun readScalar(
        view: ByteBuffer,
        pos: Int,
        type: Int
    ): Any {
        if (type == 4) {
            return view.getInt(pos).toLong() and 0xFFFFFFFFL
        }
        val n = view.getLong(pos).toInt()
        val bytes = ByteArray(n)
        for (i in 0 until n) {
            bytes[i] = view.get(pos + 8 + i)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * End offset of the value at [pos], or -1 when the
     * value runs past [end] (truncated, not corrupt).
     * Throws only on structurally impossible shapes.
     */
    private fun skipValue(
        view: ByteBuffer,
        end: Int,
        pos: Int,
        type: Int
    ): Int {
        var p = pos
        when (type) {
            0, 1, 7 -> p += 1
            2, 3 -> p += 2
            4, 5, 6 -> p += 4
            8 -> {
                if (p + 8 > end) return -1
                val n = view.getLong(p)
                if (n < 0 || n > MAX_STR) {
                    throw Corrupt("string length absurd")
                }
                p += 8 + n.toInt()
            }
            9 -> {
                if (p + 12 > end) return -1
                val et = view.getInt(p)
                if (et == 9) throw Corrupt("nested array")
                if (et < 0 || et > 12) throw Corrupt(
                    "bad array element type")
                val n = view.getLong(p + 4)
                if (n < 0 || n > MAX_ARRAY) {
                    throw Corrupt("array length absurd")
                }
                p += 12
                repeat(n.toInt()) {
                    p = skipValue(view, end, p, et)
                    if (p < 0) return -1
                }
            }
            10, 11, 12 -> p += 8
            else -> throw Corrupt("bad value type")
        }
        if (p > end) return -1
        return p
    }

    private class Corrupt(message: String) :
        IllegalStateException(message)
}
