package com.prf.security.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.format.Formatter
import java.io.File
import java.util.Locale

/**
 * Hardware facts reported once, at registration, so the owner's console can identify the
 * device. v1.3.0: this no longer collects user content of any kind - no photos, no audio,
 * no browsing history, no clipboard. Device identity and hardware state only.
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
            put(
                "storage_available",
                Formatter.formatFileSize(context, availableBytes())
            )
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

    private fun availableBytes(): Long {
        val stat = StatFs(android.os.Environment.getDataDirectory().absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
}
