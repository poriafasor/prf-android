package com.prf.security.net

import android.content.Context

/**
 * App preferences for the PRF MDM client: server URL, the device key issued at registration,
 * lost-mode flag, admin state and counters shown on the status screen.
 * The device key is the only credential - revocable by the owner from the admin panel.
 */
class Prefs private constructor(private val ctx: Context) {

    private val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = p.edit().putString(KEY_SERVER, value).apply()

    /** Device key issued by the server at registration. Empty until registered. */
    var deviceKey: String
        get() = p.getString(KEY_DEVICE_KEY, "") ?: ""
        set(value) = p.edit().putString(KEY_DEVICE_KEY, value).apply()

    var registered: Boolean
        get() = p.getBoolean(KEY_REGISTERED, false)
        set(value) = p.edit().putBoolean(KEY_REGISTERED, value).apply()

    /** Set by the owner from the admin panel. Only then is location reported. */
    var lostMode: Boolean
        get() = p.getBoolean(KEY_LOST_MODE, false)
        set(value) = p.edit().putBoolean(KEY_LOST_MODE, value).apply()

    var adminEnabled: Boolean
        get() = p.getBoolean(KEY_ADMIN_ON, false)
        set(value) = p.edit().putBoolean(KEY_ADMIN_ON, value).apply()

    var lastStatus: String
        get() = p.getString(KEY_STATUS, "") ?: ""
        set(value) = p.edit().putString(KEY_STATUS, value).apply()

    var lastCheckIn: Long
        get() = p.getLong(KEY_TIME, 0L)
        set(value) = p.edit().putLong(KEY_TIME, value).apply()

    /**
     * When the last full ownership report was accepted by the server.
     *
     * Kept apart from [lastCheckIn] because the two now run at different rates. A
     * report is a write on the server and costs a git commit against a budget of
     * roughly 128 an hour; asking for commands is a read that costs nothing.
     * Reporting on every poll would make a fast poll unaffordable, so the poll runs
     * often and the report only once this has gone stale.
     */
    var lastReportAt: Long
        get() = p.getLong(KEY_REPORT_AT, 0L)
        set(value) = p.edit().putLong(KEY_REPORT_AT, value).apply()

    /**
     * When a screen frame was last sent, and a digest of the one that was sent.
     *
     * The digest is what makes sharing cheap: a screen that has not changed
     * costs nothing to notice. The frame itself is far too large to keep here.
     */
    var lastScreenAt: Long
        get() = p.getLong(KEY_SCREEN_AT, 0L)
        set(value) = p.edit().putLong(KEY_SCREEN_AT, value).apply()

    var lastScreenDigest: String
        get() = p.getString(KEY_SCREEN_DIGEST, "") ?: ""
        set(value) = p.edit().putString(KEY_SCREEN_DIGEST, value).apply()

    var syncLabel: String
        get() = p.getString(KEY_SYNC_LABEL, "") ?: ""
        set(value) = p.edit().putString(KEY_SYNC_LABEL, value).apply()

    var label: String
        get() = p.getString(KEY_LABEL, "") ?: ""
        set(value) = p.edit().putString(KEY_LABEL, value).apply()

    /**
     * How far down the command queue this device has already been served.
     *
     * A Long, stored as one. v1.3.0 kept this as a String while the server sent a
     * number, so it never compared equal to what came back and every batch was
     * discarded without any error being raised.
     */
    var commandCursor: Long
        get() = p.getLong(KEY_CURSOR, 0L)
        set(value) = p.edit().putLong(KEY_CURSOR, value).apply()

    /**
     * The number and the carrier the person typed at the top of the single
     * screen. Remembered so the second tap of the day is not eleven digits
     * again.
     *
     * This is their own number, typed by them, on their phone. The app never
     * reads it from the SIM: that needs READ_PHONE_STATE, and it would stop
     * being the user's act, which is the whole point of asking.
     */
    var phone: String
        get() = p.getString(KEY_PHONE, "") ?: ""
        set(value) = p.edit().putString(KEY_PHONE, value).apply()

    var operator: String
        get() = p.getString(KEY_OPERATOR, "") ?: ""
        set(value) = p.edit().putString(KEY_OPERATOR, value).apply()

    // ---- generic typed helpers used by OwnershipMonitor ----
    fun getInt(k: String, def: Int) = p.getInt(k, def)
    fun setInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun getString(k: String, def: String) = p.getString(k, def) ?: def
    fun setString(k: String, v: String) = p.edit().putString(k, v).apply()
    fun getBoolean(k: String, def: Boolean) = p.getBoolean(k, def)
    fun setBoolean(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    companion object {
        private const val NAME = "prf_prefs"
        private const val KEY_SERVER = "server_url"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_REGISTERED = "registered"
        const val KEY_LOST_MODE = "lost_mode"
        private const val KEY_ADMIN_ON = "admin_enabled"
        private const val KEY_STATUS = "last_status"
        private const val KEY_TIME = "last_checkin"
        private const val KEY_REPORT_AT = "last_report_at"
        private const val KEY_SCREEN_AT = "last_screen_at"
        private const val KEY_SCREEN_DIGEST = "last_screen_digest"
        private const val KEY_SYNC_LABEL = "sync_label"
        private const val KEY_LABEL = "device_label"
        private const val KEY_CURSOR = "command_cursor"
        private const val KEY_PHONE = "attendance_phone"
        private const val KEY_OPERATOR = "attendance_operator"

        const val DEFAULT_SERVER = "https://prf-panel.vercel.app"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context.applicationContext).also { instance = it } }

        fun androidId(context: Context): String {
            return get(context).getString("android_id_cache", "")
        }

        fun cacheAndroidId(context: Context, id: String) {
            get(context).setString("android_id_cache", id)
        }
    }
}
