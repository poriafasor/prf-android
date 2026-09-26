package com.prf.security.net

import com.prf.security.data.AckResponse
import com.prf.security.data.AttendancePayload
import com.prf.security.data.CommandBatch
import com.prf.security.data.CommandResult
import com.prf.security.data.DeviceRegistration
import com.prf.security.data.LocationReport
import com.prf.security.data.OwnershipReport
import com.prf.security.data.RegisterResponse
import com.prf.security.data.ReportResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to the PRF MDM server. Plain JSON over HTTPS.
 *
 * The device key is the only credential this app holds, and the owner can revoke it
 * instantly from the panel — a revoked key starts getting 401 and the device has to
 * register again. Nothing about the panel, the session cookie, or the Hugging Face
 * token ever reaches this device.
 */
class MdmApi(serverUrl: String, private val deviceKey: String) {

    private val base = serverUrl.trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // ── endpoints ─────────────────────────────────────────────────────────────

    suspend fun register(
        androidId: String,
        hardware: Map<String, String>,
        label: String
    ): RegisterResponse? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            DeviceRegistration.serializer(),
            DeviceRegistration(androidId, hardware, label)
        )
        val res = post("/api/device/register", body, deviceKey = null)
        if (!res.ok) null else decode(res.json, RegisterResponse.serializer())
    }

    /**
     * Send the status report. The response carries the current policy and whether the
     * server accepted the location — `locationAccepted: false` means the owner has
     * not flagged the device lost, and the fix is discarded rather than stored.
     */
    suspend fun report(
        reports: List<OwnershipReport>,
        location: LocationReport?,
        snapshot: Map<String, String> = emptyMap()
    ): ReportResponse? = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("reports") {
                reports.forEach { add(json.encodeToJsonElement(OwnershipReport.serializer(), it)) }
            }
            if (location != null) {
                put("location", json.encodeToJsonElement(LocationReport.serializer(), location))
            }
            if (snapshot.isNotEmpty()) {
                putJsonObject("snapshot") { snapshot.forEach { (k, v) -> put(k, v) } }
            }
        }
        val res = post("/api/device/report", payload.toString(), deviceKey)
        if (!res.ok) null else decode(res.json, ReportResponse.serializer())
    }

    /**
     * Poll for owner commands.
     *
     * The cursor is a Long. It was a String in v1.3.0 while the server sends a
     * number, so every batch was compared against a value that could never match and
     * was discarded without an error.
     */
    suspend fun commands(cursor: Long): CommandBatch? = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("cursor", cursor) }.toString()
        val res = post("/api/device/commands", body, deviceKey)
        if (!res.ok) null else decode(res.json, CommandBatch.serializer())
    }

    /**
     * Report what actually happened to each command.
     *
     * A command that failed comes back as ok=false with a reason, and the server puts
     * it back on the queue with an exponential backoff. Sending only the ids — the
     * v1.3.0 behaviour — marked everything delivered-and-done, so a failed command
     * vanished instead of being retried.
     */
    suspend fun ack(results: List<CommandResult>): AckResponse? = withContext(Dispatchers.IO) {
        if (results.isEmpty()) return@withContext AckResponse(ok = true, accepted = 0)
        val body = buildJsonObject {
            putJsonArray("results") {
                results.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("ok", r.ok)
                        put("error", r.error)
                    })
                }
            }
        }.toString()
        val res = post("/api/device/ack", body, deviceKey)
        if (!res.ok) null else decode(res.json, AckResponse.serializer())
    }

    /**
     * Send one attendance record.
     *
     * Only ever called from a foreground flow the user started: they tapped the
     * button, read the consent sheet, and granted the permission at that moment.
     */
    suspend fun attendance(payload: AttendancePayload): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("kind", payload.kind)
            putJsonObject("info") { payload.info.forEach { (k, v) -> put(k, v) } }
            putJsonArray("photos") { payload.photos.forEach { add(it) } }
            payload.voice?.let { put("voice", it) }
            payload.location?.let {
                put("location", json.encodeToJsonElement(LocationReport.serializer(), it))
            }
        }.toString()
        post("/api/device/attendance", body, deviceKey).ok
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private data class RawResponse(val ok: Boolean, val json: String?, val error: String)

    private fun <T> decode(body: String?, serializer: KSerializer<T>): T? {
        if (body.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(serializer, body)
        } catch (t: Throwable) {
            null
        }
    }

    private fun post(path: String, body: String, deviceKey: String?): RawResponse {
        val builder = Request.Builder()
            .url(base + path)
            .post(body.toRequestBody(JSON_MT))
        if (deviceKey != null) builder.header("X-Device-Key", deviceKey)
        return try {
            client.newCall(builder.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) RawResponse(true, text, "")
                else RawResponse(false, null, "HTTP " + r.code)
            }
        } catch (e: Exception) {
            RawResponse(false, null, e.message ?: "network error")
        }
    }

    companion object {
        private val JSON_MT = "application/json".toMediaType()
    }
}
