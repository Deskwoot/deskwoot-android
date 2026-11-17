package com.deskwoot.sdk.store

import android.content.Context
import com.deskwoot.sdk.model.IsoTime
import com.deskwoot.sdk.model.array
import com.deskwoot.sdk.model.objects
import com.deskwoot.sdk.model.parseJsonObject
import com.deskwoot.sdk.model.string
import com.deskwoot.sdk.model.text
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The files of every inbox the app set up, as one folder
 * (`noBackupFilesDir/deskwoot/`): the pending push removals of all inboxes
 * in one file, and the end of every stored visitor at once, for a logout
 * that comes before `setup` or names no session. Blocking: call it on a
 * step of the [StoreQueue].
 */
internal object StoredVisitors {
    /** `{ "v": 2, "removals": [{ widgetToken, bearer, at (ISO) }, oldest first] }`. */
    const val REMOVALS_FILE: String = "push-removals.json"

    private const val VISITOR_SUFFIX = ".visitor.json"
    private const val CACHE_SUFFIX = ".cache.json"
    private const val OLD_REMOVALS_SUFFIX = ".push-removals.json"

    /** `noBackupFilesDir/deskwoot/`, never backed up; Android creates it when asked, so ask off the main thread. */
    fun folder(context: Context): File = File(context.noBackupFilesDir, "deskwoot")

    fun readRemovals(file: File): List<PushRemoval> {
        val json = parseJsonObject(readText(file)) ?: return emptyList()
        return json.array("removals")?.objects().orEmpty().mapNotNull { entry ->
            val token = entry.text("widgetToken") ?: return@mapNotNull null
            val bearer = entry.text("bearer") ?: return@mapNotNull null
            val at = IsoTime.parse(entry.string("at")) ?: return@mapNotNull null
            PushRemoval(token, bearer, at)
        }
    }

    /** An empty list deletes the file. */
    fun writeRemovals(file: File, removals: List<PushRemoval>) {
        if (removals.isEmpty()) {
            deleteWithTemp(file)
            return
        }
        val array = JSONArray()
        for (removal in removals) {
            array.put(JSONObject().put("widgetToken", removal.widgetToken).put("bearer", removal.bearer).put("at", IsoTime.format(removal.at)))
        }
        writeAtomically(file, JSONObject().put("v", 2).put("removals", array).toString())
    }

    /**
     * Every stored visitor of every inbox ends, except the files of
     * [exceptToken] (the inbox a running session wipes itself): a visitor
     * whose record says a push registration was sent leaves a [PushRemoval]
     * (written before its record is deleted), then the visitor records and
     * the thread caches go. Older removal lists of an earlier version go
     * too. Returns the widget tokens of the visitors that ended (known from
     * their records), so their notifications can go as well.
     */
    fun forgetAll(directory: File, exceptToken: String?, now: Long): Set<String> {
        val kept = exceptToken?.let(::storeKey)
        val files = directory.listFiles().orEmpty().filter { file ->
            // A temporary file of a write that died may hold a bearer too.
            val name = file.name.removeSuffix(".tmp")
            (kept == null || !name.startsWith(kept)) &&
                (name.endsWith(VISITOR_SUFFIX) || name.endsWith(CACHE_SUFFIX) || (name.endsWith(OLD_REMOVALS_SUFFIX) && name != REMOVALS_FILE))
        }
        val tokens = LinkedHashSet<String>()
        val removals = ArrayList<PushRemoval>()
        for (file in files) {
            if (!file.name.endsWith(VISITOR_SUFFIX)) continue
            val json = parseJsonObject(readText(file)) ?: continue
            val record = VisitorRecord.fromJson(json)
            val token = json.text("widgetToken") ?: continue
            tokens += token
            val bearer = record.bearer ?: continue
            if (record.pushRegistered) removals += PushRemoval(token, bearer, now)
        }
        if (removals.isNotEmpty()) {
            val list = File(directory, REMOVALS_FILE)
            writeRemovals(list, PushRemoval.merge(PushRemoval.fresh(readRemovals(list), now), removals))
        }
        files.forEach(::deleteWithTemp)
        return tokens
    }
}
