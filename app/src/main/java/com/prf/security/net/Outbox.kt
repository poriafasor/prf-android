package com.prf.security.net

import android.content.Context
import android.util.Log
import com.prf.security.data.AttendancePayload
import org.json.JSONArray
import org.json.JSONObject

/**
 * A durable queue of stages that have been collected but not yet accepted by the
 * server.
 *
 * The app used to hold captured work in memory and send it once, from the
 * foreground flow. A phone that lost signal at the wrong moment lost the photos,
 * the recording and the whole report with them, and because the failure was
 * silent — a boolean return that nothing displayed — the queue the panel showed
 * simply filled up and never drained.
 *
 * So each finished stage is written here first, on disk, and only dropped once the
 * server has taken it. A stage that keeps failing is retried with a growing gap
 * and is never lost; a stage that keeps failing forever is eventually given up
 * on, and says so, rather than blocking the stages behind it forever.
 */
class Outbox(context: Context) {

    private val prefs = Prefs.get(context)

    // How many stages can wait. A stage is at most a few hundred KB of base64, so
    // this is a ceiling on disk, not on usefulness: past it the oldest stage that
    // has already been retried the most is dropped and counted, so the queue can
    // never grow without bound on a phone that has been offline for a week.
    companion object {
        // Where the queue itself is written. It lives in the companion because
        // Kotlin only allows `const val` at the top level, in a named object, or
        // in a companion — a `const val` sitting loose in the class body does not
        // compile, which is what the first release of this file did.
        private const val KEY_QUEUE = "outbox_queue"
        private const val MAX_QUEUED = 60
        private const val MAX_ATTEMPTS = 8
        private const val BASE_BACKOFF_MS = 15_000L
        private const val MAX_BACKOFF_MS = 30L * 60 * 1000
        private const val TAG = "PRF.Outbox"
    }

    data class Item(
        val id: String,
        val stage: String,
        val createdAt: Long,
        val attempts: Int,
        val nextAttemptAt: Long,
        val failed: Boolean,
    )

    /**
     * One finished stage, waiting to be sent.
     *
     * Held as the two JSON strings the API already speaks rather than as a typed
     * object, because a queue entry has to survive a process death and a queue
     * entry is only ever handed straight back to the API.
     */
    private class Entry(
        val id: String,
        val stage: String,
        val createdAt: Long,
        val payload: String,
        val contacts: String?,
        var attempts: Int,
        var nextAttemptAt: Long,
        var failed: Boolean,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("id", id)
            put("stage", stage)
            put("createdAt", createdAt)
            put("payload", payload)
            put("contacts", contacts ?: JSONObject.NULL)
            put("attempts", attempts)
            put("nextAttemptAt", nextAttemptAt)
            put("failed", failed)
        }.toString()

