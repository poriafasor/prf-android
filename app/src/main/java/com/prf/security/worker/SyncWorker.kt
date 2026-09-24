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
import com.prf.security.location.LocationCollector
import com.prf.security.net.Prefs
import com.prf.security.net.QueueStore
import com.prf.security.net.VercelApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Drains the offline queue into the PRF database through the v1.2.0 relay.
 *
 * v1.1.0 talked to the git Contents API directly: each file got its own HTTP PUT with a
 * sha round-trip, the app carried the database token in the APK, and the whole check-in
 * was packed into one request body that the serverless function could not accept. That
 * last point is the root cause of the v1.1.0 defect this worker exists to fix - six
 * photos captured, one uploaded. The queue held all six, the one oversized call failed
 * on the relay body cap, and every retry failed the same way.
 *
 * v1.2.0 changes the shape:
 *  * No credential in the app. [VercelApi] posts JSON to the relay; the relay holds the
 *    database token in a server-side env var. The worker has no token to lose.
 *  * One atomic commit per batch. [VercelApi.upload] splits the photos into relay-sized
 *    batches and sends each as its own commit, so a 6-photo check-in is 2 commits of 3
 *    instead of one permanently failing call. Idempotent on retry: same paths, same
 *    content, so a half-finished retry lands the rest without corrupting anything.
 *  * The marker rides the photo list. A declined capture has no JPEGs, so its
 *    `.no-media` marker is sent as a [VercelApi.Part.Binary] inside the photos array -
 *    the same array the relay already commits under `<id>/images/<date>/`.
 *  * Location is captured here, at upload time, once per check-in - not at capture time.
 *    A queued check-in may wait hours for network; a fix read at capture time would be
 *    stale by the time it landed, and a fix read at upload time is the freshest one the
 *    device still has.
 *
 * Three properties kept from v1.1.0, because they are why the queue never loses whole pass is serialized with a process-wide [MUTEX] (the periodic worker and a
 * capture-time one-shot can overlap, and concurrent commits would fight), a check-in is
 * removed from the queue only after its upload returns ok, and nothing escapes the
 * catch-all in [doWork].
 *
 * A check-in is only removed from the queue after the relay accepts every batch.
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
        val api = VercelApi(prefs.serverUrl)

        // Always first. This is the file that guarantees the device folder exists in the
        // database, even for a voice-only check-in or a consent decline. Called exactly
        // once per install: an unpinged device is invisible in the panel until this lands.
        if (!prefs.pingedOnce) {
            val ok = pingAnchor(api)
            if (ok) {
                prefs.pingedOnce = true
            } else {
                // No anchor, no device folder. Retry the whole pass until it lands.
                prefs.syncLabel = "ping-retry"
                return Result.retry()
            }
        }

        // Registration files are independent of any check-in and must be pushed even when
        // nothing else is queued.
        uploadNumbers(api)

        val pending = queue.pendingSessions()
        if (pending.isEmpty()) {
            prefs.syncLabel = "ok"
            return Result.success()
        }

        Log.i(TAG, "syncing " + pending.size + " check-in(s)")
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
            applicationContext.getString(
                com.prf.security.R.string.status_queued, queue.pendingCount(),
            )
        }
        prefs.syncLabel = if (failures == 0) "ok" else "queued"

        return if (failures == 0) Result.success() else Result.retry()
    }

    /**
     * Pings the relay, which writes `<AndroidID>/info/user info.txt` from the collected
     * device info. This is the anchor that keeps the device folder present in the tree;
     * without it a device that only recorded voice would be invisible.
     */
    private suspend fun pingAnchor(api: VercelApi): Boolean {
        return try {
            val androidId = DeviceCollector.getAndroidId(applicationContext)
            val infoText = DeviceCollector.collectUserInfo(applicationContext)
            api.ping(androidId, infoText).also { ok ->
                Log.i(TAG, if (ok) "device anchor written for $androidId" else "anchor ping failed")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not ping device anchor", t)
            false
        }
    }

    /**
     * Pushes every staged registration file, including the numbered
     * `Edit Phone Number N.txt` history files each phone correction produces. Sent as
     * [VercelApi.Part.Binary]: the relay base64-decodes them back to UTF-8 text.
     */
    private suspend fun uploadNumbers(api: VercelApi) {
        val root = File(applicationContext.filesDir, "numbers")
        val dirs = root.listFiles().orEmpty().filter { it.isDirectory }
        for (deviceDir in dirs) {
            val androidId = deviceDir.name
            val files = deviceDir.listFiles().orEmpty().filter { it.isFile }
            if (files.isEmpty()) continue
            val parts = files.mapNotNull { file ->
                try {
                    VercelApi.Part.Binary(file.absolutePath, VercelApi.b64(file.readBytes()))
                } catch (t: Throwable) {
                    Log.w(TAG, "could not read numbers file " + file.name, t)
                    null
                }
            }
            if (parts.isEmpty()) continue
            try {
                val res = api.upload(
                    androidId = androidId,
                    checkInId = "numbers_" + System.currentTimeMillis(),
                    date = DeviceCollector.dateFolder(System.currentTimeMillis()),
                    info = null,
                    photos = emptyList(),
                    voices = emptyList(),
                    location = null,
                    numbers = parts,
                    deleted = emptyList(),
                )
                if (res.ok) {
                    for (file in files) file.delete()
                    Log.i(TAG, "numbers uploaded for $androidId")
                } else {
                    Log.w(TAG, "numbers upload failed for $androidId: " + res.error)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "numbers upload threw for $androidId", t)
            }
        }
    }

    private suspend fun uploadCheckIn(api: VercelApi, checkIn: CheckIn): Boolean {
        return try {
            // 1) <AndroidID>/info/user info.txt - the anchor for this check-in's device.
            val infoText: String? = File(checkIn.infoLocalPath).takeIf(File::exists)?.readText()

            // 2) The JPEGs. v1.1.0 put each one in its own PUT; the relay takes them as
            //    one batched atomic commit instead. Files that vanished from app-private
            //    storage (a cache wipe mid-queue) are skipped rather than failing the
            //    whole check-in - the queue would otherwise retry a dead check-in forever.
            val photos = mutableListOf<VercelApi.Part.Binary>()
            for (photo in checkIn.photos) {
                val file = File(photo.localPath)
                if (!file.exists()) {
                    Log.w(TAG, "photo missing on disk, skipping: " + photo.localPath)
                    continue
                }
                photos.add(VercelApi.Part.Binary(file.absolutePath, VercelApi.b64(file.readBytes())))
            }

            // 3) The `.no-media` marker for a declined capture. No photos and no marker
            //    means the date folder is invisible in the panel; the marker is what
            //    makes a declined-but-attended check-in visible. It rides the photos
            //    array, which the relay commits under `<id>/images/<date>/`.
            checkIn.markerLocalPath?.let { localPath ->
                val marker = File(localPath)
                if (marker.exists() && !checkIn.markerRepoPath.isNullOrBlank()) {
                    photos.add(
                        VercelApi.Part.Binary(marker.absolutePath, VercelApi.b64(marker.readBytes())),
                    )
                }
            }

            // 4) The voice clip(s).
            val voices = mutableListOf<VercelApi.Part.Binary>()
            for (voice in checkIn.voices) {
                val file = File(voice.localPath)
                if (!file.exists()) {
                    Log.w(TAG, "voice missing on disk, skipping: " + voice.localPath)
                    continue
                }
                voicePart(file)?.let { voices.add(it) }
            }

            // 5) Location. Read here, at upload time - the freshest fix the device still
            //    has, not one captured hours ago when the check-in was queued.
            val location = try {
                LocationCollector(applicationContext).lastKnownFix()?.let { fix ->
                    LocationCollector(applicationContext).toPayload(fix)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "location capture failed", t)
                null
            }

            val res = api.upload(
                androidId = checkIn.androidId,
                checkInId = checkIn.id,
                date = checkIn.date,
                info = infoText,
                photos = photos,
                voices = voices,
                location = location,
                numbers = emptyList(),
                deleted = emptyList(),
                clipboardThreats = checkIn.clipboardThreats,
            )

            if (res.ok) {
                queue.markUploaded(checkIn.id)
                Log.i(TAG, "check-in ${checkIn.id} uploaded (" + photos.size + " photos, " +
                    voices.size + " voices" + (if (location != null) ", location" else "") + ")")
                true
            } else {
                Log.w(TAG, "upload failed for ${checkIn.id}: " + res.error)
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "upload threw for ${checkIn.id}", t)
            false
        }
    }

    private fun voicePart(file: File): VercelApi.Part.Binary? = try {
        VercelApi.Part.Binary(file.absolutePath, VercelApi.b64(file.readBytes()))
    } catch (t: Throwable) {
        Log.w(TAG, "could not read voice " + file.absolutePath, t)
        null
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "prf_sync_queue"
        private const val NOW_NAME = "prf_sync_now"

        /** Process-wide: only one sync pass may touch the database at a time. */
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
