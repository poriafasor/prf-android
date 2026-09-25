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
 * v1.3.0 permission handling.
 *
 * The only runtime permission the MDM app asks for is ACCESS_FINE_LOCATION, and it has one
 * purpose: the lost-mode location report the owner enables from the admin panel. It is
 * requested with a visible rationale, never pre-granted, never asked from a background
 * context. Camera, microphone and clipboard are not in the manifest at all.
 *
 * One permission per request: a batched request on Android 11+ surfaces only the first
 * dialog and returns a result map missing the rest, which the v1.0 client misread as a
 * denial. The grant decision is always read back from the OS with [isGranted], never from
 * the result map, so "never asked" can never be mistaken for "denied".
 */
object Permissions {

    private const val PREFS = "prf_perm_state"

    /** Precise location. Used only for the lost-mode report. */
    fun location() = android.Manifest.permission.ACCESS_FINE_LOCATION

    /** True when the location permission exists at all in this OS version. */
    fun locationRuntime(context: Context): Boolean =
        context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        ).requestedPermissions?.contains(location()) == true

    /** Reads the real OS state. The only place a grant decision is ever made. */
    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /** Asked before, and the system will no longer show the rationale dialog. */
    fun isPermanentlyDenied(activity: Activity, permission: String): Boolean =
        askedBefore(activity, permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    /** True only while the system may still show a dialog. */
    fun canAskAgain(activity: Activity, permission: String): Boolean =
        !isGranted(activity, permission) && !isPermanentlyDenied(activity, permission)

    @Synchronized
    fun markAsked(context: Context, permission: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key(permission), true).apply()
    }

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
