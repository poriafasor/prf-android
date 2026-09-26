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
import com.prf.security.screen.ScreenShareService
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Command polling and periodic status reporting, on two different clocks.
 *
 * These used to be one thing on one clock, and that is why pressing "lock" in the
 * panel appeared to do nothing: the only schedule was a 15-minute periodic run, so
 * a queued command was not even looked at for up to a quarter of an hour.
 *
 * The two halves cost very different amounts. Sending a report is a write and
 * costs the server a git commit against a budget of about 128 an hour. Asking for
 * commands is a read, and the server writes nothing for a poll that finds an
 * empty queue — it deliberately does not even refresh `lastSeen` more than once
 * every ten minutes. So the cheap half can run every minute and the expensive
 * half every fifteen, and the budget is unaffected.
 *
 * Sends lock state and failed-attempt counts. Sends location ONLY while the owner
 * has flagged the device lost — and the server independently drops the fix if its own
 * lost flag is off, so neither side alone can turn location on.
 */
class OwnershipWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        val key = prefs.deviceKey
        // Not registered yet. `finally` still re-arms the chain, so the device
        // starts checking on its own the moment registration completes.
        if (key.isEmpty()) return Result.success()

        val api = MdmApi(prefs.serverUrl, key)
        val now = System.currentTimeMillis()

        return try {
            // Commands first, and unconditionally. This is the part the owner is
            // actually waiting on, and it is the part that costs nothing.
            pollCommands(prefs, api)

            uploadScreenFrame(prefs, api, now)

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
                // The report response already carries the policy, so the device is
                // in sync even on a cycle where no command is waiting.
                PolicyEnforcer.apply(applicationContext, res.policy)
            }
            Result.success()
        } finally {
            // Re-arm in `finally` so the chain survives a retry, a network failure
            // or an early return; a process killed mid-run is covered by
            // WorkManager re-running the unfinished work.
            //
            // Which re-arm depends on what this run was. A chain link always
            // extends the chain. The recovery run must NOT: it fires every fifteen
            // minutes regardless, so extending unconditionally would append a link
            // on top of a chain that is already healthy and the poll rate would
            // creep upward without bound.
            if (isChainLink()) scheduleNext(applicationContext) else recoverChain(applicationContext)
        }
    }

    /**
     * Put the command-poll chain back if it has stopped.
     *
     * The chain is self-sustaining, so the only way to find it dead here is a
     * chain that was cleared — an uninstall, a "clear data", or an OEM scheduler
     * that drops pending work. Without this, such a device would look healthy in
     * the panel and silently ignore every command forever, with nothing to
     * restart it but opening the app.
     */
    private suspend fun recoverChain(context: Context) {
        val live = try {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(CHAIN).await()
                .any {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                }
        } catch (e: Exception) {
            // If the query itself fails, re-arming is the safe answer: an extra
            // poll costs a read, while a missing one costs remote control.
            false
        }
        if (!live) scheduleNext(context)
    }

    /**
     * Whether this run is a link in the command-poll chain, or the separate
     * low-frequency recovery schedule.
     */
    private fun isChainLink(): Boolean =
        inputData.getBoolean(KEY_CHAIN_LINK, true)

    /**
     * Send the current screen frame, if the user is sharing and one is due.
     *
     * Every accepted frame is a write on the server and costs a git commit, so
     * this is throttled hard: one frame every [SCREEN_MS] at most, and only when
     * the frame has actually changed since the last upload. A user leaving
     * sharing on all day would otherwise spend the whole commit budget — and
     * with it the attendance writes and the command queue — on frames.
     *
     * When sharing is off, `currentFrame` returns null and there is nothing to
     * send, so the user not sharing costs nothing and leaves no trace.
     */
    private suspend fun uploadScreenFrame(prefs: Prefs, api: MdmApi, now: Long) {
        if (!ScreenShareService.running) return
        if (now - prefs.lastScreenAt < SCREEN_MS) return

        val (frame, capturedAt) = ScreenShareService.currentFrame() ?: return
        // The screen has not moved since the last frame we sent. Sending it again
        // would cost a commit to tell the panel nothing it did not already know.
        // Compared by digest: the frame itself is far too big to keep in prefs.
        val digest = Integer.toHexString(frame.hashCode())
        if (digest == prefs.lastScreenDigest) return

        if (api.uploadScreen(frame, capturedAt)) {
            prefs.lastScreenAt = now
            prefs.lastScreenDigest = digest
        }
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
        private const val CHAIN = "prf_ownership_chain"

        /**
         * Marks a run as a link in the command-poll chain rather than the slow
         * recovery schedule. Only chain links re-arm the chain.
         */
        private const val KEY_CHAIN_LINK = "chain_link"

        /**
         * How often the device asks for commands.
         *
         * A read on the server, so it is affordable far more often than a report.
         * WorkManager will not run a periodic request more often than 15 minutes
         * even when asked to, which is why this is a self-re-arming one-time chain
         * instead: `doWork` enqueues the next run in its `finally`. The platform
         * still applies its own batching and doze deferral, so a device that is
         * idle may see this stretch out — a live device sees it about every minute.
         */
        private const val POLL_MS = 60_000L

        /**
         * How often the expensive ownership report is sent.
         *
         * This is the half that costs a git commit, so it keeps the 15-minute
         * rhythm the old schedule was chosen for.
         */
        private const val REPORT_MS = 15 * 60_000L

        /**
         * The shortest gap between two screen frames.
         *
         * A frame is a write, so this is a commit each time. Two minutes keeps a
         * shared screen looking live while leaving the budget to the things that
         * are not continuous.
         */
        private const val SCREEN_MS = 2 * 60_000L

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
         * Arm the next link of the command-poll chain.
         *
         * APPEND_OR_REPLACE is what makes re-arming safe to call from `doWork`
         * itself: each link appends exactly one successor, so the chain stays one
         * link long instead of forking a new poll per run.
         */
        fun scheduleNext(context: Context) {
            val req = OneTimeWorkRequestBuilder<OwnershipWorker>()
                .setInitialDelay(POLL_MS, TimeUnit.MILLISECONDS)
                .setConstraints(netConstraints())
                .setInputData(workDataOf(KEY_CHAIN_LINK to true))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /**
         * Start the command-poll chain, plus a slow recovery schedule.
         *
         * The recovery run is the belt to the chain's braces. It carries
         * `KEY_CHAIN_LINK = false` so it does not extend the chain itself, and it
         * only appends a link when there is genuinely nothing scheduled — so a
         * healthy chain is left alone and a dropped one is picked back up.
         */
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
