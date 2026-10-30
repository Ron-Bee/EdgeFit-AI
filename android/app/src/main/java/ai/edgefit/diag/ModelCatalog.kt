package ai.edgefit.diag

import kotlin.math.ceil

/**
 * Bundled read-only GGUF catalog (EF-MODEL-01A): strict
 * parse + validation over the shipped JSON. Pure JVM: no
 * Android, no network, no downloads. Unknown hashes stay
 * null — never invented. A model name alone never
 * establishes artifact identity.
 */
object ModelCatalog {
    const val CATALOG_VERSION = 1
    const val MAX_ENTRIES = 32
    const val MAX_JSON_BYTES = 65536
    const val MAX_ARTIFACT_BYTES = 8L * 1024 * 1024 * 1024
    val ALLOWED_HOSTS = setOf("huggingface.co", "github.com")
    val EVIDENCE_LABELS = setOf("unmeasured", "locally_measured")
    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]*$")
    private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val HEX64_PATTERN = Regex("^[0-9a-f]{64}$")
    private val SIZE_PATTERN =
        Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*(KB|MB|GB)\\s*$")

    class CatalogError(message: String) : Exception(message)

    data class Entry(
        val id: String,
        val displayName: String,
        val basePublisher: String,
        val baseModel: String,
        val ggufPublisher: String,
        val repoUrl: String,
        val artifactPath: String,
        val sourceUrl: String,
        val quantization: String,
        val sizeBytes: Long?,
        val sizeDisplay: String,
        val sha256: String?,
        val license: String,
        val licenseNote: String,
        val verifiedUtc: String,
        val arch: String,
        val contextLength: Int?,
        val runtimeSupported: Boolean,
        val runtimeRef: String,
        val runtimeMethod: String,
        val rationale: String,
        val limitations: String,
        val performanceEvidence: String
    )

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): List<Entry> {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            throw CatalogError("catalog exceeds $MAX_JSON_BYTES bytes")
        }
        val top = try {
            SessionJson.parse(text) as? Map<String, Any?>
        } catch (e: Exception) {
            throw CatalogError("catalog is not valid JSON")
        } ?: throw CatalogError("catalog root must be an object")
        val version = (top["catalog_version"] as? Number)?.toInt()
        if (version != CATALOG_VERSION) {
            throw CatalogError(
                "unsupported catalog_version $version")
        }
        val raw = top["entries"] as? List<*>
            ?: throw CatalogError("entries must be an array")
        if (raw.isEmpty() || raw.size > MAX_ENTRIES) {
            throw CatalogError("entries must hold 1..$MAX_ENTRIES")
        }
        val entries = raw.mapIndexed { index, item ->
            parseEntry(item as? Map<String, Any?>
                ?: throw CatalogError("entry $index not an object"),
                index)
        }
        val ids = entries.map { it.id }
        if (ids.size != ids.toSet().size) {
            throw CatalogError("duplicate entry id")
        }
        return entries
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEntry(map: Map<String, Any?>, index: Int): Entry {
        fun req(key: String): String {
            val value = map[key] as? String
            if (value.isNullOrBlank()) {
                throw CatalogError("entry $index: $key required")
            }
            return value
        }
        val id = req("id")
        if (!ID_PATTERN.matches(id)) {
            throw CatalogError("entry $index: bad id $id")
        }
        val repoUrl = checkUrl(req("repo_url"), index)
        val sourceUrl = checkUrl(req("source_url"), index)
        if (!sourceUrl.startsWith("$repoUrl/")) {
            throw CatalogError(
                "entry $index: source_url outside repo_url")
        }
        val artifact = req("artifact_path")
        if (!artifact.endsWith(".gguf") || artifact.startsWith("/") ||
            ".." in artifact || artifact.any { it.isWhitespace() }
        ) {
            throw CatalogError("entry $index: bad artifact_path")
        }
        val sizeBytes = when (val raw = map["size_bytes"]) {
            null -> null
            is Number -> raw.toLong().also {
                if (it !in 1..MAX_ARTIFACT_BYTES) {
                    throw CatalogError("entry $index: bad size_bytes")
                }
            }
            else -> throw CatalogError("entry $index: bad size_bytes")
        }
        val sha = map["sha256"] as? String
        if (sha != null && !HEX64_PATTERN.matches(sha)) {
            throw CatalogError("entry $index: sha256 must be 64 hex")
        }
        val verified = req("verified_utc")
        if (!DATE_PATTERN.matches(verified)) {
            throw CatalogError("entry $index: bad verified_utc")
        }
        val context = when (val raw = map["context_length"]) {
            null -> null
            is Number -> raw.toInt().also {
                if (it < 1) {
                    throw CatalogError("entry $index: bad context")
                }
            }
            else -> throw CatalogError("entry $index: bad context")
        }
        val runtimeSupported = map["runtime_supported"] as? Boolean
            ?: throw CatalogError(
                "entry $index: runtime_supported boolean")
        val evidence = req("performance_evidence")
        if (evidence !in EVIDENCE_LABELS) {
            throw CatalogError("entry $index: bad evidence label")
        }
        return Entry(
            id = id,
            displayName = req("display_name"),
            basePublisher = req("base_publisher"),
            baseModel = req("base_model"),
            ggufPublisher = req("gguf_publisher"),
            repoUrl = repoUrl,
            artifactPath = artifact,
            sourceUrl = sourceUrl,
            quantization = req("quantization"),
            sizeBytes = sizeBytes,
            sizeDisplay = req("size_display"),
            sha256 = sha,
            license = req("license"),
            licenseNote = req("license_note"),
            verifiedUtc = verified,
            arch = req("arch"),
            contextLength = context,
            runtimeSupported = runtimeSupported,
            runtimeRef = req("runtime_ref"),
            runtimeMethod = req("runtime_method"),
            rationale = rationale(req("suitability_rationale"), index),
            limitations = req("limitations"),
            performanceEvidence = evidence
        )
    }

    private fun rationale(value: String, index: Int): String {
        // Catalog copy must never promise speed: fail the
        // entry rather than ship a fabricated claim. Only
        // comparative/quantitative tokens are screened;
        // plain instructional words are curator-reviewed.
        for (banned in listOf("faster", "fastest", "speedup",
            "speed-up", "tok/s", "tokens/second", "x faster",
            "performance gain", "outperforms")) {
            if (banned in value.lowercase()) {
                throw CatalogError(
                    "entry $index: rationale promises performance")
            }
        }
        return value
    }

    private fun checkUrl(value: String, index: Int): String {
        if (!value.startsWith("https://")) {
            throw CatalogError("entry $index: url must be https")
        }
        if (value.any { it.isWhitespace() || it.code < 32 }) {
            throw CatalogError("entry $index: url has bad chars")
        }
        val rest = value.removePrefix("https://")
        val host = rest.substringBefore("/")
        if (host !in ALLOWED_HOSTS) {
            throw CatalogError("entry $index: host $host not allowed")
        }
        if ("/" !in rest || rest.endsWith("/")) {
            throw CatalogError("entry $index: url needs a path")
        }
        return value
    }

    /**
     * Conservative artifact-size upper bound in bytes: exact
     * size_bytes when present, else the listed display size
     * ("639 MB") read with MiB units and rounded up. Null
     * when neither exists. Display-listed sizes are always
     * an UPPER bound, never exact.
     */
    fun sizeBoundBytes(entry: Entry): Long? {
        entry.sizeBytes?.let {
            return it
        }
        val match = SIZE_PATTERN.matchEntire(entry.sizeDisplay)
            ?: return null
        val amount = match.groupValues[1].toDoubleOrNull()
            ?: return null
        val unit = when (match.groupValues[2]) {
            "KB" -> 1024L
            "MB" -> 1024L * 1024L
            else -> 1024L * 1024L * 1024L
        }
        if (!amount.isFinite() || amount <= 0) return null
        return ceil(amount).toLong() * unit
    }

    /** Basis label for the bound above: exact, listed, unknown. */
    fun sizeBasis(entry: Entry): String = when {
        entry.sizeBytes != null -> "exact_bytes"
        sizeBoundBytes(entry) != null -> "listed_display_upper_bound"
        else -> "unknown"
    }
}
