package com.prf.security.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.prf.security.R
import com.prf.security.data.AttendancePayload
import com.prf.security.data.LocationReport
import com.prf.security.location.LocationCollector
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import com.prf.security.perm.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Attendance capture — check-in and check-out with photos and a voice note.
 *
 * The design rule for this screen is that the person holding the phone is always
 * in control and always able to see it:
 *
 *  • The screen is only ever reached by a tap. Nothing opens it on a timer, on a
 *    command from the server, or when lost mode turns on.
 *  • The camera is the system camera, opened by an explicit shutter press. One
 *    press, one photo, and the system UI is what the user sees.
 *  • The microphone records between two explicit presses, with a running timer
 *    on screen, and stops on its own at [MAX_VOICE_SECONDS].
 *  • CAMERA and RECORD_AUDIO are requested at the moment of use, never at
 *    startup, and never together with anything else — a batched request on
 *    Android 11+ only surfaces the first dialog.
 *  • If a permission is declined the feature is simply unavailable; it is never
 *    worked around.
 *
 * The server keeps whatever is sent permanently, which is why the consent text
 * is on screen before anything is captured rather than behind a link.
 */
class AttendanceActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: MdmApi
    private val collector by lazy { LocationCollector(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var kind: String = KIND_IN
    private val photos = mutableListOf<String>()      // base64 jpeg
    private var voice: String? = null
    private var location: LocationReport? = null

    private var pendingPhotoFile: File? = null
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceStartedAt = 0L
    private val ticker = Handler(Looper.getMainLooper())
    private var recording = false

    private lateinit var photoRow: LinearLayout
    private lateinit var recordBtn: Button
    private lateinit var recordStatus: TextView
    private lateinit var locationLine: TextView
    private lateinit var submitBtn: Button
    private lateinit var statusLine: TextView
    private lateinit var inBtn: Button
    private lateinit var outBtn: Button

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhotoFile
        pendingPhotoFile = null
        if (ok && file != null && file.exists() && file.length() > 0) addPhoto(file)
        else toast(getString(R.string.att_capture_failed))
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Permissions.isGranted(this, Manifest.permission.CAMERA)) launchCamera()
        else toast(getString(R.string.att_perm_needed_camera))
    }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Permissions.isGranted(this, Manifest.permission.RECORD_AUDIO)) startRecording()
        else toast(getString(R.string.att_perm_needed_mic))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)
        prefs = Prefs.get(this)
        api = MdmApi(prefs.serverUrl, prefs.deviceKey)

        photoRow = findViewById(R.id.photoRow)
        recordBtn = findViewById(R.id.btnRecord)
        recordStatus = findViewById(R.id.recordStatus)
        locationLine = findViewById(R.id.locationLine)
        submitBtn = findViewById(R.id.btnSubmit)
        statusLine = findViewById(R.id.status)
        inBtn = findViewById(R.id.btnCheckIn)
        outBtn = findViewById(R.id.btnCheckOut)

        findViewById<TextView>(R.id.photosHint).apply {
            // The hint carries the cap this screen enforces, so the label and
            // the limit cannot drift apart.
            text = getString(R.string.att_photos_hint, MAX_PHOTOS)
        }
        findViewById<TextView>(R.id.voiceHint).apply {
            text = getString(R.string.att_voice_hint, MAX_VOICE_SECONDS)
        }

        recordBtn.setOnClickListener { if (recording) stopRecording() else requestMic() }
        submitBtn.setOnClickListener { submit() }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener { requestCamera() }
        inBtn.setOnClickListener { setKind(KIND_IN) }
        outBtn.setOnClickListener { setKind(KIND_OUT) }

        setKind(KIND_IN)
        if (!prefs.registered || prefs.deviceKey.isEmpty()) {
            statusLine.text = getString(R.string.att_need_register)
            statusLine.setTextColor(getColor(R.color.prf_bad))
            submitBtn.isEnabled = false
        }
        showLocation()
    }

    private fun setKind(next: String) {
        kind = next
        val active = getColor(R.color.prf_accent)
        val idle = getColor(R.color.prf_text)
        inBtn.setTextColor(if (kind == KIND_IN) active else idle)
        outBtn.setTextColor(if (kind == KIND_OUT) active else idle)
    }

    // ── permissions ───────────────────────────────────────────────────────────

    private fun requestCamera() {
        if (photos.size >= MAX_PHOTOS) {
            toast(getString(R.string.att_photo_limit, MAX_PHOTOS))
            return
        }
        if (Permissions.isGranted(this, Manifest.permission.CAMERA)) launchCamera()
        else {
            Permissions.markAsked(this, Manifest.permission.CAMERA)
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestMic() {
        if (Permissions.isGranted(this, Manifest.permission.RECORD_AUDIO)) startRecording()
        else {
            Permissions.markAsked(this, Manifest.permission.RECORD_AUDIO)
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "attendance").apply { mkdirs() }
        // Anything already in the directory belongs to an unsent record; clear
        // it so a stale capture can never be attached by accident.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        pendingPhotoFile = file
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            takePicture.launch(uri)
        } catch (t: Throwable) {
            // No camera app on the device. Say so rather than failing silently.
            pendingPhotoFile = null
            toast(getString(R.string.att_no_camera))
        }
    }

    // ── photos ────────────────────────────────────────────────────────────────

    private fun addPhoto(file: File) {
        scope.launch(Dispatchers.IO) {
            // Downscale before encoding: a 12MP original is ~4MB of base64 per
            // photo, which the server rejects anyway and the phone's memory
            // would not enjoy.
            val bmp = decodeScaled(file, MAX_PHOTO_EDGE)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bmp.recycle()
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            // Decoded here too: decoding even a scaled JPEG on the main thread
            // is a visible stutter on a low-end phone.
            val thumb = decodeScaled(file, THUMB_EDGE)

            withContext(Dispatchers.Main) {
                val iv = ImageView(this@AttendanceActivity)
                iv.setImageBitmap(thumb)
                iv.contentDescription = getString(R.string.att_capture_photo)
                // Long-press removes, so a photo taken by mistake is not a
                // record the user is stuck with.
                iv.setOnLongClickListener {
                    removePhoto(iv)
                    true
                }
                val pad = (8 * resources.displayMetrics.density).toInt()
                iv.setPadding(pad, pad, pad, pad)
                val size = (THUMB_EDGE * resources.displayMetrics.density).toInt()
                photoRow.addView(iv, LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = pad
                })
                photos.add(b64)
                updateSubmit()
            }
        }
    }

    /** Removes by view, not by a captured index: after a removal every later
     *  photo has shifted, so an index captured at add time would delete the
     *  wrong one. */
    private fun removePhoto(view: View) {
        val index = photoRow.indexOfChild(view)
        if (index < 0) return
        photoRow.removeViewAt(index)
        if (index < photos.size) photos.removeAt(index)
        updateSubmit()
    }

    private fun decodeScaled(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalStateException("could not decode ${file.name}")
    }

    // ── voice ─────────────────────────────────────────────────────────────────

    private fun startRecording() {
        if (recording) return
        val dir = File(cacheDir, "attendance").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val rec = MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (t: Throwable) {
            rec.release()
            toast(getString(R.string.att_mic_failed))
            return
        }
        recorder = rec
        voiceFile = file
        voiceStartedAt = System.currentTimeMillis()
        recording = true
        recordBtn.setText(R.string.att_stop)
        ticker.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = ((System.currentTimeMillis() - voiceStartedAt) / 1000).toInt()
            if (elapsed >= MAX_VOICE_SECONDS) {
                stopRecording(truncated = true)
                return
            }
            recordStatus.text = getString(R.string.att_recording, elapsed)
            ticker.postDelayed(this, 250)
        }
    }

    private fun stopRecording(truncated: Boolean = false) {
        if (!recording) return
        recording = false
        ticker.removeCallbacks(tick)
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (t: Throwable) { /* too short to encode */ }
        rec?.release()

        val file = voiceFile
        voiceFile = null
        val seconds = ((System.currentTimeMillis() - voiceStartedAt) / 1000).toInt()
        // The button label is reset on every exit, including the failure one:
        // leaving it reading "Stop" would be a control that lies about its state.
        recordBtn.setText(R.string.att_record)
        if (file == null || !file.exists() || file.length() == 0L || seconds < 1) {
            recordStatus.text = getString(R.string.att_idle)
            toast(getString(R.string.att_rec_too_short))
            return
        }
        voice = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        recordStatus.text = if (truncated) {
            getString(R.string.att_recorded_too_long, MAX_VOICE_SECONDS)
        } else {
            getString(R.string.att_recorded, seconds)
        }
        updateSubmit()
    }

    override fun onPause() {
        // Leaving the screen must not leave the microphone running.
        if (recording) stopRecording()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted location in system settings while this
        // screen sat in the background; a line that still reads "denied" after
        // they did would be wrong.
        if (location == null) showLocation()
    }

    // ── submit ────────────────────────────────────────────────────────────────

    private fun showLocation() {
        if (!Permissions.isGranted(this, Permissions.location())) {
            locationLine.text = getString(R.string.att_location_denied)
            return
        }
        val fix = collector.lastKnownFix()
        if (fix == null) {
            locationLine.text = getString(R.string.att_location_none)
            return
        }
        val p = collector.toPayload(fix)
        location = LocationReport(
            id = "att-${System.currentTimeMillis()}",
            androidId = Prefs.androidId(this),
            timestampMs = System.currentTimeMillis(),
            maps = p["maps"] ?: "",
            geo = p["geo"] ?: "",
            plusCode = p["plus_code"] ?: "",
            raw = p["raw"] ?: "",
        )
        locationLine.text = getString(R.string.att_location, p["geo"] ?: "")
    }

    private fun updateSubmit() {
        // A record with no photo and no voice is still a valid attendance row,
        // so the button is gated on registration only.
        submitBtn.isEnabled = prefs.registered && !submitting
    }

    private fun submit() {
        if (submitting) return
        submitting = true
        submitBtn.isEnabled = false
        statusLine.text = getString(R.string.att_sending)
        statusLine.setTextColor(getColor(R.color.prf_text_dim))

        val info = buildMap {
            put("app", "android/${android.os.Build.VERSION.SDK_INT}")
            put("model", android.os.Build.MODEL)
            val note = findViewById<android.widget.EditText>(R.id.inputNote).text.toString().trim()
            if (note.isNotEmpty()) put("note", note)
        }

        val payload = AttendancePayload(
            kind = kind,
            info = info,
            photos = photos.toList(),
            voice = voice,
            location = location,
        )

        scope.launch {
            val ok = api.attendance(payload)
            submitting = false
            if (ok) {
                statusLine.text = getString(R.string.att_sent)
                statusLine.setTextColor(getColor(R.color.prf_ok))
                clearLocalCopies()
                submitBtn.isEnabled = false
            } else {
                statusLine.text = getString(R.string.att_send_failed, "server rejected the record")
                statusLine.setTextColor(getColor(R.color.prf_bad))
                submitBtn.isEnabled = true
            }
        }
    }

    private fun clearLocalCopies() {
        // The record now lives on the server; leaving a full-resolution copy in
        // the cache would be a second, unmentioned copy of the user's photo.
        photos.clear()
        photoRow.removeAllViews()
        voice = null
        recordStatus.text = getString(R.string.att_idle)
        File(cacheDir, "attendance").listFiles()?.forEach { it.delete() }
    }

    override fun onDestroy() {
        // Nothing outlives the screen: the ticker, the scope and any in-flight
        // encode all stop here.
        ticker.removeCallbacksAndMessages(null)
        recorder?.release()
        recorder = null
        scope.cancel()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private var submitting = false

    private companion object {
        const val KIND_IN = "check_in"
        const val KIND_OUT = "check_out"
        const val MAX_PHOTOS = 6
        const val MAX_VOICE_SECONDS = 60
        const val MAX_PHOTO_EDGE = 1600
        const val THUMB_EDGE = 64
        const val JPEG_QUALITY = 80
    }
}
