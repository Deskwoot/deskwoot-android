package com.deskwoot.sdk.model

import org.json.JSONObject

internal enum class MessageType { INCOMING, OUTGOING, ACTIVITY, OTHER }

/** Who a row speaks for in the thread (the web mapper's role). */
internal enum class MessageRole { VISITOR, BOT, AGENT, SYSTEM }

internal enum class SenderKind { VISITOR, PERSON, BOT, AI_AGENT, BUSINESS }

/**
 * Who wrote a row: the server's `sender` (a person's first name and photo,
 * or the bot's), an AI agent's public name, the visitor, or the business
 * itself when the inbox speaks.
 */
internal data class Sender(val name: String?, val avatarUrl: String?, val kind: SenderKind)

internal enum class FileType { IMAGE, VIDEO, AUDIO, FILE }

/** One file of a row. [fileUrl] is a signed relative link: resolve it against the API base. */
internal data class Attachment(
    val id: String,
    val fileUrl: String,
    val fileName: String?,
    val fileType: FileType,
    val contentType: String?,
    val fileSize: Long?,
) {
    /** Drawn inline: the IMAGE category or an image MIME type. */
    val isImage: Boolean get() = fileType == FileType.IMAGE || contentType?.startsWith("image/") == true

    internal companion object {
        fun parse(json: JSONObject): Attachment? {
            val url = json.text("fileUrl") ?: return null
            return Attachment(
                // The id is the cache key for images: the signed URL changes every hour.
                id = json.text("id") ?: url,
                fileUrl = url,
                fileName = json.text("fileName"),
                fileType = when (json.string("fileType")) {
                    "IMAGE" -> FileType.IMAGE
                    "VIDEO" -> FileType.VIDEO
                    "AUDIO" -> FileType.AUDIO
                    else -> FileType.FILE
                },
                contentType = json.text("contentType"),
                fileSize = json.long("fileSize"),
            )
        }
    }
}

/** The earlier message a row answers: its id and the start of its text (`reply_to`). */
internal data class ReplyQuote(val messageId: String, val text: String)

/** Fynn's fixed buttons under his answer. */
internal data class FynnEnd(val resolved: String?, val team: String?, val teamAway: String?)

internal enum class TrackingStatus { SHIPPED, OUT_FOR_DELIVERY }

/** The bot's parcel card. [estimatedDelivery] is a real `YYYY-MM-DD` day or null. */
internal data class TrackingCard(
    val trackingUrl: String,
    val status: TrackingStatus?,
    val carrier: String?,
    val orderNumber: String?,
    val estimatedDelivery: String?,
)

/** A workflow's readback list, drawn instead of the text. */
internal data class Readback(val intro: String, val rows: List<ReadbackRow>, val ask: String)

internal data class ReadbackRow(val label: String, val value: String)

/** The help center article a visitor wrote from (`hc_article`). */
internal data class HelpArticleRef(val url: String, val title: String)

/** An activity line's kind and fields. */
internal data class Activity(
    val kind: String?,
    val agentName: String?,
    val handoff: String?,
    val email: String?,
    val source: String?,
)

/** The attributes of a row a visitor may see, read as the web mapper reads them. */
internal data class MessageAttributes(
    val isTyping: Boolean,
    val botId: String?,
    val botTool: String?,
    val replayFollows: Boolean,
    val clientAssignedUuid: String?,
    val buttons: List<String>,
    val fynnEnd: FynnEnd?,
    val trackingCard: TrackingCard?,
    val readback: Readback?,
    val activity: Activity?,
    val replyTo: ReplyQuote?,
    val helpArticle: HelpArticleRef?,
    val chatTriggerId: String?,
)

/**
 * One row of a conversation, from the poll, the conversation list or the
 * realtime push: one serializer feeds all three, so one parser
 * reads them. [rawJson] is the row as it came, for the disk cache.
 */
