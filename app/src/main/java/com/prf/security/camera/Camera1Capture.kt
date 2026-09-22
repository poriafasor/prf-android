package com.prf.security.camera

import android.content.Context
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.prf.security.camera.CameraCompat.LENS_BACK
import com.prf.security.camera.CameraCompat.LENS_FRONT
import java.io.File
import java.io.FileOutputStream

/**
 * The real reason "No Camera Available on This Device" would still appear on a Xiaomi
 * Redmi 8 and other 2018-era handsets: CameraX talks to the Camera2 HAL, and on those
 * devices the HAL is only partially implemented (LEGACY / LIMITED level). CameraX then
 * either reports the lens as missing or fails while binding, and the app gives up.
 *
 * This class is the honest fallback: it opens the sensor through the deprecated but
 * universally working [android.hardware.Camera] API, runs a real preview, and calls
 * [Camera.takePicture] with a JPEG callback. It exists precisely so that when the
 * Camera2 path fails, the app still produces a photograph instead of an error toast.
 *
 * Lifecycle: [open] the camera, [attachPreview] a SurfaceView so the driver has a
 * surface, then [capture] each file. [release] when the session is done.
 */
class Camera1Capture private constructor(
    private val cameraId: Int,
    private val camera: Camera,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewRunning = false
    private var capturing = false

    val facing: Int = cameraFacingOf(cameraId)

    /** Connects the open camera to a surface so preview frames actually flow. */
    fun attachPreview(surfaceView: SurfaceView) {
        val holder = surfaceView.holder
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                runCatching {
                    camera.setPreviewDisplay(h)
                    camera.startPreview()
                    previewRunning = true
                    Log.i(TAG, "camera1 preview started (id=$cameraId, facing=$facing)")
                }.onFailure { Log.e(TAG, "camera1 preview start failed", it) }
            }

            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}

            override fun surfaceDestroyed(h: SurfaceHolder) {
                runCatching { camera.stopPreview() }
                previewRunning = false
            }
        })
        // Surface may already exist if the view was added before this call.
        holder.let {
            if (it.surface != null) {
                runCatching {
                    camera.setPreviewDisplay(it)
                    camera.startPreview()
                    previewRunning = true
                }
            }
        }
    }

    /**
     * Takes one JPEG. [onSaved] / [onError] are always invoked exactly once, on the main
     * thread, so the caller can advance its shot counter without worrying about threading.
     * The short pre-roll gives the sensor time to expose after the previous capture.
     */
    fun capture(outFile: File, onSaved: (File) -> Unit, onError: (Throwable) -> Unit) {
        if (capturing) {
            onError(IllegalStateException("camera1 already capturing"))
            return
        }
        capturing = true
        mainHandler.postDelayed({
            try {
                if (!previewRunning) {
                    camera.startPreview()
                    previewRunning = true
                }
                camera.takePicture(
                    /* shutter */ null,
                    /* raw */ null,
                    /* postview */ null,
                    Camera.PictureCallback { data, _ ->
                        try {
                            FileOutputStream(outFile).use { it.write(data) }
                            Log.i(TAG, "camera1 saved ${outFile.name} (${data.size} bytes)")
                            mainHandler.post { capturing = false; onSaved(outFile) }
                        } catch (t: Throwable) {
                            Log.e(TAG, "camera1 write failed", t)
                            mainHandler.post { capturing = false; onError(t) }
                        }
                    },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "camera1 takePicture failed", t)
                capturing = false
                onError(t)
            }
        }, PRE_ROLL_MS)
    }

    /** Releases the sensor. Safe to call more than once. */
    fun release() {
        runCatching {
            if (previewRunning) camera.stopPreview()
            camera.release()
        }
        previewRunning = false
    }

    private fun cameraFacingOf(id: Int): Int = try {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(id, info)
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) LENS_FRONT else LENS_BACK
    } catch (_: Throwable) {
        LENS_BACK
    }

    companion object {
        private const val TAG = "Camera1Capture"
        private const val PRE_ROLL_MS = 350L

        /**
         * Opens the best sensor for [facing] through the Camera1 API, or null when no such
         * camera exists or the driver refuses to open it. Never throws.
         */
        fun open(facing: Int): Camera1Capture? {
            val n = runCatching { Camera.getNumberOfCameras() }.getOrDefault(0)
            if (n <= 0) {
                Log.w(TAG, "camera1 reports no cameras")
                return null
            }
            // First look for an exact facing match, then fall back to any working sensor -
            // a single-camera device has no front sensor at all.
            val wanted = when (facing) {
                LENS_FRONT -> Camera.CameraInfo.CAMERA_FACING_FRONT
                LENS_BACK -> Camera.CameraInfo.CAMERA_FACING_BACK
                else -> Camera.CameraInfo.CAMERA_FACING_BACK
            }
            val targetId = (0 until n).firstOrNull { id ->
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(id, info)
                info.facing == wanted
            } ?: 0

            return runCatching {
                Log.i(TAG, "opening camera1 id=$targetId (want facing=$wanted, total=$n)")
                Camera1Capture(targetId, Camera.open(targetId))
            }.onFailure { Log.e(TAG, "camera1 open failed", it) }.getOrNull()
        }
    }
}
