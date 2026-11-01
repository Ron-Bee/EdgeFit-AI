package ai.edgefit.diag

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-256/GCM seal for the GLOBAL upload vault.
 * Separate alias from [AndroidKeySeal] so Meta BYOK keys and EdgeFit
 * upload tokens can never share key material. Same documented
 * tradeoff: device-bound custody, no biometric gating, unusable
 * before first unlock on API 28+. Android-only (fake [KeySeal] in
 * JVM tests).
 */
class GlobalKeySeal : KeySeal {
    companion object {
        const val ALIAS = "edgefit_global_upload_v1"
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 28) setUnlockedDeviceRequired(true)
            }
            .build()
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec)
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        return cipher.iv to cipher.doFinal(plain)
    }

    override fun open(iv: ByteArray, cipher: ByteArray): ByteArray {
        val instance = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return instance.doFinal(cipher)
    }
}
