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

    fun accessToken(): String? = crypto?.token

    fun githubUser(): String = crypto?.owner ?: CryptoStore.DEFAULT_OWNER

    fun repoName(): String = crypto?.repo ?: CryptoStore.DEFAULT_REPO

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
    }
}
