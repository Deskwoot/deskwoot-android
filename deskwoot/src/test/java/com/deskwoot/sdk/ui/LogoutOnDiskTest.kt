package com.deskwoot.sdk.ui

import android.content.pm.ProviderInfo
import android.os.Looper
import com.deskwoot.sdk.AppProcess
import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.FakeHttp
import com.deskwoot.sdk.FakeRealtime
import com.deskwoot.sdk.SilentLogger
import com.deskwoot.sdk.api.FileFetcher
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.engine.ConversationEngine
import com.deskwoot.sdk.engine.DeviceClock
import com.deskwoot.sdk.engine.UploadRead
import com.deskwoot.sdk.help.HelpCenterClient
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.store.FileDiskCache
import com.deskwoot.sdk.store.FileVisitorStore
import com.deskwoot.sdk.store.PushRemoval
import com.deskwoot.sdk.store.StoredVisitors
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.store.storeKey
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.files.DeskwootFileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A logout on the real files: the end of a visitor runs to its end also when
 * the app sets up another inbox right after, the stored visitor is deleted
 * before `logout` returns, a logout before `setup` (or in a launch that never
 * calls it) deletes what every inbox stored and leaves removal records only
 * where a push registration was sent, which the next setup of any inbox
 * sends. In another process of the app `setup` waits until that process
 * shows the messenger or handles a push.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogoutOnDiskTest {
    private val app = RuntimeEnvironment.getApplication()
    private val folder: File get() = StoredVisitors.folder(app)
    private val fcm = "f".repeat(20) + ":APA91b" + "x".repeat(140)

    @Before
    fun setUp() {
        UiMemory.clear()
        folder.deleteRecursively()
        ScreenHarness.install(null)
        facadeField("idleSetup").set(Deskwoot, null)
        // Each test has an application of its own (with its own folders); an app's process has one.
        facadeField("appContext").set(Deskwoot, null)
        Deskwoot.setPushToken(null)
        idle()
    }

    @After
    fun tearDown() {
        AppProcess.nameForTests = null
        // present without an Activity takes the app's current one as the presenter: no later test inherits it.
        DeskwootActivity.presenter = null
        folder.deleteRecursively()
        File(app.cacheDir, "deskwoot").deleteRecursively()
        UiMemory.clear()
    }

    private fun visitorFile(token: String) = File(folder, "${storeKey(token)}.visitor.json")

    private val removalsFile: File get() = File(folder, StoredVisitors.REMOVALS_FILE)

    private fun store(token: String, record: VisitorRecord) = runBlocking { FileVisitorStore({ folder }, token, Dispatchers.IO).write(record) }

    private fun server(): FakeHttp = FakeHttp().apply {
        on("GET /api/widget/config") { ok(ScriptedSession.helpConfig()) }
        on("GET /api/widget/conversations") { call -> if (call.query["list"] == "1") ok(json("conversations" to emptyList<Any>())) else ok(json("conversationId" to null)) }
        on("GET /api/widget/messages") { ok(pollAnswer()) }
        on("POST /api/widget/heartbeat") { ok(null) }
        on("POST /api/widget/push/devices") { ok(json("ok" to true)) }
        on("POST /api/widget/push/devices/remove") { ok(json("ok" to true)) }
    }

    /** A real engine on the real files of [token], over [http]. */
    private fun fileSession(token: String, http: FakeHttp): ChatSession {
        val api = WidgetApi(http, { BASE_URL }, token, Dispatchers.Main.immediate)
        val engine = ConversationEngine(
            api = api, realtime = FakeRealtime(), store = FileVisitorStore({ folder }, token, Dispatchers.IO), cache = FileDiskCache({ folder }, token, Dispatchers.IO),
            clock = DeviceClock, logger = SilentLogger, mainDispatcher = Dispatchers.Main.immediate, postDispatcher = Dispatchers.Main,
            appLanguage = { "en" }, uploadReader = { _, _ -> UploadRead.TooLarge }, appId = "com.example.shop", sdkVersion = "1.0.0", initialForeground = true,
            onMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        )
        return ChatSession(token, engine, HelpCenterClient(api, engine::helpContext), api, FileFetcher { _, _ -> null })
    }

    private fun startOn(token: String, http: FakeHttp = server()): ChatSession {
        val session = fileSession(token, http)
        Deskwoot.makeSession = { _, t, _ -> if (t == token) session else error("no session for $t") }
        Deskwoot.setup(app, token)
        assertTrue(idleUntil { session.state.value.phase == Phase.READY })
        return session
    }

    private fun facadeField(name: String) = Deskwoot::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test
    fun `logout then at once setup of another inbox still deletes the old visitor and records the removal`() {
        val tokenA = "wt_wipe_a"
        store(tokenA, VisitorRecord(bearer = "wb_secret_a", pushRegistered = true))
        startOn(tokenA)
        val other = ScriptedSession()
        Deskwoot.makeSession = { _, token, _ -> if (token == other.token) other.session else error("no session for $token") }

        // The app signs its user out and switches to its other inbox in the same main thread turn.
        val started = System.nanoTime()
        Deskwoot.logout()
        val millis = (System.nanoTime() - started) / 1_000_000.0
        assertFalse("deleted inside the call", visitorFile(tokenA).exists())
        assertTrue("the removal record is on the disk first", removalsFile.exists())
        println("Deskwoot.logout of a registered visitor waited %.1f ms for the removal record and the deletion".format(millis))
        Deskwoot.setup(app, other.token)
        repeat(20) { idle() }

        assertFalse("the logged out visitor's bearer must not stay on disk", visitorFile(tokenA).exists())
        assertEquals(listOf("wb_secret_a"), StoredVisitors.readRemovals(removalsFile).map { it.bearer })
        assertEquals(tokenA, StoredVisitors.readRemovals(removalsFile).single().widgetToken)
    }

    @Test
    fun `logout deletes the stored visitor before it returns, and keeps a removal only where a registration was sent`() {
        val tokenA = "wt_wipe_c"
        store(tokenA, VisitorRecord(bearer = "wb_secret_c"))
        startOn(tokenA)

        val started = System.nanoTime()
        Deskwoot.logout()
        val millis = (System.nanoTime() - started) / 1_000_000.0
        // No main loop turn in between: deleted inside the call, which waited for the disk only that long.
        assertFalse(visitorFile(tokenA).exists())
        println("Deskwoot.logout on the main thread waited %.1f ms for the stored visitor to be deleted".format(millis))
        assertTrue("$millis ms", millis < 200.0)
        repeat(5) { idle() }
        assertFalse("never registered: no removal record either", removalsFile.exists())
        assertTrue("nothing of the visitor is left in the folder: ${folder.list()?.toList()}", folder.list().orEmpty().isEmpty())
    }

    @Test
    fun `a logout before setup deletes what every inbox stored at once and keeps a removal only where a registration was sent`() {
        store("wt_inbox_a", VisitorRecord(bearer = "wb_a", verifiedExternalId = "u_a", pushRegistered = true))
        store("wt_inbox_b", VisitorRecord(bearer = "wb_b"))
        File(folder, "${storeKey("wt_inbox_a")}.cache.json").writeText("{\"v\":1,\"identity\":\"verified:u_a\"}")
        val picture = File(app.cacheDir, "deskwoot/images/x.jpg").also { it.parentFile?.mkdirs(); it.writeText("pixels") }
        // Android created the SDK's FileProvider with the process; the app calls logout before any setup.
        Deskwoot.attach(app)

        Deskwoot.logout()
        assertFalse(visitorFile("wt_inbox_a").exists())
        assertFalse(visitorFile("wt_inbox_b").exists())
        assertTrue(idleUntil { !picture.exists() })
        assertEquals(listOf(PushRemoval("wt_inbox_a", "wb_a", StoredVisitors.readRemovals(removalsFile).single().at)), StoredVisitors.readRemovals(removalsFile))
        assertEquals(listOf(StoredVisitors.REMOVALS_FILE), folder.list()?.toList())

        // The next setup of any inbox sends it, naming the inbox it was registered for.
        val http = server()
        startOn("wt_inbox_c", http)
        Deskwoot.setPushToken(fcm)
        assertTrue(idleUntil { http.calls("POST /api/widget/push/devices/remove").isNotEmpty() && !removalsFile.exists() })
        val body = checkNotNull(http.calls("POST /api/widget/push/devices/remove").single().body)
        assertEquals("wt_inbox_a", body.getString("widgetToken"))
        assertEquals("wb_a", body.getString("contactIdentifier"))
        Deskwoot.setPushToken(null)
    }

    @Test
    fun `the SDK's FileProvider keeps the application context when Android creates it`() {
        facadeField("appContext").set(Deskwoot, null)
        val info = ProviderInfo().apply {
            authority = app.packageName + ".deskwoot.files"
            grantUriPermissions = true
            exported = false
        }
        Robolectric.buildContentProvider(DeskwootFileProvider::class.java).create(info)
        assertSame(app, facadeField("appContext").get(Deskwoot))
    }

    @Test
    fun `setup in another process of the app stays idle until that process shows the messenger`() {
        AppProcess.nameForTests = app.packageName + ":remote"
        val s = ScriptedSession()
        Deskwoot.makeSession = { _, _, _ -> s.session }
        Deskwoot.setup(app, s.token)
        repeat(5) { idle() }
        assertNull("no session", facadeField("session").get(Deskwoot))
        assertTrue("no network", s.http.calls.isEmpty())
        assertTrue("nothing written", s.store.steps.isEmpty())

        Deskwoot.present(app)
        assertTrue(idleUntil { s.session.state.value.phase == Phase.READY })
        assertSame(s.session, facadeField("session").get(Deskwoot))
    }

    @Test
    fun `a push handed over on a worker thread starts an idle setup of another process`() {
        AppProcess.nameForTests = app.packageName + ":remote"
        val s = ScriptedSession()
        Deskwoot.makeSession = { _, _, _ -> s.session }
        Deskwoot.setup(app, s.token)
        idle()
        assertNull(facadeField("session").get(Deskwoot))

        val woke = AtomicReference<ChatSession?>()
        val worker = thread { woke.set(Deskwoot.activeSession()) }
        assertTrue(idleUntil { !worker.isAlive })
        assertSame(s.session, woke.get())
        assertTrue(idleUntil { s.http.calls("GET /api/widget/config").isNotEmpty() })
    }
}
