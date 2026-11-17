package com.deskwoot.sdk.engine

import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.model.SendRefusal
import com.deskwoot.sdk.model.SessionFailure
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.CacheSnapshot
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verified identity, user switches and logout. */
class IdentityTest {
    private val userA = DeskwootUser("u_a", "a".repeat(64), email = "anna@acme.io", name = "Anna", attributes = mapOf("plan" to "pro", "seats" to 3))
    private val userB = DeskwootUser("u_b", "b".repeat(64))
    private fun openAt(id: String) = ok(json("conversationId" to id, "status" to "OPEN", "unreadCount" to 0))
    private val verifyKey = "POST /api/widget/identity/verify"

    /** A verified identity of user A stored by an earlier run: bearer, markers and a cached thread. */
    private val storedA = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", lastConversationId = "c1")

    private fun cachedA(): CacheSnapshot {
        val row = checkNotNull(Message.parse(row("m1", type = "INCOMING", content = "Anna's earlier question")))
        return CacheSnapshot("verified:u_a", null, "c1", listOf(row), emptyList(), emptyList(), false, false)
    }

    private fun dormant(scope: TestScope): Harness = Harness(scope, record = storedA, cached = cachedA())

    // ── Case 1: a stored verified identity is dormant until identify or logout ──

    @Test
    fun `a stored verified identity is dormant until the app names its user`() = runTest {
        val h = dormant(this)
        h.start()

        assertEquals(Phase.READY, h.state.phase)
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        assertTrue(h.thread.messages.isEmpty())
        assertTrue(h.restores().isEmpty())
        assertTrue(h.http.calls(verifyKey).isEmpty())
        // Neither used nor shown nor deleted.
        assertEquals(storedA, h.store.record)
        assertNotNull(h.cache.snapshot)
        h.advance(60_000)
        assertEquals(storedA, h.store.record)
        assertEquals("verified:u_a", h.cache.snapshot?.identity)
    }

    // ── Case 2: identify with the same user ──

    @Test
    fun `the same user takes the stored identity back at once and verifies it in the background`() = runTest {
        val h = dormant(this)
        h.start()
        val answer = CompletableDeferred<Unit>()
        h.http.on(verifyKey) {
            answer.await()
            ok(json("contactIdentifier" to "wb_A"))
        }
        h.restoreAnswer = { openAt("c1") }
        h.engine.identify(userA)
        h.settle()

        // Before the verification answered: the stored bearer, the cached thread, the restore.
        assertEquals(1, h.http.calls(verifyKey).size)
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
        assertEquals(listOf("m1"), h.thread.messages.map { it.id })
        assertEquals("wb_A", h.restores().single().contact)
        assertEquals(Phase.READY, h.state.phase)
        assertEquals(Gate.None, h.thread.gate)

        answer.complete(Unit)
        h.settle()
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
        assertEquals("wb_A", h.store.record.bearer)
        h.advance(120_000)
        assertEquals(1, h.http.calls(verifyKey).size)
    }

    @Test
    fun `identify before the start uses the stored identity too`() = runTest {
        val h = dormant(this)
        h.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_A")) }
        h.restoreAnswer = { openAt("c1") }
        h.engine.identify(userA)
        h.start()
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
        assertEquals("c1", h.thread.conversationId)
        assertEquals(1, h.http.calls(verifyKey).size)
    }

    @Test
    fun `a refusal of the stored identity resets to a new visitor and deletes the stored data`() = runTest {
        val h = dormant(this)
        h.restoreAnswer = { openAt("c1") }
        h.http.on(verifyKey) { fail(401, json("error" to "invalid_identifier_hash")) }
        h.engine.identify(userA)
        h.start()

        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        assertEquals(VisitorRecord(), h.store.record)
        assertNull(h.cache.snapshot)
        assertNull(h.thread.conversationId)
        assertTrue(h.thread.messages.isEmpty())
        assertTrue(h.realtime.opened.all { it.closed })
    }

    @Test
    fun `no answer keeps the stored identity working and tries again later`() = runTest {
        val h = dormant(this)
        h.restoreAnswer = { openAt("c1") }
        h.http.once(verifyKey) { offline() }
        h.http.once(verifyKey) { fail(503) }
        h.http.once(verifyKey) { fail(429, retryAfter = "60") }
        h.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_A")) }
        h.engine.identify(userA)
        h.start()

        assertEquals(Phase.READY, h.state.phase)
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
        assertEquals(storedA, h.store.record)
        // A send goes out as the stored identity meanwhile.
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_A") }
        h.engine.send("Still me")
        assertEquals("wb_A", h.sends().single().body?.getString("contactIdentifier"))

        // Again after 15 s, then 30 s, then 60 s.
        h.advance(15_001)
        assertEquals(2, h.http.calls(verifyKey).size)
        h.advance(30_001)
        assertEquals(3, h.http.calls(verifyKey).size)
        h.advance(60_001)
        assertEquals(4, h.http.calls(verifyKey).size)
        h.advance(300_000)
        assertEquals(4, h.http.calls(verifyKey).size)
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
    }

