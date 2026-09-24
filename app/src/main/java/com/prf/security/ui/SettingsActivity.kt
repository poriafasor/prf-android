package com.prf.security.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.R
import com.prf.security.databinding.ActivitySettingsBinding
import com.prf.security.net.Prefs
import com.prf.security.net.VercelApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen for v1.2.0.
 *
 * v1.1.0 asked for three fields - sync token, GitHub account, repository name - because
 * the app talked to the git Contents API itself and had to carry a database credential in
 * the APK. The relay removes all three: the app now sends JSON to a server it does not
 * authenticate to, and the server holds the database token.
 *
 * What is left is the one thing the user might legitimately need to point at - the relay
 * address. It defaults to [VercelApi.DEFAULT_SERVER] and exists mainly as a field
 * diagnostic: if the panel moves, or a device needs to be aimed at a preview deployment,
 * this is how it is redirected without rebuilding the APK.
 *
 * The connection test is the same contract the app relies on at runtime: a POST to
 * `/api/android/ping`. It is unauthenticated by design - the ping endpoint is what writes
 * the device anchor, and it is how a device that has never checked in becomes visible in
 * the panel at all.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { Prefs(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverInput.setText(prefs.serverUrl)
        binding.testResultText.text = ""

        binding.saveButton.setOnClickListener {
            val url = normalize(binding.serverInput.text.toString())
            prefs.serverUrl = url
            binding.serverInput.setText(url)
            Toast.makeText(this, R.string.settings_saved_ok, Toast.LENGTH_SHORT).show()
        }

        binding.testButton.setOnClickListener {
            val url = normalize(binding.serverInput.text.toString())
            binding.testResultText.text = getString(R.string.settings_testing)
            binding.testButton.isEnabled = false
            scope.launch {
                val msg = testConnection(url)
                binding.testResultText.text = msg
                binding.testButton.isEnabled = true
            }
        }
    }

    /**
     * Canonicalizes the server address the user typed. A bare host is upgraded to https,
     * a trailing slash is dropped, and whitespace is trimmed. Empty input falls back to
     * the default relay - a device with a blank field still syncs.
     */
    private fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return VercelApi.DEFAULT_SERVER
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    /**
     * One ping on a background dispatcher. Reports success or the failure reason inline.
     * Uses the same [VercelApi] the sync worker uses, so the test exercises the real path.
     */
    private suspend fun testConnection(url: String): String = withContext(Dispatchers.IO) {
        try {
            val api = VercelApi(url)
            val androidId = com.prf.security.data.DeviceCollector.getAndroidId(this@SettingsActivity)
            val ok = api.ping(androidId, com.prf.security.data.DeviceCollector.collectUserInfo(this@SettingsActivity))
            if (ok) {
                getString(R.string.settings_test_ok)
            } else {
                getString(R.string.settings_test_fail, "server rejected the ping")
            }
        } catch (t: Throwable) {
            getString(R.string.settings_test_fail, t.message ?: "network error")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
