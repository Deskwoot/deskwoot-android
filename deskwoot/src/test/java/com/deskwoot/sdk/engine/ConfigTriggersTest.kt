package com.deskwoot.sdk.engine

import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.RecordedCall
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The look and the language reach the UI when the config is loaded: at the
 * start, at every present (a refresh), at every return to the foreground, on
 * a push in front (a refresh) and on a language change.
 */
class ConfigTriggersTest {
    private fun configs(h: Harness): List<RecordedCall> {
        h.settle()
        return h.http.calls("GET /api/widget/config")
    }

    @Test
    fun `the config loads at the start, at a refresh, at a foreground and on a language change`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.start()
        assertEquals("the start", 1, configs(h).size)

        h.advance(5_000)
        h.engine.refresh()
        assertEquals("a present or a push in front", 2, configs(h).size)

        h.advance(5_000)
        h.engine.setForeground(false)
        h.engine.setForeground(true)
        assertEquals("back in front", 3, configs(h).size)

        h.engine.setLanguage("fr")
        assertEquals("a language change", 4, configs(h).size)
        assertEquals("fr", configs(h).last().query["locale"])
    }

    @Test
    fun `a refresh asked for as one begins is answered by it, one asked for later runs after it`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.start()
        h.http.on("GET /api/widget/config") {
            delay(3_000)
            ok(baseConfig())
        }
        // A present: the facade's refresh, and the messenger's own as it opens a moment later.
        h.engine.refresh()
        h.advance(200)
        h.engine.refresh()
        h.advance(5_000)
        assertEquals(2, configs(h).size)

        // A push in front while a refresh runs for a while already: one more follows it.
        h.engine.refresh()
        h.advance(1_500)
        h.engine.refresh()
        h.advance(10_000)
        assertEquals(4, configs(h).size)
    }

    @Test
    fun `a language change while the start still runs asks for the config again in the new language`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"), appLanguage = "de-DE")
        val first = CompletableDeferred<Unit>()
        h.http.once("GET /api/widget/config") {
            first.await()
            ok(baseConfig())
        }
        h.engine.start()
        h.settle()
        assertEquals("de-DE", configs(h).single().query["locale"])

        h.engine.setLanguage("fr")
        first.complete(Unit)
        h.settle()
        assertEquals(listOf("de-DE", "fr"), configs(h).map { it.query["locale"] })
    }
}
