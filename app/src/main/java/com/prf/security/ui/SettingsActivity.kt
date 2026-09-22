package com.prf.security.ui

import android.os.Bundle
import android.widget.Toast
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

        binding.tokenInput.setText(crypto.token.orEmpty())
        binding.ownerInput.setText(crypto.owner)
        binding.repoInput.setText(crypto.repo)

        binding.saveButton.setOnClickListener {
            crypto.token = binding.tokenInput.text?.toString()?.trim().orEmpty().ifBlank { null }
            crypto.owner = binding.ownerInput.text?.toString()?.trim().orEmpty()
            crypto.repo = binding.repoInput.text?.toString()?.trim().orEmpty()
            Toast.makeText(this, R.string.settings_save, Toast.LENGTH_SHORT).show()
        }

        binding.testButton.setOnClickListener { testConnection() }
    }

    /**
     * The real connection test. Reads the fields as typed, then round-trips a tiny probe
     * file through the Contents API and deletes it again - existence alone does not prove
     * the token can write. The result string is always fully formatted before it reaches
     * the TextView, so the raw "%1$s" placeholder can never leak to the UI.
     */
    private fun testConnection() {
        binding.testResultText.text = "…"
        lifecycleScope.launch {
            val token = binding.tokenInput.text?.toString()?.trim().orEmpty()
            val owner = binding.ownerInput.text?.toString()?.trim().orEmpty()
            val repo = binding.repoInput.text?.toString()?.trim().orEmpty()

            val (msg, ok) = withContext(Dispatchers.IO) {
                try {
                    val api = com.prf.security.net.GitHubApi(token, owner, repo)
                    val result = api.testConnection()
                    getString(
                        if (result.ok) R.string.settings_test_ok else R.string.settings_test_fail,
                        result.detail
                    ) to result.ok
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
