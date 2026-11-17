package com.deskwoot.sdk.engine

import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.CacheSnapshot
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the end of a visitor does on the disk: it runs to its end on the
 * store queue also when the session is stopped right after, the next
 * session reads the store only after it, a stored visitor the session never
 * showed goes as completely as the one on screen, and a cookieless inbox
 * writes nothing that outlives the process.
 */
class VisitorEndOnDiskTest {
    private val userB = DeskwootUser("u_b", "b".repeat(64))
    private val verify = "POST /api/widget/identity/verify"
    private val registeredA = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", pushRegistered = true)

    private fun cacheOf(identity: String) = CacheSnapshot(identity, null, "c_a", emptyList(), emptyList(), emptyList(), resolved = false, continuesAfterResolve = false)

    @Test
    fun `a stop right after a logout, and a second logout, still delete the stored visitor and keep its removal`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known", pushRegistered = true), cached = cacheOf("anonymous"))
        h.start()
        // The app signs its user out and replaces the session in the same main thread turn.
        h.engine.logout()
        h.engine.logout()
        h.engine.stop()
        h.settle()

        assertEquals(VisitorRecord(), h.store.record)
        assertNull(h.cache.snapshot)
        assertEquals(listOf("wb_known"), h.store.pushRemovals)
    }

    @Test
    fun `the next session reads the store only after the end of the visitor before it finished`() = runTest {
        val first = Harness(this, record = registeredA)
        first.engine.identify(DeskwootUser("u_a", "a".repeat(64)))
        first.http.on(verify) { ok(json("contactIdentifier" to "wb_A")) }
        first.start()
        val slowDisk = CompletableDeferred<Unit>()
        first.store.clearGate = slowDisk
        first.engine.logout()
        first.engine.stop()

        // The next session of the process (another inbox) starts at once, on the same queue.
        val next = Harness(this, record = VisitorRecord(bearer = "wb_next"), sharedQueue = first.storeQueue)
        next.start()
        assertTrue("nothing read or asked before the deletion finished", next.http.calls.isEmpty())

        slowDisk.complete(Unit)
        next.settle()
        assertEquals(VisitorRecord(), first.store.record)
        assertEquals(listOf("wb_A"), first.store.pushRemovals)
        assertTrue("then it starts", next.http.calls("GET /api/widget/config").isNotEmpty())
        assertEquals(VisitorIdentity.AnonymousKnown, next.state.identity)
    }

    @Test
    fun `a stored visitor never shown ends completely on the disk, the visitor on screen goes on`() = runTest {
        // An anonymous first message over a dormant identity.
        val h = Harness(this, record = registeredA, cached = cacheOf("verified:u_a"))
        h.start()
        val hooks = mutableListOf<Boolean>()
        h.engine.visitorWiped = { hooks += it }
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c9", bearer = "wb_anon") }
        h.engine.send("Hi, no account")
        h.settle()
        assertEquals(listOf(false), hooks)
        assertEquals(VisitorRecord(bearer = "wb_anon", lastConversationId = "c9"), h.store.record)
        assertTrue("the dormant user's thread cache goes", h.cache.snapshot?.identity != "verified:u_a")
        assertEquals(listOf("wb_A"), h.store.pushRemovals)
        assertEquals("the visitor on screen goes on", "c9", h.thread.conversationId)

        // Another user named before the store was read.
        val early = Harness(this, record = registeredA, cached = cacheOf("verified:u_a"))
        early.http.on(verify) { ok(json("contactIdentifier" to "wb_B")) }
        val earlyHooks = mutableListOf<Boolean>()
        early.engine.visitorWiped = { earlyHooks += it }
        early.engine.identify(userB)
        early.start()
        assertEquals(listOf(false), earlyHooks)
        assertEquals(VisitorRecord(bearer = "wb_B", verifiedExternalId = "u_b"), early.store.record)
        assertTrue(early.cache.snapshot?.identity != "verified:u_a")
        assertEquals(listOf("wb_A"), early.store.pushRemovals)

        // A switch to a cookieless inbox.
        val switched = Harness(this, baseConfig("cookielessEnabled" to true), record = registeredA.copy(pushRegistered = false), cached = cacheOf("verified:u_a"))
        val switchedHooks = mutableListOf<Boolean>()
        switched.engine.visitorWiped = { switchedHooks += it }
        switched.start()
        assertEquals(listOf(false), switchedHooks)
        assertEquals(VisitorRecord(), switched.store.record)
        assertNull(switched.cache.snapshot)
        assertTrue("never registered: no removal record", switched.store.pushRemovals.isEmpty())
    }

    @Test
    fun `a cookieless inbox writes no record, no thread cache and no removal, from the first message to the logout`() = runTest {
        val h = Harness(this, baseConfig("cookielessEnabled" to true))
        h.http.on("POST /api/widget/push/devices") { ok(json("ok" to true)) }
        h.http.on("POST /api/widget/push/devices/remove") { ok(json("ok" to true)) }
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1", type = "INCOMING", content = "Hi", at = 5, conversationId = "c1"))) }
        h.start()
        h.engine.setPushToken("f".repeat(20) + ":APA91b" + "x".repeat(140))
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c1", bearer = "wb_mem") }
        h.engine.send("Hello")
        h.engine.setVisible(true)
        h.advance(10_000)
        assertEquals("c1", h.thread.conversationId)
        assertTrue(h.http.calls("POST /api/widget/push/devices").isNotEmpty())

        h.engine.logout()
        h.advance(10_000)
        assertTrue("only deletions: ${h.store.steps}", h.store.steps.all { it == "clear" })
        assertEquals(0, h.cache.writes)
        assertTrue(h.store.removals.isEmpty())
    }
}
