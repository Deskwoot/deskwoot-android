package com.deskwoot.sdk.ui

import android.provider.Settings
import android.view.View
import android.widget.EditText
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.R
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * The end of a visitor resets the messenger inside the call that ended
 * them, before the app's next call is handled: nothing of them stays on
 * screen, and what the app asks right after is kept for the next visitor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisitorEndTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()
    private val anna = DeskwootUser("u_anna", "a".repeat(64))
    private val ben = DeskwootUser("u_ben", "b".repeat(64))
    private lateinit var s: ScriptedSession

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
        s = ScriptedSession()
        s.http.on("POST /api/widget/identity/verify") { call -> ok(json("contactIdentifier" to "wb_" + call.body?.optJSONObject("user")?.optString("identifier"))) }
        // Anna has a conversation; Ben has none yet.
        s.http.on("GET /api/widget/conversations") { call ->
            val anna = call.headers.entries.any { it.value == "wb_u_anna" }
            when {
                call.query["list"] == "1" -> ok(json("conversations" to emptyList<Any>()))
                anna -> ok(json("conversationId" to "c_anna", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0))
                else -> ok(json("conversationId" to null))
            }
        }
        s.http.on("GET /api/widget/messages") { call -> ok(pollAnswer(row("m1", content = "Anna's order 4711", conversationId = call.query["conversationId"]))) }
        s.install()
    }

    @After
    fun tearDown() {
        controllers.forEach { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
        UiMemory.clear()
    }

    private fun open(entry: DeskwootActivity.Entry): ActivityController<DeskwootActivity> {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry))
        controllers += controller
        controller.setup()
        idle()
        return controller
    }

    private fun DeskwootActivity.shows(id: Int): Boolean = findViewById<View>(id)?.isShown == true

    /** Anna signed in, her conversation open with a half written draft. */
    private fun annaWriting(): ActivityController<DeskwootActivity> {
        Deskwoot.identify(anna)
        idle()
        assertEquals("c_anna", s.session.state.value.thread?.conversationId)
        val controller = open(DeskwootActivity.Entry.Conversation)
        val a = controller.get()
        assertTrue(a.shows(R.id.dw_thread_screen))
        a.findViewById<EditText>(R.id.dw_composer_input).text.append("Anna's draft")
        idle()
        return controller
    }

    @Test
    fun `identify of another user and at once presentNewConversation keeps that text for the new user`() {
        val controller = annaWriting()
        val a = controller.get()

        Deskwoot.identify(ben)
        Deskwoot.presentNewConversation(a, "Ben's question about invoice 88")
        // The system hands the new intent over before anything else of the main loop runs.
        controller.newIntent(checkNotNull(shadowOf(a).nextStartedActivity))
        assertTrue(idleUntil { s.session.state.value.identity == VisitorIdentity.Verified("u_ben") && a.shows(R.id.dw_composer_input) })
        idle()
        assertEquals("Ben's question about invoice 88", a.findViewById<EditText>(R.id.dw_composer_input).text.toString())
        assertTrue(a.shows(R.id.dw_thread_screen))
        assertNull("nothing of Anna's conversation", s.session.state.value.thread?.conversationId)
        assertEquals("", UiMemory.draft("c_anna"))
    }

    @Test
    fun `a logout throws the screens away inside the call, before the app's next call`() {
        val controller = annaWriting()
        val a = controller.get()
        Deskwoot.logout()
        // No main loop turn in between.
        assertNull(a.findViewById(R.id.dw_thread_screen))
        assertNull(a.findViewById(R.id.dw_composer_input))
        assertEquals("", UiMemory.draft("c_anna"))
        idle()
        assertTrue(a.shows(R.id.dw_start_screen))
        assertNull(s.session.state.value.thread?.conversationId)
    }

    @Test
    fun `a state published before the end of the visitor is never drawn again`() {
        val controller = annaWriting()
        val a = controller.get()
        val before = s.session.state.value
        Deskwoot.logout()
        // presentConversation right after the logout: the published state is still Anna's, with her running conversation.
        assertTrue(s.session.state.value === before)
        controller.newIntent(DeskwootActivity.intent(app, DeskwootActivity.Entry.Conversation))
        assertTrue("nothing drawn from Anna's state", routes(a).none { it == Route.Thread("c_anna") })
        idle()
        assertTrue("the new visitor gets a new conversation", a.shows(R.id.dw_composer_input))
        assertTrue(routes(a).none { it == Route.Thread("c_anna") })
        assertNull("Anna's conversation was never opened for the next visitor", s.session.state.value.thread?.conversationId)
        assertNull(s.store.record.lastConversationId)
    }

    private fun routes(a: DeskwootActivity): List<Route> {
        val field = DeskwootActivity::class.java.getDeclaredField("messenger").apply { isAccessible = true }
        val messenger = field.get(a) as Messenger
        return if (messenger.started) messenger.screens.map { it.route } else emptyList()
    }
}
