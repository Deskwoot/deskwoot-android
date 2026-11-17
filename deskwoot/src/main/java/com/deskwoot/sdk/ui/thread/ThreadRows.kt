package com.deskwoot.sdk.ui.thread

import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.Attachment
import com.deskwoot.sdk.model.ConversationStatus
import com.deskwoot.sdk.model.HelpArticleRef
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.MessageRole
import com.deskwoot.sdk.model.PendingSend
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.Readback
import com.deskwoot.sdk.model.SendFailure
import com.deskwoot.sdk.model.SenderKind
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.TrackingStatus
import com.deskwoot.sdk.model.UploadPhase
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.ui.people.AuthorRole
import com.deskwoot.sdk.ui.people.Face
import com.deskwoot.sdk.ui.people.People
import com.deskwoot.sdk.ui.text.Formats
import com.deskwoot.sdk.ui.text.Markdown
import com.deskwoot.sdk.ui.text.MessageText
import com.deskwoot.sdk.ui.text.Texts

/**
 * One row of the thread. [key] is stable for the life of the row (DiffUtil
 * uses it as the item's identity), [gapBelow] the space under it in dp.
 */
internal sealed class ThreadRow {
    abstract val key: String
    abstract val gapBelow: Int
}

/** "Today", "Yesterday", "16 November" before the first row of a day. */
internal data class DayRow(override val key: String, val label: String) : ThreadRow() {
    override val gapBelow: Int get() = 14
}

/** A centered activity line ("Priya is now in the chat"), never grouped. */
internal data class ActivityRow(override val key: String, val text: String) : ThreadRow() {
    override val gapBelow: Int get() = 14
}

/** Continuous chat: "New conversation" or "Conversation closed · date" between conversations. */
internal data class DividerRow(override val key: String, val text: String) : ThreadRow() {
    override val gapBelow: Int get() = 14
}

/** The writer line above an incoming group: "Miriam · 9:42", the AI agent's in bold. */
internal data class WhoLine(val text: String, val emphasized: Boolean)

/** A quote inside a bubble; [targetKey] is the quoted row's key when it is in the thread. */
internal data class QuoteView(val author: String?, val text: String, val targetKey: String?)

/** The bot's parcel card, worded. */
internal data class TrackingView(val title: String, val meta: String?, val expected: String?, val url: String)

/** A reply of the bot, the team or the business. */
internal data class IncomingRow(
    override val key: String,
    /** The server's row id; null for the greeting. */
    val messageId: String?,
    val authorName: String,
    val who: WhoLine?,
    /** The face beside the last bubble of the group; null on the other bubbles, which keep its space. */
    val face: Face?,
    /** The tail corner of the last bubble of a group. */
    val tail: Boolean,
    /** Markdown text of the bubble; null or empty when the reply is only a card or a file. */
    val text: String?,
    val readback: Readback?,
    val linkCard: MessageText.LinkCard?,
    val tracking: TrackingView?,
    val quote: QuoteView?,
    val attachments: List<Attachment>,
    val canReply: Boolean,
    /** For screen readers: the time of this row. */
    val time: String,
    override val gapBelow: Int,
) : ThreadRow() {
    val hasBubble: Boolean get() = !text.isNullOrBlank() || readback != null || attachments.isNotEmpty() || quote != null
}

internal enum class SendState { SENT, SENDING, FAILED }

/** A message of the visitor: stored, on its way or not sent. */
internal data class VisitorRow(
    override val key: String,
    val messageId: String?,
    /** The pending send this row shows, for retry and discard. */
    val pendingUuid: String?,
    val text: String?,
    /** Under the last bubble of a group. */
    val time: String?,
    val tail: Boolean,
    val state: SendState,
    val failure: SendFailure?,
    /** When the send started, so the bubble fades only after 1.5 s. */
    val sentAt: Long,
    val quote: QuoteView?,
    val helpArticle: HelpArticleRef?,
    val attachments: List<Attachment>,
    val canReply: Boolean,
    override val gapBelow: Int,
) : ThreadRow()

