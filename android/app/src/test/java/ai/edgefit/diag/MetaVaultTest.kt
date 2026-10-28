package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** JVM tests for key custody. The seal is an obvious test fake. */
class MetaVaultTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Reversible toy transform, labeled fake; never ships. */
    class FakeSeal(var fail: Boolean = false) : KeySeal {
        override fun seal(plain: ByteArray): Pair<ByteArray, ByteArray> {
            if (fail) throw IllegalStateException("fake seal boom")
            return "fake-iv-0123".toByteArray(Charsets.UTF_8) to
                plain.reversedArray()
        }

        override fun open(iv: ByteArray, cipher: ByteArray): ByteArray {
            if (fail) throw IllegalStateException("fake open boom")
            return cipher.reversedArray()
        }
    }

    private fun vault(seal: KeySeal? = FakeSeal()): MetaKeyVault =
        MetaKeyVault(temp.newFolder(), seal)

    @Test
    fun sessionKeyRoundTripsInMemoryOnly() {
        val dir = temp.newFolder()
        val vault = MetaKeyVault(dir, FakeSeal())
        vault.setSessionKey("sk-test-key-1")
        assertTrue(vault.hasSession())
        assertEquals("sk-test-key-1", vault.getKey())
        assertEquals("…ey-1", vault.sessionSuffix())
        assertFalse(vault.hasPersisted())
        // Nothing touched disk.
        assertEquals(0, dir.listFiles()!!.size)
        vault.clearSession()
        assertNull(vault.getKey())
    }

    @Test
    fun persistedKeyReloadsInFreshVault() {
        val dir = temp.newFolder()
        MetaKeyVault(dir, FakeSeal()).apply {
            setSessionKey("sk-test-key-2")
            persistSessionKey()
        }
        // Fresh process-equivalent: no memory, same files.
        val reloaded = MetaKeyVault(dir, FakeSeal())
        assertFalse(reloaded.hasSession())
        assertTrue(reloaded.hasPersisted())
        assertNull(reloaded.sessionSuffix())
        assertEquals("sk-test-key-2", reloaded.getKey())
        // Ciphertext at rest is not the key.
        val blob = File(dir, MetaKeyVault.KEY_FILE).readBytes()
            .toString(Charsets.UTF_8)
        assertFalse(blob.contains("sk-test-key-2"))
    }

    @Test
    fun forgetRemovesEveryCopy() {
        val dir = temp.newFolder()
        val vault = MetaKeyVault(dir, FakeSeal())
        assertFalse(vault.forget())
        vault.setSessionKey("sk-test-key-3")
        vault.persistSessionKey()
        assertTrue(vault.forget())
        assertFalse(vault.hasSession())
        assertFalse(vault.hasPersisted())
        assertNull(vault.getKey())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun sealFailureFailsClosed() {
        val dir = temp.newFolder()
        val vault = MetaKeyVault(dir, FakeSeal(fail = true))
        vault.setSessionKey("sk-test-key-4")
        try {
            vault.persistSessionKey()
            fail("seal failure accepted")
        } catch (e: MetaKeyVault.VaultError) {
            assertTrue(e.message!!.contains("seal failed"))
        }
        assertFalse(vault.hasPersisted())
        // Session key still usable despite persistence failure.
        assertEquals("sk-test-key-4", vault.getKey())
    }

    @Test
    fun corruptPersistedFileSelfHeals() {
        val dir = temp.newFolder()
        File(dir, MetaKeyVault.KEY_FILE).writeBytes(byteArrayOf(1, 2, 3))
        val vault = MetaKeyVault(dir, FakeSeal())
        assertNull(vault.getKey())
        assertFalse(vault.hasPersisted())
    }

    @Test
    fun undecryptablePersistedFileSelfHeals() {
        val dir = temp.newFolder()
        MetaKeyVault(dir, FakeSeal()).apply {
            setSessionKey("sk-test-key-5")
            persistSessionKey()
        }
        // Keystore wiped equivalent: open always fails now.
        val wiped = MetaKeyVault(dir, FakeSeal(fail = true))
        assertNull(wiped.getKey())
        assertFalse(wiped.hasPersisted())
    }

    @Test
    fun persistRequiresSessionKeyAndSeal() {
        val vault = vault()
        try {
            vault.persistSessionKey()
            fail("empty persist accepted")
        } catch (_: MetaKeyVault.VaultError) {
        }
        val noSeal = MetaKeyVault(temp.newFolder(), null)
        noSeal.setSessionKey("sk-test-key-6")
        try {
            noSeal.persistSessionKey()
            fail("seal-less persist accepted")
        } catch (_: MetaKeyVault.VaultError) {
        }
        try {
            noSeal.setSessionKey("   ")
            fail("blank key accepted")
        } catch (_: MetaKeyVault.VaultError) {
        }
    }
}
