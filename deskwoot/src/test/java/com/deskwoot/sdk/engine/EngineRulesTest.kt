package com.deskwoot.sdk.engine

import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.CsatState
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.model.SendRefusal
import com.deskwoot.sdk.model.SessionFailure
import com.deskwoot.sdk.model.UploadErrorCode
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.CacheSnapshot
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the engine's rules, one section per rule; each
 * section names what it covers.
 */
class EngineRulesTest {
    private val verifyKey = "POST /api/widget/identity/verify"
    private val userA = DeskwootUser("u_a", "a".repeat(64))
    private val photo = UploadSource.Bytes(ByteArray(100) { 1 })

    private fun known(scope: kotlinx.coroutines.test.TestScope, vararg config: Pair<String, Any?>): Harness {
        val h = Harness(scope, baseConfig(*config), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        return h
    }

    // ── Every send answers at once with an outcome ──

    @Test
    fun `send answers at once with an outcome, and never drops a text silently`() = runTest {
        val before = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        // No session yet.
        assertEquals(SendOutcome.Refused(SendRefusal.NOT_READY), before.engine.send("Hi"))

        val h = known(this)
        assertEquals(SendOutcome.Refused(SendRefusal.EMPTY), h.engine.send("  \n "))
        assertEquals(SendOutcome.Refused(SendRefusal.TOO_LONG), h.engine.send("x".repeat(20_001)))
        val slow = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            slow.await()
            sent(bearer = "wb_known")
        }
        val outcome = h.engine.send("x".repeat(20_000))
        assertTrue(outcome is SendOutcome.Queued)
        assertEquals((outcome as SendOutcome.Queued).uuid, h.thread.pending.single().uuid)
        assertEquals(PendingState.SENDING, h.thread.pending.single().state)
        slow.complete(Unit)
        h.settle()

        h.http.on("GET /api/widget/messages") { ok(pollAnswer(closed = true)) }
        h.advance(5_000)
        assertTrue(h.thread.closed)
        assertEquals(SendOutcome.Refused(SendRefusal.CLOSED), h.engine.send("Hello?"))

        val gated = Harness(this, baseConfig("emailCollectEnabled" to true))
        gated.start()
        assertEquals(SendOutcome.Held, gated.engine.send("First"))
        assertEquals(SendOutcome.Held, gated.engine.send("Second"))
        assertEquals(listOf("First", "Second"), gated.thread.heldMessages)
    }

    @Test
    fun `ids are lower case even when the platform makes them in capitals`() = runTest {
        val h = Harness(this, upperCaseIds = true)
        h.start()
        h.http.once("POST /api/widget/messages") { sent() }
        h.engine.send("Hi")
        val call = h.sends().single()
        val key = checkNotNull(call.headers["Idempotency-Key"])
        assertEquals(key.lowercase(), key)
        assertEquals(key, call.body?.getString("client_assigned_uuid"))
        val placeholder = checkNotNull(call.body?.getString("contactIdentifier"))
        assertEquals(placeholder.lowercase(), placeholder)
    }

    // ── Held messages are a list ──

    @Test
    fun `a coded pre_chat_required without missingFields holds every unsent message and asks the form`() = runTest {
        val fields = listOf(mapOf("name" to "email", "type" to "email", "required" to true))
        val h = Harness(
            this,
            baseConfig("preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to fields)),
            record = VisitorRecord(bearer = "wb_gone"),
        )
        h.openThread(bearer = "wb_gone")
        val gate = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            gate.await()
            fail(400, json("error" to "Pre-chat form fields required", "code" to "pre_chat_required"))
        }
        h.engine.send("One")
        h.engine.send("Two")
        h.settle()
        gate.complete(Unit)
        h.settle()
        assertEquals(1, h.sends().size)
        assertEquals(listOf("One", "Two"), h.thread.heldMessages)
        assertTrue(h.thread.pending.isEmpty())
        assertTrue(h.thread.gate is Gate.PreChatForm)

        // After the form, both go out in order into the one conversation the first made.
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c9", bearer = "wb_new", newConversation = true) }
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c9", bearer = "wb_new") }
        assertTrue(h.engine.submitPreChat(mapOf("email" to "anna@acme.io")).isEmpty())
        val again = h.sends().drop(1)
        assertEquals(listOf("One", "Two"), again.map { it.body?.getString("content") })
        assertTrue(again[0].body?.isNull("conversationId") == true)
        assertEquals("c9", again[1].body?.getString("conversationId"))
        assertEquals("wb_new", again[1].body?.getString("contactIdentifier"))
    }

