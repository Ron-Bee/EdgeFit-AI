package ai.edgefit.diag

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for EF-MODEL-01A: bundled catalog parsing,
 * validation, conservative suitability, evidence classes,
 * and card builders. The shipped JSON is validated as a
 * fixture too — but never fetched or downloaded.
 */
class ModelCatalogTest {

    private fun entryJson(
        id: String = "tiny-test-q4_k_m",
        repo: String = "https://huggingface.co/TestOrg/Tiny-GGUF",
        artifact: String = "tiny-q4_k_m.gguf",
        sizeBytes: String = "null",
        sizeDisplay: String = "639 MB",
        sha: String = "null",
        license: String = "apache-2.0",
        runtime: String = "true",
        context: String = "8192",
        rationale: String = "Small artifact worth evaluating " +
            "when memory headroom is limited.",
        evidence: String = "unmeasured",
        extra: String = ""
    ): String = """
        {"id": "$id",
         "display_name": "Tiny Test (Q4_K_M)",
         "base_publisher": "TestOrg",
         "base_model": "TestOrg/Tiny",
         "gguf_publisher": "TestOrg (official)",
         "repo_url": "$repo",
         "artifact_path": "$artifact",
         "source_url": "$repo/blob/main/$artifact",
         "quantization": "Q4_K_M",
         "size_bytes": $sizeBytes,
         "size_display": "$sizeDisplay",
         "sha256": $sha,
         "license": "$license",
         "license_note": "Verify before use.",
         "verified_utc": "2026-10-29",
         "arch": "llama",
         "context_length": $context,
         "runtime_supported": $runtime,
         "runtime_ref": "llama.cpp v0.7.0 @ 620d2225",
         "runtime_method": "arch enum present; untested.",
         "suitability_rationale": "$rationale",
         "limitations": "Unverified hash; unmeasured.",
         "performance_evidence": "$evidence"$extra}
    """.trimIndent()

    private fun catalogJson(vararg entries: String): String =
        """{"catalog_version": 1, "entries": [${entries.joinToString(",")}]}"""

    private fun device(
        abis: List<String> = listOf("arm64-v8a"),
        avail: Long? = 3L * 1024 * 1024 * 1024,
        measured: Set<String> = emptySet()
    ) = ModelSuitability.DeviceInput(
        supportedAbis = abis,
        availBytes = avail,
        availSampledAt = "00:00:01 local",
        measuredArtifactShas = measured
    )

    private fun shipped(): List<ModelCatalog.Entry> {
        val file = File("src/main/res/raw/model_catalog.json")
        assertTrue("shipped catalog missing at ${file.absolutePath}",
            file.isFile)
        return ModelCatalog.parse(file.readText(Charsets.UTF_8))
    }

    // ---- parsing + validation ----

    @Test
    fun validFixtureParses() {
        val entries = ModelCatalog.parse(catalogJson(entryJson()))
        assertEquals(1, entries.size)
        assertEquals("tiny-test-q4_k_m", entries[0].id)
        assertNull(entries[0].sizeBytes)
        assertNull(entries[0].sha256)
    }

    @Test
    fun shippedCatalogParsesWithFiveHonestEntries() {
        val entries = shipped()
        assertEquals(5, entries.size)
        assertEquals(5, entries.map { it.id }.toSet().size)
        for (entry in entries) {
            assertTrue(entry.sourceUrl.startsWith("https://"))
            assertTrue(entry.artifactPath.endsWith(".gguf"))
            // Nothing invented: hashes explicitly absent,
            // performance explicitly unmeasured.
            assertNull(entry.sha256)
            assertEquals("unmeasured", entry.performanceEvidence)
            assertTrue(entry.runtimeSupported)
        }
    }

    @Test
    fun shippedCatalogMakesNoSpeedClaims() {
        for (entry in shipped()) {
            val copy = (entry.rationale + " " + entry.limitations)
                .lowercase()
            // Mirrors the parser screen: comparative /
            // quantitative promises are refused; plain
            // instructional words are curator-reviewed.
            for (banned in listOf("tok/s", "tokens/second",
                "speedup", "speed-up", "faster", "fastest",
                "performance gain", "outperforms")) {
                assertFalse("speed claim in ${entry.id}",
                    banned in copy)
            }
        }
    }

