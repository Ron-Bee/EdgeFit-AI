package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalContractTest {
    @Test
    fun `consent constants match backend allowlist`() {
        assertEquals("ef-consent-01", GlobalContract.CONSENT_POLICY_VERSION)
        assertEquals(listOf("aggregates"), GlobalContract.CONSENT_SCOPES)
    }

    @Test
    fun `token shape mirrors backend regex`() {
        assertTrue(GlobalContract.tokenValid("abcd1234"))
        assertTrue(GlobalContract.tokenValid("A9._~+/-xY01=="))
        assertFalse(GlobalContract.tokenValid("short"))
        assertFalse(GlobalContract.tokenValid("has space12"))
        assertFalse(GlobalContract.tokenValid(""))
        assertFalse(GlobalContract.tokenValid("a".repeat(300)))
    }

    @Test
    fun `origin requires plain https`() {
        assertEquals(
            "https://staging.example.com",
            GlobalContract.normalizeOrigin("https://staging.example.com/"))
        assertEquals(
            "https://h.example:8443/prefix",
            GlobalContract.normalizeOrigin("https://h.example:8443/prefix"))
        assertNull(GlobalContract.normalizeOrigin("http://h.example.com"))
        assertNull(GlobalContract.normalizeOrigin("https://user@h.example.com"))
        assertNull(GlobalContract.normalizeOrigin("https://"))
        assertNull(GlobalContract.normalizeOrigin("not a url"))
        assertNull(GlobalContract.normalizeOrigin(""))
    }

    @Test
    fun `submission url rejects bad ids`() {
        assertEquals(
            "https://h.example.com/v1/submissions/SUB-1",
            GlobalContract.submissionUrl("https://h.example.com", "SUB-1"))
        assertNull(GlobalContract.submissionUrl(
            "https://h.example.com", "../escape"))
        assertNull(GlobalContract.submissionUrl("http://h.example.com", "SUB-1"))
    }

    @Test
    fun `unknown server states are never upgraded`() {
        assertEquals("Submitted",
            GlobalContract.displayServerState(GlobalContract.SUBMITTED))
        assertEquals("Published",
            GlobalContract.displayServerState(GlobalContract.PUBLISHED))
        assertEquals("Not shared", GlobalContract.displayServerState(null))
        assertEquals("Unknown state (WONDERFUL)",
            GlobalContract.displayServerState("WONDERFUL"))
    }
}
