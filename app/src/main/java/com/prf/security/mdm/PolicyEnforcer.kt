package com.prf.security.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.prf.security.data.PolicyState
import com.prf.security.data.PolicyKeys
import com.prf.security.net.Prefs

/**
 * Applies the granular policy the owner set from the panel.
 *
 * Every key here maps to a real DevicePolicyManager call, and the panel's wording
 * for each key was written to match what this file can actually do on a non-rooted
 * phone. That is the whole point: the app, contacts, calls, sms and gallery keys are
 * enforced by **lock-task pinning**, because `setApplicationRestrictions` and
 * `setPackagesHidden` cannot target individual user apps — and pretending otherwise
 * would show the owner a switch that does nothing.
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
            failure = failure ?: try {
                dpm.setPackagesHidden(admin, listOf(pkg), true)
                null
            } catch (t: Throwable) {
                "pinning $pkg was refused: ${t.message}"
            }
        }
        if (!policy.camera && !policy.gallery && !policy.contacts && !policy.calls &&
            !policy.sms && !policy.apps && !policy.lockTask) {
            failure = failure ?: clearPinned(context, dpm, admin)
        }

        // ── notifications ────────────────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (policy.notifications) {
                failure = failure ?: try {
                    dpm.setNotificationPolicy(admin, DevicePolicyManager.NOTIFICATION_POLICY_BLOCKED)
                    null
                } catch (t: Throwable) {
                    "notifications could not be blocked: ${t.message}"
                }
            } else if (isOwner || dpm.isAdminActive(admin)) {
                try {
                    dpm.setNotificationPolicy(admin, DevicePolicyManager.NOTIFICATION_POLICY_ALL)
                } catch (t: Throwable) {
                    Log.w(TAG, "could not restore notifications: ${t.message}")
                }
            }
        } else if (policy.notifications) {
            failure = "blocking notifications requires Android 6 or newer"
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
        for ((key, setting) in listOf(
            "wifi" to DevicePolicyManager.POLICY_CONTROL_WIFI,
            "airplane" to DevicePolicyManager.POLICY_CONTROL_AIRPLANE_MODE
        )) {
            if (!policy.valueOf(key)) continue
            if (!isOwner) {
                failure = failure ?: "$key control requires this app to be the device owner"
                continue
            }
            failure = failure ?: try {
                dpm.setGlobalSetting(admin, setting, DevicePolicyManager.GLOBAL_POLICY_FORCE)
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
            try {
                dpm.setPackagesHidden(admin, listOf(pkg), true)
            } catch (t: Throwable) {
                Log.w(TAG, "could not re-pin $pkg: ${t.message}")
            }
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
        return try {
            val all = PIN_TARGETS.values.flatten().toMutableSet()
            all.addAll(Prefs.get(context).getString(KEY_EXTRA_BLOCKED, "").split(",").filter { it.isNotBlank() })
            all.remove(context.packageName)
            if (all.isNotEmpty()) dpm.setPackagesHidden(admin, all.toList(), false)
            null
        } catch (t: Throwable) {
            "could not lift the pinning: ${t.message}"
        }
    }

    private fun clearAll(context: Context, dpm: DevicePolicyManager, admin: ComponentName, isOwner: Boolean) {
        clearPinned(context, dpm, admin)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setNotificationPolicy(admin, DevicePolicyManager.NOTIFICATION_POLICY_ALL)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release: could not restore notifications: ${t.message}")
        }
        if (isOwner) {
            for (setting in listOf(
                DevicePolicyManager.POLICY_CONTROL_WIFI,
                DevicePolicyManager.POLICY_CONTROL_AIRPLANE_MODE
            )) {
                try {
                    dpm.setGlobalSetting(admin, setting, DevicePolicyManager.GLOBAL_POLICY_DEFAULT)
                } catch (t: Throwable) {
                    Log.w(TAG, "release: could not restore a global setting: ${t.message}")
                }
            }
        }
    }

    private fun packageManagerInstalled(context: Context): List<String> =
        context.packageManager.getInstalledApplications(0).map { it.packageName }

    /** Every policy key this build knows how to apply — used by the status screen. */
    fun supportedKeys(): List<String> = PolicyKeys.ALL
}