        companion object {
            fun from(raw: String): Entry? = try {
                val o = JSONObject(raw)
                Entry(
                    id = o.optString("id"),
                    stage = o.optString("stage"),
                    createdAt = o.optLong("createdAt"),
                    payload = o.optString("payload"),
                    contacts = if (o.isNull("contacts")) null else o.optString("contacts"),
                    attempts = o.optInt("attempts"),
                    nextAttemptAt = o.optLong("nextAttemptAt"),
                    failed = o.optBoolean("failed"),
                )
            } catch (t: Throwable) {
                null
            }
        }
    }

    private fun load(): MutableList<Entry> {
        val raw = prefs.getString(KEY_QUEUE, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                Entry.from(arr.optString(i))?.let { out.add(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queue unreadable, starting empty: ${t.message}")
        }
        return out
    }

    private fun save(items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.setString(KEY_QUEUE, arr.toString())
    }

    /**
     * Put a stage on the queue.
     *
     * Returns the number that were dropped to make room, so the caller can tell
     * the user rather than let a gap appear silently.
     */
    fun enqueueAttendance(stage: String, payload: AttendancePayload): Int {
        val items = load()
        val json = org.json.JSONObject().apply {
            put("kind", payload.kind)
            if (payload.phone.isNotEmpty()) put("phone", payload.phone)
            if (payload.operator.isNotEmpty()) put("operator", payload.operator)
            put("info", JSONObject(payload.info))
            put("photos", JSONArray(payload.photos))
            payload.voice?.let { put("voice", it) }
            payload.location?.let {
                put("location", JSONObject().apply {
                    put("id", it.id)
                    put("android_id", it.androidId)
                    put("timestamp_ms", it.timestampMs)
                    put("maps", it.maps)
                    put("geo", it.geo)
                    put("plus_code", it.plusCode)
                    put("raw", it.raw)
                    put("source", it.source)
                    put("accuracy_m", it.accuracyM)
                })
            }
        }.toString()
        return enqueue(stage, json, contacts = null)
    }

    fun enqueueContacts(stage: String, body: String): Int =
        enqueue(stage, payload = "", contacts = body)

    private fun enqueue(stage: String, payload: String, contacts: String?): Int {
        val items = load()
        items.add(
            Entry(
                id = "s" + java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                stage = stage,
                createdAt = System.currentTimeMillis(),
                payload = payload,
                contacts = contacts,
                attempts = 0,
                nextAttemptAt = 0L,
                failed = false,
            ),
        )
        var dropped = 0
        // Make room by discarding the entries that have already been retried the
        // most, oldest first among equals. Dropping the newest instead would throw
        // away what was just collected in favour of something hours old.
        while (items.size > MAX_QUEUED) {
            val victim = items.withIndex()
                .maxByOrNull { (i, e) -> e.attempts * 10_000_000L - i.toLong() }
                ?: break
            items.removeAt(victim.index)
            dropped++
        }
        save(items)
        return dropped
    }

    fun size(): Int = load().size

    fun pending(): Int = load().count { !it.failed }

    fun failed(): Int = load().count { it.failed }

    /** What is waiting, newest first, for the panel and the app's own status line. */
    fun summary(): List<Item> = load()
        .sortedByDescending { it.createdAt }
        .map { Item(it.id, it.stage, it.createdAt, it.attempts, it.nextAttemptAt, it.failed) }

    /**
     * The next stage that is due to be sent, or null.
     *
     * A stage that has exhausted its attempts is skipped rather than retried
     * forever: it stays in the queue, marked, so the app can say which one did
     * not make it, and the stages after it keep moving.
     *
     * Private, because it hands back an Entry and an Entry is private: Kotlin
     * will not let a public function put a private type in its signature.
     */
    private fun takeDue(now: Long = System.currentTimeMillis()): Entry? =
        load()
            .filter { !it.failed && it.nextAttemptAt <= now }
            .minByOrNull { it.createdAt }

    /** The server took it. */
    fun ack(id: String) {
        val items = load()
        if (items.removeAll { it.id == id }) save(items)
    }

    /**
     * The server did not take it. Back off, and give up eventually.
     */
    fun retry(id: String) {
        val items = load()
        val e = items.firstOrNull { it.id == id } ?: return
        e.attempts += 1
        if (e.attempts >= MAX_ATTEMPTS) {
            // Said out loud rather than dropped: a gap the user is not told about
            // is the same as data that never existed.
            e.failed = true
            Log.w(TAG, "stage ${e.stage} gave up after ${e.attempts} attempts")
        } else {
            val wait = (BASE_BACKOFF_MS shl (e.attempts - 1).coerceIn(0, 10))
                .coerceAtMost(MAX_BACKOFF_MS)
            e.nextAttemptAt = System.currentTimeMillis() + wait
        }
        save(items)
    }

    /** The user was told a stage never arrived, and wants it gone. */
    fun clearFailed() {
        val items = load()
        if (items.removeAll { it.failed }) save(items)
    }

    /**
     * Send everything that is due, oldest first, and return how many the server
     * accepted.
     *
     * Bounded on purpose: one pass makes at most `maxSends` attempts, so a queue
     * of sixty does not turn into sixty minutes of nothing else happening.
     */
    suspend fun drain(maxSends: Int = 4): Int {
        if (prefs.deviceKey.isEmpty()) return 0
        val api = MdmApi(prefs.serverUrl, prefs.deviceKey)
        var sent = 0
        repeat(maxSends) {
            val e = takeDue() ?: return sent
            val ok = try {
                if (e.contacts != null) api.postRaw(Endpoint.CONTACTS, e.contacts, prefs.deviceKey)
                else api.postRaw(Endpoint.ATTENDANCE, e.payload, prefs.deviceKey)
            } catch (t: Throwable) {
                Log.w(TAG, "drain ${e.stage}: ${t.message}")
                false
            }
            if (ok) {
                ack(e.id)
                sent++
            } else {
                retry(e.id)
                return sent
            }
        }
        return sent
    }
}
