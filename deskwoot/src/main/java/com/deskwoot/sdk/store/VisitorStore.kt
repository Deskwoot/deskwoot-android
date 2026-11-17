package com.deskwoot.sdk.store

import com.deskwoot.sdk.model.IsoTime
import com.deskwoot.sdk.model.isTrue
import com.deskwoot.sdk.model.putNullable
import com.deskwoot.sdk.model.parseJsonObject
import com.deskwoot.sdk.model.string
import com.deskwoot.sdk.model.text
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * What the SDK remembers about the visitor of one widget token:
 * the bearer (a secret: whoever holds it reads and writes the visitor's
 * conversations), the verified user id it belongs to, when the visitor last
 * read the thread, the conversation last shown, and whether a push
 * registration was sent for the bearer. The app's language is the app's
 * setting and is never stored.
 */
internal data class VisitorRecord(
    val bearer: String? = null,
    val verifiedExternalId: String? = null,
    val lastReadAt: Long? = null,
    val lastConversationId: String? = null,
    /**
     * A push registration was sent for [bearer]: written before the first
     * registration request for it leaves the device. Only then does the end
     * of this visitor leave a [PushRemoval]; a record without it (also one
     * written before the flag existed) leaves nothing behind.
     */
    val pushRegistered: Boolean = false,
) {
    /** The bearer is a secret: never printed. */
    override fun toString(): String =
        "VisitorRecord(bearer=${if (bearer == null) "null" else "[redacted]"}, verifiedExternalId=$verifiedExternalId, " +
            "lastReadAt=$lastReadAt, lastConversationId=$lastConversationId, pushRegistered=$pushRegistered)"

    /** The record with [next] as its bearer: the registration flag belongs to a bearer and goes with it. */
    fun withBearer(next: String?): VisitorRecord = if (next == bearer) this else copy(bearer = next, pushRegistered = false)

    /**
     * Version 2 of the file: `{ v, bearer, verifiedExternalId, lastReadAt
     * (ISO), lastConversationId, pushRegistered }`; the store adds the
     * widget token, so a logout before `setup` can name it in a removal.
     */
    fun toJson(): JSONObject = JSONObject()
        .put("v", 2)
        .putNullable("bearer", bearer)
        .putNullable("verifiedExternalId", verifiedExternalId)
        .putNullable("lastReadAt", lastReadAt?.let(IsoTime::format))
        .putNullable("lastConversationId", lastConversationId)
        .also { if (pushRegistered) it.put("pushRegistered", true) }

    internal companion object {
        /** A `language` field of an older file is ignored; a file without `pushRegistered` sent no registration. */
        fun fromJson(json: JSONObject?): VisitorRecord {
            json ?: return VisitorRecord()
            val bearer = json.text("bearer")
            return VisitorRecord(
                bearer = bearer,
                verifiedExternalId = json.text("verifiedExternalId"),
                lastReadAt = IsoTime.parse(json.string("lastReadAt")),
                lastConversationId = json.text("lastConversationId"),
                pushRegistered = bearer != null && json.isTrue("pushRegistered"),
            )
        }
    }
}

/**
 * A visitor this device forgot while the phone may still be registered to
 * them: the inbox, the bearer the registration names and when the record
 * was written (wall time). Sent as a removal whenever a push token is
 * known, of whichever inbox the app sets up; kept until the server
 * confirmed it, at most [MAX_AGE_MILLIS]. The only place a forgotten bearer
 * still exists, so it is never printed.
 */
internal data class PushRemoval(val widgetToken: String, val bearer: String, val at: Long) {
    override fun toString(): String = "PushRemoval(widgetToken=$widgetToken, bearer=[redacted], at=$at)"

    /** The same registration: the same inbox and bearer, whenever it was written. */
    fun sameAs(other: PushRemoval): Boolean = widgetToken == other.widgetToken && bearer == other.bearer