    // ── Case 3: identify with another user, or logout ──

    @Test
    fun `another user deletes the stored identity before the verification`() = runTest {
        val h = dormant(this)
        h.start()
        val answer = CompletableDeferred<Unit>()
        h.http.on(verifyKey) {
            answer.await()
            ok(json("contactIdentifier" to "wb_B"))
        }
        h.restoreAnswer = { openAt("c7") }
        h.engine.identify(userB)
        h.settle()
        assertEquals(VisitorRecord(), h.store.record)
        assertNull(h.cache.snapshot)
        assertTrue(h.restores().isEmpty())

        answer.complete(Unit)
        h.settle()
        assertEquals(VisitorIdentity.Verified("u_b"), h.state.identity)
        assertEquals("c7", h.thread.conversationId)
        assertEquals(VisitorRecord(bearer = "wb_B", verifiedExternalId = "u_b", lastConversationId = "c7"), h.store.record)
    }

    @Test
    fun `logout deletes a dormant identity`() = runTest {
        val h = dormant(this)
        h.start()
        h.engine.logout()
        h.settle()
        assertEquals(VisitorRecord(), h.store.record)
        assertNull(h.cache.snapshot)
    }

    @Test
    fun `switching from one verified user to another shows nothing of the first while the second verifies`() = runTest {
        val h = Harness(this)
        h.http.once(verifyKey) { ok(json("contactIdentifier" to "wb_A")) }
        h.restoreAnswer = { openAt("c1") }
        h.engine.identify(userA)
        h.start()
        assertEquals("c1", h.thread.conversationId)

        val gate = CompletableDeferred<Unit>()
        h.http.once(verifyKey) {
            gate.await()
            ok(json("contactIdentifier" to "wb_B"))
        }
        h.restoreAnswer = { openAt("c7") }
        h.engine.identify(userB)
        h.settle()
        assertNull(h.thread.conversationId)
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        assertNull(h.store.record.bearer)
        assertNull(h.realtime.current)

        gate.complete(Unit)
        h.settle()
        assertEquals(VisitorIdentity.Verified("u_b"), h.state.identity)
        assertEquals("c7", h.thread.conversationId)
    }

    // ── Case 4: identify with no stored identity for that user ──

    @Test
    fun `without a stored identity the session waits for the verification before restore or send`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_anon"))
        val answer = CompletableDeferred<Unit>()
        h.http.on(verifyKey) {
            answer.await()
            ok(json("contactIdentifier" to "wb_B"))
        }
        h.restoreAnswer = { openAt("c7") }
        h.http.on("POST /api/widget/messages") { sent(conversationId = "c7", bearer = "wb_B") }
        h.engine.identify(userB)
        h.start()
        assertEquals(Phase.LOADING, h.state.phase)
        launch { h.engine.send("Hello") }
        h.settle()
        assertTrue(h.restores().isEmpty())
        assertTrue(h.sends().isEmpty())