/** A file of the visitor on its way up: the pending bubble with its progress. */
internal data class UploadRow(override val key: String, val fileName: String, val progress: Float, val done: Boolean) : ThreadRow() {
    override val gapBelow: Int get() = 14
}

/** The typing dots (or Fynn's tool line) beside a face; [name] is who types, for screen readers. */
internal data class TypingRow(override val key: String, val face: Face, val tool: String?, val byBot: Boolean, val name: String) : ThreadRow() {
    override val gapBelow: Int get() = 12
}

/**
 * Direkt's block at the top of the thread instead of the greeting bubble:
 * the open conversation banner when a fresh chat has
 * an older one running, then the face, the welcome title, the greeting and
 * the team line (drawn from the config).
 */
internal data class GreetingBlockRow(
    override val key: String,
    val bannerId: String?,
    val bannerName: String?,
    val bannerFace: Face?,
    val bannerPreview: String?,
    val bannerUnread: Boolean,
    override val gapBelow: Int = 14,
) : ThreadRow()

/** Choice buttons under the bot's reply, or start buttons under the greeting. */
internal data class ChoicesRow(override val key: String, val labels: List<String>, val greeting: Boolean) : ThreadRow() {
    override val gapBelow: Int get() = 14
}

/** Fynn's fixed buttons: "Frage geklärt" and the team button. */
internal data class FixedButtonsRow(
    override val key: String,
    val messageId: String,
    val resolvedLabel: String?,
    val teamLabel: String?,
    val busy: Boolean,
    val failed: Boolean,
) : ThreadRow() {
    override val gapBelow: Int get() = 14
}

/** The rating card at the end of the thread. */
internal data class CsatRow(override val key: String) : ThreadRow() {
    override val gapBelow: Int get() = 14
}

/** "Would you like a copy of this conversation?" at the end of the thread. */
internal data class TranscriptRow(override val key: String) : ThreadRow() {
    override val gapBelow: Int get() = 14
}

/** What the rows depend on besides the session state. */
internal data class FixedButtonsUi(val busy: Boolean = false, val failedFor: String? = null)

/**
 * The thread as rows: the greeting,
 * stored and pending messages in time order, day lines, continuous chat
 * dividers, the web's grouping (same writer within 3 minutes, never across
 * a divider), who lines, faces and tails, then uploads, the typing row,
 * buttons, the survey and the transcript offer.
 */
