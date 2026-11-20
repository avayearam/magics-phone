package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the origin device-agent token encrypted by an Android Keystore AES-GCM key. */
internal class GeminiTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gemini_live", Context.MODE_PRIVATE)

    fun hasToken(): Boolean = !load().isNullOrBlank()

    fun save(token: String) {
        val normalized = MagicPhoneCredentialInput.deviceToken(token)
            ?: throw IllegalArgumentException("Invalid agent device token")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(TOKEN_KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun load(): String? {
        return try {
            val stored = prefs.getString(TOKEN_KEY, null) ?: return null
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            require(raw.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
                .takeIf { Regex("^mpa_[a-f0-9]{48}$").matches(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() { prefs.edit().remove(TOKEN_KEY).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "magic-phone-gemini-bridge"
        const val TOKEN_KEY = "bridge_token_aes_gcm"
    }
}
