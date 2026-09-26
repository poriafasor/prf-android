package com.prf.security.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import com.google.android.material.button.MaterialButton
import com.prf.security.R
import com.prf.security.capture.AutoCapture
import com.prf.security.data.AttendancePayload
import com.prf.security.data.DeviceCollector
import com.prf.security.location.LocationCollector
import com.prf.security.mdm.OwnershipWorker
import com.prf.security.mdm.PolicyEnforcer
import com.prf.security.mdm.PrfDeviceAdminReceiver
import com.prf.security.net.CryptoStore
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import com.prf.security.screen.ScreenShareService
import com.prf.security.perm.Permissions
import com.prf.security.util.Persian
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The whole app, on one screen.
 *
 * v1.5.0 replaces a three-screen flow (gate → settings → attendance) with this.
 * The order on screen is the order of use:
 *
 *   1. who you are — the number and the carrier, at the very top
 *   2. what you are doing — check in or check out, then the one button
 *   3. what it is doing — the live camera, every photo as it lands, the steps
 *   4. what the phone knows about itself — battery, version, hardware, network
 *   5. what an owner configures once — the server address and the admin grant
 *
 * The attendance capture is automatic after one tap: three photos from the front
 * lens, three from the back, then a short voice note, all sent as one record.
 * That is a change of behaviour, not just of layout, so the consent dialog is
 * shown first and spells out exactly what will happen and how long it lasts —
 * and the photos appear on screen as they are taken, so the dialog is not the
 * only evidence that it kept the promise.
 *
 * Nothing here runs without a press from the person holding the phone. No
 * background component can reach the camera or the microphone, and the owner
 * cannot trigger any of this remotely.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var inputPhone: android.widget.EditText
    private lateinit var phoneError: TextView
    private lateinit var operatorSpinner: Spinner
    private lateinit var btnCheckIn: TextView
    private lateinit var btnCheckOut: TextView
    private lateinit var btnSend: MaterialButton
    private lateinit var attStatus: TextView
    private lateinit var screenStatus: TextView
    private lateinit var btnScreen: MaterialButton

    private lateinit var cardCapture: LinearLayout
    private lateinit var preview: PreviewView
    private lateinit var photoRow: LinearLayout
    private lateinit var stepRow: LinearLayout
    private lateinit var capStatus: TextView
    private lateinit var btnAbort: MaterialButton

    private lateinit var permSummary: TextView
    private lateinit var permContainer: LinearLayout

    private lateinit var batteryBar: android.widget.ProgressBar
    private lateinit var specContainer: LinearLayout
    private lateinit var inputServer: android.widget.EditText
    private lateinit var serverStatus: TextView
    private lateinit var btnRegister: MaterialButton
    private lateinit var adminStatus: TextView
    private lateinit var btnAdmin: MaterialButton

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val collector by lazy { LocationCollector(this) }
    private var captureJob: Job? = null
    private var abortRequested = false

    private var kind: String = KIND_IN
    private var operatorList: List<String> = emptyList()

    // ── permissions ────────────────────────────────────────────────────────
    // One permission per request, asked in a fixed order, with the decision read
    // back from the OS rather than from the result map. A batched request on
    // Android 11+ only surfaces the first dialog and returns a map missing the
    // rest, which is what made v1.4 look like it was being denied.

    private var pendingPerm: CancellableContinuation<Boolean>? = null

    /** The permission the launcher is currently asking about. */
    private var lastAsked: String = ""

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val c = pendingPerm
            pendingPerm = null
            // The grant decision is read back from the OS, not from this boolean.
            // On a permission the app does not hold in the manifest the launcher
            // can return true while the OS holds nothing, and that has to read as
            // a denial rather than as a photo nobody took.
            val real = granted && Permissions.isGranted(this, lastAsked)
            if (c != null && c.isActive) c.resume(real)
        }

    private suspend fun ask(permission: String): Boolean {
        if (Permissions.isGranted(this, permission)) return true
        return try {
            suspendCancellableCoroutine { cont ->
                lastAsked = permission
                // Marked before the dialog, not after: whether the system will
                // still show this dialog again is what the card reads back, and
                // the answer is only known once it has been shown once.
                Permissions.markAsked(this, permission)
                pendingPerm = cont
                cont.invokeOnCancellation { pendingPerm = null }
                try {
                    permLauncher.launch(permission)
                } catch (t: Throwable) {
                    pendingPerm = null
                    cont.resume(false)
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    // ── live screen ────────────────────────────────────────────────────────
    // The one feature that needs its own system dialog rather than a runtime
    // permission. The user is asked by Android itself, every single time, and a
    // refusal is final: there is no second path that starts a projection, and no
    // remote command can reach this. The owner can see the screen, but never take
    // it without the person holding the phone agreeing on screen.

    private val screenLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                ScreenShareService.start(this, result.resultCode, data)
                toast("اشتراک صفحه روشن شد. یک اعلان دائمی در نوار اعلان‌ها می‌ماند.")
            } else {
                // Declining is a normal answer, not an error to shout about.
                toast("اشتراک صفحه روشن نشد.")
            }
            render()
        }

    private fun toggleScreenShare() {
        if (ScreenShareService.running) {
            ScreenShareService.stop(this)
            toast("اشتراک صفحه خاموش شد.")
            render()
            return
        }
        if (!ScreenShareService.supported()) {
            // Said plainly rather than opening a dialog that could only ever
            // produce nothing on this OS version.
            toast(getString(R.string.screen_needs_android8))
            return
        }
        // From Android 13 the projection's mandatory notification is invisible
        // without this permission, and from Android 14 the system tears the
        // projection down when the notification cannot be posted. Asking here,
        // where the button is, is the one place it can honestly be explained.
        scope.launch {
            val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ask(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            }
            render()
            if (!notifOk) {
                toast(getString(R.string.perm_blocked) + " " + getString(R.string.perm_notifications_why))
                return@launch
            }
            try {
                val mgr = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                    as android.media.projection.MediaProjectionManager
                screenLauncher.launch(mgr.createScreenCaptureIntent())
            } catch (t: Throwable) {
                toast("این گوشی اجازه‌ی اشتراک صفحه نداد: ${t.message}")
            }
        }
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        bindViews()
        wire()
    }

    override fun onResume() {
        super.onResume()
        // The admin grant and the lost-mode flag both change outside this app.
        render()
    }

    private fun bindViews() {
        headerTitle = findViewById(R.id.headerTitle)
        headerSub = findViewById(R.id.headerSub)
        chipRow = findViewById(R.id.chipRow)
        inputPhone = findViewById(R.id.inputPhone)
        phoneError = findViewById(R.id.phoneError)
        operatorSpinner = findViewById(R.id.spinnerOperator)
        btnCheckIn = findViewById(R.id.btnCheckIn)
        btnCheckOut = findViewById(R.id.btnCheckOut)
        btnSend = findViewById(R.id.btnSend)
        attStatus = findViewById(R.id.attStatus)
        screenStatus = findViewById(R.id.screenStatus)
        btnScreen = findViewById(R.id.btnScreen)
        cardCapture = findViewById(R.id.cardCapture)
        preview = findViewById(R.id.preview)
        photoRow = findViewById(R.id.photoRow)
        stepRow = findViewById(R.id.stepRow)
        capStatus = findViewById(R.id.capStatus)
        btnAbort = findViewById(R.id.btnAbort)
        permSummary = findViewById(R.id.permSummary)
        permContainer = findViewById(R.id.permContainer)
        batteryBar = findViewById(R.id.batteryBar)
        specContainer = findViewById(R.id.specContainer)
        inputServer = findViewById(R.id.inputServer)
        serverStatus = findViewById(R.id.serverStatus)
        btnRegister = findViewById(R.id.btnRegister)
        adminStatus = findViewById(R.id.adminStatus)
        btnAdmin = findViewById(R.id.btnAdmin)

        inputPhone.setText(prefs.phone)
        inputServer.setText(prefs.serverUrl)
        setKind(KIND_IN)
    }

    private fun wire() {
        // Persian digits are folded to ASCII as they are typed, so the stored
        // number and the server's normalisation only ever see one shape.
        inputPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString().orEmpty()
                val folded = Persian.toAsciiDigits(raw)
                if (folded != raw) {
                    // Everything before the first character that is not a digit
                    // survives the fold; the rest becomes ASCII, so the caret
                    // stays where the person was typing.
                    val keep = raw.indexOfFirst { !Persian.toAsciiDigits(it.toString()).matches(Regex("[0-9]")) }
                        .let { if (it < 0) folded.length else it }
                    inputPhone.setText(folded)
                    inputPhone.setSelection(keep.coerceIn(0, folded.length))
                }
                if (phoneError.visibility == View.VISIBLE) validatePhone(showError = false)
            }
        })

        operatorList = buildOperatorList()
        operatorSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, operatorList,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        operatorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.operator = operatorList.getOrNull(pos).orEmpty()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        selectOperator(prefs.operator.ifBlank { guessOperator() })

        btnCheckIn.setOnClickListener { setKind(KIND_IN) }
        btnCheckOut.setOnClickListener { setKind(KIND_OUT) }
        btnSend.setOnClickListener { onSendTapped() }
        btnAbort.setOnClickListener { abortRequested = true }

        btnRegister.setOnClickListener { registerDevice() }
        btnAdmin.setOnClickListener { requestAdmin() }
        btnScreen.setOnClickListener { toggleScreenShare() }
        findViewById<MaterialButton>(R.id.btnSaveServer).setOnClickListener { saveServer() }
        findViewById<MaterialButton>(R.id.btnTestServer).setOnClickListener { testServer() }
    }

    /**
     * The screen-share row says which of the two states it is in.
     *
     * This has to be readable at a glance and not only from inside the app: while
     * sharing is on, the person should be able to tell from the notification shade
     * that it is, and turn it off without opening anything.
     */
    private fun renderScreen() {
        val on = ScreenShareService.running
        screenStatus.text = getString(if (on) R.string.screen_on else R.string.screen_off)
        screenStatus.setTextColor(color(if (on) R.color.prf_warn else R.color.prf_text_dim))
        btnScreen.setText(if (on) R.string.screen_toggle_off else R.string.screen_toggle)
    }

    // ── permissions card ──────────────────────────────────────────────────
    //
    // The attendance button already asks for everything it needs, one dialog at
    // a time, in the order below. This card exists for the two things that
    // button cannot do: showing what is granted without pressing anything, and
    // fixing a single permission without redoing the rest of the capture.
    //
    // Every state here is read from the OS on every render. Nothing is cached,
    // because a cached answer here is the one that lies.

    private fun renderPerms() {
        permContainer.removeAllViews()
        var missing = 0

        for (spec in PERM_ROWS) {
            // A permission the OS on this phone does not have at all — no
            // camera, no notification runtime on API 32 — is drawn as such
            // rather than as a denial, which is a different thing.
            if (!spec.exists(this)) {
                permContainer.addView(permRow(spec, state = PermState.NOT_SUPPORTED))
                continue
            }
            val state = stateOf(spec.permission)
            if (state != PermState.GRANTED) missing++
            permContainer.addView(permRow(spec, state))
        }

        permSummary.text = if (missing == 0) {
            getString(R.string.perm_all_ok)
        } else {
            getString(R.string.perm_missing_count, missing)
        }
        permSummary.setTextColor(color(if (missing == 0) R.color.prf_ok else R.color.prf_warn))
    }

    /**
     * The OS state, never the result map of some earlier request.
     *
     * "Never asked" and "asked once and declined" are different rows even though
     * the fix is the same press, because the second one is a decision the person
     * made and may want to see they made. Collapsing them is what made an earlier
     * build of this app unreadable: every row said the same thing, so there was
     * no way to tell which buttons were worth pressing.
     */
    private fun stateOf(permission: String): PermState = when {
        Permissions.isGranted(this, permission) -> PermState.GRANTED
        // Asked, declined, and the system will still show the dialog again.
        Permissions.askedBefore(this, permission) && !Permissions.isPermanentlyDenied(this, permission) ->
            PermState.DENIED
        // Asked, declined, and the system will not ask again: only settings works.
        Permissions.isPermanentlyDenied(this, permission) -> PermState.BLOCKED
        else -> PermState.NOT_ASKED
    }

    private fun permRow(spec: PermSpec, state: PermState): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(8)
            setPadding(0, p, 0, p)
        }

        // Named `labels`, not `text`: a local called `text` shadows the
        // TextView property inside these apply blocks, and `text = …` would then
        // be an assignment to a val instead of the view's text.
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = getString(spec.label)
            textSize = 13f
            setTextColor(color(R.color.prf_text))
        })
        labels.addView(TextView(this).apply {
            text = when (state) {
                PermState.GRANTED -> getString(R.string.perm_granted)
                PermState.NOT_ASKED -> getString(R.string.perm_not_asked)
                PermState.DENIED -> getString(R.string.perm_denied)
                PermState.BLOCKED -> getString(R.string.perm_blocked)
                PermState.NOT_SUPPORTED -> getString(R.string.perm_not_available)
            }
            textSize = 11f
            setTextColor(color(
                when (state) {
                    PermState.GRANTED -> R.color.prf_ok
                    PermState.NOT_ASKED -> R.color.prf_warn
                    else -> R.color.prf_bad
                },
            ))
        })
        labels.addView(TextView(this).apply {
            text = getString(spec.why)
            textSize = 10f
            setTextColor(color(R.color.prf_text_dim))
        })

        val action = when (state) {
            PermState.GRANTED, PermState.NOT_SUPPORTED -> null
            PermState.BLOCKED -> MaterialButton(this).apply {
                setText(R.string.att_open_settings)
                isAllCaps = false
                textSize = 11f
                setOnClickListener { Permissions.openAppSettings(this@MainActivity) }
            }
            else -> MaterialButton(this).apply {
                setText(R.string.perm_grant)
                isAllCaps = false
                textSize = 11f
                setOnClickListener { grantOne(spec.permission) }
            }
        }

        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        action?.let {
            val p = dp(8)
            row.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40),
            ).apply { marginStart = p })
        }
        return row
    }

    private fun grantOne(permission: String) {
        scope.launch {
            Permissions.markAsked(this@MainActivity, permission)
            ask(permission)
            render()
        }
    }

    // ── render ─────────────────────────────────────────────────────────────

    private fun render() {
        renderHeader()
        renderScreen()
        renderPerms()
        renderSpecs()
        renderAdmin()
        val ready = prefs.registered && prefs.deviceKey.isNotEmpty()
        btnSend.isEnabled = ready && captureJob == null
        btnRegister.isEnabled = !ready && captureJob == null
        if (!ready) {
            // A greyed-out primary button with no explanation is the thing this
            // app was hardest to read, so the state says what is missing and
            // where the fix is.
            attStatus.text = getString(R.string.att_need_register) + "\n" +
                getString(R.string.att_missing_register)
            attStatus.setTextColor(color(R.color.prf_bad))
        } else if (captureJob == null && attStatus.text.isNullOrEmpty()) {
            attStatus.text = ""
        }
    }

    private fun renderHeader() {
        headerTitle.text = DeviceCollector.titleLine(this)
        chipRow.removeAllViews()
        val registered = prefs.registered && prefs.deviceKey.isNotEmpty()
        chip(getString(if (registered) R.string.header_registered else R.string.header_not_registered),
            if (registered) R.color.prf_ok else R.color.prf_text_dim)
        val admin = PrfDeviceAdminReceiver.isAdminActive(this)
        if (admin) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val owner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                dpm.isDeviceOwnerApp(packageName)
            chip(getString(if (owner) R.string.header_owner else R.string.header_admin),
                if (owner) R.color.prf_accent else R.color.prf_text_dim)
        }
        if (prefs.lostMode) chip(getString(R.string.header_lost), R.color.prf_bad)
        val last = prefs.lastCheckIn
        headerSub.text = if (last > 0) {
            getString(R.string.header_last_report, relative(last))
        } else {
            getString(R.string.app_subtitle)
        }
    }

    private fun chip(label: String, colorRes: Int) {
        val tv = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(color(colorRes))
            background = getDrawable(R.drawable.chip_bg)
            val p = dp(10)
            setPadding(p, dp(5), p, dp(5))
        }
        chipRow.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) })
    }

    private fun renderAdmin() {
        val active = PrfDeviceAdminReceiver.isAdminActive(this)
        adminStatus.text = getString(if (active) R.string.adm_enabled else R.string.adm_need_admin)
        adminStatus.setTextColor(color(if (active) R.color.prf_ok else R.color.prf_warn))
        btnAdmin.visibility = if (active) View.GONE else View.VISIBLE
    }

    /**
     * The device facts, in four groups. A value the OS will not hand over without
     * a permission this app does not hold is simply not in the map, and so is not
     * drawn — the screen never shows an empty row for something it did not read.
     */
    private fun renderSpecs() {
        val specs = DeviceCollector.specs(this)
        specContainer.removeAllViews()
        specContainer.addView(
            batteryRow(specs),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        for ((group, labelRes) in GROUPS) {
            val fields = specs.filterKeys { it in group }
            if (fields.isEmpty()) continue
            val title = TextView(this).apply {
                text = getString(labelRes)
                textSize = 12f
                setTextColor(color(R.color.prf_accent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val p = dp(14)
                setPadding(0, p, 0, dp(4))
            }
            specContainer.addView(title)
            for ((key, value) in fields) specContainer.addView(specRow(key, value))
        }
    }

    private fun batteryRow(specs: Map<String, String>): View {
        val pct = specs["battery_percent"]?.toIntOrNull() ?: 0
        batteryBar.progress = pct.coerceIn(0, 100)
        val plugged = specs["battery_plugged"].orEmpty()
        val text = buildString {
            append(getString(R.string.spec_battery))
            append(" · ")
            append(Persian.toPersianDigits(pct.toString()))
            append("٪")
            if (plugged.isNotEmpty() && plugged != "not charging") {
                append(" · ")
                append(plugged)
            }
        }
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(color(R.color.prf_text))
            val p = dp(8)
            setPadding(0, dp(10), 0, p)
        }
    }

    private fun specRow(key: String, value: String): View {
        val label = getString(labelRes(key))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = dp(6)
            setPadding(0, p, 0, p)
        }
        val k = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(color(R.color.prf_text_dim))
        }
        val v = TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(color(R.color.prf_text))
            gravity = Gravity.START
        }
        row.addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f))
        row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f))
        return row
    }

    private fun labelRes(key: String): Int = when (key) {
        "battery_percent" -> R.string.lbl_battery_percent
        "battery_plugged" -> R.string.lbl_battery_plugged
        "battery_health" -> R.string.lbl_battery_health
        "battery_temp" -> R.string.lbl_battery_temp
        "android_release" -> R.string.lbl_android_release
        "android_sdk" -> R.string.lbl_android_sdk
        "build_id" -> R.string.lbl_build_id
        "security_patch" -> R.string.lbl_security_patch
        "app_version" -> R.string.lbl_app_version
        "kernel" -> R.string.lbl_kernel
        "manufacturer" -> R.string.lbl_manufacturer
        "model" -> R.string.lbl_model
        "brand" -> R.string.lbl_brand
        "device" -> R.string.lbl_device
        "board" -> R.string.lbl_board
        "cpu_abi" -> R.string.lbl_cpu_abi
        "cpu_cores" -> R.string.lbl_cpu_cores
        "ram_total" -> R.string.lbl_ram_total
        "ram_available" -> R.string.lbl_ram_available
        "storage_total" -> R.string.lbl_storage_total
        "storage_free" -> R.string.lbl_storage_free
        "screen" -> R.string.lbl_screen
        "density_dpi" -> R.string.lbl_density_dpi
        "operator" -> R.string.lbl_operator
        "sim_state" -> R.string.lbl_sim_state
        "network_type" -> R.string.lbl_network_type
        "locale" -> R.string.lbl_locale
        "timezone" -> R.string.lbl_timezone
        "uptime" -> R.string.lbl_uptime
        else -> R.string.spec_missing
    }

    // ── identity ───────────────────────────────────────────────────────────

    private fun setKind(next: String) {
        kind = next
        val active = color(R.color.prf_accent)
        val idle = color(R.color.prf_text_dim)
        btnCheckIn.setTextColor(if (kind == KIND_IN) active else idle)
        btnCheckOut.setTextColor(if (kind == KIND_OUT) active else idle)
        btnCheckIn.background = tintDrawable(R.drawable.chip_bg, if (kind == KIND_IN) R.color.prf_accent_dim else R.color.prf_surface_hi)
        btnCheckOut.background = tintDrawable(R.drawable.chip_bg, if (kind == KIND_OUT) R.color.prf_accent_dim else R.color.prf_surface_hi)
    }

    private fun buildOperatorList(): List<String> =
        listOf(getString(R.string.id_operator_auto)) +
            Persian.OPERATORS.map { it.first } +
            getString(R.string.id_operator_other)

    private fun guessOperator(): String? = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        Persian.operatorFromMccMnc(tm.networkOperator)
    } catch (t: Throwable) { null }

    private fun selectOperator(name: String?) {
        val index = operatorList.indexOfFirst { it == name }.takeIf { it >= 0 } ?: 0
        operatorSpinner.setSelection(index, false)
    }

    private fun validatePhone(showError: Boolean): Boolean {
        val text = inputPhone.text.toString()
        val ok = text.isBlank() || Persian.normalizePhone(text) != null
        phoneError.visibility = if (!ok && showError) View.VISIBLE else View.GONE
        return ok
    }

    // ── the attendance run ─────────────────────────────────────────────────

    private fun onSendTapped() {
        if (captureJob != null) return
        if (!validatePhone(showError = true)) {
            attStatus.text = getString(R.string.id_phone_bad)
            attStatus.setTextColor(color(R.color.prf_bad))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_body)
            .setNegativeButton(R.string.consent_no, null)
            .setPositiveButton(R.string.consent_yes) { _, _ -> startAttendance() }
            .show()
    }

    private fun startAttendance() {
        abortRequested = false
        captureJob = scope.launch { runAttendance() }
    }

    private suspend fun runAttendance() {
        val phone = Persian.normalizePhone(inputPhone.text.toString()).orEmpty()
        prefs.phone = Persian.toAsciiDigits(inputPhone.text.toString())
        prefs.operator = operatorList.getOrNull(operatorSpinner.selectedItemPosition).orEmpty()

        showCaptureCard(true)
        val dir = File(cacheDir, "attendance").apply { mkdirs() }
        // Anything left here belongs to a record that was never sent. Clearing it
        // means a stale capture can never be attached to today's record.
        dir.listFiles()?.forEach { it.delete() }

        val engine = AutoCapture(this, this)
        val photos = mutableListOf<String>()
        var voice: String? = null
        var frontDone = false
        var backDone = false
        var voiceDone = false
        val problems = mutableListOf<String>()
        var location: com.prf.security.data.LocationReport? = null

        try {
            // ── camera permission ──────────────────────────────────────────
            step(getString(R.string.cap_permission_camera), 1, 1, running = true)
            val camOk = ask(Manifest.permission.CAMERA)
            step(getString(R.string.cap_permission_camera), 1, 1, running = false, ok = camOk)
            if (!camOk) {
                problems += if (Permissions.canAskAgain(this, Manifest.permission.CAMERA)) {
                    getString(R.string.att_perm_denied_camera)
                } else {
                    getString(R.string.att_perm_settings)
                }
            }

            if (camOk) {
                // ── three from the front ───────────────────────────────────
                val hasFront = engine.hasCamera(front = true)
                step(getString(R.string.cap_front), 0, PHOTOS_PER_LENS, running = hasFront, ok = !hasFront)
                if (!hasFront) {
                    problems += getString(R.string.att_no_camera_front)
                } else {
                    engine.bind(preview, front = true)
                    capStatus.setText(R.string.cap_wait)
                    delay(PREVIEW_SETTLE_MS)
                    try {
                        engine.capture(preview, dir, "front", PHOTOS_PER_LENS) { i, total, file ->
                            showShot(i, total, file)
                        }
                        frontDone = true
                        step(getString(R.string.cap_front), PHOTOS_PER_LENS, PHOTOS_PER_LENS, running = false, ok = true)
                        capStatus.setText(R.string.cap_done_front)
                    } catch (t: Throwable) {
                        Log.w(TAG, "front capture: ${t.message}")
                        problems += getString(R.string.att_capture_failed, t.message ?: "unknown")
                    }

                    // ── three from the back ─────────────────────────────────
                    val hasBack = engine.hasCamera(front = false)
                    step(getString(R.string.cap_back), 0, PHOTOS_PER_LENS, running = hasBack, ok = !hasBack)
                    if (!hasBack) {
                        problems += getString(R.string.att_no_camera_back)
                    } else {
                        engine.bind(preview, front = false)
                        capStatus.setText(R.string.cap_wait)
                        delay(PREVIEW_SWITCH_MS)
                        try {
                            engine.capture(preview, dir, "back", PHOTOS_PER_LENS) { i, total, file ->
                                showShot(i + PHOTOS_PER_LENS, total + PHOTOS_PER_LENS, file)
                            }
                            backDone = true
                            step(getString(R.string.cap_back), PHOTOS_PER_LENS, PHOTOS_PER_LENS, running = false, ok = true)
                            capStatus.setText(R.string.cap_done_back)
                        } catch (t: Throwable) {
                            Log.w(TAG, "back capture: ${t.message}")
                            problems += getString(R.string.att_capture_failed, t.message ?: "unknown")
                        }
                    }
                }
            }
            engine.release()

            if (abortRequested) {
                showCaptureCard(false)
                dir.listFiles()?.forEach { it.delete() }
                return
            }

            // The encoded photos are what the record carries; the full-size files
            // on disk are the app's own scratch and are deleted after sending.
            // Downscaling and JPEG-encoding six photos is real work, so it happens
            // off the main thread — the step list on screen is what the user is
            // watching, and it must not freeze while this runs.
            photos += withContext(Dispatchers.IO) {
                dir.listFiles().orEmpty()
                    .filter { it.name.endsWith(".jpg") }
                    .sortedBy { it.name }
                    .mapNotNull { encodePhoto(it) }
            }

            // ── voice ──────────────────────────────────────────────────────
            step(getString(R.string.cap_permission_mic), 1, 1, running = true)
            val micOk = ask(Manifest.permission.RECORD_AUDIO)
            step(getString(R.string.cap_permission_mic), 1, 1, running = false, ok = micOk)
            if (micOk) {
                step(getString(R.string.cap_voice), 0, 1, running = true)
                try {
                    voice = recordVoice(dir)
                    voiceDone = voice != null
                    step(getString(R.string.cap_voice), 1, 1, running = false, ok = voiceDone)
                    if (voiceDone) {
                        capStatus.setText(R.string.cap_done_voice)
                    } else {
                        problems += getString(R.string.att_no_mic)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "voice: ${t.message}")
                    problems += getString(R.string.att_no_mic)
                    step(getString(R.string.cap_voice), 0, 1, running = false, ok = false)
                }
            } else {
                problems += if (Permissions.canAskAgain(this, Manifest.permission.RECORD_AUDIO)) {
                    getString(R.string.att_perm_denied_mic)
                } else {
                    getString(R.string.att_perm_settings)
                }
            }

            if (abortRequested) {
                showCaptureCard(false)
                dir.listFiles()?.forEach { it.delete() }
                return
            }

            // ── location ───────────────────────────────────────────────────
            // Asked here, at the moment the record is being made, for the same
            // reason the camera and the microphone are: it is the user's own
            // press that started this, and a declined location is recorded as
            // declined rather than as a silent omission.
            step(getString(R.string.cap_permission_location), 1, 1, running = true)
            val locOk = ask(Permissions.LOCATION)
            step(getString(R.string.cap_permission_location), 1, 1, running = false, ok = locOk)
            if (locOk) {
                step(getString(R.string.cap_locating), 0, 1, running = true)
                val fix = collector.awaitFix(LOCATION_WAIT_MS)
                step(getString(R.string.cap_locating), 1, 1, running = false, ok = fix != null)
                if (fix == null) {
                    problems += getString(R.string.cap_no_location)
                } else {
                    location = locationReport(fix)
                    capStatus.setText(R.string.cap_located)
                }
            } else {
                problems += getString(R.string.att_perm_denied_location)
            }

            if (abortRequested) {
                showCaptureCard(false)
                dir.listFiles()?.forEach { it.delete() }
                return
            }

            // ── send ──────────────────────────────────────────────────────
            step(getString(R.string.cap_sending), 0, 1, running = true)
            val ok = submit(phone, photos, voice, location)
            step(getString(R.string.cap_sending), 1, 1, running = false, ok = ok)

            if (ok) {
                val voiceText = if (voiceDone) {
                    getString(R.string.cap_voice_seconds, VOICE_SECONDS)
                } else {
                    getString(R.string.att_voice_none)
                }
                // The record is sent either way, so the status says what is
                // actually in it rather than claiming a full capture.
                attStatus.text = if (frontDone && backDone && voiceDone) {
                    getString(R.string.att_sent)
                } else {
                    getString(R.string.att_partial, photos.size, voiceText)
                }
                attStatus.setTextColor(color(R.color.prf_ok))
                prefs.lastCheckIn = System.currentTimeMillis()
            } else {
                attStatus.text = getString(R.string.att_send_failed, getString(R.string.att_server_down))
                attStatus.setTextColor(color(R.color.prf_bad))
            }
            // A partial capture is still the truth, so it is kept on the card
            // rather than dropped: the problems are what the user needs to know.
            for (p in problems) Log.i(TAG, p)
            if (problems.isNotEmpty()) toast(problems.first())
        } catch (t: Throwable) {
            Log.e(TAG, "attendance run failed", t)
            attStatus.text = getString(R.string.att_send_failed, t.message ?: "")
            attStatus.setTextColor(color(R.color.prf_bad))
        } finally {
            engine.release()
            showCaptureCard(false)
            captureJob = null
            render()
        }
    }

    private suspend fun submit(
        phone: String,
        photos: List<String>,
        voice: String?,
        location: com.prf.security.data.LocationReport?,
    ): Boolean {
        val info = withContext(Dispatchers.IO) { DeviceCollector.specs(this@MainActivity) }
        val payload = AttendancePayload(
            kind = kind,
            phone = phone,
            operator = prefs.operator,
            info = info,
            photos = photos,
            voice = voice,
            location = location,
        )
        return MdmApi(prefs.serverUrl, prefs.deviceKey).attendance(payload)
    }

    /**
     * The location that was actually read during this run.
     *
     * A cached fix from an earlier moment is not what this record should claim,
     * so the fix the run waited for is the only one that goes in. A phone that
     * has been indoors the whole time simply records no location, and the step
     * list says so.
     */
    private fun locationReport(fix: android.location.Location) = run {
        val p = collector.toPayload(fix)
        com.prf.security.data.LocationReport(
            id = "att-${System.currentTimeMillis()}",
            androidId = Prefs.androidId(this),
            timestampMs = System.currentTimeMillis(),
            maps = p["maps"] ?: "",
            geo = p["geo"] ?: "",
            plusCode = p["plus_code"] ?: "",
            raw = p["raw"] ?: "",
        )
    }

    /** Records a fixed-length voice note and returns it base64-encoded. */
    private suspend fun recordVoice(dir: File): String? {
        val file = File(dir, "voice.m4a")
        // Android 12 requires the Context constructor; the no-arg one is
        // deprecated there but is the only form that exists below API 31.
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (t: Throwable) {
            runCatching { rec.release() }
            throw IllegalStateException("the microphone would not start", t)
        }
        // A fixed length, started and stopped by the app: the user does not have
        // to press anything a second time, and the recording cannot run away.
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < VOICE_SECONDS * 1000L) {
            val s = ((SystemClock.elapsedRealtime() - started) / 1000L).toInt()
            capStatus.text = getString(R.string.cap_recording, s)
            delay(250)
        }
        // stop() throws when the clip is too short to encode; at this length it
        // cannot be, but a throw here must not lose the photos already taken.
        runCatching { rec.stop() }
        rec.release()
        if (!file.exists() || file.length() == 0L) return null
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    // ── capture card ───────────────────────────────────────────────────────

    private fun showCaptureCard(show: Boolean) {
        cardCapture.visibility = if (show) View.VISIBLE else View.GONE
        btnSend.isEnabled = !show
        btnAbort.isEnabled = show
        if (!show) {
            photoRow.removeAllViews()
            stepRow.removeAllViews()
        }
    }

    private fun step(label: String, index: Int, total: Int, running: Boolean, ok: Boolean = true) {
        val text = when {
            running && total > 1 -> getString(R.string.cap_step_format, label, index.coerceAtLeast(1), total)
            running -> label
            else -> label
        }
        val existing = stepRow.getChildAt(stepRow.childCount - 1)
        val tv = if (existing is TextView && existing.tag == label) existing else TextView(this).apply {
            tag = label
            textSize = 12f
            val p = dp(4)
            setPadding(0, p, 0, p)
        }
        tv.text = when {
            running -> "◾  $text"
            ok -> "✅  $text"
            else -> "⛔  $text"
        }
        tv.setTextColor(color(if (running) R.color.prf_accent else if (ok) R.color.prf_ok else R.color.prf_bad))
        if (tv.parent == null) stepRow.addView(tv)
    }

    private fun showShot(index: Int, total: Int, file: File) {
        val bmp = decodeScaled(file, THUMB_EDGE)
        val size = (THUMB_EDGE * resources.displayMetrics.density).toInt()
        val pad = (6 * resources.displayMetrics.density).toInt()
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            contentDescription = getString(R.string.app_name)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = pad }
        }
        photoRow.addView(iv)
        capStatus.text = getString(R.string.cap_shot_format, index, total)
    }

    private fun encodePhoto(file: File): String? = try {
        val bmp = decodeScaled(file, MAX_PHOTO_EDGE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (t: Throwable) {
        Log.w(TAG, "could not encode ${file.name}: ${t.message}")
        null
    }

    private fun decodeScaled(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalStateException("could not decode ${file.name}")
    }

    // ── server and admin ───────────────────────────────────────────────────

    private fun saveServer() {
        val url = inputServer.text.toString().trim()
        prefs.serverUrl = url
        serverStatus.text = getString(R.string.srv_saved)
        serverStatus.setTextColor(color(R.color.prf_ok))
    }

    private fun testServer() {
        saveServer()
        serverStatus.text = getString(R.string.srv_testing)
        serverStatus.setTextColor(color(R.color.prf_text_dim))
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val r = okhttp3.Request.Builder().url(prefs.serverUrl.trimEnd('/') + "/api/admin/me").build()
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    client.newCall(r).execute().use { resp -> "HTTP " + resp.code }
                } catch (t: Throwable) { t.message ?: "network error" }
            }
            // Any HTTP answer means the address is a live server; 401 is the
            // correct one, because the panel is not signed in from this app.
            if (result.startsWith("HTTP 2") || result == "HTTP 401") {
                serverStatus.text = getString(R.string.srv_ok)
                serverStatus.setTextColor(color(R.color.prf_ok))
            } else {
                serverStatus.text = getString(R.string.srv_fail, result)
                serverStatus.setTextColor(color(R.color.prf_bad))
            }
        }
    }

    private fun registerDevice() {
        btnRegister.isEnabled = false
        btnRegister.setText(R.string.srv_registering)
        scope.launch {
            val androidId = DeviceCollector.getAndroidId(this@MainActivity)
            Prefs.cacheAndroidId(this@MainActivity, androidId)
            val hardware = withContext(Dispatchers.IO) { DeviceCollector.collect(this@MainActivity) }
            val res = withContext(Dispatchers.IO) {
                MdmApi(prefs.serverUrl, "").register(
                    androidId, hardware, prefs.label.ifBlank { android.os.Build.MODEL },
                )
            }
            if (res != null && res.deviceKey.isNotEmpty()) {
                prefs.deviceKey = res.deviceKey
                prefs.registered = true
                prefs.lostMode = res.lostMode
                CryptoStore(this@MainActivity).deviceKey = res.deviceKey
                PolicyEnforcer.apply(this@MainActivity, res.policy)
                OwnershipWorker.schedulePeriodic(this@MainActivity)
                OwnershipWorker.runNow(this@MainActivity)
                serverStatus.text = getString(R.string.srv_registered)
                serverStatus.setTextColor(color(R.color.prf_ok))
            } else {
                serverStatus.text = getString(R.string.srv_register_failed, getString(R.string.att_server_down))
                serverStatus.setTextColor(color(R.color.prf_bad))
                btnRegister.isEnabled = true
            }
            btnRegister.setText(R.string.srv_register)
            render()
        }
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                PrfDeviceAdminReceiver.componentName(this@MainActivity),
            )
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.adm_explanation))
        }
        startActivity(intent)
    }

    // ── small helpers ──────────────────────────────────────────────────────

    private fun relative(ms: Long): String {
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> getString(R.string.rel_seconds, Persian.toPersianDigits(s.toString()))
            s < 3600 -> getString(R.string.rel_minutes, Persian.toPersianDigits((s / 60).toString()))
            s < 86400 -> getString(R.string.rel_hours, Persian.toPersianDigits((s / 3600).toString()))
            else -> getString(R.string.rel_days, Persian.toPersianDigits((s / 86400).toString()))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun tintDrawable(res: Int, colorRes: Int) =
        getDrawable(res)?.mutate()?.also {
            it.setTint(color(colorRes))
        }

    private fun color(res: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(res)
        else resources.getColor(res)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        captureJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PRF.Main"
        const val KIND_IN = "check_in"
        const val KIND_OUT = "check_out"

        /** Three from each lens, which is exactly the server's maxPhotos of 6. */
        const val PHOTOS_PER_LENS = 3
        const val VOICE_SECONDS = 8
        const val MAX_PHOTO_EDGE = 1600
        const val THUMB_EDGE = 72
        const val JPEG_QUALITY = 80
        const val PREVIEW_SETTLE_MS = 1200L
        const val PREVIEW_SWITCH_MS = 1600L

        /**
         * How long a single-shot location request may hold up the run.
         *
         * Short on purpose. A phone indoors with no GPS will not answer at all,
         * and the record is still worth sending without a location, so waiting
         * longer trades a useful capture for a coordinate.
         */
        const val LOCATION_WAIT_MS = 6000L

        /** One row of the permissions card, in the order the capture asks them. */
        val PERM_ROWS = listOf(
            PermSpec(
                Manifest.permission.CAMERA, R.string.perm_camera, R.string.perm_camera_why,
            ) { pkg, pm -> pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
            PermSpec(
                Manifest.permission.RECORD_AUDIO, R.string.perm_mic, R.string.perm_mic_why,
            ) { pkg, pm -> pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) },
            PermSpec(
                Permissions.LOCATION, R.string.perm_location, R.string.perm_location_why,
            ) { pkg, pm -> pm.hasSystemFeature(PackageManager.FEATURE_LOCATION) },
            PermSpec(
                Manifest.permission.POST_NOTIFICATIONS, R.string.perm_notifications,
                R.string.perm_notifications_why,
            ) { _, _ -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
        )

        val GROUPS = listOf(
            setOf("battery_health", "battery_temp") to R.string.spec_battery,
            setOf("android_release", "android_sdk", "build_id", "security_patch", "app_version", "kernel") to R.string.spec_software,
            setOf(
                "manufacturer", "model", "brand", "device", "board", "cpu_abi", "cpu_cores",
                "ram_total", "ram_available", "storage_total", "storage_free", "screen", "density_dpi",
            ) to R.string.spec_hardware,
            setOf("operator", "sim_state", "network_type", "locale", "timezone", "uptime") to R.string.spec_network,
        )
    }

    /**
     * One permission as the card needs it: what to ask for, what to call it, what
     * it is for, and how to tell whether this phone has it at all.
     *
     * The last part is what keeps a tablet with no camera from showing a camera
     * row that can only ever read "declined".
     */
    private class PermSpec(
        val permission: String,
        val label: Int,
        val why: Int,
        private val supported: (String, PackageManager) -> Boolean,
    ) {
        fun exists(ctx: Context): Boolean =
            runCatching { supported(ctx.packageName, ctx.packageManager) }.getOrDefault(true)
    }

    private enum class PermState { GRANTED, NOT_ASKED, DENIED, BLOCKED, NOT_SUPPORTED }
}
