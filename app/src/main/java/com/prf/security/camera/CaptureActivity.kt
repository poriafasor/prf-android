package com.prf.security.camera

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.prf.security.R
import com.prf.security.data.CheckIn
import com.prf.security.data.DeviceCollector
import com.prf.security.data.PhotoPayload
import com.prf.security.databinding.ActivityCaptureBinding
import com.prf.security.net.Prefs
import com.prf.security.net.QueueStore
import com.prf.security.perm.Permissions
import com.prf.security.worker.SyncWorker
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Captures exactly 3 front + 3 back photos after explicit on-screen consent, then stages
 * them into the offline queue for upload to prf-database. Nothing is captured before the
 * user presses "Yes, take photos".
 *
 * PERMISSION FIX: the previous version stacked CAMERA + RECORD_AUDIO into one
 * `RequestMultiplePermissions` batch. On Android 11+ only the first dialog of a batch is
 * shown and the result map is missing every permission the user was never asked about;
 * that absent entry is not a `false`, so a denied camera made the whole batch fail and
 * the mic silently never recorded. Here CAMERA is asked for on its own - one dialog, one
 * answer - and the grant state is read back from the OS, never from the result map.
 *
 * CAMERA FIX: the previous version called [CameraSelector.requireLensFacing] unconditionally
 * and let the IllegalArgumentException escape as "No Camera Available on This Device" on
 * old, deprecated or flagless sensors. This version asks [CameraCompat] which lenses
 * actually exist first, builds a lens plan from that, and degrades to whatever sensors are
 * present instead of crashing. When CameraX still cannot bind a lens (broken Camera2 HAL
 * on 2018-era handsets like the Xiaomi Redmi 8), it transparently switches to the Camera1
 * engine, which talks to the old driver path every one of those sensors still answers to.
 * Only when BOTH paths fail does the check-in degrade to a photo-less record.
 */
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var queueStore: QueueStore
    private lateinit var prefs: Prefs

    private var imageCapture: ImageCapture? = null
    private var boundLens: Int = NO_LENS
    private var facing: Int = LENS_FRONT
    private var frontShots = 0
    private var backShots = 0
    private var capturing = false

    /** Camera1 fallback engine, used when CameraX cannot bind the lens. */
    private var camera1: Camera1Capture? = null

    /** Camera1 preview surface, kept alive while the fallback engine runs. */
    private var camera1Surface: SurfaceView? = null

    /** True while the Camera1 engine (not CameraX) is the active capture path. */
    private var camera1Fallback = false

    /** Lenses confirmed present on THIS device, in the order they will be used. */
    private var lensPlan: List<Int> = emptyList()

    /**
     * CAMERA on its own. The mic is never part of this request: the voice flow asks for it
     * separately, from the portal, so a refused camera cannot strand the mic in an
     * undefined state the way the v1.0 stacked batch did.
     */
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Permissions.markAsked(this, Permissions.camera())
        if (Permissions.isGranted(this, Permissions.camera())) {
            binding.consentPanel.visibility = View.VISIBLE
            bindCamera(facingLens()) {}
        } else if (Permissions.isPermanentlyDenied(this, Permissions.camera())) {
            Toast.makeText(this, R.string.err_camera_blocked, Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, R.string.err_no_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** True when the camera is usable right now, read from the OS, not a result map. */
    private fun cameraGranted(): Boolean =
        Permissions.isGranted(this, Permissions.camera())

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        queueStore = QueueStore(applicationContext)
        prefs = Prefs(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Discover what this device really has. Never assume a lens exists.
        val available = mutableListOf<Int>()
        if (CameraCompat.lensAvailable(this, LENS_FRONT)) available.add(LENS_FRONT)
        if (CameraCompat.lensAvailable(this, LENS_BACK)) available.add(LENS_BACK)
        lensPlan = available
        Log.i(TAG, "lensPlan=$lensPlan")

        if (lensPlan.isEmpty()) {
            Log.w(TAG, "no camera detected on this device")
            Toast.makeText(this, R.string.err_no_camera, Toast.LENGTH_LONG).show()
        }

        binding.yesButton.setOnClickListener {
            prefs.consent = true
            binding.consentPanel.visibility = View.GONE
            takeNextPhoto()
        }
        binding.noButton.setOnClickListener {
            prefs.consent = false
            queueCheckIn(photos = emptyList())
            Toast.makeText(this, R.string.status_declined, Toast.LENGTH_SHORT).show()
            finish()
        }

        if (cameraGranted()) {
            binding.consentPanel.visibility = View.VISIBLE
            bindCamera(facingLens()) {}
        } else if (Permissions.isPermanentlyDenied(this, Permissions.camera())) {
            Toast.makeText(this, R.string.err_camera_blocked, Toast.LENGTH_LONG).show()
            finish()
        } else {
            cameraLauncher.launch(Permissions.camera())
        }
    }

    private fun facingLens(): Int = lensPlan.firstOrNull() ?: LENS_BACK

    /**
     * Binds [lens] to the lifecycle and reports readiness through [onReady], which runs on
     * the main thread. imageCapture is only valid after [onReady] fires.
     *
     * If CameraX cannot bind this lens (broken Camera2 HAL on a deprecated device) the
     * error is swallowed and the Camera1 engine is started instead, so the caller can
     * keep capturing instead of the activity dying.
     */
    private fun bindCamera(lens: Int, onReady: () -> Unit) {
        // Camera1 engine from a previous lens is now irrelevant.
        camera1?.release()
        camera1 = null
        camera1Surface?.let { (binding.root as? android.view.ViewGroup)?.removeView(it) }
        camera1Surface = null

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.Builder().requireLensFacing(lens).build(),
                    preview,
                    capture,
                )
                imageCapture = capture
                boundLens = lens
                camera1Fallback = false
                binding.previewView.visibility = View.VISIBLE
                Log.i(TAG, "cameraX bound lens=$lens")
            } catch (t: Throwable) {
                Log.e(TAG, "CameraX failed for lens=$lens - falling back to Camera1", t)
                imageCapture = null
                boundLens = NO_LENS
                startCamera1(lens)
            }
            onReady()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Switches the capture engine to the deprecated, universally-working Camera1 path. */
    private fun startCamera1(lens: Int) {
        camera1?.release()
        camera1Surface?.let { (binding.root as? android.view.ViewGroup)?.removeView(it) }
        camera1Surface = null

        val engine = Camera1Capture.open(lens) ?: run {
            Log.w(TAG, "camera1 also unavailable for lens=$lens")
            return
        }
        // The fallback needs its own surface, stacked under the consent panel.
        val surface = SurfaceView(this).apply {
            layoutParams = binding.previewView.layoutParams
            setBackgroundColor(0xFF000000.toInt())
            z = -1f
            visibility = View.VISIBLE
        }
        (binding.root as? android.view.ViewGroup)?.addView(surface, 0)
        camera1Surface = surface
        engine.attachPreview(surface)
        camera1 = engine
        boundLens = lens
        camera1Fallback = true
        Log.i(TAG, "camera1 engine ready for lens=$lens")
    }

    /** Alternates front/back until 3+3 are collected, skipping absent lenses. */
    private fun takeNextPhoto() {
        when {
            frontShots < FRONT_TOTAL && lensPlan.contains(LENS_FRONT) -> {
                facing = LENS_FRONT; ensureCameraThenCapture()
            }
            backShots < BACK_TOTAL && lensPlan.contains(LENS_BACK) -> {
                facing = LENS_BACK; ensureCameraThenCapture()
            }
            else -> finishSession()
        }
    }

    private fun ensureCameraThenCapture() {
        if (camera1Fallback || imageCapture == null || boundLens != facing) {
            bindCamera(facing) { captureOne() }
        } else {
            captureOne()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun captureOne() {
        val capture = imageCapture
        val engine = camera1
        if (capturing) return
        if (capture == null && engine == null) {
            Log.w(TAG, "no capture engine available - skipping shot")
            takeNextPhoto()
            return
        }
        capturing = true

        val realFront = (engine?.facing ?: facing) == LENS_FRONT
        val index = (if (realFront) frontShots else backShots) + 1
        val name = "${if (realFront) "front" else "back"}_$index.jpg"
        // Session scratch dir; finishSession() relocates these into the per-device tree.
        val outFile = File(sessionDir(), name)

        binding.hintText.text = getString(
            if (realFront) R.string.capture_hint_front else R.string.capture_hint_back, index, FRONT_TOTAL
        )
        binding.progressText.text = "Photo ${frontShots + backShots + 1} of $TOTAL_SHOTS"
        animateShutter()

        if (camera1Fallback && engine != null) {
            engine.capture(outFile,
                onSaved = { runOnUiThread { capturing = false; onShotSaved(realFront, it) } },
                onError = { runOnUiThread {
                    capturing = false
                    Toast.makeText(this, R.string.err_capture, Toast.LENGTH_SHORT).show()
                    takeNextPhoto()
                } },
            )
            return
        }

        capture!!.takePicture(
            ImageCapture.OutputFileOptions.Builder(outFile).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    Log.i(TAG, "saved $name")
                    runOnUiThread { capturing = false; onShotSaved(realFront, outFile) }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "cameraX capture failed - trying Camera1", exc)
                    runOnUiThread {
                        capturing = false
                        // Camera2 capture itself failed mid-session: fall back to Camera1
                        // for the remaining shots instead of abandoning the check-in.
                        startCamera1(facing)
                        ensureCameraThenCapture()
                    }
                }
            },
        )
    }

    /** Bumps the right counter and drives the next shot. */
    private fun onShotSaved(isFront: Boolean, file: File) {
        if (isFront) frontShots++ else backShots++
        Log.i(TAG, "saved ${file.name} (${frontShots + backShots}/$TOTAL_SHOTS)")
        takeNextPhoto()
    }

    private fun animateShutter() {
        binding.previewView.animate().alpha(0.35f).setDuration(90)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.previewView.animate().alpha(1f).setDuration(120).start()
                }
            }).start()
    }

    /** Scratch dir the Camera1/CameraX callbacks write into mid-session. */
    private fun sessionDir(): File =
        File(filesDir, "session").apply { mkdirs() }

    /** All photos are in - stage them and hand the session to the queue. */
    private fun finishSession() {
        binding.progressText.text = getString(R.string.status_sending)
        Thread {
            try {
                val androidId = DeviceCollector.getAndroidId(applicationContext)
                val ts = System.currentTimeMillis()
                val date = DeviceCollector.dateFolder(ts)
                val infoText = DeviceCollector.collectUserInfo(applicationContext)

                // New database layout: <AndroidID>/images/<date>/{front,back}_N.jpg
                // and <AndroidID>/info/user info.txt
                val baseDir = File(filesDir, "captures/$androidId").apply { mkdirs() }
                val imagesDir = File(baseDir, "images/$date").apply { mkdirs() }
                val infoFile = File(baseDir, "info").apply { mkdirs() }
                    .resolve("user info.txt").apply { writeText(infoText) }

                val staged = mutableListOf<File>()
                for (shot in 1..FRONT_TOTAL) {
                    val src = File(sessionDir(), "front_$shot.jpg")
                    if (!src.exists()) continue
                    val dst = File(imagesDir, src.name)
                    if (src != dst) src.copyTo(dst, overwrite = true)
                    src.delete()
                    staged.add(dst)
                }
                for (shot in 1..BACK_TOTAL) {
                    val src = File(sessionDir(), "back_$shot.jpg")
                    if (!src.exists()) continue
                    val dst = File(imagesDir, src.name)
                    if (src != dst) src.copyTo(dst, overwrite = true)
                    src.delete()
                    staged.add(dst)
                }

                val payloads = staged.sorted().map { img ->
                    PhotoPayload(
                        localPath = img.absolutePath,
                        repoPath = "$androidId/images/$date/${img.name}",
                    )
                }

                queueCheckIn(payloads, infoLocal = infoFile.absolutePath)
                prefs.lastCheckIn = ts
                prefs.lastStatus = getString(R.string.status_done)
                // The HTML portal shows this verbatim, so it must be a readable stamp,
                // not a raw epoch the user cannot interpret.
                prefs.lastCheckInLabel = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
                ).format(java.util.Date(ts))

                runOnUiThread {
                    Toast.makeText(this, R.string.capture_done, Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "staging failed", t)
                runOnUiThread {
                    Toast.makeText(this, R.string.status_error, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    /**
     * Enqueues the check-in. Photos carry consent; when the user declined, a `.no-media`
     * marker is staged into the date folder instead. Git keeps no empty directory, so
     * without that marker a declined check-in would leave no trace of the date at all and
     * the panel would never show it.
     */
    private fun queueCheckIn(
        photos: List<PhotoPayload>,
        infoLocal: String? = null,
    ) {
        val ts = System.currentTimeMillis()
        val androidId = DeviceCollector.getAndroidId(applicationContext)
        val date = DeviceCollector.dateFolder(ts)

        // No staged photos -> the date folder needs an anchor file or it will not exist.
        var markerRepo: String? = null
        var markerLocal: String? = null
        if (photos.isEmpty()) {
            val marker = File(filesDir, "captures/$androidId/images/$date/$NO_MEDIA_FILE")
            marker.parentFile?.mkdirs()
            marker.writeText("no photos captured\n")
            markerLocal = marker.absolutePath
            markerRepo = "$androidId/images/$date/$NO_MEDIA_FILE"
        }

        queueStore.enqueue(
            CheckIn(
                id = ts.toString(),
                androidId = androidId,
                date = date,
                timestampMs = ts,
                consent = photos.isNotEmpty(),
                infoRepoPath = "$androidId/info/user info.txt",
                infoLocalPath = infoLocal
                    ?: File(filesDir, "captures/$androidId/info/user info.txt").absolutePath,
                photos = photos,
                markerRepoPath = markerRepo,
                markerLocalPath = markerLocal,
            )
        )
        // Do not wait up to 15 minutes for the periodic pass.
        SyncWorker.enqueueNow(applicationContext)
        Log.i(TAG, "check-in queued with ${photos.size} photo(s)")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        camera1?.release()
        camera1 = null
    }

    companion object {
        private const val TAG = "CaptureActivity"
        private const val FRONT_TOTAL = 3
        private const val BACK_TOTAL = 3
        private const val TOTAL_SHOTS = FRONT_TOTAL + BACK_TOTAL
        private const val NO_LENS = -1
        /** Anchor written into an empty capture day so the date folder exists in git. */
        private const val NO_MEDIA_FILE = ".no-media"
        private const val LENS_FRONT = CameraSelector.LENS_FACING_FRONT
        private const val LENS_BACK = CameraSelector.LENS_FACING_BACK
    }
}
