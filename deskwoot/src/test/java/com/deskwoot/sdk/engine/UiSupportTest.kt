package com.deskwoot.sdk.engine

import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.UploadPhase
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the core gives the messenger UI beyond the protocol: offline, the gate query, the typing row's id, upload progress. */
class UiSupportTest {
    @Test
    fun `a call without any answer marks the session offline until the next answer`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        assertFalse(h.state.offline)

        h.http.on("GET /api/widget/messages") { offline() }
        h.advance(5_000)
        assertTrue(h.state.offline)
        // The thread stays readable; the poll keeps trying at its normal pace.
        assertEquals("c1", h.thread.conversationId)

        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("a1"))) }
        h.advance(5_000)
        assertFalse(h.state.offline)
    }

    @Test
    fun `a refusal is an answer, not offline`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.on("GET /api/widget/messages") { fail(500) }
        h.advance(5_000)
        assertFalse(h.state.offline)
    }

    @Test
    fun `a refusal clears offline, as on iOS`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.on("GET /api/widget/messages") { offline() }
        h.advance(5_000)
        assertTrue(h.state.offline)

        h.http.on("GET /api/widget/messages") { fail(404, json("error" to "Conversation not found")) }
        h.advance(5_000)
        assertFalse(h.state.offline)
    }

    @Test
    fun `an answer that is not the route's says nothing about the connection`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        // A captive portal's 2xx without the poll's keys: neither raised...
        h.http.on("GET /api/widget/messages") { ok(json("portal" to true)) }
        h.advance(5_000)
        assertFalse(h.state.offline)

        h.http.on("GET /api/widget/messages") { offline() }
        h.advance(5_000)
        assertTrue(h.state.offline)
        // ...nor cleared.
        h.http.on("GET /api/widget/messages") { ok(json("portal" to true)) }
        h.advance(5_000)
        assertTrue(h.state.offline)
    }

    @Test
    fun `every call reports, a send's first try without connection raises it and a typing answer clears it`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.on("POST /api/widget/messages") { offline() }
        h.engine.send("Hello?")
        h.settle()
        // At the first try, not after the 2 minutes of retries.
        assertTrue(h.state.offline)

        h.engine.setTyping(true)
        h.settle()
        assertEquals(1, h.http.calls("POST /api/widget/typing").size)
        assertFalse(h.state.offline)
    }

    @Test
    fun `the gate query reflects a new conversation at once`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true))
        h.start()
        assertEquals(Gate.EmailCollect, h.engine.gateBeforeChat())

        assertNull(h.engine.submitEmail("anna@acme.io"))
        assertEquals(Gate.None, h.engine.gateBeforeChat())
    }

    @Test
    fun `a known visitor's new conversation asks for nothing when the server knows the email`() = runTest {
        val h = Harness(this, baseConfig("emailCollectEnabled" to true), record = VisitorRecord(bearer = "wb_known"))
        h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to 0, "emailKnown" to true)) }
        h.start()
        h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("v1", type = "INCOMING", content = "Hi"))) }
        h.advance(5_000)
        h.engine.startNewConversation()
        // Read right after the call, before the state is published.
        assertEquals(Gate.None, h.engine.gateBeforeChat())
    }

    @Test
    fun `the typing indicator is keyed by the bot's placeholder row, which is then filled in place`() = runTest {
        val h = Harness(this, baseConfig(BOT_ON), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        val bot = json("bot_id" to "bot1")
        val typing = json("bot_id" to "bot1", "isTyping" to true)
        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(row("v1", type = "INCOMING", content = "Do you ship?", at = 0), row("b1", content = "", at = 1, attrs = typing)))
        }
        h.advance(5_000)
        assertTrue(h.thread.botThinking)
        assertEquals("b1", h.thread.botTypingRowId)
        assertTrue(h.thread.messages.none { it.id == "b1" })

        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(row("v1", type = "INCOMING", content = "Do you ship?", at = 0), row("b1", content = "Yes, we do.", at = 1, attrs = bot)))
        }
        h.advance(1_000)
        assertFalse(h.thread.botThinking)
        assertNull(h.thread.botTypingRowId)
        assertEquals("Yes, we do.", h.thread.messages.single { it.id == "b1" }.content)
    }

    @Test
    fun `an upload reports how far it got`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.once("POST /api/widget/attachments") { call ->
            val progress = checkNotNull(call.request.onBodyProgress)
            progress(10, 100)
            progress(11, 100) // under the 2 % step: no new state
            progress(60, 100)
            delay(1_000)
            ok(json("messageId" to "f1", "conversationId" to "c1", "reopened" to false, "newConversation" to false))
        }
        val result = backgroundScope.async { h.engine.upload(UploadSource.Bytes(ByteArray(100)), "a.png", "image/png") }
        h.advance(500)
        val uploading = h.thread.uploads.single()
        assertEquals(UploadPhase.UPLOADING, uploading.phase)
        assertEquals(0.6f, uploading.progress, 0.001f)

        h.advance(1_000)
        assertNull(result.await())
        assertEquals(UploadPhase.SENT, h.thread.uploads.single().phase)
    }

    @Test
    fun `the launcher side reads left or right`() {
        assertEquals("left", checkNotNull(WidgetConfig.parse(baseConfig("widgetPosition" to "LEFT").toString())).widgetPosition)
        assertEquals("right", checkNotNull(WidgetConfig.parse(baseConfig("widgetPosition" to "RIGHT").toString())).widgetPosition)
        assertEquals("right", checkNotNull(WidgetConfig.parse(baseConfig().toString())).widgetPosition)
    }

    @Test
    fun `the launcher title is the owner's text, null when the owner kept the default`() {
        fun title(vararg overrides: Pair<String, Any?>) = checkNotNull(WidgetConfig.parse(baseConfig(*overrides).toString())).launcherTitle
        assertEquals("Need help?", title("launcherTitle" to "  Need help? "))
        assertNull(title("launcherTitle" to ""))
        assertNull(title("launcherTitle" to "   "))
        assertNull(title("launcherTitle" to null))
        assertNull(title("launcherTitle" to 42))
        assertNull(title())
    }

    @Test
    fun `a question asked from a help article carries it as the help center context`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.http.once("POST /api/widget/messages") { com.deskwoot.sdk.sent(bearer = "wb_known") }
        val article = com.deskwoot.sdk.model.HelpArticleRef("https://help.example.com/articles/delivery", "How long does delivery take?")
        h.engine.send("Is that also true for Austria?", article = article)
        // The bubble shows the card at once.
        assertEquals(article, h.thread.pending.single().helpArticle)
        h.settle()
        val context = checkNotNull(h.sends().last().body).getJSONObject("context")
        assertEquals("help_center", context.getString("hc_source"))
        assertEquals(article.url, context.getString("hc_article_url"))
        assertEquals(article.title, context.getString("hc_article_title"))
    }

    @Test
    fun `a screen that waits for the pending state runs once it is published`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        var ran = 0
        h.engine.afterPendingState { ran++ }
        assertEquals("nothing pending: at once", 1, ran)
        h.engine.startNewConversation()
        assertTrue(h.engine.statePending)
        h.engine.afterPendingState { ran++ }
        assertEquals(1, ran)
        h.settle()
        assertEquals(2, ran)
    }

    @Test
    fun `a new conversation asked for before the restore answered stays new`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known", lastConversationId = "c1"))
        h.engine.startNewConversation()
        h.openThread()
        assertNull("the restore does not bring the old conversation back", h.thread.conversationId)
        assertTrue(h.thread.messages.isEmpty())
    }

    @Test
    fun `every wipe of the visitor counts up, and the visitor is loaded once its conversations are known`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        assertEquals(0, h.state.visitorGeneration)
        assertTrue("the list loaded after the start", h.state.visitorLoaded)
        h.engine.logout()
        h.settle()
        assertEquals(1, h.state.visitorGeneration)
        // A new visitor has nothing to load.
        assertTrue(h.state.visitorLoaded)
    }

    @Test
    fun `a refresh runs no restore while the visitor asked for a new conversation`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        h.engine.setVisible(false)
        h.engine.startNewConversation()
        h.settle()
        val before = h.restores().size
        h.engine.refresh()
        h.settle()
        assertEquals("no restore while a new conversation is asked for", before, h.restores().size)
        assertNull(h.thread.conversationId)
    }
}
