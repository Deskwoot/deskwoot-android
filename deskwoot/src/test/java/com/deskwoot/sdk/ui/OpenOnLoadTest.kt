package com.deskwoot.sdk.ui

import android.provider.Settings
import android.view.View
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.iso
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.R
import com.deskwoot.sdk.row
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * A tapped notification opens its conversation once the session can say it
 * is the visitor's: a tap during the start waits under the loading state
 * (the newest tap wins), an id of somebody else opens the start page, and a
 * logout drops the waiting tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenOnLoadTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()

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
    }

    private fun entry(id: String, minutesAgo: Int) = json(
        "id" to id,
        "status" to "OPEN",
        "lastActivityAt" to iso(-minutesAgo * 60L),
        "unread" to 1,
        "lastMessage" to row("m-$id", content = "Reply in $id", at = -minutesAgo * 60L, conversationId = id, sender = json("name" to "Mia")),
    )

    /**
     * A returning visitor with conversations c1 (the newest, which the restore names), c2 and c3. The
     * list waits for [listGate], so the session stays loading until the test lets it answer.
     */
    private fun returningVisitor(listGate: CompletableDeferred<Unit>? = null, record: VisitorRecord = VisitorRecord(bearer = "wa_known")): ScriptedSession {
        val s = ScriptedSession(record = record)
        s.http.on("GET /api/widget/conversations") { call ->
            if (call.query["list"] == "1") {
                listGate?.await()
                ok(json("conversations" to listOf(entry("c1", 1), entry("c2", 20), entry("c3", 300))))
            } else {
                ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 1))
            }
        }
        s.http.on("GET /api/widget/messages") { call ->
            val id = call.query["conversationId"]
            ok(pollAnswer(row("m-$id", content = "Reply in $id", conversationId = id)))
        }
        return s
    }

    private fun open(entry: DeskwootActivity.Entry, token: String? = null): ActivityController<DeskwootActivity> {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry, token))
        controllers += controller
        controller.setup()
        idle()
        return controller
    }

    private fun DeskwootActivity.shows(id: Int): Boolean = findViewById<View>(id)?.isShown == true

    private fun tap(controller: ActivityController<DeskwootActivity>, conversationId: String) {
        controller.newIntent(DeskwootActivity.intent(app, DeskwootActivity.Entry.Open(conversationId)))
        idle()
    }

    @Test
    fun `a tap while the visitor's conversations load waits under the loading state, the newest tap wins`() {
        val listGate = CompletableDeferred<Unit>()
        val s = returningVisitor(listGate).install()
        assertEquals("the restore named c1", "c1", s.session.state.value.thread?.conversationId)
        assertFalse("the list has not answered", s.session.state.value.visitorLoaded)

        val controller = open(DeskwootActivity.Entry.Open("c2"))
        val a = controller.get()
        assertTrue("the loading state shows", a.shows(R.id.dw_loading))
        assertFalse("never the restored conversation", a.shows(R.id.dw_thread_screen))
        assertNull("never an empty new chat", a.findViewById<View>(R.id.dw_composer_input)?.takeIf { it.isShown })
        assertEquals("c1", s.session.state.value.thread?.conversationId)

        // A second tap while it still loads: only the newest counts.
        tap(controller, "c3")
        assertTrue(a.shows(R.id.dw_loading))

        listGate.complete(Unit)
        assertTrue(idleUntil { a.shows(R.id.dw_thread_screen) })
        assertEquals("c3", s.session.state.value.thread?.conversationId)
        assertFalse(a.shows(R.id.dw_loading))
        assertEquals("c3", s.store.record.lastConversationId)
        assertEquals("the thread of the tapped conversation is on top", Route.Thread("c3"), routes(a).last())
        assertTrue("c2 was never opened", s.http.calls("GET /api/widget/messages").none { it.query["conversationId"] == "c2" })
        // Back leads to the start page, as from any opened conversation.
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(a.shows(R.id.dw_start_screen))
    }

    @Test
    fun `a tap before the app called setup starts the inbox of the notification and opens it`() {
        val s = returningVisitor()
        // The process was started by the tap: no session yet.
        ScreenHarness.install(null)
        Deskwoot.makeSession = { _, _, _ -> s.session }
        val a = open(DeskwootActivity.Entry.Open("c2"), token = s.token).get()
        assertTrue(idleUntil { s.session.state.value.thread?.conversationId == "c2" })
        assertTrue(a.shows(R.id.dw_thread_screen))
        assertEquals(s.session, Deskwoot.currentSession)
    }

    @Test
    fun `an id that is not the visitor's opens the start page and is never stored`() {
        val s = returningVisitor().install()
        assertTrue(s.session.state.value.visitorLoaded)
        val controller = open(DeskwootActivity.Entry.Open("c_other_contact"))
        val a = controller.get()
        assertTrue(a.shows(R.id.dw_start_screen))
        assertFalse(a.shows(R.id.dw_thread_screen))
        assertNotEquals("c_other_contact", s.session.state.value.thread?.conversationId)
        assertNotEquals("c_other_contact", s.store.record.lastConversationId)
        assertTrue(s.http.calls("GET /api/widget/messages").none { it.query["conversationId"] == "c_other_contact" })

        // A tap on one of theirs while the messenger is open opens it at once.
        tap(controller, "c2")
        assertTrue(a.shows(R.id.dw_thread_screen))
        assertEquals("c2", s.session.state.value.thread?.conversationId)
        // And a stale one from there: the start page again, and the stale id is neither opened nor stored.
        tap(controller, "c_stale")
        assertTrue(a.shows(R.id.dw_start_screen))
        assertNotEquals("c_stale", s.session.state.value.thread?.conversationId)
        assertNotEquals("c_stale", s.store.record.lastConversationId)
        assertTrue(s.http.calls("GET /api/widget/messages").none { it.query["conversationId"] == "c_stale" })
    }

    @Test
    fun `a logout drops a tap that waits`() {
        val listGate = CompletableDeferred<Unit>()
        val s = returningVisitor(listGate).install()
        val a = open(DeskwootActivity.Entry.Open("c2")).get()
        assertTrue(a.shows(R.id.dw_loading))
        Deskwoot.logout()
        listGate.complete(Unit)
        assertTrue(idleUntil { a.shows(R.id.dw_start_screen) })
        assertNull(s.session.state.value.thread?.conversationId)
        assertFalse(a.shows(R.id.dw_thread_screen))
        assertTrue(s.http.calls("GET /api/widget/messages").none { it.query["conversationId"] == "c2" })
    }

    @Test
    fun `the card's conversation for a stored signed in user waits for identify and opens once it answered`() {
        // The real session: a stored verified identity waits for the app's identify, so whose the id is is not known yet.
        val s = returningVisitor(record = VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna"))
        s.http.on("POST /api/widget/identity/verify") { ok(json("contactIdentifier" to "wb_anna")) }
        s.install()
        assertNull(s.session.ownsConversation("c2"))
        val a = open(DeskwootActivity.Entry.Open("c2")).get()
        assertTrue("the anonymous start of a dormant identity shows nothing of anyone", a.shows(R.id.dw_loading))
        assertFalse(a.shows(R.id.dw_thread_screen))

        Deskwoot.identify(DeskwootUser("u_anna", "a".repeat(64)))
        assertTrue(idleUntil { a.shows(R.id.dw_thread_screen) })
        assertEquals(VisitorIdentity.Verified("u_anna"), s.session.state.value.identity)
        assertEquals("c2", s.session.state.value.thread?.conversationId)
    }

    private fun routes(a: DeskwootActivity): List<Route> {
        val field = DeskwootActivity::class.java.getDeclaredField("messenger").apply { isAccessible = true }
        return (field.get(a) as Messenger).routes
    }
}
