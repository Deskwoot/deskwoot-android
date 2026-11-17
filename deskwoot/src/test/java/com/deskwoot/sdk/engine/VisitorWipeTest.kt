package com.deskwoot.sdk.engine

import com.deskwoot.sdk.BASE_TIME
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every end of the visitor on screen runs the same wipe, at once and in call
 * order: the visitor generation goes up, thread, held texts, pending rows and
 * the read marker go, and the UI hook runs before the app's next call. A
 * stored visitor that was never shown ends without touching the one on screen.
 */
class VisitorWipeTest {
    private val userA = DeskwootUser("u_a", "a".repeat(64))
    private val userB = DeskwootUser("u_b", "b".repeat(64))
    private val verify = "POST /api/widget/identity/verify"
    private val openAt = { id: String -> ok(json("conversationId" to id, "status" to "OPEN", "unreadCount" to 0)) }

    /** A returning visitor with conversation c1 on screen, a reply read, and a message still on its way. */
    private fun busyVisitor(h: Harness) {
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1", type = "INCOMING", content = "Hi", at = 5))) }
        h.openThread()
        val never = CompletableDeferred<Unit>()
        h.http.on("POST /api/widget/messages") {
            never.await()
            sent()
        }
        h.engine.send("On its way")
        h.settle()
        check(h.thread.pending.size == 1 && h.thread.messages.isNotEmpty() && h.store.record.lastReadAt != null)
    }

    private fun assertWiped(h: Harness, generationBefore: Int) {
        val state = h.state
        assertEquals(generationBefore + 1, state.visitorGeneration)
        val thread = checkNotNull(state.thread)
        assertNull(thread.conversationId)
        assertTrue(thread.messages.isEmpty())
        assertTrue(thread.pending.isEmpty())
        assertTrue(thread.heldMessages.isEmpty())
        assertNull("the read marker goes", h.store.record.lastReadAt)
    }

    @Test
    fun `logout wipes at once and the UI hook runs before the call returns`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        busyVisitor(h)
        val generation = h.state.visitorGeneration
        val hooks = mutableListOf<Boolean>()
        h.engine.visitorWiped = { hooks += it }

        h.engine.logout()
        assertEquals("at once, before anything else ran", listOf(true), hooks)
        assertWiped(h, generation)
    }

    @Test
    fun `another user over an active one wipes at once, in call order`() = runTest {
        val h = Harness(this)
        val gateB = CompletableDeferred<Unit>()
        h.http.on(verify) { call ->
            if (call.body?.getJSONObject("user")?.getString("identifier") == "u_a") {
                ok(json("contactIdentifier" to "wb_A"))
            } else {
                gateB.await()
                ok(json("contactIdentifier" to "wb_B"))
            }
        }
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1", type = "INCOMING", content = "Hi Anna", at = 5))) }
        h.restoreAnswer = { openAt("c1") }
        h.engine.identify(userA)
        h.start()
        h.engine.setVisible(true)
        h.settle()
        assertEquals("c1", h.thread.conversationId)
        val generation = h.state.visitorGeneration
        val hooks = mutableListOf<Boolean>()
        h.engine.visitorWiped = { hooks += it }

        h.engine.identify(userB)
        assertEquals("at once, before the verification", listOf(true), hooks)
        assertWiped(h, generation)
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)

        gateB.complete(Unit)
        h.settle()
        assertEquals(VisitorIdentity.Verified("u_b"), h.state.identity)
        assertEquals("one wipe only", listOf(true), hooks)
    }

    @Test
    fun `another user over a dormant one wipes the texts held meanwhile too`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true), record = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a"))
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_B")) }
        h.start()
        assertEquals(SendOutcome.Held, h.engine.send("Typed before the sign in"))
        assertEquals(listOf("Typed before the sign in"), h.thread.heldMessages)
        val generation = h.state.visitorGeneration
        val hooks = mutableListOf<Boolean>()
        h.engine.visitorWiped = { hooks += it }

        h.engine.identify(userB)
        assertEquals(listOf(true), hooks)
        assertWiped(h, generation)
        assertEquals(VisitorRecord(bearer = "wb_B", verifiedExternalId = "u_b"), h.store.record)
        assertTrue("nothing of the wiped visitor goes out", h.sends().isEmpty())
    }

    @Test
    fun `a refused identity wipes the visitor on screen`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", lastReadAt = BASE_TIME))
        h.restoreAnswer = { openAt("c1") }
        val answer = CompletableDeferred<Unit>()
        h.http.on(verify) {
            answer.await()
            fail(401, json("error" to "invalid_identifier_hash"))
        }
        h.engine.identify(userA)
        h.start()
        assertEquals("c1", h.thread.conversationId)
        val generation = h.state.visitorGeneration
        val hooks = mutableListOf<Boolean>()
        h.engine.visitorWiped = { hooks += it }

        answer.complete(Unit)
        h.settle()
        assertEquals(listOf(true), hooks)
        assertWiped(h, generation)
    }

    @Test
    fun `another user followed at once by a new conversation and a text keeps the text for the new user`() = runTest {
        val h = Harness(this)
        val gateB = CompletableDeferred<Unit>()
        h.http.on(verify) { call ->
            if (call.body?.getJSONObject("user")?.getString("identifier") == "u_a") {
                ok(json("contactIdentifier" to "wb_A"))
            } else {
                gateB.await()
                ok(json("contactIdentifier" to "wb_B"))
            }
        }
        h.restoreAnswer = { openAt("c1") }
        h.engine.identify(userA)
        h.start()
        val hooks = mutableListOf<Boolean>()
        h.engine.visitorWiped = { hooks += it }

        // The app's three calls in a row, with nothing running in between.
        h.engine.identify(userB)
        h.engine.startNewConversation()
        val outcome = h.engine.send("Order 4711")
        assertTrue(outcome is SendOutcome.Queued)
        assertEquals(listOf(true), hooks)

        h.restoreAnswer = { openAt("c7") }
        h.http.on("POST /api/widget/messages") { sent(conversationId = "c8", bearer = "wb_B", newConversation = true) }
        gateB.complete(Unit)
        h.settle()
        val body = checkNotNull(h.sends().single().body)
        assertEquals("wb_B", body.getString("contactIdentifier"))
        assertEquals("Order 4711", body.getString("content"))
        assertEquals("a new conversation, not B's restored one", true, body.getBoolean("forceNewConversation"))
        assertEquals("no second wipe", listOf(true), hooks)
    }

    @Test
    fun `a stored visitor never shown ends without touching the one on screen`() = runTest {
        // An anonymous first message over a dormant identity.
        val h = Harness(this, record = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a"))
        h.start()
        val hooks = mutableListOf<Boolean>()
        h.engine.visitorWiped = { hooks += it }
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c9", bearer = "wb_anon") }
        h.engine.send("Hi, no account")
        h.advance(1_000)
        assertEquals("files only", listOf(false), hooks)
        assertEquals(0, h.state.visitorGeneration)
        assertEquals("c9", h.thread.conversationId)

        // Another user named before the store was read.
        val early = Harness(this, record = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a"))
        early.http.on(verify) { ok(json("contactIdentifier" to "wb_B")) }
        val earlyHooks = mutableListOf<Boolean>()
        early.engine.visitorWiped = { earlyHooks += it }
        early.engine.identify(userB)
        early.start()
        assertEquals(listOf(false), earlyHooks)
        assertEquals(0, early.state.visitorGeneration)
        assertEquals(VisitorIdentity.Verified("u_b"), early.state.identity)
    }
}
