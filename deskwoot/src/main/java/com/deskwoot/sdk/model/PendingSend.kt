package com.deskwoot.sdk.model

import com.deskwoot.sdk.api.ApiError

/**
 * Where a message the visitor wrote stands before its stored row arrives.
 * SENDING: tries are running. SENT: the server took it, its row is not
 * here yet. FAILED: it was not sent; retry or discard. A
 * message that waits for the form or the email step is not a pending
 * send: it is in [ThreadState.heldMessages].
 */
internal enum class PendingState { SENDING, SENT, FAILED }

/** Why a message was not sent, by HTTP status and code. */
internal enum class SendFailure {
    /** No answer or a 5xx for the whole retry budget. */
    NOT_SENT,

    /** 429 from the per visitor or per network limit. */
    RATE_LIMITED,

    /** 413 `message_too_long` (over 20,000 characters). */
    MESSAGE_TOO_LONG,

    /** 429 `new_conversation_limit`. */
    NEW_CONVERSATION_LIMIT,

    /** 403 `monthly_conversation_limit`. */
    MONTHLY_CONVERSATION_LIMIT,

    /** 409: the conversation is resolved and the inbox takes no more messages in it. */
    CONVERSATION_RESOLVED,

    /** Any other refusal. */
    REJECTED,
}

/** The one action a Fynn button adds to a message (the team button). */
internal enum class FynnAction(val wire: String) { TEAM("team") }

/**
 * A message on its way: the bubble the thread shows at once. Its
 * [uuid] is lower case and is both the `client_assigned_uuid` and the
 * `Idempotency-Key` of every try, so the server stores it once.
 *
 * [conversationId] is the conversation it was written in: null while the
 * first send of a fresh thread has not created one yet. The bubble shows
 * only in that thread and every try goes to that conversation, never to the
 * one on screen at the time of the try.
 */
internal data class PendingSend(
    val uuid: String,
    val text: String,
    val createdAt: Long,
    val state: PendingState,
    val conversationId: String? = null,
    val error: SendFailure? = null,
    /** The message it answers; every try sends it and the bubble shows the quote. */
    val replyTo: ReplyQuote? = null,
    val action: FynnAction? = null,
    /** The visitor's tap on "Frage geklärt": [text] is the button's label. */
    val questionResolved: Boolean = false,
    /** The id of the resolve line a "Frage geklärt" became. */
    val lineId: String? = null,
    /** The help center article the question comes from; the bubble shows its card at once. */
    val helpArticle: HelpArticleRef? = null,
)

/** Why `send` took no text: nothing is ever dropped without one of these. */
internal enum class SendRefusal {
    /** Only whitespace. */
    EMPTY,

    /** The conversation takes no more messages: closed for this visitor, or over in a classic inbox. */
    CLOSED,

    /** No session yet, or the session failed (unknown widget, identity check). */
    NOT_READY,

    /** Over 20,000 characters, which the server refuses. */
    TOO_LONG,
}

/** What `send` did with a text, answered at once; delivery runs in the background. */
internal sealed class SendOutcome {
    /** A pending row with this [uuid] is in the thread and its delivery runs. */
    data class Queued(val uuid: String) : SendOutcome()

    /** The text waits in [ThreadState.heldMessages] for the step before the chat. */
    object Held : SendOutcome() {
        override fun toString(): String = "Held"
    }

    data class Refused(val reason: SendRefusal) : SendOutcome()
}

internal enum class UploadPhase {
    UPLOADING,

    /** Stored; it stays until the poll brings the file's row. */
    SENT,

    /** Refused; it stays with its [UploadState.error] until the UI dismisses it. */
    FAILED,
}

/**
 * A file on its way up, or one that was refused. [id] is lower case and
 * stays the same from the pick to the stored message: the caller's own id
 * when it gave one, so the UI can key its row (and a local preview) by it.
 */
internal data class UploadState(
    val id: String,
    val fileName: String,
    val phase: UploadPhase,
    val error: UploadErrorCode? = null,
    /** The part of the file that went out, 0 to 1; 1 once the server stored it. */
    val progress: Float = 0f,
    /** The file's type as it goes to the server, so the thread draws an image or a file row. */
    val mimeType: String? = null,
    /** When the visitor picked it (wall time, epoch milliseconds): where its row sorts in. */
    val startedAt: Long? = null,
    /** The stored message the upload became (the answer's `messageId`), set together with progress 1. */
    val messageId: String? = null,
)

/**
 * Why an upload was refused: the server's `code`, plus two the
 * client sets itself. The UI words each one; the server's English text is
 * never shown.
 */
internal enum class UploadErrorCode(val code: String) {
    FILE_TOO_LARGE("file_too_large"),
    FILE_TYPE_NOT_ALLOWED("file_type_not_allowed"),
    FILE_REJECTED("file_rejected"),
    SCAN_UNAVAILABLE("scan_unavailable"),
    STORAGE_UNAVAILABLE("storage_unavailable"),
    RATE_LIMITED("rate_limited"),
    UPLOADS_DISABLED("uploads_disabled"),

    /**
     * 409: this resolved conversation takes no more files. A 403 with the
     * same code closes the whole chat instead and shows no line.
     */
    CONVERSATION_CLOSED("conversation_closed"),

    /** Client side: a file can never be the first message. */
    CONVERSATION_NOT_STARTED("conversation_not_started"),

    /** Unknown or missing code, a network failure, an unreadable file. */
    UPLOAD_FAILED("upload_failed"),
    ;

    internal companion object {
        /** The code of a refusal; a 413 is always "too large". */
        fun from(error: ApiError): UploadErrorCode {
            if (error.status == 413) return FILE_TOO_LARGE
            return entries.firstOrNull { it.code == error.code } ?: UPLOAD_FAILED
        }
    }
}
