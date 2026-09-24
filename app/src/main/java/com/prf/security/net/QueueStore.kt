package com.prf.security.net

import android.content.Context
import com.prf.security.data.CheckIn
import kotlinx.serialization.json.Json

/**
 * Crash-proof offline queue. A check-in is written here the moment capture finishes and
 * is only deleted after every file in it has landed in the database repo. One JSON file
 * per check-in, named by its id - no database, no migration.
 */
class QueueStore(context: Context) {

    private val dir = context.getDir(DIR, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun enqueue(checkIn: CheckIn) {
        val file = dir.resolve("${checkIn.id}.json")
        file.writeText(json.encodeToString(CheckIn.serializer(), checkIn))
    }

    fun pendingSessions(): List<CheckIn> = all()

    fun all(): List<CheckIn> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".json") }
        .sortedBy { it.name }
        .mapNotNull { runCatching { json.decodeFromString(CheckIn.serializer(), it.readText()) }.getOrNull() }

    fun pendingCount(): Int = size()

    fun size(): Int = dir.listFiles()?.count { it.isFile } ?: 0

    /**
     * Attaches the on-device clipboard threat counts to the oldest pending check-in, so
     * they ride the next upload without creating a synthetic one. The counts are the only
     * clipboard-derived data that ever leaves the device - aggregate numbers per threat
     * kind, never the copied text. Idempotent: a count already present is overwritten,
     * not added to.
     */
    fun attachClipboardThreats(counts: Map<String, Int>) {
        val pending = all()
        if (pending.isEmpty()) return
        val target = pending.first()
        target.copy(clipboardThreats = counts).let { updated ->
            dir.resolve("${'$'}{updated.id}.json")
                .writeText(json.encodeToString(CheckIn.serializer(), updated))
        }
    }

    fun markUploaded(id: String) = remove(id)

    fun remove(id: String) {
        dir.resolve("$id.json").delete()
    }

    companion object {
        private const val DIR = "prf_queue"
    }
}
