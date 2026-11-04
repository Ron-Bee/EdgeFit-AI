package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroJourneyTest {

    private fun summary(
        status: String = BenchContract.STATUS_COMPLETED
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
            tpsGen = 17.738, tpsPrefill = 30.99,
            generatedTokens = 64,
            thermalBefore = "none", thermalMid = "none",
            thermalAfter = "none", memAvailPctStart = 55.0
        )

    private fun record(
        status: String = BenchContract.STATUS_COMPLETED,
        contributionState: String =
            GlobalContract.LOCAL_NOT_SHARED,
        submissionId: String? = null,
        serverEligibility: String? = null,
        consentPolicyVersion: String? = null,
        consentAcceptedAtUtc: String? = null
    ): EvidenceStore.Record =
        EvidenceStore.Record(
            summary = summary(status),
            bytes = "{}".toByteArray(Charsets.UTF_8),
            provenance = EvidenceStore.PROVENANCE_CAPTURED,
            storedAtUtc = "2026-10-28T01:00:00Z",
            contributionState = contributionState,
            submissionId = submissionId,
            serverEligibility = serverEligibility,
            lastStatusAtUtc = null,
            consentPolicyVersion = consentPolicyVersion,
            consentAcceptedAtUtc = consentAcceptedAtUtc
        )

    @Test
    fun nullRecordStaysDarkAndPlanned() {
        val journey = AeroJourney.journey(null)
        assertEquals(4, journey.nodes.size)
        assertTrue(journey.nodes.none { it.lit })
        assertTrue(journey.nodes[2].planned)
        assertTrue(journey.nodes[3].planned)
        assertNull(journey.receiptLine)
        assertEquals("Consent not recorded.",
            journey.consentLine)
    }

    @Test
    fun localOnlyLightsMeasurementAlone() {
        val journey = AeroJourney.journey(record())
        assertTrue(journey.nodes[0].lit)
        assertFalse(journey.nodes[1].lit)
        assertFalse(journey.nodes[2].lit)
        assertFalse(journey.nodes[3].lit)
        assertEquals("Not shared",
            journey.nodes[1].caption)
        assertTrue(journey.nodes[2].planned)
        assertTrue(journey.nodes[3].planned)
    }

    @Test
    fun failedRunIsNotMeasurement() {
        val journey = AeroJourney.journey(
            record(status = BenchContract.STATUS_FAILED))
        assertFalse(journey.nodes[0].lit)
    }

    @Test
    fun receiptIsNotPublication() {
        val journey = AeroJourney.journey(record(
            contributionState = GlobalContract.LOCAL_SHARED,
            submissionId = "sub_1234567890abcdef",
            serverEligibility = GlobalContract.SUBMITTED))
        assertTrue(journey.nodes[1].lit)
        assertTrue(journey.nodes[1].caption.contains(
            "cdef"))
        assertTrue(journey.nodes[1].caption.contains(
            "Submitted"))
        // Later stages stay dark: acceptance is not
        // qualification, and neither is publication.
        assertFalse(journey.nodes[2].lit)
        assertFalse(journey.nodes[3].lit)
        assertTrue(journey.nodes[3].caption.contains(
            "not published"))
        assertTrue(journey.receiptLine!!
            .contains("sub_1234567890abcdef"))
    }

    @Test
    fun qualifiedLightsStageThreeOnly() {
        val journey = AeroJourney.journey(record(
            contributionState = GlobalContract.LOCAL_SHARED,
            submissionId = "sub_q",
            serverEligibility = GlobalContract.QUALIFIED))
        assertTrue(journey.nodes[2].lit)
        assertEquals("Qualified",
            journey.nodes[2].caption)
        assertFalse(journey.nodes[3].lit)
    }

    @Test
    fun publishedLightsTheFullStrip() {
        val journey = AeroJourney.journey(record(
            contributionState = GlobalContract.LOCAL_SHARED,
            submissionId = "sub_p",
            serverEligibility = GlobalContract.PUBLISHED))
        assertTrue(journey.nodes.all { it.lit })
        assertEquals("Published aggregate",
            journey.nodes[3].caption)
    }

    @Test
    fun revokedStaysVisibleByName() {
        val journey = AeroJourney.journey(record(
            contributionState = GlobalContract.LOCAL_SHARED,
            submissionId = "sub_r",
            serverEligibility = GlobalContract.REVOKED))
        assertTrue(journey.nodes[1].lit)
        assertTrue(journey.nodes[1].caption.contains(
            "Revoked"))
        assertFalse(journey.nodes[3].lit)
        assertEquals("Revoked",
            journey.nodes[3].caption)
    }

    @Test
    fun uncertainUploadKeepsReceiptDark() {
        val journey = AeroJourney.journey(record(
            contributionState = GlobalContract.LOCAL_UNCERTAIN,
            submissionId = "sub_u",
            serverEligibility = null))
        assertFalse(journey.nodes[1].lit)
        assertEquals("Submission uncertain",
            journey.nodes[1].caption)
    }

    @Test
    fun consentLineNamesPolicyAndTime() {
        val journey = AeroJourney.journey(record(
            consentPolicyVersion = "ef-consent-01",
            consentAcceptedAtUtc = "2026-11-01T00:00:00Z"))
        assertTrue(journey.consentLine.contains(
            "ef-consent-01"))
        assertTrue(journey.consentLine.contains(
            "2026-11-01T00:00:00Z"))
    }

    @Test
    fun honestyLineMatchesReceiptDialog() {
        assertEquals(
            "Received by the backend. This is NOT " +
                "publication; qualification is separate.",
            AeroJourney.HONESTY)
    }
}