internal class ThreadRows(
    private val config: WidgetConfig,
    private val people: People,
    private val texts: Texts,
    private val formats: Formats,
    /** Direkt shows the greeting in its header block instead of a bubble. */
    private val greetingAsBubble: Boolean = true,
) {
    private sealed class Entry {
        abstract val at: Long
        abstract val sortId: String
        abstract val conversationId: String?

        class Server(val m: Message) : Entry() {
            override val at: Long get() = m.createdAt
            override val sortId: String get() = m.id
            override val conversationId: String? get() = m.conversationId
        }

        class Pending(val p: PendingSend) : Entry() {
            override val at: Long get() = p.createdAt
            override val sortId: String get() = "optimistic-" + p.uuid
            override val conversationId: String? get() = null
        }

        object Greeting : Entry() {
            override val at: Long get() = 0L
            override val sortId: String get() = ""
            override val conversationId: String? get() = null
        }
    }

    fun build(thread: ThreadState, now: Long, buttons: FixedButtonsUi = FixedButtonsUi()): List<ThreadRow> {
        val greeting = config.greeting?.trim()?.takeIf { it.isNotEmpty() && greetingAsBubble }
        val entries = ArrayList<Entry>(thread.messages.size + thread.pending.size + 1)
        if (greeting != null) entries += Entry.Greeting
        thread.messages.forEach { entries += Entry.Server(it) }
        thread.pending.forEach { entries += Entry.Pending(it) }
        entries.sortWith(compareBy<Entry>({ it.at }, { it.sortId }))
        // "Frage geklärt": the optimistic copy and the server's line share one key; the line wins.
        val ordered = mergeQuestionChoices(entries)

        val byId = thread.messages.associateBy { it.id }
        val canWrite = !thread.closed && !thread.inEndState
        val continuous = config.continuousChat
        val segments = thread.segments.associateBy { it.id }
        val lastIndexOf = HashMap<String, Int>()
        if (continuous) ordered.forEachIndexed { i, e -> e.entry.conversationId?.let { lastIndexOf[it] = i } }
        val firstReal = ordered.firstOrNull { it.entry !is Entry.Greeting }?.entry?.at

        val rows = ArrayList<ThreadRow>(ordered.size + 8)
        val started = HashSet<String>()
        var previous: Keyed? = null
        var previousWasDivider = false

        for ((index, item) in ordered.withIndex()) {
            val entry = item.entry
            // Continuous chat: "New conversation" before every conversation after the first.
            val cid = entry.conversationId
            if (continuous && cid != null && cid !in started) {
                if (started.isNotEmpty()) {
                    rows += DividerRow("div:new:$cid", texts.t(R.string.dw_new_conversation))
                    previousWasDivider = true
                }
                started += cid
            }
            // The day line before the first row of a day; the greeting counts for the first real row's day.
            val dayAt = if (entry is Entry.Greeting) firstReal else entry.at
            val prevAt = previous?.let { if (it.entry is Entry.Greeting) firstReal else it.entry.at }
            if (firstReal != null && dayAt != null && !previousWasDivider &&
                (prevAt == null || !formats.sameDay(prevAt, dayAt))
            ) {
                rows += DayRow("day:" + formats.dayKey(dayAt), formats.dayLine(dayAt, now))
            }
            previousWasDivider = false

            val next = ordered.getOrNull(index + 1)
            val groupStart = previous == null || !sameGroup(previous, item) || rows.lastOrNull() is DividerRow
            // The last row of a resolved conversation: the "closed" line follows it.
            val closing: String? = if (continuous && cid != null && lastIndexOf[cid] == index &&
                segments[cid]?.status == ConversationStatus.RESOLVED
            ) {
                cid
            } else {
                null
            }
            val nextStartsConversation = continuous && next?.entry?.conversationId?.let { it !in started && started.isNotEmpty() } == true
            val groupEnd = next == null || !sameGroup(item, next) || closing != null || nextStartsConversation

            rows += rowFor(item, ordered, index, groupStart, groupEnd, thread, byId, canWrite, buttons)

            if (closing != null) {
                rows += DividerRow("div:closed:$closing", formats.closedDivider(segments[closing]?.resolvedAt, now))
                previousWasDivider = true
            }
            previous = item
        }

        // Files on their way up: newest, so last.
        for (upload in thread.uploads) {
            if (upload.phase == UploadPhase.FAILED) continue
            rows += UploadRow("up:" + upload.id, upload.fileName, upload.progress, upload.phase == UploadPhase.SENT)
        }

        // The typing row: keyed by the bot's placeholder row, so the answer fills it in place.
        if (thread.botThinking || thread.agentTyping) {
            val byBot = thread.botThinking
            val author = people.typingAuthor(thread.messages, byBot)
            val tool = if (byBot) toolLine(thread.botTool) else null
            val key = thread.botTypingRowId?.let { "m:$it" } ?: "typing"
            rows += TypingRow(key, author.face, tool, byBot, author.name)
        }

        if (thread.csatDue) rows += CsatRow("csat:" + (thread.conversationId ?: ""))
        if (thread.transcriptOffered) rows += TranscriptRow("tr:" + (thread.conversationId ?: ""))
        return uniqueKeys(rows)
    }

    /** An entry with its thread key, its text and its author name for grouping. */
    private class Keyed(val entry: Entry, val key: String, val role: MessageRole, val aiAgent: String?, val authorName: String?, val text: String?)

    private fun mergeQuestionChoices(entries: List<Entry>): List<Keyed> {
        val out = ArrayList<Keyed>(entries.size)
        val seen = HashMap<String, Int>()
        var answer: Message? = null
        for (entry in entries) {
            when (entry) {
                Entry.Greeting -> out += Keyed(entry, "greeting", greetingRole(), null, people.greetingAuthor().name, config.greeting?.trim())
                is Entry.Server -> {
                    val m = entry.m
                    if (m.questionResolved) {
                        val key = "qr:" + (answer?.id ?: m.id)
                        val label = answer?.attributes?.fynnEnd?.resolved ?: texts.t(R.string.dw_question_resolved)
                        val keyed = Keyed(entry, key, MessageRole.VISITOR, null, null, label)
                        val at = seen[key]
                        if (at == null) {
                            seen[key] = out.size
                            out += keyed
                        } else {
                            out[at] = keyed
                        }
                        continue
                    }
                    if (m.attributes.fynnEnd != null) answer = m
                    // The visitor's stored row takes the key of its optimistic copy, so the bubble never jumps.
                    val uuid = m.clientAssignedUuid
                    val key = if (m.role == MessageRole.VISITOR && uuid != null) "u:$uuid" else "m:" + m.id
                    val author = if (m.role == MessageRole.BOT || m.role == MessageRole.AGENT) people.authorOf(m).name else null
                    out += Keyed(entry, key, m.role, aiAgentOf(m), author, m.content)
                }
                is Entry.Pending -> {
                    val p = entry.p
                    if (p.questionResolved) {
                        val key = "qr:" + (answer?.id ?: p.uuid)
                        if (seen[key] == null) {
                            seen[key] = out.size
                            out += Keyed(entry, key, MessageRole.VISITOR, null, null, p.text)
                        }
                        continue
                    }
                    out += Keyed(entry, "u:" + p.uuid, MessageRole.VISITOR, null, null, p.text)
                }
            }
        }
        return out
    }

    private fun greetingRole(): MessageRole = if (config.botActive) MessageRole.BOT else MessageRole.AGENT

    private fun aiAgentOf(m: Message): String? = if (m.sender?.kind == SenderKind.AI_AGENT) m.sender.name else null

    /** Grouped as on the web: same role and writer, never system, never the greeting, within 3 minutes. */
    private fun sameGroup(a: Keyed, b: Keyed): Boolean {
        if (a.role == MessageRole.SYSTEM || b.role == MessageRole.SYSTEM) return false
        if (a.role != b.role) return false
        if (a.aiAgent != b.aiAgent) return false
        if (a.role != MessageRole.VISITOR && a.authorName != b.authorName) return false
        if (a.entry is Entry.Greeting || b.entry is Entry.Greeting) return false
        return kotlin.math.abs(b.entry.at - a.entry.at) <= GROUP_WINDOW_MILLIS
    }

    private fun rowFor(
        item: Keyed,
        all: List<Keyed>,
        index: Int,
        groupStart: Boolean,
        groupEnd: Boolean,
        thread: ThreadState,
        byId: Map<String, Message>,
        canWrite: Boolean,
        buttons: FixedButtonsUi,
    ): List<ThreadRow> {
        val entry = item.entry
        if (item.role == MessageRole.SYSTEM && entry is Entry.Server) {
            return listOf(ActivityRow(item.key, activityText(entry.m)))
        }
        val out = ArrayList<ThreadRow>(2)
        if (item.role == MessageRole.VISITOR) {
            val m = (entry as? Entry.Server)?.m
            val p = (entry as? Entry.Pending)?.p
            val state = when (p?.state) {
                null, PendingState.SENT -> SendState.SENT
                PendingState.SENDING -> SendState.SENDING
                PendingState.FAILED -> SendState.FAILED
            }
            val text = BubbleTextCache.get(item.text, withCard = false).first
            out += VisitorRow(
                key = item.key,
                messageId = m?.id,
                pendingUuid = p?.uuid,
                text = text,
                time = if (groupEnd) formats.clock(entry.at) else null,
                tail = groupEnd,
                state = state,
                failure = p?.error,
                sentAt = entry.at,
                quote = quoteOf(m?.attributes?.replyTo ?: p?.replyTo, byId, all),
                // The card shows on the bubble at once, before the server stored the message.
                helpArticle = m?.attributes?.helpArticle ?: p?.helpArticle,
                attachments = m?.attachments.orEmpty(),
                canReply = canWrite && m != null && !m.questionResolved && m.conversationId != null,
                gapBelow = if (groupEnd) 14 else 4,
            )
            return out
        }

        // Incoming: the greeting, the bot, the team, the business.
        val m = (entry as? Entry.Server)?.m
        val author = if (m != null) people.authorOf(m) else people.greetingAuthor()
        val who = if (groupStart) {
            var last = index
            while (last + 1 < all.size && sameGroup(all[last], all[last + 1])) last++
            val label = when (author.role) {
                AuthorRole.AI -> texts.t(R.string.dw_ai_agent_badge, author.name)
                AuthorRole.BOT -> author.name + " · " + texts.t(R.string.dw_ai_assistant)
                else -> author.name
            }
            val lastEntry = all[last].entry
            val time = if (lastEntry is Entry.Greeting) "" else formats.clock(lastEntry.at)
            WhoLine(if (time.isEmpty()) label else "$label · $time", author.role == AuthorRole.AI)
        } else {
            null
        }
        val readback = m?.attributes?.readback
        val (text, card) = BubbleTextCache.get(item.text, withCard = readback == null)
        val buttonsBelow = m != null && (m.id == thread.choicesMessageId || m.id == thread.fynnEndMessageId) ||
            (m == null && greetingButtonsShow(thread))
        out += IncomingRow(
            key = item.key,
            messageId = m?.id,
            authorName = author.name,
            who = who,
            face = if (groupEnd) author.face else null,
            tail = groupEnd,
            text = text,
            readback = readback,
            linkCard = card,
            tracking = m?.attributes?.trackingCard?.let { tc ->
                val title = when (tc.status) {
                    TrackingStatus.OUT_FOR_DELIVERY -> texts.t(R.string.dw_tracking_out_for_delivery)
                    TrackingStatus.SHIPPED -> texts.t(R.string.dw_tracking_on_its_way)
                    null -> texts.t(R.string.dw_tracking_parcel)
                }
                val meta = listOfNotNull(tc.orderNumber?.let { texts.t(R.string.dw_tracking_order, it) }, tc.carrier)
                    .joinToString(" · ").ifEmpty { null }
                TrackingView(title, meta, tc.estimatedDelivery?.let { texts.t(R.string.dw_tracking_expected, formats.deliveryDay(it)) }, tc.trackingUrl)
            },
            quote = quoteOf(m?.attributes?.replyTo, byId, all),
            attachments = m?.attachments.orEmpty(),
            canReply = canWrite && m != null && !m.isTyping && m.conversationId != null,
            time = if (entry is Entry.Greeting) "" else formats.clock(entry.at),
            gapBelow = if (groupEnd || buttonsBelow) 14 else 4,
        )
        // Buttons under this row.
        if (m == null) {
            if (greetingButtonsShow(thread)) out += ChoicesRow("ch:greeting", config.greetingButtons, greeting = true)
        } else {
            // Under one answer the choice buttons and Fynn's fixed buttons can both show.
            if (m.id == thread.choicesMessageId && m.attributes.buttons.isNotEmpty()) {
                out += ChoicesRow("ch:" + m.id, m.attributes.buttons, greeting = false)
            }
            if (m.id != thread.fynnEndMessageId) return out
            val end = m.attributes.fynnEnd
            if (end != null) {
                val team = if (!config.available) end.teamAway ?: end.team else end.team
                if (end.resolved != null || team != null) {
                    out += FixedButtonsRow("fx:" + m.id, m.id, end.resolved, team, buttons.busy, buttons.failedFor == m.id)
                }
            }
        }
        return out
    }

    /**
     * Direkt's top rows: its greeting block and the start buttons under it.
     * [conversations]: the visitor's list, for the banner of an older open
     * conversation while this chat is a fresh one.
     */
    fun direktTop(thread: ThreadState, conversations: List<com.deskwoot.sdk.model.ConversationSummary>): List<ThreadRow> {
        val fresh = thread.messages.none { it.role == MessageRole.VISITOR } && thread.pending.isEmpty()
        val open = if (fresh) conversations.firstOrNull { !it.resolved && it.lastMessage != null && it.id != thread.conversationId } else null
        val last = open?.lastMessage
        val author = last?.let { people.authorOf(it) }
        val block = GreetingBlockRow(
            key = "greeting-block",
            bannerId = open?.id,
            bannerName = author?.name,
            bannerFace = author?.face,
            bannerPreview = last?.let { MessageText.preview(it.content, 120) ?: it.attachments.firstOrNull()?.fileName?.let { n -> texts.t(R.string.dw_file_preview, n) } },
            bannerUnread = (open?.unread ?: 0) > 0,
        )
        val buttons = if (startButtonsShow(thread)) listOf(ChoicesRow("ch:greeting", config.greetingButtons, greeting = true)) else emptyList()
        return listOf(block) + buttons
    }

    /** Start buttons under the greeting: until the visitor wrote anything, and only while buttons may show. */
    private fun greetingButtonsShow(thread: ThreadState): Boolean {
        if (config.greetingButtons.isEmpty() || config.greeting.isNullOrBlank() || !greetingAsBubble) return false
        return startButtonsShow(thread)
    }

    /** The start buttons' own rule, without the bubble they hang under (Direkt puts them under its block). */
    private fun startButtonsShow(thread: ThreadState): Boolean {
        if (config.greetingButtons.isEmpty()) return false
        if (thread.closed || thread.inEndState || thread.botThinking) return false
        if (thread.pending.isNotEmpty()) return false
        return thread.messages.none { it.role == MessageRole.VISITOR }
    }

    private fun quoteOf(reply: com.deskwoot.sdk.model.ReplyQuote?, byId: Map<String, Message>, all: List<Keyed>): QuoteView? {
        reply ?: return null
        val quoted = byId[reply.messageId]
        val author = when {
            quoted == null -> null
            quoted.role == MessageRole.VISITOR -> texts.t(R.string.dw_you)
            else -> people.businessName
        }
        val text = reply.text.trim().ifEmpty { null }
            ?: MessageText.quotePreview(quoted?.content)
            ?: quoted?.attachments?.firstOrNull()?.fileName?.trim()?.ifEmpty { null }
            ?: texts.t(R.string.dw_file_label)
        val target = quoted?.let { q -> all.firstOrNull { (it.entry as? Entry.Server)?.m?.id == q.id }?.key }
        return QuoteView(author, text, target)
    }

    /** The activity line in the visitor's language; unknown kinds show the server's text. */
    fun activityText(m: Message): String {
        val a = m.attributes.activity
        val name = a?.agentName
        return when (a?.kind) {
            "agent_joined" -> if (name != null) texts.t(R.string.dw_agent_joined, name) else texts.t(R.string.dw_team_member_joined)
            "agent_left" -> if (name != null) texts.t(R.string.dw_agent_left, name) else texts.t(R.string.dw_team_member_left)
            "bot_handoff" -> when (a.handoff) {
                "assigned" -> if (name != null) texts.t(R.string.dw_bot_handoff_ai_agent, name) else texts.t(R.string.dw_bot_handoff_assigned)
                "queued" -> texts.t(R.string.dw_bot_handoff_queued)
                "waiting" -> texts.t(R.string.dw_bot_handoff_waiting)
                else -> m.content.orEmpty()
            }
            "continued_via_email" -> a.email?.let { texts.t(R.string.dw_continued_by_email, it) } ?: m.content.orEmpty()
            "resolved_by_contact" -> texts.t(R.string.dw_resolved_by_you)
            "resolved", "auto_resolved" -> texts.t(R.string.dw_conversation_resolved)
            "status_changed" -> texts.t(R.string.dw_conversation_resolved)
            "reopened_by_customer", "reopened_by_agent" -> texts.t(R.string.dw_conversation_reopened)
            else -> m.content.orEmpty()
        }
    }

    /** Fynn's tool line for a known kind; any other kind shows the dots. */
    private fun toolLine(kind: String?): String? = when (kind) {
        "order" -> texts.t(R.string.dw_tool_order)
        "lookup" -> texts.t(R.string.dw_tool_lookup)
        "times" -> texts.t(R.string.dw_tool_times)
        "appointments" -> texts.t(R.string.dw_tool_appointments)
        "details" -> texts.t(R.string.dw_tool_details)
        "products" -> texts.t(R.string.dw_tool_products)
        "checking" -> texts.t(R.string.dw_tool_checking)
        else -> null
    }

    internal companion object {
        const val GROUP_WINDOW_MILLIS: Long = 3 * 60 * 1000L
    }
}

