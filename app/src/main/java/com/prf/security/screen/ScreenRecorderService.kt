package com.prf.security.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File




























class ScreenRecorderService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var thread: android.os.HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (recorder != null) return START_STICKY

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code == Int.MIN_VALUE || data == null) {
            
            
            
            Log.w(TAG, "started without a consent result; nothing to record")
            finish()
            return START_NOT_STICKY
        }

        return try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mgr.getMediaProjection(code, data)
            if (p == null) {
                finish(); return START_NOT_STICKY
            }
            projection = p
            startRecording(p)
            START_NOT_STICKY
        } catch (t: Throwable) {
            Log.w(TAG, "could not start recording: ${t.message}")
            finish()
            START_NOT_STICKY
        }
    }

    private fun startRecording(p: MediaProjection) {
        val dir = File(cacheDir, "recordings").apply { mkdirs() }
        val file = File(dir, "screen-${System.currentTimeMillis()}.mp4")
        output = file

        
        
        
        
        
        
        startInForeground()

        val metrics = displaySize()
        val (w, h) = scaleFor(metrics.first, metrics.second)
        val dpi = resources.displayMetrics.densityDpi

        thread = android.os.HandlerThread("prf-rec").also { it.start() }
        val handler = android.os.Handler(thread!!.looper)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    
                    
                    
                    Log.i(TAG, "projection stopped by the system")
                    finish()
                }
            }, handler)
        }

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder = r
        r.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(BITRATE)
            setVideoFrameRate(FRAME_RATE)
            setVideoSize(w, h)
            setMaxDuration(MAX_SECONDS * 1000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        startedAt = System.currentTimeMillis()
        recording = true
    }

    








    fun finish() {
        if (!recording && recorder == null) {
            running = false
            stopSelf()
            return
        }
        val f = output
        var failed = false
        try { recorder?.stop() } catch (t: Throwable) {
            
            
            
            Log.w(TAG, "recorder stop: ${t.message}")
            failed = true
        }
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
        try { projection?.stop() } catch (_: Throwable) { }
        projection = null
        try { thread?.quitSafely() } catch (_: Throwable) { }
        thread = null
        recording = false
        running = false
        
        
        
        
        finished = if (!failed && f != null && f.exists() && f.length() > MIN_BYTES) f else null
        if (finished == null) f?.delete()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = CHANNEL_DESC
                        
                        
                        
                        
                        
                        
                        setSound(null, null)
                        enableVibration(false)
                        enableLights(false)
                        setShowBadge(false)
                    }
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(NOTIF_TITLE)
            .setContentText(NOTIF_TEXT)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    
    private fun scaleFor(w: Int, h: Int): Pair<Int, Int> {
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(w, h))
        var tw = (w * scale).toInt() and 1.inv()
        var th = (h * scale).toInt() and 1.inv()
        if (tw < 160) tw = 160
        if (th < 160) th = 160
        return tw to th
    }

    override fun onDestroy() {
        finish()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PRF.Recorder"
        private const val CHANNEL = "prf_screen_record"
        private const val CHANNEL_NAME = "ضبط صفحه"
        private const val CHANNEL_DESC = "وقتی این اعلان روی گوشی هست، برنامه در حال ضبط صفحه است."
        private const val NOTIF_ID = 4712
        
        private const val NOTIF_TITLE = "در حال ضبط صفحه"
        private const val NOTIF_TEXT = "ضبط حداکثر ۳ دقیقه. برای توقف، اینجا را لمس کنید."

        private const val MAX_EDGE = 720

        








        private const val MIN_BYTES = 20_000L

        










        const val BITRATE = 340_000
        const val FRAME_RATE = 24

        
        const val MAX_SECONDS = 180

        const val ACTION_STOP = "com.prf.security.screen.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile private var instance: ScreenRecorderService? = null

        @Volatile var recording: Boolean = false
            private set

        @Volatile var running: Boolean = false
            private set

        @Volatile var startedAt: Long = 0L
            private set

        
        @Volatile var finished: File? = null
            private set

        







        fun produced(): File? = finished?.takeIf { it.exists() && it.length() > 0 }

        fun start(context: Context, resultCode: Int, data: Intent) {
            if (!supported()) {
                Log.w(TAG, "recording needs Android 8 or newer on this build")
                return
            }
            finished = null
            running = true
            val i = Intent(context, ScreenRecorderService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenRecorderService::class.java).setAction(ACTION_STOP)
            )
        }

        






        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
