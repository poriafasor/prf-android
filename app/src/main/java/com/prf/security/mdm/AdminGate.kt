package com.prf.security.mdm

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * What this phone will actually let an app do, and the three doors into it.
 *
 * Everything the panel offers is gated on one of two different grants, and the
 * difference is not cosmetic — it is the difference between a control that works
 * and one that is silently refused:
 *
 *   - **Device Admin** is granted by the user through a system dialog. It allows
 *     locking the screen, resetting the password and (on some devices) wiping.
 *     It does *not* allow suspending another app, setting a user restriction, or
 *     locking the launcher to an allow-list.
 *   - **Device Owner** is granted once, at provisioning time, on a device with no
 *     accounts on it. It allows all of the above plus `setPackagesSuspended`,
 *     which is the only call that genuinely stops an app from opening.
 *
 * So "lock the gallery" is impossible on a phone that is only an admin, and no
 * amount of retrying the admin dialog changes that. The only honest fix is to get
 * the phone to Device Owner, and this class exists to make that reachable and to
 * say plainly which of the two the phone currently is.
 *
 * The previous implementation fired `ACTION_ADD_DEVICE_ADMIN` once from
 * `onCreate`, set a flag saying it had been asked, and never checked whether the
 * dialog had appeared. On any phone where the first attempt did not land — the
 * dialog is post-to-decor so it is dropped if the activity goes away, and the
 * flag was set before the launch, not after — the app never asked again and the
 * user was left with a button that did nothing. That is the whole of
 * "I pressed enable device admin and nothing happened".
 */
object AdminGate {

    private const val TAG = "PRF.AdminGate"

    /** The three states, kept apart because they are not the same thing. */
    enum class Level {
        /** Nothing granted. The panel's owner-only controls will be refused. */
        NONE,

        /** Device Admin only. Lock works; suspend and user restrictions do not. */
        ADMIN,

        /** Device Owner. Everything in this app can work. */
        OWNER,
    }

    fun level(context: Context): Level {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return Level.NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && dpm.isDeviceOwnerApp(context.packageName)) {
            return Level.OWNER
        }
        return if (dpm.isAdminActive(PrfDeviceAdminReceiver.componentName(context))) Level.ADMIN else Level.NONE
    }

    fun isOwner(context: Context): Boolean = level(context) == Level.OWNER

    /**
     * The system dialog that grants Device Admin.
     *
     * Returns false when the intent could not be launched at all, which is a
     * different outcome from the user opening it and declining, and the caller
     * has to be able to tell the two apart: the first is worth retrying, the
     * second is a decision.
     */
    fun requestAdmin(context: Context, explanation: String): Boolean = try {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                PrfDeviceAdminReceiver.componentName(context),
            )
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "ACTION_ADD_DEVICE_ADMIN unavailable: ${t.message}")
        false
    }

    /**
     * The system's own list of device administrators.
     *
     * This is the fallback that cannot fail. The add-dialog is one activity and
     * some OEM builds drop it; the settings list is the same screen a person
     * would use by hand, and from it the app is one toggle away. If the only door
     * to a feature is one that can silently do nothing, that is not a door.
     */
    fun openAdminSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_DEVICE_ADMIN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (t: Throwable) {
        Log.w(TAG, "ACTION_DEVICE_ADMIN_SETTINGS unavailable: ${t.message}")
        false
    }

    /**
     * The device-owner provisioning flow, Android 11 and up.
     *
     * This is the only route to the level that makes app suspension work. The
     * system decides whether it can still happen — it cannot once the phone has
     * accounts on it, or once another app is already the owner — and it says so
     * in its own words when the user gets there, which is the correct place for
     * that answer. Offered rather than forced: on a phone that is already set up
     * this screen is a dead end, and a dead end presented as a solution is the
     * thing this app is not allowed to do.
     */
    fun canOfferProvisioning(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun openProvisioning(context: Context): Boolean {
        if (!canOfferProvisioning(context)) return false
        return try {
            context.startActivity(
                Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (t: Throwable) {
            // Older and heavily-modified builds resolve the constant to nothing.
            Log.w(TAG, "ACTION_PROVISION_MANAGED_DEVICE unavailable: ${t.message}")
            // The QR path, which is what the screen offers when the intent is
            // missing. Deep-linking the NFC provisioning screen is the documented
            // fallback and costs one line.
            try {
                context.startActivity(
                    Intent(Settings.ACTION_NFC_PAYMENT)
                        .setData(Uri.parse("android-app://com.google.android.gms.nfcprovisioning/"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (t2: Throwable) {
                Log.w(TAG, "NFC provisioning screen unavailable: ${t2.message}")
                false
            }
        }
    }

    /**
     * Open this app's own settings page, which is where a permanently-denied
     * permission can be granted again — the runtime dialog will not return.
     */
    fun openAppSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (t: Throwable) {
        false
    }
}
