package com.prf.security.mdm

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.prf.security.data.Prefs

/**
 * Device Admin hook. Lets the owner lock the device and (when Device Owner) wipe it remotely.
 * Everything here is user-visible Android MDM API - no hidden surveillance.
 */
class PrfDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "device admin enabled")
        Prefs.get(context).setAdminEnabled(true)
        OwnershipMonitor.markSecured(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling removes the owner's ability to lock or wipe this phone if it is lost."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "device admin disabled")
        Prefs.get(context).setAdminEnabled(false)
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        OwnershipMonitor.recordFailedAttempt(context)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        OwnershipMonitor.recordUnlock(context)
    }

    companion object {
        private const val TAG = "PRF.Admin"

        fun componentName(context: Context): ComponentName =
            ComponentName(context, PrfDeviceAdminReceiver::class.java)

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(componentName(context))
        }
    }
}
