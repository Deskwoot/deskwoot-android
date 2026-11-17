package com.deskwoot.sdk.realtime

import com.deskwoot.sdk.BASE_TIME
import com.deskwoot.sdk.FakeHttp
import com.deskwoot.sdk.SilentLogger
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.virtualClock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for 2 realtime bugs found and fixed before the first
 * release, plus the realtime rules: the backoff starts over only after a
 * delivered event or 30 s of uptime, any 401 drops the token, an error
 * event clears the resume position, any exception is a drop, and a refusal
 * for good is reported through `onStopped`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SseRegressionTest {
    /** A stream that is accepted and ends at once. */
    private class EndingStream : EventStream {
        override val status: Int = 200

        override suspend fun read(): String? = null

        override suspend fun readError(): String = ""

        override fun close() = Unit
    }

    /** A stream fed by the test. */
    private class FedStream(override val status: Int = 200, private val errorBody: String = "") : EventStream {
        val chunks = Channel<String?>(Channel.UNLIMITED)

        override suspend fun read(): String? = chunks.receive()

        override suspend fun readError(): String = errorBody

        override fun close() = Unit
    }

    private val tokenRequest = { ApiResult.Success(json("keyName" to "app.key", "nonce" to "n", "mac" to "m")) }

    private fun TestScope.client(
        http: FakeHttp,
        scope: CoroutineScope = backgroundScope,
        connector: EventStreamConnector,
    ): AblySseClient {
        http.on("POST /keys/app.key/requestToken") { ok(json("token" to "tok", "issued" to BASE_TIME, "expires" to BASE_TIME + 3_600_000)) }
        return AblySseClient(http, connector, scope, virtualClock(testScheduler), SilentLogger, "https://realtime.test", "https://rest.test")
    }

    @Test
    fun `a stream that is accepted and ends at once reopens with a growing pause`() = runTest {
        val http = FakeHttp()
        var opens = 0
        val client = client(http) { _, _ ->
            opens++
            EndingStream()
        }
        client.open("widget-conversation:c1", { tokenRequest() }, { }) { }
        advanceTimeBy(60_000)
        runCurrent()
        // 1, 2, 4, 8, 15, 15, 15 s: 7 or 8 opens in a minute, never 61.
        assertTrue("opens in 60 s: $opens", opens in 5..9)
    }

    @Test
    fun `a runtime exception from the connection is a drop, realtime tries again`() = runTest {
        val http = FakeHttp()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> errors += e },
        )
        var opens = 0
        var stopped = 0
        val client = client(http, scope) { _, _ ->
            opens++
            throw IllegalStateException("platform quirk")
        }
        client.open("widget-conversation:c1", { tokenRequest() }, { }) { stopped++ }
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue("opens in 120 s: $opens", opens > 1)
        assertTrue(errors.isEmpty())
        assertEquals(0, stopped)
    }

    @Test
    fun `a stream that lived 30 s starts the backoff over, a shorter one does not`() = runTest {
        val http = FakeHttp()
        val streams = ArrayDeque<FedStream>()
        val opensAt = mutableListOf<Long>()
        val client = client(http) { _, _ ->
            opensAt += testScheduler.currentTime
            streams.removeFirstOrNull() ?: endingStream()
        }
        // Three short streams build the pause up to 4 s; then one lives 31 s with heartbeats only.
        val long = FedStream()
        streams.addAll(listOf(endingStream(), endingStream(), endingStream(), long))
        client.open("widget-conversation:c1", { tokenRequest() }, { }) { }
        runCurrent()
        advanceTimeBy(1_000 + 2_000 + 4_000 + 1)
        runCurrent()
        assertEquals(4, opensAt.size)
        long.chunks.send(":heartbeat\n\n")
        advanceTimeBy(31_000)
        long.chunks.send(null)
        runCurrent()
        val endedAt = testScheduler.currentTime
        // After a healthy stream the next try comes after 1 s again, not 8 s.
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(5, opensAt.size)
        assertTrue(opensAt.last() - endedAt <= 1_001)
    }

    @Test
    fun `any 401 drops the cached token, an error event clears the resume position`() = runTest {
        val http = FakeHttp()
        var trades = 0
        http.on("POST /keys/app.key/requestToken") {
            trades++
            ok(json("token" to "tok-$trades", "issued" to BASE_TIME, "expires" to BASE_TIME + 3_600_000))
        }
        val first = FedStream()
        val streams = ArrayDeque(listOf(first, FedStream(401, "{\"error\":{\"code\":40100,\"statusCode\":401}}")))
        val urls = mutableListOf<String>()
        val client = AblySseClient(
            http,
            { url, _ ->
                urls += url
                streams.removeFirstOrNull() ?: FedStream()
            },
            backgroundScope,
            virtualClock(testScheduler),
            SilentLogger,
            "https://realtime.test",
            "https://rest.test",
        )
        client.open("widget-conversation:c1", { tokenRequest() }, { }) { }
        runCurrent()
        // An event with an id, then an error event of a code outside 4014x and 40160.
        first.chunks.send("id: e1\ndata: {\"channel\":\"widget-conversation:c1\",\"name\":\"x\",\"id\":\"m1\"}\n\n")
        first.chunks.send("event: error\ndata: {\"code\":50000,\"statusCode\":500}\n\n")
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, urls.size)
        assertFalse("an error event leaves no resume position", urls[1].contains("lastEvent="))
        // That second stream is refused with a 401 of another code: the next try trades a new token.
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(3, urls.size)
        assertEquals(2, trades)
        assertTrue(urls[2].contains("accessToken=tok-2"))
    }

    @Test
    fun `a refused channel reports onStopped once and never reopens`() = runTest {
        val http = FakeHttp()
        var opens = 0
        var stopped = 0
        val client = client(http) { _, _ ->
            opens++
            FedStream(401, "{\"error\":{\"code\":40160,\"statusCode\":401}}")
        }
        client.open("widget-conversation:c1", { tokenRequest() }, { }) { stopped++ }
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, opens)
        assertEquals(1, stopped)
    }

    @Test
    fun `a closed connection never reports onStopped`() = runTest {
        val http = FakeHttp()
        var stopped = 0
        val client = client(http) { _, _ -> FedStream() }
        val connection = client.open("widget-conversation:c1", { tokenRequest() }, { }) { stopped++ }
        runCurrent()
        connection.close()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, stopped)
    }

    private fun endingStream(): FedStream = FedStream().also { it.chunks.trySend(null) }
}
