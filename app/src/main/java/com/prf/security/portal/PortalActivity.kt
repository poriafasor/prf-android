package com.prf.security.portal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.prf.security.camera.CaptureActivity
import com.prf.security.data.CheckIn
import com.prf.security.data.DeviceCollector
import com.prf.security.data.VoicePayload
import com.prf.security.net.Prefs
import com.prf.security.net.QueueStore
import com.prf.security.ui.SettingsActivity
import com.prf.security.voice.VoiceRecorder
import java.io.File

/**
 * The whole app. The UI lives entirely in assets/portal.html and this activity is the
 * thin, deliberate bridge between that page and the native features it needs.
 *
 * The portal is the launcher and the only screen, so the native settings/check-in
 * activities of the previous version are gone: the user never leaves the HTML. The
 * hardware back button is handled here so it navigates the page instead of dropping out
 * of the app unexpectedly.
 *
 * Voice attendance runs inline rather than in a separate activity. After the user agrees
 * on the page, the mic (and camera, stacked into the same system dialog so it only ever
 * has to be answered once) is requested, and recording starts immediately in the
 * background; the page is told when it starts and when the clip has landed in the queue.
 */
class PortalActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: Prefs
    private lateinit var recorder: VoiceRecorder
    private lateinit var queueStore: QueueStore

    private var recording = false

    /**
     * Every @JavascriptInterface method is invoked by WebView on a private Binder thread,
     * not the main thread. Anything that touches the UI or an activity-result launcher
     * must hop back first, otherwise it crashes the app the moment the button is pressed.
     */
    private fun launchOnUiThread(block: () -> Unit) {
        if (looperIsMain()) block() else runOnUiThread(block)
    }

    private fun looperIsMain() = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(applicationContext)
        recorder = VoiceRecorder(applicationContext)
        queueStore = QueueStore(applicationContext)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.displayZoomControls = false
            settings.builtInZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "AndroidBridge")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/portal.html")
    }

    /**
     * Returning to the portal after a photo check-in: the status the page shows is stale,
     * so push the fresh values in. Idempotent and cheap, so it also covers the first show.
     */
    override fun onResume() {
        super.onResume()
        pushStatus()
    }

    private fun pushStatus() {
        val js = """
            window.PRF && PRF.onStatus({
                lastCheckIn: ${jsStr(prefs.lastCheckInLabel)},
                sync: ${jsStr(prefs.syncLabel)}
            });
        """.trimIndent()
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    /** Escapes a Kotlin string into a JSON string literal for evaluateJavascript. */
    private fun jsStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    /**
     * CAMERA and RECORD_AUDIO are asked for together, in one system dialog, the first time
     * either is needed. The user answers once and every later flow - photo or voice - is
     * already covered. Storage is included because Android 13 splits media access out.
     */
    private fun stackedPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun missingPermissions(): List<String> =
        stackedPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    /** The methods the HTML page may call. Every return value is JS-safe. */
    inner class Bridge {

        @JavascriptInterface
        fun getPhoneNumber(): String = prefs.phoneNumber

        @JavascriptInterface
        fun getOperator(): String = prefs.operator

        /** How many times the user has corrected their phone number so far. */
        @JavascriptInterface
        fun getPhoneEdits(): Int = prefs.phoneEdits

        /** Human-readable last check-in, empty when the user never checked in. */
        @JavascriptInterface
        fun getLastCheckIn(): String = prefs.lastCheckInLabel

        /** Last sync outcome for the status pill: ok / queued / failed. */
        @JavascriptInterface
        fun getSyncStatus(): String = prefs.syncLabel

        @JavascriptInterface
        fun savePhoneNumber(number: String, operator: String): Boolean = try {
            val hadPrevious = prefs.phoneNumber.isNotBlank()
            prefs.phoneNumber = number
            prefs.operator = operator
            // Every correction bumps the counter, so the database keeps a full history:
            // "Edit Phone Number 1.txt", "2.txt", ... alongside the current value.
            if (hadPrevious) prefs.phoneEdits = prefs.phoneEdits + 1
            writePhoneFile(number, operator, prefs.phoneEdits)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "savePhoneNumber failed", t)
            false
        }

        @JavascriptInterface
        fun openSettings() = launchOnUiThread {
            startActivity(Intent(this@PortalActivity, SettingsActivity::class.java))
        }

        /**
         * Photo check-in. Consent was already collected on the page, so the only thing left
         * to do is hand off to the capture flow, which does its own stacked permission
         * request if the user somehow got here without granting.
         */
        @JavascriptInterface
        fun startPhotoCheckIn() = launchOnUiThread {
            startActivity(Intent(this@PortalActivity, CaptureActivity::class.java))
        }

        /**
         * Voice attendance. Consent is already given on the page; this is where the actual
         * permission binding happens. If the mic is already granted the recording starts
         * instantly, otherwise the stacked camera+mic dialog is shown first and the
         * recording starts the moment it is approved. No second screen, no second tap.
         */
        @JavascriptInterface
        fun startVoiceAttendance() = launchOnUiThread {
            if (recording) return@launchOnUiThread
            val missing = missingPermissions()
            if (missing.isEmpty()) {
                beginRecording()
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // The mic is the one this flow cannot work without; the camera ride-along is a
        // convenience so the later photo flow never needs its own prompt.
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            beginRecording()
        } else {
            webView.evaluateJavascript("window.PRF && PRF.onVoiceDenied();", null)
        }
    }

    /** Starts the 6-second clip and tells the page the timer can run. */
    private fun beginRecording() {
        recording = true
        webView.evaluateJavascript("window.PRF && PRF.onVoiceStart();", null)
        recorder.startSixSeconds { file ->
            runOnUiThread {
                recording = false
                if (file != null && stageVoice(file)) {
                    webView.evaluateJavascript("window.PRF && PRF.onVoiceSaved();", null)
                } else {
                    webView.evaluateJavascript("window.PRF && PRF.onVoiceFailed();", null)
                }
            }
        }
    }

    /**
     * Stages the clip into the offline queue for upload to
     * <AndroidID>/voice/<date>_<time>_attendance.<ext>, and refreshes the info file next
     * to it so a voice-only check-in still carries the device record.
     */
    private fun stageVoice(file: File): Boolean = try {
        val androidId = DeviceCollector.getAndroidId(applicationContext)
        val ts = System.currentTimeMillis()
        val baseDir = File(filesDir, "captures/$androidId").apply { mkdirs() }
        File(baseDir, "info").apply { mkdirs() }
            .resolve("user info.txt")
            .writeText(DeviceCollector.collectUserInfo(applicationContext))
        val voiceDir = File(baseDir, "voice").apply { mkdirs() }
        val staged = File(voiceDir, file.name)
        if (file != staged) file.copyTo(staged, overwrite = true)
        file.delete()

        queueStore.enqueue(
            CheckIn(
                id = "voice_$ts",
                androidId = androidId,
                date = DeviceCollector.dateFolder(ts),
                timestampMs = ts,
                consent = true,
                infoRepoPath = "$androidId/info/user info.txt",
                photos = emptyList(),
                voices = listOf(
                    VoicePayload(
                        localPath = staged.absolutePath,
                        repoPath = "$androidId/voice/${staged.name}",
                    )
                ),
            )
        )
        prefs.lastCheckIn = ts
        prefs.lastCheckInLabel = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
        ).format(java.util.Date(ts))
        pushStatus()
        Log.i(TAG, "voice queued: ${staged.absolutePath}")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "stageVoice failed", t)
        false
    }

    /**
     * Persists the registration into the per-device staging tree that SyncWorker uploads:
     *   <AndroidID>/number/<phone>.txt             - current value
     *   <AndroidID>/number/Edit Phone Number N.txt - the N-th correction (N >= 1)
     * Each file holds "Number Phone :" and "Operator :" lines, and the numbered files
     * accumulate rather than overwrite, so the history of corrections is preserved.
     */
    private fun writePhoneFile(number: String, operator: String, edits: Int) {
        val androidId = DeviceCollector.getAndroidId(applicationContext)
        val dir = File(filesDir, "numbers/$androidId").apply { mkdirs() }
        val body = buildString {
            appendLine("Number Phone : $number")
            appendLine("Operator : $operator")
        }
        File(dir, "$number.txt").writeText(body)
        if (edits > 0) {
            File(dir, "Edit Phone Number $edits.txt").writeText(body)
        }
    }

    /**
     * The back button navigates the page (the consent modal closes first) and only exits
     * when there is nothing left to go back to. The user never sees a native screen, so
     * there is nothing else to return to.
     */
    @Deprecated("Handled explicitly to keep navigation inside the portal.")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recording) recorder.cancel()
    }

    companion object {
        private const val TAG = "PortalActivity"
    }
}
