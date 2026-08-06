package com.claustrum.model

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted store for the Hugging Face access token used to download **gated**
 * models (Gemma is gated under the Gemma license). Backed by
 * EncryptedSharedPreferences (AES-256) so the token is not left in plaintext.
 *
 * The token is only ever attached as an `Authorization: Bearer` header to the
 * Hugging Face download request ([ModelDownloadWorker]); it is never logged,
 * shown, or sent anywhere else.
 */
class TokenStore(context: Context) {

    private val prefs by lazy {
        val appCtx = context.applicationContext
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appCtx,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The stored HF token, or null if none set. */
    fun hfToken(): String? =
        try {
            prefs.getString(KEY_HF, null)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.e(TAG, "read token failed", t)
            null
        }

    fun setHfToken(token: String?) {
        try {
            prefs.edit().apply {
                if (token.isNullOrBlank()) remove(KEY_HF) else putString(KEY_HF, token.trim())
            }.apply()
        } catch (t: Throwable) {
            Log.e(TAG, "write token failed", t)
        }
    }

    fun hasHfToken(): Boolean = hfToken() != null

    companion object {
        private const val TAG = "TokenStore"
        private const val FILE = "claustrum_secure_prefs"
        private const val KEY_HF = "hf_access_token"
    }
}
