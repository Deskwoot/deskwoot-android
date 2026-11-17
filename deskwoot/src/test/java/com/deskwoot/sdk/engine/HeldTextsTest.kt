package com.deskwoot.sdk.engine

import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Texts held for the step before the chat go out by themselves when the step
 * goes away without being answered; leaving the step on purpose hands them
 * back. "Start a new conversation" and the step's answers are used up only by
 * the send that carried them.
 */
class HeldTextsTest {
    private val userA = DeskwootUser("u_a", "a".repeat(64))
    private val verify = "POST /api/widget/identity/verify"
    private val emailStep = "emailCollectEnabled" to true
    private val form = arrayOf(
        "preChatFormEnabled" to true,
        "preChatFormFields" to mapOf("fields" to listOf(mapOf("name" to "order_number", "label" to "Order number", "type" to "text", "required" to true))),
    )

    private fun contents(h: Harness): List<String> = h.sends().map { checkNotNull(it.body).getString("content") }

    @Test
    fun `after a late identify verified the visitor the held text goes out as that user`() = runTest {
        val h = Harness(this, baseConfig(emailStep))
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_A")) }
        h.http.on("POST /api/widget/messages") { sent(conversationId = "c5", bearer = "wb_A") }
        h.start()
        assertEquals(SendOutcome.Held, h.engine.send("Where is my parcel?"))
        assertEquals(Gate.EmailCollect, h.thread.gate)

        h.engine.identify(userA)
        h.settle()
        assertEquals(listOf("Where is my parcel?"), contents(h))
        assertEquals("wb_A", h.sends().single().body?.getString("contactIdentifier"))
        assertTrue(h.thread.heldMessages.isEmpty())
        assertEquals(Gate.None, h.thread.gate)
    }

