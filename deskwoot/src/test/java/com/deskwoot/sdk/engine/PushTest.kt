package com.deskwoot.sdk.engine

import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.RecordedCall
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Push registration: register
 * whenever a token and a bearer the server knows exist, again after a bearer
 * change and a token change, remove before a logout wipes the bearer, a
 * cookieless inbox registers too, failures wait for the next start or
 * foreground and are never shown.
 */
class PushTest {
    private val fcm = "f".repeat(20) + ":APA91b" + "x".repeat(140)
    private val otherFcm = "g".repeat(20) + ":APA91b" + "y".repeat(140)
    private val devices = "POST /api/widget/push/devices"
    private val remove = "POST /api/widget/push/devices/remove"
    private val verify = "POST /api/widget/identity/verify"
    private val userA = DeskwootUser("u_a", "a".repeat(64))
    private val userB = DeskwootUser("u_b", "b".repeat(64))

    private fun harness(h: Harness): Harness {
        h.http.on(devices) { ok(json("ok" to true)) }
        h.http.on(remove) { ok(json("ok" to true)) }
        return h
    }

    private fun returning(scope: TestScope): Harness = harness(Harness(scope, record = VisitorRecord(bearer = "wb_known")))

    private fun registrations(h: Harness): List<RecordedCall> {
        h.settle()
        return h.http.calls(devices)
    }

    private fun removals(h: Harness): List<RecordedCall> {
        h.settle()
        return h.http.calls(remove)
    }

    private fun JSONObject?.text(key: String): String? = if (this != null && has(key)) getString(key) else null

    @Test
    fun `a returning visitor registers once with every field the route reads`() = runTest {
        val h = returning(this)
        h.start()
        assertTrue("no token yet", registrations(h).isEmpty())

        h.engine.setPushToken("  $fcm ")
        val body = checkNotNull(registrations(h).single().body)
        assertEquals(setOf("widgetToken", "contactIdentifier", "platform", "token", "appId", "locale"), body.keys().asSequence().toSet())
        assertEquals(TOKEN, body.getString("widgetToken"))
        assertEquals("wb_known", body.getString("contactIdentifier"))
        assertEquals("android", body.getString("platform"))
        assertEquals(fcm, body.getString("token"))
        assertEquals("com.example.shop", body.getString("appId"))
        assertEquals("de-DE", body.getString("locale"))

        // The same token and bearer register once per process.
        h.engine.setPushToken(fcm)
        h.engine.refresh()
        h.advance(120_000)
        assertEquals(1, registrations(h).size)
    }

    @Test
    fun `a token given before the start registers once the session knows the visitor`() = runTest {
        val h = returning(this)
        h.engine.setPushToken(fcm)
        h.settle()
        assertTrue(registrations(h).isEmpty())

        h.start()
        assertEquals("wb_known", registrations(h).single().body.text("contactIdentifier"))
    }

    @Test
    fun `a new visitor registers only once the first message minted the bearer`() = runTest {
        val h = harness(Harness(this))
        h.start()
        h.engine.setPushToken(fcm)
        assertTrue("a placeholder is no visitor the server knows", registrations(h).isEmpty())

        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_minted") }
        h.engine.send("Hello")
        assertEquals("wb_minted", registrations(h).single().body.text("contactIdentifier"))
    }

    @Test
    fun `a first message answered after the app went to the background registers the phone at once`() = runTest {
        val h = harness(Harness(this))
        h.start()
        h.engine.setPushToken(fcm)
        val answer = CompletableDeferred<Unit>()
        h.http.once("POST /api/widget/messages") {
            answer.await()
            sent(bearer = "wb_minted", newConversation = true)
        }
        h.engine.send("Where is my order?")
        h.settle()
        // The visitor leaves the app before the answer that brings their bearer: the team's reply must still ring.
        h.engine.setForeground(false)
        h.settle()
        answer.complete(Unit)
        assertEquals("wb_minted", registrations(h).single().body.text("contactIdentifier"))
    }

