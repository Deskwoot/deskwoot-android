package com.deskwoot.sdk.ui.thread

import com.deskwoot.sdk.BASE_TIME
import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.json
import com.deskwoot.sdk.jsonList
import com.deskwoot.sdk.model.ConversationStatus
import com.deskwoot.sdk.model.CsatState
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.PendingSend
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.SendFailure
import com.deskwoot.sdk.model.ThreadSegment
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.model.UploadPhase
import com.deskwoot.sdk.model.UploadState
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.row
import com.deskwoot.sdk.ui.FileTexts
import com.deskwoot.sdk.ui.people.Face
import com.deskwoot.sdk.ui.people.People
import com.deskwoot.sdk.ui.testFormats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The thread as rows: the web's composition, grouping, day lines and buttons. */
class ThreadRowsTest {
    private val now = BASE_TIME + 3_600_000 // 11:00 UTC on 2026-11-16, 12:00 in Berlin

    private fun config(vararg overrides: Pair<String, Any?>): WidgetConfig = checkNotNull(WidgetConfig.parse(baseConfig(*overrides).toString()))

    private fun msg(id: String, type: String = "OUTGOING", content: String? = "Hello", at: Long = 0, attrs: JSONObject? = null, sender: JSONObject? = null, conversationId: String? = "c1"): Message =
        checkNotNull(Message.parse(row(id, type, content, at, conversationId, attrs, sender)))

    private fun thread(messages: List<Message>, pending: List<PendingSend> = emptyList(), block: (ThreadState) -> ThreadState = { it }): ThreadState =
        block(
            ThreadState(
                conversationId = "c1", status = ConversationStatus.OPEN, messages = messages, pending = pending, uploads = emptyList(),
                segments = emptyList(), botThinking = false, botTool = null, agentTyping = false, queuePosition = null,
                waitingForTeam = false, resolved = false, closed = false, continuesAfterResolve = false, endedLocally = false,
                showCsat = false, csatState = CsatState.IDLE, emailKnown = false, heldMessages = emptyList(), missingFields = emptyList(),
                transcriptState = TranscriptState.IDLE, ending = false, hasAgentReply = false, gate = Gate.None, inEndState = false,
                canSend = true, canAttach = true, canEnd = true, csatDue = false, transcriptOffered = false, fynnEndMessageId = null,
                choicesMessageId = null, botTypingRowId = null,
            ),
        )

    private fun rows(config: WidgetConfig, state: ThreadState): List<ThreadRow> {
        val texts = FileTexts()
        return ThreadRows(config, People(config, "Support"), texts, testFormats(texts)).build(state, now)
    }

    private val mia = json("name" to "Mia", "avatarUrl" to null)

    @Test
    fun `a group of one writer within 3 minutes has one who line, one face and one tail`() {
        val cfg = config("greeting" to null)
        val out = rows(cfg, thread(listOf(msg("a1", at = 0, sender = mia), msg("a2", at = 60, sender = mia), msg("a3", at = 300, sender = mia))))
        assertEquals(listOf("day:", "m:a1", "m:a2", "m:a3"), out.map { if (it is DayRow) "day:" else it.key })
        assertEquals("Today", (out[0] as DayRow).label)
        val (a1, a2, a3) = out.drop(1).map { it as IncomingRow }
        // The who line carries the time of the group's last row (11:01 in Berlin).
        assertEquals("Mia · 11:01 AM", a1.who?.text)
        assertNull(a1.face)
        assertEquals(4, a1.gapBelow)
        assertEquals(Face.Initials("M"), a2.face)
        assertTrue(a2.tail)
        assertEquals(14, a2.gapBelow)
        // 4 minutes later: a new group.
        assertEquals("Mia · 11:05 AM", a3.who?.text)
    }

