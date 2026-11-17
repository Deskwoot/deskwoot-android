package com.deskwoot.sdk.model

import com.deskwoot.sdk.json
import com.deskwoot.sdk.jsonList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDecodingTest {
    @Test
    fun `an empty config takes the web widget's defaults`() {
        val config = WidgetConfig.parse(JSONObject())

        // No name at all: the UI says "Support" in the session's language (People).
        assertEquals("", config.businessName)
        assertEquals("#2563eb", config.brandColor)
        assertEquals("light", config.widgetTheme)
        assertEquals("klartext", config.widgetDesign)
        assertEquals("du", config.formOfAddress)
        assertNull(config.widgetLocale)
        assertTrue(config.showBranding)
        assertTrue(config.widgetSounds)
        assertEquals(listOf("image/*", "application/pdf", "text/*"), config.allowedFileTypes)
        assertEquals(5 * 1024 * 1024, config.maxUploadBytes)
        assertEquals("STARS", config.csat.ratingStyle)
        assertFalse(config.csat.enabled)
        assertFalse(config.bot.enabled)
        assertFalse(config.botActive)
        assertFalse(config.continuousChat)
        assertFalse(config.emailCollectEnabled)
        assertTrue(config.allowMessagesAfterResolved)
        // A missing `available` or `showBranding` reads as true.
        assertTrue(config.available)
        assertTrue(config.preChatFields.isEmpty())
        assertTrue(config.greetingButtons.isEmpty())
        assertTrue(config.featuredArticles.isEmpty())
    }

    @Test
    fun `unknown fields are ignored and unknown values fall back`() {
        val config = WidgetConfig.parse(
            json(
                "somethingNew" to mapOf("nested" to listOf(1, 2, 3)),
                "businessName" to "  ",
                "inboxName" to "Help desk",
                "brandColor" to "blue",
                "widgetDesign" to "future",
                "widgetTheme" to "dark",
                "widgetLocale" to "auto",
                "formOfAddress" to "sie",
                "csat" to mapOf("enabled" to true, "ratingStyle" to "LASERS", "extra" to 1),
                "bot" to mapOf("enabled" to true, "agentBotId" to "b1", "name" to " Fynn "),
                "maxUploadBytes" to 0,
                "persistentConversations" to true,
                "greetingButtons" to listOf("A", " ", "B", "C", "D", "E"),
                "allowedFileTypes" to "not a list",
                "available" to "yes",
            ),
        )

        assertEquals("Help desk", config.businessName)
        assertEquals("#2563eb", config.brandColor)
        assertEquals("klartext", config.widgetDesign)
        assertEquals("dark", config.widgetTheme)
        assertNull(config.widgetLocale)
        assertEquals("sie", config.formOfAddress)
        assertTrue(config.csat.enabled)
        assertEquals("STARS", config.csat.ratingStyle)
        assertTrue(config.botActive)
        assertEquals("Fynn", config.bot.name)
        assertEquals(5 * 1024 * 1024, config.maxUploadBytes)
        // The older flag alone still means continuous chat.
        assertTrue(config.continuousChat)
        assertEquals(listOf("A", "B", "C", "D"), config.greetingButtons)
        assertEquals(WidgetConfig.DEFAULT_FILE_TYPES, config.allowedFileTypes)
        assertTrue(config.available)
        assertFalse(WidgetConfig.parse(json("available" to false)).available)
    }

    @Test
    fun `pre chat fields read both the object and the old array shape`() {
        val field = mapOf("name" to "email", "type" to "email", "label" to "E-Mail", "required" to true)
        val select = mapOf(
            "name" to "topic",
            "type" to "select",
            "options" to listOf("a", "b"),
            "optionLabels" to listOf("A"),
            "required" to false,
        )
        val modern = WidgetConfig.parse(json("preChatFormFields" to mapOf("fields" to listOf(field, select, mapOf("type" to "text")))))
        val legacy = WidgetConfig.parse(json("preChatFormFields" to listOf(field)))

        assertEquals(2, modern.preChatFields.size)
        assertTrue(modern.preChatFields[0].isEmail)
        assertTrue(modern.preChatFields[0].required)
        // Translations are used only with one per option.
        assertNull(modern.preChatFields[1].optionLabels)
        assertEquals(1, legacy.preChatFields.size)
    }

    @Test
    fun `the raw config survives for the disk cache`() {
        val original = json("businessName" to "Acme", "csat" to mapOf("enabled" to true))
        val config = WidgetConfig.parse(original)
        val again = checkNotNull(WidgetConfig.parse(config.rawJson))
        assertEquals(config, again.copy(rawJson = config.rawJson))
    }

    @Test
    fun `rows are read as the web mapper reads them`() {
        val bot = Message.parse(
            json(
                "id" to "r1",
                "messageType" to "OUTGOING",
                "content" to "",
                "createdAt" to "2026-11-16T10:00:00.000Z",
                "contentAttributes" to mapOf("bot_id" to "b1", "isTyping" to true, "bot_tool" to "order"),
            ),
        )
        val agent = Message.parse(
            json(
                "id" to "a1",
                "messageType" to "OUTGOING",
                "content" to "Hi",
                "createdAt" to "2026-11-16T10:00:01Z",
                "sender" to mapOf("name" to "Mia", "avatarUrl" to null),
                "contentAttributes" to mapOf(
                    "buttons" to listOf(mapOf("label" to "Yes"), mapOf("label" to " ")),
                    "fynnEnd" to mapOf("resolved" to "Done"),
                    "reply_to" to mapOf("message_id" to "m0", "text" to "earlier"),
                ),
            ),
        )
        val resolvedTap = Message.parse(
            json(
                "id" to "x1",
                "messageType" to "ACTIVITY",
                "content" to "You ended the conversation",
                "createdAt" to "2026-11-16T10:00:02Z",
                "contentAttributes" to mapOf("activityKind" to "resolved_by_contact", "source" to "fynn_resolved"),
            ),
        )

        checkNotNull(bot)
        assertEquals(MessageRole.BOT, bot.role)
        assertTrue(bot.isTypingPlaceholder)
        assertEquals("order", bot.attributes.botTool)

        checkNotNull(agent)
        assertEquals(MessageRole.AGENT, agent.role)
        assertEquals(SenderKind.PERSON, agent.sender?.kind)
        assertEquals("Mia", agent.sender?.name)
        assertEquals(listOf("Yes"), agent.attributes.buttons)
        // Fixed buttons are read only on the bot's rows.
        assertNull(agent.attributes.fynnEnd)
        assertEquals(ReplyQuote("m0", "earlier"), agent.attributes.replyTo)

        checkNotNull(resolvedTap)
        assertEquals(MessageRole.VISITOR, resolvedTap.role)
        assertTrue(resolvedTap.questionResolved)
        assertNull(resolvedTap.content)
    }

    @Test
    fun `visitor visibility rules match the poll's`() {
        fun activity(kind: String?, content: String) =
            json("id" to "a", "messageType" to "ACTIVITY", "content" to content, "contentAttributes" to kind?.let { mapOf("activityKind" to it) })

        assertTrue(MessageRules.isVisibleActivity(activity("agent_joined", "Mia joined")))
        assertFalse(MessageRules.isVisibleActivity(activity("assigned", "Assigned")))
        assertTrue(MessageRules.isVisibleActivity(activity("status_changed", "Conversation resolved by Mia")))
        assertFalse(MessageRules.isVisibleActivity(activity("status_changed", "Status changed to pending")))
        assertFalse(MessageRules.isVisibleActivity(activity(null, "Label added")))
        assertTrue(MessageRules.isVisibleActivity(activity(null, "Bot transferred the conversation")))
        assertTrue(MessageRules.isHiddenRow(json("id" to "p", "private" to true)))
        assertTrue(MessageRules.isHiddenRow(json("id" to "t", "contentAttributes" to mapOf("bot_internal" to true))))
        assertEquals(3, jsonList(1, 2, 3).length())
    }

    @Test
    fun `iso times parse and format as the server writes them`() {
        val millis = checkNotNull(IsoTime.parse("2026-11-16T09:41:00.123Z"))
        assertEquals("2026-11-16T09:41:00.123Z", IsoTime.format(millis))
        assertEquals(IsoTime.parse("2026-11-16T10:41:00.123+01:00"), millis)
        assertEquals(IsoTime.parse("2026-11-16T09:41:00.123456Z"), millis)
        assertNull(IsoTime.parse("2026-02-30T00:00:00Z"))
        assertNull(IsoTime.parse("yesterday"))
        assertEquals("1970-01-01T00:00:00.000Z", IsoTime.format(0))
        assertEquals("2024-02-29T23:59:59.999Z", IsoTime.format(checkNotNull(IsoTime.parse("2024-02-29T23:59:59.999Z"))))
    }
}
