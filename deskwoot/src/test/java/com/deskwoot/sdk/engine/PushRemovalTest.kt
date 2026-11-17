package com.deskwoot.sdk.engine

import com.deskwoot.sdk.BASE_TIME
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.PushRemoval
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone never stays registered to a visitor the device forgot, and the
 * device keeps nothing of a visitor no registration was sent for: before the
 * first registration request for a bearer leaves, the visitor's record says
 * so; when a bearer with that flag leaves the device, it becomes a pending
 * removal in the store (with or without a push token); the removal goes out
 * whenever a token is known (at once, when the token arrives, at every start
 * and foreground, when a push names that forgotten visitor), for every inbox
 * of the app, and its record goes only on a 2xx or after 30 days;
 * registrations and removals run through one queue in call order.
 */
class PushRemovalTest {
    private val fcm = "f".repeat(20) + ":APA91b" + "x".repeat(140)
    private val devices = "POST /api/widget/push/devices"
    private val remove = "POST /api/widget/push/devices/remove"
    private val verify = "POST /api/widget/identity/verify"
    private val userA = DeskwootUser("u_a", "a".repeat(64))
    private val userB = DeskwootUser("u_b", "b".repeat(64))
    private val dormantA = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", pushRegistered = true)
    private val day = 24 * 60 * 60 * 1_000L

    private fun push(h: Harness): Harness {
        h.http.on(devices) { ok(json("ok" to true)) }
        h.http.on(remove) { ok(json("ok" to true)) }
        return h
    }

    private fun removed(h: Harness): List<String?> {
        h.settle()
        return h.http.calls(remove).map { it.body?.optString("contactIdentifier") }
    }

    private fun registered(h: Harness): List<String?> {
        h.settle()
        return h.http.calls(devices).map { it.body?.optString("contactIdentifier") }
    }

    /** On the disk, [bearer] went into the pending removals before [step] (the record that held it deleted or replaced). */
    private fun assertKeptBefore(h: Harness, bearer: String, step: String) {
        h.settle()
        val steps = h.store.steps.toList()
        val kept = steps.indexOfFirst { it.startsWith("removals") && bearer in it.removePrefix("removals ").split(",") }
        val wiped = steps.indexOf(step)
        assertTrue("$bearer kept before $step in $steps", kept >= 0 && wiped >= 0 && kept < wiped)
    }

    // ── Only a bearer a registration was sent for leaves a record ──

    @Test
    fun `a logout of a visitor no registration was sent for keeps nothing of them`() = runTest {
        // An app that never hands over a push token: nothing of the logged out visitor stays on the device.
        val h = push(Harness(this, record = VisitorRecord(bearer = "wb_known")))
        h.start()
        h.engine.logout()
        h.settle()

        assertNull(h.store.record.bearer)
        assertTrue("no removal record", h.store.pushRemovals.isEmpty())
        assertTrue(h.store.steps.none { it.startsWith("removals") })
        h.engine.setPushToken(fcm)
        assertTrue(removed(h).isEmpty())
    }

    @Test
    fun `the flag is on the disk before the first registration request leaves, once per bearer`() = runTest {
        val h = push(Harness(this, record = VisitorRecord(bearer = "wb_known")))
        val flagAtRequest = mutableListOf<Boolean>()
        h.http.on(devices) {
            flagAtRequest += h.store.record.pushRegistered
            ok(json("ok" to true))
        }
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(listOf("wb_known"), registered(h))
        assertEquals(listOf(true), flagAtRequest)
        assertEquals(VisitorRecord(bearer = "wb_known", pushRegistered = true), h.store.record)

        // A language change registers again; the flag is written once.
        h.engine.setLanguage("fr")
        assertEquals(2, registered(h).size)
        assertEquals(1, h.store.steps.count { it == "write wb_known" })
    }

    @Test
    fun `a registered visitor's logout without a token keeps the bearer, and its removal goes out once the token arrives`() = runTest {
        val h = push(Harness(this, record = VisitorRecord(bearer = "wb_known", pushRegistered = true)))
        h.start()
        h.engine.logout()
        h.settle()

        assertEquals(listOf("wb_known"), h.store.pushRemovals)
        assertNull(h.store.record.bearer)
        assertKeptBefore(h, "wb_known", "clear")
        assertTrue("no token, no call", removed(h).isEmpty())

        h.engine.setPushToken(fcm)
        assertEquals(listOf("wb_known"), removed(h))
        assertEquals(fcm, h.http.calls(remove).single().body?.getString("token"))
        assertEquals(TOKEN, h.http.calls(remove).single().body?.getString("widgetToken"))
        assertTrue("deleted after the 2xx", h.store.pushRemovals.isEmpty())
        assertTrue("the new visitor is nobody the server knows", registered(h).isEmpty())
    }