    @Test
    fun `a token change registers again`() = runTest {
        val h = returning(this)
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(1, registrations(h).size)

        h.engine.setPushToken(otherFcm)
        assertEquals(2, registrations(h).size)
        assertEquals(otherFcm, registrations(h).last().body.text("token"))
        assertTrue("the old token is not removed: the server drops it once FCM reports it gone", removals(h).isEmpty())
    }

    @Test
    fun `a bearer change by identity verification registers again`() = runTest {
        val h = returning(this)
        h.http.on(verify) { ok(json("contactIdentifier" to "wb_verified")) }
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(1, registrations(h).size)

        h.engine.identify(userA)
        assertEquals(2, registrations(h).size)
        assertEquals("wb_verified", registrations(h).last().body.text("contactIdentifier"))
        assertEquals(fcm, registrations(h).last().body.text("token"))
        // The anonymous bearer leaves the device: it is taken off before the verified one registers.
        assertEquals("wb_known", removals(h).single().body.text("contactIdentifier"))
        val removedAt = h.http.calls.indexOfFirst { it.key == remove }
        assertTrue(removedAt < h.http.calls.indexOfLast { it.key == devices })
        assertTrue("deleted after the 2xx", h.store.pushRemovals.isEmpty())
    }

    @Test
    fun `a language change registers again`() = runTest {
        val h = returning(this)
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(1, registrations(h).size)

        h.engine.setLanguage("fr")
        assertEquals(2, registrations(h).size)
        assertEquals("fr", registrations(h).last().body.text("locale"))
    }

    @Test
    fun `logout removes the phone with the old bearer before the wipe`() = runTest {
        val h = returning(this)
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(1, registrations(h).size)

        var storedAtRemoval: String? = null
        h.http.on(remove) {
            storedAtRemoval = h.store.record.bearer
            ok(json("ok" to true))
        }
        h.engine.logout()
        val body = checkNotNull(removals(h).single().body)
        assertEquals(setOf("widgetToken", "contactIdentifier", "token"), body.keys().asSequence().toSet())
        assertEquals(TOKEN, body.getString("widgetToken"))
        assertEquals("wb_known", body.getString("contactIdentifier"))
        assertEquals(fcm, body.getString("token"))
        assertEquals("the call went out before the local wipe", "wb_known", storedAtRemoval)

        h.advance(60_000)
        assertNull("the bearer is gone", h.store.record.bearer)
        assertEquals("the new visitor is no one the server knows yet", 1, registrations(h).size)
    }

