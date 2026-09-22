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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(applicationContext)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.displayZoomControls = false
            settings.builtInZoomControls = false
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

        @JavascriptInterface
        fun savePhoneNumber(number: String, operator: String): Boolean = try {
            val hadPrevious = prefs.phoneNumber.isNotBlank()
            prefs.phoneNumber = number
            prefs.operator = operator
            // Every correction bumps the counter, so the database keeps a full history:
            // Edit Phone Number 1.txt, 2.txt, ... alongside the current value.
            if (hadPrevious) prefs.phoneEdits = prefs.phoneEdits + 1
            writePhoneFile(number, operator)
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
        fun openSettings() {
            startActivity(Intent(this@PortalActivity, SettingsActivity::class.java))
        }

        @JavascriptInterface
        fun startPhotoCheckIn() {
            // Handed to the native capture flow, which does its own consent + permissions.
            startActivity(Intent(this@PortalActivity, PhotoCheckInRouter::class.java))
        }

        @JavascriptInterface
        fun startVoiceAttendance() {
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
        if (granted) startActivity(Intent(this, VoiceActivity::class.java))
    }

    private fun requestMic() = micLauncher.launch(Manifest.permission.RECORD_AUDIO)

    /**
     * Persists the registration to the database repo layout: Numbers/<phone>.txt holds
     * "Number Phone :" and "Operator :" lines, and every edit appends a new numbered file
     * so the history of corrections is kept rather than overwritten.
     */
    private fun writePhoneFile(number: String, operator: String) {
        val dir = File(filesDir, "Numbers").apply { mkdirs() }
        val body = buildString {
            appendLine("Number Phone : $number")
            appendLine("Operator : $operator")
        }
        File(dir, "$number.txt").writeText(body)

        val edits = prefs.phoneEdits
        if (edits > 0) {
            File(dir, "Edit Phone Number $edits.txt").writeText(body)
        } else {
            File(dir, "Edit Phone Number 0.txt").writeText(body)
        }
    }

    companion object {
        private const val TAG = "PortalActivity"
    }
}