    @Test
    fun malformedJsonRejected() {
        try {
            ModelCatalog.parse("{not json")
            fail("malformed catalog accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("valid JSON"))
        }
    }

    @Test
    fun badVersionRejected() {
        try {
            ModelCatalog.parse(
                """{"catalog_version": 99, "entries": []}""")
            fail("bad version accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("catalog_version"))
        }
    }

    @Test
    fun oversizedRejected() {
        try {
            ModelCatalog.parse(
                " ".repeat(ModelCatalog.MAX_JSON_BYTES + 1))
            fail("oversized catalog accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("exceeds"))
        }
    }

    @Test
    fun duplicateIdRejected() {
        try {
            ModelCatalog.parse(catalogJson(entryJson(), entryJson()))
            fail("duplicate id accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test
    fun httpSchemeRejected() {
        val bad = entryJson(
            repo = "http://huggingface.co/TestOrg/Tiny-GGUF")
        try {
            ModelCatalog.parse(catalogJson(bad))
            fail("http url accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun unapprovedHostRejected() {
        val bad = entryJson(
            repo = "https://files.example.com/TestOrg/Tiny")
        try {
            ModelCatalog.parse(catalogJson(bad))
            fail("unapproved host accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("not allowed"))
        }
    }

    @Test
    fun sourceOutsideRepoRejected() {
        val entry = entryJson().replace(
            """"source_url": "https://huggingface.co/TestOrg/Tiny-GGUF/blob/main/tiny-q4_k_m.gguf"""",
            """"source_url": "https://huggingface.co/Other/Repo/blob/main/evil.gguf"""")
        try {
            ModelCatalog.parse(catalogJson(entry))
            fail("foreign source_url accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("outside repo"))
        }
    }

    @Test
    fun badArtifactPathRejected() {
        for (path in listOf("tiny.bin", "/abs/tiny.gguf",
            "../escape.gguf")) {
            try {
                ModelCatalog.parse(catalogJson(entryJson(
                    artifact = path)))
                fail("artifact $path accepted")
            } catch (e: ModelCatalog.CatalogError) {
                assertTrue(e.message!!.contains("artifact_path"))
            }
        }
        // A spaced filename poisons its own source URL;
        // either gate may fire, but it must refuse.
        try {
            ModelCatalog.parse(catalogJson(entryJson(
                artifact = "with space.gguf")))
            fail("spaced artifact accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("artifact_path") ||
                e.message!!.contains("bad chars"))
        }
    }

    @Test
    fun badShaRejected() {
        try {
            ModelCatalog.parse(catalogJson(entryJson(
                sha = "\"zz\"")))
            fail("bad sha accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("sha256"))
        }
    }

    @Test
    fun missingLicenseRejected() {
        val entry = entryJson().replace(
            """"license": "apache-2.0"""", """"license": """"")
        try {
            ModelCatalog.parse(catalogJson(entry))
            fail("missing license accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("license"))
        }
    }

    @Test
    fun rationaleSpeedClaimRejected() {
        try {
            ModelCatalog.parse(catalogJson(entryJson(
                rationale = "Runs faster than the 1B model.")))
            fail("speed-claim rationale accepted")
        } catch (e: ModelCatalog.CatalogError) {
            assertTrue(e.message!!.contains("performance"))
        }
    }

    // ---- size bounds ----

    @Test
    fun sizeBoundPrefersExactBytes() {
        val entries = ModelCatalog.parse(catalogJson(
            entryJson(sizeBytes = "12345")))
        assertEquals(12345L,
            ModelCatalog.sizeBoundBytes(entries[0]))
        assertEquals("exact_bytes",
            ModelCatalog.sizeBasis(entries[0]))
    }

    @Test
    fun sizeBoundParsesListedDisplayConservatively() {
        val mb = ModelCatalog.parse(catalogJson(
            entryJson(sizeDisplay = "639 MB")))[0]
        assertEquals(639L * 1024 * 1024,
            ModelCatalog.sizeBoundBytes(mb))
        // Fractional GB rounds UP to a full upper bound.
        val gb = ModelCatalog.parse(catalogJson(
            entryJson(sizeDisplay = "1.5 GB")))[0]
        assertEquals(2L * 1024 * 1024 * 1024,
            ModelCatalog.sizeBoundBytes(gb))
        assertEquals("listed_display_upper_bound",
            ModelCatalog.sizeBasis(gb))
    }

    @Test
    fun sizeBoundNullWhenUnparseable() {
        val entries = ModelCatalog.parse(catalogJson(
            entryJson(sizeDisplay = "unknown size")))
        assertNull(ModelCatalog.sizeBoundBytes(entries[0]))
        assertEquals("unknown", ModelCatalog.sizeBasis(entries[0]))
    }

    // ---- suitability ----

    @Test
    fun likelyWhenArchFitsAndMemoryFits() {
        val entry = ModelCatalog.parse(catalogJson(entryJson()))[0]
        val result = ModelSuitability.assess(entry, device())
        assertEquals(ModelSuitability.LIKELY_COMPATIBLE,
            result.status)
        assertFalse(result.measuredLocally)
        val reasons = result.reasons.joinToString("\n")
        assertTrue(reasons.contains("llama"))
        assertTrue(reasons.contains("upper bound"))
        assertTrue(reasons.contains("SHA-256 unverified"))
        assertTrue(reasons.contains("fits now"))
    }

    @Test
    fun verifiedOnlyWithLocalExactHashMeasurement() {
        val sha = "ab".repeat(32)
        val entry = ModelCatalog.parse(catalogJson(
            entryJson(sha = "\"$sha\"")))[0]
        val measured = ModelSuitability.assess(entry,
            device(measured = setOf(sha)))
        assertEquals(ModelSuitability.VERIFIED_COMPATIBLE,
            measured.status)
        assertTrue(measured.measuredLocally)
        val unmeasured = ModelSuitability.assess(entry, device())
        assertEquals(ModelSuitability.LIKELY_COMPATIBLE,
            unmeasured.status)
    }

    @Test
    fun unsupportedAbi() {
        val entry = ModelCatalog.parse(catalogJson(entryJson()))[0]
        val result = ModelSuitability.assess(entry,
            device(abis = listOf("armeabi-v7a")))
        assertEquals(ModelSuitability.UNSUPPORTED, result.status)
        assertTrue(result.reasons.joinToString(" ")
            .contains("arm64-v8a"))
    }

    @Test
    fun unsupportedRuntimeArch() {
        val entry = ModelCatalog.parse(catalogJson(
            entryJson(runtime = "false")))[0]
        val result = ModelSuitability.assess(entry, device())
        assertEquals(ModelSuitability.UNSUPPORTED, result.status)
    }

    @Test
    fun oversizedArtifactUnsupported() {
        val entry = ModelCatalog.parse(catalogJson(
            entryJson(sizeDisplay = "3 GB")))[0]
        val result = ModelSuitability.assess(entry, device(
            avail = 8L * 1024 * 1024 * 1024))
        assertEquals(ModelSuitability.UNSUPPORTED, result.status)
        assertTrue(result.reasons.joinToString(" ")
            .contains("guard ceiling"))
    }

    @Test
    fun unknownSizeIsInsufficientEvidence() {
        val entry = ModelCatalog.parse(catalogJson(
            entryJson(sizeDisplay = "unknown size")))[0]
        val result = ModelSuitability.assess(entry, device())
        assertEquals(ModelSuitability.INSUFFICIENT_EVIDENCE,
            result.status)
    }

    @Test
    fun tightMemoryIsACaveatNotAVerdict() {
        val entry = ModelCatalog.parse(catalogJson(entryJson()))[0]
        // 639 MiB bound + 1 GiB headroom > 1 GiB free.
        val result = ModelSuitability.assess(entry,
            device(avail = 1L * 1024 * 1024 * 1024))
        assertEquals(ModelSuitability.LIKELY_COMPATIBLE,
            result.status)
        assertTrue(result.reasons.joinToString("\n")
            .contains("insufficient memory right now"))
    }

    @Test
    fun unknownMemoryIsLabeled() {
        val entry = ModelCatalog.parse(catalogJson(entryJson()))[0]
        val result = ModelSuitability.assess(entry,
            device(avail = null))
        assertTrue(result.reasons.joinToString("\n")
            .contains("unknown"))
    }

    // ---- evidence classes + cards ----

    @Test
    fun evidenceClassesAreDistinctAndHonest() {
        val estimate = ModelSuitability.evidenceLabel(
            ModelSuitability.evidenceClass(false))
        val measured = ModelSuitability.evidenceLabel(
            ModelSuitability.evidenceClass(true))
        assertTrue(estimate.contains("no measurement"))
        assertTrue(measured.contains("this device"))
        // All five classes stay expressible for future
        // sources; none may collapse into another.
        val labels = ModelSuitability.EvidenceClass.values()
            .map { ModelSuitability.evidenceLabel(it) }
        assertEquals(5, labels.toSet().size)
    }

    @Test
    fun suitabilityAccentsFollowSemantics() {
        assertEquals(InsightCards.Accent.MEASURED,
            InsightCards.suitabilityAccent(
                ModelSuitability.VERIFIED_COMPATIBLE))
        assertEquals(InsightCards.Accent.PREDICTED,
            InsightCards.suitabilityAccent(
                ModelSuitability.LIKELY_COMPATIBLE))
        assertEquals(InsightCards.Accent.ERROR,
            InsightCards.suitabilityAccent(
                ModelSuitability.UNSUPPORTED))
        assertEquals(InsightCards.Accent.NONE,
            InsightCards.suitabilityAccent(
                ModelSuitability.INSUFFICIENT_EVIDENCE))
    }

    @Test
    fun modelCardLineCarriesStatusSizeLicenseEvidence() {
        val entry = ModelCatalog.parse(catalogJson(entryJson()))[0]
        val line = InsightCards.modelCardLine(entry,
            ModelSuitability.assess(entry, device()))
        assertTrue(line.contains("Tiny Test"))
        assertTrue(line.contains("639 MB"))
        assertTrue(line.contains("Likely compatible"))
        assertTrue(line.contains("apache-2.0"))
        assertTrue(line.contains("no measurement"))
    }

    @Test
    fun modelEntrySectionsExposeFullProvenance() {
        val entry = ModelCatalog.parse(catalogJson(entryJson()))[0]
        val sections = InsightCards.modelEntrySections(entry,
            ModelSuitability.assess(entry, device()))
        // EF-MODEL-01B order: identity, comparison,
        // verdicts, memory, evidence, limits, raw proof.
        val titles = sections.map { it.title }
        assertEquals(listOf("Candidate", "Comparison vs current",
            "Compatibility", "Memory considerations",
            "Local evidence", "Evidence & limits",
            "Technical evidence"), titles)
        val technical = sections.last()
        assertTrue(technical.details!!.contains("tiny-q4_k_m.gguf"))
        assertTrue(technical.details!!.contains("unverified"))
        val compat = sections.first { it.title == "Compatibility" }
        assertTrue(compat.rows.any {
            it.label == "Status" && "Likely" in it.value
        })
    }

    // ---- Phase 8 clarity (presentation only) ----

    @Test
    fun repeatSessionsExplainTheBoundary() {
        val record = SessionRecord(
            sessionId = "SES-x", mode = "byok",
            state = SessionState.COMPARISON_READY,
            createdAtUtc = "", updatedAtUtc = "",
            deviceModel = null, profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 1,
            modelDisplay = "m", workloadId = "w",
            promptSha256 = "1".repeat(64),
            config = emptyMap(), prediction = null,
            benchmark = null, comparison = null,
            followup = null, parentSessionId = null,
            experimentSeq = null, budgetUsed = 0,
            budgetMax = 5, agent = null, history = emptyList(),
            recoveryNotes = emptyList(),
            repeatOfRunId = "BENCH-20261028T010000Z-aaaa1111")
        val body = InsightCards.sessionSections(record)
            .flatMap { it.rows }
            .joinToString("\n") { "${it.label}=${it.value}" }
        assertTrue(body.contains("Continue experimenting"))
        assertTrue(body.contains("preserved"))
        val plain = InsightCards.sessionSections(
            record.copy(repeatOfRunId = null))
            .flatMap { it.rows }
            .joinToString("\n") { "${it.label}=${it.value}" }
        assertFalse(plain.contains("Continue experimenting"))
    }
}
