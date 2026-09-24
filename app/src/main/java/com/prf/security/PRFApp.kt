package com.prf.security

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.prf.security.worker.SyncWorker
import java.util.concurrent.TimeUnit

/**
 * Bootstraps WorkManager on-demand and schedules the queue-draining sync. The manifest
 * disables the default initializer so this class owns the WorkManager configuration.
 *
 * v1.2.0 removes the credential-seeding step entirely. v1.1.0 had CI inject the
 * private-database token through a build secret, base64-wrap it into BuildConfig, and
 * decode it on first launch into the encrypted store - which meant every shipped APK
 * carried a database credential in its bytecode. The app no longer speaks to the database
 * at all: it posts JSON to the relay, and the relay holds the token server-side. There is
 * nothing left to seed, so this class is now only the notification channel and the sync
 * schedule.
 */
class PRFApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleSync()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            clipboardScope()
            val channel = NotificationChannel(
                getString(R.string.notif_channel_id),
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * No-op placeholder retained for source stability across the v1.2.0 credential removal.
     * The clipboard observer lives in [com.prf.security.portal.PortalActivity], where it can
     * show a warning dialog over an active activity rather than fire from a headless context.
     */
    private fun clipboardScope() = Unit

    private fun scheduleSync() {
        // Only ever run on a live connection: a sync pass with no network is guaranteed
        // failure, and WorkManager would otherwise burn retries waiting for one.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // UPDATE, not KEEP: a new app version may carry a new worker contract, and KEEP
        // would keep the previously scheduled request as-is instead of this one.
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(SyncWorker.WORK_NAME)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            syncWorkName(), ExistingPeriodicWorkPolicy.UPDATE, request
        )

        // Drain whatever the previous version queued, now, under the new worker.
        SyncWorker.enqueueNow(this)
    }

    /** Work name indirection so a future rename does not require a manifest edit. */
    private fun syncWorkName(): String = SyncWorker.WORK_NAME

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    companion object {
        private const val TAG = "PRFApp"
    }
}
