package com.deskwoot.sdk.ui.notify

import android.Manifest
import android.app.NotificationManager
import android.provider.Settings
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.R
import com.deskwoot.sdk.engine.VisitorKey
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.PushPayload
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.DeskwootActivity
import com.deskwoot.sdk.ui.ScriptedSession
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.UiMemory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Delivered notifications follow the visitor and the reading: the end of a
 * visitor takes every one of that inbox away, a conversation read in the
 * messenger its own; the app's notifications and another inbox's stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationCleanupTest {
    private val app = RuntimeEnvironment.getApplication()
    private val shown get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
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

    /** A returning visitor (bearer [bearer]) whose restore names c1; the server shows them their rows. */
    private fun visitor(record: VisitorRecord = VisitorRecord(bearer = "wa_known")): ScriptedSession {
        val s = ScriptedSession(record = record)
        s.http.on("GET /api/widget/conversations") { call ->
            if (call.query["list"] == "1") ok(json("conversations" to emptyList<Any>()))
            else ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0))
        }
        s.http.on("GET /api/widget/messages") { call -> ok(pollAnswer(row("m-x", content = "Reply", conversationId = call.query["conversationId"]))) }
        s.http.on("POST /api/widget/identity/verify") { ok(json("contactIdentifier" to "wb_other_user")) }
        return s
    }

    private fun push(s: ScriptedSession, conversationId: String, bearer: String) = mapOf(
        "deskwoot" to "1",
        "conversationId" to conversationId,
        "widgetToken" to s.token,
        "messageId" to "m-$conversationId",
        "visitor" to VisitorKey.of(bearer),
        "title" to "Mia",
        "body" to "Your new password is in the letter",
    )

    /** One notification of the app's own, and one of another inbox of the app. */
    private fun othersShown() {
        NotificationManagerCompat.from(app).notify(
            "app_order_shipped",
            7,
            NotificationCompat.Builder(app, "app_channel").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Shipped").build(),
        )
        PushNotifications.show(app, PushPayload("c_shop", "m1", null, "Leo", "Hi"), "wt_other_inbox", null, quiet = false)
    }

    private fun ours(s: ScriptedSession, conversationId: String) =
        shown.getNotification(PushNotifications.tag(s.token, conversationId), PushNotifications.NOTIFICATION_ID)

    @Test
    fun `a logout takes every notification of the visitor away, the app's own and another inbox's stay`() {
        val s = visitor().install()
        assertTrue(Deskwoot.handlePush(app, push(s, "c1", "wa_known")))
        assertTrue(Deskwoot.handlePush(app, push(s, "c2", "wa_known")))
        othersShown()
        assertEquals(4, shown.size())

        Deskwoot.logout()
        idle()
        assertNull(ours(s, "c1"))
        assertNull(ours(s, "c2"))
        assertEquals("the app's and the other inbox's", 2, shown.size())
        assertNotNull(shown.getNotification("app_order_shipped", 7))
        assertNotNull(shown.getNotification(PushNotifications.tag("wt_other_inbox", "c_shop"), PushNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `identify of another user takes the earlier user's notifications away`() {
        val s = visitor(VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna")).install()
        Deskwoot.identify(DeskwootUser("u_anna", "a".repeat(64)))
        assertTrue(idleUntil { s.session.state.value.identitySettled })
        assertTrue(Deskwoot.handlePush(app, push(s, "c1", "wb_anna")))
        assertNotNull(ours(s, "c1"))

        Deskwoot.identify(DeskwootUser("u_other", "b".repeat(64)))
        assertTrue(idleUntil { s.session.state.value.identity == VisitorIdentity.Verified("u_other") })
        assertNull(ours(s, "c1"))
    }

    @Test
    fun `the stored signed in user's notifications go when the app names another user`() {
        // Dormant: their reply was drawn, then the app signs in somebody else.
        val s = visitor(VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna")).install()
        assertTrue(Deskwoot.handlePush(app, push(s, "c4", "wb_anna")))
        assertNotNull(ours(s, "c4"))
        Deskwoot.identify(DeskwootUser("u_other", "b".repeat(64)))
        idle()
        assertNull(ours(s, "c4"))
    }

    @Test
    fun `a conversation read in the messenger loses its notification, another keeps its own`() {
        val s = visitor().install()
        assertTrue(Deskwoot.handlePush(app, push(s, "c1", "wa_known")))
        assertTrue(Deskwoot.handlePush(app, push(s, "c2", "wa_known")))
        assertEquals(2, shown.size())

        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, DeskwootActivity.Entry.Conversation))
        controllers += controller
        controller.setup()
        val a = controller.get()
        assertTrue(idleUntil { a.findViewById<View>(R.id.dw_thread_screen)?.isShown == true && s.session.isOnScreen("c1") })
        idle()
        assertNull("read on screen", ours(s, "c1"))
        assertNotNull(ours(s, "c2"))
    }
}
