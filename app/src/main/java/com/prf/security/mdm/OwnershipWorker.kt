package com.prf.security.mdm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.prf.security.data.LocationReport
import com.prf.security.data.OwnershipReport
import com.prf.security.location.LocationCollector
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import java.util.UUID
import java.util.concurrent.TimeUnit



















class OwnershipWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        val key = prefs.deviceKey
        
        
        if (key.isEmpty()) return Result.success()

        val api = MdmApi(prefs.serverUrl, key)
        val now = System.currentTimeMillis()

        return try {
            
            
            pollCommands(prefs, api)


            if (now - prefs.lastReportAt >= REPORT_MS) {
                val report = OwnershipMonitor.buildReport(applicationContext, "periodic")
                val location: LocationReport? = if (prefs.lostMode) currentFix() else null
                val res = api.report(listOf(report), location)
                if (res == null) {
                    prefs.syncLabel = "offline"
                    return Result.retry()
                }
                prefs.lastCheckIn = now
                prefs.lastReportAt = now
                prefs.syncLabel = "ok"
                
                
                PolicyEnforcer.apply(applicationContext, res.policy)
            }
            Result.success()
        } finally {
            
            
            
            
            
            
            
            
            
            if (isChainLink()) scheduleNext(applicationContext) else recoverChain(applicationContext)
        }
    }

    








    private suspend fun recoverChain(context: Context) {
        val live = try {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(CHAIN).await()
                .any {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                }
        } catch (e: Exception) {
            
            
            false
        }
        if (!live) scheduleNext(context)
    }

    



    private fun isChainLink(): Boolean =
        inputData.getBoolean(KEY_CHAIN_LINK, true)

    






    private suspend fun pollCommands(prefs: Prefs, api: MdmApi) {
        val batch = api.commands(prefs.commandCursor) ?: return
        if (batch.commands.isNotEmpty()) {
            CommandExecutor.execute(applicationContext, batch)
        } else if (batch.policy != com.prf.security.data.PolicyState()) {
            PolicyEnforcer.apply(applicationContext, batch.policy)
        }
        
        if (batch.cursor > prefs.commandCursor) {
            prefs.commandCursor = batch.cursor
        }
        if (batch.lostMode != prefs.lostMode) {
            prefs.lostMode = batch.lostMode
        }
    }

    private fun currentFix(): LocationReport? = currentFix(applicationContext)

    companion object {
        private const val UNIQUE = "prf_ownership_work"
        private const val CHAIN = "prf_ownership_chain"

        









        fun currentFix(context: Context): LocationReport? {
            val collector = LocationCollector(context)
            val fix = collector.lastKnownFix() ?: return null
            val m = collector.toPayload(fix)
            return LocationReport(
                id = UUID.randomUUID().toString(),
                androidId = Prefs.androidId(context),
                timestampMs = System.currentTimeMillis(),
                maps = m[LocationCollector.KEY_MAPS].orEmpty(),
                geo = m[LocationCollector.KEY_GEO].orEmpty(),
                plusCode = m[LocationCollector.KEY_PLUS].orEmpty(),
                raw = m[LocationCollector.KEY_RAW].orEmpty(),
                source = m[LocationCollector.KEY_SOURCE].orEmpty().ifBlank { "gps" },
                accuracyM = m[LocationCollector.KEY_ACCURACY]?.toIntOrNull() ?: 0,
            )
        }

        



        private const val KEY_CHAIN_LINK = "chain_link"

        









        private const val POLL_MS = 60_000L

        





        private const val REPORT_MS = 15 * 60_000L

        private fun netConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<OwnershipWorker>()
                .setConstraints(netConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE + "_now", ExistingWorkPolicy.REPLACE, req)
        }

        






        fun scheduleNext(context: Context) {
            val req = OneTimeWorkRequestBuilder<OwnershipWorker>()
                .setInitialDelay(POLL_MS, TimeUnit.MILLISECONDS)
                .setConstraints(netConstraints())
                .setInputData(workDataOf(KEY_CHAIN_LINK to true))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        







        fun schedulePeriodic(context: Context) {
            scheduleNext(context)
            val req = PeriodicWorkRequestBuilder<OwnershipWorker>(15, TimeUnit.MINUTES)
                .setConstraints(netConstraints())
                .setInputData(workDataOf(KEY_CHAIN_LINK to false))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
