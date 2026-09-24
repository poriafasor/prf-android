package com.prf.security.perm

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.R
import com.prf.security.portal.PortalActivity
import com.prf.security.worker.SyncWorker

/**
 * The v1.2.0 entry gate.
 *
 * v1.1.0 launched straight into [PortalActivity] and requested each permission the moment
 * a feature was used: the camera launcher asked for CAMERA when a check-in started, the
 * mic launcher asked for RECORD_AUDIO when a voice attendance started. If either was
 * denied the feature silently went away, and the user could reach the portal with neither
 * granted and no way to understand why nothing worked.
 *
 * v1.2.0 moves the decision to the front door. GateActivity is the launcher activity and
 * the app cannot be used until the four runtime permissions it needs are granted:
 *  * CAMERA and RECORD_AUDIO - the two capture features the portal exists for
 *  * ACCESS_FINE_LOCATION - recorded per check-in, [com.prf.security.location.LocationCollector]
 *  * POST_NOTIFICATIONS - so the sync status notification can be posted on Android 13+
 *
 * Requests are still one permission at a time (see [Permissions]): a batched request on
 * Android 11+ surfaces only the first dialog and reports the rest as not-asked, which the
 * old code read as a denial. Here the sequence is camera, then mic, then location, then
 * notifications, each with its own rationale when the OS will still show one and a hard
 * route to the app settings screen when it will not.
 *
 * The gate is re-evaluated in [onResume]: granting a permission in the system settings
 * screen and navigating back must let the user straight through without a restart.
 */
class GateActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var why: Button
    private lateinit var progress: ProgressBar

    /**
     * The permission list. NOT a field initializer: [Permissions.notificationsRuntime]
     * needs a working Activity Context, and Kotlin field initializers compile into
     * `<init>` and run *before* the activity is attached to one, so the old
     * `private val required = buildList { ... notificationsRuntime(this) }` threw an NPE
     * on every cold launch. Built in [onCreate] after `super.onCreate` instead.
     */
    private lateinit var required: List<Needed>

    private data class Needed(val permission: String, val rationale: Int)

    private lateinit var singleLauncher: ActivityResultLauncher<String>

    private var pending: Needed? = null

    private var entered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        required = buildList {
            add(Needed(Permissions.camera(), R.string.gate_rationale_camera))
            add(Needed(Permissions.mic(), R.string.gate_rationale_mic))
            add(Needed(Permissions.location(), R.string.gate_rationale_location))
            if (Permissions.notificationsRuntime(this@GateActivity)) {
                add(Needed(Permissions.notifications(), R.string.gate_rationale_notifications))
            }
        }

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
        status = TextView(this).apply {
            text = getString(R.string.gate_why)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, 0, 0, dp(24))
        }
        action = Button(this).apply {
            text = getString(R.string.gate_allow)
            visibility = View.GONE
            setOnClickListener { onAction() }
        }
        why = Button(this).apply {
            text = getString(R.string.gate_why_title)
            visibility = View.GONE
            setOnClickListener { showRationale() }
        }
        for (v in listOf(title, status, progress, action, why)) root.addView(v, lp)
        setContentView(root)

        singleLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val asked = pending
            if (asked != null) Permissions.markAsked(this, asked.permission)
            if (granted) {
                advance()
            } else {
                // The OS-level decision is re-read in onResume - never trusted to the
                // result map, which cannot distinguish "not shown" from "denied".
                render()
            }
        }
        render()
    }

    /** Re-checks on return from the system settings screen. */
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun nextMissing(): Needed? = required.firstOrNull {
        !Permissions.isGranted(this, it.permission)
    }

    private fun render() {
        val n = nextMissing()
        if (n == null) {
            status.text = getString(R.string.gate_continue)
            action.visibility = View.GONE
            why.visibility = View.GONE
            progress.visibility = View.VISIBLE
            enterApp()
            return
        }
        progress.visibility = View.GONE
        status.text = getString(R.string.gate_why)
        when {
            Permissions.isPermanentlyDenied(this, n.permission) -> {
                action.text = getString(R.string.gate_open_settings)
                action.visibility = View.VISIBLE
                why.visibility = View.VISIBLE
            }
            Permissions.canAskAgain(this, n.permission) -> {
                action.text = getString(R.string.gate_ask_again)
                action.visibility = View.VISIBLE
                why.visibility = View.VISIBLE
            }
            else -> {
                action.text = getString(R.string.gate_allow)
                action.visibility = View.VISIBLE
                why.visibility = View.GONE
            }
        }
        pending = n
    }

    private fun onAction() {
        val n = pending ?: return render()
        if (Permissions.isPermanentlyDenied(this, n.permission)) {
            // The system will not show another dialog; only the app settings screen can
            // recover this. onResume picks the sequence back up on return.
            Permissions.openAppSettings(this)
            return
        }
        if (Permissions.askedBefore(this, n.permission)) {
            // Second denial with a rationale still showable: explain, then re-ask.
            showRationale { ask(n) }
            return
        }
        ask(n)
    }

    private fun ask(n: Needed) {
        pending = n
        singleLauncher.launch(n.permission)
    }

    private fun showRationale(andThen: () -> Unit = {}) {
        val n = pending ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gate_why_title)
            .setMessage(getString(n.rationale))
            .setPositiveButton(R.string.gate_ask_again) { _, _ -> andThen() }
            .setNegativeButton(R.string.gate_not_now) { _, _ -> }
            .setCancelable(false)
            .show()
    }

    private fun advance() {
        render()
    }

    /**
     * Everything is granted. Kick the queue in case capture happened while the gate was
     * up, then enter the portal as a new task so back from the portal exits the app
     * rather than landing back on the gate.
     */
    private fun enterApp() {
        if (isFinishing || isDestroyed) return
        if (!entered) {
            entered = true
            SyncWorker.enqueueNow(this)
            startActivity(Intent(this, PortalActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ))
            finish()
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
