package com.prf.security.net

import android.util.Base64
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
                val existingSha = sha(path)
                if (existingSha != null) {
                    put("sha", existingSha)
                }
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
                val contentObj = tree["content"] as? JsonObject
                if (contentObj == null) {
                    throw GitHubException(res.code, path, "no sha in response")
                }
                val sha = (contentObj["sha"] as? JsonPrimitive)?.content
                if (sha.isNullOrEmpty()) {
                    throw GitHubException(res.code, path, "no sha in response")
                }
                sha
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

    /**
     * The real connection test. Existence is not enough: the token must also be able to
     * WRITE, so this round-trips a tiny probe file through the Contents API and then
     * deletes it. Whatever happens, the caller gets a human-readable reason - never an
     * unformatted "%1$s".
     */
    suspend fun testConnection(): TestResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext TestResult(false, "no token")
        if (owner.isBlank()) return@withContext TestResult(false, "no account")
        if (repo.isBlank()) return@withContext TestResult(false, "no repository")

        val probePath = ".prf_connection_probe"
        try {
            // 1) repo must exist and the token must at least read it.
            val exists = repoExists()
            if (!exists) {
                return@withContext TestResult(false, "repository $owner/$repo not found (401/404)")
            }
            // 2) prove write access with a real create + delete round-trip.
            val createdSha = try {
                putFile(probePath, b64("prf-probe-ok".toByteArray()), "chore: connection probe")
            } catch (t: Throwable) {
                return@withContext TestResult(false, "write denied: ${t.message}")
            }
            runCatching { deleteFile(probePath, createdSha, "chore: remove probe") }
            TestResult(true, "writable")
        } catch (t: Throwable) {
            TestResult(false, t.message ?: "unknown error")
        }
    }

    /** Deletes [path] at [fileSha]. Used by the connection probe cleanup. */
    suspend fun deleteFile(path: String, fileSha: String, message: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("message", message)
                put("sha", fileSha)
            }
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "PRF-Security-Android")
                .delete(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
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

        /** android.util.Base64, not java.util.Base64 - safe down to API 23. */
        fun b64(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

/** Outcome of [GitHubApi.testConnection]. [detail] is always display-ready. */
data class TestResult(val ok: Boolean, val detail: String)

class GitHubException(val code: Int, val path: String, val detail: String) :
    Exception("GitHub $code on $path: $detail")
