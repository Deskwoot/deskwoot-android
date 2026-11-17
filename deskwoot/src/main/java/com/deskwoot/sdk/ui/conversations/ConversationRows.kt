package com.deskwoot.sdk.ui.conversations

import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.ConversationSummary
import com.deskwoot.sdk.model.MessageRole
import com.deskwoot.sdk.ui.people.Face
import com.deskwoot.sdk.ui.people.People
import com.deskwoot.sdk.ui.text.Formats
import com.deskwoot.sdk.ui.text.MessageText
import com.deskwoot.sdk.ui.text.Texts

/** One row of "Your conversations". */
internal data class ConversationRow(
    val id: String,
    val name: String,
    val face: Face,
    val preview: String,
    val time: String,
    val unread: Boolean,
)

/**
 * The visitor's list as rows:
 * the last message's author, or the bot or the business when the visitor
 * wrote last; "You: " before the visitor's own text; a file without text
 * as "File: {name}"; the list time; the unread dot.
 */
internal class ConversationRows(private val people: People, private val texts: Texts, private val formats: Formats) {
    fun build(conversations: List<ConversationSummary>, now: Long): List<ConversationRow> =
        conversations.sortedByDescending { it.lastActivityAt }.map { row(it, now) }

    fun row(c: ConversationSummary, now: Long): ConversationRow {
        val last = c.lastMessage
        val mine = last == null || last.role == MessageRole.VISITOR
        val author = if (last == null || last.role == MessageRole.VISITOR) people.firstAnswerer else people.authorOf(last)
        val text = MessageText.preview(last?.content)
            ?: last?.attachments?.firstOrNull()?.let { file ->
                file.fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.t(R.string.dw_file_preview, it) }
                    ?: texts.t(R.string.dw_file_sent_preview)
            }?.take(80)
            ?: ""
        val preview = if (mine && last != null && text.isNotEmpty()) texts.t(R.string.dw_you_said, text) else text
        val at = if (c.lastActivityAt > 0) c.lastActivityAt else last?.createdAt ?: 0L
        return ConversationRow(c.id, author.name, author.face, preview, formats.listTime(at, now), c.unread > 0)
    }
}
