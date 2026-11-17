package com.deskwoot.sdk.ui.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.R
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.api.FileFetcher
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.engine.VisitorKey
import com.deskwoot.sdk.help.HelpCenterClient
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.DeskwootActivity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Push notifications on Android: `Deskwoot.handlePush`
 * draws a reply of the setup's inbox in the SDK's own channel, one per
 * conversation, none while that conversation is on screen; a tap opens it
 * in the messenger; another inbox's push, the app's own and every push
 * without the app's grant show nothing and crash nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushNotificationsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val shown get() = shadowOf(manager)
    private val noCard: (String) -> Boolean = { false }

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PushNotifications.icon = 0
    }

    @After
    fun reset() {
        PushNotifications.icon = 0
    }

    private fun push(
        conversationId: String = "c1",
        widgetToken: String = TOKEN,
        messageId: String = "m9",
        body: String = "Yes, we ship to Austria.",
        visitor: String? = VisitorKey.of("wb_known"),
    ) = mapOf(
        "deskwoot" to "1",
        "conversationId" to conversationId,
        "widgetToken" to widgetToken,
        "messageId" to messageId,
        "visitor" to visitor,
        "title" to "Priya",
        "body" to body,
    )

    /** A returning visitor's session on the test engine (German app language), started. */
    private fun session(scope: TestScope): Pair<Harness, ChatSession> {
        val h = Harness(scope, record = VisitorRecord(bearer = "wb_known"))
        val api = WidgetApi(h.http, { BASE_URL }, TOKEN, StandardTestDispatcher(scope.testScheduler))
        val session = ChatSession(TOKEN, h.engine, HelpCenterClient(api) { null }, api, FileFetcher { _, _ -> null })
        return h to session
    }

    private fun notification(conversationId: String): Notification? =
        shown.getNotification(PushNotifications.tag(TOKEN, conversationId), PushNotifications.NOTIFICATION_ID)

    @Test
    fun `a reply shows with the app in front and in the background`() = runTest {
        val (h, session) = session(this)
        h.start()
        assertTrue(PushNotifications.handle(app, push(), session, noCard))
        val front = checkNotNull(notification("c1")) { "in front, the thread not on screen" }
        assertEquals("Priya", front.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Yes, we ship to Austria.", front.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(PushNotifications.CHANNEL_ID, front.channelId)
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, front.category)

        h.engine.setForeground(false)
        h.settle()
        assertTrue(PushNotifications.handle(app, push("c2"), session, noCard))
        assertNotNull("in the background", notification("c2"))
        assertEquals(2, shown.size())
    }

    @Test
    fun `none while that conversation is on screen, another one still shows`() = runTest {
        val (h, session) = session(this)
        h.openThread("c1")
        assertTrue(session.isOnScreen("c1"))
        assertTrue("still ours, the app must not handle it", PushNotifications.handle(app, push("c1"), session, noCard))
        assertNull(notification("c1"))
        assertTrue(PushNotifications.handle(app, push("c2"), session, noCard))
        assertNotNull(notification("c2"))
        assertEquals(1, shown.size())
    }

    @Test
    fun `one notification per conversation, the newer reply replaces the older`() = runTest {
        val (h, session) = session(this)
        h.start()
        PushNotifications.handle(app, push("c1", body = "First"), session, noCard)
        PushNotifications.handle(app, push("c1", messageId = "m10", body = "Second"), session, noCard)
        assertEquals(1, shown.size())
        assertEquals("Second", notification("c1")?.extras?.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `a tap opens that conversation through the messenger's Activity`() = runTest {
        val (h, session) = session(this)
        h.start()
        PushNotifications.handle(app, push("c1"), session, noCard)
        PushNotifications.handle(app, push("c2"), session, noCard)
        val tap = checkNotNull(notification("c1")?.contentIntent)
        val pending = shadowOf(tap)
        assertTrue(pending.isActivity)
        assertTrue("immutable", pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = pending.savedIntent
        assertEquals(DeskwootActivity::class.java.name, intent.component?.className)
        assertTrue("the app's task", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val entry = DeskwootActivity.Entry.from(intent)
        assertTrue(entry is DeskwootActivity.Entry.Open)
        assertEquals("c1", (entry as DeskwootActivity.Entry.Open).conversationId)
        // Each conversation keeps its own intent: the conversation is its data.
        val other = shadowOf(checkNotNull(notification("c2")?.contentIntent))
        assertNotEquals(intent.data, other.savedIntent.data)
        assertEquals("c2", (DeskwootActivity.Entry.from(other.savedIntent) as DeskwootActivity.Entry.Open).conversationId)
    }

    @Test
    fun `a push for a visitor this device forgot shows nothing and is still ours`() = runTest {
        val (h, session) = session(this)
        h.start()
        assertTrue("a logged out or replaced visitor's key", PushNotifications.handle(app, push(visitor = VisitorKey.of("wb_logged_out")), session, noCard))
        assertTrue("no key at all", PushNotifications.handle(app, push("c2", visitor = null), session, noCard))
        assertEquals(0, shown.size())
        assertTrue("the visitor's own still shows", PushNotifications.handle(app, push("c3"), session, noCard))
        assertEquals(1, shown.size())
    }

    @Test
    fun `a push for the stored signed in user who waits for identify shows`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna"))
        val api = WidgetApi(h.http, { BASE_URL }, TOKEN, StandardTestDispatcher(testScheduler))
        val session = ChatSession(TOKEN, h.engine, HelpCenterClient(api) { null }, api, FileFetcher { _, _ -> null })
        h.start()
        assertTrue(PushNotifications.handle(app, push("c7", visitor = VisitorKey.of("wb_anna")), session, noCard))
        assertNotNull(notification("c7"))
    }

    @Test
    fun `in a process that has not read the store yet the worker thread reads it before deciding`() = runTest {
        // The process was started for the push: the session exists, its start has not read the store.
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"), onMainThread = { false })
        val api = WidgetApi(h.http, { BASE_URL }, TOKEN, StandardTestDispatcher(testScheduler))
        val session = ChatSession(TOKEN, h.engine, HelpCenterClient(api) { null }, api, FileFetcher { _, _ -> null })
        assertFalse(session.visitorKnown)
        var answers = emptyList<Boolean>()
        val worker = Thread {
            answers = listOf(
                PushNotifications.handle(app, push("c1"), session, noCard),
                PushNotifications.handle(app, push("c2", visitor = VisitorKey.of("wb_forgotten")), session, noCard),
            )
        }
        worker.start()
        worker.join()
        assertEquals(listOf(true, true), answers)
        assertNotNull("the stored visitor's reply shows before handlePush returned", notification("c1"))
        assertNull(notification("c2"))
    }

    @Test
    fun `a cookieless inbox's visitor gets no notification once the process ended`() = runTest {
        val cookieless = com.deskwoot.sdk.baseConfig("cookielessEnabled" to true)
        val h = Harness(this, config = cookieless)
        h.http.on("POST /api/widget/messages") { com.deskwoot.sdk.sent(bearer = "wb_minted") }
        val api = WidgetApi(h.http, { BASE_URL }, TOKEN, StandardTestDispatcher(testScheduler))
        val session = ChatSession(TOKEN, h.engine, HelpCenterClient(api) { null }, api, FileFetcher { _, _ -> null })
        h.start()
        h.engine.send("Hello")
        h.settle()
        assertTrue(PushNotifications.handle(app, push("c1", visitor = VisitorKey.of("wb_minted")), session, noCard))
        assertNotNull("while the process lives, the visitor in memory is theirs", notification("c1"))
        manager.cancelAll()

        // The next process: a cookieless inbox kept nothing.
        val next = Harness(this, config = cookieless, record = h.store.record)
        val nextApi = WidgetApi(next.http, { BASE_URL }, TOKEN, StandardTestDispatcher(testScheduler))
        val nextSession = ChatSession(TOKEN, next.engine, HelpCenterClient(nextApi) { null }, nextApi, FileFetcher { _, _ -> null })
        next.start()
        assertTrue(PushNotifications.handle(app, push("c1", visitor = VisitorKey.of("wb_minted")), nextSession, noCard))
        assertNull(notification("c1"))
    }

    @Test
    fun `each notification opens its own conversation, also ids with equal hashes`() = runTest {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val (h, session) = session(this)
        h.start()
        PushNotifications.handle(app, push("Aa", messageId = "m1"), session, noCard)
        PushNotifications.handle(app, push("BB", messageId = "m2"), session, noCard)
        for (id in listOf("Aa", "BB")) {
            val tap = shadowOf(checkNotNull(notification(id)?.contentIntent)).savedIntent
            assertEquals(id, (DeskwootActivity.Entry.from(tap) as DeskwootActivity.Entry.Open).conversationId)
        }
    }

    @Test
    fun `another inbox's push, the app's own and a push before setup are not ours`() = runTest {
        val (h, session) = session(this)
        h.start()
        assertFalse(PushNotifications.handle(app, push(widgetToken = "wt_other"), session, noCard))
        assertFalse(PushNotifications.handle(app, mapOf("title" to "Your order shipped", "widgetToken" to TOKEN), session, noCard))
        assertFalse("before setup", PushNotifications.handle(app, push(), null, noCard))
        assertNull(PushNotifications.parse(push(), null))
        assertNotNull(PushNotifications.parse(push(), session))
        assertEquals(0, shown.size())
    }

    @Test
    fun `without the app's grant or with notifications off nothing shows and nothing fails`() = runTest {
        val (h, session) = session(this)
        h.start()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue("ours all the same", PushNotifications.handle(app, push(), session, noCard))
        assertEquals(0, shown.size())

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shown.setNotificationsEnabled(false)
        assertTrue(PushNotifications.handle(app, push(), session, noCard))
        assertEquals(0, shown.size())
    }

    @Test
    fun `the app's icon by default, the one it names with setNotificationIcon`() = runTest {
        val (h, session) = session(this)
        h.start()
        PushNotifications.handle(app, push("c1"), session, noCard)
        // The test app has no icon of its own: the SDK's chat glyph.
        val expected = app.applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.dw_ic_chat
        assertEquals(expected, notification("c1")?.smallIcon?.resId)

        PushNotifications.icon = R.drawable.dw_ic_check
        PushNotifications.handle(app, push("c2"), session, noCard)
        assertEquals(R.drawable.dw_ic_check, notification("c2")?.smallIcon?.resId)

        // An id that names no resource would make the system refuse the notification: the default instead.
        PushNotifications.icon = 0x7f7f7f7f
        PushNotifications.handle(app, push("c3"), session, noCard)
        assertEquals(expected, notification("c3")?.smallIcon?.resId)
    }

    @Test
    fun `its own channel, named in the session's language, high importance`() = runTest {
        val (h, session) = session(this)
        h.start()
        assertNull(manager.getNotificationChannel(PushNotifications.CHANNEL_ID))
        PushNotifications.handle(app, push(), session, noCard)
        val channel = checkNotNull(manager.getNotificationChannel(PushNotifications.CHANNEL_ID)) { "created with the first notification" }
        // The harness's app speaks German.
        assertEquals("Antworten im Chat", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `a reply the in app card showed already shows without sound or banner`() = runTest {
        val (h, session) = session(this)
        h.start()
        PushNotifications.handle(app, push("c1", messageId = "m9"), session) { it == "m9" }
        PushNotifications.handle(app, push("c2", messageId = "m11"), session) { it == "m9" }
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, NotificationCompat.getGroupAlertBehavior(checkNotNull(notification("c1"))))
        assertEquals(NotificationCompat.GROUP_ALERT_ALL, NotificationCompat.getGroupAlertBehavior(checkNotNull(notification("c2"))))
    }

    @Test
    fun `a push that arrives refreshes the running session`() = runTest {
        val (h, session) = session(this)
        h.start()
        val before = h.http.calls("GET /api/widget/conversations").size
        PushNotifications.handle(app, push(), session, noCard)
        h.settle()
        assertTrue("the list (and its unread count) is asked again", h.http.calls("GET /api/widget/conversations").size > before)
    }

    @Test
    fun `on a worker thread the notification is posted before handlePush returns`() = runTest {
        val (h, session) = session(this)
        h.start()
        var answer: Boolean? = null
        val worker = Thread { answer = PushNotifications.handle(app, push(), session, noCard) }
        worker.start()
        worker.join()
        assertEquals(true, answer)
        assertEquals("drawn on the calling thread, nothing waits for the main thread", 1, shown.size())
    }
}
