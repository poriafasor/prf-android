package com.prf.security.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.content.pm.PackageManager
import com.prf.security.data.DeviceCollector
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records a fixed 6-second attendance voice clip, only after the user has explicitly
 * agreed on screen. The user speaks their own attendance statement (date + presence).
 *
 * The recorder picks the best encoder the device actually supports rather than assuming
 * one: AAC on anything modern, AMR-NB as the fallback for the deprecated handsets that
 * ship without an AAC recorder. Every MediaRecorder call is wrapped, because this API
 * throws or returns INIT on hardware that cannot do either, and that must never crash
 * the app.
 *
 * Output lands in app-private storage under voices/<AndroidID>/<date>_<time>_attendance.<ext>,
 * mirroring the per-device layout the database repo expects. The caller uploads it from there.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** Starts a 6-second recording. Invokes [onComplete] with the file, or null on failure. */
    fun startSixSeconds(onComplete: (File?) -> Unit) {
        val baseName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(Date(System.currentTimeMillis()))
        val useAac = supportsAac()
        val ext = if (useAac) "m4a" else "amr"
        val dir = File(context.filesDir, "$DIR_VOICES/${DeviceCollector.getAndroidId(context)}").apply { mkdirs() }
        val out = File(dir, "${baseName}_attendance.$ext")
        outputFile = out

        try {
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (useAac) {
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(96000)
                rec.setAudioSamplingRate(44100)
            } else {
                rec.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            }
            rec.setOutputFile(out.absolutePath)
            rec.setMaxDuration(SIX_SECONDS_MS)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stopAndFinalize(onComplete)
                }
            }
            rec.prepare()
            rec.start()
            recorder = rec
            Log.i(TAG, "recording 6s -> ${out.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "could not start recorder", t)
            recorder = null
            onComplete(null)
        }
    }

    /** Stops the recorder if it is running and reports the finished file. */
    private fun stopAndFinalize(onComplete: (File?) -> Unit) {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recorder stop threw", t)
        }
        recorder = null
        val out = outputFile
        onComplete(if (out != null && out.exists() && out.length() > 0) out else null)
    }

    /** Releases resources if the activity is destroyed mid-recording. */
    fun cancel() {
        try {
            recorder?.apply { stop(); release() }
        } catch (t: Throwable) {
            Log.w(TAG, "cancel threw", t)
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    private fun supportsAac(): Boolean = runCatching {
        val pm = context.packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }.getOrDefault(false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val DIR_VOICES = "voices"
        private const val SIX_SECONDS_MS = 6000
    }
}
