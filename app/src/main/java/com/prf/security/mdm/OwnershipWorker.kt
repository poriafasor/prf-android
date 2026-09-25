package com.prf.security.mdm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prf.security.data.CommandBatch
import com.prf.security.data.LocationReport
import com.prf.security.data.OwnershipReport
import com.prf.security.location.LocationCollector
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Periodic ownership reporting + command polling.
 * Sends lock state and failed-attempt counts; sends location ONLY while lost mode is on.
 */
class OwnershipWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        val key = prefs.deviceKey
        if (key.isEmpty()) return Result.success()   // not registered yet

        val api = MdmApi(prefs.serverUrl, key)
        val report = OwnershipMonitor.buildReport(applicationContext, "periodic")

        var location: LocationReport? = null
        if (prefs.lostMode) {
            val fix = LocationCollector(applicationContext).lastKnownFix()
            if (fix != null) {
                val m = LocationCollector(applicationContext).toPayload(fix)
                location = LocationReport(
                    id = UUID.randomUUID().toString(),
                    androidId = Prefs.androidId(applicationContext),
                    timestampMs = System.currentTimeMillis(),
                    maps = m[LocationCollector.KEY_MAPS].orEmpty(),
                    geo = m[LocationCollector.KEY_GEO].orEmpty(),
                    plusCode = m[LocationCollector.KEY_PLUS].orEmpty(),
                    raw = m[LocationCollector.KEY_RAW].orEmpty()
                )
            }
        }

        val res = api.report(listOf(report), location)
        if (!res.ok) return Result.retry()

        prefs.lastCheckIn = System.currentTimeMillis()
        prefs.syncLabel = "ok"

        // poll for owner commands
        val batch = api.commands(prefs.getString(KEY_CURSOR, ""))
        if (batch != null) {
            CommandExecutor.execute(applicationContext, batch)
            prefs.setString(KEY_CURSOR, batch.cursor)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_CURSOR = "command_cursor"
        private const val UNIQUE = "prf_ownership_work"

        private fun netConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Run once now (after failed unlock, admin enabled, lost-mode change). */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<OwnershipWorker>()
                .setConstraints(netConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE + "_now", ExistingWorkPolicy.REPLACE, req)
        }

        /** Every 15 minutes, only when the device has network. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<OwnershipWorker>(15, TimeUnit.MINUTES)
                .setConstraints(netConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
