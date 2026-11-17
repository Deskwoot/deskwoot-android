package com.deskwoot.sdk.model

/** Whether the session is usable: config, identity and the restore lookup are done (READY) or could not be (FAILED). */
internal enum class Phase { LOADING, READY, FAILED }

/** Why the session failed, so the UI can word each case and the log can name it. */
internal enum class SessionFailure {
    /** The server does not know the widget token (404 `Invalid widget token`). */
    UNKNOWN_WIDGET,

    /** No answer at all, or no usable one, while the session started. */
    NO_CONNECTION,

    /** The host app's user could not be verified and no stored identity stands in. */
    IDENTITY_CHECK,

    /** Any other refusal of the server. */
    SERVER,
}

/** Who the visitor is to the inbox. Never carries the bearer, which is a secret. */
internal sealed class VisitorIdentity {
    /** Nobody wrote yet: a placeholder in memory, nothing stored. */
    object AnonymousNew : VisitorIdentity()

    /** An anonymous visitor with a bearer the server minted earlier. */
    object AnonymousKnown : VisitorIdentity()

    /** The host app's signed in user, proven by the identity hash. */
    data class Verified(val externalId: String) : VisitorIdentity()
}

/** A conversation's status as the visitor sees it. */
internal enum class ConversationStatus {
    OPEN,
    PENDING,
    SNOOZED,
    RESOLVED,
    ;

    internal companion object {
        /** Null for nothing; an unknown value reads as live, since a visitor never sees SPAM. */
        fun parse(text: String?): ConversationStatus? {
            val value = text?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.name == value } ?: OPEN
        }
    }
}

/** The step before the first message. */
internal sealed class Gate {
    object None : Gate()

    /** The inbox's form, without its email questions when the server knows an email. */
    data class PreChatForm(val fields: List<PreChatField>) : Gate()

    object EmailCollect : Gate()
}

internal enum class CsatState { IDLE, SENDING, SUBMITTED, FAILED }

internal enum class TranscriptState { IDLE, SENDING, SENT, NEEDS_EMAIL, FAILED }

/** A problem with one answer of the steps before the chat. */
internal enum class FieldProblem { REQUIRED, INVALID_EMAIL, BLOCKED_EMAIL }

/** What "Frage geklärt" came to. */
internal enum class ResolveResult {
    RESOLVED,

    /** 409 `not_fynn_last`: a person answered meanwhile. No error line: the poll shows their reply. */
    NOT_FYNN_LAST,
    FAILED,
}

/**
 * The conversation on screen, or the one last open: rows, sends, uploads
 * and every flag the thread view needs. The fields after [ending] are
 * derived by the engine the way the web messenger derives them, so the UI
 * reads them instead of repeating the rules.
 */
internal data class ThreadState(
    val conversationId: String?,
    /** Null before the first message. */
    val status: ConversationStatus?,
    /** Server rows, merged and ordered; the bot's empty typing placeholder is left out (it is [botThinking]). */
    val messages: List<Message>,
    val pending: List<PendingSend>,
    val uploads: List<UploadState>,
    /** Continuous chat: the conversations in this one history, oldest first, for its dividers. */
    val segments: List<ThreadSegment>,
    val botThinking: Boolean,
    /** The kind of tool Fynn runs right now (`bot_tool`), shown as a line; null while he only types. */
    val botTool: String?,
    val agentTyping: Boolean,
    val queuePosition: Int?,
    val waitingForTeam: Boolean,
    val resolved: Boolean,
    /** The team closed the chat for this visitor: read only. */
    val closed: Boolean,
    val continuesAfterResolve: Boolean,
    val endedLocally: Boolean,
    /** The poll's `showCsatSurvey`. */
    val showCsat: Boolean,
    val csatState: CsatState,
    val emailKnown: Boolean,
    /**
     * The texts the visitor wrote while a step before the chat was due, in
     * the order written; they go out in that order once the step is
     * answered, all into one conversation.
     */
    val heldMessages: List<String>,
    /** Fields the server asked for after a refused first message. */
    val missingFields: List<String>,
    val transcriptState: TranscriptState,
    val ending: Boolean,
    // Derived
    val hasAgentReply: Boolean,
    val gate: Gate,
    /** Classic inbox only: the conversation is over and the composer gives way. */
    val inEndState: Boolean,
    val canSend: Boolean,
    val canAttach: Boolean,
    val canEnd: Boolean,
    /** The rating card is due. */
    val csatDue: Boolean,
    val transcriptOffered: Boolean,
    /** Fynn's newest answer whose fixed buttons show. */
    val fynnEndMessageId: String?,
    /** The bot's newest reply whose choice buttons show. */
    val choicesMessageId: String?,
    /**
     * The id of the bot's typing placeholder row while it types, so the UI
     * can key its typing indicator by the row that will carry the answer and
     * fill it in place. Null while only the hold after a send keeps
     * the dots up, or while nobody types.
     */
    val botTypingRowId: String?,
)

/** Everything a screen of the SDK draws from, published at most once per main loop turn. */
internal data class SessionState(
    val phase: Phase,
    val config: WidgetConfig?,
    val identity: VisitorIdentity,
    /** The visitor's conversations, newest first, with their unread replies. */
    val conversations: List<ConversationSummary>,
    val thread: ThreadState?,
    /** Replies the visitor has not seen, across conversations. */
    val unreadCount: Int,
    /** The language tag the UI draws its strings in: the inbox's fixed language, else the app's. */
    val locale: String,
    /**
     * The last call to the server got no answer at all (no network, a
     * timeout); the next answered call clears it. The UI shows a quiet line,
     * the poll keeps trying.
     */
    val offline: Boolean = false,
    /** Why [phase] is FAILED; null in every other phase. */
    val failure: SessionFailure? = null,
    /** Goes up on every wipe of the visitor (logout, another user, a refused identity): the UI drops what it keeps of them. */
    val visitorGeneration: Int = 0,
    /** False from every identity change until the visitor's conversations have loaded (or there is nothing to load). */
    val visitorLoaded: Boolean = false,
    /**
     * Who the visitor is has settled: no identify or verification on its
     * way, and no stored signed in user waiting for the app to name them.
     */
    val identitySettled: Boolean = false,
) {
    internal companion object {
        fun initial(locale: String): SessionState = SessionState(
            phase = Phase.LOADING,
            config = null,
            identity = VisitorIdentity.AnonymousNew,
            conversations = emptyList(),
            thread = null,
            unreadCount = 0,
            locale = locale,
        )
    }
}