    @Test
    fun `a logout before setup, before the store was read, keeps the stored bearer only when its flag says so`() = runTest {
        val h = push(Harness(this, record = dormantA))
        // What setup does with a logout called before it: the token, the logout, then the start.
        h.engine.setPushToken(fcm)
        h.engine.logout()
        h.start()

        assertEquals(VisitorRecord(), h.store.record)
        assertKeptBefore(h, "wb_A", "clear")
        assertEquals(listOf("wb_A"), removed(h))
        assertTrue(h.store.pushRemovals.isEmpty())

        val unregistered = push(Harness(this, record = dormantA.copy(pushRegistered = false)))
        unregistered.engine.setPushToken(fcm)
        unregistered.engine.logout()
        unregistered.start()
        assertEquals(VisitorRecord(), unregistered.store.record)
        assertTrue(removed(unregistered).isEmpty())
        assertTrue(unregistered.store.steps.none { it.startsWith("removals") })
    }

    @Test
    fun `a logout while the identity is dormant and offline keeps it until a 2xx, tried at every foreground and start`() = runTest {
        val h = push(Harness(this, record = dormantA))
        h.http.on(remove) { offline() }
        h.engine.setPushToken(fcm)
        h.start()
        h.engine.logout()
        assertEquals(listOf("wb_A"), removed(h))
        assertEquals(listOf("wb_A"), h.store.pushRemovals)
        h.advance(120_000)
        assertEquals("not tried again in a loop", 1, removed(h).size)

        h.http.on(remove) { fail(503) }
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals(2, removed(h).size)
        // A 200 that is not the route's answer is no 2xx of the route.
        h.http.on(remove) { ok(json("something" to "else")) }
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals(3, removed(h).size)
        assertEquals(listOf("wb_A"), h.store.pushRemovals)

        h.http.on(remove) { ok(json("ok" to true)) }
        h.engine.start()
        assertEquals(4, removed(h).size)
        assertTrue(h.store.pushRemovals.isEmpty())
    }

    @Test
    fun `another user over a registered one removes the first before the second registers, and over an unregistered one keeps nothing`() = runTest {
        val h = push(Harness(this))
        h.http.on(verify) { call -> ok(json("contactIdentifier" to if (call.body?.getJSONObject("user")?.getString("identifier") == "u_a") "wb_A" else "wb_B")) }
        h.engine.setPushToken(fcm)
        h.engine.identify(userA)
        h.start()
        assertEquals(listOf("wb_A"), registered(h))

        h.engine.identify(userB)
        h.settle()
        assertKeptBefore(h, "wb_A", "clear")
        assertEquals(listOf("wb_A"), removed(h))
        assertEquals(listOf("wb_A", "wb_B"), registered(h))
        assertTrue(h.http.calls.indexOfFirst { it.key == remove } < h.http.calls.indexOfLast { it.key == devices })
        assertTrue(h.store.pushRemovals.isEmpty())

        val quiet = push(Harness(this))
        quiet.http.on(verify) { call -> ok(json("contactIdentifier" to if (call.body?.getJSONObject("user")?.getString("identifier") == "u_a") "wb_A" else "wb_B")) }
        quiet.engine.identify(userA)
        quiet.start()
        quiet.engine.identify(userB)
        quiet.settle()
        assertEquals("wb_B", quiet.store.record.bearer)
        assertTrue("no token ever: nothing of the first user stays", quiet.store.pushRemovals.isEmpty())
    }

    @Test
    fun `another user over a dormant one keeps the dormant bearer, also when named before the store was read`() = runTest {
        val after = push(Harness(this, record = dormantA))
        after.http.on(verify) { ok(json("contactIdentifier" to "wb_B")) }
        after.start()
        after.engine.identify(userB)
        after.settle()
        assertEquals(listOf("wb_A"), after.store.pushRemovals)
        assertKeptBefore(after, "wb_A", "clear")

        val before = push(Harness(this, record = dormantA))
        before.http.on(verify) { ok(json("contactIdentifier" to "wb_B")) }
        before.engine.identify(userB)
        before.start()
        assertEquals(VisitorIdentity.Verified("u_b"), before.state.identity)
        assertEquals(listOf("wb_A"), before.store.pushRemovals)
        assertKeptBefore(before, "wb_A", "clear")
    }

