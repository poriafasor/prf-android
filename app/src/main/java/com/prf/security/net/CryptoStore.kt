package com.prf.security.net

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted holder for the one secret the app keeps at rest.
 *
 * v1.2.0: the app no longer holds a database credential at all - the relay owns that.
 * The only flag still here is the clipboard-scan consent, which is stored encrypted so
 * a device-user cannot flip it by editing a plain XML file.
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

    /** Whether the user accepted the on-device clipboard threat scan. Off until accepted. */
    var clipboardScanAccepted: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD, false)
        set(value) = prefs.edit { putBoolean(KEY_CLIPBOARD, value) }

    private fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) =
        edit().apply(block).apply()

    companion object {
        private const val FILE = "prf_secure_prefs"
        private const val FALLBACK = "prf_fallback_prefs"
        private const val KEY_CLIPBOARD = "clipboard_scan_accepted"
    }
}
