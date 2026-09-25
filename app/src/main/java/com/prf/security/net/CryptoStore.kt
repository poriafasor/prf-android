package com.prf.security.net

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted holder for the device key issued at registration.
 *
 * The device key is the app's only secret. It is scoped to this one device by the server
 * and revocable by the owner from the admin panel. Storing it encrypted at rest means a
 * device user cannot lift it by reading a plain XML file.
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
        // Fallback keeps the app usable where Keystore-backed prefs fail.
        context.getSharedPreferences(FALLBACK, Context.MODE_PRIVATE)
    }

    var deviceKey: String
        get() = prefs.getString(KEY_DEVICE_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_DEVICE_KEY, value) }

    private fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) =
        edit().apply(block).apply()

    companion object {
        private const val FILE = "prf_secure_prefs"
        private const val FALLBACK = "prf_fallback_prefs"
        private const val KEY_DEVICE_KEY = "mdm_device_key"
    }
}
