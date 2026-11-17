package com.deskwoot.sdk.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.provider.Settings
import android.view.View
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.R
import com.deskwoot.sdk.engine.VisitorKey
import com.deskwoot.sdk.iso
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A notification's tap goes by the visitor key of its push, on the real
 * session: the stored signed in user's waits for the app's identify (at
 * most 10 s from the tap), the visitor's own opens, anybody else's shows the
 * start page, and a conversation the server shows nothing of ends there too.
 * A tap after the app's task ended puts the app's launch screen under the
 * messenger; a tap delivered to the open messenger survives a recreate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTapTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()
    private val anna = DeskwootUser("u_anna", "a".repeat(64))
    private val bob = DeskwootUser("u_bob", "b".repeat(64))

    /** The app's own first screen, for the tap that arrives after the app's task ended. */
    class AppHome : Activity()

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
    }

    @After
    fun tearDown() {
        controllers.forEach { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
        UiMemory.clear()
        idle()
    }

    private fun entry(id: String, minutesAgo: Int) = json(
        "id" to id,
        "status" to "OPEN",
        "lastActivityAt" to iso(-minutesAgo * 60L),
        "unread" to 1,
        "lastMessage" to row("m-$id", content = "Reply in $id", at = -minutesAgo * 60L, conversationId = id, sender = json("name" to "Mia")),
    )

    /**
     * A session whose store holds [record]; the list names c1 and c2 (the
     * restore c1), and the server shows rows of exactly those (and c9) to the
     * bearers in [owners]. The restore, the list and the poll answer on a
     * later turn of the main loop, as over a network: the Activity is on
     * screen by then.
     */
    private fun session(
        record: VisitorRecord,
        owners: Set<String> = setOfNotNull(record.bearer),
        listGate: CompletableDeferred<Unit>? = null,
    ): ScriptedSession {
        val s = ScriptedSession(record = record)
        s.http.on("GET /api/widget/conversations") { call ->
            yield()
            if (call.query["list"] == "1") {
                listGate?.await()
                ok(json("conversations" to listOf(entry("c1", 1), entry("c2", 20))))
            } else {
                ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 1))
            }
        }
        s.http.on("GET /api/widget/messages") { call ->
            yield()
            val id = call.query["conversationId"]
            if (call.contact in owners && id in setOf("c1", "c2", "c9")) ok(pollAnswer(row("m-$id", content = "Reply in $id", conversationId = id))) else ok(pollAnswer())
        }
        s.http.on("POST /api/widget/identity/verify") { call ->
            ok(json("contactIdentifier" to if (call.body?.optString("identifier") == "u_bob") "wb_bob" else "wb_anna"))
        }
        return s
    }

    private fun signedIn(): ScriptedSession = session(VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna"))

    /** The messenger as a tap on the notification of [conversationId] opens it, the app's task alive unless [taskGone]. */
    private fun tap(s: ScriptedSession, conversationId: String, visitor: String?, taskGone: Boolean = false): ActivityController<DeskwootActivity> {
        val intent = DeskwootActivity.tapIntent(app, DeskwootActivity.Entry.Open(conversationId), s.token, visitor)
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, intent)
        controllers += controller
        shadowOf(controller.get()).setIsTaskRoot(taskGone)
        controller.setup()
        idle()
        return controller
    }

    private fun DeskwootActivity.shows(id: Int): Boolean = findViewById<View>(id)?.isShown == true

    private fun polled(s: ScriptedSession, id: String): Boolean = s.http.calls("GET /api/widget/messages").any { it.query["conversationId"] == id }

    @Test
    fun `a tap for the stored signed in user waits for identify and then opens that conversation`() {
        val s = signedIn().install()
        val a = tap(s, "c2", VisitorKey.of("wb_anna")).get()
        repeat(3) { idle() }
        assertTrue("the loading state, with its close button", a.shows(R.id.dw_loading) && a.shows(R.id.dw_close))
        assertFalse("never the start page of an anonymous visitor", a.shows(R.id.dw_start_screen))
        assertFalse(a.shows(R.id.dw_thread_screen))

        Deskwoot.identify(anna)
        assertTrue(idleUntil { a.shows(R.id.dw_thread_screen) })
        assertEquals(VisitorIdentity.Verified("u_anna"), s.session.state.value.identity)
        assertEquals("c2", s.session.state.value.thread?.conversationId)
        assertEquals("wb_anna", s.http.calls("GET /api/widget/messages").last { it.query["conversationId"] == "c2" }.contact)
    }

    @Test
    fun `a tap for the stored signed in user shows the start page when no identify came within 10 s`() {
        val s = signedIn().install()
        val a = tap(s, "c2", VisitorKey.of("wb_anna")).get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertTrue("still waiting after 9 s", a.shows(R.id.dw_loading))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        idle()
        assertTrue("what the messenger shows without a tap", a.shows(R.id.dw_start_screen))
        assertFalse(a.shows(R.id.dw_loading))

        // An identify after that does not bring the dropped tap back.
        Deskwoot.identify(anna)
        assertTrue(idleUntil { s.session.state.value.identitySettled && s.session.state.value.visitorLoaded })
        repeat(3) { idle() }
        assertFalse(a.shows(R.id.dw_thread_screen))
        assertFalse(polled(s, "c2"))
    }

    @Test
    fun `identify of another user drops a tap for the stored signed in user`() {
        val s = signedIn().install()
        val a = tap(s, "c2", VisitorKey.of("wb_anna")).get()
        assertTrue(a.shows(R.id.dw_loading))
        Deskwoot.identify(bob)
        assertTrue(idleUntil { a.shows(R.id.dw_start_screen) && s.session.state.value.identity == VisitorIdentity.Verified("u_bob") })
        repeat(3) { idle() }
        assertFalse(a.shows(R.id.dw_thread_screen))
        assertFalse("nothing of the stored user's conversation was asked for", polled(s, "c2"))
    }

    @Test
    fun `a logout drops a tap for the stored signed in user`() {
        val s = signedIn().install()
        val a = tap(s, "c2", VisitorKey.of("wb_anna")).get()
        assertTrue(a.shows(R.id.dw_loading))
        Deskwoot.logout()
        assertTrue(idleUntil { a.shows(R.id.dw_start_screen) })
        repeat(3) { idle() }
        assertFalse(a.shows(R.id.dw_thread_screen))
        assertFalse(polled(s, "c2"))
        assertNull(s.session.state.value.thread?.conversationId)
    }

    @Test
    fun `a tap for the stored signed in user opens when the verification gives the user a new bearer`() {
        val s = session(VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna"), owners = setOf("wb_anna", "wb_anna_new"))
        s.http.on("POST /api/widget/identity/verify") { ok(json("contactIdentifier" to "wb_anna_new")) }
        s.install()
        val a = tap(s, "c2", VisitorKey.of("wb_anna")).get()
        Deskwoot.identify(anna)
        assertTrue(idleUntil { a.shows(R.id.dw_thread_screen) })
        assertEquals("c2", s.session.state.value.thread?.conversationId)
        assertEquals("wb_anna_new", s.store.record.bearer)
        assertTrue("the replaced bearer's pushes are still the user's", s.session.pushVisitor(VisitorKey.of("wb_anna")) == com.deskwoot.sdk.engine.PushVisitor.ACTIVE)
    }

    @Test
    fun `the visitor's own key opens the conversation even when the list does not name it`() {
        val s = session(VisitorRecord(bearer = "wa_known")).install()
        val a = tap(s, "c9", VisitorKey.of("wa_known")).get()
        assertTrue(idleUntil { a.shows(R.id.dw_thread_screen) && s.session.ownsConversation("c9") == true })
        assertEquals("c9", s.session.state.value.thread?.conversationId)
        assertEquals("once its rows came, it is remembered", "c9", s.store.record.lastConversationId)
    }

    @Test
    fun `a tapped conversation the server shows nothing of ends on the start page`() {
        val s = session(VisitorRecord(bearer = "wa_known")).install()
        val a = tap(s, "c_other", VisitorKey.of("wa_known")).get()
        assertTrue(idleUntil { a.shows(R.id.dw_start_screen) })
        assertTrue(polled(s, "c_other"))
        assertFalse(a.shows(R.id.dw_thread_screen))
        assertNotEquals("c_other", s.session.state.value.thread?.conversationId)
        assertNotEquals("c_other", s.store.record.lastConversationId)
    }

    @Test
    fun `a key of nobody this device knows, or none, opens the start page`() {
        val s = session(VisitorRecord(bearer = "wa_known")).install()
        val a = tap(s, "c2", VisitorKey.of("wb_somebody_else")).get()
        assertTrue(a.shows(R.id.dw_start_screen))
        assertFalse(a.shows(R.id.dw_thread_screen))
        val b = tap(s, "c2", null).get()
        assertTrue(b.shows(R.id.dw_start_screen))
        assertFalse(polled(s, "c2"))
    }

    @Test
    fun `a tap delivered to the open messenger becomes its intent and survives a recreate before it is applied`() {
        val listGate = CompletableDeferred<Unit>()
        val s = session(VisitorRecord(bearer = "wa_known"), listGate = listGate).install()
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, DeskwootActivity.Entry.Start))
        controllers += controller
        controller.setup()
        idle()
        val tapped = DeskwootActivity.tapIntent(app, DeskwootActivity.Entry.Open("c2"), s.token, VisitorKey.of("wa_known"))
        controller.newIntent(tapped)
        idle()
        assertEquals("c2", (DeskwootActivity.Entry.from(controller.get().intent) as DeskwootActivity.Entry.Open).conversationId)
        assertTrue("it waits while the session loads", controller.get().shows(R.id.dw_loading))

        controller.recreate()
        idle()
        listGate.complete(Unit)
        assertTrue(idleUntil { s.session.state.value.thread?.conversationId == "c2" && controller.get().shows(R.id.dw_thread_screen) })
    }

    @Test
    fun `a tap after the app's task ended puts the app's launch screen under the messenger and ends itself`() {
        val home = ComponentName(app, AppHome::class.java)
        shadowOf(app.packageManager).addActivityIfNotPresent(home)
        shadowOf(app.packageManager).addIntentFilterForActivity(home, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        val s = session(VisitorRecord(bearer = "wa_known")).install()
        val first = tap(s, "c2", VisitorKey.of("wa_known"), taskGone = true).get()
        assertTrue("the instance that would be the task's root ends", first.isFinishing)

        val started = listOfNotNull(shadowOf(first).nextStartedActivity, shadowOf(first).nextStartedActivity)
        assertEquals(2, started.size)
        val launch = started.single { it.component == home }
        assertTrue("the root of a fresh task", launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val again = started.single { it.component?.className == DeskwootActivity::class.java.name }
        assertEquals("in the launch screen's task", 0, again.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals("c2", (DeskwootActivity.Entry.from(again) as DeskwootActivity.Entry.Open).conversationId)

        // The messenger on top opens the tapped conversation and never passes the tap on again.
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, again)
        controllers += controller
        controller.setup()
        val top = controller.get()
        assertTrue(idleUntil { top.shows(R.id.dw_thread_screen) })
        assertFalse(top.isFinishing)
        assertNull(shadowOf(top).nextStartedActivity)
        assertEquals("c2", s.session.state.value.thread?.conversationId)
    }

    @Test
    fun `with the app's task alive a tap opens the messenger on top and starts nothing else`() {
        val home = ComponentName(app, AppHome::class.java)
        shadowOf(app.packageManager).addActivityIfNotPresent(home)
        shadowOf(app.packageManager).addIntentFilterForActivity(home, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        val s = session(VisitorRecord(bearer = "wa_known")).install()
        val a = tap(s, "c2", VisitorKey.of("wa_known")).get()
        assertFalse(a.isFinishing)
        assertNull(shadowOf(a).nextStartedActivity)
        assertTrue(idleUntil { a.shows(R.id.dw_thread_screen) })
    }

    @Test
    fun `after a process death the messenger sets its inbox up at once, also with calls of the app queued from other threads`() {
        val s = session(VisitorRecord(bearer = "wa_known"))
        // The process was started by the tap: no session yet, and the app already called something from a worker thread.
        ScreenHarness.install(null)
        Deskwoot.makeSession = { _, _, _ -> s.session }
        val worker = Thread { Deskwoot.inAppNotificationsEnabled = true; Deskwoot.language = null }
        worker.start()
        worker.join()
        val a = tap(s, "c2", VisitorKey.of("wa_known")).get()
        assertFalse("the messenger found its session", a.isFinishing)
        assertEquals(s.session, Deskwoot.currentSession)
        assertTrue(idleUntil { a.shows(R.id.dw_thread_screen) })
    }
}
