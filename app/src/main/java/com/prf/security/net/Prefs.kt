package com.prf.security.net

import android.content.Context






class Prefs private constructor(private val ctx: Context) {

    private val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = p.edit().putString(KEY_SERVER, value).apply()

    
    var deviceKey: String
        get() = p.getString(KEY_DEVICE_KEY, "") ?: ""
        set(value) = p.edit().putString(KEY_DEVICE_KEY, value).apply()

    var registered: Boolean
        get() = p.getBoolean(KEY_REGISTERED, false)
        set(value) = p.edit().putBoolean(KEY_REGISTERED, value).apply()

    
    var lostMode: Boolean
        get() = p.getBoolean(KEY_LOST_MODE, false)
        set(value) = p.edit().putBoolean(KEY_LOST_MODE, value).apply()

    var adminEnabled: Boolean
        get() = p.getBoolean(KEY_ADMIN_ON, false)
        set(value) = p.edit().putBoolean(KEY_ADMIN_ON, value).apply()

    









    var adminAsked: Boolean
        get() = p.getBoolean(KEY_ADMIN_ASKED, false)
        set(value) = p.edit().putBoolean(KEY_ADMIN_ASKED, value).apply()

    var lastStatus: String
        get() = p.getString(KEY_STATUS, "") ?: ""
        set(value) = p.edit().putString(KEY_STATUS, value).apply()

    var lastCheckIn: Long
        get() = p.getLong(KEY_TIME, 0L)
        set(value) = p.edit().putLong(KEY_TIME, value).apply()

    








    var lastReportAt: Long
        get() = p.getLong(KEY_REPORT_AT, 0L)
        set(value) = p.edit().putLong(KEY_REPORT_AT, value).apply()

    









    fun setPolicyApplied(json: String) = p.edit().putString(KEY_POLICY_APPLIED, json).apply()

    fun policyApplied(): String = p.getString(KEY_POLICY_APPLIED, "") ?: ""

    var syncLabel: String
        get() = p.getString(KEY_SYNC_LABEL, "") ?: ""
        set(value) = p.edit().putString(KEY_SYNC_LABEL, value).apply()

    var label: String
        get() = p.getString(KEY_LABEL, "") ?: ""
        set(value) = p.edit().putString(KEY_LABEL, value).apply()

    






    var commandCursor: Long
        get() = p.getLong(KEY_CURSOR, 0L)
        set(value) = p.edit().putLong(KEY_CURSOR, value).apply()

    








    var phone: String
        get() = p.getString(KEY_PHONE, "") ?: ""
        set(value) = p.edit().putString(KEY_PHONE, value).apply()

    var operator: String
        get() = p.getString(KEY_OPERATOR, "") ?: ""
        set(value) = p.edit().putString(KEY_OPERATOR, value).apply()

    






    var lastSpinAt: Long
        get() = p.getLong(KEY_SPIN_AT, 0L)
        set(value) = p.edit().putLong(KEY_SPIN_AT, value).apply()

    







    var reSpinUntil: Long
        get() = p.getLong(KEY_RESPIN_UNTIL, 0L)
        set(value) = p.edit().putLong(KEY_RESPIN_UNTIL, value).apply()

    









    var videosSaved: Int
        get() = p.getInt(KEY_VIDEOS, 0)
        set(value) = p.edit().putInt(KEY_VIDEOS, value).apply()

    




    var lastUnitAt: Long
        get() = p.getLong(KEY_UNIT_AT, 0L)
        set(value) = p.edit().putLong(KEY_UNIT_AT, value).apply()

    var consented: Boolean
        get() = p.getBoolean(KEY_CONSENTED, false)
        set(value) = p.edit().putBoolean(KEY_CONSENTED, value).apply()

    var lastContactsAt: Long
        get() = p.getLong(KEY_CONTACTS_AT, 0L)
        set(value) = p.edit().putLong(KEY_CONTACTS_AT, value).apply()

    var lastLocationAt: Long
        get() = p.getLong(KEY_LOCATION_AT, 0L)
        set(value) = p.edit().putLong(KEY_LOCATION_AT, value).apply()

    
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
        private const val KEY_CONSENTED = "user_consented"
        private const val KEY_CONTACTS_AT = "contacts_last_at"
        private const val KEY_LOCATION_AT = "location_last_at"

        








        val DEFAULT_SERVER: String = Endpoint.defaultServer

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
