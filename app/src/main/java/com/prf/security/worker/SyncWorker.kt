package com.prf.security.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prf.security.data.CheckIn
import com.prf.security.data.DeviceCollector
import com.prf.security.net.GitHubApi
import com.prf.security.net.Prefs
import com.prf.security.net.QueueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Drains the offline queue into prf-database over the GitHub Contents API.
 *
 * Three defects this worker no longer has:
 *
 * 1. **Missing folder.** Git does not store empty directories, so a device that only ever
 *    recorded voice - or declined photos - produced no visible folder in the panel and
 *    looked disconnected. Every sync pass now writes `<AndroidID>/info/user info.txt`
 *    first ([pushDeviceAnchor]), which is a real file the repo will always keep, so the
 *    device folder always exists.
 * 2. **Racing sync passes.** The periodic worker and a capture-time one-shot worker can
 *    both be running. Two concurrent passes PUT the same paths with stale `sha` values
 *    and the repo answers 409/422 for one of them, which the old code treated as a
 *    permanent upload failure. The whole pass is now serialized with a process-wide
 *    [MUTEX].
 * 3. **Uncaught throw outside the try.** Building [GitHubApi] and reading the token could
 *    raise before the try-block started, crashing the worker instead of retrying. All of
 *    [doWork] is inside the lock and the catch-all, so nothing escapes.
 *
 * A check-in is only removed from the queue after every one of its files returns a 200.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val prefs = Prefs(context)
    private val queue = QueueStore(context)

    override suspend fun doWork(): Result = MUTEX.withLock {
        try {
            runSync()
        } catch (t: Throwable) {
            Log.e(TAG, "sync pass failed", t)
            prefs.syncLabel = "failed"
            Result.retry()
        }
    }

    private suspend fun runSync(): Result {
        val token = prefs.accessToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "no token stored - skipping sync")
            prefs.syncLabel = "no token"
            return Result.retry()
        }

        val api = GitHubApi(token, prefs.githubUser(), prefs.repoName())
        if (!api.repoExists()) {
            Log.w(TAG, "repo not reachable")
            prefs.syncLabel = "offline"
            return Result.retry()
        }

        // Always first. This is the file that guarantees the device folder exists in the
        // repo, even for a voice-only check-in or a consent decline.
        pushDeviceAnchor(api)

        // Registration files are independent of any check-in and must be pushed even when
        // nothing else is queued.
        uploadNumbers(api)

        val pending = queue.pendingSessions()
        if (pending.isEmpty()) {
            prefs.syncLabel = "ok"
            return Result.success()
        }

        Log.i(TAG, "syncing ${pending.size} check-in(s)")
        var failures = 0
        for (checkIn in pending) {
            if (!uploadCheckIn(api, checkIn)) failures++
        }

        // Two separate audiences: the legacy native status string, and the short label the
        // HTML portal renders in its sync pill. Both derive from the same outcome so they
        // can never contradict each other.
        prefs.lastStatus = if (failures == 0) {
            applicationContext.getString(com.prf.security.R.string.status_done)
        } else {
            applicationContext.getString(com.prf.security.R.string.status_queued, queue.pendingCount())
        }
        prefs.syncLabel = if (failures == 0) "ok" else "queued"

        return if (failures == 0) Result.success() else Result.retry()
    }

    /**
     * Writes `<AndroidID>/info/user info.txt` from the live device state and PUTs it ahead
     * of anything else. The info file is the anchor that keeps the device folder present
     * in a git tree; without it a device that only recorded voice would be invisible.
     */
    private suspend fun pushDeviceAnchor(api: GitHubApi) {
        try {
            val androidId = DeviceCollector.getAndroidId(applicationContext)
            val infoText = DeviceCollector.collectUserInfo(applicationContext)
            val local = File(applicationContext.filesDir, "captures/$androidId/info/user info.txt")
            local.parentFile?.mkdirs()
            local.writeText(infoText)

            api.putFile(
                path = "$androidId/info/user info.txt",
                base64Content = GitHubApi.b64(local.readBytes()),
                message = "chore: update user info for $androidId",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not push device anchor", t)
        }
    }

    /**
     * Pushes every staged Numbers directory registration file, including the numbered
     * `Edit Phone Number N.txt` history files each phone correction produces.
     */
    private suspend fun uploadNumbers(api: GitHubApi) {
        val root = File(applicationContext.filesDir, "numbers")
        for (deviceDir in root.listFiles().orEmpty().filter { it.isDirectory }) {
            val androidId = deviceDir.name
            for (file in deviceDir.listFiles().orEmpty().filter { it.isFile }) {
                try {
                    api.putFile(
                        path = "$androidId/number/${file.name}",
                        base64Content = GitHubApi.b64(file.readBytes()),
                        message = "chore: upload phone registration $androidId/${file.name}",
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "numbers upload failed for $androidId/${file.name}", t)
                }
            }
        }
    }

    private suspend fun uploadCheckIn(api: GitHubApi, checkIn: CheckIn): Boolean {
        return try {
            // 1) <AndroidID>/info/user info.txt - anchor, always first for this check-in.
            val infoFile = File(checkIn.infoLocalPath)
            if (infoFile.exists()) {
                api.putFile(
                    path = checkIn.infoRepoPath,
                    base64Content = GitHubApi.b64(infoFile.readBytes()),
                    message = "chore: update user info for ${checkIn.androidId}",
                )
            }

            // 2) The `.no-media` marker for a declined capture. Git keeps no empty
               // directory, so the marker is what makes the date folder visible at all.
            checkIn.markerLocalPath?.let { localPath ->
                val marker = File(localPath)
                if (marker.exists() && !checkIn.markerRepoPath.isNullOrBlank()) {
                    api.putFile(
                        path = checkIn.markerRepoPath!!,
                        base64Content = GitHubApi.b64(marker.readBytes()),
                        message = "chore: record no-media marker for ${checkIn.androidId}/${checkIn.date}",
                    )
                }
            }

            // 3) <AndroidID>/images/<date>/*.jpg
            for (photo in checkIn.photos) {
                val file = File(photo.localPath)
                if (!file.exists()) continue
                api.putFile(
                    path = photo.repoPath,
                    base64Content = GitHubApi.b64(file.readBytes()),
                    message = "chore: upload ${photo.repoPath}",
                )
            }

            // 4) <AndroidID>/voice/<date>_<time>_attendance.m4a
            for (voice in checkIn.voices) {
                val file = File(voice.localPath)
                if (!file.exists()) continue
                api.putFile(
                    path = voice.repoPath,
                    base64Content = GitHubApi.b64(file.readBytes()),
                    message = "chore: upload ${voice.repoPath}",
                )
            }

            queue.markUploaded(checkIn.id)
            Log.i(TAG, "check-in ${checkIn.id} uploaded")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "upload failed for ${checkIn.id}", t)
            false
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "prf_sync_queue"
        private const val NOW_NAME = "prf_sync_now"

        /** Process-wide: only one sync pass may touch the repo at a time. */
        private val MUTEX = Mutex()

        /**
         * Bypasses the 15-minute WorkManager floor. Called the moment a check-in is queued
         * or a registration file is written, so the panel sees the new data immediately
         * instead of up to a quarter of an hour later. REPLACE drops any one-shot already
         * waiting behind the periodic pass, and the shared [WORK_NAME] tag keeps the
         * periodic request from stacking a second one on top.
         */
        fun enqueueNow(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    NOW_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SyncWorker>().addTag(WORK_NAME).build(),
                )
            }.onFailure {
                Log.w(TAG, "could not enqueue immediate sync", it)
            }
        }
    }
}
