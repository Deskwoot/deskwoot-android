package com.deskwoot.sdk.ui.notify

import android.Manifest
import android.app.NotificationManager
import com.deskwoot.sdk.AppProcess
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.engine.VisitorKey
import com.deskwoot.sdk.json
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.PushRemoval
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.DeskwootActivity
import com.deskwoot.sdk.ui.ScreenHarness
import com.deskwoot.sdk.ui.ScriptedSession
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.UiMemory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What `Deskwoot.handlePush` and `isDeskwootPush` do as the app calls them
 * from its messaging service: a push for a visitor this phone forgot draws
 * nothing and sends that visitor's waiting removal at once, and in a process
 * of the app that is not its default one a push starts the setup that
 * waited there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandlePushFacadeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val shown get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val fcm = "f".repeat(20) + ":APA91b" + "x".repeat(140)
    private val remove = "POST /api/widget/push/devices/remove"

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UiMemory.clear()
        ScreenHarness.install(null)
        facadeField("idleSetup").set(Deskwoot, null)
        Deskwoot.setPushToken(null)
        idle()
    }

    @After
    fun tearDown() {
        AppProcess.nameForTests = null
        DeskwootActivity.presenter = null
        Deskwoot.setPushToken(null)
        idle()
        UiMemory.clear()
    }

    private fun facadeField(name: String) = Deskwoot::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** A returning visitor (`wa_known`) with conversation c1. */
    private fun visitor(): ScriptedSession {
        val s = ScriptedSession(record = VisitorRecord(bearer = "wa_known"))
        s.http.on("GET /api/widget/conversations") { call ->
            if (call.query["list"] == "1") ok(json("conversations" to emptyList<Any>()))
            else ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0))
        }
        s.http.on("GET /api/widget/messages") { call -> ok(pollAnswer(row("m-x", content = "Reply", conversationId = call.query["conversationId"]))) }
        s.http.on("POST /api/widget/push/devices") { ok(json("ok" to true)) }
        return s
    }

    private fun push(s: ScriptedSession, bearer: String) = mapOf(
        "deskwoot" to "1",
        "conversationId" to "c1",
        "widgetToken" to s.token,
        "messageId" to "m-${bearer.hashCode()}",
        "visitor" to VisitorKey.of(bearer),
        "title" to "Mia",
        "body" to "Your test results are ready",
    )

    @Test
    fun `a push for a forgotten visitor sends their waiting removal at once, answers true and draws nothing`() {
        val s = visitor()
        // A visitor an earlier launch forgot while the server still holds this phone for them.
        runBlocking { s.store.writePushRemovals(listOf(PushRemoval(s.token, "wb_gone", System.currentTimeMillis()))) }
        val tries = AtomicInteger()
        s.http.on(remove) { if (tries.incrementAndGet() == 1) offline() else ok(json("ok" to true)) }
        s.install()
        Deskwoot.setPushToken(fcm)
        assertTrue("the try at the token got no answer", idleUntil { s.http.calls(remove).size == 1 })
        repeat(3) { idle() }
        assertEquals("not tried again in a loop", 1, s.http.calls(remove).size)

        // FCM hands the push over on a worker thread.
        val answered = AtomicBoolean(false)
        thread { answered.set(Deskwoot.handlePush(app, push(s, "wb_gone"))) }.join()
        assertTrue("still Deskwoot's", answered.get())
        assertTrue("the removal goes out at once", idleUntil { s.http.calls(remove).size == 2 })
        val body = checkNotNull(s.http.calls(remove).last().body)
        assertEquals("wb_gone", body.getString("contactIdentifier"))
        assertEquals(s.token, body.getString("widgetToken"))
        assertEquals(fcm, body.getString("token"))
        assertTrue("its record goes after the 2xx", idleUntil { s.store.removals.isEmpty() })
        assertEquals("nothing of the forgotten visitor shows", 0, shown.size())

        // The same phone draws a push of the visitor it runs as.
        thread { Deskwoot.handlePush(app, push(s, "wa_known")) }.join()
        assertTrue(idleUntil { shown.size() == 1 })
    }

    @Test
    fun `in another process of the app a push handed to handlePush starts the setup that waited and its notification shows`() {
        AppProcess.nameForTests = app.packageName + ":remote"
        val s = visitor()
        Deskwoot.makeSession = { _, _, _ -> s.session }
        Deskwoot.setup(app, s.token)
        repeat(3) { idle() }
        assertNull("the setup waits", facadeField("session").get(Deskwoot))
        assertTrue(s.http.calls.isEmpty())

        val answered = AtomicBoolean(false)
        val worker = thread { answered.set(Deskwoot.handlePush(app, push(s, "wa_known"))) }
        assertTrue(idleUntil { !worker.isAlive })
        assertTrue("Deskwoot's push", answered.get())
        assertSame(s.session, facadeField("session").get(Deskwoot))
        assertTrue("its notification shows", idleUntil { shown.size() == 1 })
        assertTrue("the session started", idleUntil { s.http.calls("GET /api/widget/config").isNotEmpty() })
    }

    @Test
    fun `in another process of the app isDeskwootPush starts the setup that waited and knows the push`() {
        AppProcess.nameForTests = app.packageName + ":remote"
        val s = visitor()
        Deskwoot.makeSession = { _, _, _ -> s.session }
        Deskwoot.setup(app, s.token)
        repeat(3) { idle() }
        assertNull(facadeField("session").get(Deskwoot))

        val ours = AtomicBoolean(false)
        val worker = thread { ours.set(Deskwoot.isDeskwootPush(push(s, "wa_known"))) }
        assertTrue(idleUntil { !worker.isAlive })
        assertTrue(ours.get())
        assertSame(s.session, facadeField("session").get(Deskwoot))
    }
}