/**
 * What a message that was not sent says under its bubble, in the visitor's
 * language, and whether Try again can work: after no answer, a rate limit or an unknown refusal it can; never
 * for the limits, a text that is too long or a conversation that is over. A send that got no answer or an
 * unknown refusal says "Not sent" beside Try again; the other refusals say why.
 */
internal object SendFailures {
    fun text(failure: SendFailure?, texts: Texts): String = when (failure) {
        null, SendFailure.NOT_SENT, SendFailure.REJECTED -> texts.t(R.string.dw_sdk_not_sent)
        SendFailure.RATE_LIMITED -> texts.t(R.string.dw_sdk_send_rate_limited)
        SendFailure.MESSAGE_TOO_LONG -> texts.t(R.string.dw_sdk_too_long)
        SendFailure.NEW_CONVERSATION_LIMIT -> texts.t(R.string.dw_sdk_send_new_conversation_limit)
        SendFailure.MONTHLY_CONVERSATION_LIMIT -> texts.t(R.string.dw_sdk_send_monthly_limit)
        SendFailure.CONVERSATION_RESOLVED -> texts.t(R.string.dw_sdk_send_conversation_resolved)
    }

    fun canRetry(failure: SendFailure?): Boolean = when (failure) {
        null, SendFailure.NOT_SENT, SendFailure.RATE_LIMITED, SendFailure.REJECTED -> true
        SendFailure.MESSAGE_TOO_LONG, SendFailure.NEW_CONVERSATION_LIMIT, SendFailure.MONTHLY_CONVERSATION_LIMIT, SendFailure.CONVERSATION_RESOLVED -> false
    }
}

