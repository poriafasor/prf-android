package com.prf.security.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

/**
 * Shows the owner the user's actual screen.
 *
 * The only supported way to do this on a non-rooted phone is `MediaProjection`,
 * and it is deliberately not covert: the user has to accept a system dialog, and
 * while it runs there is a permanent notification saying the screen is being
 * shared, which they can tap to stop it. There is no path here that captures a
 * frame without that consent, and no remote command can start it — the owner can
 * ask, and the phone still asks the user.
 *
 * The service holds a virtual display mirroring the real one and keeps the most
 * recent frame in memory. It does not upload anything itself: the poll worker
 * asks for [takeFrame] and sends it, so the upload rate is governed by the same
 * budget as every other write to the server rather than by how fast the screen
 * changes.
 *
 * On Android 14 and newer the foreground-service type has to be declared *and*
 * the service has to be in the foreground before `getMediaProjection` is called,
 * so the order in [onStartCommand] is load-bearing and is not reordered for
 * tidiness.
 */
class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var latest: Bitmap? = null
    private var encodedFor: Bitmap? = null
    @Volatile private var cachedFrame: String? = null
    @Volatile private var frameAt: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code == Int.MIN_VALUE || data == null) {
            // Started without a consent result: there is nothing to project, and
            // calling getMediaProjection without one throws.
            Log.w(TAG, "started without a consent result; nothing to share")
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_STICKY

        return try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mgr.getMediaProjection(code, data) ?: run {
                stopSelf(); return START_NOT_STICKY
            }
            projection = p
            startCapture(p)
            running = true
            START_STICKY
        } catch (t: Throwable) {
            Log.w(TAG, "could not start the projection: ${t.message}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startCapture(p: MediaProjection) {
        val metrics = displaySize()
        val (w, h) = scaleFor(metrics.first, metrics.second)
        val dpi = resources.displayMetrics.densityDpi

        thread = HandlerThread("prf-screen").also { it.start() }
        handler = Handler(thread!!.looper)

        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
        reader = ir
        ir.setOnImageAvailableListener({ source ->
            // Only the newest frame matters. A backlog of older ones would be
            // uploaded after the user had already moved on.
            var image: Image? = null
            try {
                image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                val plane = image.planes[0]
                val raw = plane.buffer
                val bytes = ByteArray(raw.remaining())
                raw.get(bytes)

                // The reader may hand back rows padded to a stride, and pixels
                // wider than the four bytes ARGB needs. copyPixelsFromBuffer
                // assumes neither, so anything unusual is repacked into a tightly
                // packed buffer first. Skipping this produces a skewed or
                // colour-swapped frame on some devices and a correct one on
                // others, which is worse than a visible failure to debug.
                val packed = if (plane.rowStride == w * 4 && plane.pixelStride == 4) {
                    java.nio.ByteBuffer.wrap(bytes)
                } else {
                    val out = java.nio.ByteBuffer.allocate(w * h * 4)
                    for (y in 0 until h) {
                        val rowStart = y * plane.rowStride
                        for (x in 0 until w) {
                            val src = rowStart + x * plane.pixelStride
                            if (src + 4 > bytes.size) break
                            out.put((y * w + x) * 4, bytes, src, 4)
                        }
                    }
                    out.flip()
                    out
                }

                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(packed)
                // The previous bitmap is dropped rather than recycled. The poll
                // worker may be inside encodeLatest() reading it right now, and
                // recycling under it would crash the worker on a bitmap it never
                // touched. One unreferenced bitmap per frame is the collector's
                // job, not a leak.
                latest = bmp
            } catch (t: Throwable) {
                Log.w(TAG, "could not read a frame: ${t.message}")
            } finally {
                image?.close()
            }
        }, handler)

        virtualDisplay = p.createVirtualDisplay(
            "prf-screen",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, handler
        )
    }

    /**
     * The most recent frame as a base64 JPEG, or null if nothing has arrived yet.
     *
     * Downscaled and re-compressed here rather than sent raw: a full-resolution
     * frame would be several megabytes of base64 on a request that the server
     * stores whole, and the panel cannot usefully show more than this anyway.
     *
     * The result is cached against the bitmap it came from, because the worker
     * asks for a frame on every poll while the screen changes many times between
     * polls — re-encoding an unchanged frame each time would be pure waste.
     */
    private fun encodeLatest(): String? {
        val src = latest ?: return null
        synchronized(this) {
            if (encodedFor === src && cachedFrame != null) return cachedFrame
            return try {
                val w = src.width
                val h = src.height
                val scale = minOf(1f, MAX_EDGE_OUT.toFloat() / maxOf(w, h))
                val tw = maxOf(1, (w * scale).toInt())
                val th = maxOf(1, (h * scale).toInt())
                val small = if (scale < 1f) Bitmap.createScaledBitmap(src, tw, th, true) else src
                val bos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)
                if (small !== src) small.recycle()
                val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                encodedFor = src
                cachedFrame = b64
                frameAt = System.currentTimeMillis()
                b64
            } catch (t: Throwable) {
                Log.w(TAG, "could not encode a frame: ${t.message}")
                null
            }
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** Keep both sides even: a virtual display with an odd width pads rows. */
    private fun scaleFor(w: Int, h: Int): Pair<Int, Int> {
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(w, h))
        var tw = (w * scale).toInt() and 1.inv()
        var th = (h * scale).toInt() and 1.inv()
        if (tw <= 0) tw = 2
        if (th <= 0) th = 2
        return tw to th
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
                ).apply { description = CHANNEL_DESC }
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(NOTIF_TITLE)
            .setContentText(NOTIF_TEXT)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        try { thread?.quitSafely() } catch (_: Throwable) {}
        virtualDisplay = null; reader = null; projection = null
        synchronized(this) {
            encodedFor = null
            cachedFrame = null
        }
        running = false
        latest?.recycle()
        latest = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PRF.Screen"
        private const val CHANNEL = "prf_screen_share"
        private const val CHANNEL_NAME = "اشتراک صفحه"
        private const val CHANNEL_DESC = "وقتی روشن است، صفحه‌ی گوشی در پنل مدیریت نمایش داده می‌شود."
        private const val NOTIF_ID = 4711
        private const val NOTIF_TITLE = "اشتراک صفحه روشن است"
        private const val NOTIF_TEXT = "صفحه‌ی این گوشی در پنل مدیریت دیده می‌شود. برای خاموش‌کردن، اینجا را لمس کنید."
        private const val MAX_IMAGES = 2
        private const val MAX_EDGE = 1080
        private const val MAX_EDGE_OUT = 720
        private const val QUALITY = 70

        const val ACTION_STOP = "com.prf.security.screen.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile private var instance: ScreenShareService? = null

        @Volatile var running: Boolean = false
            private set

        /**
         * The frame to upload, or null when nothing is being shared.
         *
         * Reached statically because the poll worker runs in this process but is
         * not the service: a worker cannot hold a reference to a service the
         * system may already have stopped and restarted. Returning null when
         * sharing is off is the important part — the worker must have no way to
         * obtain a frame the user did not agree to share.
         */
        fun currentFrame(): Pair<String, Long>? {
            val svc = instance ?: return null
            val b64 = svc.encodeLatest() ?: return null
            return b64 to svc.frameAt
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenShareService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
