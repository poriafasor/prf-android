package com.prf.security

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.prf.security.net.CryptoStore
import com.prf.security.worker.SyncWorker
import java.util.concurrent.TimeUnit

/**
 * Bootstraps WorkManager on-demand and schedules the queue-draining sync. The manifest
 * disables the default initializer so this class owns the WorkManager configuration.
 *
 * This is also where the build-time credential lands: CI injects the private-database
 * token through the PRF_TOKEN secret, Gradle exposes it base64-wrapped as
 * BuildConfig.PRF_TOKEN_B64, and onCreate decodes it once into the encrypted store. Only
 * the decoded bytes touch the Keystore - the raw token is never written anywhere else.
 */
class PRFApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        seedBuildToken()
        createNotificationChannel()
        scheduleSync()
    }

    /**
     * Moves the CI-injected token from BuildConfig into encrypted storage, but only if the
     * user has not already set one in Settings. A manually entered token always wins, so a
     * rotated CI token never silently clobbers a credential the user owns.
     */
    private fun seedBuildToken() {
        val wrapped = try {
            BuildConfig.PRF_TOKEN_B64
        } catch (t: Throwable) {
            Log.w(TAG, "BuildConfig.PRF_TOKEN_B64 unavailable", t)
            return
        }
        if (wrapped.isBlank()) {
            Log.i(TAG, "no build-time token - sync needs manual setup in Settings")
            return
        }
        try {
            val token = String(Base64.decode(wrapped, Base64.NO_WRAP))
            if (token.isBlank()) return

            val crypto = CryptoStore(this)
            if (crypto.token.isNullOrBlank()) {
                crypto.token = token
                Log.i(TAG, "build-time token seeded into encrypted store")
            } else {
                Log.i(TAG, "existing token kept - build token not overwritten")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "could not seed build-time token", t)
        }
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
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    companion object {
        private const val TAG = "PRFApp"
    }
}
