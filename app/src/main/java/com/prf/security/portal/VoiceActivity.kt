package com.prf.security.portal

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.data.CheckIn
import com.prf.security.data.DeviceCollector
import com.prf.security.data.VoicePayload
import com.prf.security.net.QueueStore
import com.prf.security.voice.VoiceRecorder
import java.io.File

/**
 * Six-second voice attendance. The consent prompt is shown again here, in the native flow,
 * because the HTML page's prompt is a UI affordance - the binding permission request is
 * what actually grants the mic, and the user must approve it themselves.
 *
 * On finish the clip is staged into the offline queue as a VoicePayload so it uploads to
 * Voices/<date>_<time>_attendance.m4a in the database repo alongside the photos.
 */
class VoiceActivity : AppCompatActivity() {

    private lateinit var recorder: VoiceRecorder
    private lateinit var queueStore: QueueStore
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recorder = VoiceRecorder(applicationContext)
        queueStore = QueueStore(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }
        val title = TextView(this).apply { text = "Voice attendance"; textSize = 22f }
        val status = TextView(this).apply {
            text = "Press the button, then say: today, date, I am present at the company."
            textSize = 15f
            setPadding(0, 24, 0, 48)
        }
        val button = Button(this).apply { text = "Start 6-second recording" }
        root.addView(title)
        root.addView(status)
        root.addView(button)
        setContentView(root)

        button.setOnClickListener {
            if (!recording) {
                button.text = "Recording… speak now"
                recording = true
                recorder.startSixSeconds { file ->
                    runOnUiThread {
                        recording = false
                        button.text = "Start 6-second recording"
                        if (file != null) {
                            stageVoice(file)
                            status.text = "Saved. Attendance recorded."
                        } else {
                            status.text = "Recording failed. Try again."
                        }
                    }
                }
            }
        }
    }

    /** Puts the clip into the offline queue for upload. */
    private fun stageVoice(file: File) {
        try {
            val androidId = DeviceCollector.getAndroidId(applicationContext)
            val ts = System.currentTimeMillis()
            queueStore.enqueue(
                CheckIn(
                    id = "voice_${ts}",
                    androidId = androidId,
                    date = DeviceCollector.dateFolder(ts),
                    timestampMs = ts,
                    consent = true,
                    infoRepoPath = "$androidId/user info.txt",
                    photos = emptyList(),
                    voices = listOf(
                        VoicePayload(
                            localPath = file.absolutePath,
                            repoPath = "Voices/${file.name}",
                        )
                    ),
                )
            )
            Log.i(TAG, "voice queued: ${file.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "stageVoice failed", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recording) recorder.cancel()
    }

    companion object {
        private const val TAG = "VoiceActivity"
    }
}
