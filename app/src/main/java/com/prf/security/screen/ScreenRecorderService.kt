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

/**
 * Records the screen, for at most [MAX_SECONDS], and then finishes.
 *
 * The one supported way to do this on a non-rooted phone is `MediaProjection`,
 * and it is not covert: the user accepts a system dialog, the system shows its
 * own screen-recording indicator in the status bar, and on Android 14+ a
 * foreground service with a notification of its own is a hard requirement rather
 * than a choice. None of those three things can be removed by an app, and
 * pretending otherwise would be the easy lie. What *is* removed here is every
 * notification the app posted for its own sake: this service is the only one that
 * posts anything, it says what is actually happening for as long as it is
 * happening, and it is gone the moment recording stops.
 *
 * **What the old service did wrong, and what this one changes.**
 *
 * `ScreenShareService` held a virtual display open and answered a single frame
 * whenever the poll worker asked, and its notification said "اشتراک صفحه روشن
 * است" for as long as the service existed — so a notification claiming the screen
 * was shared stayed on the phone after the user had turned sharing off, and the
 * panel called a still frame a "live screen". This service has a defined end: it
 * records, it stops, it deletes its notification, and the app closes.
 *
 * The recording is written to the cache directory and nothing is uploaded from
 * here. The upload is driven from the activity, which owns the moment the person
 * is told it has been sent, and which is still on screen when the notification
 * comes down.
 */
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
            // Started without a consent result. There is nothing to project and
            // `getMediaProjection` throws without one, so this is a hard stop
            // rather than a retry.
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

        // Android 14 requires the mediaProjection foreground service to be running
        // — with its notification — before `getMediaProjection()` is called, and
        // tears the projection down if it is not. This is the platform's rule and
        // not something this app would choose; the notification is the price of
        // being allowed to record at all on that version, and its text says what
        // is actually true for exactly as long as it is true.
        startInForeground()

        val metrics = displaySize()
        val (w, h) = scaleFor(metrics.first, metrics.second)
        val dpi = resources.displayMetrics.densityDpi

        thread = android.os.HandlerThread("prf-rec").also { it.start() }
        val handler = android.os.Handler(thread!!.looper)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // The user revoked the projection from the system UI, or the
                    // projection died. Either way the recording is over and the
                    // app has to be told, because the button on it is waiting.
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

    /**
     * Stops, and takes the notification down with it.
     *
     * `stopForeground(REMOVE)` rather than merely `stopSelf`: the complaint was a
     * notification that said recording was on after it was not, and a service
     * that stops still leaves its notification until the system gets round to
     * it. This is the exact line where that was true, so this is where it is
     * removed.
     */
    fun finish() {
        if (!recording && recorder == null) {
            running = false
            stopSelf()
            return
        }
        val f = output
        var failed = false
        try { recorder?.stop() } catch (t: Throwable) {
            // `stop` throws if it is called before a single frame was written. A
            // recording that lasted four milliseconds is not a failure of the
            // phone; it is a file that must not be sent.
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
        // `stop()` did not throw, and the file is big enough to contain actual
        // frames. A file below the floor is a container the encoder wrote and
        // then abandoned, and sending it would put a video of nothing in the
        // panel with a real duration on it.
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
                        // Silent and as close to invisible as a foreground
                        // service's notification can be. The platform requires
                        // this notification to exist before `getMediaProjection`
                        // is called on Android 14; it does not require it to make
                        // a sound, vibrate, light the screen or badge the icon,
                        // and none of those is wanted here.
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

    /** H.264 needs even dimensions; an odd one pads rows and the file will not play. */
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
        // The text says what is true, and it is only on screen while it is true.
        private const val NOTIF_TITLE = "در حال ضبط صفحه"
        private const val NOTIF_TEXT = "ضبط حداکثر ۳ دقیقه. برای توقف، اینجا را لمس کنید."

        private const val MAX_EDGE = 720

        /**
         * A file smaller than this contains no frames.
         *
         * A 3-second recording at 340kbps is around 127KB, so a 20KB file is a
         * header and nothing else — the shape of a recording that was stopped
         * before the first frame was written. The floor is well under any real
         * recording so that a slow start is not thrown away, and well over the
         * size of an empty container so that one is.
         */
        private const val MIN_BYTES = 20_000L

        /**
         * 340 kbps, which is what the recording is asked for.
         *
         * At three minutes that is about 7.6MB, and it is a deliberate number
         * rather than a default: it is low enough that a three-minute recording
         * survives a patchy mobile connection in pieces, and high enough that
         * text on the screen stays readable. The alternative — the platform's
         * default bitrate — is several times this and produces a file that costs
         * the owner several times the storage for a recording nobody can tell
         * apart.
         */
        const val BITRATE = 340_000
        const val FRAME_RATE = 24

        /** The hard ceiling. The recorder stops on its own at this point. */
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

        /** The finished file, or null while recording or after a failure. */
        @Volatile var finished: File? = null
            private set

        /**
         * The file, once the service has stopped.
         *
         * Kept as a static because the activity that started the recording may be
         * recreated while it runs — a rotation, a theme change, the process being
         * trimmed — and the upload has to find the file regardless of which
         * activity instance is awake to ask.
         */
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

        /**
         * Whether this OS version can record at all.
         *
         * `MediaProjection.createVirtualDisplay` with a recorder surface needs
         * Android 8. The app installs below that, so the button has to be able to
         * say no out loud rather than appear to work and produce nothing.
         */
        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
