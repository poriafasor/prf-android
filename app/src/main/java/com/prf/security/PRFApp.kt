package com.prf.security

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.prf.security.worker.SyncWorker
import java.util.concurrent.TimeUnit

/**
 * Bootstraps WorkManager on-demand and schedules the queue-draining sync. The manifest
 * disables the default initializer so this class owns the WorkManager configuration.
 */
class PRFApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleSync()
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

    private fun scheduleSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .addTag(SyncWorker.WORK_NAME)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
