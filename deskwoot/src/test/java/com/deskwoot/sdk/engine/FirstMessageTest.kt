package com.deskwoot.sdk.engine

import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.FieldProblem
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.SendFailure
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.offline
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

/** The first message of a new visitor. */
class FirstMessageTest {
    private val lowerUuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `a new visitor's placeholder becomes the bearer the server minted`() = runTest {
        val h = Harness(this)
        h.start()
        h.engine.setVisible(true)
        assertEquals(Phase.READY, h.state.phase)
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        // A placeholder names nobody: no restore lookup, nothing stored.
        assertTrue(h.restores().isEmpty())
        assertNull(h.store.record.bearer)

        val placeholder = Harness.uuid(1)
        val messageId = Harness.uuid(2)
        h.http.once("POST /api/widget/messages") { sent(newConversation = true) }
        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(row("m1", type = "INCOMING", content = "Hallo", attrs = json("clientAssignedUuid" to messageId))))
        }
        h.engine.send("  Hallo  ")

        val body = checkNotNull(h.sends().single().body)
        assertTrue(lowerUuid.matches(placeholder))
        assertEquals(placeholder, body.getString("contactIdentifier"))
        assertEquals(messageId, h.sends().single().headers["Idempotency-Key"])
        assertEquals(messageId, body.getString("client_assigned_uuid"))
        assertEquals("Hallo", body.getString("content"))
        assertEquals(TOKEN, body.getString("widgetToken"))
        assertTrue(body.isNull("conversationId"))
        assertFalse(body.getBoolean("forceNewConversation"))
        assertEquals("de-DE", body.getString("widgetLocale"))

        assertEquals("wb_minted", h.store.record.bearer)
        assertEquals(VisitorIdentity.AnonymousKnown, h.state.identity)
        assertEquals("c1", h.thread.conversationId)
        assertEquals("widget-conversation:c1", h.realtime.current?.channel)
        // The stored row replaced the pending bubble.
        assertTrue(h.thread.pending.isEmpty())
        assertEquals(listOf("m1"), h.thread.messages.map { it.id })
    }

    @Test
    fun `the first message gets 120 s for its one try, later ones 30 s`() = runTest {
        val h = Harness(this)
        h.start()
        h.http.on("POST /api/widget/messages") { sent() }
        h.engine.send("One")
        h.engine.send("Two")
        assertEquals(listOf(120_000, 30_000), h.sends().map { it.request.timeoutMillis })
        assertEquals(15_000, h.http.calls("GET /api/widget/config").single().request.timeoutMillis)
    }

    @Test
    fun `the email step holds the first message until an address is given`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true))
        h.start()
        assertEquals(Gate.EmailCollect, h.thread.gate)

        h.engine.send("Hi")
        // A held message is not a pending send.
        assertEquals(listOf("Hi"), h.thread.heldMessages)
        assertTrue(h.thread.pending.isEmpty())
        assertTrue(h.sends().isEmpty())

        assertEquals(FieldProblem.INVALID_EMAIL, h.engine.submitEmail("nope"))
        assertEquals(FieldProblem.BLOCKED_EMAIL, h.engine.submitEmail("anna@test.com"))
        h.http.once("POST /api/widget/messages") { sent(emailKnown = true) }
        assertNull(h.engine.submitEmail(" anna@acme.io "))

        assertEquals("anna@acme.io", h.sends().single().body?.getString("email"))
        assertEquals("Hi", h.sends().single().body?.getString("content"))
        assertEquals(PendingState.SENT, h.thread.pending.single().state)
        assertTrue(h.thread.heldMessages.isEmpty())
        assertEquals(Gate.None, h.thread.gate)
        assertTrue(h.thread.emailKnown)

        // The answers ride on the first message only.
        h.http.once("POST /api/widget/messages") { sent() }
        h.engine.send("Second")
        assertFalse(checkNotNull(h.sends().last().body).has("email"))
    }

    @Test
    fun `leaving the step drops the held text`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true))
        h.start()
        h.engine.send("Hi")
        h.engine.cancelGate()
        assertTrue(h.thread.heldMessages.isEmpty())
        assertEquals(Gate.EmailCollect, h.thread.gate)
        h.http.once("POST /api/widget/messages") { sent() }
        assertNull(h.engine.submitEmail("anna@acme.io"))
        assertTrue(h.sends().isEmpty())
    }

    @Test
    fun `the pre chat form validates, trims and splits its answers by field name`() = runTest {
        val fields = listOf(
            mapOf("name" to "name", "type" to "text", "required" to true),
            mapOf("name" to "email", "type" to "email", "required" to true),
            mapOf("name" to "company", "type" to "text", "required" to false),
        )
        val h = Harness(this, baseConfig("preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to fields), "emailCollectEnabled" to true))
        h.start()
        val gate = h.thread.gate as Gate.PreChatForm
        assertEquals(listOf("name", "email", "company"), gate.fields.map { it.name })

        val problems = h.engine.submitPreChat(mapOf("name" to " ", "email" to "x@"))
        assertEquals(mapOf("name" to FieldProblem.REQUIRED, "email" to FieldProblem.INVALID_EMAIL), problems)
        assertTrue(h.thread.gate is Gate.PreChatForm)

        assertTrue(h.engine.submitPreChat(mapOf("name" to " Anna ", "email" to "anna@acme.io", "company" to " Acme ", "secret" to "x")).isEmpty())
        assertEquals(Gate.None, h.thread.gate)
        h.http.once("POST /api/widget/messages") { sent() }
        h.engine.send("Hello")

        val body = checkNotNull(h.sends().single().body)
        assertEquals("Anna", body.getString("name"))
        assertEquals("anna@acme.io", body.getString("email"))
        assertEquals("Acme", body.getJSONObject("preChatFormValues").getString("company"))
        assertEquals(1, body.getJSONObject("preChatFormValues").length())
    }

    @Test
    fun `when the bot collects the fields nothing is asked first`() = runTest {
        val fields = listOf(mapOf("name" to "email", "type" to "email", "required" to true))
        val h = Harness(
            this,
            baseConfig(
                BOT_ON,
                "letBotCollectPreChatFields" to true,
                "preChatFormEnabled" to true,
                "preChatFormFields" to mapOf("fields" to fields),
                "emailCollectEnabled" to true,
            ),
        )
        h.start()
        assertEquals(Gate.None, h.thread.gate)

        h.http.once("POST /api/widget/messages") { sent(botWillReply = true) }
        h.engine.send("Where is my order?")

        val body = checkNotNull(h.sends().single().body)
        assertFalse(body.has("email"))
        // The typing dots stay: the server said the bot answers.
        assertTrue(h.thread.botThinking)
        assertFalse(h.thread.canSend)
    }

    @Test
    fun `email_required holds the message and switches to the email step`() = runTest {
        val h = Harness(this)
        h.start()
        assertEquals(Gate.None, h.thread.gate)
        h.http.once("POST /api/widget/messages") { fail(400, json("error" to "Email is required to start a conversation", "code" to "email_required")) }
        h.engine.send("Hi there")

        assertEquals(listOf("Hi there"), h.thread.heldMessages)
        assertTrue(h.thread.pending.isEmpty())
        assertEquals(Gate.EmailCollect, h.thread.gate)

        h.http.once("POST /api/widget/messages") { sent() }
        assertNull(h.engine.submitEmail("anna@acme.io"))
        val again = checkNotNull(h.sends().last().body)
        assertEquals("Hi there", again.getString("content"))
        assertEquals("anna@acme.io", again.getString("email"))
        // The placeholder of the refused first try is kept: one visitor, one contact.
        assertEquals(Harness.uuid(1), again.getString("contactIdentifier"))
    }

    @Test
    fun `a 400 without a code is rejected and its English text is never read`() = runTest {
        val h = Harness(this)
        h.start()
        h.http.once("POST /api/widget/messages") { fail(400, json("error" to "Email is required to start a conversation")) }
        h.engine.send("Hi")

        assertEquals(SendFailure.REJECTED, h.thread.pending.single().error)
        assertTrue(h.thread.heldMessages.isEmpty())
        assertEquals(Gate.None, h.thread.gate)
    }

    @Test
    fun `a refused first message of a contact that is gone starts over with a new placeholder`() = runTest {
        val fields = listOf(mapOf("name" to "email", "type" to "email", "required" to true))
        val h = Harness(
            this,
            baseConfig("preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to fields)),
            record = VisitorRecord(bearer = "wb_gone"),
        )
        h.openThread(bearer = "wb_gone")
        // A known contact with a running thread is not asked again.
        assertEquals(Gate.None, h.thread.gate)

        h.http.once("POST /api/widget/messages") {
            fail(400, json("error" to "Pre-chat form fields required", "missingFields" to listOf("email")))
        }
        h.engine.send("Still there?")
        assertEquals(listOf("Still there?"), h.thread.heldMessages)
        assertTrue(h.thread.pending.isEmpty())
        assertTrue(h.thread.gate is Gate.PreChatForm)
        assertEquals(listOf("email"), h.thread.missingFields)
        assertNull(h.thread.conversationId)
        assertNull(h.store.record.bearer)
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)

        h.http.once("POST /api/widget/messages") { sent(conversationId = "c2", bearer = "wb_new") }
        assertTrue(h.engine.submitPreChat(mapOf("email" to "anna@acme.io")).isEmpty())

        val again = h.sends().last()
        assertEquals(Harness.uuid(2), again.body?.getString("contactIdentifier"))
        assertEquals("anna@acme.io", again.body?.getString("email"))
        assertEquals("Still there?", again.body?.getString("content"))
        assertEquals("wb_new", h.store.record.bearer)
        assertEquals("c2", h.thread.conversationId)
    }

    // ── A stale config: the server wants a form the cached config does not have ──

    private val formFields = listOf(
        mapOf("name" to "email", "type" to "email", "required" to true),
        mapOf("name" to "name", "type" to "text", "required" to false),
    )

    private val preChatRequired = fail(400, json("error" to "Pre-chat form fields required", "code" to "pre_chat_required", "missingFields" to listOf("email")))

    private fun freshConfigCalls(h: Harness) = h.http.calls("GET /api/widget/config").filter { it.query.containsKey("fresh") }

    @Test
    fun `pre_chat_required with a config without a form asks the config again past the cache, then the form`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true), record = VisitorRecord(bearer = "wb_gone"))
        h.openThread(bearer = "wb_gone")
        assertEquals(Gate.None, h.thread.gate)
        // The owner switched the form on after the config in hand was served.
        h.http.on("GET /api/widget/config") {
            ok(baseConfig("emailCollectEnabled" to true, "preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to formFields)))
        }
        h.http.once("POST /api/widget/messages") { preChatRequired }
        h.engine.send("Hi")

        val form = h.thread.gate as Gate.PreChatForm
        assertEquals(listOf("email", "name"), form.fields.map { it.name })
        assertEquals(listOf("Hi"), h.thread.heldMessages)
        assertTrue(h.thread.pending.isEmpty())
        assertEquals(listOf("email"), h.thread.missingFields)
        val refetch = freshConfigCalls(h).single()
        assertEquals(TOKEN, refetch.query["widgetToken"])
        assertTrue(refetch.query["fresh"].orEmpty().isNotBlank())
        assertTrue(checkNotNull(h.state.config).preChatFormEnabled)

        // The form's answers ride on the held message, as for a form the config had from the start.
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c2", bearer = "wb_new") }
        assertTrue(h.engine.submitPreChat(mapOf("email" to "anna@acme.io", "name" to "Anna")).isEmpty())
        val again = h.sends().last()
        assertEquals("Hi", again.body?.getString("content"))
        assertEquals("anna@acme.io", again.body?.getString("email"))
        assertEquals("Anna", again.body?.getString("name"))
    }

    @Test
    fun `when the fresh config has no form either, the step is the one it was before`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true), record = VisitorRecord(bearer = "wb_gone"))
        h.openThread(bearer = "wb_gone")
        h.http.once("POST /api/widget/messages") { preChatRequired }
        h.engine.send("Hi")
        h.settle()

        assertEquals(1, freshConfigCalls(h).size)
        assertEquals(Gate.EmailCollect, h.thread.gate)
        assertEquals(listOf("Hi"), h.thread.heldMessages)
    }

    @Test
    fun `without an answer to the fresh config the config in hand decides`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true), record = VisitorRecord(bearer = "wb_gone"))
        h.openThread(bearer = "wb_gone")
        h.http.once("GET /api/widget/config") { offline() }
        h.http.once("POST /api/widget/messages") { preChatRequired }
        h.engine.send("Hi")
        h.settle()

        assertEquals(1, freshConfigCalls(h).size)
        assertEquals(Gate.EmailCollect, h.thread.gate)
        assertEquals(listOf("Hi"), h.thread.heldMessages)
    }

    @Test
    fun `a config that has the form asks it at once, without asking the config again`() = runTest {
        val h = Harness(
            this,
            baseConfig("preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to formFields)),
            record = VisitorRecord(bearer = "wb_gone"),
        )
        h.openThread(bearer = "wb_gone")
        h.http.once("POST /api/widget/messages") { preChatRequired }
        h.engine.send("Hi")

        assertTrue(h.thread.gate is Gate.PreChatForm)
        assertTrue(freshConfigCalls(h).isEmpty())
    }
}
