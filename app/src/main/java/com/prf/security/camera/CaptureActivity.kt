package com.prf.security.camera

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.View
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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Captures exactly 3 front + 3 back photos after explicit on-screen consent, then stages
 * them into the offline queue for upload to prf-database. Nothing is captured before the
 * user presses "Yes, take photos".
 *
 * CAMERA FIX: the previous version called [CameraSelector.requireLensFacing] unconditionally
 * and let the IllegalArgumentException escape as "No Camera Available on This Device" on
 * old, deprecated or flagless sensors. This version asks [CameraCompat] which lenses
 * actually exist first, builds a lens plan from that, and degrades to whatever sensors are
 * present instead of crashing. If the device truly has no camera, the check-in is still
 * recorded without photos - the app never refuses to run.
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

    /** Lenses confirmed present on THIS device, in the order they will be used. */
    private var lensPlan: List<Int> = emptyList()

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            binding.consentPanel.visibility = View.VISIBLE
            bindCamera(facingLens()) {}
        } else {
            Toast.makeText(this, R.string.err_no_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

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
        if (CameraCompat.lensAvailable(this, LENS_BACK)) available.add(LENS_BACK)
        if (CameraCompat.lensAvailable(this, LENS_FRONT)) available.add(LENS_FRONT)
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

        if (requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            binding.consentPanel.visibility = View.VISIBLE
            bindCamera(facingLens()) {}
        } else {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    private fun facingLens(): Int = lensPlan.firstOrNull() ?: LENS_BACK

    /**
     * Binds [lens] to the lifecycle and reports readiness through [onReady], which runs on
     * the main thread. imageCapture is only valid after [onReady] fires.
     *
     * If CameraX cannot bind this lens (broken Camera2 HAL on a deprecated device) the
     * error is swallowed and imageCapture is left null, so the caller can fall through to
     * the next lens in the plan instead of the activity dying.
     */
    private fun bindCamera(lens: Int, onReady: () -> Unit) {
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
                binding.previewView.visibility = View.VISIBLE
            } catch (t: Throwable) {
                Log.e(TAG, "Camera init failed for lens=$lens", t)
                imageCapture = null
                boundLens = NO_LENS
            }
            onReady()
        }, ContextCompat.getMainExecutor(this))
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
        if (imageCapture == null || boundLens != facing) {
            bindCamera(facing) { captureOne() }
        } else {
            captureOne()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun captureOne() {
        val capture = imageCapture
        if (capturing || capture == null) return
        capturing = true

        val isFront = facing == LENS_FRONT
        val index = (if (isFront) frontShots else backShots) + 1
        val name = "${if (isFront) "front" else "back"}_$index.jpg"
        val outDir = File(filesDir, "captures").apply { mkdirs() }
        val outFile = File(outDir, name)

        binding.hintText.text = getString(
            if (isFront) R.string.capture_hint_front else R.string.capture_hint_back, index, FRONT_TOTAL
        )
        binding.progressText.text = "Photo ${frontShots + backShots + 1} of $TOTAL_SHOTS"
        animateShutter()

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(outFile).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    if (isFront) frontShots++ else backShots++
                    Log.i(TAG, "saved $name (${frontShots + backShots}/$TOTAL_SHOTS)")
                    runOnUiThread {
                        capturing = false
                        takeNextPhoto()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exc)
                    runOnUiThread {
                        capturing = false
                        Toast.makeText(
                            this@CaptureActivity,
                            getString(R.string.err_capture, exc.message),
                            Toast.LENGTH_SHORT
                        ).show()
                        takeNextPhoto()
                    }
                }
            },
        )
    }

    private fun animateShutter() {
        binding.previewView.animate().alpha(0.35f).setDuration(90)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.previewView.animate().alpha(1f).setDuration(120).start()
                }
            }).start()
    }

    /** All photos are in - stage them and hand the session to the queue. */
    private fun finishSession() {
        binding.progressText.text = getString(R.string.status_sending)
        Thread {
            try {
                val androidId = DeviceCollector.getAndroidId(applicationContext)
                val ts = System.currentTimeMillis()
                val date = DeviceCollector.dateFolder(ts)
                val infoText = DeviceCollector.collectUserInfo(applicationContext)

                File(filesDir, "captures/$androidId").apply { mkdirs() }
                    .resolve("user info.txt").writeText(infoText)

                val sessionDir = File(filesDir, "captures").apply { mkdirs() }
                val imagesDir = File(filesDir, "captures/$androidId/$date/images").apply { mkdirs() }

                val staged = mutableListOf<File>()
                for (shot in 1..FRONT_TOTAL) {
                    val src = File(sessionDir, "front_$shot.jpg")
                    if (!src.exists()) continue
                    val dst = File(imagesDir, src.name)
                    if (src != dst) src.copyTo(dst, overwrite = true)
                    src.delete()
                    staged.add(dst)
                }
                for (shot in 1..BACK_TOTAL) {
                    val src = File(sessionDir, "back_$shot.jpg")
                    if (!src.exists()) continue
                    val dst = File(imagesDir, src.name)
                    if (src != dst) src.copyTo(dst, overwrite = true)
                    src.delete()
                    staged.add(dst)
                }

                val payloads = staged.sorted().map { img ->
                    PhotoPayload(
                        localPath = img.absolutePath,
                        repoPath = "$androidId/$date/images/${img.name}",
                    )
                }

                queueCheckIn(payloads)
                prefs.lastCheckIn = ts
                prefs.lastStatus = getString(R.string.status_done)

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

    private fun queueCheckIn(photos: List<PhotoPayload>) {
        val ts = System.currentTimeMillis()
        val androidId = DeviceCollector.getAndroidId(applicationContext)
        val date = DeviceCollector.dateFolder(ts)
        queueStore.enqueue(
            CheckIn(
                id = ts.toString(),
                androidId = androidId,
                date = date,
                timestampMs = ts,
                consent = photos.isNotEmpty(),
                infoRepoPath = "$androidId/user info.txt",
                photos = photos,
            )
        )
        Log.i(TAG, "check-in queued with ${photos.size} photo(s)")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CaptureActivity"
        private const val FRONT_TOTAL = 3
        private const val BACK_TOTAL = 3
        private const val TOTAL_SHOTS = FRONT_TOTAL + BACK_TOTAL
        private const val NO_LENS = -1
        private const val LENS_FRONT = CameraSelector.LENS_FACING_FRONT
        private const val LENS_BACK = CameraSelector.LENS_FACING_BACK
    }
}
