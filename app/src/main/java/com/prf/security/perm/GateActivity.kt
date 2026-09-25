package com.prf.security.perm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.R
import com.prf.security.data.DeviceCollector
import com.prf.security.mdm.OwnershipWorker
import com.prf.security.mdm.PrfDeviceAdminReceiver
import com.prf.security.net.CryptoStore
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.3.0 honest MDM onboarding.
 *
 * This screen says exactly what the app does, in plain language, before anything runs:
 *  - reports lock state and failed unlock attempts to the owner's console
 *  - reports location ONLY after the owner flags the device lost
 *  - can lock the phone, and (if set as Device Owner) wipe it, on the owner's command
 *  - cannot and does not read the camera, microphone, messages or clipboard
 *
 * There is no permission gate silently blocking features and no hidden collection: the
 * only runtime permission the app asks for is ACCESS_FINE_LOCATION, and it is used solely
 * for the lost-mode location report the owner enables.
 */
class GateActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var action: Button
    private lateinit var adminBtn: Button
    private lateinit var progress: ProgressBar

    private var registered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        title = TextView(this).apply {
            text = getString(R.string.gate_title)
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
            gravity = Gravity.CENTER
        }
        body = TextView(this).apply {
            text = getString(R.string.mdm_onboarding_body)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, 0, 0, dp(24))
        }
        action = Button(this).apply {
            text = getString(R.string.mdm_activate)
            setOnClickListener { onActivate() }
        }
        adminBtn = Button(this).apply {
            text = getString(R.string.mdm_enable_admin)
            visibility = View.GONE
            setOnClickListener { requestAdmin() }
        }
        for (v in listOf(title, body, progress, action, adminBtn)) root.addView(v, lp)
        setContentView(root)
    }

    private fun refresh() {
        val prefs = Prefs.get(this)
        registered = prefs.registered && prefs.deviceKey.isNotEmpty()
        if (registered) {
            body.text = getString(R.string.mdm_active_body)
            action.text = getString(R.string.mdm_open_settings)
            adminBtn.visibility =
                if (PrfDeviceAdminReceiver.isAdminActive(this)) View.GONE else View.VISIBLE
        } else {
            body.text = getString(R.string.mdm_onboarding_body)
            action.text = getString(R.string.mdm_activate)
            adminBtn.visibility = View.GONE
        }
    }

    private fun onActivate() {
        if (registered) {
            startActivity(Intent(this, com.prf.security.ui.SettingsActivity::class.java))
            return
        }
        registerDevice()
    }

    private fun registerDevice() {
        progress.visibility = View.VISIBLE
        action.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            val prefs = Prefs.get(this@GateActivity)
            val api = MdmApi(prefs.serverUrl, "")
            val androidId = DeviceCollector.getAndroidId(this@GateActivity)
            Prefs.cacheAndroidId(this@GateActivity, androidId)
            val hardware = DeviceCollector.collect(this@GateActivity)
            val res = withContext(Dispatchers.IO) {
                api.register(androidId, hardware, prefs.label.ifEmpty { android.os.Build.MODEL })
            }
            progress.visibility = View.GONE
            action.isEnabled = true
            if (res.ok && res.deviceKey.isNotEmpty()) {
                prefs.deviceKey = res.deviceKey
                prefs.registered = true
                prefs.androidId(this@GateActivity)
                CryptoStore(this@GateActivity).deviceKey = res.deviceKey
                OwnershipWorker.schedulePeriodic(this@GateActivity)
                OwnershipWorker.runNow(this@GateActivity)
            }
            refresh()
        }
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                PrfDeviceAdminReceiver.componentName(this@GateActivity)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.mdm_admin_explanation)
            )
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        @Suppress("unused")
        private const val TAG = "PRF.Gate"
    }
}
