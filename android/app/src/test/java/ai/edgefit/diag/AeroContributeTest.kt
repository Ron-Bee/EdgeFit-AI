package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF-UX-AERO-01 Step 5 contribution derivations: EXACT
 * preserved terms, honest readiness/connection states,
 * measured-only previews, and the static data summary.
 */
class AeroContributeTest {

    private fun summary(
        status: String = BenchContract.STATUS_COMPLETED,
        gen: Double = 17.738
    ): BenchResultParse.Summary =
        BenchResultParse.Summary(
            runId = "BENCH-20261028T010000Z-aaaaaaaa",
            sha256 = "2".repeat(64),
            status = status, error = null,
            appVersion = "1.0",
            collectedAtUtc = "2026-10-28T01:00:00Z",
            deviceModel = "SM-S908B", sdkInt = 34,
            modelDisplay = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            modelSizeBytes = 1048576L * 800,
            modelSha256 = "0".repeat(64),
            quantization = "Q4_K_M",
            nCtx = 512, nThreads = 4, nPredict = 64,
            sampler = "greedy", workloadId = "edgefit-prompt-v1",
            promptSha256 = "1".repeat(64),
            tpsGen = gen, tpsPrefill = 30.99,
            generatedTokens = 64,
            thermalBefore = "none", thermalMid = "none",
            thermalAfter = "none", memAvailPctStart = 55.0
        )

    private fun record(
        status: String = BenchContract.STATUS_COMPLETED,
        gen: Double = 17.738,
        provenance: String = EvidenceStore.PROVENANCE_CAPTURED,
        contributionState: String = GlobalContract.LOCAL_NOT_SHARED,
        submissionId: String? = null,
        serverEligibility: String? = null
    ): EvidenceStore.Record =
        EvidenceStore.Record(
            summary = summary(status, gen),
            bytes = "{}".toByteArray(Charsets.UTF_8),
            provenance = provenance,
            storedAtUtc = "2026-10-28T01:00:00Z",
            contributionState = contributionState,
            submissionId = submissionId,
            serverEligibility = serverEligibility,
            lastStatusAtUtc = null,
            consentPolicyVersion = null,
            consentAcceptedAtUtc = null
        )

    @Test
    fun exactTermsPreserved() {
        assertEquals("Contribute to EdgeFit Global",
            AeroContribute.TITLE)
        assertEquals("Ready to contribute", AeroContribute.READY)
    }

    @Test
    fun readinessStatesAreExact() {
        assertEquals("Ready to contribute",
            AeroContribute.readinessLine(record()))
        assertEquals("Ready to contribute (retry available)",
            AeroContribute.readinessLine(record(
                contributionState = GlobalContract.LOCAL_RETRY)))
        assertEquals("Submission uncertain — review before retrying",
            AeroContribute.readinessLine(record(
                contributionState = GlobalContract.LOCAL_UNCERTAIN)))
        assertEquals("Sending…",
            AeroContribute.readinessLine(record(
                contributionState = GlobalContract.LOCAL_SENDING)))
        assertEquals("Shared: Submitted",
            AeroContribute.readinessLine(record(
                contributionState = GlobalContract.LOCAL_SHARED,
                submissionId = "sub-1",
                serverEligibility = GlobalContract.SUBMITTED)))
    }

    @Test
    fun unconfiguredConnectionNeverReadsWorking() {
        val line = AeroContribute.connectionLine(null, null)
        assertTrue(line.contains("not configured"))
        assertFalse(line.contains("token …"))
        assertEquals("Cloud connection not configured.",
            line)
    }

    @Test
    fun missingTokenNamesTheFix() {
        val line = AeroContribute.connectionLine(
            "https://staging-host.example", null)
        assertTrue(line.contains("No upload token"))
        assertTrue(line.contains("EdgeFit Global Connection"))
    }

    @Test
    fun configuredConnectionShowsSuffixOnly() {
        val line = AeroContribute.connectionLine(
            "https://staging-host.example", "…abcd")
        assertTrue(line.contains("…abcd"))
        assertTrue(line.contains("Nothing uploads automatically"))
    }

    @Test
    fun provenanceDistinguishesImports() {
        assertEquals("Measured on this install",
            AeroContribute.provenanceLine(
                EvidenceStore.PROVENANCE_CAPTURED))
        assertEquals(
            "Imported file (not proof of this install)",
            AeroContribute.provenanceLine(
                EvidenceStore.PROVENANCE_IMPORTED))
    }

    @Test
    fun previewShowsMeasuredThroughput() {
        val joined = AeroContribute.previewLines(record())
            .joinToString("\n")
        assertTrue(joined.contains("SM-S908B"))
        assertTrue(joined.contains("Llama-3.2-1B"))
        assertTrue(joined.contains("17.74 tok/s gen"))
        assertTrue(joined.contains("Measured on this install"))
    }

    @Test
    fun previewHidesZeroedCountersOnFailure() {
        val joined = AeroContribute.previewLines(record(
            status = BenchContract.STATUS_FAILED, gen = 0.0))
            .joinToString("\n")
        assertTrue(joined.contains("no measurement"))
        assertTrue(joined.contains("FAILED"))
        assertFalse(joined.contains("tok/s"))
    }

    @Test
    fun dataSummaryStatesConsentAndNoAutoUpload() {
        val joined = AeroContribute.dataLines().joinToString(" ")
        assertTrue(joined.contains("consent"))
        assertTrue(joined.contains("no public per-run listing"))
        assertTrue(joined.contains("ef-consent-01"))
        assertTrue(joined.contains("never automatically"))
        assertTrue(joined.contains("withdraw"))
    }

    @Test
    fun evidenceLineMatchesExistingFormat() {
        assertEquals("Ready to contribute (aaaaaaaa)",
            AeroContribute.evidenceLine(record()))
    }

    @Test
    fun emptyLinesCarryNoReceipts() {
        val joined = AeroContribute.emptyLines().joinToString(" ")
        assertTrue(joined.contains(
            "No preserved benchmark evidence yet."))
        assertFalse(joined.contains("tok/s"))
        assertFalse(joined.contains("Submission"))
    }
}
