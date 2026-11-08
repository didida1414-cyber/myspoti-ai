// Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// New file written for this patch set; see NOTICE.md at the root of the patch repository.
package com.music.spotui.gemini

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.music.spotui.di.Graph
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The person's Gemini API key, kept on this phone only. It is stored sealed with an AES key that
 * lives in the Android Keystore and never leaves it, so the preferences file alone is useless.
 */
object GeminiKeyStore {
    private const val TAG = "GeminiKeyStore"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "myspoti_ai_gemini_key"
    private const val FIELD = "gemini_key_sealed"
    private const val IV_BYTES = 12

    private fun prefs() = Graph.app.getSharedPreferences("myspoti_ai_gemini", Context.MODE_PRIVATE)

    private fun sealingKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Stores [key] (a blank one removes the stored key). False when this phone could not seal it. */
    fun save(key: String): Boolean {
        val clean = key.trim()
        if (clean.isEmpty()) {
            clear()
            return true
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, sealingKey())
            val sealed = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            prefs().edit().putString(FIELD, Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "the key could not be saved", e)
            false
        }
    }

    /** The stored key, or null when there is none (or it can no longer be opened). */
    fun get(): String? = try {
        val raw = prefs().getString(FIELD, null)
        if (raw == null) {
            null
        } else {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, sealingKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8).takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    fun has(): Boolean = get() != null

    fun clear() {
        prefs().edit().remove(FIELD).apply()
    }
}
