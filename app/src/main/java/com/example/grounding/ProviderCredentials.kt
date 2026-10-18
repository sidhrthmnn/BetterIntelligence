package com.example.grounding

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only encrypted bytes are persisted. The non-exportable key is kept in Android Keystore. */
class ProviderCredentials(context: Context) {
    private val preferences = context.getSharedPreferences("search_credentials", Context.MODE_PRIVATE)
    private val alias = "bi.brave.search.v1"
    val hasBraveKey: Boolean get() = preferences.contains("ciphertext")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun saveBrave(value: String) {
        require(value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() }) { "Enter a valid API key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "Could not save the API key" }
    }
    fun brave(): String? {
        val encrypted = preferences.getString("ciphertext", null) ?: return null
        return runCatching {
            val iv = Base64.decode(preferences.getString("iv", null), Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
            }
        }.getOrNull()
    }
    fun clearBrave() { preferences.edit().clear().apply() }
}
