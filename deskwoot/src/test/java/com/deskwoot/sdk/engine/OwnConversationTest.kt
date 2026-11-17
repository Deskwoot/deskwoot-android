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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a conversation (a notification's) is this visitor's: confirmed by
 * the restore, the list or a write of this visitor; not theirs once the
 * visitor settled; unknown before. An id that is not confirmed is never
 * stored as the last conversation.
 */
class OwnConversationTest {
    private val userA = DeskwootUser("u_a", "a".repeat(64))
    private val verify = "POST /api/widget/identity/verify"

    private fun entry(id: String) = json("id" to id, "status" to "OPEN", "lastActivityAt" to "2026-11-16T09:00:00.000Z", "unread" to 0)

    private fun openAt(id: String) = ok(json("conversationId" to id, "status" to "OPEN", "unreadCount" to 0))

    @Test
    fun `the restore and the list confirm the visitor's conversations, any other id is not theirs once settled`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.restoreAnswer = { openAt("c1") }
        h.listAnswer = { ok(json("conversations" to listOf(entry("c1"), entry("c2")))) }
        assertNull("nothing read yet", h.engine.ownsConversation("c1"))

        h.start()
        assertEquals(true, h.engine.ownsConversation("c1"))
        assertEquals(true, h.engine.ownsConversation("c2"))
        assertEquals("another contact's or a forgotten visitor's", false, h.engine.ownsConversation("c9"))
    }

    @Test
    fun `a new visitor owns no conversation`() = runTest {
        val h = Harness(this)
        h.start()
        assertEquals(false, h.engine.ownsConversation("c1"))
    }

    @Test
    fun `nothing is known while the list has not loaded or an identify runs`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.restoreAnswer = { openAt("c1") }
        h.listAnswer = { offline() }
        h.start()
        assertEquals(true, h.engine.ownsConversation("c1"))
        assertNull("the list did not come", h.engine.ownsConversation("c2"))

        h.listAnswer = { ok(json("conversations" to listOf(entry("c1"), entry("c2")))) }
        val answer = CompletableDeferred<Unit>()
        h.http.on(verify) {
            answer.await()
            ok(json("contactIdentifier" to "wb_A"))
        }
        h.engine.identify(userA)
        h.settle()
        assertNull("the identity has not settled", h.engine.ownsConversation("c2"))

        h.restoreAnswer = { openAt("c7") }
        h.listAnswer = { ok(json("conversations" to listOf(entry("c7")))) }
        answer.complete(Unit)
        h.settle()
        assertEquals(true, h.engine.ownsConversation("c7"))
        assertEquals("the anonymous visitor's are not the user's", false, h.engine.ownsConversation("c1"))
    }

    @Test
    fun `an id that is not confirmed is never stored as the last conversation, a confirmed one is`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.listAnswer = { ok(json("conversations" to listOf(entry("c1"), entry("c2")))) }
        h.openThread("c1")
        assertEquals("c1", h.store.record.lastConversationId)

        // A tap of a stale registration: the server shows no rows of it to this visitor.
        h.engine.openConversation("c9")
        h.advance(6_000)
        assertEquals("c1", h.store.record.lastConversationId)

        h.engine.openConversation("c2")
        h.settle()
        assertEquals("c2", h.store.record.lastConversationId)

        // An id the list did not name yet whose rows the server shows to this visitor is theirs.
        h.http.on("GET /api/widget/messages") { call ->
            if (call.query["conversationId"] == "c3") ok(pollAnswer(row("m3", type = "INCOMING", conversationId = "c3"))) else ok(pollAnswer())
        }
        h.engine.openConversation("c3")
        h.settle()
        assertEquals(true, h.engine.ownsConversation("c3"))
        assertEquals("c3", h.store.record.lastConversationId)
    }
}
