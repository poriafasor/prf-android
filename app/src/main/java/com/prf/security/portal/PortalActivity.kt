package com.prf.security.portal

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.R
import com.prf.security.camera.CaptureActivity
import com.prf.security.clipboard.ClipboardGuard
import com.prf.security.data.CheckIn
import com.prf.security.data.DeviceCollector
import com.prf.security.data.VoicePayload
import com.prf.security.net.Prefs
import com.prf.security.net.QueueStore
import com.prf.security.perm.Permissions
import com.prf.security.ui.SettingsActivity
import com.prf.security.voice.VoiceRecorder
import com.prf.security.worker.SyncWorker
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
 * on the page the mic is requested - **one permission at a time** - and recording starts
 * immediately in the background; the page is told when it starts and when the clip has
 * landed in the queue.
 */
class PortalActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: Prefs
    private lateinit var recorder: VoiceRecorder
    private lateinit var queueStore: QueueStore

    private var recording = false

    /**
     * Threat-kind -> times warned this session. Only these counts ever leave the device
     * (attached to the next check-in); the copied text itself never does.
     */
    private val clipboardSeen = mutableMapOf<String, Int>()

    /**
     * Held as a field so the activity-result launchers can call back into it: the
     * permission callbacks run on the activity, not on the bridge that JS talks to.
     */
    private val bridge = Bridge()

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
            addJavascriptInterface(bridge, "AndroidBridge")
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
        scanClipboard()
    }

    /**
     * Reads the current clipboard **on-device** and warns the user if it matches a known
     * threat pattern. Everything happens locally: the copied text is shown only in the
     * warning dialog the user themselves sees, and nothing leaves the device except an
     * aggregate count attached to the next check-in. Scanning is gated on explicit
     * consent, and the portal asks for that consent once, the first time it is needed.
     *
     * Runs on a background thread: reading the primary clip can block on the clipboard
     * service, and doing it on the main thread is a StrictMode violation on newer APIs.
     */
    private fun scanClipboard() {
        if (!ClipboardGuard.accepted(applicationContext)) return
        Thread {
            val threat = ClipboardGuard.scan(applicationContext) ?: return@Thread
            synchronized(clipboardSeen) {
                clipboardSeen[threat.kind.key] = (clipboardSeen[threat.kind.key] ?: 0) + 1
            }
            runOnUiThread { showClipboardWarning(threat) }
        }.start()
    }

    private fun showClipboardWarning(threat: ClipboardGuard.Threat) {
        AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_warning_title)
            .setMessage(getString(R.string.clipboard_warning_body, threat.match))
            .setPositiveButton(R.string.clipboard_warning_dismiss, null)
            .setOnDismissListener { pushClipboardCounts() }
            .show()
    }

    /**
     * Attaches the aggregate threat counts to the next queued check-in. The counts are the
     * only clipboard-derived data that leaves the device - "typosquat: 2", never the text.
     */
    private fun pushClipboardCounts() {
        val counts = synchronized(clipboardSeen) { clipboardSeen.toMap() }
        val clean = ClipboardGuard.aggregateForUpload(counts)
        if (clean.isNotEmpty()) queueStore.attachClipboardThreats(clean)
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
     * One permission at a time, and the grant decision is always read back from the OS
     * with [Permissions.isGranted] - never from the activity-result map.
     *
     * The v1.0 client stacked CAMERA and RECORD_AUDIO into one
     * `RequestMultiplePermissions` call. On Android 11+ the system surfaces only the
     * first dialog of a batch and returns a result map that is missing every permission
     * the user was never shown; that absent entry is not a `false`, so the old callback
     * treated a not-asked mic as a denial: the user granted the camera, the photo was
     * taken, and the mic never recorded.
     *
     * A permission that was asked before and is no longer showable is a permanent denial,
     * and the only recovery is the app's own settings screen.
     */
    private val cameraLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Permissions.markAsked(this, Permissions.camera())
        if (granted) {
            launchOnUiThread { bridge.startPhotoCheckIn() }
        } else if (Permissions.isPermanentlyDenied(this, Permissions.camera())) {
            notifyCameraBlocked()
        }
    }

    private val micLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Permissions.markAsked(this, Permissions.mic())
        if (granted) {
            launchOnUiThread { beginRecording() }
        } else {
            notifyVoiceDenied()
        }
    }

    /** Tells the page its recording was refused so the UI can recover gracefully. */
    private fun notifyVoiceDenied() = runOnUiThread {
        webView.evaluateJavascript("window.PRF && PRF.onVoiceDenied();", null)
    }

    /** Camera is permanently blocked: the settings screen is the only recovery. */
    private fun notifyCameraBlocked() = runOnUiThread {
        webView.evaluateJavascript(
            "window.PRF && PRF.onCameraBlocked();", null
        )
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

        /** Human-readable version name, e.g. "1.1.0". */
        @JavascriptInterface
        fun getAppVersion(): String = com.prf.security.BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun savePhoneNumber(number: String, operator: String): Boolean = try {
            val hadPrevious = prefs.phoneNumber.isNotBlank()
            prefs.phoneNumber = number
            prefs.operator = operator
            // Every correction bumps the counter, so the database keeps a full history:
            // "Edit Phone Number 1.txt", "2.txt", ... alongside the current value.
            if (hadPrevious) prefs.phoneEdits = prefs.phoneEdits + 1
            writePhoneFile(number, operator, prefs.phoneEdits)
            // Registration changed: do not wait for the 15-minute periodic pass.
            SyncWorker.enqueueNow(applicationContext)
            pushStatus()
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
         * Photo check-in. Consent was already collected on the page, so the only thing
         * left to do is hand off to the capture flow, which requests the camera itself -
         * one permission, one dialog - if the user somehow got here without granting.
         */
        @JavascriptInterface
        fun startPhotoCheckIn() = launchOnUiThread {
            if (Permissions.isGranted(this@PortalActivity, Permissions.camera())) {
                startActivity(Intent(this@PortalActivity, CaptureActivity::class.java))
            } else if (Permissions.isPermanentlyDenied(this@PortalActivity, Permissions.camera())) {
                notifyCameraBlocked()
            } else {
                cameraLauncher.launch(Permissions.camera())
            }
        }

        /**
         * Voice attendance. Consent is already given on the page; this is where the actual
         * permission binding happens. If the mic is already granted the recording starts
         * instantly, otherwise the **single** mic dialog is shown first and the recording
         * starts the moment it is approved. No second screen, no second tap, and never a
         * second permission hiding behind the mic's dialog.
         */
        @JavascriptInterface
        fun startVoiceAttendance() = launchOnUiThread {
            if (recording) return@launchOnUiThread
            withMic { beginRecording() }
        }

        /**
         * Opens the app's own permission screen. The page offers this from the
         * blocked-permission UI; a permanently denied permission has no other recovery.
         */
        @JavascriptInterface
        fun openAppSettings() = launchOnUiThread {
            Permissions.openAppSettings(this@PortalActivity)
        }

        /** True when the user has already opted in to the on-device link scan. */
        @JavascriptInterface
        fun clipboardScanAccepted(): Boolean = ClipboardGuard.accepted(applicationContext)

        /** Records the consent decision from the portal's modal. */
        @JavascriptInterface
        fun setClipboardScanAccepted(accepted: Boolean) {
            ClipboardGuard.setAccepted(applicationContext, accepted)
        }
    }

    /** Asks for the mic if needed, otherwise runs [action] right away. */
    private fun withMic(action: () -> Unit) {
        if (Permissions.isGranted(this, Permissions.mic())) {
            launchOnUiThread(action)
        } else if (Permissions.isPermanentlyDenied(this, Permissions.mic())) {
            notifyVoiceDenied()
        } else {
            runOnUiThread { micLauncher.launch(Permissions.mic()) }
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
        val infoDir = File(baseDir, "info").apply { mkdirs() }
        val infoFile = infoDir.resolve("user info.txt")
        infoFile.writeText(DeviceCollector.collectUserInfo(applicationContext))

        val voiceDir = File(baseDir, "voice").apply { mkdirs() }
        val staged = File(voiceDir, file.name)
        if (file != staged) file.copyTo(staged, overwrite = true)
        file.delete()

        queueStore.enqueue(
            CheckIn(
                id = "voice_$ts",
                androidId = androidId,                date = DeviceCollector.dateFolder(ts),
                timestampMs = ts,
                consent = true,
                infoRepoPath = "$androidId/info/user info.txt",
                infoLocalPath = infoFile.absolutePath,
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
        // Do not wait for the periodic pass: the panel should show this check-in now.
        SyncWorker.enqueueNow(applicationContext)
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
     * Each file holds "Number Phone :" and "Operator :" lines, and the database keeps a
     * full correction history; the numbered files accumulate rather than overwrite.
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
