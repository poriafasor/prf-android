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


























object PolicyEnforcer {

    private const val TAG = "PRF.Policy"

    
    private val POLICY_RESULT_SERIALIZER =
        MapSerializer(String.serializer(), String.serializer().nullable)

    





    private val APP_LOCK_KEYS = listOf("camera", "gallery", "contacts", "calls", "sms")

    
    private const val KEY_EXTRA_BLOCKED = "policy_extra_blocked"

    private fun extraBlocked(context: Context): List<String> =
        Prefs.get(context).getString(KEY_EXTRA_BLOCKED, "").split(",").filter { it.isNotBlank() }

    








    fun setExtraBlocked(context: Context, pkg: String, blocked: Boolean): String? {
        if (blocked) rememberExtraBlocked(context, pkg) else forgetExtraBlocked(context, pkg)
        val res = if (blocked) AppBlocker.block(context, pkg) else AppBlocker.unblock(context, pkg)
        if (res.level == AppBlocker.Level.NONE) {
            
            
            
            if (blocked) forgetExtraBlocked(context, pkg)
            return res.reason ?: "the system refused the change"
        }
        return null
    }

    






    fun applyDetailed(context: Context, policy: PolicyState): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = PrfDeviceAdminReceiver.componentName(context)

        if (!PrfDeviceAdminReceiver.isAdminActive(context)) {
            
            
            for (key in PolicyKeys.ALL) {
                if (policy.valueOf(key)) out[key] = "this app is not a device admin"
            }
            return out.remembered(context)
        }
        val isOwner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            dpm.isDeviceOwnerApp(context.packageName)

        
        
        
        if (policy.isReleased(System.currentTimeMillis())) {
            clearAll(context, dpm, admin, isOwner)
            for (key in PolicyKeys.ALL) {
                if (policy.valueOf(key)) out[key] = "a temporary release is in force"
            }
            return out.remembered(context)
        }

        
        
        
        
        for (key in APP_LOCK_KEYS) {
            if (policy.valueOf(key)) {
                out[key] = AppBlocker.blockKey(context, key)[key]
            } else {
                
                
                
                
                
                releaseKey(context, key)
            }
        }

        
        
        
        
        
        
        
        
        
        
        
        
        
        
        var extraFailure: String? = null
        for (pkg in extraBlocked(context)) {
            if (pkg == context.packageName) continue
            val res = AppBlocker.block(context, pkg)
            if (res.level == AppBlocker.Level.NONE) extraFailure = extraFailure ?: res.reason
        }
        if (policy.apps) out["apps"] = extraFailure
        if (policy.lockTask) out["lockTask"] = extraFailure

        
        
        
        
        
        
        
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
                
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "release: could not restore notifications: ${t.message}")
            }
        }

        
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

    








    private fun Map<String, String?>.remembered(context: Context): Map<String, String?> {
        try {
            
            
            
            
            Prefs.get(context).setPolicyApplied(
                Json.encodeToString(POLICY_RESULT_SERIALIZER, this),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not record the policy outcome: " + t.message)
        }
        return this
    }

    





    fun apply(context: Context, policy: PolicyState): String? =
        applyDetailed(context, policy).values.firstOrNull { it != null }

    













    fun setBlocked(context: Context, pkg: String, blocked: Boolean): String? {
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return "device admin is not enabled"
        return setExtraBlocked(context, pkg, blocked)
    }

    








    fun refreshBlockedState(context: Context) {
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return
        for (pkg in extraBlocked(context)) {
            if (pkg == context.packageName) continue
            val res = AppBlocker.block(context, pkg)
            if (res.level == AppBlocker.Level.NONE) Log.w(TAG, "re-block ${res.describe()}")
        }
    }

    
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

    








    private fun releaseKey(context: Context, key: String) {
        AppBlocker.unblockKey(context, key)
    }

    
    private fun clearBlocked(context: Context): String? {
        var failure: String? = null
        for (key in APP_LOCK_KEYS) {
            AppBlocker.unblockKey(context, key)
        }
        for (pkg in extraBlocked(context)) {
            if (pkg == context.packageName) continue
            
            
            
            failure = failure ?: AppBlocker.unblock(context, pkg).reason
        }
        return failure
    }

    private fun clearAll(context: Context, dpm: DevicePolicyManager, admin: ComponentName, isOwner: Boolean) {
        clearBlocked(context)
        try {
            
            
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

    
    fun supportedKeys(): List<String> = PolicyKeys.ALL
}
