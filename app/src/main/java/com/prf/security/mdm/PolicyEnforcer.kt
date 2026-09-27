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
 * phone. That is the whole point.
 *
 * The app-locking keys (camera, gallery, contacts, calls, sms, apps) delegate to
 * [AppBlocker], which stacks `setPackagesSuspended` over `setApplicationHidden`
 * and `setUninstallBlocked`. That ordering is the fix for the v1.7.0 failure
 * where a green switch in the panel only removed a launcher icon: the user could
 * still open the gallery from the file manager, and the panel said it was locked.
 * AppBlocker reports which mechanism actually took, so a lock that only hid the
 * icon is reported as a partial lock with that stated, not as success.
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
     * The keys that mean "this app must not be openable", in the order they are
     * applied. Each names a class of app rather than a fixed package list —
     * AppBlocker resolves the real packages on the phone it is running on, so a
     * device whose gallery is not in any known-brand list is still covered.
     */
    private val APP_LOCK_KEYS = listOf("camera", "gallery", "contacts", "calls", "sms")

    /** Apps the owner added to the "apps" key, stored in prefs. */
    private const val KEY_EXTRA_BLOCKED = "policy_extra_blocked"

    private fun extraBlocked(context: Context): List<String> =
        Prefs.get(context).getString(KEY_EXTRA_BLOCKED, "").split(",").filter { it.isNotBlank() }

    /**
     * Block one package the owner named by hand, or undo it.
     *
     * The single-package counterpart of what the key-based path does for a whole
     * class of apps. Kept here rather than in [AppBlocker] so that the extra-blocked
     * list stays the single record of what the owner asked for by name — the list
     * is what a later policy re-apply walks, and a command that did not add its
     * target to it left the panel reporting a block that was not there.
     */
    fun setExtraBlocked(context: Context, pkg: String, blocked: Boolean): String? {
        if (blocked) rememberExtraBlocked(context, pkg) else forgetExtraBlocked(context, pkg)
        val res = if (blocked) AppBlocker.block(context, pkg) else AppBlocker.unblock(context, pkg)
        if (res.level == AppBlocker.Level.NONE) {
            // A block that did not take must not be left behind in the list, or
            // every later policy re-apply would keep retrying it and the panel
            // would keep showing a block that is not there.
            if (blocked) forgetExtraBlocked(context, pkg)
            return res.reason ?: "the system refused the change"
        }
        return null
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

        // ── app locking ──────────────────────────────────────────────────────
        // Each key is resolved to real packages on THIS phone and blocked with
        // suspend+hide+uninstall-block, so a key that only got the icon hidden is
        // reported as a partial lock rather than as applied.
        for (key in APP_LOCK_KEYS) {
            if (policy.valueOf(key)) {
                out[key] = AppBlocker.blockKey(context, key)[key]
            } else {
                // A key that was switched off must un-do what an earlier
                // application did, or the phone keeps the previous policy forever
                // while the panel shows the switch as off. Only the keys that were
                // ever applied are worth unblocking, and AppBlocker resolves the
                // same list either way.
                releaseKey(context, key)
            }
        }

        // ── apps the owner named by hand ─────────────────────────────────────
        // Enforced on every policy application, whether or not the "apps" or
        // "lockTask" keys are on. That is deliberate, and it is the fix for a
        // specific failure: a `block_app` command adds its package to this list,
        // and the previous code only re-blocked the list while `apps` was on — so
        // a manual block was undone by the very next poll, seconds later, and the
        // panel showed Contacts blocked while Contacts opened normally.
        //
        // Each entry here was put in deliberately, by a command or by the owner
        // ticking "apps", so nothing clears it implicitly. A block is lifted by an
        // explicit `unblock_app`, which removes the package from this list — and
        // the panel says so on the row, because a switch that reads as "turn the
        // lock off" and does not is the same kind of lie this project is not
        // allowed to ship.
        var extraFailure: String? = null
        for (pkg in extraBlocked(context)) {
            if (pkg == context.packageName) continue
            val res = AppBlocker.block(context, pkg)
            if (res.level == AppBlocker.Level.NONE) extraFailure = extraFailure ?: res.reason
        }
        if (policy.apps) out["apps"] = extraFailure
        if (policy.lockTask) out["lockTask"] = extraFailure

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
     * Block or unblock one package right now, and report whether it worked.
     *
     * The single-app counterpart of what [apply] does for every package in a policy.
     * A `block_app` command names exactly one package and must take effect on that
     * command rather than waiting for the next policy application, so it needs a
     * way to act on its own. Returns null on success, or the reason it was refused
     * — "not the device owner" and "the system said no" are different problems for
     * the person reading the panel, and neither is success.
     *
     * Named `setBlocked` rather than the old `hideNow` because what it does is no
     * longer hiding: it suspends, and the old name is what made the code read as
     * though hiding the icon were the whole of the mechanism.
     */
    fun setBlocked(context: Context, pkg: String, blocked: Boolean): String? {
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return "device admin is not enabled"
        return setExtraBlocked(context, pkg, blocked)
    }

    /**
     * Re-apply every block the current policy asked for.
     *
     * Called after a block_app command and at every policy application. It is also
     * what makes a block survive the phone being rebooted or the app being killed:
     * suspend and hide are system state, but a phone that was factory-reset or had
     * its policy storage cleared comes back with neither, so the owner cannot rely
     * on the block unless something re-applies it.
     */
    fun refreshBlockedState(context: Context) {
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return
        for (pkg in extraBlocked(context)) {
            if (pkg == context.packageName) continue
            val res = AppBlocker.block(context, pkg)
            if (res.level == AppBlocker.Level.NONE) Log.w(TAG, "re-block ${res.describe()}")
        }
    }

    /** Remember an app the owner added by hand, so a policy re-apply keeps it pinned. */
    fun rememberExtraBlocked(context: Context, pkg: String) {
        val prefs = Prefs.get(context)
        val current = extraBlocked(context)
        if (pkg in current) return
        prefs.setString(KEY_EXTRA_BLOCKED, (current + pkg).joinToString(","))
    }

    fun forgetExtraBlocked(context: Context, pkg: String) {
        val prefs = Prefs.get(context)
        val current = extraBlocked(context).filter { it != pkg }
        prefs.setString(KEY_EXTRA_BLOCKED, current.joinToString(","))
    }

    /**
     * Lift every app lock a key installed, whether or not it ever succeeded.
     *
     * The "whether or not" matters. A phone that suspended the gallery, then had
     * the policy cleared on a build that cannot suspend, would otherwise keep the
     * suspend state forever with no code path that knows to clear it. Unblocking
     * is cheap and idempotent, so it runs for every off-key rather than tracking
     * which ones previously took.
     */
    private fun releaseKey(context: Context, key: String) {
        AppBlocker.unblockKey(context, key)
    }

    /** Lift every app lock there is, for a release window or a full clear. */
    private fun clearBlocked(context: Context): String? {
        var failure: String? = null
        for (key in APP_LOCK_KEYS) {
            AppBlocker.unblockKey(context, key)
        }
        for (pkg in extraBlocked(context)) {
            if (pkg == context.packageName) continue
            // One refusal must not stop the rest: leaving a second app blocked
            // after the owner released the policy would be worse than reporting
            // the error.
            failure = failure ?: AppBlocker.unblock(context, pkg).reason
        }
        return failure
    }

    private fun clearAll(context: Context, dpm: DevicePolicyManager, admin: ComponentName, isOwner: Boolean) {
        clearBlocked(context)
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
