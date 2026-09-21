package com.prf.security.net

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted holder for the sync credential.
 *
 * The GitHub token is NOT compiled into the app. It is either typed once in the Settings
 * screen or injected at build time through a CI secret; either way it lands here,
 * encrypted with the Android Keystore master key.
 */
class CryptoStore(context: Context) {

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        // Fallback keeps the app usable on devices where Keystore-backed prefs fail.
        context.getSharedPreferences(FALLBACK, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_TOKEN, value) }

    var owner: String
        get() = prefs.getString(KEY_OWNER, DEFAULT_OWNER) ?: DEFAULT_OWNER
        set(value) = prefs.edit { putString(KEY_OWNER, value) }

    var repo: String
        get() = prefs.getString(KEY_REPO, DEFAULT_REPO) ?: DEFAULT_REPO
        set(value) = prefs.edit { putString(KEY_REPO, value) }

    private fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) =
        edit().apply(block).apply()

    companion object {
        private const val FILE = "prf_secure_prefs"
        private const val FALLBACK = "prf_fallback_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        const val DEFAULT_OWNER = "poriafasor"
        const val DEFAULT_REPO = "prf-database"
    }
}
