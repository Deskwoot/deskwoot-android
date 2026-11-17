package com.deskwoot.sdk.engine

import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.IsoTime
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.CacheSnapshot
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The restore lookup on every start. */
class RestoreTest {
    private val known = VisitorRecord(bearer = "wb_known", lastReadAt = IsoTime.parse("2026-11-16T09:00:00.000Z"))

    @Test
    fun `an open conversation is restored, polled and streamed`() = runTest {
        val h = Harness(this, record = known)
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 2)) }
        h.start()

        val lookup = h.restores().single()
        assertEquals("wb_known", lookup.contact)
        assertEquals("2026-11-16T09:00:00.000Z", lookup.query["lastReadAt"])
        assertEquals(Phase.READY, h.state.phase)
        assertEquals("c1", h.thread.conversationId)
        assertEquals("c1", h.polls().last().query["conversationId"])
        assertEquals("0", h.polls().last().query["seen"])
        assertEquals("widget-conversation:c1", h.realtime.current?.channel)
        // The restored unread count holds until the thread's rows arrive.
        assertEquals(2, h.state.unreadCount)
        assertEquals("c1", h.store.record.lastConversationId)
    }

    @Test
    fun `a resolved conversation of a normal inbox is not shown`() = runTest {
        val h = Harness(this, record = known)
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "RESOLVED", "resolvedAt" to "2026-11-15T10:00:00.000Z", "unreadCount" to 0)) }
        h.start()

        assertNull(h.thread.conversationId)
        assertTrue(h.polls().isEmpty())
        assertTrue(h.thread.canSend)
        assertFalse(h.thread.inEndState)
    }

    @Test
    fun `a resolved conversation is shown in continuous chat, and when it goes on for the visitor`() = runTest {
        val continuous = Harness(this, baseConfig("continuousChat" to true), record = known)
        continuous.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "RESOLVED", "unreadCount" to 0)) }
        continuous.start()
        assertEquals("c1", continuous.thread.conversationId)
        assertTrue(continuous.thread.resolved)
        assertFalse(continuous.thread.inEndState)
        assertTrue(continuous.thread.canSend)

        val continuing = Harness(this, record = known)
        continuing.restoreAnswer = { ok(json("conversationId" to "c2", "status" to "RESOLVED", "continuesAfterResolve" to true)) }
        continuing.start()
        assertEquals("c2", continuing.thread.conversationId)
        assertTrue(continuing.thread.resolved)
        assertTrue(continuing.thread.continuesAfterResolve)
        assertFalse(continuing.thread.inEndState)
    }

    @Test
    fun `no conversation leaves a fresh thread and tells whether the email is known`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true), record = known)
        h.restoreAnswer = { ok(json("conversationId" to null, "emailKnown" to true)) }
        h.start()

        assertNull(h.thread.conversationId)
        assertTrue(h.thread.emailKnown)
        assertEquals(com.deskwoot.sdk.model.Gate.None, h.thread.gate)
    }

    @Test
    fun `a closed visitor gets the read only state`() = runTest {
        val h = Harness(this, record = known)
        h.restoreAnswer = { ok(json("conversationId" to null, "closed" to true)) }
        h.start()

        assertTrue(h.thread.closed)
        assertFalse(h.thread.canSend)
        assertTrue(h.realtime.opened.isEmpty())
    }

    @Test
    fun `a 429 is not an empty answer and the thread from the disk stays`() = runTest {
        val cachedRow = checkNotNull(Message.parse(row("m1", type = "INCOMING", content = "Earlier", conversationId = "c9")))
        val snapshot = CacheSnapshot(
            identity = "anonymous",
            config = null,
            conversationId = "c9",
            messages = listOf(cachedRow),
            conversations = emptyList(),
            segments = emptyList(),
            resolved = false,
            continuesAfterResolve = false,
        )
        val h = Harness(this, record = known.copy(lastConversationId = "c9"), cached = snapshot)
        h.restoreAnswer = { fail(429, json("error" to "rate_limited"), retryAfter = "10") }
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("m1", type = "INCOMING", content = "Earlier", conversationId = "c9"))) }
        h.start()

        assertEquals("c9", h.thread.conversationId)
        assertEquals(listOf("m1"), h.thread.messages.map { it.id })
        assertEquals("c9", h.polls().last().query["conversationId"])
    }

    @Test
    fun `the cached thread is not painted for another identity`() = runTest {
        val cachedRow = checkNotNull(Message.parse(row("m1", type = "INCOMING", content = "Someone else's")))
        val snapshot = CacheSnapshot("verified:u_other", null, "c1", listOf(cachedRow), emptyList(), emptyList(), false, false)
        val h = Harness(this, record = known, cached = snapshot)
        h.start()
        assertTrue(h.thread.messages.isEmpty())
    }
}