    @Test
    fun `an anonymous first message over a dormant identity keeps the dormant bearer`() = runTest {
        val h = push(Harness(this, record = dormantA))
        h.start()
        h.http.once("POST /api/widget/messages") { sent(conversationId = "c9", bearer = "wb_anon") }
        h.engine.send("Hi, no account")
        h.advance(1_000)

        assertEquals("wb_anon", h.store.record.bearer)
        assertFalse("the new bearer was never registered", h.store.record.pushRegistered)
        assertEquals(listOf("wb_A"), h.store.pushRemovals)
        assertKeptBefore(h, "wb_A", "write wb_anon")
    }

    @Test
    fun `an identity refusal that drops the stored identity keeps its bearer`() = runTest {
        val h = push(Harness(this, record = dormantA))
        h.http.on(verify) { fail(401, json("error" to "invalid_identifier_hash")) }
        h.engine.identify(userA)
        h.start()

        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        assertEquals(listOf("wb_A"), h.store.pushRemovals)
        assertKeptBefore(h, "wb_A", "clear")
    }

    @Test
    fun `the switch to a cookieless inbox keeps the stored bearer when it was registered`() = runTest {
        val h = push(Harness(this, baseConfig("cookielessEnabled" to true), record = VisitorRecord(bearer = "wb_old", pushRegistered = true)))
        h.start()

        assertEquals(VisitorRecord(), h.store.record)
        assertEquals(listOf("wb_old"), h.store.pushRemovals)
        assertKeptBefore(h, "wb_old", "clear")
        h.engine.setPushToken(fcm)
        assertEquals(listOf("wb_old"), removed(h))
        assertTrue(h.store.pushRemovals.isEmpty())
    }

    // ── The list survives, holds every inbox, is capped and runs in call order ──

    @Test
    fun `the pending removal survives a restart and goes out at the next start with a token`() = runTest {
        val first = push(Harness(this, record = VisitorRecord(bearer = "wb_known", pushRegistered = true)))
        first.start()
        first.engine.logout()
        first.settle()
        assertEquals(listOf("wb_known"), first.store.pushRemovals)

        // A new process: what the store kept, nothing else.
        val next = push(Harness(this, record = first.store.record, removals = first.store.removals))
        next.start()
        assertTrue("no token yet", removed(next).isEmpty())
        next.engine.setPushToken(fcm)
        assertEquals(listOf("wb_known"), removed(next))
        assertTrue(next.store.pushRemovals.isEmpty())

        // The token known before the start: the removal goes out with the start.
        val early = push(Harness(this, pushRemovals = listOf("wb_x")))
        early.engine.setPushToken(fcm)
        early.start()
        assertEquals(listOf("wb_x"), removed(early))
        assertTrue(early.store.pushRemovals.isEmpty())
    }

    @Test
    fun `the removals of every inbox of the app go out, each naming its own inbox`() = runTest {
        val other = PushRemoval("wt_other_inbox", "wb_other", BASE_TIME)
        val h = push(Harness(this, pushRemovals = listOf("wb_here"), removals = listOf(other)))
        h.start()
        h.engine.setPushToken(fcm)

        assertEquals(listOf("wb_here", "wb_other"), removed(h))
        assertEquals(listOf(TOKEN, "wt_other_inbox"), h.http.calls(remove).map { it.body?.getString("widgetToken") })
        assertTrue(h.store.removals.isEmpty())
    }

    @Test
    fun `a removal record older than 30 days is deleted unsent`() = runTest {
        val old = PushRemoval(TOKEN, "wb_old", BASE_TIME - 31 * day)
        val young = PushRemoval(TOKEN, "wb_young", BASE_TIME - 29 * day)
        val h = push(Harness(this, removals = listOf(old, young)))
        h.http.on(remove) { offline() }
        h.start()
        h.settle()
        assertEquals("deleted at the first read", listOf(young), h.store.removals)

        h.engine.setPushToken(fcm)
        assertEquals(listOf("wb_young"), removed(h))
    }

    @Test
    fun `at most 8 wait per inbox, the oldest is dropped first and the rest go out oldest first`() = runTest {
        val older = (1..8).map { "wb_old$it" }
        val h = push(Harness(this, record = VisitorRecord(bearer = "wb_known", pushRegistered = true), pushRemovals = older))
        h.start()
        h.engine.logout()
        h.settle()
        val kept = older.drop(1) + "wb_known"
        assertEquals(kept, h.store.pushRemovals)

        h.engine.setPushToken(fcm)
        assertEquals(kept, removed(h))
        assertTrue(h.store.pushRemovals.isEmpty())
    }