/**
 * What a long press on a message offers, as on iOS: Copy where its bubble
 * has text (a workflow's readback counts), Reply where the visitor can
 * reply to it. The greeting, a message still on its way and one that was
 * not sent offer Copy only; a reply that is only a file offers Reply only.
 */
internal object MessageMenu {
    /** Whether Copy may show, without working out the text (a bind runs often). */
    fun mayCopy(row: ThreadRow): Boolean = when (row) {
        is IncomingRow -> !row.text.isNullOrBlank() || row.readback != null
        is VisitorRow -> !row.text.isNullOrBlank()
        else -> false
    }

    fun canReply(row: ThreadRow): Boolean = when (row) {
        is IncomingRow -> row.canReply
        is VisitorRow -> row.canReply
        else -> false
    }

    /**
     * What Copy puts on the clipboard, or null when the row offers no Copy:
     * the bubble's text as it reads, without the marks of markdown (a link
     * as its words, a list item without its bullet, one line per block);
     * a readback as its intro, one line of label and value per row, and its
     * question.
     */
    fun copyText(row: ThreadRow): String? {
        val readback = (row as? IncomingRow)?.readback
        val text = when {
            readback != null -> (listOf(readback.intro) + readback.rows.map { it.label + " " + it.value } + readback.ask).joinToString("\n")
            row is IncomingRow -> row.text?.let { plain(Markdown.parse(it)) }
            row is VisitorRow -> row.text?.let { plain(Markdown.parse(it)) }
            else -> null
        }
        return text?.trim()?.ifEmpty { null }
    }

