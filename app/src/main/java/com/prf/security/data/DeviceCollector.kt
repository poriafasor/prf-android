package com.prf.security.data

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.format.Formatter
import android.util.DisplayMetrics
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Hardware and software facts about the phone.
 *
 * This reads the device's own configuration — model, battery, memory, screen,
 * network, SIM. It reads nothing the user typed, stored, or saw: no contacts, no
 * messages, no browsing, no clipboard, no photo, no microphone. The number and
 * the operator in an attendance record are the ones the person typed on the
 * attendance screen themselves, which is also why this object never asks for
 * READ_PHONE_STATE to learn the phone number.
 *
 * Two maps come out of here, for two different jobs:
 *  - [collect] is the registration record, sent once, and keeps the v1.3 key
 *    names the server already stores.
 *  - [specs] is the per-attendance record, sent with every check-in, and uses the
 *    grouped v1.5 key names the panel renders under Persian headings.
 */
object DeviceCollector {

    private const val UNKNOWN = "Unknown"

    /** Stable per-device identifier: 16 lowercase hex chars. */
    fun getAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.lowercase(Locale.ROOT)
            ?.take(16)
            .orEmpty().ifBlank { "0000000000000000" }

    /** Registration hardware map consumed by the server. */
    @SuppressLint("HardwareIds")
    fun collect(context: Context): Map<String, String> = buildMap {
        put("android_id", getAndroidId(context))
        put("manufacturer", Build.MANUFACTURER ?: UNKNOWN)
        put("model", Build.MODEL ?: UNKNOWN)
        put("brand", Build.BRAND ?: UNKNOWN)
        put("device", Build.DEVICE ?: UNKNOWN)
        put("product", Build.PRODUCT ?: UNKNOWN)
        put("sdk", Build.VERSION.SDK_INT.toString())
        put("release", Build.VERSION.RELEASE ?: UNKNOWN)
        put("security_patch", Build.VERSION.SECURITY_PATCH ?: UNKNOWN)

        put("board", Build.BOARD ?: UNKNOWN)
        put("display", Build.DISPLAY ?: UNKNOWN)
        put("fingerprint", Build.FINGERPRINT ?: UNKNOWN)
        put("host", Build.HOST ?: UNKNOWN)
        put("id", Build.ID ?: UNKNOWN)
        put("tags", Build.TAGS ?: UNKNOWN)
        put("type", Build.TYPE ?: UNKNOWN)
        put("user", Build.USER ?: UNKNOWN)

        try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, 0)
            put("app_version_name", pi.versionName ?: UNKNOWN)
            put("app_version_code", pi.longVersionCode.toString())
        } catch (t: Throwable) { put("app_version_name", UNKNOWN) }

        try {
            put("storage_available", Formatter.formatFileSize(context, availableBytes()))
        } catch (t: Throwable) { put("storage_available", UNKNOWN) }

        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            put("battery_level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString())
        } catch (t: Throwable) { put("battery_level", UNKNOWN) }

        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            put("operator", tm.networkOperatorName ?: UNKNOWN)
        } catch (t: Throwable) { put("operator", UNKNOWN) }

        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE)
            if (lm is android.location.LocationManager) {
                put("gps_enabled", lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER).toString())
            }
        } catch (t: Throwable) { put("gps_enabled", UNKNOWN) }
    }

    /**
     * The per-attendance device record.
     *
     * A fact the OS will not hand over without a permission this app deliberately
     * does not hold — the network type needs READ_PHONE_STATE on Android 11+ — is
     * left out of the map entirely rather than filled with a guess. The server
     * drops empty values, so an absent key reads in the panel as "the phone did
     * not report this", which is the truth.
     */
    fun specs(context: Context): Map<String, String> = buildMap {
        // ── battery ────────────────────────────────────────────────────────
        try {
            // The battery broadcast is sticky: reading it with a null receiver is
            // the documented way to get the current state without asking for
            // anything, and it works on every release this app supports.
            val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) {
                    put("battery_percent", (level * 100L / scale).toString())
                }
                put("battery_plugged", plugLabel(b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)))
                put("battery_health", healthLabel(b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)))
                val temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (temp != Int.MIN_VALUE) put("battery_temp", "%.1f°C".format(temp / 10.0))
            }
        } catch (t: Throwable) { /* battery unavailable; the group is simply empty */ }

        // ── software ───────────────────────────────────────────────────────
        put("android_release", Build.VERSION.RELEASE ?: UNKNOWN)
        put("android_sdk", Build.VERSION.SDK_INT.toString())
        put("build_id", Build.ID ?: UNKNOWN)
        put("security_patch", Build.VERSION.SECURITY_PATCH ?: UNKNOWN)
        put("app_version", appVersion(context))
        try {
            // /proc/version is world-readable on every stock Android and needs no
            // permission; it holds the kernel banner and nothing about the user.
            val v = File("/proc/version").readText().trim()
            if (v.isNotEmpty()) put("kernel", v.substringBefore(" (").take(120))
        } catch (t: Throwable) { /* not readable: no key, rather than a placeholder */ }

        // ── hardware ───────────────────────────────────────────────────────
        put("manufacturer", Build.MANUFACTURER ?: UNKNOWN)
        put("model", Build.MODEL ?: UNKNOWN)
        put("brand", Build.BRAND ?: UNKNOWN)
        put("device", Build.DEVICE ?: UNKNOWN)
        put("board", Build.BOARD ?: UNKNOWN)
        put("cpu_abi", Build.SUPPORTED_ABIS?.firstOrNull() ?: UNKNOWN)
        put("cpu_cores", Runtime.getRuntime().availableProcessors().toString())
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            put("ram_total", Formatter.formatFileSize(context, mi.totalMem))
            put("ram_available", Formatter.formatFileSize(context, mi.availMem))
        } catch (t: Throwable) { /* memory stats unavailable on this device */ }
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            put("storage_total", Formatter.formatFileSize(context, stat.blockCountLong * stat.blockSizeLong))
            put("storage_free", Formatter.formatFileSize(context, availableBytes()))
        } catch (t: Throwable) { /* storage stats unavailable */ }
        try {
            val dm = context.resources.displayMetrics
            val w = if (dm.widthPixels >= dm.heightPixels) dm.widthPixels else dm.heightPixels
            val h = if (dm.widthPixels >= dm.heightPixels) dm.heightPixels else dm.widthPixels
            put("screen", "$w×$h px")
            put("density_dpi", "${dm.densityDpi} dpi")
        } catch (t: Throwable) { /* no display metrics */ }

        // ── network and SIM ────────────────────────────────────────────────
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            val name = tm.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
            if (name != null) put("operator", name)
            put("sim_state", simLabel(tm.simState))
            try {
                // Needs READ_PHONE_STATE from Android 11; without it the OS throws
                // and the key is left out instead of being guessed from the MCC.
                @Suppress("DEPRECATION")
                put("network_type", networkLabel(tm.networkType))
            } catch (t: Throwable) { /* the OS would not say; nothing is recorded */ }
        } catch (t: Throwable) { /* no telephony at all on this device */ }
        put("locale", Locale.getDefault().toString())
        put("timezone", TimeZone.getDefault().id)
        put("uptime", uptime(SystemClock.elapsedRealtime()))
    }

    /**
     * The device name shown on the phone, for the header: "Pixel 7 · اندروید ۱۴".
     */
    fun titleLine(context: Context): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: UNKNOWN
        return "$model · Android ${Build.VERSION.RELEASE ?: UNKNOWN}"
    }

    fun batteryPercent(context: Context): Int? = try {
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level >= 0 && scale > 0) (level * 100 / scale) else null
    } catch (t: Throwable) { null }

    // ── label helpers ───────────────────────────────────────────────────────

    private fun plugLabel(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "connected to the charger"
        BatteryManager.BATTERY_PLUGGED_USB -> "connected over USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging wirelessly"
        else -> "not charging"
    }

    private fun healthLabel(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "too hot"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failed"
        BatteryManager.BATTERY_HEALTH_COLD -> "too cold"
        else -> "unknown"
    }

    @Suppress("DEPRECATION")
    private fun simLabel(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_ABSENT -> "no SIM"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        else -> "unknown"
    }

    @Suppress("DEPRECATION")
    private fun networkLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "3G"
        else -> "unknown"
    }

    private fun uptime(ms: Long): String {
        val totalMinutes = ms / 60_000
        val d = totalMinutes / (60 * 24)
        val h = (totalMinutes / 60) % 24
        val m = totalMinutes % 60
        return "${d}d ${h}h ${m}m"
    }

    private fun appVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        pi.versionName ?: UNKNOWN
    } catch (t: Throwable) { UNKNOWN }

    private fun availableBytes(): Long {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
}
