package com.deskwoot.sdk.api

import com.deskwoot.sdk.model.ConversationSummary
import com.deskwoot.sdk.model.IsoTime
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.MessageRules
import com.deskwoot.sdk.model.ThreadSegment
import com.deskwoot.sdk.model.array
import com.deskwoot.sdk.model.boolean
import com.deskwoot.sdk.model.int
import com.deskwoot.sdk.model.isTrue
import com.deskwoot.sdk.model.obj
import com.deskwoot.sdk.model.objects
import com.deskwoot.sdk.model.string
import com.deskwoot.sdk.model.text
import org.json.JSONObject

// The answers of the widget API, decoded. Each parse returns null when a
// key the route always sends is missing: the call then counts as no usable
// answer, never as a success. Every other field a server may
// leave out is nullable or has the default the web client reads it with.

/** `POST /api/widget/identity/verify`. Without a bearer it is no answer, never a refusal. */
internal data class VerifyAnswer(val contactIdentifier: String, val attributesIgnored: String?) {
    /** The bearer is a secret: never printed. */
    override fun toString(): String = "VerifyAnswer(contactIdentifier=[redacted], attributesIgnored=$attributesIgnored)"

    internal companion object {
        fun parse(json: JSONObject): VerifyAnswer? {
            val bearer = json.text("contactIdentifier") ?: return null
            return VerifyAnswer(bearer, json.text("attributesIgnored"))
        }
    }
}

/** `GET /api/widget/conversations`. */
internal data class RestoreAnswer(
    val conversationId: String?,
    val status: String?,
    val resolvedAt: Long?,
    val unreadCount: Int,
    val latestAgentMessage: String?,
    val continuesAfterResolve: Boolean,
    val emailKnown: Boolean,
    val closed: Boolean,
) {
    internal companion object {
        /** Every answer of the route names `conversationId` (null for none). */
        fun parse(json: JSONObject): RestoreAnswer? {
            if (!json.has("conversationId")) return null
            return RestoreAnswer(
                conversationId = json.text("conversationId"),
                status = json.text("status"),
                resolvedAt = IsoTime.parse(json.string("resolvedAt")),
                unreadCount = json.int("unreadCount")?.coerceAtLeast(0) ?: 0,
                latestAgentMessage = json.string("latestAgentMessage"),
                continuesAfterResolve = json.isTrue("continuesAfterResolve"),
                emailKnown = json.isTrue("emailKnown"),
                closed = json.isTrue("closed"),
            )
        }
    }
}

/** `GET /api/widget/conversations?list=1`. */
internal data class ListAnswer(val conversations: List<ConversationSummary>, val closed: Boolean) {
    internal companion object {
        fun parse(json: JSONObject): ListAnswer? {
            val entries = json.array("conversations") ?: return null
            return ListAnswer(entries.objects().mapNotNull(ConversationSummary::parse), json.isTrue("closed"))
        }
    }
}

/**
 * `GET /api/widget/messages`. Rows a visitor must not see are
 * dropped here once more. [hasAgentReply] null: an older server, the
 * thread then guesses. [segments] only in continuous chat.
 */
internal data class PollAnswer(
    val conversationId: String?,
    val messages: List<Message>,
    val resolved: Boolean,
    val continuesAfterResolve: Boolean,
    val showCsatSurvey: Boolean,
    val queuePosition: Int?,
    val waiting: Boolean,
    val hasAgentReply: Boolean?,
    val segments: List<ThreadSegment>?,
    val closed: Boolean,
) {
    internal companion object {
        /** At most [MAX_ROWS] rows are read: the server sends 300 in continuous chat. */
        const val MAX_ROWS: Int = 1_000

        fun parse(json: JSONObject): PollAnswer? {
            val rows = json.array("messages") ?: return null
            return PollAnswer(
                conversationId = json.text("conversationId"),
                messages = rows.objects().take(MAX_ROWS)
                    .filterNot(MessageRules::isHiddenRow)
                    .mapNotNull(Message::parse),
                resolved = json.isTrue("resolved"),
                continuesAfterResolve = json.isTrue("continuesAfterResolve"),
                showCsatSurvey = json.isTrue("showCsatSurvey"),
                queuePosition = json.obj("queue")?.int("position"),
                waiting = json.isTrue("waiting"),
                hasAgentReply = json.boolean("hasAgentReply"),
                segments = json.array("conversations")?.objects()?.mapNotNull(ThreadSegment::parse),
                closed = json.isTrue("closed"),
            )
        }
    }
}

/**
 * `POST /api/widget/messages`. A spam quarantine looks like an
 * accept and carries no message id. The bearer is mandatory: every
 * accepted send names it.
 */
internal data class SendAnswer(
    val messageId: String?,
    val conversationId: String?,
    val reopened: Boolean,
    val newConversation: Boolean,
    val botWillReply: Boolean,
    val contactIdentifier: String,
    val emailKnown: Boolean,
) {
    /** The bearer is a secret: never printed. */
    override fun toString(): String =
        "SendAnswer(messageId=$messageId, conversationId=$conversationId, reopened=$reopened, " +
            "newConversation=$newConversation, botWillReply=$botWillReply, contactIdentifier=[redacted], emailKnown=$emailKnown)"

    internal companion object {
        fun parse(json: JSONObject): SendAnswer? {
            val bearer = json.text("contactIdentifier") ?: return null
            val messageId = json.text("messageId")
            if (messageId == null && !json.isTrue("quarantined")) return null
            return SendAnswer(
                messageId = messageId,
                conversationId = json.text("conversationId"),
                reopened = json.isTrue("reopened"),
                newConversation = json.isTrue("newConversation"),
                botWillReply = json.isTrue("botWillReply"),
                contactIdentifier = bearer,
                emailKnown = json.isTrue("emailKnown"),
            )
        }
    }
}

/** `POST /api/widget/attachments`. [messageId] is the stored file's row; null for a quarantined file. */
internal data class UploadAnswer(val messageId: String?, val conversationId: String?, val reopened: Boolean, val newConversation: Boolean) {
    internal companion object {
        fun parse(json: JSONObject): UploadAnswer? {
            val messageId = json.text("messageId")
            if (messageId == null && !json.isTrue("quarantined")) return null
            return UploadAnswer(
                messageId = messageId,
                conversationId = json.text("conversationId"),
                reopened = json.isTrue("reopened"),
                newConversation = json.isTrue("newConversation"),
            )
        }
    }
}

/** `POST /api/widget/conversations/{id}/resolved`; [lineId] is the resolve line. */
internal data class ResolvedAnswer(val alreadyResolved: Boolean, val lineId: String?, val lineCreatedAt: Long?) {
    internal companion object {
        fun parse(json: JSONObject): ResolvedAnswer? {
            if (!json.isTrue("ok")) return null
            val line = json.obj("line")
            return ResolvedAnswer(
                alreadyResolved = json.isTrue("alreadyResolved"),
                lineId = line?.text("id"),
                lineCreatedAt = IsoTime.parse(line?.string("createdAt")),
            )
        }
    }
}
