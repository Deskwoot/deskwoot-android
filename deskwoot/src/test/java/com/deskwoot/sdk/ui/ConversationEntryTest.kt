package com.deskwoot.sdk.ui

import android.provider.Settings
import android.view.View
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import org.junit.After
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `presentConversation` opens the visitor's running conversation; before the
 * visitor's conversations are known it enters the empty thread as it is,
 * never marked new, so the running one still comes when the server answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationEntryTest {
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

    @Test
    fun `before the visitor's conversations are known it enters the empty thread unmarked, and the running one comes once the server answers`() {
        val s = ScriptedSession(record = VisitorRecord(bearer = "wa_known"))
        // The restore and the list get no answer while the session starts.
        val reachable = AtomicBoolean(false)
        s.http.on("GET /api/widget/conversations") { call ->
            when {
                !reachable.get() -> offline()
                call.query["list"] == "1" -> ok(json("conversations" to emptyList<Any>()))
                else -> ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0))
            }
        }
        s.http.on("GET /api/widget/messages") { call -> ok(pollAnswer(row("m1", type = "INCOMING", content = "Your parcel left", conversationId = call.query["conversationId"]))) }
        s.install()
        assertTrue(idleUntil { s.session.state.value.phase == Phase.READY })
        assertFalse(s.session.state.value.visitorLoaded)

        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, DeskwootActivity.Entry.Conversation))
        controllers += controller
        controller.setup()
        idle()
        val a = controller.get()
        assertTrue(a.findViewById<View>(R.id.dw_thread_screen)?.isShown == true)
        assertNull(s.session.state.value.thread?.conversationId)

        reachable.set(true)
        s.session.refresh()
        assertTrue("the running conversation comes with the restore", idleUntil { s.session.state.value.thread?.conversationId == "c1" })
    }
}