        answer.complete(Unit)
        h.settle()
        assertEquals("wb_B", h.restores().single().contact)
        assertEquals("wb_B", h.sends().single().body?.getString("contactIdentifier"))
        assertEquals(VisitorIdentity.Verified("u_b"), h.state.identity)
    }

    @Test
    fun `an identify mid session also holds sends until the verification answered`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_anon"))
        h.openThread(bearer = "wb_anon")
        val answer = CompletableDeferred<Unit>()
        h.http.on(verifyKey) {
            answer.await()
            fail(401, json("error" to "invalid_identifier_hash"))
        }
        h.http.on("POST /api/widget/messages") { sent(bearer = "wb_anon") }
        h.engine.identify(userB)
        h.settle()
        assertEquals(Phase.LOADING, h.state.phase)
        launch { h.engine.send("Hello") }
        h.settle()
        assertTrue(h.sends().isEmpty())

        // Refused: the visitor continues anonymous, as the web does.
        answer.complete(Unit)
        h.settle()
        assertEquals(Phase.READY, h.state.phase)
        assertEquals(VisitorIdentity.AnonymousKnown, h.state.identity)
        assertEquals("wb_anon", h.sends().single().body?.getString("contactIdentifier"))
    }

    @Test
    fun `a refused verification without a stored identity stays anonymous`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_anon"))
        h.http.on(verifyKey) { fail(401, json("error" to "invalid_identifier_hash")) }
        h.restoreAnswer = { openAt("c1") }
        h.engine.identify(userA)
        h.start()

        assertEquals(Phase.READY, h.state.phase)
        assertEquals(VisitorIdentity.AnonymousKnown, h.state.identity)
        assertEquals("wb_anon", h.restores().single().contact)
        assertEquals(VisitorRecord(bearer = "wb_anon", lastConversationId = "c1"), h.store.record)
    }

    @Test
    fun `no usable answer without a stored identity fails and never goes anonymous`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_anon"))
        h.http.on(verifyKey) { offline() }
        h.engine.identify(userA)
        h.start()
        assertEquals(Phase.FAILED, h.state.phase)
        assertEquals(SessionFailure.IDENTITY_CHECK, h.state.failure)
        assertTrue(h.restores().isEmpty())

        // A send is refused with a reason and never goes out as the anonymous visitor.
        assertEquals(SendOutcome.Refused(SendRefusal.NOT_READY), h.engine.send("Hi"))
        assertTrue(h.sends().isEmpty())
        assertTrue(h.thread.pending.isEmpty())

        h.http.on(verifyKey) { fail(429, retryAfter = "60") }
        h.engine.refresh()
        h.settle()
        assertEquals(Phase.FAILED, h.state.phase)
        h.http.on(verifyKey) { fail(502) }
        h.engine.refresh()
        h.settle()
        assertEquals(Phase.FAILED, h.state.phase)

        h.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_A")) }
        h.engine.refresh()
        h.settle()
        assertEquals(Phase.READY, h.state.phase)
        assertNull(h.state.failure)
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
        assertTrue(h.restores().all { it.contact == "wb_A" })
    }

    // ── Case 5: an anonymous send while a dormant identity is stored ──

    @Test
    fun `an anonymous first message deletes a dormant identity when its bearer is stored`() = runTest {
        val h = dormant(this)
        h.start()
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c9", bearer = "wb_anon") }
        h.engine.send("Hi, no account")
        h.advance(1_000)

        assertEquals(VisitorRecord(bearer = "wb_anon", lastConversationId = "c9"), h.store.record)
        assertEquals("anonymous", h.cache.snapshot?.identity)
        assertEquals(VisitorIdentity.AnonymousKnown, h.state.identity)
    }

    // ── Payloads, logout, language ──

    @Test
    fun `the same payload is verified once per process`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true))
        h.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_A", "name" to "Anna", "email" to "anna@acme.io")) }
        h.restoreAnswer = { openAt("c1") }
        h.engine.identify(userA)
        h.start()

        val verify = checkNotNull(h.http.calls(verifyKey).single().body)
        assertEquals(TOKEN, verify.getString("widgetToken"))
        val user = verify.getJSONObject("user")
        assertEquals("u_a", user.getString("identifier"))
        assertEquals("a".repeat(64), user.getString("identifierHash"))
        assertEquals(3, user.getJSONObject("attributes").getInt("seats"))
        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
        // A verified visitor skips the steps before the chat.
        assertEquals(Gate.None, h.thread.gate)

        h.engine.identify(userA)
        h.settle()
        assertEquals(1, h.http.calls(verifyKey).size)
    }

    @Test
    fun `an anonymous visitor who signs in leaves the anonymous thread behind`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_anon"))
        h.openThread(bearer = "wb_anon")
        h.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_A")) }
        h.restoreAnswer = { ok(json("conversationId" to null)) }
        h.engine.identify(userA)
        h.settle()

        assertEquals(VisitorIdentity.Verified("u_a"), h.state.identity)
        assertNull(h.thread.conversationId)
        assertEquals("wb_A", h.store.record.bearer)
    }

    @Test
    fun `logout tells the UI the visitor is gone, so it drops drafts, pictures and files`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        var wiped = 0
        h.engine.visitorWiped = { wiped++ }
        h.engine.logout()
        h.settle()
        assertEquals(1, wiped)
    }

    @Test
    fun `logout wipes everything, stops every job and keeps the app's language`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"), language = "pt-BR")
        h.openThread()
        assertEquals("pt-BR", h.http.calls("GET /api/widget/config").last().query["locale"])
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1", content = "Hi", sender = json("name" to "Mia")))) }
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known") }
        h.engine.send("Hello")
        h.advance(6_000)
        assertTrue(h.cache.snapshot != null)

        h.engine.logout()
        h.settle()
        assertEquals(VisitorRecord(), h.store.record)
        assertNull(h.cache.snapshot)
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        assertNull(h.thread.conversationId)
        assertTrue(h.thread.messages.isEmpty())
        assertTrue(h.thread.pending.isEmpty())
        assertEquals(0, h.state.unreadCount)
        assertTrue(h.state.conversations.isEmpty())
        assertTrue(h.realtime.opened.all { it.closed })
        // The language is the app's setting: kept in memory, never stored.
        assertEquals("pt-BR", h.engine.language)

        val calls = h.http.calls.size
        h.advance(120_000)
        assertEquals(calls, h.http.calls.size)
        assertFalse(h.cache.snapshot != null)
    }
}