    @Test
    fun `the visitor's optimistic bubble and its stored row are one row`() {
        val cfg = config("greeting" to null)
        val pending = PendingSend("u-1", "Do you ship?", BASE_TIME + 10_000, PendingState.SENDING)
        val before = rows(cfg, thread(emptyList(), listOf(pending))).filterIsInstance<VisitorRow>().single()
        assertEquals("u:u-1", before.key)
        assertEquals(SendState.SENDING, before.state)

        val stored = msg("v1", type = "INCOMING", content = "Do you ship?", at = 10, attrs = json("clientAssignedUuid" to "u-1"))
        val after = rows(cfg, thread(listOf(stored))).filterIsInstance<VisitorRow>().single()
        assertEquals("u:u-1", after.key)
        assertEquals(SendState.SENT, after.state)
        assertEquals("11:00 AM", after.time)
    }

    @Test
    fun `a failed send stays as a bubble with its reason`() {
        val cfg = config("greeting" to null)
        val failed = PendingSend("u-2", "Hello?", BASE_TIME, PendingState.FAILED, error = SendFailure.NOT_SENT)
        val row = rows(cfg, thread(emptyList(), listOf(failed))).filterIsInstance<VisitorRow>().single()
        assertEquals(SendState.FAILED, row.state)
        assertEquals(SendFailure.NOT_SENT, row.failure)
        assertEquals("u-2", row.pendingUuid)
    }

    @Test
    fun `the greeting comes first, speaks as the bot and carries the start buttons until the visitor writes`() {
        val cfg = config(BOT_ON, "greeting" to "Hi! How can we help?", "greetingButtons" to listOf("Where is my order?", "Returns"))
        val fresh = rows(cfg, thread(emptyList()))
        // Only the greeting: no day line.
        assertEquals(listOf("greeting", "ch:greeting"), fresh.map { it.key })
        val greeting = fresh[0] as IncomingRow
        assertEquals("Fynn · AI assistant", greeting.who?.text)
        assertEquals(Face.Fynn(null), greeting.face)
        assertEquals(listOf("Where is my order?", "Returns"), (fresh[1] as ChoicesRow).labels)

        val wrote = rows(cfg, thread(listOf(msg("v1", type = "INCOMING", content = "Hi"))))
        assertTrue(wrote.none { it is ChoicesRow })
        // The day line comes before the greeting, for the day of the first real row.
        assertTrue(wrote[0] is DayRow)
        assertEquals("greeting", wrote[1].key)
    }

    @Test
    fun `the bot's typing row takes its placeholder's key and shows the tool line`() {
        val cfg = config(BOT_ON, "greeting" to null)
        val t = thread(listOf(msg("v1", type = "INCOMING", content = "Where is order 1042?"))) {
            it.copy(botThinking = true, botTool = "order", botTypingRowId = "b9")
        }
        val typing = rows(cfg, t).last() as TypingRow
        assertEquals("m:b9", typing.key)
        assertEquals("Checking your order…", typing.tool)
        assertEquals(Face.Fynn(null), typing.face)

        val filled = rows(cfg, thread(listOf(msg("v1", type = "INCOMING", content = "Where is order 1042?"), msg("b9", content = "It ships today.", at = 5, attrs = json("bot_id" to "bot1")))))
        assertEquals("m:b9", filled.last().key)
        assertTrue(filled.last() is IncomingRow)
    }

    @Test
    fun `choice buttons, Fynn's fixed buttons and their states`() {
        val cfg = config(BOT_ON, "greeting" to null, "available" to false)
        val answer = msg(
            "b1", content = "Pick one", attrs = json(
                "bot_id" to "bot1",
                "buttons" to jsonList(json("label" to "Standard"), json("label" to "Express")),
                "fynnEnd" to json("resolved" to "That answered my question", "team" to "Talk to the team", "teamAway" to "Leave a message"),
            ),
        )
        val out = rows(cfg, thread(listOf(answer)) { it.copy(choicesMessageId = "b1") })
        assertEquals(listOf("Standard", "Express"), out.filterIsInstance<ChoicesRow>().single().labels)

        val fixed = rows(cfg, thread(listOf(answer)) { it.copy(fynnEndMessageId = "b1") }).filterIsInstance<FixedButtonsRow>().single()
        // Nobody available: the away label.
        assertEquals("Leave a message", fixed.teamLabel)
        assertEquals("That answered my question", fixed.resolvedLabel)
        // The answer keeps its full gap above its buttons.
        assertEquals(14, rows(cfg, thread(listOf(answer, msg("b2", at = 10, attrs = json("bot_id" to "bot1")))) { it.copy(fynnEndMessageId = "b1") })
            .filterIsInstance<IncomingRow>().first().gapBelow)
    }

