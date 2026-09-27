package com.prf.security.perm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat



















object Permissions {

    private const val PREFS = "prf_perm_state"

    





    const val LOCATION = android.Manifest.permission.ACCESS_FINE_LOCATION

    
    fun location() = android.Manifest.permission.ACCESS_FINE_LOCATION

    
    fun locationRuntime(context: Context): Boolean =
        context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        ).requestedPermissions?.contains(location()) == true

    
    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    
    fun isPermanentlyDenied(activity: Activity, permission: String): Boolean =
        askedBefore(activity, permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    
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
