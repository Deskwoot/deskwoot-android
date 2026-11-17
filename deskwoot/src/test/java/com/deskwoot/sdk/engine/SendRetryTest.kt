package com.deskwoot.sdk.engine

import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.SendFailure
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retries with one id, and one send at a time until the bearer is confirmed. */
class SendRetryTest {
    @Test
    fun `a network error and a 5xx go again with the same id and the same placeholder`() = runTest {
        val h = Harness(this)
        h.start()
        h.http.once("POST /api/widget/messages") { offline() }
        h.http.once("POST /api/widget/messages") { fail(503, json("error" to "db"), retryAfter = "2") }
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_1") }

        val job = launch { h.engine.send("Hi") }
        h.settle()
        assertEquals(PendingState.SENDING, h.thread.pending.single().state)
        h.advance(10_000)
        job.join()

        val tries = h.sends()
        assertEquals(3, tries.size)
        assertEquals(1, tries.map { it.headers["Idempotency-Key"] }.toSet().size)
        assertEquals(1, tries.map { it.body?.getString("client_assigned_uuid") }.toSet().size)
        assertEquals(setOf(Harness.uuid(1)), tries.map { it.body?.getString("contactIdentifier") }.toSet())
        // 1 s, up to a quarter shorter; then the server's Retry-After.
        assertTrue(tries[1].at - tries[0].at in 750..1_000)
        assertEquals(2_000, tries[2].at - tries[1].at)
        assertEquals(PendingState.SENT, h.thread.pending.single().state)
        assertEquals("wb_1", h.store.record.bearer)
    }

    @Test
    fun `after the 2 minute budget the message fails, and it keeps its id when sent again`() = runTest {
        val h = Harness(this)
        h.start()
        h.http.on("POST /api/widget/messages") { offline() }
        val job = launch { h.engine.send("Hi") }
        h.advance(130_000)
        job.join()

        val failed = h.thread.pending.single()
        assertEquals(PendingState.FAILED, failed.state)
        assertEquals(SendFailure.NOT_SENT, failed.error)
        val tries = h.sends()
        assertTrue(tries.size in 8..16)
        assertTrue(tries.last().at <= 120_000)
        assertEquals(setOf(failed.uuid), tries.map { it.headers["Idempotency-Key"] }.toSet())

        h.http.once("POST /api/widget/messages") { sent() }
        h.engine.retry(failed.uuid)
        assertEquals(failed.uuid, h.sends().last().headers["Idempotency-Key"])
        assertEquals(PendingState.SENT, h.thread.pending.single().state)
    }

    @Test
    fun `an immediate resend of the failed text keeps its id, a discarded id is never sent again`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.once("POST /api/widget/messages") { fail(500) }
        h.http.on("POST /api/widget/messages") { offline() }
        h.engine.send("Hi")
        h.advance(130_000)
        val first = h.thread.pending.single()
        assertEquals(PendingState.FAILED, first.state)

        // The visitor types the same text again while the failed bubble shows: the same id.
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known") }
        h.engine.send("Hi")
        assertEquals(first.uuid, h.sends().last().headers["Idempotency-Key"])
        assertEquals(1, h.thread.pending.size)

        // A discarded message's id is never sent again.
        h.http.on("POST /api/widget/messages") { offline() }
        h.engine.send("Again")
        h.advance(130_000)
        val failed = h.thread.pending.single { it.text == "Again" }
        h.engine.discard(failed.uuid)
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known") }
        h.engine.send("Again")
        assertNotEquals(failed.uuid, h.sends().last().headers["Idempotency-Key"])
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known") }
        h.engine.send("Something else")
        assertNotEquals(failed.uuid, h.sends().last().headers["Idempotency-Key"])
    }

    @Test
    fun `a 4xx is final and mapped by status and code`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        val cases = listOf(
            fail(429, json("error" to "Too many messages."), retryAfter = "30") to SendFailure.RATE_LIMITED,
            fail(429, json("code" to "new_conversation_limit")) to SendFailure.NEW_CONVERSATION_LIMIT,
            fail(413, json("code" to "message_too_long", "maxLength" to 20_000)) to SendFailure.MESSAGE_TOO_LONG,
            fail(403, json("code" to "monthly_conversation_limit")) to SendFailure.MONTHLY_CONVERSATION_LIMIT,
            fail(409, json("error" to "Conversation is resolved and reopening is disabled for this inbox.")) to SendFailure.CONVERSATION_RESOLVED,
        )
        for ((index, case) in cases.withIndex()) {
            h.http.once("POST /api/widget/messages") { case.first }
            h.engine.send("Message $index")
            assertEquals(index + 1, h.sends().size)
            assertEquals(case.second, h.thread.pending.last().error)
        }
    }

    @Test
    fun `no second send before the server confirmed the bearer`() = runTest {
        val h = Harness(this)
        h.start()
        val gate = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            gate.await()
            sent(bearer = "wb_1")
        }
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_1") }

        launch { h.engine.send("First") }
        launch { h.engine.send("Second") }
        h.settle()
        assertEquals(1, h.sends().size)
        assertEquals(2, h.thread.pending.size)

        gate.complete(Unit)
        h.settle()
        val second = checkNotNull(h.sends().getOrNull(1)?.body)
        assertEquals("wb_1", second.getString("contactIdentifier"))
        assertEquals("c1", second.getString("conversationId"))
        assertEquals("Second", second.getString("content"))
    }

    @Test
    fun `a stored anonymous bearer is confirmed by its first send of the session`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        val gate = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            gate.await()
            sent(bearer = "wb_known")
        }
        h.http.on("POST /api/widget/messages") { sent(bearer = "wb_known") }
        launch { h.engine.send("One") }
        launch { h.engine.send("Two") }
        h.settle()
        assertEquals(1, h.sends().size)
        gate.complete(Unit)
        h.settle()
        assertEquals(2, h.sends().size)

        // Confirmed now: sends no longer wait for each other.
        val slow = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            slow.await()
            sent(bearer = "wb_known")
        }
        launch { h.engine.send("Three") }
        launch { h.engine.send("Four") }
        h.settle()
        assertEquals(4, h.sends().size)
        slow.complete(Unit)
    }
}
