package com.prf.security.capture

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Automatic attendance capture: three photos from the front lens, then three from
 * the back, with no shutter press in between.
 *
 * Why CameraX and not the system camera: `ACTION_IMAGE_CAPTURE` hands the shot to
 * another app, and that app decides how many presses a photo costs. Capturing
 * automatically means driving the camera from inside this app, which needs a real
 * camera API — CameraX is the supported one.
 *
 * The user is never left guessing. [bind] puts a live preview on screen before
 * anything is recorded, and it stays on screen through the burst, so the camera
 * is visibly open for as long as it is open. A device with no lens of the
 * requested facing, a camera that will not open, or a lens that is busy all come
 * back as a sentence rather than a silent zero.
 *
 * Nothing here runs without CAMERA permission, which the person grants at the
 * moment they press the attendance button. No background component of this app
 * holds a reference to this class.
 */
class AutoCapture(
    private val context: Context,
    private val owner: LifecycleOwner,
) {

    private var bound: ProcessCameraProvider? = null
    private var currentLensFront: Boolean = true

    private val mainExecutor: Executor get() = ContextCompat.getMainExecutor(context)

    /** True when the device actually has a camera on the requested side. */
    suspend fun hasCamera(front: Boolean): Boolean = try {
        cameraProvider().hasCamera(selector(front))
    } catch (t: Throwable) {
        Log.w(TAG, "no camera for front=$front: ${t.message}")
        false
    }

    /**
     * Shows the live preview from the requested lens, so the user can see that
     * the camera is on and pointed at them before anything is recorded.
     */
    suspend fun bind(preview: PreviewView, front: Boolean) {
        val p = cameraProvider()
        val useCase = Preview.Builder().build().apply {
            setSurfaceProvider(preview.surfaceProvider)
        }
        // bindToLifecycle replaces the previous binding rather than stacking, so
        // switching lenses does not leave the old one running beside the new one.
        p.bindToLifecycle(owner, selector(front), useCase)
        currentLensFront = front
    }

    /**
     * Takes [count] photos back to back from the lens currently shown in [preview].
     *
     * The preview stays bound alongside the capture, so the screen the person is
     * looking at keeps showing the live image through the whole burst instead of
     * going black the moment the first shot is taken.
     */
    suspend fun capture(
        preview: PreviewView,
        dir: File,
        prefix: String,
        count: Int,
        onShot: (index: Int, total: Int, file: File) -> Unit,
    ) {
        val p = cameraProvider()
        val sel = selector(currentLensFront)
        val still = ImageCapture.Builder()
            // Minimise latency so three shots in a row do not turn into a
            // slideshow; the burst is the point of this flow.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val live = Preview.Builder().build().apply {
            setSurfaceProvider(preview.surfaceProvider)
        }

        p.unbindAll()
        p.bindToLifecycle(owner, sel, live, still)
        try {
            repeat(count) { i ->
                val file = File(dir, "${prefix}_${i + 1}.jpg")
                takeOne(still, file)
                onShot(i + 1, count, file)
            }
        } finally {
            release()
        }
    }

    private suspend fun takeOne(capture: ImageCapture, file: File) {
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "photo failed: ${exception.message}")
                        // A partial record is still worth sending, so a failed
                        // shot is reported to the caller and the burst continues.
                        if (cont.isActive) cont.resume(Unit)
                    }
                },
            )
        }
        if (!file.exists() || file.length() == 0L) {
            throw IllegalStateException("the camera produced an empty file")
        }
    }

    /** Stops the camera. Called on every exit path, including a failure. */
    fun release() {
        try {
            bound?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "unbind failed: ${t.message}")
        }
    }

    private fun selector(front: Boolean): CameraSelector = CameraSelector.Builder()
        .requireLensFacing(
            if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
        )
        .build()

    private suspend fun cameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            bound?.let {
                cont.resume(it)
                return@suspendCancellableCoroutine
            }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val p = future.get()
                    bound = p
                    if (cont.isActive) cont.resume(p)
                } catch (t: Throwable) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("the camera service did not start: ${t.message}"),
                        )
                    }
                }
            }, mainExecutor)
        }

    companion object {
        private const val TAG = "PRF.AutoCapture"
    }
}
