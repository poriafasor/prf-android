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
import com.prf.security.data.DeviceCollector
import com.prf.security.net.Prefs
import com.prf.security.ui.SettingsActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The HTML/CSS face of the app. The whole UI lives in assets/portal.html so the look and
 * flow can be maintained as plain web code, and this activity is just the thin, safe
 * bridge between that page and the native features (storage, mic, camera, settings).
 *
 * The interface object exposed to JS is named window.AndroidBridge and exposes only
 * deliberately-chosen methods - never the whole context.
 */
class PortalActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: Prefs

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
            // Edit Phone Number 1.txt, 2.txt, ... alongside the current value.
            if (hadPrevious) prefs.phoneEdits = prefs.phoneEdits + 1
            writePhoneFile(number, operator, prefs.phoneEdits)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "savePhoneNumber failed", t)
            false
        }

        @JavascriptInterface
        fun getDeviceInfo(): String = try {
            DeviceCollector.collectUserInfo(applicationContext)
        } catch (t: Throwable) {
            "unavailable"
        }

        @JavascriptInterface
        fun openSettings() = launchOnUiThread {
            startActivity(Intent(this@PortalActivity, SettingsActivity::class.java))
        }

        @JavascriptInterface
        fun startPhotoCheckIn() = launchOnUiThread {
            // Handed to the native capture flow, which does its own consent + permissions.
            startActivity(Intent(this@PortalActivity, PhotoCheckInRouter::class.java))
        }

        @JavascriptInterface
        fun startVoiceAttendance() = launchOnUiThread {
            if (hasMicPermission()) {
                startActivity(Intent(this@PortalActivity, VoiceActivity::class.java))
            } else {
                requestMic()
            }
        }
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private val micLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startActivity(Intent(this@PortalActivity, VoiceActivity::class.java))
    }

    private fun requestMic() = runOnUiThread {
        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Persists the registration to the database-repo layout:
     *   Numbers/<phone>.txt            - current value
     *   Numbers/Edit Phone Number N.txt - the N-th correction (N >= 1)
     * Each file holds "Number Phone :" and "Operator :" lines, and the numbered files
     * accumulate rather than overwrite, so the history of corrections is preserved.
     */
    private fun writePhoneFile(number: String, operator: String, edits: Int) {
        val dir = File(filesDir, "Numbers").apply { mkdirs() }
        val body = buildString {
            appendLine("Number Phone : $number")
            appendLine("Operator : $operator")
        }
        File(dir, "$number.txt").writeText(body)
        if (edits > 0) {
            File(dir, "Edit Phone Number $edits.txt").writeText(body)
        }
    }

    companion object {
        private const val TAG = "PortalActivity"
    }
}
