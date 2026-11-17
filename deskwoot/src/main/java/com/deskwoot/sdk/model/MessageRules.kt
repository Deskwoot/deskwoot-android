package com.deskwoot.sdk.model

import org.json.JSONObject

/** Rules about single rows that the poll, the push and the thread share. */
internal object MessageRules {
    /** The activity kinds a visitor sees (VISITOR_VISIBLE_ACTIVITY_KINDS). */
    val VISIBLE_ACTIVITY_KINDS: Set<String> = setOf(
        "agent_joined",
        "agent_left",
        "bot_handoff",
        "resolved",
        "continued_via_email",
        "resolved_by_contact",
        "auto_resolved",
        "reopened_by_customer",
        "reopened_by_agent",
    )

    private val RESOLVE_LINE = Regex("^conversation resolved\\b", RegexOption.IGNORE_CASE)

    fun isResolveLine(content: String?): Boolean = RESOLVE_LINE.containsMatchIn(content ?: "")

    /**
     * Notes and the bot's tool traces never show. The server leaves them
     * out; a pushed row is checked again, as the web widget does.
     */
    fun isHiddenRow(row: JSONObject): Boolean =
        row.isTrue("private") || row.obj("contentAttributes")?.isTrue("bot_internal") == true

    /** Whether a pushed ACTIVITY row may show: the poll's own rule. */
    fun isVisibleActivity(row: JSONObject): Boolean {
        if (row.string("messageType") != "ACTIVITY") return true
        val kind = row.obj("contentAttributes")?.text("activityKind")
        val content = row.string("content") ?: ""
        if (kind != null) {
            if (kind == "status_changed" || kind == "resolved") return isResolveLine(content)
            return kind in VISIBLE_ACTIVITY_KINDS
        }
        val c = content.lowercase()
        if (c.contains("auto-assigned") || c.contains("auto assigned")) return false
        if (c.startsWith("conversation assigned to") || c.contains("re-assigned")) return false
        if (c.startsWith("label ") || c.contains("status changed")) return false
        return c.contains("joined the conversation") ||
            c.startsWith("bot transferred the conversation") ||
            c.contains("conversation resolved")
    }

    /**
     * A row that ends the bot's turn for the typing indicator: a finished
     * reply of the bot or the team, or the bot's handoff line. A thanks line
     * with `replay_follows` does not: the answer still comes.
     */
    fun endsBotTurn(m: Message): Boolean {
        if (m.role == MessageRole.SYSTEM) return m.attributes.activity?.kind == "bot_handoff"
        return m.role != MessageRole.VISITOR && !m.isTyping && !m.attributes.replayFollows
    }

    /** A team member's reply or join line ends the wait after a handover. */
    fun endsHandoverWait(m: Message): Boolean {
        if (m.role == MessageRole.SYSTEM) return m.attributes.activity?.kind == "agent_joined"
        return m.role == MessageRole.AGENT && !m.isTyping && m.attributes.chatTriggerId == null && !m.automated
    }

    /** A reply that counts toward the unread badge: from the team or the bot, with text or a file. */
    fun countsAsUnread(m: Message): Boolean =
        (m.role == MessageRole.AGENT || m.role == MessageRole.BOT) &&
            !m.isTyping &&
            (m.hasText || m.attachments.isNotEmpty()) &&
            m.attributes.chatTriggerId == null

    /** A human agent replied in this conversation, as the web guesses it without the poll's answer. */
    fun isHumanReply(m: Message): Boolean =
        m.role == MessageRole.AGENT && m.sender?.kind == SenderKind.PERSON && !m.isTyping &&
            (m.hasText || m.attachments.isNotEmpty()) && m.attributes.chatTriggerId == null && !m.automated

    /** Display order: by server time, then by id. */
    val ORDER: Comparator<Message> = compareBy<Message>({ it.createdAt }, { it.id })
}
