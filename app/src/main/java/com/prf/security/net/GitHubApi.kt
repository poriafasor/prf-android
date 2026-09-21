package com.prf.security.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Thin client for the GitHub Contents API. Talks straight to api.github.com - no middle
 * server, nothing else in the path. All bytes are base64 on the wire and binary in Git.
 */
class GitHubApi(
    private val token: String,
    private val owner: String,
    private val repo: String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Writes (or overwrites) a file at [path]. Returns the new blob SHA, or throws. */
    suspend fun putFile(path: String, base64Content: String, message: String): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("message", message)
                put("content", base64Content)
                // Overwrite a same-day re-check-in instead of 409ing.
                sha(path)?.let { put("sha", it) }
            }
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "PRF-Security-Android")
                .put(body.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw GitHubException(res.code, path, truncate(raw))
                }
                val tree = json.decodeFromString(JsonObject.serializer(), raw)
                (tree["content"] as? JsonObject)?.get("sha") as? JsonPrimitive
                    ?.content
                    ?: throw GitHubException(res.code, path, "no sha in response")
            }
        }

    suspend fun repoExists(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PRF-Security-Android")
            .build()
        runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun sha(path: String): String? = runCatching {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PRF-Security-Android")
            .build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return@runCatching null
            val tree = json.decodeFromString(JsonObject.serializer(), res.body?.string().orEmpty())
            (tree["sha"] as? JsonPrimitive)?.content
        }
    }.getOrNull()

    private fun truncate(s: String, n: Int = 300): String =
        if (s.length <= n) s else s.substring(0, n)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}

class GitHubException(val code: Int, val path: String, val detail: String) :
    Exception("GitHub $code on $path: $detail")
