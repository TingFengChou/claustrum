package com.claustrum.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted store for the Hugging Face access token used to download **gated**
 * models (Gemma is gated under the Gemma license). Backed by
 * EncryptedSharedPreferences (AES-256) so the token is never left in plaintext.
 *
 * Security discipline (public repo):
 * - The token is read **only** inside [ModelDownloadWorker] at download time and
 *   attached as an `Authorization: Bearer` header — it is never persisted to
 *   WorkManager's job database, never logged, never shown in the UI, never
 *   committed.
 * - The backing prefs file (`claustrum_secure_prefs`) is excluded from Auto
 *   Backup (`allowBackup=false`) so a restore can't leave an undecryptable file.
 * - Keystore init is crash-safe: if the master key is unusable (e.g. corrupted
 *   after a restore) the file is dropped and rebuilt, degrading to "no token"
 *   rather than crashing.
 */
class TokenStore(context: Context) {

    private val appCtx = context.applicationContext
    private val prefs: SharedPreferences? by lazy { openSafely() }

    private fun openSafely(): SharedPreferences? =
        try {
            build()
        } catch (t: Throwable) {
            Log.w(TAG, "secure prefs open failed; resetting", t)
            try {
                appCtx.deleteSharedPreferences(FILE)
                build()
            } catch (e: Throwable) {
                Log.e(TAG, "secure prefs unavailable", e)
                null
            }
        }

    private fun build(): SharedPreferences {
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appCtx,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The stored HF token, or null if none set / store unavailable. */
    fun hfToken(): String? =
        prefs?.getString(KEY_HF, null)?.takeIf { it.isNotBlank() }

    fun setHfToken(token: String?) {
        val p = prefs ?: return
        p.edit().apply {
            if (token.isNullOrBlank()) remove(KEY_HF) else putString(KEY_HF, token.trim())
        }.apply()
    }

    fun hasHfToken(): Boolean = hfToken() != null

    companion object {
        private const val TAG = "TokenStore"
        private const val FILE = "claustrum_secure_prefs"
        private const val KEY_HF = "hf_access_token"
    }
}
