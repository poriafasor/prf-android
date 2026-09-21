package com.prf.security.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prf.security.R
import com.prf.security.databinding.ActivitySettingsBinding
import com.prf.security.net.CryptoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var crypto: CryptoStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        crypto = CryptoStore(applicationContext)

        // Show stored values without leaking the token itself.
        binding.tokenInput.setText(crypto.token.orEmpty())
        binding.ownerInput.setText(crypto.owner)
        binding.repoInput.setText(crypto.repo)

        binding.saveButton.setOnClickListener {
            crypto.token = binding.tokenInput.text?.toString()?.trim().orEmpty().ifBlank { null }
            crypto.owner = binding.ownerInput.text?.toString()?.trim().orEmpty()
            crypto.repo = binding.repoInput.text?.toString()?.trim().orEmpty()
            android.widget.Toast.makeText(
                this, R.string.settings_save, android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        binding.testButton.setOnClickListener { testConnection() }
    }

    private fun testConnection() {
        binding.testResultText.text = "…"
        lifecycleScope.launch {
            val token = binding.tokenInput.text?.toString()?.trim().orEmpty()
            val owner = binding.ownerInput.text?.toString()?.trim().orEmpty()
            val repo = binding.repoInput.text?.toString()?.trim().orEmpty()
            val (msg, ok) = withContext(Dispatchers.IO) {
                try {
                    val api = com.prf.security.net.GitHubApi(token, owner, repo)
                    val exists = api.repoExists()
                    getString(if (exists) R.string.settings_test_ok else R.string.settings_test_fail) to exists
                } catch (t: Throwable) {
                    getString(R.string.settings_test_fail, t.message ?: "error") to false
                }
            }
            binding.testResultText.text = msg
            binding.testResultText.setTextColor(
                getColor(if (ok) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
            )
        }
    }
}