    internal companion object {
        /** Records kept per inbox at most; the oldest is dropped first. */
        const val MAX_PER_INBOX: Int = 8

        /** A record older than this (30 days) is deleted unsent. */
        const val MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1_000

        /** [records] without those older than [MAX_AGE_MILLIS] at [now]; a clock that went back keeps them. */
        fun fresh(records: List<PushRemoval>, now: Long): List<PushRemoval> = records.filter { now - it.at <= MAX_AGE_MILLIS }

        /**
         * [records] with [added] after them (a registration already there keeps
         * its place and time), at most [MAX_PER_INBOX] per inbox, oldest first.
         */
        fun merge(records: List<PushRemoval>, added: List<PushRemoval>): List<PushRemoval> {
            val all = ArrayList(records)
            for (entry in added) if (all.none { it.sameAs(entry) }) all += entry
            val counts = HashMap<String, Int>()
            val keep = BooleanArray(all.size)
            for (index in all.indices.reversed()) {
                val count = (counts[all[index].widgetToken] ?: 0) + 1
                counts[all[index].widgetToken] = count
                keep[index] = count <= MAX_PER_INBOX
            }
            return all.filterIndexed { index, _ -> keep[index] }
        }
    }
}

/**
 * Keeps the [VisitorRecord] of one widget token, and the pending push
 * removals of every inbox of the app. An interface so tests use
 * [MemoryVisitorStore]. Every change goes through the [StoreQueue].
 */
internal interface VisitorStore {
    suspend fun read(): VisitorRecord

    suspend fun write(record: VisitorRecord)

    /** Deletes the record. The pending push removals stay: they outlive the visitor on purpose. */
    suspend fun clear()

    /** The pending removals of every inbox, oldest first; a missing, broken or unreadable file reads as none. */
    suspend fun readPushRemovals(): List<PushRemoval>

    /** An empty list deletes the file: no bearer stays on the disk once every removal went through. */
    suspend fun writePushRemovals(removals: List<PushRemoval>)
}

/**
 * The record as a JSON file in `noBackupFilesDir/deskwoot/`: never backed up
 * or restored to another device, so a bearer never travels. Writes go to a
 * temporary file that is flushed to the disk and then replaces the old one,
 * so a crash or a power cut never leaves half a file, and no temporary file
 * with the bearer stays behind. [directory] is asked for on
 * [io] only: Android creates the folder when it is first asked for.
 *
 * A file that exists but cannot be read (an I/O error, not a broken file)
 * may still hold the only key to an anonymous visitor's conversations: the
 * store then reads as empty and writes nothing for the rest of this
 * process, so a new bearer never replaces it. [clear] still
 * deletes it: a logout must never leave a bearer behind.
 */
internal class FileVisitorStore(
    directory: () -> File,
    private val widgetToken: String,
    private val io: CoroutineDispatcher,
) : VisitorStore {
    private val file by lazy { File(directory(), "${storeKey(widgetToken)}.visitor.json") }
    private val removalsFile by lazy { File(directory(), StoredVisitors.REMOVALS_FILE) }

    /** The list of an earlier version of the SDK, one per inbox, kept without asking whether a registration existed. */
    private val oldRemovalsFile by lazy { File(directory(), "${storeKey(widgetToken)}.push-removals.json") }
    private val lock = Mutex()
    private var unreadable = false

    // The lock is taken and released on [io]: a step that waits for it never waits for the main thread.

    override suspend fun read(): VisitorRecord = withContext(io) {
        lock.withLock {
            when (val text = readStored(file)) {
                is Stored.Text -> VisitorRecord.fromJson(parseJsonObject(text.value))
                Stored.Missing -> VisitorRecord()
                Stored.Unreadable -> {
                    unreadable = true
                    VisitorRecord()
                }
            }
        }
    }

    override suspend fun write(record: VisitorRecord) {
        withContext(io) {
            lock.withLock {
                if (!unreadable) writeAtomically(file, record.toJson().put("widgetToken", widgetToken).toString())
            }
        }
    }

    override suspend fun clear() {
        withContext(io) {
            lock.withLock {
                deleteWithTemp(file)
                unreadable = false
            }
        }
    }

    override suspend fun readPushRemovals(): List<PushRemoval> = withContext(io) {
        lock.withLock {
            // The older list never asked whether a registration existed: it goes, with the bearers in it.
            deleteWithTemp(oldRemovalsFile)
            StoredVisitors.readRemovals(removalsFile)
        }
    }

    override suspend fun writePushRemovals(removals: List<PushRemoval>) {
        withContext(io) { lock.withLock { StoredVisitors.writeRemovals(removalsFile, removals) } }
    }
}

