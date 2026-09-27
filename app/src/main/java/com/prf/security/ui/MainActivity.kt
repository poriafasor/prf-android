package com.prf.security.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.prf.security.R
import com.prf.security.capture.AutoCapture
import com.prf.security.data.AttendancePayload
import com.prf.security.data.DeviceCollector
import com.prf.security.data.LocationReport
import com.prf.security.location.LocationCollector
import com.prf.security.mdm.AdminGate
import com.prf.security.mdm.OwnershipWorker
import com.prf.security.mdm.PolicyEnforcer
import com.prf.security.mdm.PolicyWatchService
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import com.prf.security.perm.Permissions
import com.prf.security.screen.ScreenShareService
import com.prf.security.util.Persian
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.pow
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
 * The whole app, on one screen, driven by one button.
 *
 * v2.0.0 replaces the nine-section settings page. Every section on it was a
 * button, and the complaint about the app was not that the buttons were ugly —
 * it was that pressing them in the right order was the only way to make anything
 * happen, and that pressing the wrong one did nothing at all.
 *
 * So the run is now one sequence, started by one press, and the order inside it
 * is the order the platform requires:
 *
 *   1. the number and the carrier, which only the person holding the phone knows
 *   2. the device-admin grant, because every remote control depends on it
 *   3. the runtime permissions, one system dialog at a time
 *   4. registration with the server, which is what the rest is reported through
 *   5. the capture — photos, a voice note, a location — and the send
 *
 * Steps 2 to 5 are invisible. There is one status line, and it says what
 * actually happened at each stage, including the parts that did not.
 *
 * **What cannot be made automatic, and why it is not pretended otherwise.**
 * Android requires a person to agree to two things and no app can decide for
 * them: the device-admin grant (a system dialog the user activates) and every
 * runtime permission (a system dialog the user allows). The run asks for both and
 * then continues with whatever it was given, reporting a refusal as a refusal.
 * The third thing is the device-owner role, which is not a dialog at all — it is
 * a one-time provisioning step on a phone that has no accounts on it, and it is
 * the only thing that makes app suspension work. That is offered as a button
 * rather than hidden, because on an admin-only phone the panel's "lock the
 * gallery" is refused by the OS and no retrying will change it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var headerSub: TextView
    private lateinit var gateStatus: TextView
    private lateinit var inputPhone: EditText
    private lateinit var phoneError: TextView
    private lateinit var operatorSpinner: Spinner
    private lateinit var btnSend: MaterialButton
    private lateinit var attStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnAdmin: MaterialButton
    private lateinit var btnOwner: MaterialButton
    private lateinit var btnScreen: MaterialButton

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val collector by lazy { LocationCollector(this) }
    private var runJob: Job? = null

    /**
     * How many times this activity has come back to the foreground.
     *
     * Only ever read as "is this larger than it was", which is the one question
     * about the user's answer to a system dialog that can be answered without
     * guessing. See [awaitAdminResult].
     */
    @Volatile
    private var resumeCount: Int = 0

    /**
     * Set when a run hit a permission Android will no longer ask about. The
     * status line offers the way back for exactly as long as this is true.
     */
    private var blockedPermission: Boolean = false

    private var operatorList: List<String> = emptyList()

    // ── permissions ────────────────────────────────────────────────────────
    // One permission per request, asked in a fixed order, with the decision read
    // back from the OS rather than from the result map. A batched request on
    // Android 11+ only surfaces the first dialog and returns a map missing the
    // rest, which is what made an earlier build look as if it were being denied.

    private var pendingPerm: CancellableContinuation<Boolean>? = null
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
        if (!Permissions.canAskAgain(this, permission)) return false
        return try {
            suspendCancellableCoroutine { cont ->
                lastAsked = permission
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

    /**
     * Waits for the user to come back from the device-admin dialog.
     *
     * The dialog is fired and the activity pauses; the result arrives on resume.
     * A `postDelayed` would be a guess about how long a person takes to read a
     * dialog, and a guess here is how a run carries on as if the grant had been
     * made when it had not.
     *
     * "Came back" is counted in [resumeCount], not read off `hasWindowFocus()`.
     * Window focus is a poor stand-in for it: this activity can hold focus for a
     * moment after the settings screen has already taken it, it flickers as
     * dialogs open and close over the top, and on a phone where the grant
     * screen does not cover the app at all it can stay true the whole time — so
     * the old check reported a refusal the moment the dialog was still up, and a
     * grant a few seconds later was then read as "the user refused".
     */
    private suspend fun awaitAdminResult(timeoutMs: Long): Boolean {
        if (AdminGate.level(this) != AdminGate.Level.NONE) return true
        val resumesAtStart = resumeCount
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            if (AdminGate.level(this) != AdminGate.Level.NONE) return true
            if (resumeCount > resumesAtStart) {
                // A resume with no grant is a dismissal — the user went into the
                // screen, came back, and did not enable it. But the grant is
                // committed by the system, and on some builds it lands a moment
                // after the activity comes back, so it is read once more after a
                // short settle before that is called. The grant screen is the
                // only place this run sends the activity away to, so a resume in
                // this window means one thing.
                delay(GRANT_SETTLE_MS)
                return AdminGate.level(this) != AdminGate.Level.NONE
            }
        }
        return false
    }

    /**
     * Whether a permission is held, in the sense this run cares about.
     *
     * Location is the one that is not a simple yes. From Android 12 the system
     * offers precise and approximate as two answers to the same dialog, and
     * someone who picks approximate has still agreed to share where they are —
     * a cell tower's worth, but a location. Reading the fine permission alone
     * would call that a refusal and put an empty position in the record, which
     * is a false statement about what the user agreed to.
     */
    private fun granted(permission: String): Boolean =
        Permissions.isGranted(this, permission) ||
            (permission == Permissions.LOCATION &&
                Permissions.isGranted(this, Manifest.permission.ACCESS_COARSE_LOCATION))

    // ── live screen ────────────────────────────────────────────────────────
    // The one feature that needs its own system dialog rather than a runtime
    // permission. The user is asked by Android itself, every single time, and a
    // refusal is final: there is no second path that starts a projection, and no
    // remote command can reach this.

    private val screenLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                ScreenShareService.start(this, result.resultCode, data)
                status("اشتراک صفحه روشن شد.")
            } else {
                // Declining is a normal answer, not an error to shout about.
                status("اشتراک صفحه روشن نشد.")
            }
            render()
        }

    private fun toggleScreenShare() {
        if (ScreenShareService.running) {
            ScreenShareService.stop(this)
            status("اشتراک صفحه خاموش شد.")
            render()
            return
        }
        if (!ScreenShareService.supported()) {
            status(getString(R.string.screen_needs_android8))
            return
        }
        // From Android 13 the projection's mandatory notification is invisible
        // without this permission, and from Android 14 the system tears the
        // projection down when the notification cannot be posted.
        scope.launch {
            val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ask(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            }
            render()
            if (!notifOk) return@launch
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as android.media.projection.MediaProjectionManager
                screenLauncher.launch(mgr.createScreenCaptureIntent())
            } catch (t: Throwable) {
                status("این گوشی اجازه‌ی اشتراک صفحه نداد: ${t.message}")
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
        // Registering without a press is safe: it sends the android id and the
        // hardware list, opens no camera and reads no microphone. It is also the
        // thing that has to be in place before any later step can report
        // anything, so waiting for the button would only delay it.
        if (!prefs.registered || prefs.deviceKey.isEmpty()) {
            scope.launch {
                registerDevice()
                render()
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        resumeCount += 1
        // The admin grant and the provisioning both change outside this app.
        render()
    }

    private fun bindViews() {
        headerSub = findViewById(R.id.headerSub)
        gateStatus = findViewById(R.id.gateStatus)
        inputPhone = findViewById(R.id.inputPhone)
        phoneError = findViewById(R.id.phoneError)
        operatorSpinner = findViewById(R.id.spinnerOperator)
        btnSend = findViewById(R.id.btnSend)
        attStatus = findViewById(R.id.attStatus)
        progress = findViewById(R.id.progress)
        btnAdmin = findViewById(R.id.btnAdmin)
        btnOwner = findViewById(R.id.btnOwner)
        btnScreen = findViewById(R.id.btnScreen)

        inputPhone.setText(prefs.phone)
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
                    val keep = raw.indexOfFirst {
                        !Persian.toAsciiDigits(it.toString()).matches(Regex("[0-9]"))
                    }.let { if (it < 0) folded.length else it }
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

        btnSend.setOnClickListener { onSendTapped() }
        btnAdmin.setOnClickListener { onAdminTapped() }
        btnOwner.setOnClickListener { onOwnerTapped() }
        btnScreen.setOnClickListener { toggleScreenShare() }

        attStatus.setOnClickListener {
            if (blockedPermission) openPermissionSettings()
        }
    }

    // ── the two grants ─────────────────────────────────────────────────────

    /**
     * Device admin, with a way out that always works.
     *
     * The add-dialog is tried first because it is one tap instead of three. If
     * the system does not resolve it — the failure mode behind "I pressed it and
     * nothing happened" — the settings list is opened instead, which is the same
     * screen a person would reach by hand. The old code fired the intent once
     * from onCreate, set a flag before the launch, and never checked the result,
     * so a single dropped dialog left the app permanently silent about it.
     */
    private fun onAdminTapped() {
        val launched = AdminGate.requestAdmin(this, getString(R.string.adm_explanation))
        if (!launched) {
            if (!AdminGate.openAdminSettings(this)) {
                status("این گوشی صفحه‌ی تنظیمات مدیریت دستگاه را ندارد. لطفاً از تنظیمات گوشی، «امنیت» و سپس «مدیران دستگاه» اقدام کنید.")
            }
        }
    }

    private fun onOwnerTapped() {
        if (!AdminGate.canOfferProvisioning(this)) {
            status("ثبت مالک دستگاه فقط در اندروید ۱۲ به بالا و روی گوشی‌ای ممکن است که هنوز حساب کاربری روی آن ساخته نشده باشد.")
            return
        }
        if (!AdminGate.openProvisioning(this)) {
            status("این گوشی صفحه‌ی ثبت مالک دستگاه را ندارد. بدون آن، قفل کردن برنامه‌ها مثل گالری روی این گوشی انجام نمی‌شود.")
        }
    }

    // ── render ─────────────────────────────────────────────────────────────

    private fun render() {
        renderGate()
        val running = runJob != null
        progress.visibility = if (running) View.VISIBLE else View.GONE
        btnSend.isEnabled = !running
        btnSend.setText(if (running) R.string.btn_busy else R.string.btn_register_number)
        btnScreen.setText(if (ScreenShareService.running) R.string.screen_toggle_off else R.string.screen_toggle)

        val last = prefs.lastCheckIn
        headerSub.text = if (last > 0) {
            getString(R.string.header_last_report, relative(last))
        } else {
            getString(R.string.app_subtitle)
        }
    }

    /**
     * The one line that says what this phone is allowed to do.
     *
     * This is not decoration. A phone that is only a device admin cannot have
     * its apps suspended, so the panel's "lock the gallery" is refused by the
     * OS and the phone reports that refusal; saying it here, on the phone,
     * before anything is pressed, is the difference between a user who knows
     * why it did not work and one who thinks the app is broken.
     */
    private fun renderGate() {
        when (AdminGate.level(this)) {
            AdminGate.Level.OWNER -> {
                gateStatus.text = getString(R.string.gate_owner_ok)
                gateStatus.setTextColor(color(R.color.prf_ok))
                btnAdmin.visibility = View.GONE
                btnOwner.visibility = View.GONE
            }
            AdminGate.Level.ADMIN -> {
                gateStatus.text = getString(R.string.gate_admin_only)
                gateStatus.setTextColor(color(R.color.prf_warn))
                btnAdmin.visibility = View.GONE
                btnOwner.visibility = View.VISIBLE
            }
            AdminGate.Level.NONE -> {
                gateStatus.text = getString(R.string.gate_none)
                gateStatus.setTextColor(color(R.color.prf_bad))
                btnAdmin.visibility = View.VISIBLE
                btnOwner.visibility = if (AdminGate.canOfferProvisioning(this)) View.VISIBLE else View.GONE
            }
        }
    }

    private fun status(msg: String) {
        attStatus.text = msg
        attStatus.setTextColor(color(R.color.prf_text_dim))
    }

    private fun status(msg: String, colorRes: Int) {
        attStatus.text = msg
        attStatus.setTextColor(color(colorRes))
    }

    /**
     * Opens the page where a permanently-denied permission can be granted again.
     *
     * This is the only recovery Android offers once a permission has been denied
     * twice, and it is not reachable from the app's own UI. Tapping the status
     * line is the least intrusive place to put it: it appears only after a run
     * has actually hit the wall, and it is the same line that told the user
     * about it.
     */
    private fun openPermissionSettings() {
        if (!AdminGate.openAppSettings(this)) {
            status(getString(R.string.run_settings_failed), R.color.prf_bad)
        }
    }

    // ── the run ────────────────────────────────────────────────────────────

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

    private fun onSendTapped() {
        if (runJob != null) return
        if (!validatePhone(showError = true)) {
            status(getString(R.string.phone_bad), R.color.prf_bad)
            return
        }
        // The consent dialog is not an option the run may skip: it is the only
        // moment the person is told, in words, that this press turns the camera
        // and the microphone on and takes six photos and a recording. Everything
        // after this press is the app doing what it said it would do.
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_body)
            .setNegativeButton(R.string.consent_no, null)
            .setPositiveButton(R.string.consent_yes) { _, _ -> startRun() }
            .show()
    }

    private fun startRun() {
        prefs.phone = Persian.toAsciiDigits(inputPhone.text.toString())
        prefs.operator = operatorList.getOrNull(operatorSpinner.selectedItemPosition).orEmpty()
        runJob = scope.launch { run() }
    }

    /**
     * The whole thing, in the order the platform requires.
     *
     * Each stage reports before and after, and a stage that does not succeed is
     * recorded and carried past rather than thrown, because a phone with no
     * camera can still register, report and be locked, and refusing to try is
     * what left a fleet of devices invisible in the panel.
     */
    private suspend fun run() {
        val notes = mutableListOf<String>()
        blockedPermission = false
        progress.visibility = View.VISIBLE
        btnSend.isEnabled = false

        try {
            // ── 1. the admin grant ─────────────────────────────────────────
            if (AdminGate.level(this) == AdminGate.Level.NONE) {
                status(getString(R.string.run_step_admin))
                AdminGate.requestAdmin(this, getString(R.string.adm_explanation))
                val got = awaitAdminResult(ADMIN_WAIT_MS)
                if (!got) {
                    notes += getString(R.string.run_admin_refused)
                }
            }
            render()

            // ── 2. permissions, one dialog at a time ──────────────────────
            for (p in PERM_ORDER) {
                if (granted(p)) continue
                if (!Permissions.canAskAgain(this, p)) {
                    // The system will not show this dialog again. Saying so is
                    // only half an answer, so the status line becomes the way
                    // back to the page where it can be granted.
                    blockedPermission = true
                    notes += getString(R.string.run_perm_blocked)
                    continue
                }
                if (!ask(p)) notes += getString(R.string.run_perm_refused)
            }
            render()
            // ── 3. registration ───────────────────────────────────────────
            if (!prefs.registered || prefs.deviceKey.isEmpty()) {
                status(getString(R.string.run_step_register))
                val ok = registerDevice()
                if (!ok) {
                    // Everything below is reported through a key this call
                    // issues, so continuing would build a record the server
                    // would reject. Said plainly instead of failing silently.
                    status(getString(R.string.run_register_failed), R.color.prf_bad)
                    return
                }
            }

            // ── 4. capture and send ───────────────────────────────────────
            captureAndSend(notes)
        } catch (t: Throwable) {
            Log.e(TAG, "run failed", t)
            status(getString(R.string.run_failed, t.message ?: ""), R.color.prf_bad)
        } finally {
            runJob = null
            render()
        }
    }

    private suspend fun captureAndSend(notes: MutableList<String>) {
        val phone = Persian.normalizePhone(inputPhone.text.toString()).orEmpty()
        val dir = File(cacheDir, "attendance").apply { mkdirs() }
        // Anything left here belongs to a record that was never sent. Clearing it
        // means a stale capture can never be attached to today's record.
        dir.listFiles()?.forEach { it.delete() }

        status(getString(R.string.run_step_capture))

        val engine = AutoCapture(this, this)
        val photos = mutableListOf<String>()
        var voice: String? = null
        var frontDone = false
        var backDone = false
        var location: LocationReport? = null

        try {
            if (granted(Manifest.permission.CAMERA)) {
                val hasFront = engine.hasCamera(front = true)
                if (hasFront) {
                    frontDone = takePhotos(engine, dir, front = true, PHOTOS_PER_LENS)
                }
                if (frontDone) {
                    val hasBack = engine.hasCamera(front = false)
                    if (hasBack) backDone = takePhotos(engine, dir, front = false, PHOTOS_PER_LENS)
                }
            } else {
                notes += getString(R.string.att_perm_denied_camera)
            }
            engine.release()

            // One lens is enough. Requiring both would throw away three good
            // photos and send nothing, on every phone that has only one camera
            // or whose other one is busy — the record is the photos the phone
            // could actually take, not the photos the plan called for.
            if (frontDone || backDone) {
                photos += withContext(Dispatchers.IO) {
                    dir.listFiles().orEmpty()
                        .filter { it.name.endsWith(".jpg") }
                        .sortedBy { it.name }
                        .mapNotNull { encodePhoto(it) }
                }
            }

            if (granted(Manifest.permission.RECORD_AUDIO)) {
                try {
                    voice = recordVoice(dir)
                } catch (t: Throwable) {
                    Log.w(TAG, "voice: ${t.message}")
                }
            }

            // A phone indoors will simply not answer this, and the record is
            // still worth sending without a coordinate, so the wait is short.
            if (granted(Permissions.LOCATION)) {
                collector.awaitFix(LOCATION_WAIT_MS)?.let { location = locationReport(it) }
            }

            status(getString(R.string.run_step_send))
            val info = withContext(Dispatchers.IO) { DeviceCollector.specs(this@MainActivity) }
            val payload = AttendancePayload(
                kind = KIND_ATTENDANCE,
                phone = phone,
                operator = prefs.operator,
                info = info,
                photos = photos,
                voice = voice,
                location = location,
            )
            val sent = MdmApi(prefs.serverUrl, prefs.deviceKey).attendance(payload)

            if (sent) {
                prefs.lastCheckIn = System.currentTimeMillis()
                // The background work that makes the panel's switches work at
                // all: the watcher polls for commands every few seconds, which
                // is the only scheduler Doze does not defer.
                PolicyWatchService.start(this@MainActivity)
                OwnershipWorker.schedulePeriodic(this@MainActivity)
                // What the record actually holds, not what the run set out to
                // take. Saying "8 seconds of voice" on a record with no voice in
                // it is the kind of small false statement the panel is not
                // supposed to tell.
                val summary = buildString {
                    append(
                        getString(
                            R.string.run_done,
                            faDigits(photos.size),
                            if (voice != null) faDigits(VOICE_SECONDS) else faDigits(0),
                        ),
                    )
                    if (location == null) append("\n").append(getString(R.string.run_no_location))
                }
                status(if (notes.isEmpty()) summary else "$summary\n" + notes.joinToString("\n"), R.color.prf_ok)
            } else {
                status(getString(R.string.att_send_failed, getString(R.string.att_server_down)), R.color.prf_bad)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
            status(getString(R.string.att_send_failed, t.message ?: ""), R.color.prf_bad)
        }
    }

    /**
     * Photos from one lens.
     *
     * There is no preview to bind: the screen this replaced showed a live camera
     * feed inside a settings page, and a run that starts with one press and ends
     * with a record does not need a window into itself. The camera is still
     * opened, the system indicator still shows, and the burst is the same three
     * shots either way.
     */
    private suspend fun takePhotos(
        engine: AutoCapture,
        dir: File,
        front: Boolean,
        count: Int,
    ): Boolean = try {
        engine.bind(null, front = front)
        // The session needs a moment after the lens is bound before the first
        // still capture is accepted; without it the first shot of the burst is
        // the one that fails.
        delay(PREVIEW_SETTLE_MS)
        engine.capture(null, dir, if (front) "front" else "back", count) { _, _, _ -> }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "capture ${if (front) "front" else "back"}: ${t.message}")
        false
    }

    /**
     * The fix, in the shape the wire and the server both expect.
     *
     * The collector hands back strings — a maps link, a geo: URI, a plus code and
     * a raw line — because those are what a person can actually open, and the
     * server stores exactly those three. Building a report out of the numeric
     * fields instead would send nothing the server knows how to render.
     */
    private fun locationReport(fix: android.location.Location): LocationReport {
        val p = collector.toPayload(fix)
        return LocationReport(
            id = "att-${System.currentTimeMillis()}",
            androidId = Prefs.androidId(this),
            timestampMs = fix.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            maps = p[LocationCollector.KEY_MAPS].orEmpty(),
            geo = p[LocationCollector.KEY_GEO].orEmpty(),
            plusCode = p[LocationCollector.KEY_PLUS].orEmpty(),
            raw = p[LocationCollector.KEY_RAW].orEmpty(),
        )
    }

    private suspend fun recordVoice(dir: File): String? {
        val out = File(dir, "voice.m4a")
        var recorder: MediaRecorder? = null
        return try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
            delay(VOICE_SECONDS * 1000L)
            recorder.stop()
            recorder.release()
            recorder = null
            Base64.encodeToString(out.readBytes(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "voice: ${t.message}")
            try { recorder?.release() } catch (ignored: Throwable) { }
            null
        } finally {
            out.delete()
        }
    }

    private fun encodePhoto(file: File): String? = try {
        val scaled = decodeScaled(file, MAX_PHOTO_EDGE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (t: Throwable) {
        Log.w(TAG, "encode ${file.name}: ${t.message}")
        null
    }

    private fun decodeScaled(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        var edge = maxOf(bounds.outWidth, bounds.outHeight)
        while (edge / 2 >= maxEdge) {
            sample *= 2
            edge /= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalStateException("decode failed for ${file.name}")
    }

    // ── registration ───────────────────────────────────────────────────────

    /**
     * Register with the server, retrying a few times.
     *
     * Returns whether the phone ended up with a key, because every later step is
     * reported through that key. This is a suspend function on purpose: the run
     * needs the answer before it captures anything, and the old version
     * reported through a callback while also kicking off a second attempt of its
     * own — so a caller could get `false` from a call that was still running.
     */
    private suspend fun registerDevice(attempt: Int = 0): Boolean {
        if (attempt > REGISTER_ATTEMPTS) return false
        return try {
            val androidId = DeviceCollector.getAndroidId(this)
            Prefs.cacheAndroidId(this, androidId)
            val hardware = withContext(Dispatchers.IO) { DeviceCollector.collect(this) }
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    MdmApi(prefs.serverUrl, "").register(
                        androidId, hardware, prefs.label.ifBlank { Build.MODEL },
                    )
                }.getOrNull()
            }
            if (res != null && res.deviceKey.isNotEmpty()) {
                prefs.deviceKey = res.deviceKey
                prefs.registered = true
                prefs.lostMode = res.lostMode
                PolicyEnforcer.apply(this, res.policy)
                PolicyWatchService.start(this)
                true
            } else {
                // 3s, 9s, 27s. Long enough that a server which is genuinely down
                // is not hammered, short enough that a phone which comes back on
                // the same walk is registered before it is put away.
                delay(3_000L * (3.0.pow(attempt.toDouble())).toLong())
                registerDevice(attempt + 1)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "register: ${t.message}")
            if (attempt > REGISTER_ATTEMPTS) {
                false
            } else {
                delay(3_000L)
                registerDevice(attempt + 1)
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun faDigits(n: Int): String = Persian.toPersianDigits(n.toString())

    private fun relative(ms: Long): String {
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> getString(R.string.rel_seconds, Persian.toPersianDigits(s.toString()))
            s < 3600 -> getString(R.string.rel_minutes, Persian.toPersianDigits((s / 60).toString()))
            s < 86400 -> getString(R.string.rel_hours, Persian.toPersianDigits((s / 3600).toString()))
            else -> getString(R.string.rel_days, Persian.toPersianDigits((s / 86400).toString()))
        }
    }

    private fun color(res: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(res)
        else resources.getColor(res)

    override fun onDestroy() {
        runJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PRF.Main"

        /**
         * The record is a single kind now.
         *
         * v1.9.0 had a check-in and a check-out toggle, which meant a press of
         * the same button produced two different records and the owner had to
         * know which one they were filing. One press, one record, no toggle.
         */
        const val KIND_ATTENDANCE = "attendance"

        const val PHOTOS_PER_LENS = 3
        const val VOICE_SECONDS = 8
        const val MAX_PHOTO_EDGE = 1600
        const val JPEG_QUALITY = 80
        const val PREVIEW_SETTLE_MS = 1200L

        /**
         * How long the run waits for the user to answer the admin dialog.
         *
         * Long enough to read and act on a system dialog, short enough that a
         * person who dismissed it is not left looking at a spinner. The run
         * continues either way; the grant is reported as refused if it is not
         * there when this expires.
         */
        const val ADMIN_WAIT_MS = 60_000L

        /**
         * How long the grant is given a moment to land after the activity comes
         * back from the grant screen. Read once, immediately, some builds report
         * the old state and the run would call a completed grant a refusal.
         */
        const val GRANT_SETTLE_MS = 700L

        /**
         * How long a single-shot location request may hold up the run. A phone
         * indoors with no GPS will not answer at all, and the record is still
         * worth sending without a location.
         */
        const val LOCATION_WAIT_MS = 6_000L

        const val REGISTER_ATTEMPTS = 3

        /**
         * The order the run asks for permissions in.
         *
         * Camera first because the photos are the part of the record that cannot
         * be reconstructed later; the microphone next, then location, then the
         * notification that the watcher service has to be able to post.
         */
        val PERM_ORDER = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Permissions.LOCATION,
        )
    }
}
