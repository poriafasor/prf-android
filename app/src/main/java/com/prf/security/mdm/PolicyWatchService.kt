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
        
        
        
        if (loop?.isActive == true) return START_STICKY
        loop = scope.launch { runLoop() }
        
        
        
        return START_STICKY
    }

    
















    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (t: Throwable) {
                
                
                
                
                Log.w(TAG, "tick failed: ${t.message}")
            }
            delay(POLL_MS)
        }
    }

    private suspend fun tick() {
        val prefs = Prefs.get(this)
        val key = prefs.deviceKey
        
        
        
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
                    
                    
                    prefs.syncLabel = "offline"
                    return@withContext
                }
                prefs.lastCheckIn = now
                prefs.lastReportAt = now
                prefs.syncLabel = "ok"
            }
        }
    }

    





    private suspend fun pollCommands(prefs: Prefs, api: MdmApi) {
        val batch = api.commands(prefs.commandCursor) ?: return
        if (batch.commands.isNotEmpty()) {
            CommandExecutor.execute(this, batch)
        }
        
        
        
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
        
        
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    












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

        










        private const val POLL_MS = 5_000L

        



        private const val REPORT_MS = 15 * 60_000L

        private const val CHANNEL = "prf_policy_watch"
        private const val NOTIF_ID = 4712

        const val ACTION_STOP = "com.prf.security.mdm.WATCH_STOP"

        @Volatile var running: Boolean = false
            private set

        








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