    private fun plain(blocks: List<Markdown.Block>): String = blocks.map { block ->
        when (block) {
            is Markdown.Block.Paragraph -> Markdown.plain(block.inlines)
            is Markdown.Block.Heading -> Markdown.plain(block.inlines)
            is Markdown.Block.ListBlock -> block.items.joinToString("\n") { plain(it.blocks) }
            is Markdown.Block.Quote -> plain(block.blocks)
            is Markdown.Block.Code -> block.text
            is Markdown.Block.Table -> (listOf(block.header) + block.rows).joinToString("\n") { cells -> cells.joinToString(", ") { Markdown.plain(it) } }
            Markdown.Block.Rule -> ""
        }
    }.filter { it.isNotEmpty() }.joinToString("\n")
}

/**
 * Keys rest on server rules the client never enforces (one "question
 * resolved" line per answer, one stored row per clientAssignedUuid); two
 * rows with one key would break the list's stable ids. The
 * first keeps its key, a later one gets the key with its count.
 */
internal fun uniqueKeys(rows: List<ThreadRow>): List<ThreadRow> {
    val seen = HashMap<String, Int>()
    var changed = false
    val out = rows.map { row ->
        val count = seen[row.key] ?: 0
        seen[row.key] = count + 1
        if (count == 0) {
            row
        } else {
            changed = true
            var candidate = row.key + "~" + count
            while (seen.containsKey(candidate)) candidate += "~"
            seen[candidate] = 1
            row.withKey(candidate)
        }
    }
    return if (changed) out else rows
}

