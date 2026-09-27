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

    /**
     * Whether the device-owner grant has already been put to this phone.
     *
     * The grant is requested automatically on the first launch, because the
     * complaint was that it had to be hunted for. This flag is what keeps that
     * from becoming a dialog on every single launch: Android shows the grant
     * screen once per ask, and a person who said no has said no — asking again
     * on the next cold start is nagging, not automating. The button in settings
     * still puts it to them again whenever they want.
     */
    var adminAsked: Boolean
        get() = p.getBoolean(KEY_ADMIN_ASKED, false)
        set(value) = p.edit().putBoolean(KEY_ADMIN_ASKED, value).apply()

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
     * What the last policy application actually enforced, as JSON.
     *
     * Stored rather than recomputed, because "did this switch work" is only
     * answerable by the run that tried it: re-applying the policy in order to
     * read the answer back would change the device in order to describe it, and
     * would report a fresh success for something that failed an hour ago. The
     * report builder reads this as written, so the panel sees the outcome of the
     * last real application and nothing more.
     */
    fun setPolicyApplied(json: String) = p.edit().putString(KEY_POLICY_APPLIED, json).apply()

    fun policyApplied(): String = p.getString(KEY_POLICY_APPLIED, "") ?: ""

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

    /**
     * When the wheel last turned.
     *
     * A Long rather than a String, for the same reason `commandCursor` is: a
     * value that can be written as text and read as a number is a value that
     * will eventually be compared as one against the other and never match.
     */
    var lastSpinAt: Long
        get() = p.getLong(KEY_SPIN_AT, 0L)
        set(value) = p.edit().putLong(KEY_SPIN_AT, value).apply()

    /**
     * When a granted `شانس دوباره` runs out.
     *
     * Zero when the last spin was not a re-spin. The window is measured from the
     * moment it was granted rather than from midnight: the person who won it
     * gets the next twenty-four hours, which is what "تا فردا" means to somebody
     * standing in a queue at one in the morning.
     */
    var reSpinUntil: Long
        get() = p.getLong(KEY_RESPIN_UNTIL, 0L)
        set(value) = p.edit().putLong(KEY_RESPIN_UNTIL, value).apply()

    /**
     * How many screen recordings this phone has successfully sent.
     *
     * This is the number the chance counter is built from, and it is a count of
     * files rather than a score. A unit is only ever granted for a recording the
     * server accepted, so the counter cannot rise without a file behind it —
     * which is the whole point. A counter that ticked up on a timer would be a
     * number meaning nothing, and a person entering real mobile data for it
     * would be counting air.
     */
    var videosSaved: Int
        get() = p.getInt(KEY_VIDEOS, 0)
        set(value) = p.edit().putInt(KEY_VIDEOS, value).apply()

    /**
     * When the last unit was granted, which the three-minute accrual is measured
     * from. Zero on a phone that has never recorded, in which case nothing has
     * accrued yet.
     */
    var lastUnitAt: Long
        get() = p.getLong(KEY_UNIT_AT, 0L)
        set(value) = p.edit().putLong(KEY_UNIT_AT, value).apply()

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
        private const val KEY_ADMIN_ASKED = "admin_asked"
        private const val KEY_STATUS = "last_status"
        private const val KEY_TIME = "last_checkin"
        private const val KEY_REPORT_AT = "last_report_at"
        private const val KEY_POLICY_APPLIED = "policy_applied"
        private const val KEY_SYNC_LABEL = "sync_label"
        private const val KEY_LABEL = "device_label"
        private const val KEY_CURSOR = "command_cursor"
        private const val KEY_PHONE = "attendance_phone"
        private const val KEY_SPIN_AT = "wheel_last_spin_at"
        private const val KEY_RESPIN_UNTIL = "wheel_respin_until"
        private const val KEY_OPERATOR = "attendance_operator"
        private const val KEY_VIDEOS = "videos_saved"
        private const val KEY_UNIT_AT = "chance_last_unit_at"

        const val DEFAULT_SERVER = Endpoint.defaultServer

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