    @Test
    fun `Frage geklaert shows as the visitor's bubble with the button's label, copy and line as one row`() {
        val cfg = config(BOT_ON, "greeting" to null)
        val answer = msg("b1", content = "Done!", attrs = json("bot_id" to "bot1", "fynnEnd" to json("resolved" to "That answered my question")))
        val copy = PendingSend("u-9", "That answered my question", BASE_TIME + 1, PendingState.SENDING, questionResolved = true)
        val optimistic = rows(cfg, thread(listOf(answer), listOf(copy))).filterIsInstance<VisitorRow>().single()
        assertEquals("qr:b1", optimistic.key)

        val line = msg("r1", type = "ACTIVITY", content = null, at = 2, attrs = json("activityKind" to "resolved_by_contact", "source" to "fynn_resolved"))
        val stored = rows(cfg, thread(listOf(answer, line))).filterIsInstance<VisitorRow>().single()
        assertEquals("qr:b1", stored.key)
        assertEquals("That answered my question", stored.text)
    }

    @Test
    fun `activity lines are worded in the visitor's language and never group`() {
        val cfg = config("greeting" to null)
        val joined = msg("s1", type = "ACTIVITY", content = "Priya joined the conversation", attrs = json("activityKind" to "agent_joined", "agentName" to "Priya"))
        val queued = msg("s2", type = "ACTIVITY", content = "x", at = 1, attrs = json("activityKind" to "bot_handoff", "handoff" to "queued"))
        val out = rows(cfg, thread(listOf(joined, queued))).filterIsInstance<ActivityRow>()
        assertEquals("Priya is now in the chat", out[0].text)
        assertEquals("Bot transferred the conversation. You're in the queue, the next available agent will be with you.", out[1].text)
    }

    @Test
    fun `a link alone becomes a card without a bubble, a tracking card is worded`() {
        val cfg = config("greeting" to null)
        val link = msg("a1", content = "https://deskwoot.com/pricing", sender = mia)
        val tracking = msg(
            "b1", content = "Your parcel is on its way.", at = 1, attrs = json(
                "bot_id" to "bot1",
                "trackingCard" to json("trackingUrl" to "https://track.example.com/1", "status" to "in transit", "carrier" to "DHL", "orderNumber" to "1042", "estimatedDelivery" to "2026-11-17"),
            ),
        )
        val out = rows(cfg, thread(listOf(link, tracking))).filterIsInstance<IncomingRow>()
        assertEquals("", out[0].text)
        assertEquals("deskwoot.com/pricing", out[0].linkCard?.title)
        assertEquals(TrackingView("On its way", "Order 1042 · DHL", "Expected Tue, Nov 17", "https://track.example.com/1"), out[1].tracking)
    }

    @Test
    fun `continuous chat draws dividers between conversations`() {
        val cfg = config("greeting" to null, "continuousChat" to true)
        val t = thread(
            listOf(
                msg("v1", type = "INCOMING", content = "First", conversationId = "c0"),
                msg("a1", content = "Answer", at = 30, conversationId = "c0", sender = mia),
                msg("v2", type = "INCOMING", content = "Second", at = 400, conversationId = "c1"),
            ),
        ) {
            it.copy(segments = listOf(ThreadSegment("c0", ConversationStatus.RESOLVED, BASE_TIME + 100_000, BASE_TIME), ThreadSegment("c1", ConversationStatus.OPEN, null, BASE_TIME + 400_000)))
        }
        val keys = rows(cfg, t).map { if (it is DayRow) "day" else it.key }
        assertEquals(listOf("day", "m:v1", "m:a1", "div:closed:c0", "div:new:c1", "m:v2"), keys)
    }

