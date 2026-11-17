package com.deskwoot.sdk.model

import org.json.JSONObject

/**
 * One row of the visitor's list of conversations (`?list=1`): the conversation, its newest shown message and the replies the
 * visitor has not seen.
 */
internal data class ConversationSummary(
    val id: String,
    val status: ConversationStatus,
    val lastActivityAt: Long,
    val lastMessage: Message?,
    val unread: Int,
) {
    val resolved: Boolean get() = status == ConversationStatus.RESOLVED

    /** The server's entry shape, for the disk cache. */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("status", status.name)
        .put("lastActivityAt", IsoTime.format(lastActivityAt))
        .put("unread", unread)
        .putNullable("lastMessage", lastMessage?.let { parseJsonObject(it.rawJson) })

    internal companion object {
        /** `id` and `status` are required; `unread` is the floor of a positive number, else 0. */
        fun parse(json: JSONObject): ConversationSummary? {
            val id = json.text("id") ?: return null
            val status = ConversationStatus.parse(json.string("status")) ?: return null
            val last = json.obj("lastMessage")?.let(Message::parse)
            val unread = (json.opt("unread") as? Number)?.toDouble()
                ?.takeIf { it > 0 && !it.isInfinite() }?.toInt() ?: 0
            return ConversationSummary(
                id = id,
                status = status,
                lastActivityAt = IsoTime.parse(json.string("lastActivityAt")) ?: last?.createdAt ?: 0L,
                lastMessage = last,
                unread = unread,
            )
        }
    }
}

/**
 * One conversation inside a continuous chat's single history (the poll's
 * `conversations`, oldest first): what the thread needs for its
 * "New conversation" and "Conversation closed" lines.
 */
internal data class ThreadSegment(val id: String, val status: ConversationStatus, val resolvedAt: Long?, val createdAt: Long?) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("status", status.name)
        .putNullable("resolvedAt", resolvedAt?.let(IsoTime::format))
        .putNullable("createdAt", createdAt?.let(IsoTime::format))

    internal companion object {
        fun parse(json: JSONObject): ThreadSegment? {
            val id = json.text("id") ?: return null
            val status = ConversationStatus.parse(json.string("status")) ?: return null
            return ThreadSegment(id, status, IsoTime.parse(json.string("resolvedAt")), IsoTime.parse(json.string("createdAt")))
        }
    }
}
