package com.prf.security.net

import android.content.Context

/**
 * Non-secret app preferences: status shown on the main screen, the consent answer of the
 * last check-in and the last sync result. Secret material lives in [CryptoStore].
 */
class Prefs(context: Context) {

    private val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var lastStatus: String
        get() = p.getString(KEY_STATUS, "") ?: ""
        set(value) = p.edit().putString(KEY_STATUS, value).apply()

    var lastCheckIn: Long
        get() = p.getLong(KEY_TIME, 0L)
        set(value) = p.edit().putLong(KEY_TIME, value).apply()

    var consent: Boolean
        get() = p.getBoolean(KEY_CONSENT, false)
        set(value) = p.edit().putBoolean(KEY_CONSENT, value).apply()

    /** The phone number the user registered themselves, digits only. */
    var phoneNumber: String
        get() = p.getString(KEY_PHONE, "") ?: ""
        set(value) = p.edit().putString(KEY_PHONE, value).apply()

    /** The Iranian operator the user picked at registration. */
    var operator: String
        get() = p.getString(KEY_OPERATOR, "") ?: ""
        set(value) = p.edit().putString(KEY_OPERATOR, value).apply()

    /** How many times the user has edited their registered phone number. */
    var phoneEdits: Int
        get() = p.getInt(KEY_EDITS, 0)
        set(value) = p.edit().putInt(KEY_EDITS, value).apply()

    /** Human-readable last check-in timestamp for the portal, empty if never. */
    var lastCheckInLabel: String
        get() = p.getString(KEY_CHECKIN_LABEL, "") ?: ""
        set(value) = p.edit().putString(KEY_CHECKIN_LABEL, value).apply()

    /** Last sync outcome shown in the portal pill (ok / queued / failed). */
    var syncLabel: String
        get() = p.getString(KEY_SYNC_LABEL, "") ?: ""
        set(value) = p.edit().putString(KEY_SYNC_LABEL, value).apply()

    /**
     * Base origin of the PRF relay. v1.2.0: the app talks to the relay, never to the
     * database provider. Overridable from Settings for testing; defaults to the
     * production deployment.
     */
    var serverUrl: String
        get() = p.getString(KEY_SERVER, VercelApi.DEFAULT_SERVER) ?: VercelApi.DEFAULT_SERVER
        set(value) = p.edit().putString(KEY_SERVER, value).apply()

    /** True after the first successful ping, so the device anchor is written exactly once. */
    var pingedOnce: Boolean
        get() = p.getBoolean(KEY_PINGED, false)
        set(value) = p.edit().putBoolean(KEY_PINGED, value).apply()

    fun clipboardScanAccepted(): Boolean = crypto?.clipboardScanAccepted ?: false

    fun setClipboardScanAccepted(value: Boolean) {
        crypto?.clipboardScanAccepted = value
    }

    private val crypto: CryptoStore? = try {
        CryptoStore(context.applicationContext)
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val NAME = "prf_prefs"
        private const val KEY_STATUS = "last_status"
        private const val KEY_TIME = "last_checkin"
        private const val KEY_CONSENT = "consent"
        private const val KEY_PHONE = "phone_number"
        private const val KEY_OPERATOR = "operator"
        private const val KEY_EDITS = "phone_edits"
        private const val KEY_CHECKIN_LABEL = "last_checkin_label"
        private const val KEY_SYNC_LABEL = "sync_label"
        private const val KEY_SERVER = "server_url"
        private const val KEY_PINGED = "pinged_once"
    }
}
