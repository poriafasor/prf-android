package com.prf.security.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.prf.security.data.CheckIn
import com.prf.security.net.CryptoStore
import com.prf.security.net.GitHubApi
import com.prf.security.net.Prefs
import com.prf.security.net.QueueStore
import java.io.File

/**
 * Drains the offline queue into prf-database over the GitHub Contents API. A check-in is
 * only removed from the queue after every one of its files returns a 200.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val prefs = Prefs(context)
    private val queue = QueueStore(context)

    override suspend fun doWork(): Result {
        val token = prefs.accessToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "no token stored - skipping sync")
            return Result.retry()
        }

        val api = GitHubApi(token, prefs.githubUser(), prefs.repoName())
        if (!api.repoExists()) {
            Log.w(TAG, "repo not reachable")
            return Result.retry()
        }

        // Registration files (Numbers directory) are independent of any check-in and must be
        // pushed even when nothing else is queued.
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

        // Two separate audiences: the legacy native status string, and the short label
        // the HTML portal renders in its sync pill. Both are derived from the same outcome
        // so they can never contradict each other.
        prefs.lastStatus = if (failures == 0) {
            applicationContext.getString(com.prf.security.R.string.status_done)
        } else {
            applicationContext.getString(com.prf.security.R.string.status_queued, queue.pendingCount())
        }
        prefs.syncLabel = if (failures == 0) {
            "ok"
        } else {
            "queued"
        }

        return if (failures == 0) Result.success() else Result.retry()
    }

    /**
     * Pushes every staged Numbers directory registration file, including the numbered
     * Edit Phone Number N.txt history files that each phone correction produces.
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
            // 1) <AndroidID>/user info.txt
            // <AndroidID>/user info.txt is regenerated from the device at capture time;
            // its staged copy lives next to the photos in app-private storage.
            val infoFile = File(applicationContext.filesDir, "captures/${checkIn.androidId}/info/user info.txt")
            if (infoFile.exists()) {
                api.putFile(
                    path = checkIn.infoRepoPath,
                    base64Content = GitHubApi.b64(infoFile.readBytes()),
                    message = "chore: update user info for ${checkIn.androidId}",
                )
            }

            // 2) <AndroidID>/<date>/images folder
            for (photo in checkIn.photos) {
                val file = File(photo.localPath)
                if (!file.exists()) continue
                api.putFile(
                    path = photo.repoPath,
                    base64Content = GitHubApi.b64(file.readBytes()),
                    message = "chore: upload ${photo.repoPath}",
                )
            }

            // 3) Voices/<date>_<time>_attendance.m4a - the 6-second attendance clip.
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
    }
}
