package com.deskwoot.sdk.engine

import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for engine bugs found and fixed before the first
 * release: each asserts the correct behaviour and failed on the code that
 * had the bug. Written for the actions that answer at once and for
 * `heldMessages`.
 */
class EngineRegressionTest {
    private val verifyKey = "POST /api/widget/identity/verify"
    private val userA = DeskwootUser("u_a", "a".repeat(64))

    @Test
    fun `a later message with the same text never reuses the id of a message the server stored`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.on("POST /api/widget/messages") { offline() }
        h.engine.send("ok")
        h.advance(130_000)
        val failed = h.thread.pending.single()
        assertEquals(PendingState.FAILED, failed.state)
        // The server had stored it after all; the poll brings its row.
        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(row("m1", type = "INCOMING", content = "ok", attrs = json("clientAssignedUuid" to failed.uuid))))
        }
        h.advance(5_000)
        assertTrue(h.thread.pending.isEmpty())
        h.http.on("POST /api/widget/messages") { sent(bearer = "wb_known") }
        h.engine.send("Where is my order?")
        h.advance(300_000)
        h.engine.send("ok")
        assertNotEquals("a new message must get a new id", failed.uuid, h.sends().last().headers["Idempotency-Key"])
    }

    @Test
    fun `a restore that started before a send never clears the conversation the send started`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.start()
        assertNull(h.thread.conversationId)
        val slow = CompletableDeferred<Unit>()
        h.http.once("GET /api/widget/conversations") {
            slow.await()
            ok(json("conversationId" to null))
        }
        h.engine.refresh() // present() while the start page shows: the thread is not on screen
        h.settle()
        h.engine.setVisible(true)
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c2", bearer = "wb_known", newConversation = true) }
        h.engine.send("Hi")
        assertEquals("c2", h.thread.conversationId)
        val pollsBefore = h.polls().size
        slow.complete(Unit)
        h.settle()
        h.advance(20_000)
        assertEquals("c2", h.thread.conversationId)
        // The thread on screen keeps its poll, for c2 only.
        val after = h.polls().drop(pollsBefore)
        assertTrue(after.isNotEmpty())
        assertTrue(after.all { it.query["conversationId"] == "c2" })
    }

    @Test
    fun `an edge 403 without a body keeps the stored verified identity`() = runTest {
        val stored = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", lastConversationId = "c1")
        val h = Harness(this, record = stored)
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0)) }
        h.http.on(verifyKey) { fail(403, null) }
        h.engine.identify(userA)
        h.start()
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
        assertEquals("wb_A", h.store.record.bearer)
    }

    @Test
    fun `the same identify twice while its verification runs verifies once`() = runTest {
        val h = Harness(this)
        h.start()
        val gate = CompletableDeferred<Unit>()
        h.http.on(verifyKey) {
            gate.await()
            ok(json("contactIdentifier" to "wb_A"))
        }
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0)) }
        h.engine.identify(userA)
        h.settle()
        h.engine.identify(DeskwootUser("u_a", "a".repeat(64)))
        h.settle()
        gate.complete(Unit)
        h.settle()
        assertEquals(1, h.http.calls(verifyKey).size)
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
    }

    @Test
    fun `a device clock 10 minutes ahead hides no new reply from the unread count`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        // The server's clock is 10 minutes behind the device's: rows carry server time.
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1", content = "Hi, Mia here", at = -600, sender = json("name" to "Mia")))) }
        h.openThread()
        h.engine.setVisible(false)
        h.settle()
        h.advance(60_000)
        h.http.on("GET /api/widget/messages") {
            ok(
                pollAnswer(
                    row("a1", content = "Hi, Mia here", at = -600, sender = json("name" to "Mia")),
                    row("a2", content = "Your refund is on its way", at = -540, sender = json("name" to "Mia")),
                ),
            )
        }
        h.advance(31_000)
        assertEquals(1, h.state.unreadCount)
    }

    @Test
    fun `two quick messages refused with email_required are both held, in order`() = runTest {
        val h = Harness(this)
        h.start()
        val gate = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            gate.await()
            fail(400, json("code" to "email_required"))
        }
        h.http.on("POST /api/widget/messages") { fail(400, json("code" to "email_required")) }
        h.engine.send("First question")
        h.engine.send("Second question")
        h.settle()
        gate.complete(Unit)
        h.settle()
        assertEquals(1, h.sends().size)
        assertEquals(listOf("First question", "Second question"), h.thread.heldMessages)
        assertTrue("nothing stays in sending", h.thread.pending.isEmpty())
    }

    @Test
    fun `submitEmail answers at once and the held message goes in the background`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true))
        h.start()
        assertEquals(SendOutcome.Held, h.engine.send("Hello"))
        assertEquals(listOf("Hello"), h.thread.heldMessages)
        val gate = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            gate.await()
            sent()
        }
        // Not a suspending call any more: it answers before the send is delivered.
        assertNull(h.engine.submitEmail("anna@acme.io"))
        assertTrue(h.thread.heldMessages.isEmpty())
        assertEquals(PendingState.SENDING, h.thread.pending.single().state)
        gate.complete(Unit)
        h.settle()
        assertEquals(1, h.sends().size)
        assertEquals(PendingState.SENT, h.thread.pending.single().state)
    }

    @Test
    fun `a 2xx without a send answer is no answer, tried again and then failed`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.on("POST /api/widget/messages") { ok(null) }
        h.engine.send("Hi")
        h.advance(60_000)
        // An answer without the route's keys is retried like a network failure, within the budget of 2 minutes.
        assertEquals(PendingState.SENDING, h.thread.pending.single().state)
        assertTrue(h.sends().size > 1)
        h.advance(70_000)
        assertEquals(PendingState.FAILED, h.thread.pending.single().state)
    }

    @Test
    fun `a 2xx config without a body never turns a cookieless inbox into one that stores the bearer`() = runTest {
        val h = Harness(this, baseConfig("cookielessEnabled" to true))
        h.start()
        h.http.on("GET /api/widget/config") { ok(null) }
        h.engine.refresh()
        h.settle()
        assertEquals(true, h.state.config?.cookielessEnabled)
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_minted") }
        h.engine.send("Hi")
        h.advance(1_000)
        assertNull(h.store.record.bearer)
        assertNull(h.cache.snapshot)
    }
}
