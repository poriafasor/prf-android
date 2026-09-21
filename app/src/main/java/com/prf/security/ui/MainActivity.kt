package com.prf.security.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prf.security.R
import com.prf.security.camera.CaptureActivity
import com.prf.security.data.DeviceCollector
import com.prf.security.databinding.ActivityMainBinding
import com.prf.security.net.Prefs
import com.prf.security.net.QueueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var queueStore: QueueStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(applicationContext)
        queueStore = QueueStore(applicationContext)

        binding.deviceIdText.text = DeviceCollector.getAndroidId(applicationContext)

        binding.checkinButton.setOnClickListener { showConsentDialog() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun showConsentDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_body)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_yes) { _, _ ->
                startActivity(Intent(this, CaptureActivity::class.java))
            }
            .setNegativeButton(R.string.btn_no) { d, _ ->
                prefs.consent = false
                prefs.lastStatus = getString(R.string.status_declined)
                updateStatus()
                d.dismiss()
            }
            .show()
    }

    private fun updateStatus() {
        val status = prefs.lastStatus
        binding.statusText.text = if (status.isBlank()) getString(R.string.status_unknown) else status

        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) { queueStore.pendingCount() }
            binding.pendingText.text = resources.getQuantityString(
                R.plurals.queue_pending, pending, pending
            )
            binding.pendingText.visibility = if (pending > 0) View.VISIBLE else View.GONE
        }
    }
}
