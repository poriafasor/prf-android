package com.prf.security.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
























class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.action ?: return
        if (what !in HANDLED) return

        
        
        
        
        val pending = goAsync()
        val app = context.applicationContext
        try {
            
            
            
            if (PrfDeviceAdminReceiver.isAdminActive(app)) {
                PolicyEnforcer.refreshBlockedState(app)
            }
            
            
            
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

        












        private val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
