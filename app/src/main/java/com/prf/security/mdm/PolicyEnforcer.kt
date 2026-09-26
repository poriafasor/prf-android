package com.prf.security.mdm

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.prf.security.data.PolicyState
import com.prf.security.data.PolicyKeys
import com.prf.security.net.Prefs

/**
 * Applies the granular policy the owner set from the panel.
 *
 * Every key here maps to a real device-owner mechanism, and the panel's wording
 * for each key was written to match what this file can actually do on a non-rooted
 * phone. That is the whole point: the app, contacts, calls, sms and gallery keys
 * hide their apps with `setApplicationHidden`, which is the only per-app hiding
 * call DevicePolicyManager has — there is no list-taking variant to batch them
 * with, and `setApplicationRestrictions` cannot target user apps at all.
 *
 * Two keys cannot work the way their names suggest, and the panel says so:
 * notifications need the *user* to grant Do Not Disturb access, and the wifi and
 * airplane keys are user restrictions, which stop the user from changing the
 * setting rather than quietly flipping the radio.
 *
 * `apply` returns null on success or a sentence describing what could not be done, so
 * a policy the device cannot honour reports the reason instead of a green tick.
 */
object PolicyEnforcer {

    private const val TAG = "PRF.Policy"

    /**
     * Apps each policy key pins out of the launcher. This is the mechanism that makes
     * camera/contacts/sms/gallery restrictions real on a stock device.
     */
    private val PIN_TARGETS = mapOf(
        "camera" to listOf(
            "com.android.camera2", "com.android.camera", "com.sec.android.app.camera",
            "com.htc.camera", "com.motorola.camera2", "com.oneplus.camera",
            "com.oppo.camera", "com.vivo.camera", "com.huawei.camera"
        ),
        "gallery" to listOf(
            "com.google.android.apps.photos", "com.sec.android.gallery3d",
            "com.android.gallery3d", "com.miui.gallery", "com.oneplus.gallery",
            "com.coloros.gallery3d", "com.vivo.gallery", "com.huawei.photos"
        ),
        "contacts" to listOf("com.android.contacts", "com.google.android.contacts",
            "com.sec.android.contacts", "com.miui.contacts"),
        "calls" to listOf("com.android.dialer", "com.google.android.dialer",
            "com.sec.android.dialer", "com.android.server.telecom"),
        "sms" to listOf("com.android.mms", "com.google.android.apps.messaging",
            "com.android.messaging", "com.samsung.android.messaging")
    )

    /** Apps the owner added to the "apps" key, stored in prefs. */
    private const val KEY_EXTRA_BLOCKED = "policy_extra_blocked"

