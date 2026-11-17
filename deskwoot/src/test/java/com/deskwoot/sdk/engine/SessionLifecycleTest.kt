package com.deskwoot.sdk.engine

import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.CacheSnapshot
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cookieless inboxes, the heartbeat, refresh and the config at boot. */
class SessionLifecycleTest {
    private val cookieless = "cookielessEnabled" to true

    private fun cachedThread(config: WidgetConfig? = null): CacheSnapshot {
        val row = checkNotNull(Message.parse(row("m1", type = "INCOMING", content = "Earlier")))
        return CacheSnapshot("anonymous", config, "c1", listOf(row), emptyList(), emptyList(), false, false)
    }

    @Test
    fun `a cookieless inbox stores nothing, deletes what was stored and reads no list`() = runTest {
        val h = Harness(
            this,
            baseConfig(cookieless),
            record = VisitorRecord(bearer = "wb_old", lastConversationId = "c1"),
            cached = cachedThread(),
        )
        h.start()

        // The earlier visitor is gone: not used, not shown, deleted.
        assertEquals(VisitorIdentity.AnonymousNew, h.state.identity)
        assertTrue(h.restores().isEmpty())
        assertTrue(h.thread.messages.isEmpty())
        assertEquals(VisitorRecord(), h.store.record)
        assertNull(h.cache.snapshot)

        // The visitor lives in memory: the minted bearer works, nothing is written.
        h.http.once("POST /api/widget/messages") { sent(bearer = "wb_mem") }
        h.engine.send("Hi")
        h.advance(60_000)
        assertEquals(VisitorIdentity.AnonymousKnown, h.state.identity)
        assertEquals("wb_mem", h.polls().last().contact)
        assertEquals(VisitorRecord(), h.store.record)
        assertNull(h.cache.snapshot)
        assertTrue(h.http.calls("GET /api/widget/conversations").none { it.query["list"] == "1" })
    }

    @Test
    fun `a cached cookieless config paints no thread`() = runTest {
        val config = WidgetConfig.parse(baseConfig(cookieless))
        val anonymousConfig = WidgetConfig.parse(baseConfig())
        val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h = Harness(this, baseConfig(cookieless), record = VisitorRecord(bearer = "wb_old"), cached = cachedThread(config))
        h.http.on("GET /api/widget/config") {
            answer.await()
            ok(baseConfig(cookieless))
        }
        h.engine.start()
        // Before the network answered: the config shows, the thread does not.
        assertTrue(checkNotNull(h.state.config).cookielessEnabled)
        assertTrue(checkNotNull(h.state.thread).messages.isEmpty())
        answer.complete(Unit)
        h.settle()

        // The same cache without the flag paints the thread at once.
        val plain = Harness(this, record = VisitorRecord(bearer = "wb_old"), cached = cachedThread(anonymousConfig))
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        plain.http.on("GET /api/widget/config") {
            held.await()
            ok(baseConfig())
        }
        plain.engine.start()
        assertEquals(listOf("m1"), checkNotNull(plain.state.thread).messages.map { it.id })
        held.complete(Unit)
    }

    @Test
    fun `the heartbeat goes once after the first config, failures ignored`() = runTest {
        val h = Harness(this, baseConfig(cookieless), record = VisitorRecord(bearer = "wb_known"))
        h.http.on("POST /api/widget/heartbeat") { fail(500) }
        h.start()
        h.engine.refresh()
        h.engine.setVisible(true)
        h.advance(120_000)
        h.engine.refresh()
        h.settle()

        val beat = h.http.calls("POST /api/widget/heartbeat").single()
        val body = checkNotNull(beat.body)
        assertEquals(TOKEN, body.getString("widgetToken"))
        assertEquals("loaded", body.getString("status"))
        val app = body.getJSONObject("app")
        assertEquals("android", app.getString("platform"))
        assertEquals("com.example.shop", app.getString("id"))
        assertEquals("1.0.0", app.getString("sdk"))
        // It names no visitor.
        assertTrue(!body.has("contactIdentifier"))
        assertEquals(Phase.READY, h.state.phase)
    }

    @Test
    fun `refresh skips the restore while the thread is on screen, boot always restores`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        assertEquals(1, h.restores().size)

        h.engine.refresh()
        h.settle()
        assertEquals(1, h.restores().size)

        h.engine.setVisible(false)
        h.engine.refresh()
        h.settle()
        assertEquals(2, h.restores().size)
    }

    @Test
    fun `with a cached config the boot tries once and does not hold up the restore`() = runTest {
        val config = WidgetConfig.parse(baseConfig())
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"), cached = cachedThread(config))
        h.http.on("GET /api/widget/config") { offline() }
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN")) }
        h.start()

        assertEquals(1, h.http.calls("GET /api/widget/config").size)
        assertEquals(Phase.READY, h.state.phase)
        assertEquals(0L, h.restores().single().at)
    }

    @Test
    fun `without a cached config the boot tries after 0, 1, 3 and 8 s`() = runTest {
        val h = Harness(this)
        h.http.on("GET /api/widget/config") { offline() }
        h.engine.start()
        h.advance(20_000)

        assertEquals(listOf(0L, 1_000L, 4_000L, 12_000L), h.http.calls("GET /api/widget/config").map { it.at })
        assertEquals(Phase.FAILED, h.state.phase)
    }

    @Test
    fun `an unknown widget token fails at once`() = runTest {
        val h = Harness(this)
        h.http.on("GET /api/widget/config") { fail(404, json("error" to "Invalid widget token")) }
        h.start()
        assertEquals(1, h.http.calls("GET /api/widget/config").size)
        assertEquals(Phase.FAILED, h.state.phase)
        assertTrue(h.http.calls("POST /api/widget/heartbeat").isEmpty())
    }
}
