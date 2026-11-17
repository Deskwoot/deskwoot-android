package com.deskwoot.sdk.engine

import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.json
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose push a visitor key names, and when who the visitor is has settled:
 * the stored signed in user stays dormant (their conversations not known)
 * until the app names them or logs out; the user named takes their stored
 * identity back once its verification answered, a bearer it replaces stays
 * theirs; another thread of a process that has not read the store yet reads
 * it first.
 */
class PushVisitorTest {
    private val anna = DeskwootUser("u_anna", "a".repeat(64))
    private val bob = DeskwootUser("u_bob", "b".repeat(64))
    private val verify = "POST /api/widget/identity/verify"
    private val stored = VisitorRecord(bearer = "wb_anna", verifiedExternalId = "u_anna")

    private fun entry(id: String) = json("id" to id, "status" to "OPEN", "lastActivityAt" to "2026-11-16T09:00:00.000Z", "unread" to 0)

    @Test
    fun `the stored signed in user stays dormant until identify, and whose a conversation is stays unknown meanwhile`() = runTest {
        val h = Harness(this, record = stored)
        h.listAnswer = { ok(json("conversations" to listOf(entry("c1")))) }
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_anna")) }
        h.start()
        assertEquals(PushVisitor.DORMANT, h.engine.pushVisitor(VisitorKey.of("wb_anna")))
        assertNull("not known while the user may still come back", h.engine.ownsConversation("c1"))
        assertFalse(h.state.identitySettled)

        h.engine.identify(anna)
        h.settle()
        assertEquals(PushVisitor.ACTIVE, h.engine.pushVisitor(VisitorKey.of("wb_anna")))
        assertTrue(h.state.identitySettled)
        assertEquals(true, h.engine.ownsConversation("c1"))
        assertEquals(false, h.engine.ownsConversation("c9"))
    }

    @Test
    fun `identify of another user or a logout makes the stored user's pushes forgotten`() = runTest {
        val other = Harness(this, record = stored)
        other.http.on(verify) { ok(json("contactIdentifier" to "wb_bob")) }
        other.start()
        other.engine.identify(bob)
        assertEquals(PushVisitor.FORGOTTEN, other.engine.pushVisitor(VisitorKey.of("wb_anna")))
        other.settle()
        assertEquals(PushVisitor.ACTIVE, other.engine.pushVisitor(VisitorKey.of("wb_bob")))

        val out = Harness(this, record = stored)
        out.start()
        out.engine.logout()
        assertEquals(PushVisitor.FORGOTTEN, out.engine.pushVisitor(VisitorKey.of("wb_anna")))
        assertEquals("no key", PushVisitor.FORGOTTEN, out.engine.pushVisitor(null))
    }

    @Test
    fun `on another thread before the store was read the store decides, with what the app said so far`() = runTest {
        fun fresh(record: VisitorRecord) = Harness(this, record = record, onMainThread = { false }).engine

        assertEquals("the stored anonymous visitor the session starts as", PushVisitor.ACTIVE, fresh(VisitorRecord(bearer = "wa_known")).pushVisitor(VisitorKey.of("wa_known")))
        assertEquals(PushVisitor.DORMANT, fresh(stored).pushVisitor(VisitorKey.of("wb_anna")))
        assertEquals(PushVisitor.FORGOTTEN, fresh(stored).pushVisitor(VisitorKey.of("wb_somebody")))

        val named = fresh(stored)
        named.identify(anna)
        assertEquals("identify of that user in Application.onCreate", PushVisitor.ACTIVE, named.pushVisitor(VisitorKey.of("wb_anna")))
        val another = fresh(stored)
        another.identify(bob)
        assertEquals(PushVisitor.FORGOTTEN, another.pushVisitor(VisitorKey.of("wb_anna")))
        val loggedOut = fresh(VisitorRecord(bearer = "wa_known"))
        loggedOut.logout()
        assertEquals(PushVisitor.FORGOTTEN, loggedOut.pushVisitor(VisitorKey.of("wa_known")))
    }

    @Test
    fun `a verification that gives the user a new bearer keeps the earlier bearer's pushes theirs`() = runTest {
        val h = Harness(this, record = stored)
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_anna_new")) }
        h.start()
        h.engine.identify(anna)
        h.settle()
        assertEquals("wb_anna_new", h.store.record.bearer)
        assertEquals(PushVisitor.ACTIVE, h.engine.pushVisitor(VisitorKey.of("wb_anna_new")))
        assertEquals(PushVisitor.ACTIVE, h.engine.pushVisitor(VisitorKey.of("wb_anna")))

        h.engine.logout()
        assertEquals(PushVisitor.FORGOTTEN, h.engine.pushVisitor(VisitorKey.of("wb_anna")))
        assertEquals(PushVisitor.FORGOTTEN, h.engine.pushVisitor(VisitorKey.of("wb_anna_new")))
    }

    @Test
    fun `a stored identity taken back settles once its verification answered or got no answer`() = runTest {
        val h = Harness(this, record = stored)
        val answer = CompletableDeferred<Unit>()
        h.http.on(verify) {
            answer.await()
            offline()
        }
        h.start()
        h.engine.identify(anna)
        h.settle()
        assertEquals(PushVisitor.ACTIVE, h.engine.pushVisitor(VisitorKey.of("wb_anna")))
        assertFalse("the verification has not answered", h.state.identitySettled)
        answer.complete(Unit)
        h.settle()
        assertTrue("no answer keeps the identity working: nothing waits for it", h.state.identitySettled)
    }

    @Test
    fun `a tapped conversation the server shows nothing of is refused, one it shows rows of is confirmed`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.http.on("GET /api/widget/messages") { call ->
            if (call.query["conversationId"] == "c3") ok(pollAnswer(row("m3", type = "INCOMING", conversationId = "c3"))) else ok(pollAnswer())
        }
        h.start()
        h.engine.openConversation("c9", tapped = true)
        h.settle()
        assertTrue(h.engine.tapRefused("c9"))
        assertNull("the thread let go of it", h.state.thread?.conversationId)
        assertEquals(false, h.engine.ownsConversation("c9"))

        h.engine.openConversation("c3", tapped = true)
        h.settle()
        assertFalse(h.engine.tapRefused("c3"))
        assertEquals(true, h.engine.ownsConversation("c3"))
        assertEquals("c3", h.state.thread?.conversationId)
    }
}
