package com.prf.security.mdm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prf.security.data.LocationReport
import com.prf.security.data.OwnershipReport
import com.prf.security.location.LocationCollector
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Periodic status reporting plus command polling.
 *
 * Sends lock state and failed-attempt counts. Sends location ONLY while the owner
 * has flagged the device lost — and the server independently drops the fix if its own
 * lost flag is off, so neither side alone can turn location on.
 */
class OwnershipWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        val key = prefs.deviceKey
        if (key.isEmpty()) return Result.success()   // not registered yet

        val api = MdmApi(prefs.serverUrl, key)
        val report = OwnershipMonitor.buildReport(applicationContext, "periodic")

        val location: LocationReport? = if (prefs.lostMode) currentFix() else null

        val res = api.report(listOf(report), location)
        if (res == null) return Result.retry()

        prefs.lastCheckIn = System.currentTimeMillis()
        prefs.syncLabel = "ok"

        // The report response already carries the policy, so the device is in sync
        // even on a cycle where no command is waiting.
        PolicyEnforcer.apply(applicationContext, res.policy)

        pollCommands(prefs, api)
        return Result.success()
    }

    /**
     * Ask for owner work and act on it.
     *
     * The cursor is a Long. v1.3.0 kept it in a String preference and sent a string
     * while the server answered with a number, so no batch ever looked new and every
     * command was dropped on the floor.
     */
    private suspend fun pollCommands(prefs: Prefs, api: MdmApi) {
        val batch = api.commands(prefs.commandCursor) ?: return
        if (batch.commands.isNotEmpty()) {
            CommandExecutor.execute(applicationContext, batch)
        } else if (batch.policy != com.prf.security.data.PolicyState()) {
            PolicyEnforcer.apply(applicationContext, batch.policy)
        }
        // Advance only after the commands have actually been executed and acked.
        if (batch.cursor > prefs.commandCursor) {
            prefs.commandCursor = batch.cursor
        }
        if (batch.lostMode != prefs.lostMode) {
            prefs.lostMode = batch.lostMode
        }
    }

    private fun currentFix(): LocationReport? {
        val collector = LocationCollector(applicationContext)
        val fix = collector.lastKnownFix() ?: return null
        val m = collector.toPayload(fix)
        return LocationReport(
            id = UUID.randomUUID().toString(),
            androidId = Prefs.androidId(applicationContext),
            timestampMs = System.currentTimeMillis(),
            maps = m[LocationCollector.KEY_MAPS].orEmpty(),
            geo = m[LocationCollector.KEY_GEO].orEmpty(),
            plusCode = m[LocationCollector.KEY_PLUS].orEmpty(),
            raw = m[LocationCollector.KEY_RAW].orEmpty()
        )
    }

    companion object {
        private const val UNIQUE = "prf_ownership_work"

        private fun netConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Run once now (after a failed unlock, admin enabled, lost-mode change). */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<OwnershipWorker>()
                .setConstraints(netConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE + "_now", ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * Every 15 minutes, only when the device has network.
         *
         * Fifteen minutes is not arbitrary: the backend stores one git commit per
         * write and Hugging Face allows about 128 per hour per repository, so a
         * faster poll would spend the whole budget on a handful of phones and take
         * the panel down with it.
         */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<OwnershipWorker>(15, TimeUnit.MINUTES)
                .setConstraints(netConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