    @Test
    fun `a switch of user removes the first user's phone, then registers the second`() = runTest {
        val h = harness(Harness(this, record = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a")))
        h.http.on(verify) { call -> ok(json("contactIdentifier" to if (call.body?.getJSONObject("user")?.getString("identifier") == "u_a") "wb_A" else "wb_B")) }
        h.engine.identify(userA)
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals("wb_A", registrations(h).single().body.text("contactIdentifier"))

        h.engine.identify(userB)
        assertEquals("wb_A", removals(h).single().body.text("contactIdentifier"))
        assertEquals("wb_B", registrations(h).last().body.text("contactIdentifier"))
        val removedAt = h.http.calls.indexOfFirst { it.key == remove }
        val registeredBAt = h.http.calls.indexOfLast { it.key == devices }
        assertTrue("removed before the second user registers", removedAt < registeredBAt)
    }

    @Test
    fun `logout with a dormant verified identity removes its phone too, when an earlier launch registered it`() = runTest {
        val h = harness(Harness(this, record = VisitorRecord(bearer = "wb_A", verifiedExternalId = "u_a", pushRegistered = true)))
        h.engine.setPushToken(fcm)
        h.start()
        assertTrue("a dormant identity is not used", registrations(h).isEmpty())

        h.engine.logout()
        assertEquals("wb_A", removals(h).single().body.text("contactIdentifier"))
    }

    @Test
    fun `a cookieless inbox registers its bearer in memory and stores nothing`() = runTest {
        val h = harness(Harness(this, baseConfig("cookielessEnabled" to true)))
        h.start()
        h.engine.setPushToken(fcm)
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_minted") }
        h.engine.send("Hello")
        assertEquals("wb_minted", registrations(h).single().body.text("contactIdentifier"))
        assertNull(h.store.record.bearer)
    }

    @Test
    fun `a failure waits for the next foreground and is never shown`() = runTest {
        val h = returning(this)
        h.http.on(devices) { fail(500) }
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(1, registrations(h).size)
        h.advance(120_000)
        assertEquals("not tried again in a loop", 1, registrations(h).size)
        assertNull(h.state.failure)

        h.http.on(devices) { offline() }
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals(2, registrations(h).size)

        h.http.on(devices) { ok(json("ok" to true)) }
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals(3, registrations(h).size)
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals("registered: no more calls", 3, registrations(h).size)
    }

    @Test
    fun `a failure is tried again on the next start too`() = runTest {
        val h = returning(this)
        h.http.once(devices) { offline() }
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(1, registrations(h).size)
        h.start()
        assertEquals(2, registrations(h).size)
    }

    @Test
    fun `a 429 is tried again on a foreground after its Retry-After, not before`() = runTest {
        val h = returning(this)
        h.http.once(devices) { fail(429, json("code" to "rate_limited"), retryAfter = "60") }
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(1, registrations(h).size)

        h.advance(30_000)
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals(1, registrations(h).size)

        h.advance(31_000)
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals(2, registrations(h).size)
    }

    @Test
    fun `a refused token is not sent again, a new token is`() = runTest {
        val h = returning(this)
        h.http.once(devices) { fail(400, json("error" to "Invalid token", "code" to "invalid_device", "field" to "token")) }
        h.start()
        h.engine.setPushToken("not-a-token")
        assertEquals(1, registrations(h).size)
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals(1, registrations(h).size)

        h.engine.setPushToken(fcm)
        assertEquals(2, registrations(h).size)
    }

    @Test
    fun `a null or blank token takes the phone off the visitor, and the same token registers again`() = runTest {
        val h = returning(this)
        h.start()
        h.engine.setPushToken(fcm)
        assertEquals(1, registrations(h).size)

        h.engine.setPushToken("  ")
        assertEquals(fcm, removals(h).single().body.text("token"))
        assertEquals("wb_known", removals(h).single().body.text("contactIdentifier"))
        h.engine.setPushToken(null)
        assertEquals("already forgotten", 1, removals(h).size)

        h.engine.setPushToken(fcm)
        assertEquals(2, registrations(h).size)
    }

    @Test
    fun `a registration still on its way is not undone by the logout that follows`() = runTest {
        val h = returning(this)
        val order = mutableListOf<String>()
        h.http.on(devices) { call ->
            delay(1_000)
            order += "register ${call.body.text("contactIdentifier")}"
            ok(json("ok" to true))
        }
        h.http.on(remove) { call ->
            order += "remove ${call.body.text("contactIdentifier")}"
            ok(json("ok" to true))
        }
        h.start()
        h.engine.setPushToken(fcm)
        h.settle()
        h.engine.logout()
        h.advance(2_000)
        assertEquals(listOf("register wb_known", "remove wb_known"), order)
    }

    @Test
    fun `the push calls report to the offline flag`() = runTest {
        val h = returning(this)
        h.http.on(devices) { offline() }
        h.start()
        assertFalse(h.state.offline)
        h.engine.setPushToken(fcm)
        h.settle()
        assertTrue(h.state.offline)
    }

    @Test
    fun `on screen only while that thread shows with the app in front`() = runTest {
        val h = returning(this)
        h.openThread()
        assertTrue(h.engine.isOnScreen("c1"))
        assertFalse("another conversation gets its notification", h.engine.isOnScreen("c2"))
        h.engine.setVisible(false)
        assertFalse(h.engine.isOnScreen("c1"))
        h.engine.setVisible(true)
        h.engine.setForeground(false)
        assertFalse(h.engine.isOnScreen("c1"))
    }
}
