package ai.edgefit.diag

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GlobalTokenVaultTest {
    private class FakeSeal : KeySeal {
        override fun seal(plain: ByteArray) =
            byteArrayOf(9) to plain.reversedArray()

        override fun open(iv: ByteArray, cipher: ByteArray) =
            cipher.reversedArray()
    }

    private fun vault(seal: KeySeal? = FakeSeal()): GlobalTokenVault {
        val dir = Files.createTempDirectory("gvault-test").toFile()
        return GlobalTokenVault(dir, seal)
    }

    @Test
    fun `session token round-trips without file`() {
        val v = vault()
        v.setSessionToken("uploadtoken-12345678")
        assertEquals("uploadtoken-12345678", v.getToken())
        assertFalse(v.hasPersisted())
    }

    @Test
    fun `persisted token survives new vault instance`() {
        val dir = Files.createTempDirectory("gvault-test").toFile()
        val first = GlobalTokenVault(dir, FakeSeal())
        first.setSessionToken("uploadtoken-12345678")
        first.persistSessionToken()
        assertTrue(first.hasPersisted())
        val second = GlobalTokenVault(dir, FakeSeal())
        assertEquals("uploadtoken-12345678", second.getToken())
    }

    @Test
    fun `suffix never exposes the token`() {
        val v = vault()
        assertNull(v.suffix())
        v.setSessionToken("uploadtoken-12345678")
        assertEquals("…5678", v.suffix())
    }

    @Test
    fun `forget clears session and file`() {
        val dir = Files.createTempDirectory("gvault-test").toFile()
        val v = GlobalTokenVault(dir, FakeSeal())
        v.setSessionToken("uploadtoken-12345678")
        v.persistSessionToken()
        assertTrue(v.forget())
        assertNull(v.getToken())
        assertFalse(v.hasPersisted())
        assertFalse(v.forget())
    }

    @Test
    fun `bad token shapes rejected`() {
        val v = vault()
        try {
            v.setSessionToken("tiny")
            fail("expected VaultError")
        } catch (_: GlobalTokenVault.VaultError) {
        }
        try {
            v.setSessionToken("   ")
            fail("expected VaultError")
        } catch (_: GlobalTokenVault.VaultError) {
        }
    }

    @Test
    fun `persist without seal fails closed`() {
        val v = vault(seal = null)
        v.setSessionToken("uploadtoken-12345678")
        try {
            v.persistSessionToken()
            fail("expected VaultError")
        } catch (_: GlobalTokenVault.VaultError) {
        }
    }

    @Test
    fun `uses its own file not the meta vault file`() {
        val dir = Files.createTempDirectory("gvault-test").toFile()
        val v = GlobalTokenVault(dir, FakeSeal())
        v.setSessionToken("uploadtoken-12345678")
        v.persistSessionToken()
        assertTrue(java.io.File(dir, "global_upload_token.bin").isFile)
        assertFalse(java.io.File(dir, "meta_key.bin").isFile)
    }
}