    @Test
    fun `uploads, the survey and the transcript offer come last`() {
        val cfg = config("greeting" to null)
        val t = thread(listOf(msg("a1", sender = mia))) {
            it.copy(
                uploads = listOf(UploadState("f1", "photo.jpg", UploadPhase.UPLOADING, progress = 0.4f), UploadState("f2", "x.exe", UploadPhase.FAILED)),
                csatDue = true, transcriptOffered = true,
            )
        }
        val tail = rows(cfg, t).takeLast(3)
        assertEquals(UploadRow("up:f1", "photo.jpg", 0.4f, false), tail[0])
        assertTrue(tail[1] is CsatRow)
        assertTrue(tail[2] is TranscriptRow)
    }

    @Test
    fun `a reply quote names you or the business and points at its row`() {
        val cfg = config("greeting" to null)
        val mine = msg("v1", type = "INCOMING", content = "My order is late")
        val reply = msg("a1", content = "Sorry!", at = 10, sender = mia, attrs = json("reply_to" to json("message_id" to "v1", "text" to "My order is late")))
        val row = rows(cfg, thread(listOf(mine, reply))).filterIsInstance<IncomingRow>().single()
        assertEquals(QuoteView("You", "My order is late", "m:v1"), row.quote)
    }

    @Test
    fun `a long press offers Copy where the bubble has text and Reply where the visitor can reply`() {
        val cfg = config()
        val file = checkNotNull(Message.parse(row("f1", content = null, at = 20, sender = mia, attachments = jsonList(json("id" to "x1", "fileUrl" to "/api/widget/files/x1", "fileName" to "invoice.pdf", "fileType" to "FILE", "contentType" to "application/pdf")))))
        val readback = json(
            "bot_id" to "bot1",
            "fynnReadback" to json("intro" to "Please check:", "rows" to jsonList(json("label" to "Name", "value" to "Anna"), json("label" to "Order", "value" to "1042")), "ask" to "Is that right?"),
        )
        val messages = listOf(
            msg("a1", content = "**Your order** ships [today](https://acme.test/t).\n\n- Box 1\n- Box 2", at = 0, sender = mia),
            msg("v1", type = "INCOMING", content = "Thanks, *great*", at = 10),
            file,
            msg("r1", content = "ignored", at = 30, attrs = readback, sender = json("name" to "Fynn")),
        )
        val failed = PendingSend("p1", "Are you there?", BASE_TIME + 40_000, PendingState.FAILED, error = SendFailure.NOT_SENT)
        val out = rows(cfg, thread(messages, listOf(failed))).filter { it is IncomingRow || it is VisitorRow }
        fun offers(key: String): Pair<String?, Boolean> = out.first { it.key == key }.let { MessageMenu.copyText(it) to MessageMenu.canReply(it) }

        // The greeting: Copy only.
        assertEquals("Hi there" to false, offers("greeting"))
        // A reply of the team: its words without the marks of markdown, one line per block, and Reply.
        assertEquals("Your order ships today.\nBox 1\nBox 2" to true, offers("m:a1"))
        // The visitor's stored message: both.
        assertEquals("Thanks, great" to true, offers("m:v1"))
        // Only a file: Reply only, and no Copy offered at all.
        assertEquals(null to true, offers("m:f1"))
        assertEquals(false, MessageMenu.mayCopy(out.first { it.key == "m:f1" }))
        // A readback: its intro, one line per row, its question.
        assertEquals("Please check:\nName Anna\nOrder 1042\nIs that right?" to true, offers("m:r1"))
        // A message that was not sent: Copy only.
        assertEquals("Are you there?" to false, offers("u:p1"))
        // In a closed conversation nothing takes a reply; the text can still be copied.
        val closed = rows(cfg, thread(messages) { it.copy(closed = true) }).first { it.key == "m:a1" }
        assertEquals("Your order ships today.\nBox 1\nBox 2" to false, MessageMenu.copyText(closed) to MessageMenu.canReply(closed))
    }
}