private fun ThreadRow.withKey(key: String): ThreadRow = when (this) {
    is DayRow -> copy(key = key)
    is ActivityRow -> copy(key = key)
    is DividerRow -> copy(key = key)
    is IncomingRow -> copy(key = key)
    is VisitorRow -> copy(key = key)
    is UploadRow -> copy(key = key)
    is TypingRow -> copy(key = key)
    is ChoicesRow -> copy(key = key)
    is FixedButtonsRow -> copy(key = key)
    is CsatRow -> copy(key = key)
    is TranscriptRow -> copy(key = key)
    is GreetingBlockRow -> copy(key = key)
}

/**
 * The replies the visitor has not seen yet, for the announcement and the
 * "New messages" chip; [seen] learns them. The bot's typing row keeps its
 * key when the answer fills it in place, so a reply counts as new by being
 * a reply for the first time, not by its key being new.
 */
internal fun newIncomingRows(rows: List<ThreadRow>, seen: MutableSet<String>): List<IncomingRow> =
    rows.filterIsInstance<IncomingRow>().filter { seen.add(it.key) }

/**
 * The bubble text and link card of a reply, worked out once per text
 * instead of on every state change (the bot's poll, typing
 * and realtime rebuild the rows many times a minute).
 */
internal object BubbleTextCache {
    private const val MAX = 1000

    private val map = object : LinkedHashMap<String, Pair<String?, MessageText.LinkCard?>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String?, MessageText.LinkCard?>>?): Boolean = size > MAX
    }

    @Synchronized
    fun get(raw: String?, withCard: Boolean): Pair<String?, MessageText.LinkCard?> {
        raw ?: return null to null
        val key = (if (withCard) "c" else "n") + raw
        map[key]?.let { return it }
        var text: String? = MessageText.bubbleText(raw)
        var card: MessageText.LinkCard? = null
        if (withCard && text != null) {
            val (rest, split) = MessageText.splitLinkCard(text)
            if (split != null) {
                text = rest
                card = split
            }
        }
        val result = text to card
        map[key] = result
        return result
    }
}
