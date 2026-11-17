package com.deskwoot.sdk.store

import com.deskwoot.sdk.model.ConversationSummary
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.ThreadSegment
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.model.array
import com.deskwoot.sdk.model.isTrue
import com.deskwoot.sdk.model.obj
import com.deskwoot.sdk.model.objects
import com.deskwoot.sdk.model.parseJsonObject
import com.deskwoot.sdk.model.putNullable
import com.deskwoot.sdk.model.text
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last state worth painting before the network answers:
 * the config, the visitor's list and the last thread. [identity] names who
 * it belongs to ("anonymous" or "verified:<externalId>"), so a thread is
 * never painted for anybody else.
 */
internal data class CacheSnapshot(
    val identity: String,
    val config: WidgetConfig?,
    val conversationId: String?,
    val messages: List<Message>,
    val conversations: List<ConversationSummary>,
    val segments: List<ThreadSegment>,
    val resolved: Boolean,
    val continuesAfterResolve: Boolean,
) {
    /**
     * Version 1 of the file: `{ v, identity, config, conversationId,
     * messages: [server rows], conversations: [list entries], segments,
     * resolved, continuesAfterResolve }`. Rows and entries keep the
     * server's own shape, so the same parsers read them back.
     */
    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("identity", identity)
        .putNullable("config", config?.let { parseJsonObject(it.rawJson) })
        .putNullable("conversationId", conversationId)
        .put("messages", JSONArray().also { array -> messages.forEach { m -> parseJsonObject(m.rawJson)?.let(array::put) } })
        .put("conversations", JSONArray().also { array -> conversations.forEach { array.put(it.toJson()) } })
        .put("segments", JSONArray().also { array -> segments.forEach { array.put(it.toJson()) } })
        .put("resolved", resolved)
        .put("continuesAfterResolve", continuesAfterResolve)

    internal companion object {
        /** At most this many rows are kept: enough to paint, small enough to write often. */
        const val MAX_MESSAGES: Int = 200

        fun fromJson(json: JSONObject?): CacheSnapshot? {
            json ?: return null
            val identity = json.text("identity") ?: return null
            return CacheSnapshot(
                identity = identity,
                config = json.obj("config")?.let(WidgetConfig::parse),
                conversationId = json.text("conversationId"),
                messages = json.array("messages")?.objects()?.mapNotNull(Message::parse).orEmpty(),
                conversations = json.array("conversations")?.objects()?.mapNotNull(ConversationSummary::parse).orEmpty(),
                segments = json.array("segments")?.objects()?.mapNotNull(ThreadSegment::parse).orEmpty(),
                resolved = json.isTrue("resolved"),
                continuesAfterResolve = json.isTrue("continuesAfterResolve"),
            )
        }
    }
}

/** Keeps the [CacheSnapshot] of one widget token. An interface so tests use [MemoryDiskCache]. */
internal interface DiskCache {
    suspend fun read(): CacheSnapshot?

    suspend fun write(snapshot: CacheSnapshot)

    suspend fun clear()
}

/**
 * The snapshot as a JSON file next to the visitor record: message texts
 * are the visitor's data, never backed up. [directory] is asked for on [io] only.
 */
internal class FileDiskCache(
    directory: () -> File,
    widgetToken: String,
    private val io: CoroutineDispatcher,
) : DiskCache {
    private val file by lazy { File(directory(), "${storeKey(widgetToken)}.cache.json") }
    private val lock = Mutex()

    // The lock is taken and released on [io]: a step of the store queue that waits for it never waits for the main thread.

    override suspend fun read(): CacheSnapshot? = withContext(io) {
        lock.withLock { CacheSnapshot.fromJson(parseJsonObject(readText(file))) }
    }

    /** A write whose visitor ended while it waited for the lock writes nothing: the wipe's [clear] may have run first. */
    override suspend fun write(snapshot: CacheSnapshot) {
        withContext(io) {
            lock.withLock {
                currentCoroutineContext().ensureActive()
                writeAtomically(file, snapshot.toJson().toString())
            }
        }
    }

    override suspend fun clear() {
        withContext(io) { lock.withLock { deleteWithTemp(file) } }
    }
}

internal class MemoryDiskCache(initial: CacheSnapshot? = null) : DiskCache {
    @Volatile
    var snapshot: CacheSnapshot? = initial
        private set

    override suspend fun read(): CacheSnapshot? = snapshot

    /** How many snapshots were written. */
    @Volatile
    var writes: Int = 0
        private set

    override suspend fun write(snapshot: CacheSnapshot) {
        writes++
        this.snapshot = snapshot
    }

    override suspend fun clear() {
        snapshot = null
    }
}
