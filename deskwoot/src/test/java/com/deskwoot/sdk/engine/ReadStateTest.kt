package com.deskwoot.sdk.engine

import com.deskwoot.sdk.BASE_TIME
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.iso
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.IsoTime
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `seen`, the read marker, the poll speed and the unread count. */
class ReadStateTest {
    private fun entry(id: String, unread: Int, at: Long) =
        json("id" to id, "status" to "OPEN", "lastActivityAt" to iso(at), "lastMessage" to null, "unread" to unread)

    @Test
    fun `replies the visitor has not seen count across conversations`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known", lastReadAt = BASE_TIME + 1_000))
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 1)) }
        h.listAnswer = { ok(json("conversations" to listOf(entry("c1", 1, 5), entry("c2", 2, 3)))) }
        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(row("a0", at = 0), row("v1", type = "INCOMING", content = "Hi", at = 0), row("a1", at = 5, sender = json("name" to "Mia"))))
        }
        h.start()

        // Not on screen: seen=0, and only the reply after the read marker counts, plus c2's two.
        assertEquals("0", h.polls().last().query["seen"])
        assertEquals(3, h.state.unreadCount)
        assertEquals(listOf("c1", "c2"), h.state.conversations.map { it.id })

        h.engine.setVisible(true)
        h.settle()
        assertEquals("1", h.polls().last().query["seen"])
        assertEquals(2, h.state.unreadCount)
        assertTrue(checkNotNull(h.store.record.lastReadAt) >= BASE_TIME + 5_000)

        h.engine.setVisible(false)
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a2", at = 60, sender = json("name" to "Mia"))))
        assertEquals(3, h.state.unreadCount)
        assertEquals(1, h.state.conversations.first { it.id == "c1" }.unread)
    }

    /**
     * c2 is the newer conversation (the team's reply at +600 s), c1 the
     * older one (its reply at +100 s). The server counts, as the real one
     * does, the replies newer than the read marker the request names.
     */
    private fun newerAndOlder(scope: TestScope): Harness {
        val h = Harness(scope, record = VisitorRecord(bearer = "wb_known"))
        fun unreadAfter(marker: String?, replyAt: Long): Int =
            if (marker == null || checkNotNull(IsoTime.parse(marker)) < BASE_TIME + replyAt * 1_000) 1 else 0
        h.http.on("GET /api/widget/conversations") { call ->
            val marker = call.query["lastReadAt"]
            if (call.query["list"] == "1") {
                ok(json("conversations" to listOf(entry("c2", unreadAfter(marker, 600), 600), entry("c1", unreadAfter(marker, 100), 100))))
            } else {
                ok(json("conversationId" to "c2", "status" to "OPEN", "unreadCount" to unreadAfter(marker, 600)))
            }
        }
        h.http.on("GET /api/widget/messages") { call ->
            if (call.query["conversationId"] == "c2") {
                ok(
                    pollAnswer(
                        row("v2", type = "INCOMING", content = "Where is my order?", at = 500, conversationId = "c2"),
                        row("a2", content = "It ships today.", at = 600, conversationId = "c2", sender = json("name" to "Mia")),
                    ),
                )
            } else {
                ok(
                    pollAnswer(
                        row("v1", type = "INCOMING", content = "Hi", at = 50, conversationId = "c1"),
                        row("a1", content = "Hello", at = 100, conversationId = "c1", sender = json("name" to "Mia")),
                    ),
                )
            }
        }
        return h
    }

    /** The visitor reads c2, then opens the older c1 (on screen, so it polls with seen=1). */
    private fun readNewerThenOlder(h: Harness) {
        h.start()
        h.engine.setVisible(true)
        h.settle()
        h.engine.openConversation("c1")
        h.settle()
    }

    /** The messenger closes, the app goes to the background and comes back: the refresh restores. */
    private fun closeAndComeBack(h: Harness) {
        h.engine.setVisible(false)
        h.settle()
        h.engine.setForeground(false)
        h.settle()
        h.engine.setForeground(true)
        h.settle()
    }

    @Test
    fun `reading an older conversation leaves the read marker on the newer reply`() = runTest {
        val h = newerAndOlder(this)
        h.start()
        h.engine.setVisible(true)
        h.settle()
        assertEquals(BASE_TIME + 600_000, h.store.record.lastReadAt)

        h.engine.openConversation("c1")
        h.settle()
        assertEquals("1", h.polls().last().query["seen"])
        assertEquals("c1", h.polls().last().query["conversationId"])
        assertEquals("the marker never moves back", BASE_TIME + 600_000, h.store.record.lastReadAt)

        // The restore and the list after the return to the foreground name the newer marker.
        closeAndComeBack(h)
        assertEquals(iso(600), h.restores().last().query["lastReadAt"])
        assertEquals(iso(600), h.http.calls("GET /api/widget/conversations").last { it.query["list"] == "1" }.query["lastReadAt"])
    }

    @Test
    fun `a reply read before stays read after the visitor opened an older conversation`() = runTest {
        val h = newerAndOlder(this)
        readNewerThenOlder(h)
        closeAndComeBack(h)
        // The restore brought c2 back off screen: its reply was read on screen before.
        assertEquals("c2", h.thread.conversationId)
        assertEquals("unread count and launcher badge", 0, h.state.unreadCount)
        assertEquals("the list", listOf(0, 0), h.state.conversations.map { it.unread })

        // A newer reply in c2 still counts.
        h.realtime.emit("message:created", json("conversationId" to "c2", "message" to row("a3", at = 700, conversationId = "c2", sender = json("name" to "Mia"))))
        assertEquals(1, h.state.unreadCount)
    }

    @Test
    fun `a reply that arrives by realtime on screen polls at once with seen=1, so no push follows`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        val before = h.polls().size
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a1", at = 10, sender = json("name" to "Mia"))))
        assertEquals(before + 1, h.polls().size)
        assertEquals("1", h.polls().last().query["seen"])

        // The AI agent's reply too.
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("b1", content = "We ship today.", at = 11, attrs = json("bot_id" to "bot1"))))
        assertEquals(before + 2, h.polls().size)
        assertEquals("1", h.polls().last().query["seen"])
    }

    @Test
    fun `no extra poll for the visitor's own row, or for a reply off screen`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        val before = h.polls().size
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("v9", type = "INCOMING", content = "Thanks", at = 10)))
        assertEquals(before, h.polls().size)

        h.engine.setVisible(false)
        h.settle()
        val hidden = h.polls().size
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a2", at = 12, sender = json("name" to "Mia"))))
        // Off screen the reply stays unread, and the push may come.
        assertEquals(hidden, h.polls().size)
    }

    @Test
    fun `the poll right after a reply that ended the wait does not bring the waiting line back`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        // The server clears the wait only after it pushed the reply: its next poll still says waiting.
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), waiting = true)) }
        h.realtime.emit("handover:waiting", json("conversationId" to "c1", "waiting" to true))
        h.advance(5_000)
        assertTrue(h.thread.waitingForTeam)

        val before = h.polls().size
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a2", content = "I'm here", at = 40, sender = json("name" to "Mia"))))
        assertEquals(before + 1, h.polls().size)
        assertFalse(h.thread.waitingForTeam)
        h.advance(5_000)
        assertFalse(h.thread.waitingForTeam)

        // Past 6 s the server's word counts again.
        h.advance(5_000)
        assertTrue(h.thread.waitingForTeam)
    }

    @Test
    fun `replies that arrive while a poll is in flight add one poll after it, not one each`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.on("GET /api/widget/messages") {
            delay(1_000)
            ok(pollAnswer())
        }
        val before = h.polls().size
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a1", at = 10, sender = json("name" to "Mia"))))
        h.settle()
        assertEquals(before + 1, h.polls().size)
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a2", at = 11, sender = json("name" to "Mia"))))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a3", at = 12, sender = json("name" to "Mia"))))
        h.advance(1_000)
        assertEquals(before + 2, h.polls().size)
        h.advance(1_000)
        h.advance(1_000)
        assertEquals(before + 2, h.polls().size)
    }

    @Test
    fun `off screen the poll slows to 30 s and the list refreshes every 30 s`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN")) }
        h.start()
        val polls = h.polls().size
        val lists = h.http.calls("GET /api/widget/conversations").count { it.query["list"] == "1" }

        h.advance(29_000)
        assertEquals(polls, h.polls().size)
        h.advance(1_100)
        assertEquals(polls + 1, h.polls().size)
        assertEquals(lists + 1, h.http.calls("GET /api/widget/conversations").count { it.query["list"] == "1" })

        h.engine.setVisible(true)
        h.settle()
        assertEquals(polls + 2, h.polls().size)
        h.advance(5_000)
        assertEquals(polls + 3, h.polls().size)
    }

    @Test
    fun `the background stops the poll and the stream, the foreground brings them back at once`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.engine.setForeground(false)
        h.settle()
        val polls = h.polls().size
        assertTrue(h.realtime.opened.all { it.closed })
        h.advance(120_000)
        assertEquals(polls, h.polls().size)

        h.engine.setForeground(true)
        h.settle()
        assertEquals(polls + 1, h.polls().size)
        assertEquals("widget-conversation:c1", h.realtime.current?.channel)
    }
}
