package com.prf.security.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.R
import com.prf.security.databinding.ActivitySettingsBinding
import com.prf.security.mdm.OwnershipWorker
import com.prf.security.mdm.PrfDeviceAdminReceiver
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.3.0 MDM status screen.
 *
 * Shows the device's own state plainly: registered or not, admin enabled or not, lost
 * mode on or off, last report time, and the server address. The lost-mode switch here is
 * the same flag the owner sets from the admin panel - turning it on here is the
 * user-side equivalent of "I lost my phone", and it is the only thing that makes the app
 * report location.
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

        binding.lostModeButton.setOnClickListener { toggleLostMode() }
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

    private fun toggleLostMode() {
        val now = prefs.lostMode
        if (!now && !PrfDeviceAdminReceiver.isAdminActive(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.mdm_lost_mode)
                .setMessage(getString(R.string.mdm_lost_mode_needs_admin))
                .setPositiveButton(R.string.mdm_enable_admin) { _, _ ->
                    startActivity(
                        android.content.Intent(
                            android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                        ).putExtra(
                            android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            PrfDeviceAdminReceiver.componentName(this)
                        )
                    )
                }
                .setNegativeButton(R.string.gate_not_now, null)
                .show()
            return
        }
        prefs.lostMode = !now
        OwnershipWorker.runNow(this)
        render()
    }

    private fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Prefs.DEFAULT_SERVER
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    private suspend fun testConnection(url: String): String = withContext(Dispatchers.IO) {
        try {
            val api = MdmApi(url, prefs.deviceKey)
            val batch = api.commands("")
            if (batch != null) getString(R.string.settings_test_ok)
            else getString(R.string.settings_test_fail, "server rejected the request")
        } catch (t: Throwable) {
            getString(R.string.settings_test_fail, t.message ?: "network error")
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val registered = prefs.registered && prefs.deviceKey.isNotEmpty()
        val admin = PrfDeviceAdminReceiver.isAdminActive(this)
        binding.statusText.text = if (registered) {
            getString(R.string.mdm_status_registered, prefs.label.ifEmpty { android.os.Build.MODEL })
        } else {
            getString(R.string.mdm_status_not_registered)
        }
        binding.syncText.text = if (registered) {
            getString(
                R.string.mdm_status_sync,
                prefs.syncLabel.ifEmpty { "—" },
                admin
            )
        } else {
            getString(R.string.mdm_status_admin_off)
        }
        binding.lostModeButton.text = getString(
            if (prefs.lostMode) R.string.mdm_lost_mode_on else R.string.mdm_lost_mode_off
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
