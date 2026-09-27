package com.prf.security.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Puts the phone back under management after a restart.
 *
 * Suspend and hide are system state and do survive a reboot, but two things do
 * not, and both of them are the difference between a lock that holds and one
 * that quietly stops:
 *
 *  - the *watcher*. Android does not restart a foreground service across a
 *    reboot, so without this the phone came back looking perfectly healthy in
 *    the panel while accepting no commands at all — the same silent failure the
 *    service itself was written to fix, one level up.
 *  - the *policy in this app's own state*. Any path that clears the owner's
 *    policy (a restore, a "reset app settings", an OS upgrade that recreates the
 *    profile) leaves the system-level blocks in place but the app with no record
 *    of which packages the owner asked to block, so a later unblock would have
 *    nothing to unblock and the app could not re-assert the block if the system
 *    dropped it.
 *
 * The re-assert is what makes "lock the gallery" mean it. `refreshBlockedState`
 * walks the recorded list and blocks each package again, so a phone that was
 * rebooted, updated, or had its policy storage cleared comes back with the lock
 * the owner set rather than with an assumption that the lock is still there.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.action ?: return
        if (what !in HANDLED) return

        // A receiver's onReceive has a few seconds before the system may kill the
        // process, and every call below is network-free but not instant — the
        // block re-assert walks the package manager. goAsync() keeps the process
        // alive until the work is genuinely finished rather than racing it.
        val pending = goAsync()
        val app = context.applicationContext
        try {
            // Re-assert first and synchronously. It touches only the system, so
            // it succeeds even with no network at all, and the phone is locked
            // before the watcher has finished starting.
            if (PrfDeviceAdminReceiver.isAdminActive(app)) {
                PolicyEnforcer.refreshBlockedState(app)
            }
            // The periodic worker is the belt to the watcher's braces: if the
            // service is refused or killed, the slow chain still reports and
            // still applies policy.
            OwnershipWorker.schedulePeriodic(app)
            PolicyWatchService.start(app)
        } catch (t: Throwable) {
            Log.w(TAG, "could not restore management after $what: ${t.message}")
        } finally {
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "PRF.Boot"

        /**
         * The broadcasts that mean "this phone just came back".
         *
         * `LOCKED_BOOT_COMPLETED` is the interesting one and arrives before the
         * user unlocks, which is the first moment at which this app's own storage
         * is readable — `BOOT_COMPLETED` alone is delivered after unlock, so on a
         * phone with a secure lock screen the restore happened only once the user
         * had typed their password. Both are registered for that reason.
         *
         * `MY_PACKAGE_REPLACED` covers the upgrade case: installing the new build
         * is exactly when a new device-owner policy has to be re-asserted, and
         * when the panel and the app have just changed version.
         */
        private val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
