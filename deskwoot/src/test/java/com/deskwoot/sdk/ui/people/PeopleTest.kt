package com.deskwoot.sdk.ui.people

import com.deskwoot.sdk.BASE_TIME
import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.iso
import com.deskwoot.sdk.json
import com.deskwoot.sdk.jsonList
import com.deskwoot.sdk.model.ConversationStatus
import com.deskwoot.sdk.model.ConversationSummary
import com.deskwoot.sdk.model.CsatState
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.row
import com.deskwoot.sdk.ui.FileTexts
import com.deskwoot.sdk.ui.conversations.ConversationRow
import com.deskwoot.sdk.ui.conversations.ConversationRows
import com.deskwoot.sdk.ui.testFormats
import com.deskwoot.sdk.ui.text.UiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who speaks, the header line, the team line, the list rows and the formats. */
class PeopleTest {
    private fun config(vararg overrides: Pair<String, Any?>): WidgetConfig = checkNotNull(WidgetConfig.parse(baseConfig(*overrides).toString()))

    private fun msg(id: String, type: String = "OUTGOING", content: String? = "Hi", at: Long = 0, attrs: org.json.JSONObject? = null, sender: org.json.JSONObject? = null): Message =
        checkNotNull(Message.parse(row(id, type, content, at, "c1", attrs, sender).also { r -> if (id.startsWith("ai")) r.put("aiAgent", "Nova") }))

    private fun thread(messages: List<Message>, waiting: Boolean = false, ended: Boolean = false, agentReplied: Boolean = false) = ThreadState(
        conversationId = "c1", status = ConversationStatus.OPEN, messages = messages, pending = emptyList(), uploads = emptyList(),
        segments = emptyList(), botThinking = false, botTool = null, agentTyping = false, queuePosition = null, waitingForTeam = waiting,
        resolved = ended, closed = false, continuesAfterResolve = false, endedLocally = false, showCsat = false, csatState = CsatState.IDLE,
        emailKnown = false, heldMessages = emptyList(), missingFields = emptyList(), transcriptState = TranscriptState.IDLE, ending = false,
        hasAgentReply = agentReplied, gate = Gate.None, inEndState = ended, canSend = true, canAttach = true, canEnd = true, csatDue = false,
        transcriptOffered = false, fynnEndMessageId = null, choicesMessageId = null, botTypingRowId = null,
    )

    @Test
    fun `authors of rows`() {
        val cfg = config(BOT_ON, "companyLogoUrl" to "https://cdn.example.com/logo.png")
        val people = People(cfg, "Support")
        assertEquals(Author("Mia", Face.Initials("M"), AuthorRole.PERSON), people.authorOf(msg("a1", sender = json("name" to "Mia"))))
        assertEquals(Author("Mia", Face.Photo("https://x/m.png"), AuthorRole.PERSON), people.authorOf(msg("a1", sender = json("name" to "Mia", "avatarUrl" to "https://x/m.png"))))
        assertEquals(Author("Fynn", Face.Fynn(null), AuthorRole.BOT), people.authorOf(msg("b1", attrs = json("bot_id" to "bot1"), sender = json("name" to "Fynn"))))
        // A bot row without a sender speaks as the business.
        assertEquals(AuthorRole.BUSINESS, people.authorOf(msg("b2", attrs = json("bot_id" to "bot1"))).role)
        assertEquals(Author("Nova", Face.Robot, AuthorRole.AI), people.authorOf(msg("ai1")))
        assertEquals(Face.Logo("https://cdn.example.com/logo.png", "A"), people.business.face)
        assertEquals("JD", People.initials("jane  doe smith"))
        assertEquals("?", People.initials("  "))
    }

    @Test
    fun `an inbox without names is called Support in the session's language`() {
        val nameless = config("businessName" to null, "inboxName" to "  ")
        assertEquals("Soporte", People(nameless, FileTexts(UiLanguage.ES).t(com.deskwoot.sdk.R.string.dw_support)).businessName)
        assertEquals("客服", People(nameless, FileTexts(UiLanguage.ZH).t(com.deskwoot.sdk.R.string.dw_support)).businessName)
        assertEquals(Face.Logo(null, "S"), People(nameless, "Support").business.face)
        // The inbox's name when the business has none; the business name first.
        assertEquals("Help desk", People(config("businessName" to null, "inboxName" to "Help desk"), "Soporte").businessName)
        assertEquals("Acme", People(config(), "Soporte").businessName)
    }

    @Test
    fun `start faces put the bot first and stop at 3`() {
        val cfg = config(BOT_ON, "team" to listOf(mapOf("name" to "Maya"), mapOf("name" to "Ruben"), mapOf("name" to "QA")))
        assertEquals(listOf(Face.Fynn(null), Face.Initials("M"), Face.Initials("R")), People(cfg, "Support").startFaces())
        assertEquals(listOf(Face.Logo(null, "A")), People(config(), "Support").startFaces())
    }

