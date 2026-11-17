package com.deskwoot.sdk.realtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun `events split across chunks come out whole`() {
        val parser = SseParser()
        assertTrue(parser.add("id: 7\nevent: mess").isEmpty())
        assertTrue(parser.add("age\ndata: {\"a\":").isEmpty())
        val events = parser.add("1}\n\n")

        assertEquals(listOf(SseEvent("message", "7", "{\"a\":1}")), events)
    }

    @Test
    fun `comments are heartbeats, data lines join, CRLF works`() {
        val parser = SseParser()
        val events = parser.add(":heartbeat\r\n\r\ndata: one\r\ndata: two\r\n\r\ndata:x\n\n")

        assertEquals(2, events.size)
        assertEquals("one\ntwo", events[0].data)
        assertNull(events[0].event)
        assertEquals("x", events[1].data)
    }

    @Test
    fun `an Ably envelope decodes its json data and its channel`() {
        val envelope = JSONObject()
            .put("id", "m1")
            .put("channel", "[?rewind=2m]widget-conversation:c1")
            .put("name", "message:created")
            .put("encoding", "utf-8/json")
            .put("data", "{\"conversationId\":\"c1\"}")
            .put("timestamp", 1_700_000_000_000L)
        val message = checkNotNull(parseAblyEnvelope(envelope.toString()))

        assertEquals("widget-conversation:c1", message.channel)
        assertEquals("message:created", message.name)
        assertEquals("c1", (message.data as JSONObject).getString("conversationId"))
        assertEquals("m1", message.id)
        assertNull(parseAblyEnvelope("not json"))
        assertNull(parseAblyEnvelope("{\"name\":\"x\"}"))
    }

    @Test
    fun `a lone CR ends a line, also when the LF of a CRLF comes in the next chunk`() {
        val parser = SseParser()
        assertEquals(listOf(SseEvent(null, null, "one")), parser.add("data: one\r\r"))
        assertTrue(parser.add("data: two\r").isEmpty())
        // The LF belongs to the CR before it: no empty line, no event yet.
        assertTrue(parser.add("\ndata: three\r\n").isEmpty())
        assertEquals(listOf(SseEvent(null, null, "two\nthree")), parser.add("\r\n"))
    }

    @Test
    fun `an event without data is not dispatched`() {
        val parser = SseParser()
        assertTrue(parser.add("event: ping\nid: 4\n\n").isEmpty())
        assertEquals(listOf(SseEvent(null, "4", "x")), parser.add("data: x\n\n"))
    }

    @Test
    fun `a line or an event over 1 MiB throws, so the stream is dropped`() {
        val endless = SseParser()
        val chunk = "a".repeat(64 * 1024)
        var thrown = false
        try {
            repeat(17) { endless.add(if (it == 0) "data: $chunk" else chunk) }
        } catch (_: SseTooLong) {
            thrown = true
        }
        assertTrue(thrown)

        val manyLines = SseParser()
        thrown = false
        try {
            repeat(20) { manyLines.add("data: $chunk\n") }
        } catch (_: SseTooLong) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
