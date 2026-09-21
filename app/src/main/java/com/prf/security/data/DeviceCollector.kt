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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DeviceCollector {

    private const val UNKNOWN = "Unknown"

    /** Stable per-device folder name: 16 lowercase hex chars. */
    fun getAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.lowercase(Locale.ROOT)
            ?.take(16)
            .orEmpty().ifBlank { "0000000000000000" }

    /** Device-local date folder name, e.g. 2026-09-21. */
    fun dateFolder(ms: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(ms))
    }

    /** Full text of <AndroidID>/user info.txt. */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun collectUserInfo(context: Context): String =
        collect(context, consent = true, timestampMs = System.currentTimeMillis())

    /** Full text of <AndroidID>/user info.txt, always carrying a Consent line. */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun collect(context: Context, consent: Boolean, timestampMs: Long): String {
        val tz = TimeZone.getDefault()
        val offsetMin = tz.getOffset(timestampMs) / 60000
        val tzStr = "GMT%+03d:%02d".format(offsetMin / 60, Math.abs(offsetMin) % 60)

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = try { tm?.networkCountryIso } catch (_: Throwable) { null }
        val opName = try { tm?.networkOperatorName } catch (_: Throwable) { null }
        val op = try { tm?.simOperator } catch (_: Throwable) { null }
        val simCountry = try { tm?.simCountryIso } catch (_: Throwable) { null }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        val memTotal = readMemInfo("MemTotal")
        val memAvail = readMemInfo("MemAvailable")

        return buildString {
            append("AndroidID: ").append(getAndroidId(context)).append('\n')
            append("Consent: ").append(if (consent) "Yes" else "No").append('\n')
            append("CheckInTime: ").append(timeString(timestampMs)).append('\n')
            append("Manufacturer: ").append(nullSafe(Build.MANUFACTURER)).append('\n')
            append("Model: ").append(nullSafe(Build.MODEL)).append('\n')
            append("Brand: ").append(nullSafe(Build.BRAND)).append('\n')
            append("Device: ").append(nullSafe(Build.DEVICE)).append('\n')
            append("Product: ").append(nullSafe(Build.PRODUCT)).append('\n')
            append("BuildId: ").append(nullSafe(Build.ID)).append('\n')
            append("AndroidVersion: ").append(Build.VERSION.RELEASE ?: UNKNOWN).append('\n')
            append("SdkVersion: ").append(Build.VERSION.SDK_INT).append('\n')
            append("SecurityPatch: ").append(Build.VERSION.SECURITY_PATCH ?: UNKNOWN).append('\n')
            append("Timezone: ").append(tzStr).append('\n')
            append("Locale: ").append(Locale.getDefault().toString()).append('\n')
            append("NetworkCountry: ").append(iso ?: UNKNOWN).append('\n')
            append("SimCountry: ").append(simCountry ?: UNKNOWN).append('\n')
            append("Operator: ").append(opName ?: UNKNOWN).append('\n')
            append("SimOperator: ").append(op ?: UNKNOWN).append('\n')
            append("BatteryPercent: ").append(battery).append('\n')
            append("TotalMemoryMB: ").append(memTotal).append('\n')
            append("AvailableMemoryMB: ").append(memAvail).append('\n')
            append("StorageFreeMB: ").append(storageFreeMb(context)).append('\n')
            append("ScreenResolution: ").append(screenResolution(context)).append('\n')
            append("UptimeHours: ").append(uptimeHours()).append('\n')
            append("DeviceSecure: ").append(if (isDeviceSecure(context)) "Yes" else "No").append('\n')
            append("WifiEnabled: ").append(if (isWifiEnabled(context)) "Yes" else "No").append('\n')
            append("AppVersion: 1.0.1").append('\n')
        }
    }

    private fun timeString(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(ms))
    }

    private fun nullSafe(v: String?): String = if (v.isNullOrBlank()) UNKNOWN else v

    private fun readMemInfo(key: String): Long {
        return try {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("$key:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.toLong()
                    ?.div(1024)
                    ?: -1
            }
        } catch (_: Throwable) {
            -1
        }
    }

    private fun storageFreeMb(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
        } catch (_: Throwable) {
            -1
        }
    }

    @Suppress("DEPRECATION")
    private fun screenResolution(context: Context): String {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val pt = android.graphics.Point()
            wm.defaultDisplay.getRealSize(pt)
            "${pt.x}x${pt.y}"
        } catch (_: Throwable) {
            UNKNOWN
        }
    }

    private fun uptimeHours(): String {
        return try {
            "%.1f".format(System.currentTimeMillis() / 3600000.0)
        } catch (_: Throwable) {
            UNKNOWN
        }
    }

    private fun isDeviceSecure(context: Context): Boolean = try {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
            ?.isDeviceSecure ?: false
    } catch (_: Throwable) { false }

    @SuppressLint("WifiManagerPotentialLeak")
    private fun isWifiEnabled(context: Context): Boolean = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.isWifiEnabled ?: false
    } catch (_: Throwable) { false }
}
