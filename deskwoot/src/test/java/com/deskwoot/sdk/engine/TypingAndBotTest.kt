package com.deskwoot.sdk.engine

import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typing in both directions and the bot's typing hold. */
class TypingAndBotTest {
    private fun typingCalls(h: Harness) = h.http.calls("POST /api/widget/typing").map {
        "${it.body?.getString("conversationId")}:${it.body?.getBoolean("isTyping")}@${it.at}"
    }

    @Test
    fun `typing signals at most every 3 s and stops 4 s after the last key`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        val t0 = testScheduler.currentTime

        h.engine.setTyping(true)
        h.advance(1_000)
        h.engine.setTyping(true)
        h.advance(2_500)
        h.engine.setTyping(true)
        h.settle()
        assertEquals(listOf("c1:true@$t0", "c1:true@${t0 + 3_500}"), typingCalls(h))

        h.advance(4_000)
        assertEquals("c1:false@${t0 + 7_500}", typingCalls(h).last())
        assertEquals("wb_known", h.http.calls("POST /api/widget/typing").last().body?.getString("contactIdentifier"))
    }

    @Test
    fun `an emptied field stops at once, and keys in another conversation stop the old one`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.engine.setTyping(true)
        h.engine.setTyping(false)
        h.settle()
        assertEquals(listOf(true, false), h.http.calls("POST /api/widget/typing").map { it.body?.getBoolean("isTyping") })

        h.engine.setTyping(true)
        h.settle()
        h.engine.openConversation("c2")
        h.engine.setTyping(true)
        h.settle()
        val last = h.http.calls("POST /api/widget/typing").takeLast(2).map { "${it.body?.getString("conversationId")}:${it.body?.getBoolean("isTyping")}" }
        assertEquals(listOf("c1:false", "c2:true"), last)
    }

    @Test
    fun `no typing signal before a conversation exists`() = runTest {
        val h = Harness(this)
        h.start()
        h.engine.setTyping(true)
        h.settle()
        assertTrue(h.http.calls("POST /api/widget/typing").isEmpty())
    }

    @Test
    fun `agent typing shows from realtime and goes after 6 s or with a reply`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.realtime.emit("typing:start", json("conversationId" to "c1", "userId" to "bot"))
        assertFalse(h.thread.agentTyping)
        h.realtime.emit("typing:start", json("conversationId" to "c1", "userId" to "agent"))
        assertTrue(h.thread.agentTyping)
        h.advance(6_001)
        assertFalse(h.thread.agentTyping)

        h.realtime.emit("typing:start", json("conversationId" to "c1", "userId" to "agent"))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("a1", sender = json("name" to "Mia"))))
        assertFalse(h.thread.agentTyping)
    }

    @Test
    fun `the bot hold ends with bot turn ended`() = runTest {
        val h = Harness(this, baseConfig(BOT_ON), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = true) }
        h.engine.send("Hi")
        assertTrue(h.thread.botThinking)
        h.advance(800)
        assertTrue(h.thread.botThinking)
        // Polls run every 0.8 s while the bot writes.
        val polls = h.polls().size
        h.advance(800)
        assertEquals(polls + 1, h.polls().size)

        h.realtime.emit("bot.turn_ended", json("conversationId" to "c1", "replied" to false))
        assertFalse(h.thread.botThinking)
        assertTrue(h.thread.canSend)
    }

    @Test
    fun `the bot hold ends after 35 s at the latest`() = runTest {
        val h = Harness(this, baseConfig(BOT_ON), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = true) }
        h.engine.send("Hi")
        h.advance(34_000)
        assertTrue(h.thread.botThinking)
        h.advance(1_100)
        assertFalse(h.thread.botThinking)
    }

    @Test
    fun `no hold when the server says no bot answers`() = runTest {
        val h = Harness(this, baseConfig(BOT_ON), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = false) }
        h.engine.send("Hi")
        assertFalse(h.thread.botThinking)
    }

    @Test
    fun `the tool line comes from the placeholder in the poll, and a reply after the send ends the turn`() = runTest {
        val h = Harness(this, baseConfig(BOT_ON), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = true) }
        h.engine.send("Where is my order?")
        val uuid = Harness.uuid(1)
        val mine = row("v1", type = "INCOMING", content = "Where is my order?", at = 1, attrs = json("clientAssignedUuid" to uuid))
        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(mine, row("r1", content = "", at = 2, attrs = json("bot_id" to "b1", "isTyping" to true, "bot_tool" to "order"))))
        }
        h.advance(800)
        assertTrue(h.thread.botThinking)
        assertEquals("order", h.thread.botTool)

        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(mine, row("r1", content = "It ships today.", at = 2, attrs = json("bot_id" to "b1", "replay_follows" to false))))
        }
        h.advance(800)
        assertFalse(h.thread.botThinking)
        assertNull(h.thread.botTool)
    }
}
