package com.deskwoot.sdk.store

import com.deskwoot.sdk.BASE_TIME
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.ConversationStatus
import com.deskwoot.sdk.model.ConversationSummary
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.ThreadSegment
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.row
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The visitor record and the disk cache on disk (brief: VisitorStore, DiskCache). */
class StoreTest {
    private val folder: File = Files.createTempDirectory("deskwoot-store").toFile()

    @After
    fun removeFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun `the visitor record survives a round trip and two tokens never share one`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val store = FileVisitorStore({ File(folder, "deskwoot") }, "token-a", io)
        val other = FileVisitorStore({ File(folder, "deskwoot") }, "token-b", io)
        val record = VisitorRecord("wb_x", "u_1", BASE_TIME, "c1")
        store.write(record)

        assertEquals(record, store.read())
        assertEquals(VisitorRecord(), other.read())
        val file = checkNotNull(File(folder, "deskwoot").listFiles()).single()
        assertTrue(file.name.endsWith(".visitor.json"))
        val stored = JSONObject(file.readText())
        assertEquals(2, stored.getInt("v"))
        assertEquals("2026-11-16T10:00:00.000Z", stored.getString("lastReadAt"))
        assertEquals("the inbox, for a logout before setup", "token-a", stored.getString("widgetToken"))
        assertTrue("no registration sent: no flag", !stored.has("pushRegistered"))

