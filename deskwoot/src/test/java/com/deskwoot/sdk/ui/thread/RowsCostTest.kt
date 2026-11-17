package com.deskwoot.sdk.ui.thread

import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.R
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.ConversationStatus
import com.deskwoot.sdk.model.CsatState
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.SendFailure
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.row
import com.deskwoot.sdk.ui.FileTexts
import com.deskwoot.sdk.ui.people.People
import com.deskwoot.sdk.ui.testFormats
import com.deskwoot.sdk.ui.text.UiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a session driven rebuild of the thread costs, and the words for a send that failed. */
class RowsCostTest {
    private val config: WidgetConfig = checkNotNull(WidgetConfig.parse(baseConfig(BOT_ON).toString()))

    /** 200 messages: the visitor, the bot with markdown and links, a team member, every few minutes. */
    private fun thread(count: Int): ThreadState {
        val messages = (0 until count).map { i ->
            val at = i * 90L
            checkNotNull(
                Message.parse(
                    when (i % 3) {
                        0 -> row("v$i", type = "INCOMING", content = "Question number $i about my **order**?", at = at, attrs = json("clientAssignedUuid" to "u$i"))
                        1 -> row("b$i", content = "Here is what I found:\n\n- Step one\n- Step [two](https://deskwoot.com/help/$i)\n\n> A quote", at = at, attrs = json("bot_id" to "bot1"), sender = json("name" to "Fynn"))
                        else -> row("a$i", content = "<p>Hello from the <b>team</b>, see <a href=\"https://deskwoot.com\">our site</a></p>", at = at, sender = json("name" to "Mia"))
                    },
                ),
            )
        }
        return ThreadState(
            conversationId = "c1", status = ConversationStatus.OPEN, messages = messages, pending = emptyList(), uploads = emptyList(),
            segments = emptyList(), botThinking = false, botTool = null, agentTyping = false, queuePosition = null, waitingForTeam = false,
            resolved = false, closed = false, continuesAfterResolve = false, endedLocally = false, showCsat = false, csatState = CsatState.IDLE,
            emailKnown = false, heldMessages = emptyList(), missingFields = emptyList(), transcriptState = TranscriptState.IDLE, ending = false,
            hasAgentReply = true, gate = Gate.None, inEndState = false, canSend = true, canAttach = true, canEnd = true, csatDue = false,
            transcriptOffered = false, fynnEndMessageId = null, choicesMessageId = null, botTypingRowId = null,
        )
    }

    @Test
    fun `a session driven rebuild of a 200 message thread stays cheap`() {
        val texts = FileTexts()
        val formats = testFormats(texts)
        val people = People(config, "Support")
        val state = thread(200)
        val now = System.currentTimeMillis()
        // The first build fills the bubble text cache, as the first paint does.
        val first = ThreadRows(config, people, texts, formats).build(state, now)
        // The 200 messages and the greeting.
        assertEquals(201, first.count { it is IncomingRow || it is VisitorRow })
        // Every later state change (a poll, typing, realtime) builds the rows again; the screen makes a new ThreadRows each time.
        repeat(10) { ThreadRows(config, people, texts, formats).build(state.copy(agentTyping = it % 2 == 0), now) }
        val times = (0 until 31).map {
            val start = System.nanoTime()
            ThreadRows(config, people, texts, formats).build(state.copy(agentTyping = it % 2 == 0), now)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val median = times[times.size / 2]
        println(String.format(java.util.Locale.ROOT, "ThreadRows.build of 200 messages, median %.2f ms (min %.2f, max %.2f)", median, times.first(), times.last()))
        // On a laptop this is about a millisecond; a phone is some times slower. The bound only catches a regression to uncached work.
        assertTrue("median $median ms", median < 25.0)
    }

    @Test
    fun `a failed send says why in every language, and offers Try again only where it can work`() {
        for (language in UiLanguage.entries) {
            val texts = FileTexts(language)
            val words = SendFailure.entries.associateWith { SendFailures.text(it, texts) }
            // Each reason has its own words, except no answer and an unknown refusal: "Not sent", as on iOS.
            assertEquals(texts.t(R.string.dw_sdk_not_sent), words[SendFailure.NOT_SENT])
            assertEquals(texts.t(R.string.dw_sdk_not_sent), words[SendFailure.REJECTED])
            assertEquals(texts.t(R.string.dw_sdk_not_sent), SendFailures.text(null, texts))
            assertEquals(texts.t(R.string.dw_sdk_too_long), words[SendFailure.MESSAGE_TOO_LONG])
            assertEquals("$language", 6, words.values.toSet().size)
            words.values.forEach { assertTrue("$language", it.isNotBlank()) }
        }
        assertEquals("Not sent", SendFailures.text(SendFailure.NOT_SENT, FileTexts()))
        assertEquals("Nicht gesendet", SendFailures.text(SendFailure.REJECTED, FileTexts(UiLanguage.DE)))
        assertEquals("Too many messages in a short time. Trying again works in a moment.", SendFailures.text(SendFailure.RATE_LIMITED, FileTexts()))
        assertEquals("Zu viele Nachrichten in kurzer Zeit. In einem Moment klappt es wieder.", SendFailures.text(SendFailure.RATE_LIMITED, FileTexts(UiLanguage.DE)))
        assertTrue(SendFailures.canRetry(null))
        assertTrue(SendFailures.canRetry(SendFailure.NOT_SENT))
        assertTrue(SendFailures.canRetry(SendFailure.RATE_LIMITED))
        assertTrue(SendFailures.canRetry(SendFailure.REJECTED))
        for (final in listOf(SendFailure.MESSAGE_TOO_LONG, SendFailure.NEW_CONVERSATION_LIMIT, SendFailure.MONTHLY_CONVERSATION_LIMIT, SendFailure.CONVERSATION_RESOLVED)) {
            assertFalse("$final", SendFailures.canRetry(final))
        }
    }
}