/** The store in memory, for tests; [steps] lists what was done to it, in order. */
internal class MemoryVisitorStore(initial: VisitorRecord = VisitorRecord(), removals: List<PushRemoval> = emptyList()) : VisitorStore {
    @Volatile
    var record: VisitorRecord = initial
        private set

    @Volatile
    var removals: List<PushRemoval> = removals
        private set

    /** The bearers of [removals], oldest first. */
    val pushRemovals: List<String> get() = removals.map { it.bearer }

    /** `write <bearer>`, `clear`, `removals <a,b>`: the order the engine changed the store in. */
    val steps: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    override suspend fun read(): VisitorRecord = record

    override suspend fun write(record: VisitorRecord) {
        steps += "write ${record.bearer}"
        this.record = record
    }

    /** Holds every [clear] until completed: a deletion that takes its time. */
    @Volatile
    var clearGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun clear() {
        clearGate?.await()
        steps += "clear"
        record = VisitorRecord()
    }

    override suspend fun readPushRemovals(): List<PushRemoval> = removals

    override suspend fun writePushRemovals(removals: List<PushRemoval>) {
        steps += "removals ${removals.joinToString(",") { it.bearer }}"
        this.removals = removals
    }
}

/**
 * The file name part for a widget token: its SHA-256 in hex, cut to 32
 * characters. Two widgets in one app never share a visitor, and
 * the name never depends on what characters a token holds.
 */
internal fun storeKey(widgetToken: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(widgetToken.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(32)
}

/** What a store file holds: its text, nothing, or something that exists and cannot be read now. */
internal sealed class Stored {
    data class Text(val value: String) : Stored()

    object Missing : Stored()

    object Unreadable : Stored()
}

/**
 * Reads a store file. A temporary file next to it is a leftover of a write
 * that died (the lock keeps reads and writes apart), so it goes: it may hold
 * a bearer.
 */
internal fun readStored(file: File): Stored {
    tempOf(file).delete()
    if (!file.exists()) return Stored.Missing
    return try {
        Stored.Text(file.readText(Charsets.UTF_8))
    } catch (_: IOException) {
        Stored.Unreadable
    } catch (_: SecurityException) {
        Stored.Unreadable
    }
}

internal fun readText(file: File): String? = (readStored(file) as? Stored.Text)?.value

/**
 * Replaces [file] with [text]: written to a temporary file, flushed to the
 * disk (fsync) and renamed over the old one, which the file system does in
 * one step. On any failure the temporary file is deleted, so no copy of a
 * secret stays behind; memory stays the truth and the next write tries again.
 */
internal fun writeAtomically(file: File, text: String) {
    val temp = tempOf(file)
    try {
        file.parentFile?.mkdirs()
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) temp.delete()
        }
    } catch (_: IOException) {
        temp.delete()
    } catch (_: SecurityException) {
        temp.delete()
    }
}

/** Deletes [file] and a temporary file of a write that died next to it. */
internal fun deleteWithTemp(file: File) {
    file.delete()
    tempOf(file).delete()
}

private fun tempOf(file: File): File = File(file.parentFile, file.name + ".tmp")
