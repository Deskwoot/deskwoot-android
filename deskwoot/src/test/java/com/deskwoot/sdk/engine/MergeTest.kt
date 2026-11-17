package com.deskwoot.sdk.engine

import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rows from the poll and the push into one thread. */
class MergeTest {
    private fun harness(scope: kotlinx.coroutines.test.TestScope): Harness {
        val h = Harness(scope, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        return h
    }

    @Test
    fun `the pending bubble is replaced by its stored row`() = runTest {
        val h = harness(this)
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known") }
        h.engine.send("Hi")
        val uuid = h.thread.pending.single().uuid

        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(row("m1", type = "INCOMING", content = "Hi", attrs = json("clientAssignedUuid" to uuid))))
        }
        h.advance(5_000)
        assertTrue(h.thread.pending.isEmpty())
        assertEquals(listOf("m1"), h.thread.messages.map { it.id })
    }

    @Test
    fun `a pushed row with the pending uuid replaces the bubble too`() = runTest {
        val h = harness(this)
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known") }
        h.engine.send("Hi")
        val uuid = h.thread.pending.single().uuid
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("m1", type = "INCOMING", content = "Hi", attrs = json("clientAssignedUuid" to uuid))))
        assertTrue(h.thread.pending.isEmpty())
        assertEquals(1, h.thread.messages.size)
    }

    @Test
    fun `the bot's placeholder is filled in place and a stale poll never empties it`() = runTest {
        val h = harness(this)
        val typing = row("r1", content = "", at = 10, attrs = json("bot_id" to "b1", "isTyping" to true))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to typing))
        assertTrue(h.thread.botThinking)
        // The placeholder is never drawn as a bubble.
        assertTrue(h.thread.messages.isEmpty())

        val reply = row("r1", content = "Your order ships today.", at = 10, attrs = json("bot_id" to "b1"))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to reply))
        assertFalse(h.thread.botThinking)
        assertEquals("Your order ships today.", h.thread.messages.single().content)

        // A poll that started before the reply was written still has the placeholder.
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(typing)) }
        h.advance(5_000)
        assertEquals("Your order ships today.", h.thread.messages.single().content)
    }

    @Test
    fun `an empty poll answer never empties the thread`() = runTest {
        val h = harness(this)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1", content = "Hello"), row("v1", type = "INCOMING", content = "Hi", at = 1))) }
        h.advance(5_000)
        assertEquals(2, h.thread.messages.size)

        h.http.on("GET /api/widget/messages") { ok(pollAnswer()) }
        h.advance(5_000)
        assertEquals(listOf("a1", "v1"), h.thread.messages.map { it.id })
    }

    @Test
    fun `a pushed row the older snapshot lacks stays, a row older than the snapshot goes`() = runTest {
        val h = harness(this)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1", content = "One", at = 0), row("gone", content = "Old", at = 1))) }
        h.advance(5_000)
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a3", content = "Three", at = 30)))

        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1", content = "One", at = 0), row("a2", content = "Two", at = 20))) }
        h.advance(5_000)
        assertEquals(listOf("a1", "a2", "a3"), h.thread.messages.map { it.id })
    }

    @Test
    fun `realtime rows are deduplicated and hidden ones never show`() = runTest {
        val h = harness(this)
        val agent = row("a1", content = "Hi, Mia here", sender = json("name" to "Mia"))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to agent))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a1", content = "Hi, Mia here", sender = json("name" to "Mia"))))
        val note = row("n1", content = "internal").put("private", true)
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to note))
        val assignment = row("x1", type = "ACTIVITY", content = "Conversation assigned to Mia", attrs = json("activityKind" to "assigned"))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to assignment))
        val joined = row("x2", type = "ACTIVITY", content = "Mia joined", at = 2, attrs = json("activityKind" to "agent_joined", "agentName" to "Mia"))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to joined))

        assertEquals(listOf("a1", "x2"), h.thread.messages.map { it.id })
        assertTrue(h.thread.hasAgentReply)
    }

    @Test
    fun `a push too large for realtime is read by polling at once`() = runTest {
        val h = harness(this)
        val pollsBefore = h.polls().size
        val slim = row("a9", content = null).put("contentOmitted", true)
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to slim))
        h.settle()
        assertTrue(h.thread.messages.isEmpty())
        assertEquals(pollsBefore + 1, h.polls().size)
    }

    @Test
    fun `queue and handover pushes win over older polls`() = runTest {
        val h = harness(this)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), queue = 3)) }
        h.advance(5_000)
        assertEquals(3, h.thread.queuePosition)

        h.realtime.emit("queue:position", json("conversationId" to "c1", "position" to 1))
        h.advance(5_000)
        assertEquals(1, h.thread.queuePosition)
        h.advance(5_000)
        assertEquals(3, h.thread.queuePosition)

        h.realtime.emit("queue:position", json("conversationId" to "c1", "position" to null))
        h.realtime.emit("handover:waiting", json("conversationId" to "c1", "waiting" to true))
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), waiting = true)) }
        h.advance(6_001)
        assertTrue(h.thread.waitingForTeam)
        // A team member's reply ends the wait at once.
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a2", content = "I'm here", at = 40, sender = json("name" to "Mia"))))
        assertFalse(h.thread.waitingForTeam)
    }
}