    @Test
    fun `the header line follows who answered last`() {
        val texts = FileTexts()
        val bot = config(BOT_ON)
        val people = People(bot, "Support")
        assertEquals("AI assistant · answers right away", HeaderLine.of(thread(emptyList()), bot, people, texts).subtitle)
        val person = thread(listOf(msg("a1", sender = json("name" to "Priya"))))
        val line = HeaderLine.of(person, bot, people, texts)
        assertEquals("Priya", line.author.name)
        assertEquals("Acme · in the chat", line.subtitle)
        assertTrue(line.online)
        assertEquals("Connecting you...", HeaderLine.of(thread(emptyList(), waiting = true), bot, people, texts).subtitle)
        assertEquals("Acme", HeaderLine.of(thread(person.messages, ended = true), bot, people, texts).subtitle)

        val noBot = config("available" to false)
        assertEquals("Typically replies in a few minutes", HeaderLine.of(thread(emptyList()), noBot, People(noBot, "Support"), texts).subtitle)
        val replied = HeaderLine.of(thread(emptyList(), agentReplied = true), noBot, People(noBot, "Support"), texts)
        assertEquals("Away", replied.subtitle)
        assertFalse(replied.online)
    }

    @Test
    fun `the team line of the start page`() {
        val texts = FileTexts()
        val bot = config(BOT_ON, "replyTime" to "in_a_few_hours")
        assertEquals(TeamLine("Fynn answers right away.", "The team usually in a few hours."), TeamLine.of(bot, People(bot, "Support"), texts))
        assertEquals(TeamLine("Typically replies in a few minutes", null), TeamLine.of(config("replyTime" to "whenever"), People(config(), "Support"), texts))
        assertEquals("Fynn answers right away. The team usually in a few hours.", TeamLine.of(bot, People(bot, "Support"), texts).asSentences(texts))
        val de = FileTexts(UiLanguage.DE)
        assertEquals("Fynn antwortet sofort.", TeamLine.of(bot, People(bot, "Support"), de).lead)
    }

    @Test
    fun `conversation rows name the last writer and mark the visitor's own text`() {
        val texts = FileTexts()
        val cfg = config(BOT_ON)
        val rows = ConversationRows(People(cfg, "Support"), texts, testFormats(texts))
        val now = BASE_TIME + 120_000
        val fromTeam = ConversationSummary("c1", ConversationStatus.OPEN, BASE_TIME + 60_000, msg("a1", content = "**Good** news", sender = json("name" to "Priya")), 2)
        val mine = ConversationSummary("c2", ConversationStatus.OPEN, BASE_TIME - 7_200_000, msg("v1", type = "INCOMING", content = "Thanks"), 0)
        val file = ConversationSummary(
            "c3", ConversationStatus.RESOLVED, BASE_TIME - 90_000_000,
            checkNotNull(Message.parse(row("a2", content = null, sender = json("name" to "Priya"), attachments = jsonList(json("id" to "f", "fileUrl" to "/f", "fileName" to "invoice.pdf"))))), 0,
        )
        val out = rows.build(listOf(mine, file, fromTeam), now)
        assertEquals(listOf("c1", "c2", "c3"), out.map { it.id })
        assertEquals(ConversationRow("c1", "Priya", Face.Initials("P"), "Good news", "1m ago", true), out[0])
        assertEquals("You: Thanks", out[1].preview)
        assertEquals("Fynn", out[1].name)
        // 2 hours ago today: the clock (09:00 Berlin = 8:00 UTC).
        assertEquals("9:00 AM", out[1].time)
        assertEquals("File: invoice.pdf", out[2].preview)
        assertEquals("Nov 15", out[2].time)
    }

    @Test
    fun `formats in the session's language`() {
        val en = testFormats()
        assertTrue(en.twelveHour)
        assertEquals("11:00 AM", en.clock(BASE_TIME))
        assertEquals("Today", en.dayLine(BASE_TIME, BASE_TIME + 1000))
        assertEquals("Yesterday", en.dayLine(BASE_TIME - 86_400_000, BASE_TIME))
        assertEquals("November 2", en.dayLine(BASE_TIME - 14 * 86_400_000L, BASE_TIME))
        assertEquals("Conversation closed · Nov 16, 11:00 AM", en.closedDivider(BASE_TIME, BASE_TIME + 1000))
        val de = testFormats(FileTexts(UiLanguage.DE))
        assertFalse(de.twelveHour)
        assertEquals("11:00", de.clock(BASE_TIME))
        assertEquals("Heute", de.dayLine(BASE_TIME, BASE_TIME))
        assertEquals("2. November", de.dayLine(BASE_TIME - 14 * 86_400_000L, BASE_TIME))
        assertEquals("Updated 3d ago", en.updated(BASE_TIME - 3 * 86_400_000L, BASE_TIME))
        assertEquals("Mon, Nov 16", en.deliveryDay(iso(0).substring(0, 10)))
    }
}
