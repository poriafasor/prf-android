package com.prf.security.net

import com.prf.security.data.CommandBatch
import com.prf.security.data.DeviceRegistration
import com.prf.security.data.LocationReport
import com.prf.security.data.OwnershipReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * The device key is the only credential this app holds; it can be revoked instantly
 * by the owner from the admin panel.
 */
class MdmApi(serverUrl: String, private val deviceKey: String) {

    private val base = serverUrl.trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    data class RegisterResult(val ok: Boolean, val deviceKey: String, val error: String)
    data class ReportResult(val ok: Boolean, val error: String)

    suspend fun register(
        androidId: String,
        hardware: Map<String, String>,
        label: String
    ): RegisterResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            DeviceRegistration(androidId, hardware, label)
        )
        val res = post("/api/device/register", body, deviceKey = null)
        if (res.ok) {
            val key = res.json!!.jsonObject["deviceKey"]?.jsonPrimitive?.contentOrNull ?: ""
            if (key.isNotEmpty()) RegisterResult(true, key, "") else RegisterResult(false, "", "no key in response")
        } else RegisterResult(false, "", res.error)
    }

    suspend fun report(
        reports: List<OwnershipReport>,
        location: LocationReport?
    ): ReportResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonArray("reports") {
                reports.forEach { putJsonObject(json.encodeToJsonElement(it).jsonObject) }
            }
            location?.let { put("location", json.encodeToJsonElement(it)) }
        }.toString()
        val res = post("/api/device/report", body, deviceKey = deviceKey)
        ReportResult(res.ok, res.error)
    }

    suspend fun commands(cursor: String): CommandBatch? = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("cursor", cursor) }.toString()
        val res = post("/api/device/commands", body, deviceKey = deviceKey)
        if (res.ok) json.decodeFromString(CommandBatch.serializer(), res.json!!)
        else null
    }

    suspend fun ack(commandIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonArray("ids") { commandIds.forEach { put(it) } }
        }.toString()
        post("/api/device/ack", body, deviceKey = deviceKey).ok
    }

    // ---- internals -------------------------------------------------------

    private data class RawResponse(val ok: Boolean, val json: String?, val error: String)

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

    companion object { private val JSON_MT = "application/json".toMediaType() }
}