    @Test
    fun `after a late identify of the dormant user the held texts go out in order into the restored conversation`() = runTest {
        val h = Harness(this, baseConfig(emailStep), record = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a"))
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_A")) }
        h.http.on("POST /api/widget/messages") { sent(conversationId = "c1", bearer = "wb_A") }
        h.start()
        h.engine.send("Where is my parcel?")
        h.engine.send("Order 4711")
        assertEquals(listOf("Where is my parcel?", "Order 4711"), h.thread.heldMessages)

        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0)) }
        h.engine.identify(userA)
        h.settle()
        assertEquals(listOf("Where is my parcel?", "Order 4711"), contents(h))
        assertTrue(h.sends().all { it.body?.getString("conversationId") == "c1" })
        assertTrue(h.thread.heldMessages.isEmpty())
    }

    @Test
    fun `when the inbox switched the step off the held text goes out as the next message`() = runTest {
        val h = Harness(this, baseConfig(emailStep))
        h.start()
        h.engine.send("Hello?")
        assertEquals(listOf("Hello?"), h.thread.heldMessages)

        h.http.on("GET /api/widget/config") { ok(baseConfig()) }
        h.http.on("POST /api/widget/messages") { sent(conversationId = "c2") }
        h.engine.refresh()
        h.settle()
        assertEquals(listOf("Hello?"), contents(h))
        assertTrue(h.thread.heldMessages.isEmpty())
        assertEquals("c2", h.thread.conversationId)
    }

    @Test
    fun `a conversation opened while the step showed takes the held text`() = runTest {
        val h = Harness(this, baseConfig(emailStep), record = VisitorRecord(bearer = "wb_known"))
        h.listAnswer = {
            ok(json("conversations" to listOf(json("id" to "c2", "status" to "OPEN", "lastActivityAt" to "2026-11-16T09:00:00.000Z", "unread" to 1))))
        }
        h.start()
        h.engine.startNewConversation()
        assertEquals(Gate.EmailCollect, h.engine.gateBeforeChat())
        h.engine.send("About my last order")
        assertEquals(listOf("About my last order"), h.thread.heldMessages)

        // A tap on a notification, or the list, opens c2 while the step shows.
        h.http.on("POST /api/widget/messages") { sent(conversationId = "c2", bearer = "wb_known") }
        h.engine.openConversation("c2")
        h.settle()
        val body = checkNotNull(h.sends().single().body)
        assertEquals("About my last order", body.getString("content"))
        assertEquals("c2", body.getString("conversationId"))
        assertTrue(h.thread.heldMessages.isEmpty())
    }

    @Test
    fun `leaving the step on purpose hands the texts back and sends nothing`() = runTest {
        val h = Harness(this, baseConfig(emailStep))
        h.start()
        h.engine.send("Hello?")
        h.engine.cancelGate()
        h.settle()
        assertTrue(h.thread.heldMessages.isEmpty())
        h.advance(60_000)
        assertTrue(h.sends().isEmpty())
    }

    @Test
    fun `a text written after the step went away, while the visitor still loads, goes behind the held ones`() = runTest {
        val h = Harness(this, baseConfig(emailStep), record = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a"))
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_A")) }
        h.http.on("POST /api/widget/messages") { sent(conversationId = "c1", bearer = "wb_A") }
        h.start()
        h.engine.send("First")
        val restored = CompletableDeferred<Unit>()
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0)) }
        h.http.on("GET /api/widget/conversations") { call ->
            if (call.query["list"] == "1") {
                ok(json("conversations" to emptyList<Any>()))
            } else {
                restored.await()
                h.restoreAnswer()
            }
        }
        h.engine.identify(userA)
        h.settle()
        // Verified: no step any more, but the user's conversations are still on their way.
        assertEquals(Gate.None, h.thread.gate)
        assertEquals(SendOutcome.Held, h.engine.send("Second"))
        assertTrue(h.sends().isEmpty())

        restored.complete(Unit)
        h.settle()
        assertEquals(listOf("First", "Second"), contents(h))
        assertTrue(h.sends().all { it.body?.getString("conversationId") == "c1" })
    }

    // ── Used up only by the send that carried them ──

    @Test
    fun `another conversation's success keeps the request for a new conversation for the next message`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread("c1")
        val c1Answer = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            c1Answer.await()
            sent(conversationId = "c1", bearer = "wb_known")
        }
        h.engine.send("Still about c1")
        h.settle()
        h.engine.startNewConversation()
        c1Answer.complete(Unit)
        h.settle()

        h.http.once("POST /api/widget/messages") { sent(conversationId = "c3", bearer = "wb_known", newConversation = true) }
        h.engine.send("Something new")
        h.settle()
        val next = checkNotNull(h.sends().last().body)
        assertTrue(next.getBoolean("forceNewConversation"))
        assertTrue(next.isNull("conversationId"))
        assertEquals("c3", h.thread.conversationId)
    }

    @Test
    fun `another conversation's success keeps the step's answers, the send that carries them uses them up`() = runTest {
        val h = Harness(this, baseConfig(*form), record = VisitorRecord(bearer = "wb_known"))
        h.openThread("c1")
        val c1Answer = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            c1Answer.await()
            sent(conversationId = "c1", bearer = "wb_known")
        }
        h.engine.send("Still about c1")
        h.settle()
        h.engine.startNewConversation()
        assertTrue(h.engine.gateBeforeChat() is Gate.PreChatForm)
        assertTrue(h.engine.submitPreChat(mapOf("order_number" to "4711")).isEmpty())

        c1Answer.complete(Unit)
        h.settle()
        assertEquals("the form does not come back", Gate.None, h.engine.gateBeforeChat())

        h.http.once("POST /api/widget/messages") { sent(conversationId = "c3", bearer = "wb_known", newConversation = true) }
        h.engine.send("Something new")
        h.settle()
        val next: JSONObject = checkNotNull(h.sends().last().body)
        assertEquals("4711", next.getJSONObject("preChatFormValues").getString("order_number"))
        assertTrue(next.getBoolean("forceNewConversation"))

        // Used up by that success: the next message carries them no more.
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c3", bearer = "wb_known") }
        h.engine.send("And one more")
        h.settle()
        assertFalse(checkNotNull(h.sends().last().body).has("preChatFormValues"))
    }
}
