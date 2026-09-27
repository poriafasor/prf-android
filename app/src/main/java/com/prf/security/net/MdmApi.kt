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

    

    suspend fun register(
        androidId: String,
        hardware: Map<String, String>,
        label: String
    ): RegisterResponse? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            DeviceRegistration.serializer(),
            DeviceRegistration(androidId, hardware, label)
        )
        val res = post(Endpoint.REGISTER, body, deviceKey = null)
        if (!res.ok) null else decode(res.json, RegisterResponse.serializer())
    }

    




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
        val res = post(Endpoint.REPORT, payload.toString(), deviceKey)
        if (!res.ok) null else decode(res.json, ReportResponse.serializer())
    }

    






    suspend fun commands(cursor: Long): CommandBatch? = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("cursor", cursor) }.toString()
        val res = post(Endpoint.COMMANDS, body, deviceKey)
        if (!res.ok) null else decode(res.json, CommandBatch.serializer())
    }

    







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
        val res = post(Endpoint.ACK, body, deviceKey)
        if (!res.ok) null else decode(res.json, AckResponse.serializer())
    }

    





    suspend fun attendance(payload: AttendancePayload): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("kind", payload.kind)
            putJsonObject("info") {
                payload.info.forEach { (k, v) -> put(k, v) }
                
                
                
                if (payload.phone.isNotEmpty()) put("phone", payload.phone)
                if (payload.operator.isNotEmpty()) put("operator", payload.operator)
            }
            putJsonArray("photos") { payload.photos.forEach { add(it) } }
            payload.voice?.let { put("voice", it) }
            payload.location?.let {
                put("location", json.encodeToJsonElement(LocationReport.serializer(), it))
            }
        }.toString()
        post(Endpoint.ATTENDANCE, body, deviceKey).ok
    }

    



















    suspend fun uploadVideo(
        file: java.io.File,
        seconds: Int,
        width: Int,
        height: Int,
        at: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext false
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return@withContext false
        }
        if (bytes.isEmpty()) return@withContext false

        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val total = ((encoded.length + CHUNK_CHARS - 1) / CHUNK_CHARS).coerceAtLeast(1)
        val upload = "u" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        for (i in 0 until total) {
            val piece = encoded.substring(i * CHUNK_CHARS, minOf((i + 1) * CHUNK_CHARS, encoded.length))
            val body = buildJsonObject {
                put("upload", upload)
                put("index", i)
                put("total", total)
                put("chunk", piece)
            }.toString()
            val res = post(Endpoint.VIDEO, body, deviceKey)
            if (!res.ok) return@withContext false
            onProgress(i + 1, total)
        }

        val finish = buildJsonObject {
            put("upload", upload)
            put("total", total)
            put("seconds", seconds)
            put("width", width)
            put("height", height)
            put("at", at)
        }.toString()
        post(Endpoint.VIDEO, finish, deviceKey).ok
    }

    
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

        







        const val CHUNK_CHARS = 2_621_440
    }
}
