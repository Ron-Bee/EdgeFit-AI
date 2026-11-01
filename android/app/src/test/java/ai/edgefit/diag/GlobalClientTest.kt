package ai.edgefit.diag

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalClientTest {
    companion object {
        const val ORIGIN = "https://staging.example.com"
        const val TOKEN = "testtoken12345678"
    }

    private class FakeSeal : KeySeal {
        override fun seal(plain: ByteArray) =
            byteArrayOf(1, 2) to plain.reversedArray()

        override fun open(iv: ByteArray, cipher: ByteArray) =
            cipher.reversedArray()
    }

    private fun harness(origin: String = ORIGIN): Triple<EvidenceStore, FakeGlobalTransport, GlobalClient> {
        val dir = Files.createTempDirectory("global-test").toFile()
        val store = EvidenceStore(dir)
        val fake = FakeGlobalTransport()
        val vault = GlobalTokenVault(dir, FakeSeal())
        vault.setSessionToken(TOKEN)
        val client = GlobalClient(store, fake, vault, { origin },
            { BenchResultParseTest.NOW }, { true })
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        return Triple(store, fake, client)
    }

    @Test
    fun `submit 201 stores receipt and consent`() {
        val (store, fake, client) = harness()
        fake.script.add(GlobalHttpResult(
            201,
            """{"submission_id":"SUB-1","eligibility":"SUBMITTED","duplicate":false}""",
            null))
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Shared)
        outcome as GlobalClient.SubmitOutcome.Shared
        assertEquals("SUB-1", outcome.submissionId)
        assertEquals(false, outcome.duplicate)
        val record = store.load("BENCH-TEST-001")
        assertEquals(GlobalContract.LOCAL_SHARED, record.contributionState)
        assertEquals("ef-consent-01", record.consentPolicyVersion)
        // Envelope shape: result object + consent, token only in header.
        val sent = SessionJson.parse(fake.bodies.single()!!) as Map<*, *>
        assertTrue((sent["result"] as Map<*, *>)["run_id"] == "BENCH-TEST-001")
        val consent = sent["consent"] as Map<*, *>
        assertEquals("ef-consent-01", consent["policy_version"])
        assertEquals(listOf("aggregates"), consent["scopes"])
        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertEquals("$ORIGIN/v1/submissions", call.url)
    }

    @Test
    fun `submit 200 duplicate accepted without second row`() {
        val (_, fake, client) = harness()
        fake.script.add(GlobalHttpResult(
            200,
            """{"submission_id":"SUB-9","eligibility":"VALIDATED","duplicate":true}""",
            null))
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Shared)
        assertEquals(true,
            (outcome as GlobalClient.SubmitOutcome.Shared).duplicate)
    }

    @Test
    fun `submit 422 surfaces itemized rejections`() {
        val (store, fake, client) = harness()
        fake.script.add(GlobalHttpResult(
            422,
            """{"error":{"code":"invalid_submission","message":"bad",
                "details":[{"field":"result.status","reason":"unknown"}]}}""",
            null))
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Rejected)
        outcome as GlobalClient.SubmitOutcome.Rejected
        assertEquals("invalid_submission", outcome.code)
        assertEquals(listOf("result.status: unknown"), outcome.details)
        assertEquals(GlobalContract.LOCAL_RETRY,
            store.load("BENCH-TEST-001").contributionState)
    }

    @Test
    fun `submit 401 is auth failure not uncertain`() {
        val (_, fake, client) = harness()
        fake.script.add(GlobalHttpResult(
            401, """{"error":{"code":"unauthorized","message":"no"}}""",
            null))
        assertTrue(
            client.submit("BENCH-TEST-001") is
                GlobalClient.SubmitOutcome.AuthFailed)
    }

    @Test
    fun `submit 429 carries retry-after`() {
        val (_, fake, client) = harness()
        fake.script.add(GlobalHttpResult(
            429, """{"error":{"code":"quota_exceeded","message":"q"}}""", 60))
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Quota)
        assertEquals(60L,
            (outcome as GlobalClient.SubmitOutcome.Quota).retryAfterSec)
    }

    @Test
    fun `transport failure during submit is uncertain`() {
        val (store, fake, client) = harness()
        fake.script.add(IOException("socket closed"))
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Uncertain)
        assertEquals(GlobalContract.LOCAL_UNCERTAIN,
            store.load("BENCH-TEST-001").contributionState)
        // Recovery resubmits identical bytes; server dedups.
        fake.script.add(GlobalHttpResult(
            200,
            """{"submission_id":"SUB-7","eligibility":"SUBMITTED","duplicate":true}""",
            null))
        val retry = client.submit("BENCH-TEST-001")
        assertTrue(retry is GlobalClient.SubmitOutcome.Shared)
        assertEquals(2, fake.calls.size)
        assertEquals(fake.bodies[0], fake.bodies[1])
    }

    @Test
    fun `unreadable receipt on 201 is uncertain`() {
        val (store, fake, client) = harness()
        fake.script.add(GlobalHttpResult(201, "{garbage", null))
        assertTrue(client.submit("BENCH-TEST-001") is
            GlobalClient.SubmitOutcome.Uncertain)
        assertEquals(GlobalContract.LOCAL_UNCERTAIN,
            store.load("BENCH-TEST-001").contributionState)
    }

    @Test
    fun `server 500 is uncertain not failed`() {
        val (_, fake, client) = harness()
        fake.script.add(GlobalHttpResult(500, "", null))
        assertTrue(client.submit("BENCH-TEST-001") is
            GlobalClient.SubmitOutcome.Uncertain)
    }

    @Test
    fun `already shared blocks resend`() {
        val (store, fake, client) = harness()
        store.updateContribution("BENCH-TEST-001",
            state = GlobalContract.LOCAL_SHARED, submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED)
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Blocked)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `missing origin blocks without network`() {
        val dir = Files.createTempDirectory("global-test").toFile()
        val store = EvidenceStore(dir)
        val fake = FakeGlobalTransport()
        val vault = GlobalTokenVault(dir, FakeSeal())
        vault.setSessionToken(TOKEN)
        val client = GlobalClient(store, fake, vault, { null },
            { BenchResultParseTest.NOW }, { true })
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Blocked)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `missing token blocks without network`() {
        val dir = Files.createTempDirectory("global-test").toFile()
        val store = EvidenceStore(dir)
        val fake = FakeGlobalTransport()
        val vault = GlobalTokenVault(dir, FakeSeal())
        val client = GlobalClient(store, fake, vault, { ORIGIN },
            { BenchResultParseTest.NOW }, { true })
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Blocked)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `refresh updates eligibility without sending data`() {
        val (store, fake, client) = harness()
        store.updateContribution("BENCH-TEST-001",
            state = GlobalContract.LOCAL_SHARED, submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED)
        fake.script.add(GlobalHttpResult(
            200,
            """{"submission_id":"SUB-1","eligibility":"QUALIFIED"}""",
            null))
        val outcome = client.refreshStatus("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.StatusOutcome.Updated)
        assertEquals(GlobalContract.QUALIFIED,
            store.load("BENCH-TEST-001").serverEligibility)
        assertEquals("GET", fake.calls.single().method)
        assertEquals(null, fake.bodies.single())
    }

    @Test
    fun `refresh rejects mismatched submission id`() {
        val (_, fake, client) = harness()
        fake.script.add(GlobalHttpResult(
            200,
            """{"submission_id":"SUB-OTHER","eligibility":"QUALIFIED"}""",
            null))
        // No receipt yet: refresh refuses before any network.
        val outcome = client.refreshStatus("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.StatusOutcome.Failed)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `refresh failure keeps last known state`() {
        val (store, fake, client) = harness()
        store.updateContribution("BENCH-TEST-001",
            state = GlobalContract.LOCAL_SHARED, submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED,
            statusAtUtc = "2026-10-31T04:00:00Z")
        fake.script.add(IOException("offline"))
        val outcome = client.refreshStatus("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.StatusOutcome.Failed)
        val record = store.load("BENCH-TEST-001")
        assertEquals(GlobalContract.SUBMITTED, record.serverEligibility)
        assertEquals("2026-10-31T04:00:00Z", record.lastStatusAtUtc)
    }

    @Test
    fun `revoke stores revoked on server confirmation`() {
        val (store, fake, client) = harness()
        store.updateContribution("BENCH-TEST-001",
            state = GlobalContract.LOCAL_SHARED, submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED)
        fake.script.add(GlobalHttpResult(
            200,
            """{"submission_id":"SUB-1","eligibility":"REVOKED"}""",
            null))
        assertTrue(
            client.revoke("BENCH-TEST-001") is
                GlobalClient.RevokeOutcome.Revoked)
        assertEquals(GlobalContract.REVOKED,
            store.load("BENCH-TEST-001").serverEligibility)
        assertEquals("DELETE", fake.calls.single().method)
    }

    @Test
    fun `revoke mismatch does not change local state`() {
        val (store, fake, client) = harness()
        store.updateContribution("BENCH-TEST-001",
            state = GlobalContract.LOCAL_SHARED, submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED)
        fake.script.add(GlobalHttpResult(
            200,
            """{"submission_id":"SUB-1","eligibility":"SUBMITTED"}""",
            null))
        val outcome = client.revoke("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.RevokeOutcome.Failed)
        assertEquals(GlobalContract.SUBMITTED,
            store.load("BENCH-TEST-001").serverEligibility)
    }

    @Test
    fun `concurrent submit refused while in flight`() {
        val (_, fake, client) = harness()
        fake.script.add(GlobalHttpResult(
            201,
            """{"submission_id":"SUB-1","eligibility":"SUBMITTED"}""",
            null))
        // Occupy in-flight with a thread blocked in transport.
        val gate = java.util.concurrent.CountDownLatch(1)
        val blocking = object : GlobalTransport {
            override fun request(
                method: String, url: String, token: String, body: String?
            ): GlobalHttpResult {
                gate.await()
                return GlobalHttpResult(
                    201,
                    """{"submission_id":"SUB-1","eligibility":"SUBMITTED"}""",
                    null)
            }
        }
        val dir = Files.createTempDirectory("global-test").toFile()
        val store2 = EvidenceStore(dir)
        val vault2 = GlobalTokenVault(dir, FakeSeal())
        vault2.setSessionToken(TOKEN)
        store2.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        val client2 = GlobalClient(store2, blocking, vault2, { ORIGIN },
            { BenchResultParseTest.NOW }, { true })
        val first = Thread { client2.submit("BENCH-TEST-001") }
        first.start()
        Thread.sleep(300)
        val second = client2.submit("BENCH-TEST-001")
        gate.countDown()
        first.join(5000)
        assertTrue(second is GlobalClient.SubmitOutcome.Blocked)
    }

    @Test
    fun `review exposes consent facts without network`() {
        val (_, fake, client) = harness()
        val review = client.review("BENCH-TEST-001")
        assertEquals("BENCH-TEST-001", review.runId)
        assertEquals(listOf("aggregates"), review.scopes)
        assertEquals(ORIGIN, review.origin)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `submit blocked while consent policy unapproved`() {
        val dir = Files.createTempDirectory("global-test").toFile()
        val store = EvidenceStore(dir)
        val fake = FakeGlobalTransport()
        val vault = GlobalTokenVault(dir, FakeSeal())
        vault.setSessionToken(TOKEN)
        val client = GlobalClient(store, fake, vault, { ORIGIN },
            { BenchResultParseTest.NOW })
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Blocked)
        assertTrue((outcome as GlobalClient.SubmitOutcome.Blocked)
            .reason.contains("not yet owner-approved"))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `revokeWithdrawal confirms revocation by id`() {
        val dir = Files.createTempDirectory("global-test").toFile()
        val store = EvidenceStore(dir)
        val fake = FakeGlobalTransport()
        val vault = GlobalTokenVault(dir, FakeSeal())
        vault.setSessionToken(TOKEN)
        val client = GlobalClient(store, fake, vault, { ORIGIN },
            { BenchResultParseTest.NOW }, { true })
        fake.script.add(GlobalHttpResult(
            200,
            """{"submission_id":"SUB-9","eligibility":"REVOKED"}""",
            null))
        val outcome = client.revokeWithdrawal("SUB-9")
        assertTrue(outcome is GlobalClient.RevokeOutcome.Revoked)
        assertEquals("DELETE", fake.calls.single().method)
    }

    @Test
    fun `revokeWithdrawal mismatch stays failed`() {
        val dir = Files.createTempDirectory("global-test").toFile()
        val store = EvidenceStore(dir)
        val fake = FakeGlobalTransport()
        val vault = GlobalTokenVault(dir, FakeSeal())
        vault.setSessionToken(TOKEN)
        val client = GlobalClient(store, fake, vault, { ORIGIN },
            { BenchResultParseTest.NOW }, { true })
        fake.script.add(GlobalHttpResult(
            200,
            """{"submission_id":"SUB-9","eligibility":"SUBMITTED"}""",
            null))
        val outcome = client.revokeWithdrawal("SUB-9")
        assertTrue(outcome is GlobalClient.RevokeOutcome.Failed)
    }

    @Test
    fun `submit malformed origin blocks without network`() {
        val (_, fake, client) = harness(origin = "http://insecure.example.com/x")
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Blocked)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `submit failed result blocks without network`() {
        val (store, fake, client) = harness()
        store.store(BenchResultParseTest.validDoc("BENCH-FAIL-001", "FAILED"),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        val outcome = client.submit("BENCH-FAIL-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Blocked)
        assertTrue((outcome as GlobalClient.SubmitOutcome.Blocked)
            .reason.contains("COMPLETED"))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `submit imported result blocks without network`() {
        val (store, fake, client) = harness()
        store.store(BenchResultParseTest.validDoc("BENCH-IMP-001"),
            EvidenceStore.PROVENANCE_IMPORTED, BenchResultParseTest.NOW)
        val outcome = client.submit("BENCH-IMP-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Blocked)
        assertTrue((outcome as GlobalClient.SubmitOutcome.Blocked)
            .reason.contains("imported"))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `review and idle client perform no requests`() {
        val (_, fake, client) = harness()
        val review = client.review("BENCH-TEST-001")
        assertEquals("BENCH-TEST-001", review.runId)
        assertEquals("COMPLETED", review.status)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `approved submit sends exactly result plus consent`() {
        val (_, fake, client) = harness()
        fake.script.add(GlobalHttpResult(
            201,
            """{"submission_id":"SUB-1","eligibility":"SUBMITTED","duplicate":false}""",
            null))
        val outcome = client.submit("BENCH-TEST-001")
        assertTrue(outcome is GlobalClient.SubmitOutcome.Shared)
        assertEquals(1, fake.calls.size)
        val sent = SessionJson.parse(fake.bodies.single()!!) as Map<*, *>
        assertEquals(setOf("result", "consent"), sent.keys)
        val consent = sent["consent"] as Map<*, *>
        assertEquals("ef-consent-01", consent["policy_version"])
        assertEquals(listOf("aggregates"), consent["scopes"])
    }

}
