package com.deskwoot.sdk.engine

import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.CsatState
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.ResolveResult
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** End, "Frage geklärt", resolve and reopen, CSAT and transcript. */
class AfterChatTest {
    private fun harness(scope: TestScope, vararg config: Pair<String, Any?>): Harness {
        val h = Harness(scope, baseConfig(*config), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        return h
    }

    @Test
    fun `ending a classic chat puts it into the end state, a new conversation starts fresh`() = runTest {
        val h = harness(this)
        assertTrue(h.thread.canEnd)
        h.http.once("POST /api/widget/conversations/c1/end") { ok(json("ok" to true, "alreadyResolved" to false)) }

        assertTrue(h.engine.endConversation())
        val body = checkNotNull(h.http.calls("POST /api/widget/conversations/c1/end").single().body)
        assertEquals("wb_known", body.getString("contactIdentifier"))
        assertTrue(h.thread.inEndState)
        assertFalse(h.thread.canSend)
        assertFalse(h.thread.canEnd)
        assertTrue(h.thread.transcriptOffered)

        h.engine.startNewConversation()
        assertEquals(null, h.thread.conversationId)
        assertTrue(h.thread.canSend)
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c2", bearer = "wb_known", newConversation = true) }
        h.engine.send("A new question")
        val sent = checkNotNull(h.sends().single().body)
        assertTrue(sent.getBoolean("forceNewConversation"))
        assertTrue(sent.isNull("conversationId"))
        assertEquals("c2", h.thread.conversationId)
    }

    @Test
    fun `ending fails without a 2xx and keeps the chat`() = runTest {
        val h = harness(this)
        h.http.once("POST /api/widget/conversations/c1/end") { fail(500) }
        assertFalse(h.engine.endConversation())
        assertFalse(h.thread.inEndState)
        assertFalse(h.thread.ending)
    }

    @Test
    fun `in continuous chat the end marks the conversation resolved and the composer stays`() = runTest {
        val h = harness(this, "continuousChat" to true)
        h.http.once("POST /api/widget/conversations/c1/end") { ok(json("ok" to true)) }
        assertTrue(h.engine.endConversation())
        assertTrue(h.thread.resolved)
        assertFalse(h.thread.inEndState)
        assertTrue(h.thread.canSend)
        assertEquals(com.deskwoot.sdk.model.ConversationStatus.RESOLVED, h.thread.segments.single().status)
    }

    @Test
    fun `a poll that says resolved ends a classic chat, a send that reopens it brings the composer back`() = runTest {
        val h = harness(this)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), resolved = true, showCsatSurvey = true)) }
        h.advance(5_000)
        assertTrue(h.thread.inEndState)
        assertTrue(h.thread.showCsat)
        assertTrue(h.thread.csatDue)
        // The conversation is over: no stream any more; the poll still runs.
        assertTrue(h.realtime.current == null)

        // A resolved chat that goes on for the visitor keeps the composer.
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), resolved = true, continuesAfterResolve = true)) }
        h.advance(5_000)
        assertFalse(h.thread.inEndState)

        // A poll asked before the send reopened the chat still says resolved; it must not undo the reopen.
        val slowPoll = CompletableDeferred<Unit>()
        h.http.once("GET /api/widget/messages") {
            slowPoll.await()
            ok(pollAnswer(row("a1"), resolved = true, continuesAfterResolve = true))
        }
        h.advance(5_000)
        h.advance(1)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"))) }
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", reopened = true) }
        h.engine.send("One more thing")
        assertFalse(h.thread.resolved)
        slowPoll.complete(Unit)
        h.settle()
        assertFalse(h.thread.resolved)
        assertTrue(h.thread.canSend)
    }

    @Test
    fun `Frage geklärt shows the choice at once and ends the chat`() = runTest {
        val h = harness(this, BOT_ON)
        val answer = row("r1", content = "Done!", at = 5, attrs = json("bot_id" to "b1", "fynnEnd" to mapOf("resolved" to "Frage geklärt", "team" to "Team")))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to answer))
        assertEquals("Frage geklärt", h.thread.messages.single().attributes.fynnEnd?.resolved)
        assertEquals("r1", h.thread.fynnEndMessageId)

        h.http.once("POST /api/widget/conversations/c1/resolved") {
            ok(json("ok" to true, "alreadyResolved" to false, "line" to mapOf("id" to "line1", "createdAt" to "2026-11-16T10:00:06.000Z")))
        }
        val result = h.engine.markResolved()
        assertEquals(ResolveResult.RESOLVED, result)
        assertEquals("Frage geklärt", h.thread.pending.single().text)
        assertEquals(null, h.thread.fynnEndMessageId)
        val choice = h.thread.pending.single()
        assertTrue(choice.questionResolved)
        assertEquals(PendingState.SENT, choice.state)
        assertTrue(choice.createdAt > checkNotNull(com.deskwoot.sdk.model.IsoTime.parse("2026-11-16T10:00:05.000Z")))
        assertTrue(h.thread.inEndState)

        // The resolve line arrives: the pending choice gives way to it.
        val line = row("line1", type = "ACTIVITY", content = "Question answered", at = 6, attrs = json("activityKind" to "resolved_by_contact", "source" to "fynn_resolved"))
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(answer, line, resolved = true)) }
        h.advance(30_000)
        assertTrue(h.thread.pending.isEmpty())
        assertTrue(h.thread.messages.last().questionResolved)
    }

    @Test
    fun `Frage geklärt after a person answered is no error and polls at once`() = runTest {
        val h = harness(this, BOT_ON)
        val answer = row("r1", content = "Done!", at = 5, attrs = json("bot_id" to "b1", "fynnEnd" to mapOf("resolved" to "Frage geklärt")))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to answer))
        val pollsBefore = h.polls().size
        h.http.once("POST /api/widget/conversations/c1/resolved") { fail(409, json("code" to "not_fynn_last")) }
        assertEquals(ResolveResult.NOT_FYNN_LAST, h.engine.markResolved())
        h.settle()
        assertTrue(h.thread.pending.isEmpty())
        assertEquals(pollsBefore + 1, h.polls().size)
        assertFalse(h.thread.inEndState)
    }

    @Test
    fun `choice buttons show under the bot's newest reply until the visitor writes`() = runTest {
        val h = harness(this)
        val reply = row("r1", content = "Which one?", at = 5, attrs = json("bot_id" to "b1", "buttons" to listOf(mapOf("label" to "A"), mapOf("label" to "B"))))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to reply))
        assertEquals("r1", h.thread.choicesMessageId)
        // Without a Fynn answer there is nothing to mark resolved.
        assertEquals(null, h.thread.fynnEndMessageId)
        assertEquals(ResolveResult.FAILED, h.engine.markResolved())

        val slow = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            slow.await()
            sent(bearer = "wb_known")
        }
        launch { h.engine.pressButton(ChatButton.Choice("A")) }
        h.settle()
        assertEquals("A", h.thread.pending.single().text)
        assertEquals(null, h.thread.choicesMessageId)
        slow.complete(Unit)
        h.settle()
        assertEquals("A", h.sends().single().body?.getString("content"))
    }

    @Test
    fun `the team button sends its label with the team action`() = runTest {
        val h = harness(this, BOT_ON)
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = false) }
        assertTrue(h.engine.pressButton(ChatButton.FynnTeam("Talk to the team")) is SendOutcome.Queued)
        val body = checkNotNull(h.sends().single().body)
        assertEquals("Talk to the team", body.getString("content"))
        assertEquals("team", body.getString("fynnAction"))
    }

    @Test
    fun `CSAT is sent once with an integer rating and a trimmed comment`() = runTest {
        val h = harness(this)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), resolved = true, showCsatSurvey = true)) }
        h.advance(5_000)
        assertTrue(h.thread.csatDue)
        assertFalse(h.engine.submitCsat(6, null))

        h.http.once("POST /api/widget/csat") { ok(json("id" to "s1", "rating" to 4)) }
        assertTrue(h.engine.submitCsat(4, "  Quick help  "))
        val body = checkNotNull(h.http.calls("POST /api/widget/csat").single().body)
        assertEquals(4, body.getInt("rating"))
        assertEquals("Quick help", body.getString("feedback"))
        assertEquals("c1", body.getString("conversationId"))
        assertFalse(body.has("widgetToken"))
        assertEquals(CsatState.SUBMITTED, h.thread.csatState)
        assertTrue(h.thread.csatDue)

        h.http.once("POST /api/widget/csat") { fail(409, json("error" to "A rating has already been submitted for this conversation.")) }
        assertFalse(h.engine.submitCsat(5, null))
        assertEquals(CsatState.FAILED, h.thread.csatState)
    }

    @Test
    fun `the transcript asks for an address when the server needs one`() = runTest {
        val h = harness(this)
        h.http.once("POST /api/widget/transcript") { fail(400, json("error" to "Enter the email address", "needsEmail" to true)) }
        assertEquals(TranscriptState.NEEDS_EMAIL, h.engine.requestTranscript())
        assertFalse(checkNotNull(h.http.calls("POST /api/widget/transcript").single().body).has("email"))

        h.http.once("POST /api/widget/transcript") { ok(json("success" to true)) }
        assertEquals(TranscriptState.SENT, h.engine.requestTranscript(" anna@acme.io "))
        assertEquals("anna@acme.io", h.http.calls("POST /api/widget/transcript").last().body?.getString("email"))
        assertEquals(TranscriptState.SENT, h.thread.transcriptState)

        h.http.once("POST /api/widget/transcript") { fail(502, json("error" to "transcript_failed")) }
        assertEquals(TranscriptState.FAILED, h.engine.requestTranscript())
    }

    @Test
    fun `a send refused as closed switches the chat to read only`() = runTest {
        val h = harness(this)
        h.http.once("POST /api/widget/messages") { fail(403, json("error" to "This conversation is closed.", "code" to "conversation_closed")) }
        h.engine.send("Hello?")
        assertTrue(h.thread.closed)
        assertTrue(h.thread.pending.isEmpty())
        assertFalse(h.thread.canSend)
        assertTrue(h.realtime.current == null)

        // The team lifts it: the next poll without the flag opens the chat again.
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"))) }
        h.advance(5_000)
        assertFalse(h.thread.closed)
        assertEquals("widget-conversation:c1", h.realtime.current?.channel)
    }
}
