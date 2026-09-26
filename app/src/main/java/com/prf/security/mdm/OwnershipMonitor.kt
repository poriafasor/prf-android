package com.prf.security.mdm

import android.content.Context
import android.os.Build
import com.prf.security.data.OwnershipReport
import com.prf.security.net.Prefs
import java.util.UUID
import android.util.Log
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Collects honest ownership state: is the device locked, is a screen lock set,
 * how many failed unlock attempts since last success. Nothing else is tracked.
 */
object OwnershipMonitor {

    private const val TAG = "PRF.Monitor"

    /** The same shape [PolicyEnforcer] writes, so the two cannot drift apart. */
    private val POLICY_RESULT_SERIALIZER =
        MapSerializer(String.serializer(), String.serializer().nullable)

    private const val KEY_FAILED = "failed_attempts"
    private const val KEY_SECURED = "lock_secured"
    private const val KEY_LOCKED = "was_locked"
    private const val KEY_LAST_EVENT = "last_event"

    fun recordFailedAttempt(context: Context) {
        val p = Prefs.get(context)
        val n = p.getInt(KEY_FAILED, 0)
        p.setInt(KEY_FAILED, n + 1)
        p.setString(KEY_LAST_EVENT, "password_failed")
        OwnershipWorker.runNow(context)
    }

    fun recordUnlock(context: Context) {
        val p = Prefs.get(context)
        p.setInt(KEY_FAILED, 0)
        p.setString(KEY_LAST_EVENT, "password_succeeded")
        OwnershipWorker.runNow(context)
    }

    fun markSecured(context: Context) {
        Prefs.get(context).setBoolean(KEY_SECURED, true)
    }

    fun markLockedState(context: Context, locked: Boolean) {
        Prefs.get(context).setBoolean(KEY_LOCKED, locked)
    }

    fun buildReport(context: Context, trigger: String): OwnershipReport {
        val p = Prefs.get(context)
        return OwnershipReport(
            id = UUID.randomUUID().toString(),
            androidId = Prefs.androidId(context),
            timestampMs = System.currentTimeMillis(),
            locked = isCurrentlyLocked(context),
            lost = p.getBoolean(Prefs.KEY_LOST_MODE, false),
            secured = isSecured(context),
            failedAttempts = p.getInt(KEY_FAILED, 0),
            screenOn = isScreenOn(context),
            eventTriggered = trigger.ifEmpty { p.getString(KEY_LAST_EVENT, "periodic") },
            appVersion = appVersionName(context),
            policyApplied = readPolicyApplied(p)
        )
    }

    /**
     * The outcome of the last policy application, as stored by the run that
     * enforced it.
     *
     * Read back rather than recomputed on purpose: this report is a description
     * of the device, and describing it must not change it. A malformed or absent
     * record reads as an empty map, which the panel shows as "the device has not
     * reported yet" rather than as a set of switches that failed.
     */
    private fun readPolicyApplied(p: Prefs): Map<String, String?> {
        val raw = p.policyApplied()
        if (raw.isBlank()) return emptyMap()
        return try {
            Json.decodeFromString(POLICY_RESULT_SERIALIZER, raw)
        } catch (t: Throwable) {
            Log.w(TAG, "stored policy outcome is unreadable: " + t.message)
            emptyMap()
        }
    }

    private fun isCurrentlyLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return km.isKeyguardLocked
    }

    private fun isSecured(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) km.isDeviceSecure
        else km.isKeyguardSecure
    }

    private fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isInteractive
    }

    private fun appVersionName(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
