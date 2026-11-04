package ai.edgefit.diag

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EvidenceStoreTest {
    private fun freshStore(): EvidenceStore {
        val dir = Files.createTempDirectory("evidence-test").toFile()
        return EvidenceStore(dir)
    }

    @Test
    fun `store then load round-trips bytes exactly`() {
        val store = freshStore()
        val text = BenchResultParseTest.validDoc()
        val record = store.store(
            text, EvidenceStore.PROVENANCE_CAPTURED,
            BenchResultParseTest.NOW)
        assertEquals("BENCH-TEST-001", record.summary.runId)
        assertEquals(
            EvidenceStore.PROVENANCE_CAPTURED, record.provenance)
        assertEquals(text, record.bytes.toString(Charsets.UTF_8))
        assertEquals(
            GlobalContract.LOCAL_NOT_SHARED, record.contributionState)
        assertNull(record.submissionId)
    }

    @Test
    fun `identical re-store returns existing record`() {
        val store = freshStore()
        val text = BenchResultParseTest.validDoc()
        store.store(text, EvidenceStore.PROVENANCE_CAPTURED,
            BenchResultParseTest.NOW)
        val again = store.store(text, EvidenceStore.PROVENANCE_IMPORTED,
            BenchResultParseTest.NOW)
        assertEquals(
            EvidenceStore.PROVENANCE_CAPTURED, again.provenance)
        assertEquals(listOf("BENCH-TEST-001"), store.listIds())
    }

    @Test
    fun `different bytes under same id conflict`() {
        val store = freshStore()
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        try {
            store.store(
                BenchResultParseTest.validDoc {
                    @Suppress("UNCHECKED_CAST")
                    val measurements = get("measurements")
                        as MutableMap<String, Any?>
                    measurements["load_ms"] = 999L
                },
                EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
            fail("expected Conflict")
        } catch (_: EvidenceStore.Conflict) {
        }
    }

    @Test
    fun `invalid document refused`() {
        val store = freshStore()
        try {
            store.store("{bad", EvidenceStore.PROVENANCE_IMPORTED,
                BenchResultParseTest.NOW)
            fail("expected StoreError")
        } catch (e: EvidenceStore.StoreError) {
            assertTrue(e.message!!.contains("invalid benchmark JSON"))
        }
    }

    @Test
    fun `missing record throws not found`() {
        try {
            freshStore().load("BENCH-NOPE")
            fail("expected NotFound")
        } catch (_: EvidenceStore.NotFound) {
        }
    }

    @Test
    fun `contribution update keeps evidence bytes`() {
        val store = freshStore()
        val text = BenchResultParseTest.validDoc()
        store.store(text, EvidenceStore.PROVENANCE_CAPTURED,
            BenchResultParseTest.NOW)
        val updated = store.updateContribution(
            "BENCH-TEST-001", state = GlobalContract.LOCAL_SHARED,
            submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED,
            statusAtUtc = "2026-10-31T04:00:00Z",
            consentPolicyVersion = "ef-consent-01",
            consentAcceptedAtUtc = "2026-10-31T03:59:00Z")
        assertEquals(GlobalContract.LOCAL_SHARED, updated.contributionState)
        assertEquals("SUB-1", updated.submissionId)
        assertEquals(GlobalContract.SUBMITTED, updated.serverEligibility)
        assertEquals("ef-consent-01", updated.consentPolicyVersion)
        assertEquals(text, updated.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `imported provenance preserved`() {
        val store = freshStore()
        val record = store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_IMPORTED, BenchResultParseTest.NOW)
        assertEquals(EvidenceStore.PROVENANCE_IMPORTED, record.provenance)
    }

    @Test
    fun `corrupt sidecar surfaces store error`() {
        val dir = Files.createTempDirectory("evidence-test").toFile()
        val store = EvidenceStore(dir)
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        File(dir, "evidence/BENCH-TEST-001.meta.json").writeText("{bad")
        try {
            store.load("BENCH-TEST-001")
            fail("expected StoreError")
        } catch (_: EvidenceStore.StoreError) {
        }
    }

    @Test
    fun `delete removes stored record`() {
        val store = freshStore()
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        assertTrue(store.delete("BENCH-TEST-001"))
        assertTrue(store.listIds().isEmpty())
        try {
            store.load("BENCH-TEST-001")
            fail("expected NotFound")
        } catch (_: EvidenceStore.NotFound) {
        }
    }

    @Test
    fun `delete missing record returns false`() {
        assertTrue(!freshStore().delete("BENCH-NOPE"))
    }

    @Test
    fun `delete invalid id throws`() {
        try {
            freshStore().delete("../evil")
            fail("expected BadId")
        } catch (_: EvidenceStore.BadId) {
        }
    }

    @Test
    fun `deleteRetainingWithdrawal keeps submission-only record`() {
        val store = freshStore()
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        store.updateContribution("BENCH-TEST-001",
            state = GlobalContract.LOCAL_SHARED, submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED)
        val w = store.deleteRetainingWithdrawal(
            "BENCH-TEST-001", BenchResultParseTest.NOW)
        assertTrue(w != null)
        assertEquals("SUB-1", w!!.submissionId)
        assertTrue(store.listIds().isEmpty())
        assertEquals(listOf("BENCH-TEST-001"),
            store.listWithdrawals().map { it.runId })
        try {
            store.load("BENCH-TEST-001")
            fail("expected NotFound")
        } catch (_: EvidenceStore.NotFound) {
        }
    }

    @Test
    fun `withdrawal record carries no benchmark bytes`() {
        val dir = Files.createTempDirectory("evidence-test").toFile()
        val store = EvidenceStore(dir)
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        store.updateContribution("BENCH-TEST-001",
            state = GlobalContract.LOCAL_SHARED, submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED)
        store.deleteRetainingWithdrawal(
            "BENCH-TEST-001", BenchResultParseTest.NOW)
        val text = File(dir, "evidence/BENCH-TEST-001.withdraw.json").readText()
        assertTrue(!text.contains("measurements"))
        assertTrue(text.contains("SUB-1"))
    }

    @Test
    fun `deleteRetainingWithdrawal unshared just deletes`() {
        val store = freshStore()
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        assertTrue(store.deleteRetainingWithdrawal(
            "BENCH-TEST-001", BenchResultParseTest.NOW) == null)
        assertTrue(store.listIds().isEmpty())
        assertTrue(store.listWithdrawals().isEmpty())
    }

    @Test
    fun `deleteRetainingWithdrawal missing returns null`() {
        assertTrue(freshStore().deleteRetainingWithdrawal(
            "BENCH-NOPE", BenchResultParseTest.NOW) == null)
    }

    @Test
    fun `withdrawal dismiss round trip`() {
        val store = freshStore()
        store.store(BenchResultParseTest.validDoc(),
            EvidenceStore.PROVENANCE_CAPTURED, BenchResultParseTest.NOW)
        store.updateContribution("BENCH-TEST-001",
            state = GlobalContract.LOCAL_SHARED, submissionId = "SUB-1",
            serverEligibility = GlobalContract.SUBMITTED)
        store.deleteRetainingWithdrawal(
            "BENCH-TEST-001", BenchResultParseTest.NOW)
        assertTrue(store.deleteWithdrawal("BENCH-TEST-001"))
        assertTrue(store.listWithdrawals().isEmpty())
        assertTrue(!store.deleteWithdrawal("BENCH-TEST-001"))
    }

}