    /**
     * Hides or unhides one app, returning null on success or a sentence on refusal.
     *
     * `setApplicationHidden` is the only hiding API DevicePolicyManager actually has —
     * there is no setPackagesHidden — and it hides a single package per call, which is
     * why every caller loops rather than passing a list. It also arrived in Android 8,
     * and it needs the device-owner role, not plain device admin: on an older release
     * or a merely-admin app the system throws, and that refusal is reported rather
     * than swallowed so the panel never shows a policy as applied when it was not.
     */
    private fun hide(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        pkg: String,
        hidden: Boolean,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "hiding apps needs Android 8 or newer"
        }
        return try {
            dpm.setApplicationHidden(admin, pkg, hidden)
            null
        } catch (t: Throwable) {
            val what = if (hidden) "hiding" else "unhiding"
            "$what $pkg was refused: ${t.message}"
        }
    }

    fun apply(context: Context, policy: PolicyState): String? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = PrfDeviceAdminReceiver.componentName(context)

        if (!PrfDeviceAdminReceiver.isAdminActive(context)) {
            return "this app is not a device admin, so no policy can be enforced"
        }
        val isOwner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            dpm.isDeviceOwnerApp(context.packageName)

        // A release window in force means the owner asked for the locks to come off.
        // The server already sends the policy cleared in that case; re-checking here
        // keeps the device correct even if a stale batch is applied.
        if (policy.isReleased(System.currentTimeMillis())) {
            clearAll(context, dpm, admin, isOwner)
            return null
        }

        var failure: String? = null

        // ── lock-task pinning ────────────────────────────────────────────────
        val pinned = mutableSetOf<String>()
        for ((key, targets) in PIN_TARGETS) {
            if (policy.valueOf(key)) pinned.addAll(targets)
        }
        if (policy.apps || policy.lockTask) {
            pinned.addAll(Prefs.get(context).getString(KEY_EXTRA_BLOCKED, "").split(",").filter { it.isNotBlank() })
        }
        for (pkg in pinned) {
            // Never pin the control app: locking the owner's own remote control
            // would leave them with no way to undo it.
            if (pkg == context.packageName) continue
            failure = failure ?: hide(dpm, admin, pkg, true)
        }
        if (!policy.camera && !policy.gallery && !policy.contacts && !policy.calls &&
            !policy.sms && !policy.apps && !policy.lockTask) {
            failure = failure ?: clearPinned(context, dpm, admin)
        }

        // ── notifications ────────────────────────────────────────────────────
        // DevicePolicyManager has no notification API at all, so the real mechanism is
        // NotificationManager's interruption filter: INTERRUPTION_FILTER_NONE silences
        // every notification on the device. It is gated behind "Do Not Disturb access",
        // a special permission the *user* grants in Settings — a device owner cannot
        // take it for itself. So when the key is on and access was never granted, this
        // reports that sentence instead of silently doing nothing.
        if (policy.notifications) {
            failure = failure ?: try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    "notifications stay on until the user grants this app Do Not Disturb access " +
                        "(Settings > Notifications > Do Not Disturb access)"
                } else {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    null
                }
            } catch (t: Throwable) {
                "notifications could not be blocked: ${t.message}"
            }
        } else {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                // Restoring must not depend on the owner role and must not throw on a
                // device where access was revoked in the meantime.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "release: could not restore notifications: ${t.message}")
            }
        }

        // ── uninstall protection ─────────────────────────────────────────────
        if (policy.uninstall) {
            failure = failure ?: try {
                for (pkg in packageManagerInstalled(context)) {
                    if (pkg != context.packageName) dpm.setUninstallBlocked(admin, pkg, true)
                }
                null
            } catch (t: Throwable) {
                "uninstall blocking was refused: ${t.message}"
            }
        }

        // ── connectivity: device owner only ─────────────────────────────────
        // These are user restrictions, not setGlobalSetting. The AOSP javadoc for
        // setGlobalSetting is explicit that WIFI_ON "has no effect as of M" and points
        // at the dedicated mechanisms, and there is no POLICY_CONTROL_WIFI constant in
        // DevicePolicyManager at all. DISALLOW_CONFIG_WIFI / DISALLOW_AIRPLANE_MODE are
        // the real, device-owner-only controls: they stop the user from changing the
        // setting. They do not silently flip the radio, which is what a restriction
        // honestly means.
        for ((key, restriction) in listOf(
            "wifi" to UserManager.DISALLOW_CONFIG_WIFI,
            "airplane" to UserManager.DISALLOW_AIRPLANE_MODE
        )) {
            if (!policy.valueOf(key)) continue
            if (!isOwner) {
                failure = failure ?: "$key control requires this app to be the device owner"
                continue
            }
            failure = failure ?: try {
                dpm.addUserRestriction(admin, restriction)
                null
            } catch (t: Throwable) {
                "$key control was refused: ${t.message}"
            }
        }

        return failure
    }

    /**
     * Re-hide everything the current policy pins. Called after a block_app command
     * so a manual block is not undone by the next policy application.
     */
    fun refreshPinnedState(context: Context) {
        val prefs = Prefs.get(context)
        val blocked = prefs.getString(KEY_EXTRA_BLOCKED, "").split(",").filter { it.isNotBlank() }
        if (blocked.isEmpty()) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = PrfDeviceAdminReceiver.componentName(context)
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return
        for (pkg in blocked) {
            if (pkg == context.packageName) continue
            hide(dpm, admin, pkg, true)?.let { Log.w(TAG, it) }
        }
    }

    /** Remember an app the owner added by hand, so a policy re-apply keeps it pinned. */
    fun rememberExtraBlocked(context: Context, pkg: String) {
        val prefs = Prefs.get(context)
        val current = prefs.getString(KEY_EXTRA_BLOCKED, "").split(",").filter { it.isNotBlank() }
        if (pkg in current) return
        prefs.setString(KEY_EXTRA_BLOCKED, (current + pkg).joinToString(","))
    }

    fun forgetExtraBlocked(context: Context, pkg: String) {
        val prefs = Prefs.get(context)
        val current = prefs.getString(KEY_EXTRA_BLOCKED, "").split(",").filter { it.isNotBlank() && it != pkg }
        prefs.setString(KEY_EXTRA_BLOCKED, current.joinToString(","))
    }

    private fun clearPinned(context: Context, dpm: DevicePolicyManager, admin: ComponentName): String? {
        val all = PIN_TARGETS.values.flatten().toMutableSet()
        all.addAll(Prefs.get(context).getString(KEY_EXTRA_BLOCKED, "").split(",").filter { it.isNotBlank() })
        all.remove(context.packageName)
        var failure: String? = null
        for (pkg in all) {
            // One refusal must not stop the rest: leaving a second app hidden after
            // the owner released the policy would be worse than reporting the error.
            failure = failure ?: hide(dpm, admin, pkg, false)
        }
        return failure
    }

    private fun clearAll(context: Context, dpm: DevicePolicyManager, admin: ComponentName, isOwner: Boolean) {
        clearPinned(context, dpm, admin)
        try {
            // Same mechanism as apply(): the interruption filter, not a DPM call, and
            // only when the user granted Do Not Disturb access in the first place.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release: could not restore notifications: ${t.message}")
        }
        if (isOwner) {
            for (restriction in listOf(
                UserManager.DISALLOW_CONFIG_WIFI,
                UserManager.DISALLOW_AIRPLANE_MODE
            )) {
                try {
                    dpm.clearUserRestriction(admin, restriction)
                } catch (t: Throwable) {
                    Log.w(TAG, "release: could not restore a restriction: ${t.message}")
                }
            }
        }
    }

    private fun packageManagerInstalled(context: Context): List<String> =
        context.packageManager.getInstalledApplications(0).map { it.packageName }

    /** Every policy key this build knows how to apply — used by the status screen. */
    fun supportedKeys(): List<String> = PolicyKeys.ALL
}
