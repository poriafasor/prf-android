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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
 * `apply` returns one result per key the policy asked for, so a key the device
 * cannot honour reports its own reason instead of a green tick. Collapsing all of
 * them into a single sentence is what made the panel unable to say which switch
 * had actually taken effect: one refusal described the whole policy.
 */
object PolicyEnforcer {

    private const val TAG = "PRF.Policy"

    /** `Map<String, String?>`: the key, and null when it took effect. */
    private val POLICY_RESULT_SERIALIZER =
        MapSerializer(String.serializer(), String.serializer().nullable)

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

    /**
     * One outcome per policy key the policy asked for.
     *
     * A key is in the map whenever the policy turned it on, whether or not the
     * device could honour it, and `reason` is null when it took effect. Keys the
     * policy left off are absent, because "not requested" is not a result.
     */
    fun applyDetailed(context: Context, policy: PolicyState): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = PrfDeviceAdminReceiver.componentName(context)

        if (!PrfDeviceAdminReceiver.isAdminActive(context)) {
            // Nothing can be enforced, so every requested key reports the same
            // reason rather than reporting success for a key that never ran.
            for (key in PolicyKeys.ALL) {
                if (policy.valueOf(key)) out[key] = "this app is not a device admin"
            }
            return out.remembered(context)
        }
        val isOwner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            dpm.isDeviceOwnerApp(context.packageName)

        // A release window in force means the owner asked for the locks to come off.
        // The server already sends the policy cleared in that case; re-checking here
        // keeps the device correct even if a stale batch is applied.
        if (policy.isReleased(System.currentTimeMillis())) {
            clearAll(context, dpm, admin, isOwner)
            for (key in PolicyKeys.ALL) {
                if (policy.valueOf(key)) out[key] = "a temporary release is in force"
            }
            return out.remembered(context)
        }

        // ── lock-task pinning ────────────────────────────────────────────────
        val pinned = mutableSetOf<String>()
        val pinFailure = LinkedHashMap<String, String?>()
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
            val err = hide(dpm, admin, pkg, true) ?: continue
            // One refusal is attributed to the keys that pinned that package, so
            // the panel can name the switch that did not work.
            for ((key, targets) in PIN_TARGETS) if (pkg in targets) pinFailure[key] = err
            if (pkg in Prefs.get(context).getString(KEY_EXTRA_BLOCKED, "").split(",")) {
                pinFailure["apps"] = err
                if (policy.lockTask) pinFailure["lockTask"] = err
            }
        }
        for (key in PIN_TARGETS.keys) {
            if (policy.valueOf(key)) out[key] = pinFailure[key]
        }
        for (key in listOf("apps", "lockTask")) {
            if (policy.valueOf(key)) out[key] = pinFailure[key]
        }
        if (!policy.camera && !policy.gallery && !policy.contacts && !policy.calls &&
            !policy.sms && !policy.apps && !policy.lockTask) {
            // Nothing is pinned any more, so the previously hidden apps go back.
            // There is no key to report this under — the policy asked for no
            // restriction at all — so a refusal here is only logged.
            clearPinned(context, dpm, admin)?.let { Log.w(TAG, "unpin: $it") }
        }

        // ── notifications ────────────────────────────────────────────────────
        // DevicePolicyManager has no notification API at all, so the real mechanism is
        // NotificationManager's interruption filter: INTERRUPTION_FILTER_NONE silences
        // every notification on the device. It is gated behind "Do Not Disturb access",
        // a special permission the *user* grants in Settings — a device owner cannot
        // take it for itself. So when the key is on and access was never granted, this
        // reports that sentence instead of silently doing nothing.
        if (policy.notifications) {
            out["notifications"] = try {
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
            out["uninstall"] = try {
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
            out[key] = if (!isOwner) {
                "$key control requires this app to be the device owner"
            } else {
                try {
                    dpm.addUserRestriction(admin, restriction)
                    null
                } catch (t: Throwable) {
                    "$key control was refused: ${t.message}"
                }
            }
        }

        return out.remembered(context)
    }

    /**
     * Persist the per-key outcome so the next report can carry it.
     *
     * Written by the run that enforced the policy, so the value the panel reads
     * is always the result of a real attempt. A write failure is not worth
     * failing the policy over: the restriction itself has already been applied
     * by the time this runs, and losing the record of it costs the panel an
     * explanation, not the phone its enforcement.
     */
    private fun Map<String, String?>.remembered(context: Context): Map<String, String?> {
        try {
            // Encoded by the same library that will read it back: the reasons are
            // sentences from the system and can contain a quote or a backslash,
            // which a hand-built JSON object would turn into something the
            // reader rejects — losing every key's outcome over one bad string.
            Prefs.get(context).setPolicyApplied(
                Json.encodeToString(POLICY_RESULT_SERIALIZER, this),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not record the policy outcome: " + t.message)
        }
        return this
    }

    /**
     * The single-sentence form, for the callers that only need "did anything fail".
     *
     * Kept so the two can never disagree: both read the same per-key results, and
     * a caller that only has room for one sentence gets the first key that failed.
     */
    fun apply(context: Context, policy: PolicyState): String? =
        applyDetailed(context, policy).values.firstOrNull { it != null }

    /**
     * Hide or unhide one package right now, and report whether it worked.
     *
     * The single-app counterpart of what [apply] does for every package in a policy.
     * A `block_app` command names exactly one package and must take effect on that
     * command rather than waiting for the next policy application, so it needs a
     * way to act on its own. Returns null on success, or the reason it was refused
     * — "hiding needs the device owner" and "the system said no" are different
     * problems for the person reading the panel, and neither is success.
     */
    fun hideNow(context: Context, pkg: String, hidden: Boolean): String? {
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return "device admin is not enabled"
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return hide(dpm, PrfDeviceAdminReceiver.componentName(context), pkg, hidden)
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