    @Test
    fun `a gate recovery forgets that the server knew an email`() = runTest {
        val fields = listOf(
            mapOf("name" to "name", "type" to "text", "required" to true),
            mapOf("name" to "email", "type" to "email", "required" to true),
        )
        val h = Harness(
            this,
            baseConfig("preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to fields)),
            record = VisitorRecord(bearer = "wb_gone"),
        )
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0, "emailKnown" to true)) }
        h.start()
        h.engine.setVisible(true)
        assertTrue(h.thread.emailKnown)
        h.http.once("POST /api/widget/messages") { fail(400, json("code" to "pre_chat_required", "missingFields" to listOf("email"))) }
        h.engine.send("Hi")
        assertFalse(h.thread.emailKnown)
        val form = h.thread.gate as Gate.PreChatForm
        assertTrue(form.fields.any { it.isEmail })
    }

    // ── Writes belong to their conversation ──

    @Test
    fun `a message written in c1 goes to c1 and shows only there after the visitor opens c2`() = runTest {
        val h = known(this)
        val slow = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            slow.await()
            sent(conversationId = "c1", bearer = "wb_known", reopened = true)
        }
        h.engine.send("For c1")
        h.settle()
        h.engine.openConversation("c2")
        assertEquals("c2", h.thread.conversationId)
        assertTrue(h.thread.pending.isEmpty())
        slow.complete(Unit)
        h.settle()
        assertEquals("c1", h.sends().single().body?.getString("conversationId"))
        // The answer never moves the thread back.
        assertEquals("c2", h.thread.conversationId)
        assertTrue(h.thread.pending.isEmpty())

        // A message written in c2 goes to c2.
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c2", bearer = "wb_known") }
        h.engine.send("For c2")
        assertEquals("c2", h.sends().last().body?.getString("conversationId"))
        assertEquals(listOf("For c2"), h.thread.pending.map { it.text })
        assertEquals("c2", h.thread.pending.single().conversationId)

        // Back in c1, its sent message shows again until its row comes.
        h.engine.openConversation("c1")
        assertEquals(listOf("For c1"), h.thread.pending.map { it.text })
    }

    @Test
    fun `an end answer for c1 never marks c2, and only c1 shows ending`() = runTest {
        val h = known(this)
        val slow = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/conversations/c1/end") {
            slow.await()
            ok(json("ok" to true))
        }
        val ended = async { h.engine.endConversation() }
        h.settle()
        assertTrue(h.thread.ending)
        h.engine.openConversation("c2")
        assertFalse(h.thread.ending)
        assertTrue(h.thread.canEnd)
        slow.complete(Unit)
        assertTrue(ended.await())
        assertFalse(h.thread.endedLocally)
        assertFalse(h.thread.inEndState)
        assertEquals("c2", h.thread.conversationId)
    }

    @Test
    fun `an upload answer for c1 never pulls the thread back from c2`() = runTest {
        val h = known(this)
        val slow = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/attachments") {
            slow.await()
            ok(json("messageId" to "f1", "conversationId" to "c5", "reopened" to false, "newConversation" to true))
        }
        val upload = async { h.engine.upload(photo, "a.png", "image/png") }
        h.settle()
        h.engine.openConversation("c2")
        slow.complete(Unit)
        assertNull(upload.await())
        assertEquals("c2", h.thread.conversationId)
    }

    @Test
    fun `two quick messages after start new conversation make one new conversation`() = runTest {
        val h = known(this)
        // A confirmed visitor: sends into a known conversation go side by side.
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c1", bearer = "wb_known") }
        h.engine.send("Hi")
        h.settle()
        h.engine.startNewConversation()
        val slow = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            slow.await()
            sent(conversationId = "c5", bearer = "wb_known", newConversation = true)
        }
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c5", bearer = "wb_known") }
        h.engine.send("One")
        h.engine.send("Two")
        h.settle()
        // The second waits until the first named the new conversation.
        assertEquals(2, h.sends().size)
        slow.complete(Unit)
        h.settle()
        val (one, two) = h.sends().drop(1).map { checkNotNull(it.body) }
        assertTrue(one.getBoolean("forceNewConversation"))
        assertTrue(one.isNull("conversationId"))
        assertFalse(two.getBoolean("forceNewConversation"))
        assertEquals("c5", two.getString("conversationId"))
        assertEquals("c5", h.thread.conversationId)
    }

    @Test
    fun `a new conversation asked for while the first message of a fresh thread is on its way stays new`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.start()
        val answer = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            answer.await()
            sent(conversationId = "c9", bearer = "wb_known", newConversation = true)
        }
        h.engine.send("First question")
        h.settle()
        h.engine.startNewConversation()
        h.settle()
        // The answer names the conversation the first message made: the visitor stays on the new, empty thread.
        answer.complete(Unit)
        h.settle()
        assertNull(h.thread.conversationId)
    }

    // ── A conversation epoch ──

    @Test
    fun `a stale restore saying c1 is open never moves the visitor out of the conversation their message started`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.start()
        val slow = CompletableDeferred<Unit>()
        h.http.once("GET /api/widget/conversations") {
            slow.await()
            ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0))
        }
        h.engine.refresh()
        h.settle()
        h.engine.setVisible(true)
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c2", bearer = "wb_known", newConversation = true) }
        h.engine.send("Hi")
        slow.complete(Unit)
        h.settle()
        assertEquals("c2", h.thread.conversationId)
        assertTrue(h.sends().single().body?.isNull("conversationId") == true)
    }

    @Test
    fun `the poll loop of a conversation the visitor left stops, and its late answer is dropped`() = runTest {
        val h = known(this)
        val slow = CompletableDeferred<Unit>()
        h.http.once("GET /api/widget/messages") {
            slow.await()
            ok(pollAnswer(row("old", content = "From c1")))
        }
        h.advance(5_000)
        h.engine.openConversation("c2")
        val switchedAt = h.polls().size
        slow.complete(Unit)
        h.settle()
        assertTrue(h.thread.messages.none { it.id == "old" })
        h.advance(30_000)
        val after = h.polls().drop(switchedAt)
        assertTrue(after.isNotEmpty())
        assertTrue("no poll of c1 after the switch", after.all { it.query["conversationId"] == "c2" })
    }

    // ── Idempotency ids ──

    @Test
    fun `the id of a failed message is forgotten on a conversation switch`() = runTest {
        val h = known(this)
        h.http.on("POST /api/widget/messages") { offline() }
        h.engine.send("ok")
        h.advance(130_000)
        val failed = h.thread.pending.single()
        h.engine.openConversation("c2")
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c2", bearer = "wb_known") }
        h.engine.send("ok")
        assertTrue(failed.uuid != h.sends().last().headers["Idempotency-Key"])
    }

    // ── Identity refusals ──

    @Test
    fun `only 400, 401 or 404 with the API's JSON refuse an identity`() = runTest {
        val stored = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", lastConversationId = "c1")
        for (answer in listOf(fail(405, json("error" to "Method not allowed")), fail(404, null), fail(451, json("error" to "x")))) {
            val h = Harness(this, record = stored)
            h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0)) }
            h.http.on(verifyKey) { answer }
            h.engine.identify(userA)
            h.start()
            assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
            assertEquals("wb_A", h.store.record.bearer)
        }
        val refused = Harness(this, record = stored)
        refused.http.on(verifyKey) { fail(401, json("error" to "invalid_identifier_hash")) }
        refused.engine.identify(userA)
        refused.start()
        assertEquals(VisitorIdentity.AnonymousNew, refused.state.identity)
        assertNull(refused.store.record.bearer)
    }

    // ── Identify and logout in call order ──

    @Test
    fun `identify then logout in the same turn never verifies and leaves nothing stored`() = runTest {
        val stored = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", lastConversationId = "c1")
        val h = Harness(this, record = stored)
        h.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_A")) }
        h.engine.identify(userA)
        h.engine.logout()
        h.start()
        assertTrue(h.http.calls(verifyKey).isEmpty())
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        assertEquals(VisitorRecord(), h.store.record)

        // The same after the start: the logout stops the queued verification.
        val later = Harness(this)
        later.start()
        later.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_A")) }
        later.engine.identify(userA)
        later.engine.logout()
        later.settle()
        assertTrue(later.http.calls(verifyKey).isEmpty())
        assertEquals(VisitorIdentity.AnonymousNew, later.state.identity)
        assertEquals(VisitorRecord(), later.store.record)
    }

    @Test
    fun `a message queued behind another goes out as the user a verification settles on, never stuck in sending`() = runTest {
        val h = known(this)
        val never = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            never.await()
            sent(bearer = "wb_known")
        }
        h.engine.send("A")
        h.engine.send("B")
        h.settle()
        assertEquals(1, h.sends().size)
        h.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_A")) }
        h.restoreAnswer = { ok(json("conversationId" to "c7", "status" to "OPEN", "unreadCount" to 0)) }
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c7", bearer = "wb_A") }
        h.engine.identify(userA)
        h.settle()
        val last = checkNotNull(h.sends().last().body)
        assertEquals("B", last.getString("content"))
        assertEquals("wb_A", last.getString("contactIdentifier"))
        assertEquals("c7", last.getString("conversationId"))
        assertTrue(h.thread.pending.none { it.state == PendingState.SENDING })
    }

    @Test
    fun `a logout before the start deletes the stored data before the start reads it`() = runTest {
        val stored = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", lastConversationId = "c1")
        val h = Harness(this, record = stored)
        h.engine.logout()
        h.start()
        assertEquals(VisitorRecord(), h.store.record)
        assertNull(h.cache.snapshot)
        assertEquals(Phase.READY, h.state.phase)
        assertTrue(h.restores().none { it.contact == "wb_A" })
    }

    // ── Monotonic clock and server times only ──

    @Test
    fun `the send budget runs on the monotonic clock when the device clock jumps back an hour`() = runTest {
        val h = known(this)
        h.http.on("POST /api/widget/messages") { offline() }
        h.engine.send("Hi")
        h.advance(60_000)
        h.clock.deviceSkewMillis = -3_600_000
        h.advance(70_000)
        assertEquals(PendingState.FAILED, h.thread.pending.single().state)
        val tries = h.sends().size
        h.advance(600_000)
        assertEquals(tries, h.sends().size)
    }

    @Test
    fun `the bot hold ends by row order when the device clock runs 2 minutes ahead`() = runTest {
        val h = Harness(this, baseConfig(BOT_ON), record = VisitorRecord(bearer = "wb_known"), deviceSkewMillis = 120_000)
        h.openThread()
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = true) }
        h.engine.send("Where is my order?")
        assertTrue(h.thread.botThinking)
        val uuid = h.sends().single().headers["Idempotency-Key"]
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("v1", type = "INCOMING", content = "Where is my order?", at = 1, attrs = json("clientAssignedUuid" to uuid))))
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("b1", content = "It ships today.", at = 3, attrs = json("bot_id" to "b1"))))
        assertFalse(h.thread.botThinking)
    }

    // ── Why the session failed ──

    @Test
    fun `a failed session says why`() = runTest {
        val unknown = Harness(this)
        unknown.http.on("GET /api/widget/config") { fail(404, json("error" to "Invalid widget token")) }
        unknown.start()
        assertEquals(Phase.FAILED, unknown.state.phase)
        assertEquals(SessionFailure.UNKNOWN_WIDGET, unknown.state.failure)

        // A cached config does not hide it: the widget was switched off, the inbox deleted.
        val cached = CacheSnapshot("anonymous", WidgetConfig.parse(baseConfig()), null, emptyList(), emptyList(), emptyList(), false, false)
        val gone = Harness(this, record = VisitorRecord(bearer = "wb_known"), cached = cached)
        gone.http.on("GET /api/widget/config") { fail(404, json("error" to "Invalid widget token")) }
        gone.start()
        assertEquals(Phase.FAILED, gone.state.phase)
        assertEquals(SessionFailure.UNKNOWN_WIDGET, gone.state.failure)

        val refused = Harness(this)
        refused.http.on("GET /api/widget/config") { fail(403, json("error" to "Widget disabled")) }
        refused.start()
        assertEquals(SessionFailure.SERVER, refused.state.failure)

        val offlineStart = Harness(this)
        offlineStart.http.on("GET /api/widget/config") { offline() }
        offlineStart.start()
        offlineStart.advance(13_000)
        assertEquals(SessionFailure.NO_CONNECTION, offlineStart.state.failure)

        // An answer that is not a config counts as none and is never used.
        val garbage = Harness(this)
        garbage.http.on("GET /api/widget/config") { ok(json("hello" to "portal")) }
        garbage.start()
        garbage.advance(13_000)
        assertEquals(SessionFailure.NO_CONNECTION, garbage.state.failure)
        assertNull(garbage.state.config)

        // A good answer clears it.
        garbage.http.on("GET /api/widget/config") { ok(baseConfig()) }
        garbage.engine.refresh()
        garbage.settle()
        assertEquals(Phase.READY, garbage.state.phase)
        assertNull(garbage.state.failure)
    }

    // ── The survey starts over ──

    @Test
    fun `the survey starts over when a continuous conversation is resolved a second time`() = runTest {
        val h = known(this, "continuousChat" to true)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), resolved = true, showCsatSurvey = true)) }
        h.advance(5_000)
        h.http.on("POST /api/widget/csat") { ok(json("id" to "s1", "rating" to 5)) }
        assertTrue(h.engine.submitCsat(5, null))
        assertEquals(CsatState.SUBMITTED, h.thread.csatState)

        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), row("a2", at = 2), resolved = false)) }
        h.advance(5_000)
        assertEquals(CsatState.IDLE, h.thread.csatState)

        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), row("a2", at = 2), row("a3", at = 3), resolved = true, showCsatSurvey = true)) }
        h.advance(5_000)
        assertTrue(h.thread.csatDue)
        assertTrue(h.engine.submitCsat(4, null))
        assertEquals(2, h.http.calls("POST /api/widget/csat").size)
    }

    // ── Refresh and the restore ──

    @Test
    fun `refresh restores while the thread is off screen, and tries a failed restore again`() = runTest {
        val h = known(this)
        h.engine.setVisible(false)
        h.restoreAnswer = { ok(json("conversationId" to "c3", "status" to "OPEN", "unreadCount" to 1)) }
        h.engine.refresh()
        h.settle()
        assertEquals("c3", h.thread.conversationId)

        val failing = Harness(this, record = VisitorRecord(bearer = "wb_known", lastConversationId = "c1"))
        failing.restoreAnswer = { offline() }
        failing.start()
        failing.engine.setVisible(true)
        assertEquals(1, failing.restores().size)
        failing.engine.refresh()
        failing.settle()
        assertEquals(2, failing.restores().size)
        failing.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0)) }
        failing.engine.refresh()
        failing.settle()
        assertEquals(3, failing.restores().size)
        // On screen, with a conversation and a restore that answered: no lookup.
        failing.engine.refresh()
        failing.settle()
        assertEquals(3, failing.restores().size)
    }

    // ── The first foreground value ──

    @Test
    fun `a session started in the background polls and streams nothing until the app is in front`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"), initialForeground = false)
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 2)) }
        h.start()
        h.advance(60_000)
        assertEquals(2, h.state.unreadCount)
        assertTrue(h.polls().isEmpty())
        assertNull(h.realtime.current)
        h.engine.setForeground(true)
        h.settle()
        assertEquals(1, h.polls().size)
        assertTrue(h.realtime.current != null)
    }

    // ── The bot hold ──

    @Test
    fun `the bot hold belongs to the message that raised it`() = runTest {
        val h = Harness(this, baseConfig(BOT_ON), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = false) }
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = true) }
        h.engine.send("A note for the team")
        h.engine.send("And a question for Fynn")
        h.settle()
        assertEquals(2, h.sends().size)
        assertTrue("the second message's hold stays", h.thread.botThinking)
    }

    @Test
    fun `a pushed bot placeholder polls at once`() = runTest {
        val h = Harness(this, baseConfig(BOT_ON), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        val before = h.polls().size
        h.realtime.emit("message:created", json("conversationId" to "c1", "message" to row("b1", content = "", at = 1, attrs = json("bot_id" to "b1", "isTyping" to true))))
        assertEquals(before + 1, h.polls().size)
        assertTrue(h.thread.botThinking)
    }

    // ── A stream that ended for good lets go ──

    @Test
    fun `a refused stream is let go and opened again only after the app comes back`() = runTest {
        val h = known(this)
        val first = checkNotNull(h.realtime.current)
        first.stop()
        h.settle()
        assertNull(h.realtime.current)
        h.advance(60_000)
        assertEquals(1, h.realtime.opened.size)
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        h.settle()
        assertEquals(2, h.realtime.opened.size)
    }

    // ── Empty poll answers ──

    @Test
    fun `an empty poll answer changes nothing but closed`() = runTest {
        val h = known(this)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"), queue = 3, hasAgentReply = true)) }
        h.advance(5_000)
        assertEquals(3, h.thread.queuePosition)
        assertTrue(h.thread.hasAgentReply)
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(queue = null, waiting = false, hasAgentReply = false)) }
        h.advance(5_000)
        assertEquals(3, h.thread.queuePosition)
        assertTrue(h.thread.hasAgentReply)
        assertEquals(listOf("a1"), h.thread.messages.map { it.id })
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(closed = true)) }
        h.advance(5_000)
        assertTrue(h.thread.closed)
    }

    // ── The bearer in a header ──

    @Test
    fun `every GET names the bearer in a header and never in its URL`() = runTest {
        val h = known(this, "continuousChat" to false)
        h.engine.setVisible(false)
        h.advance(31_000)
        h.http.on("GET /api/widget/realtime-token") { ok(json("keyName" to "k", "nonce" to "n")) }
        assertTrue(checkNotNull(h.realtime.current).auth() is com.deskwoot.sdk.api.ApiResult.Success)
        val gets = h.http.calls.filter { it.method == "GET" && it.path != "/api/widget/config" }
        // The restore, the list, the poll and the realtime token.
        assertEquals(
            setOf("/api/widget/conversations", "/api/widget/messages", "/api/widget/realtime-token"),
            gets.mapTo(HashSet()) { it.path },
        )
        assertTrue(gets.any { it.query["list"] == "1" })
        for (call in gets) {
            assertEquals("wb_known", call.contact)
            assertFalse(call.request.url.contains("wb_known"))
            assertNull(call.query["contactIdentifier"])
        }
    }

    // ── A dormant verified record stays as it is ──

    @Test
    fun `a new visitor's actions leave a dormant verified record alone`() = runTest {
        val dormant = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", lastConversationId = "c1")
        val h = Harness(this, record = dormant)
        h.start()
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        val slow = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            slow.await()
            fail(400, json("error" to "Pre-chat form fields required", "missingFields" to listOf("email")))
        }
        h.engine.send("Hi")
        h.settle()
        h.engine.startNewConversation()
        h.settle()
        slow.complete(Unit)
        h.settle()
        assertEquals(dormant, h.store.record)
    }

    // ── Uploads stop on logout ──

    @Test
    fun `a logout stops an upload on its way`() = runTest {
        val h = known(this)
        val never = CompletableDeferred<Unit>()
        h.http.on("POST /api/widget/attachments") {
            never.await()
            ok(json("messageId" to "f1", "conversationId" to "c1"))
        }
        val upload = async { h.engine.upload(photo, "a.png", "image/png") }
        h.settle()
        assertEquals(1, h.thread.uploads.size)
        h.engine.logout()
        assertEquals(UploadErrorCode.UPLOAD_FAILED, upload.await())
        assertTrue(h.thread.uploads.isEmpty())
    }

    @Test
    fun `stop ends an upload on its way too`() = runTest {
        val h = known(this)
        val never = CompletableDeferred<Unit>()
        h.http.on("POST /api/widget/attachments") {
            never.await()
            ok(json("messageId" to "f1", "conversationId" to "c1"))
        }
        val upload = async { h.engine.upload(photo, "a.png", "image/png") }
        h.settle()
        h.engine.stop()
        assertEquals(UploadErrorCode.UPLOAD_FAILED, upload.await())
    }

    // ── A surprise never leaves a bubble spinning ──

    @Test
    fun `an unexpected exception while sending fails the message`() = runTest {
        val h = known(this)
        h.http.once("POST /api/widget/messages") { throw IllegalStateException("a platform quirk") }
        h.engine.send("Hi")
        h.settle()
        val row = h.thread.pending.single()
        assertEquals(PendingState.FAILED, row.state)
        assertEquals(com.deskwoot.sdk.model.SendFailure.NOT_SENT, row.error)
    }

    // ── A file type the server takes ──

    @Test
    fun `a file without an allowed extension is refused before it is uploaded`() = runTest {
        val h = known(this, "allowedFileTypes" to listOf("*/*"))
        assertEquals(UploadErrorCode.FILE_TYPE_NOT_ALLOWED, h.engine.upload(photo, "notes", "application/x-custom"))
        assertEquals(UploadErrorCode.FILE_TYPE_NOT_ALLOWED, h.engine.upload(photo, "photo.heic", "image/heic"))
        assertTrue(h.http.calls("POST /api/widget/attachments").isEmpty())
        // Every type the server takes gets its extension from the MIME type.
        assertEquals("notes.docx", UploadRules.fileName("notes", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "file"))
        assertEquals("list.csv", UploadRules.fileName("list", "text/csv", "file"))
        assertEquals("song.mp3", UploadRules.fileName("song", "audio/mpeg", "file"))
        assertEquals("backup.zip", UploadRules.fileName("backup", "application/zip", "file"))
        assertEquals("IMG_1.jpeg", UploadRules.fileName("IMG_1.jpeg", "image/jpeg", "file"))
        assertEquals("data.json", UploadRules.fileName("data.json", "application/x-custom", "file"))
    }
}
