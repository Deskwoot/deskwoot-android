package com.deskwoot.sdk.realtime

import com.deskwoot.sdk.BASE_TIME
import com.deskwoot.sdk.FakeHttp
import com.deskwoot.sdk.SilentLogger
import com.deskwoot.sdk.api.ApiError
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.virtualClock
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AblySseClientTest {
    private class FakeStream(override val status: Int, private val errorBody: String = "") : EventStream {
        val chunks = Channel<String?>(Channel.UNLIMITED)
        var closed = false

        override suspend fun read(): String? = chunks.receive()

        override suspend fun readError(): String = errorBody

        override fun close() {
            closed = true
        }
    }

    private class Setup(val test: TestScope) {
        val http = FakeHttp()
        val urls = mutableListOf<String>()
        val streams = ArrayDeque<FakeStream>()
        val opened = mutableListOf<FakeStream>()
        val events = mutableListOf<RealtimeEvent>()
        var tokenRequests = 0
        var auth: suspend () -> ApiResult<JSONObject> = {
            tokenRequests++
            ApiResult.Success(json("keyName" to "app.key", "nonce" to "n$tokenRequests", "mac" to "m", "ttl" to 3_600_000))
        }
        val client = AblySseClient(
            http = http,
            connector = { url, _ ->
                urls += url
                streams.removeFirstOrNull()?.also { opened += it } ?: FakeStream(200).also { opened += it }
            },
            scope = test.backgroundScope,
            clock = virtualClock(test.testScheduler),
            logger = SilentLogger,
            realtimeBase = "https://realtime.test",
            restBase = "https://rest.test",
        )

        init {
            http.on("POST /keys/app.key/requestToken") { ok(json("token" to "tok-${tokenRequests}", "expires" to BASE_TIME + 3_600_000)) }
        }

        var stopped = 0

        fun open() = client.open("widget-conversation:c1", { auth() }, { events += it }) { stopped++ }
    }

    private fun envelope(id: String, name: String, data: JSONObject, channel: String = "widget-conversation:c1"): String {
        val message = JSONObject()
            .put("id", id)
            .put("channel", channel)
            .put("name", name)
            .put("encoding", "json")
            .put("data", data.toString())
        return "id: $id\ndata: $message\n\n"
    }

    @Test
    fun `a TokenRequest is traded, events arrive once, a drop resumes after the last event`() = runTest {
        val s = Setup(this)
        val first = FakeStream(200)
        s.streams.addLast(first)
        s.open()
        runCurrent()

        assertEquals(1, s.tokenRequests)
        val trade = s.http.calls("POST /keys/app.key/requestToken").single()
        assertEquals("2", trade.headers["X-Ably-Version"])
        assertEquals("n1", trade.body?.getString("nonce"))
        assertTrue(s.urls.single().startsWith("https://realtime.test/event-stream?v=1.2&channels=widget-conversation%3Ac1&accessToken=tok-1&heartbeats=true"))

        first.chunks.send(envelope("e1", "message:created", json("conversationId" to "c1")))
        first.chunks.send(envelope("e1", "message:created", json("conversationId" to "c1")))
        first.chunks.send(envelope("e2", "typing:start", json("conversationId" to "c1", "userId" to "agent"), channel = "widget-conversation:other"))
        runCurrent()
        assertEquals(listOf("message:created"), s.events.map { it.name })
        assertEquals("c1", s.events.single().data?.getString("conversationId"))

        // The server ends the stream: reconnect after 1 s, same token, resume after e2.
        first.chunks.send(null)
        runCurrent()
        assertTrue(first.closed)
        assertEquals(1, s.urls.size)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, s.urls.size)
        assertTrue(s.urls[1].endsWith("&lastEvent=e2"))
        assertEquals(1, s.tokenRequests)
    }

    @Test
    fun `a silent stream counts as dropped after 45 s`() = runTest {
        val s = Setup(this)
        s.open()
        runCurrent()
        assertEquals(1, s.urls.size)
        advanceTimeBy(44_000)
        runCurrent()
        assertEquals(1, s.urls.size)
        advanceTimeBy(2_000 + 1_000)
        runCurrent()
        assertEquals(2, s.urls.size)
    }

    @Test
    fun `an expired token is traded again at once`() = runTest {
        val s = Setup(this)
        val first = FakeStream(200)
        s.streams.addLast(first)
        s.open()
        runCurrent()
        first.chunks.send("event: error\ndata: {\"code\":40142,\"statusCode\":401}\n\n")
        runCurrent()

        assertEquals(2, s.tokenRequests)
        assertEquals(2, s.urls.size)
        assertTrue(s.urls[1].contains("accessToken=tok-2"))
    }

    @Test
    fun `a refused channel stops realtime for the conversation`() = runTest {
        val s = Setup(this)
        s.streams.addLast(FakeStream(401, "{\"error\":{\"code\":40160,\"statusCode\":401}}"))
        s.open()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, s.urls.size)
    }

    @Test
    fun `a token route that refuses for good stops, a 5xx is tried again`() = runTest {
        val refused = Setup(this)
        refused.auth = { ApiResult.Failure(ApiError(status = 404)) }
        refused.open()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(refused.urls.isEmpty())

        val busy = Setup(this)
        var tries = 0
        busy.auth = {
            tries++
            if (tries < 3) ApiResult.Failure(ApiError(status = 503)) else ApiResult.Success(json("token" to "ready", "expires" to BASE_TIME + 3_600_000))
        }
        busy.open()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(busy.urls.isEmpty())
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(3, tries)
        assertTrue(busy.urls.single().contains("accessToken=ready"))
        // A ready token needs no trade.
        assertTrue(busy.http.calls.isEmpty())
    }

    @Test
    fun `closing the connection stops the stream`() = runTest {
        val s = Setup(this)
        val first = FakeStream(200)
        s.streams.addLast(first)
        val connection = s.open()
        runCurrent()
        connection.close()
        runCurrent()
        assertTrue(first.closed)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, s.urls.size)
    }
}
