package com.deskwoot.sdk.ui

import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.R
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.image.ImageViewer
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The messenger's saved state carries the key of the visitor it showed. When
 * Android brings the messenger back after the process ended, its screens,
 * drafts, the help search and the picture in the viewer come back only for
 * that visitor; for anybody else the start page shows and nothing of them
 * is readable or opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreAfterProcessDeathTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()
    private val anna = DeskwootUser("u_anna", "a".repeat(64))
    private val ben = DeskwootUser("u_ben", "b".repeat(64))

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

    private fun open(entry: DeskwootActivity.Entry, saved: Bundle? = null): ActivityController<DeskwootActivity> {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry))
        controllers += controller
        if (saved == null) controller.setup() else controller.setup(saved)
        idle()
        return controller
    }

    private fun DeskwootActivity.shows(id: Int): Boolean = findViewById<View>(id)?.isShown == true

    /** A session whose visitor has the open conversation c_anna. */
    private fun withAnnasConversation(record: VisitorRecord): ScriptedSession {
        val s = ScriptedSession(record = record)
        s.http.on("GET /api/widget/conversations") { call ->
            val hers = call.headers.values.any { it == "wa_anna" || it == "wb_anna" }
            when {
                call.query["list"] == "1" -> ok(json("conversations" to emptyList<Any>()))
                hers -> ok(json("conversationId" to "c_anna", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0))
                else -> ok(json("conversationId" to null))
            }
        }
        s.http.on("GET /api/widget/messages") { call -> ok(pollAnswer(row("m1", content = "Your parcel 4711", conversationId = call.query["conversationId"]))) }
        s.http.on("POST /api/widget/identity/verify") { call -> ok(json("contactIdentifier" to "wb_" + call.body?.optJSONObject("user")?.optString("identifier")?.removePrefix("u_"))) }
        return s
    }

    /** Anna's conversation on screen with a draft, a help search and a picture open; the state Android saves, then the Activity goes. */
    private fun annasSavedState(s: ScriptedSession): Bundle {
        val controller = open(DeskwootActivity.Entry.Conversation)
        val a = controller.get()
        assertTrue(idleUntil { a.findViewById<View>(R.id.dw_composer_input)?.isShown == true })
        assertEquals("c_anna", s.session.state.value.thread?.conversationId)
        a.findViewById<EditText>(R.id.dw_composer_input).setText("my account number is 123456")
        UiMemory.helpQuery = "change account 123456"
        idle()
        val saved = Bundle()
        controller.saveInstanceState(saved)
        controller.pause().stop().destroy()
        ImageViewer.save(saved, "att_1", "/files/annas-id.png", "annas-id.png")
        // The process ends: nothing in memory survives.
        UiMemory.clear()
        return saved
    }

    private fun assertNothingOfAnna(restored: DeskwootActivity, s: ScriptedSession) {
        assertTrue("the start page", idleUntil { restored.shows(R.id.dw_start_screen) })
        repeat(3) { idle() }
        assertEquals("the old visitor's draft must not come back", "", UiMemory.draft("c_anna"))
        assertEquals("nor the help search", "", UiMemory.helpQuery)
        val field = restored.findViewById<EditText>(R.id.dw_composer_input)
        assertTrue("no text of the old visitor in a field", field == null || field.text.isEmpty())
        assertNull("no picture of theirs", restored.findViewById(R.id.dw_image_viewer))
        assertNull(restored.findViewById(R.id.dw_thread_screen))
        assertNotEquals("the new visitor's session never sits on the old conversation", "c_anna", s.session.state.value.thread?.conversationId)
    }

    @Test
    fun `a messenger restored after a logout brings back nothing of the old visitor`() {
        val s = withAnnasConversation(VisitorRecord(bearer = "wa_anna")).install()
        val saved = annasSavedState(s)
        // The next start: the app finds its user signed out and calls logout before the messenger comes back.
        Deskwoot.logout()
        idle()

        val restored = open(DeskwootActivity.Entry.Conversation, saved).get()
        assertNothingOfAnna(restored, s)
    }

    @Test
    fun `a messenger restored for the same visitor brings back the thread, the draft and the search`() {
        val before = withAnnasConversation(VisitorRecord(bearer = "wa_anna")).install()
        val saved = annasSavedState(before)
        // A new process: a new session reads the same store.
        val s = withAnnasConversation(before.store.record).install()

        val restored = open(DeskwootActivity.Entry.Conversation, saved).get()
        assertTrue(idleUntil { restored.shows(R.id.dw_thread_screen) && s.session.state.value.thread?.conversationId == "c_anna" })
        assertEquals("my account number is 123456", restored.findViewById<EditText>(R.id.dw_composer_input).text.toString())
        assertEquals("change account 123456", UiMemory.helpQuery)
    }

    @Test
    fun `a restored route of a conversation the server no longer names as the visitor's is not opened`() {
        val before = withAnnasConversation(VisitorRecord(bearer = "wa_anna")).install()
        val saved = annasSavedState(before)
        // A new process of the same visitor; meanwhile c_anna went to another contact on the server.
        val s = ScriptedSession(record = before.store.record)
        s.http.on("GET /api/widget/messages") { ok(pollAnswer()) }
        s.install()
        assertTrue(idleUntil { s.session.state.value.visitorLoaded && s.session.identitySettled })

        open(DeskwootActivity.Entry.Conversation, saved)
        repeat(5) { idle() }
        assertNotEquals("the screen's route alone opens nothing", "c_anna", s.session.state.value.thread?.conversationId)
    }

    @Test
    fun `a saved state of the stored user is dropped when the app names another user before the store is read`() {
        val before = withAnnasConversation(VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna"))
        before.install()
        Deskwoot.identify(anna)
        assertTrue(idleUntil { before.session.state.value.thread?.conversationId == "c_anna" })
        val saved = annasSavedState(before)

        // A new process whose app names Ben in Application.onCreate before its setup: Anna's stored identity
        // ends without ever being shown in this process.
        ScreenHarness.install(null)
        Deskwoot.identify(ben)
        val s = withAnnasConversation(before.store.record).install()
        assertTrue(idleUntil { s.session.state.value.identity == VisitorIdentity.Verified("u_ben") })

        val restored = open(DeskwootActivity.Entry.Conversation, saved).get()
        assertNothingOfAnna(restored, s)
    }

    @Test
    fun `a saved state of the stored user the app has not named yet is dropped`() {
        val before = withAnnasConversation(VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna"))
        before.install()
        Deskwoot.identify(anna)
        assertTrue(idleUntil { before.session.state.value.thread?.conversationId == "c_anna" })
        val saved = annasSavedState(before)

        // A new process: the app names nobody in Application.onCreate.
        val s = withAnnasConversation(before.store.record).install()
        val restored = open(DeskwootActivity.Entry.Conversation, saved).get()
        assertNothingOfAnna(restored, s)
    }
}