    @Test
    fun `a registration on its way when its visitor ends is followed by the removal, and the same user signing in again registers again`() = runTest {
        val h = push(Harness(this))
        val order = mutableListOf<String>()
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_A")) }
        h.http.on(devices) { call ->
            delay(1_000)
            order += "register ${call.body?.getString("contactIdentifier")}"
            ok(json("ok" to true))
        }
        h.http.on(remove) { call ->
            order += "remove ${call.body?.getString("contactIdentifier")}"
            ok(json("ok" to true))
        }
        h.engine.setPushToken(fcm)
        h.engine.identify(userA)
        h.start()
        // The registration is still on its way.
        h.engine.logout()
        h.advance(2_000)
        assertEquals(listOf("register wb_A", "remove wb_A"), order)
        assertTrue(h.store.pushRemovals.isEmpty())

        // The registration that answered after the logout was not kept as done: the same user registers again.
        h.engine.identify(userA)
        h.advance(2_000)
        assertEquals(listOf("register wb_A", "remove wb_A", "register wb_A"), order)
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        h.advance(5_000)
        assertEquals("registered now: nothing more", 3, order.size)
    }

    @Test
    fun `a user back before the removal went out stays registered and leaves the list`() = runTest {
        val h = push(Harness(this, record = dormantA))
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_A")) }
        h.engine.identify(userA)
        h.start()
        h.engine.logout()
        h.settle()
        assertEquals(listOf("wb_A"), h.store.pushRemovals)

        h.engine.identify(userA)
        h.settle()
        assertTrue("the device knows this visitor again", h.store.pushRemovals.isEmpty())
        h.engine.setPushToken(fcm)
        assertTrue(removed(h).isEmpty())
        assertEquals(listOf("wb_A"), registered(h))
    }

    // ── A push for a forgotten visitor ──

    @Test
    fun `a push naming a forgotten visitor sends their pending removal at once, from any thread`() = runTest {
        var worker = false
        val h = push(Harness(this, pushRemovals = listOf("wb_gone", "wb_other"), onMainThread = { !worker }))
        h.http.on(remove) { offline() }
        h.engine.setPushToken(fcm)
        h.start()
        assertEquals("the first try at the start", listOf("wb_gone", "wb_other"), removed(h))

        h.http.on(remove) { ok(json("ok" to true)) }
        h.engine.removeForgotten(VisitorKey.of("wb_nobody"))
        assertEquals("a key of nobody pending sends nothing", 2, removed(h).size)
        // FCM hands the push over on a worker thread.
        worker = true
        h.engine.removeForgotten(VisitorKey.of("wb_gone").uppercase())
        worker = false
        assertEquals(listOf("wb_gone", "wb_other", "wb_gone"), removed(h))
        assertEquals("only that visitor's record went", listOf("wb_other"), h.store.pushRemovals)
    }

    @Test
    fun `a push naming a forgotten visitor in a fresh process reads the list first, also a record of an earlier inbox`() = runTest {
        val h = push(Harness(this, removals = listOf(PushRemoval("wt_earlier_inbox", "wb_gone", BASE_TIME))))
        // A fresh process started for the push: the session has not started, nothing was read.
        h.engine.removeForgotten(VisitorKey.of("wb_gone"))
        h.settle()
        assertTrue("no token, nothing can go", removed(h).isEmpty())
        h.engine.setPushToken(fcm)
        h.engine.removeForgotten(VisitorKey.of("wb_gone"))
        assertEquals("once, though asked twice", listOf("wb_gone"), removed(h))
        assertEquals("wt_earlier_inbox", h.http.calls(remove).single().body?.getString("widgetToken"))
        assertTrue(h.store.removals.isEmpty())
    }

    // ── A cookieless inbox ──

    @Test
    fun `a cookieless inbox registers its visitor and takes the phone off at logout, keeping the flag and the removal in memory only`() = runTest {
        val h = push(Harness(this, baseConfig("cookielessEnabled" to true)))
        h.http.on(remove) { offline() }
        h.start()
        h.engine.setPushToken(fcm)
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_mem") }
        h.engine.send("Hello")
        assertEquals(listOf("wb_mem"), registered(h))
        assertNull(h.store.record.bearer)

        h.engine.logout()
        assertEquals(listOf("wb_mem"), removed(h))
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals("tried again while the process lives", listOf("wb_mem", "wb_mem"), removed(h))
        assertTrue("nothing written: no record, no flag, no removal", h.store.steps.none { it.startsWith("write") || it.startsWith("removals") })
    }
}
