package com.deskwoot.sdk.ui

import android.provider.Settings
import android.view.View
import android.widget.EditText
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.iso
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.ui.ScriptedSession.Companion.helpConfig
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The inbox changes its look while the messenger is open: it is built again
 * in the new look on the screen the visitor is on (the same conversation,
 * draft, list or article), never by replaying how it was opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookChangeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()
    private lateinit var s: ScriptedSession

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
        s = ScriptedSession()
        var sentOnce = false
        s.http.on("POST /api/widget/messages") {
            sentOnce = true
            sent(conversationId = "c1", bearer = "wb_minted", newConversation = true)
        }
        s.http.on("GET /api/widget/conversations") { call ->
            when {
                !sentOnce -> if (call.query["list"] == "1") ok(json("conversations" to emptyList<Any>())) else ok(json("conversationId" to null))
                call.query["list"] == "1" -> ok(json("conversations" to listOf(entry("c1"))))
                else -> ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0))
            }
        }
        s.http.on("GET /api/widget/messages") { call ->
            if (call.query["conversationId"] == "c1") ok(pollAnswer(row("m1", type = "INCOMING", content = "Order 4711", conversationId = "c1"))) else ok(pollAnswer())
        }
        s.install()
    }

    @After
    fun tearDown() {
        controllers.forEach { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
        UiMemory.clear()
    }

    private fun entry(id: String) = json(
        "id" to id,
        "status" to "OPEN",
        "lastActivityAt" to iso(-60),
        "unread" to 0,
        "lastMessage" to row("m-$id", type = "INCOMING", content = "Order 4711", at = -60, conversationId = id),
    )

    private fun open(entry: DeskwootActivity.Entry): ActivityController<DeskwootActivity> {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry))
        controllers += controller
        controller.setup()
        idle()
        return controller
    }

    /** Some view with [id] is on screen (covered screens keep theirs, hidden). */
    private fun DeskwootActivity.shows(id: Int): Boolean {
        fun any(v: View): Boolean = (v.id == id && v.isShown) || (v is android.view.ViewGroup && (0 until v.childCount).any { any(v.getChildAt(it)) })
        return any(window.decorView)
    }

    private fun messenger(a: DeskwootActivity): Messenger =
        DeskwootActivity::class.java.getDeclaredField("messenger").apply { isAccessible = true }.get(a) as Messenger

    private fun look(design: String) {
        s.config = helpConfig("widgetDesign" to design)
        s.session.refresh()
        idle()
    }

    private fun composer(a: DeskwootActivity): String = a.findViewById<EditText>(R.id.dw_composer_input).text.toString()

    @Test
    fun `Klartext to Direkt and back keeps the conversation and the draft, and the app's text is used once`() {
        val a = open(DeskwootActivity.Entry.New("Order 4711")).get()
        assertEquals("Order 4711", composer(a))
        a.findViewById<View>(R.id.dw_composer_send).performClick()
        assertTrue(idleUntil { s.session.state.value.thread?.conversationId == "c1" })
        assertEquals("", composer(a))

        look("direkt")
        assertTrue("Direkt's chat on its start page", a.shows(R.id.dw_header_conversations))
        assertEquals(listOf<Route>(Route.Start), messenger(a).routes)
        assertEquals("the same conversation", "c1", s.session.state.value.thread?.conversationId)
        assertEquals("the app's text is not put back", "", composer(a))
        assertEquals(1, s.http.calls("POST /api/widget/messages").size)

        a.findViewById<EditText>(R.id.dw_composer_input).text.append("half written")
        idle()
        look("klartext")
        assertEquals(listOf(Route.Start, Route.Thread("c1")), messenger(a).routes)
        assertTrue(a.shows(R.id.dw_thread_screen))
        assertFalse(a.shows(R.id.dw_header_conversations))
        assertEquals("c1", s.session.state.value.thread?.conversationId)
        assertEquals("half written", composer(a))
        // Back leads to Klartext's start page, as from any thread.
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(a.shows(R.id.dw_start_screen))
        assertEquals("c1", s.session.state.value.thread?.conversationId)
    }

    @Test
    fun `an open article stays open, a sheet over Direkt's chat, its own screen in Klartext and Empfang`() {
        val a = open(DeskwootActivity.Entry.Help).get()
        a.findViewById<View>(R.id.dw_collection_row).performClick()
        idle()
        a.findViewById<View>(R.id.dw_article_row).performClick()
        idle()
        val trail = listOf(Route.Help, Route.Collection("col1", "Billing"), Route.Article("change-plan"))
        assertEquals(trail, messenger(a).routes)

        look("direkt")
        assertEquals(listOf<Route>(Route.Start) + trail, messenger(a).routes)
        assertTrue("the article in its sheet", a.shows(R.id.dw_sheet) && a.shows(R.id.dw_article_screen))

        look("empfang")
        assertEquals(trail, messenger(a).routes)
        assertTrue(a.shows(R.id.dw_article_screen))
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("the collection is behind it again", a.shows(R.id.dw_collection_screen))
    }

    @Test
    fun `Direkt's list over the chat becomes Empfang's list over the thread`() {
        val a = open(DeskwootActivity.Entry.New(null)).get()
        a.findViewById<EditText>(R.id.dw_composer_input).text.append("Hello")
        idle()
        a.findViewById<View>(R.id.dw_composer_send).performClick()
        assertTrue(idleUntil { s.session.state.value.thread?.conversationId == "c1" })
        look("direkt")
        a.findViewById<View>(R.id.dw_header_conversations).performClick()
        idle()
        assertEquals(listOf(Route.Start, Route.Conversations), messenger(a).routes)

        look("empfang")
        assertEquals(listOf(Route.Start, Route.Thread("c1"), Route.Conversations), messenger(a).routes)
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("back from the list is the conversation", a.shows(R.id.dw_thread_screen))
        assertEquals("c1", s.session.state.value.thread?.conversationId)
    }

    @Test
    fun `a new look that comes with a new theme makes the Activity again on the same conversation`() {
        Deskwoot.theme = Deskwoot.Theme.INBOX
        val controller = open(DeskwootActivity.Entry.New("Order 4711"))
        controller.get().findViewById<View>(R.id.dw_composer_send).performClick()
        assertTrue(idleUntil { s.session.state.value.thread?.conversationId == "c1" })
        s.config = helpConfig("widgetDesign" to "direkt", "widgetTheme" to "dark")
        s.session.refresh()
        idle()
        val again = controller.get()
        assertEquals("Direkt's chat in dark", listOf<Route>(Route.Start), messenger(again).routes)
        assertTrue(again.shows(R.id.dw_header_conversations))
        assertEquals("c1", s.session.state.value.thread?.conversationId)
        assertEquals("", composer(again))
    }

    @Test
    fun `the trail of one look in the other`() {
        val c1 = Route.Thread("c1")
        val sheets = listOf(Route.Help, Route.Collection("a", "A"), Route.Article("x"))
        // Into Direkt: a thread is the chat on the start page; the rest goes over it as sheets.
        assertEquals(listOf<Route>(Route.Start), LookSwitch.routes(listOf(Route.Start, c1), KlartextDesign, DirektDesign, "c1"))
        assertEquals(listOf(Route.Start, Route.Conversations), LookSwitch.routes(listOf(Route.Start, c1, Route.Conversations), EmpfangDesign, DirektDesign, "c1"))
        assertEquals(listOf<Route>(Route.Start) + sheets, LookSwitch.routes(sheets, KlartextDesign, DirektDesign, null))
        assertEquals(listOf(Route.Start, Route.Gate), LookSwitch.routes(listOf(Route.Start, Route.Gate), KlartextDesign, DirektDesign, null))
        // Out of Direkt: the chat is the thread screen over the start page; the help is a view of its own.
        assertEquals(listOf(Route.Start, c1), LookSwitch.routes(listOf(Route.Start), DirektDesign, KlartextDesign, "c1"))
        assertEquals(listOf(Route.Start, Route.Thread(null)), LookSwitch.routes(listOf(Route.Start), DirektDesign, EmpfangDesign, null))
        assertEquals(sheets, LookSwitch.routes(listOf<Route>(Route.Start) + sheets, DirektDesign, KlartextDesign, "c1"))
        assertEquals(listOf(Route.Start, c1, Route.Article("x")), LookSwitch.routes(listOf(Route.Start, Route.Article("x")), DirektDesign, EmpfangDesign, "c1"))
        assertEquals(listOf(Route.Start, Route.Gate), LookSwitch.routes(listOf(Route.Start, Route.Gate), DirektDesign, KlartextDesign, null))
        // Klartext and Empfang share their trails.
        val trail = listOf(Route.Start, c1, Route.Conversations)
        assertEquals(trail, LookSwitch.routes(trail, KlartextDesign, EmpfangDesign, "c1"))
        assertEquals(trail, LookSwitch.routes(trail, EmpfangDesign, KlartextDesign, "c1"))
    }
}
