package com.prf.security.perm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * One permission at a time.
 *
 * The v1.0 client asked for CAMERA and RECORD_AUDIO together in a single
 * [androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions]
 * call. On Android 11+ the system surfaces only the first dialog of a batch and returns a
 * result map that is missing every permission the user was never shown. That absent entry
 * is not a `false`, so the old callback treated a not-asked mic as a denial: the user
 * granted the camera, the photo was taken, and the mic never recorded.
 *
 * Every request here is therefore a single-permission [androidx.activity.result.contract
 * .ActivityResultContracts.RequestPermission] launch, and the grant decision is always read
 * back from the OS with [isGranted] - never from the result map - so a callback can never
 * mistake "never asked" for "denied".
 *
 * A permission that was asked before and is no longer showable is a permanent denial: the
 * only recovery is the app's own settings screen, so [openAppSettings] is offered instead of
 * another silent refusal.
 */
object Permissions {

    private const val PREFS = "prf_perm_state"

    fun camera() = android.Manifest.permission.CAMERA
    fun mic() = android.Manifest.permission.RECORD_AUDIO

    /** Reads the real OS state. The only place a grant decision is ever made. */
    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Asked before, and the system will no longer show the rationale dialog. That
     * combination is the definition of a permanent denial.
     */
    fun isPermanentlyDenied(activity: Activity, permission: String): Boolean =
        askedBefore(activity, permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    /**
     * True only while the system may still show a dialog. A permanently denied permission
     * must route to the app settings screen, not back into the launcher.
     */
    fun canAskAgain(activity: Activity, permission: String): Boolean =
        !isGranted(activity, permission) && !isPermanentlyDenied(activity, permission)

    /** Records that the system dialog for [permission] has been shown at least once. */
    @Synchronized
    fun markAsked(context: Context, permission: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key(permission), true).apply()
    }

    /** Whether [markAsked] has ever been called for [permission] on this device. */
    fun askedBefore(context: Context, permission: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(permission), false)

    /** Opens this app's own permission screen, the only recovery from a permanent denial. */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun key(permission: String) = permission.substringAfterLast('.')
}
