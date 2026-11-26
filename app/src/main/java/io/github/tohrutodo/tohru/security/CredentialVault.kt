package io.github.tohrutodo.tohru.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Stores tokens encrypted with a Keystore-backed AES key.
// Ciphertext lives in private SharedPreferences; the key never leaves Keystore.
interface CredentialVault {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
}

class KeystoreVault(context: Context) : CredentialVault {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val keystore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val cipherText = cipher.doFinal(value.toByteArray())
        val packed = cipher.iv + cipherText
        prefs.edit { putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)) }
    }

    override fun get(key: String): String? {
        val packed = prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        if (packed.size < GCM_IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed, 0, GCM_IV_BYTES))
        return String(cipher.doFinal(packed, GCM_IV_BYTES, packed.size - GCM_IV_BYTES))
    }

    override fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    companion object {
        private const val KEY_ALIAS = "tohru-vault-key"
        private const val GCM_IV_BYTES = 12
    }
}