internal data class Message(
    val id: String,
    val conversationId: String?,
    val type: MessageType,
    val role: MessageRole,
    /** The text; null for a file row and for the visitor's "question resolved" line. */
    val content: String?,
    /** TEXT or HTML; any other value reads as TEXT. */
    val contentType: String,
    /** Server time in epoch milliseconds; 0 when it did not parse. */
    val createdAt: Long,
    val sender: Sender?,
    val attachments: List<Attachment>,
    val attributes: MessageAttributes,
    /** An OUTGOING row the server wrote on its own (the out of office reply). */
    val automated: Boolean,
    /** The visitor's own "Frage geklärt" tap, drawn as their bubble. */
    val questionResolved: Boolean,
    /** A push too large for Ably: never paint it, poll instead. */
    val contentOmitted: Boolean,
    val rawJson: String,
) {
    val isTyping: Boolean get() = attributes.isTyping
    val hasText: Boolean get() = !content.isNullOrBlank()
    val clientAssignedUuid: String? get() = attributes.clientAssignedUuid

    /** The bot's empty typing placeholder: drives the typing indicator, never drawn. */
    val isTypingPlaceholder: Boolean get() = isTyping && !hasText

    internal companion object {
        private val VISIBLE_HANDOFF = setOf("assigned", "queued", "waiting")
        private val DAY = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:T|$)")

        /** A server row, or null for anything without an id. */
        fun parse(json: JSONObject): Message? {
            val id = json.text("id") ?: return null
            val attrs = json.obj("contentAttributes")
            val type = when (json.string("messageType")) {
                "INCOMING" -> MessageType.INCOMING
                "OUTGOING" -> MessageType.OUTGOING
                "ACTIVITY" -> MessageType.ACTIVITY
                else -> MessageType.OTHER
            }
            val botId = attrs?.text("bot_id")
            val activityKind = attrs?.text("activityKind")
            val questionResolved = type == MessageType.ACTIVITY &&
                activityKind == "resolved_by_contact" && attrs.string("source") == "fynn_resolved"
            // The bot_id branch is load bearing: a bot row has no other mark.
            val role = when {
                questionResolved -> MessageRole.VISITOR
                type == MessageType.INCOMING -> MessageRole.VISITOR
                type == MessageType.ACTIVITY -> MessageRole.SYSTEM
                botId != null -> MessageRole.BOT
                else -> MessageRole.AGENT
            }
            val outgoing = type == MessageType.OUTGOING
            val aiAgent = if (outgoing) json.text("aiAgent") else null
            val serverSender = if (outgoing) json.obj("sender") else null
            val isTyping = attrs?.opt("isTyping") == true
            val automated = outgoing && activityKind != null
            val sender = when {
                role == MessageRole.SYSTEM -> null
                role == MessageRole.VISITOR -> Sender(null, null, SenderKind.VISITOR)
                aiAgent != null -> Sender(aiAgent, null, SenderKind.AI_AGENT)
                role == MessageRole.BOT -> Sender(serverSender?.text("name"), serverSender?.text("avatarUrl"), SenderKind.BOT)
                serverSender?.text("name") != null ->
                    Sender(serverSender.text("name"), serverSender.text("avatarUrl"), SenderKind.PERSON)
                else -> Sender(null, null, SenderKind.BUSINESS)
            }
            val attributes = MessageAttributes(
                isTyping = isTyping,
                botId = botId,
                botTool = if (isTyping) attrs.text("bot_tool") else null,
                replayFollows = attrs?.opt("replay_follows") == true,
                clientAssignedUuid = attrs?.text("clientAssignedUuid"),
                buttons = if (outgoing) buttonsOf(attrs) else emptyList(),
                fynnEnd = if (outgoing && botId != null) fynnEndOf(attrs.obj("fynnEnd")) else null,
                trackingCard = if (outgoing) trackingCardOf(attrs?.obj("trackingCard")) else null,
                readback = if (outgoing && botId != null) readbackOf(attrs.obj("fynnReadback")) else null,
                activity = if (type == MessageType.ACTIVITY) activityOf(attrs) else null,
                replyTo = if (type != MessageType.ACTIVITY) replyToOf(attrs?.obj("reply_to")) else null,
                helpArticle = if (type == MessageType.INCOMING) helpArticleOf(attrs?.obj("hc_article")) else null,
                chatTriggerId = attrs?.obj("chatTrigger")?.text("id"),
            )
            return Message(
                id = id,
                conversationId = json.text("conversationId"),
                type = type,
                role = role,
                content = if (questionResolved) null else json.string("content"),
                contentType = if (json.string("contentType") == "HTML") "HTML" else "TEXT",
                createdAt = IsoTime.parse(json.string("createdAt")) ?: 0L,
                sender = sender,
                attachments = json.array("attachments")?.objects()?.mapNotNull(Attachment::parse).orEmpty(),
                attributes = attributes,
                automated = automated,
                questionResolved = questionResolved,
                contentOmitted = json.isTrue("contentOmitted"),
                rawJson = json.rawText(),
            )
        }

        fun parse(text: String?): Message? = parseJsonObject(text)?.let(::parse)

        /** Choice buttons: labels trimmed, empty ones dropped, at most 5. */
        private fun buttonsOf(attrs: JSONObject?): List<String> =
            attrs?.array("buttons")?.objects()?.mapNotNull { it.text("label") }?.take(5).orEmpty()

        private fun fynnEndOf(json: JSONObject?): FynnEnd? {
            json ?: return null
            val end = FynnEnd(json.text("resolved"), json.text("team"), json.text("teamAway"))
            return if (end.resolved == null && end.team == null && end.teamAway == null) null else end
        }

        /** A card only with a safe http or https link; status and day normalised as the web does. */
        private fun trackingCardOf(json: JSONObject?): TrackingCard? {
            json ?: return null
            val url = json.text("trackingUrl") ?: return null
            val lower = url.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
            if (url.substringAfter("://").substringBefore('/').isBlank()) return null
            val status = when (json.text("status")?.lowercase()?.replace(Regex("[\\s-]+"), "_")) {
                "out_for_delivery" -> TrackingStatus.OUT_FOR_DELIVERY
                "shipped", "in_transit", "fulfilled" -> TrackingStatus.SHIPPED
                else -> null
            }
            val day = json.text("estimatedDelivery")?.let { DAY.find(it) }?.let { match ->
                val text = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
                // Only a real day: February 30 is not one.
                if (IsoTime.parse("${text}T00:00:00Z")?.let { IsoTime.format(it).startsWith(text) } == true) text else null
            }
            return TrackingCard(url, status, json.text("carrier"), json.text("orderNumber"), day)
        }

        /** Any malformed part drops the whole list; the row then shows its text. */
        private fun readbackOf(json: JSONObject?): Readback? {
            json ?: return null
            val intro = json.string("intro") ?: return null
            val ask = json.string("ask") ?: return null
            val rows = json.array("rows") ?: return null
            if (rows.length() > 50) return null
            val parsed = ArrayList<ReadbackRow>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.opt(i) as? JSONObject ?: return null
                val label = row.string("label") ?: return null
                val value = row.string("value")?.takeIf { it.isNotEmpty() } ?: return null
                parsed += ReadbackRow(label, value)
            }
            return Readback(intro, parsed, ask)
        }

        private fun activityOf(attrs: JSONObject?): Activity = Activity(
            kind = attrs?.text("activityKind"),
            agentName = attrs?.text("agentName"),
            handoff = attrs?.string("handoff")?.takeIf { it in VISIBLE_HANDOFF },
            email = attrs?.text("email"),
            source = attrs?.text("source"),
        )

        private fun replyToOf(json: JSONObject?): ReplyQuote? {
            val id = json?.text("message_id") ?: return null
            return ReplyQuote(id, json.string("text") ?: "")
        }

        private fun helpArticleOf(json: JSONObject?): HelpArticleRef? {
            json ?: return null
            val url = json.text("url") ?: return null
            val lower = url.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
            val title = json.text("title")?.takeIf { it.length <= 300 } ?: return null
            return HelpArticleRef(url, title)
        }
    }
}
