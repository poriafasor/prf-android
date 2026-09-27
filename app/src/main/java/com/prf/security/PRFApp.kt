package com.prf.security

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.prf.security.mdm.OwnershipWorker
import com.prf.security.mdm.PolicyEnforcer
import com.prf.security.mdm.PolicyWatchService
import com.prf.security.mdm.PrfDeviceAdminReceiver
import com.prf.security.net.Prefs









class PRFApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restoreKey()
        OwnershipWorker.schedulePeriodic(this)
        
        
        
        
        
        
        if (PrfDeviceAdminReceiver.isAdminActive(this)) {
            PolicyEnforcer.refreshBlockedState(this)
        }
        PolicyWatchService.start(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.notif_channel_id),
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    
    private fun restoreKey() {
        try {
            val store = com.prf.security.net.CryptoStore(this)
            val key = store.deviceKey
            if (key.isNotEmpty()) Prefs.get(this).deviceKey = key
        } catch (t: Throwable) {
            Log.w(TAG, "key restore failed: ${t.message}")
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    companion object { private const val TAG = "PRFApp" }
}
