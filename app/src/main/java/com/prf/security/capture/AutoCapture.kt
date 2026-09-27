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






















class AutoCapture(
    private val context: Context,
    private val owner: LifecycleOwner,
) {

    private var bound: ProcessCameraProvider? = null
    private var currentLensFront: Boolean = true

    private val mainExecutor: Executor get() = ContextCompat.getMainExecutor(context)

    
    suspend fun hasCamera(front: Boolean): Boolean = try {
        cameraProvider().hasCamera(selector(front))
    } catch (t: Throwable) {
        Log.w(TAG, "no camera for front=$front: ${t.message}")
        false
    }

    










    suspend fun bind(preview: PreviewView?, front: Boolean) {
        val p = cameraProvider()
        if (preview == null) {
            
            
            p.unbindAll()
            p.bindToLifecycle(owner, selector(front), ImageCapture.Builder().build())
            currentLensFront = front
            return
        }
        val useCase = Preview.Builder().build().apply {
            setSurfaceProvider(preview.surfaceProvider)
        }
        
        
        p.bindToLifecycle(owner, selector(front), useCase)
        currentLensFront = front
    }

    







    suspend fun capture(
        preview: PreviewView?,
        dir: File,
        prefix: String,
        count: Int,
        onShot: (index: Int, total: Int, file: File) -> Unit,
    ) {
        val p = cameraProvider()
        val sel = selector(currentLensFront)
        val still = ImageCapture.Builder()
            
            
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        p.unbindAll()
        if (preview == null) {
            p.bindToLifecycle(owner, sel, still)
        } else {
            val live = Preview.Builder().build().apply {
                setSurfaceProvider(preview.surfaceProvider)
            }
            p.bindToLifecycle(owner, sel, live, still)
        }
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
                        
                        
                        if (cont.isActive) cont.resume(Unit)
                    }
                },
            )
        }
        if (!file.exists() || file.length() == 0L) {
            throw IllegalStateException("the camera produced an empty file")
        }
    }

    
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
