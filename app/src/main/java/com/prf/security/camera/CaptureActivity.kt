package com.prf.security.camera

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.prf.security.R
import com.prf.security.data.CaptureResult
import com.prf.security.data.CheckIn
import com.prf.security.data.DeviceCollector
import com.prf.security.data.PhotoPayload
import com.prf.security.databinding.ActivityCaptureBinding
import com.prf.security.net.Prefs
import com.prf.security.net.QueueStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Captures exactly 3 front + 3 back photos after explicit on-screen consent, then stages
 * them into the offline queue for upload to prf-database. Nothing is captured before the
 * user presses "Yes, take photos".
 */
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var queueStore: QueueStore
    private lateinit var prefs: Prefs

    private var imageCapture: ImageCapture? = null
    private var facing = CameraSelector.LENS_FACING_FRONT
    private var frontShots = 0
    private var backShots = 0
    private var capturing = false

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
            startCamera()
        } else {
            Toast.makeText(this, R.string.err_no_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on for the whole capture session.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        queueStore = QueueStore(applicationContext)
        prefs = Prefs(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

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
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.Builder().requireLensFacing(facing).build(),
                    preview,
                    imageCapture,
                )
                binding.previewView.visibility = View.VISIBLE
            } catch (t: Throwable) {
                Log.e(TAG, "Camera init failed", t)
                Toast.makeText(this, R.string.err_no_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Alternates front/back until 3+3 are collected. */
    private fun takeNextPhoto() {
        when {
            frontShots < FRONT_TOTAL -> { facing = LENS_FRONT; captureOne() }
            backShots < BACK_TOTAL -> { facing = LENS_BACK; captureOne() }
            else -> finishSession()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun captureOne() {
        val capture = imageCapture
        if (capturing || capture == null) return
        capturing = true

        // Switch lens if the session needs the other camera now.
        if ((facing == LENS_FRONT && frontShots >= FRONT_TOTAL) ||
            (facing == LENS_BACK && backShots >= BACK_TOTAL)
        ) {
            restartCameraWith(facing)
        }

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
                        // Retry the same shot index.
                        takeNextPhoto()
                    }
                }
            },
        )
    }

    private fun restartCameraWith(lens: Int) {
        imageCapture = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.Builder().requireLensFacing(lens).build(),
                    preview,
                    imageCapture,
                )
            }.onFailure {
                Log.e(TAG, "camera switch failed", it)
                Toast.makeText(this, R.string.err_no_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun animateShutter() {
        binding.previewView.animate().alpha(0.35f).setDuration(90)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.previewView.animate().alpha(1f).setDuration(120).start()
                }
            }).start()
    }

    /** All 6 photos are in - stage them and hand the session to the queue. */
    private fun finishSession() {
        binding.progressText.text = getString(R.string.status_sending)
        Thread {
            try {
                val androidId = DeviceCollector.getAndroidId(applicationContext)
                val ts = System.currentTimeMillis()
                val date = DeviceCollector.dateFolder(ts)
                val infoText = DeviceCollector.collectUserInfo(applicationContext)

                // <AndroidID>/user info.txt
                val infoFile = File(filesDir, "captures/$androidId/user info.txt").apply {
                    parentFile?.mkdirs(); writeText(infoText)
                }

                // <AndroidID>/<date>/images/front_1..3.jpg + back_1..3.jpg
                val imagesDir = File(filesDir, "captures/$androidId/$date/images").apply { mkdirs() }
                val moved = mutableListOf<File>()
                File(filesDir, "captures").listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".jpg") }
                    .sorted()
                    .forEach { src ->
                        val dst = File(imagesDir, src.name)
                        if (src != dst) {
                            src.copyTo(dst, overwrite = true)
                            moved.add(dst)
                        }
                    }

                val payloads = moved.map { img ->
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
        private const val LENS_FRONT = CameraSelector.LENS_FACING_FRONT
        private const val LENS_BACK = CameraSelector.LENS_FACING_BACK
    }
}