        store.clear()
        assertEquals(VisitorRecord(), store.read())
    }

    @Test
    fun `the flag of a sent push registration survives a round trip and goes with its bearer`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val store = FileVisitorStore({ folder }, "token-a", io)
        val record = VisitorRecord(bearer = "wb_x", pushRegistered = true)
        store.write(record)
        assertEquals(record, store.read())
        assertTrue(JSONObject(File(folder, "${storeKey("token-a")}.visitor.json").readText()).getBoolean("pushRegistered"))

        assertEquals("the same bearer keeps it", record, record.withBearer("wb_x"))
        assertEquals(VisitorRecord(bearer = "wb_y"), record.withBearer("wb_y"))
        // A file of a version before the flag: no registration was sent as far as anyone knows.
        File(folder, "${storeKey("token-a")}.visitor.json").writeText("{\"v\":1,\"bearer\":\"wb_old\"}")
        assertEquals(VisitorRecord(bearer = "wb_old"), store.read())
    }

    @Test
    fun `pending push removals of every inbox share one list that survives a new store and a wipe, and goes when empty`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val dir = File(folder, "deskwoot")
        val store = FileVisitorStore({ dir }, "token-a", io)
        assertTrue(store.readPushRemovals().isEmpty())
        store.write(VisitorRecord(bearer = "wb_a"))
        val list = listOf(PushRemoval("token-b", "wb_old", BASE_TIME), PushRemoval("token-a", "wb_a", BASE_TIME + 1_000))
        store.writePushRemovals(list)

        // The visitor's record goes; the removals do not.
        store.clear()
        assertEquals(VisitorRecord(), store.read())
        // A new process reads the same file, and so does the store of every other inbox.
        val restarted = FileVisitorStore({ dir }, "token-a", io)
        assertEquals(list, restarted.readPushRemovals())
        assertEquals(list, FileVisitorStore({ dir }, "token-b", io).readPushRemovals())
        val file = File(dir, StoredVisitors.REMOVALS_FILE)
        val json = JSONObject(file.readText())
        assertEquals(2, json.getInt("v"))
        assertEquals("token-b", json.getJSONArray("removals").getJSONObject(0).getString("widgetToken"))
        assertEquals("2026-11-16T10:00:01.000Z", json.getJSONArray("removals").getJSONObject(1).getString("at"))

        // Once every removal went through, no bearer stays on the disk.
        restarted.writePushRemovals(emptyList())
        assertTrue(!file.exists())
        assertTrue(restarted.readPushRemovals().isEmpty())
        file.writeText("{ not json")
        assertTrue("a broken file reads as none", restarted.readPushRemovals().isEmpty())
    }

    @Test
    fun `the list of an earlier version, kept without asking whether a registration existed, is deleted unread`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val dir = File(folder, "deskwoot").also { it.mkdirs() }
        val old = File(dir, "${storeKey("token-a")}.push-removals.json").also { it.writeText("{\"v\":1,\"bearers\":[\"wb_logged_out\"]}") }
        assertTrue(FileVisitorStore({ dir }, "token-a", io).readPushRemovals().isEmpty())
        assertTrue(!old.exists())
    }

    @Test
    fun `removals keep their first time, hold at most 8 per inbox and expire after 30 days`() {
        val day = 24 * 60 * 60 * 1_000L
        val a = (1..9).map { PushRemoval("token-a", "wb_a$it", BASE_TIME + it) }
        val b = PushRemoval("token-b", "wb_b", BASE_TIME)
        val merged = PushRemoval.merge(listOf(b), a)
        assertEquals("the oldest of the inbox goes first, the other inbox keeps its own", listOf(b) + a.drop(1), merged)
        assertEquals(merged, PushRemoval.merge(merged, listOf(PushRemoval("token-a", "wb_a5", BASE_TIME + 99_000))))
        assertEquals(merged.single { it.bearer == "wb_a5" }.at, BASE_TIME + 5)

        val old = PushRemoval("token-a", "wb_old", BASE_TIME - 31 * day)
        val young = PushRemoval("token-a", "wb_young", BASE_TIME - 29 * day)
        assertEquals(listOf(young), PushRemoval.fresh(listOf(old, young), BASE_TIME))
        assertEquals("a clock that went back keeps them", listOf(old, young), PushRemoval.fresh(listOf(old, young), BASE_TIME - 40 * day))
    }

    @Test
    fun `the end of every stored visitor keeps a removal only where a registration was sent, and spares the inbox named`() {
        val dir = File(folder, "deskwoot").also { it.mkdirs() }
        fun visitor(token: String, json: String) = File(dir, "${storeKey(token)}.visitor.json").also { it.writeText(json) }
        val registered = visitor("token-a", "{\"v\":2,\"widgetToken\":\"token-a\",\"bearer\":\"wb_a\",\"pushRegistered\":true}")
        val unregistered = visitor("token-b", "{\"v\":2,\"widgetToken\":\"token-b\",\"bearer\":\"wb_b\"}")
        val older = visitor("token-c", "{\"v\":1,\"bearer\":\"wb_c\"}")
        val running = visitor("token-d", "{\"v\":2,\"widgetToken\":\"token-d\",\"bearer\":\"wb_d\",\"pushRegistered\":true}")
        val cacheA = File(dir, "${storeKey("token-a")}.cache.json").also { it.writeText("{}") }
        val cacheD = File(dir, "${storeKey("token-d")}.cache.json").also { it.writeText("{}") }
        val temp = File(dir, "${storeKey("token-b")}.visitor.json.tmp").also { it.writeText("{\"bearer\":\"wb_b\"}") }
        val oldList = File(dir, "${storeKey("token-c")}.push-removals.json").also { it.writeText("{\"v\":1,\"bearers\":[\"wb_x\"]}") }

        val ended = StoredVisitors.forgetAll(dir, exceptToken = "token-d", now = BASE_TIME)

        assertEquals(setOf("token-a", "token-b"), ended)
        assertEquals(listOf(PushRemoval("token-a", "wb_a", BASE_TIME)), StoredVisitors.readRemovals(File(dir, StoredVisitors.REMOVALS_FILE)))
        listOf(registered, unregistered, older, cacheA, temp, oldList).forEach { assertTrue("${it.name} deleted", !it.exists()) }
        assertTrue("the inbox a session runs for wipes its own", running.exists() && cacheD.exists())
    }

    @Test
    fun `the language of an older file is ignored and never written`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val store = FileVisitorStore({ folder }, "token-a", io)
        val file = File(folder, "${storeKey("token-a")}.visitor.json")
        file.writeText("{\"v\":1,\"bearer\":\"wb_x\",\"language\":\"fr\"}")
        val record = store.read()
        assertEquals(VisitorRecord(bearer = "wb_x"), record)
        store.write(record)
        assertTrue(!JSONObject(file.readText()).has("language"))
    }

    @Test
    fun `a broken file reads as a new visitor`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val store = FileVisitorStore({ folder }, "token-a", io)
        File(folder, "${storeKey("token-a")}.visitor.json").writeText("{ not json")
        assertEquals(VisitorRecord(), store.read())
    }

    @Test
    fun `the cache keeps rows and entries in the server's own shape`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val cache = FileDiskCache({ folder }, "token-a", io)
        val message = checkNotNull(Message.parse(row("m1", type = "INCOMING", content = "Hi")))
        val entry = checkNotNull(ConversationSummary.parse(json("id" to "c1", "status" to "OPEN", "lastActivityAt" to "2026-11-16T10:00:00.000Z", "unread" to 2)))
        val snapshot = CacheSnapshot(
            identity = "anonymous",
            config = WidgetConfig.parse(json("businessName" to "Acme")),
            conversationId = "c1",
            messages = listOf(message),
            conversations = listOf(entry),
            segments = listOf(ThreadSegment("c1", ConversationStatus.OPEN, null, BASE_TIME)),
            resolved = false,
            continuesAfterResolve = false,
        )
        cache.write(snapshot)
        val back = checkNotNull(cache.read())

        assertEquals("anonymous", back.identity)
        assertEquals("Acme", back.config?.businessName)
        assertEquals(listOf("m1"), back.messages.map { it.id })
        assertEquals("Hi", back.messages.single().content)
        assertEquals(2, back.conversations.single().unread)
        assertEquals(BASE_TIME, back.segments.single().createdAt)

        cache.clear()
        assertNull(cache.read())
    }

    @Test
    fun `a temporary file a dead write left behind is deleted, also by clear`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val dir = File(folder, "deskwoot").also { it.mkdirs() }
        val temp = File(dir, storeKey("token-a") + ".visitor.json.tmp")
        temp.writeText("{\"bearer\":\"wb_secret\"}")
        val store = FileVisitorStore({ dir }, "token-a", io)
        assertEquals(VisitorRecord(), store.read())
        assertTrue(!temp.exists())

        store.write(VisitorRecord(bearer = "wb_x"))
        temp.writeText("{\"bearer\":\"wb_secret\"}")
        store.clear()
        assertTrue(!temp.exists())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a record that exists but cannot be read is never overwritten`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val dir = File(folder, "deskwoot").also { it.mkdirs() }
        // A path that exists and throws on read stands in for a file the system will not open now.
        val blocked = File(dir, storeKey("token-a") + ".visitor.json").also { it.mkdirs() }
        val store = FileVisitorStore({ dir }, "token-a", io)
        assertEquals(VisitorRecord(), store.read())
        store.write(VisitorRecord(bearer = "wb_new"))
        assertTrue(blocked.isDirectory)
        // A logout still deletes it.
        store.clear()
        assertTrue(!blocked.exists())
        store.write(VisitorRecord(bearer = "wb_new"))
        assertEquals("wb_new", store.read().bearer)
    }
}
