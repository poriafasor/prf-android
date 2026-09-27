package com.prf.security.mdm

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log





























object AdminGate {

    private const val TAG = "PRF.AdminGate"

    




    private const val ACTION_DEVICE_ADMIN_SETTINGS = "android.settings.DEVICE_ADMIN_SETTINGS"

    



    private const val ACTION_NFC_PAYMENT = "android.nfc.NFC_PAYMENT_SETTINGS"

    
    enum class Level {
        
        NONE,

        
        ADMIN,

        
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

    







    fun requestAdmin(context: Context, explanation: String): Boolean {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                PrfDeviceAdminReceiver.componentName(context),
            )
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        }
        return start(context, intent)
    }

    













    fun openAdminSettings(context: Context): Boolean {
        if (start(context, Intent(ACTION_DEVICE_ADMIN_SETTINGS))) return true
        Log.w(TAG, "no device-admin settings screen, falling back to security settings")
        return start(context, Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    










    fun canOfferProvisioning(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun openProvisioning(context: Context): Boolean {
        if (!canOfferProvisioning(context)) return false
        if (start(context, Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE))) return true
        Log.w(TAG, "ACTION_PROVISION_MANAGED_DEVICE unavailable, trying the NFC screen")
        
        
        
        return start(
            context,
            Intent(ACTION_NFC_PAYMENT)
                .setData(Uri.parse("android-app://com.google.android.gms.nfcprovisioning/")),
        )
    }

    






    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "${intent.action} unavailable: ${t.message}")
        false
    }

    



    fun openAppSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null)),
    )
}
