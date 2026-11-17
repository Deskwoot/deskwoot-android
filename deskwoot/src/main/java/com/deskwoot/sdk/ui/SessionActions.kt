package com.deskwoot.sdk.ui

import com.deskwoot.sdk.engine.ChatButton
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.engine.UploadSource
import com.deskwoot.sdk.model.FieldProblem
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.ReplyQuote
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.model.SendRefusal
import com.deskwoot.sdk.model.ResolveResult
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.model.UploadErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one place the screens call the session's actions. Nothing here waits
 * for a delivery: a send, a button, a form answer go out in the background
 * and the screens follow the session state. Results that only matter later
 * (a resolve, an upload, an end, a rating) come back through a callback on
 * the main thread.
 */
internal class SessionActions(private val session: ChatSession, private val scope: CoroutineScope) {
    /**
     * Sends [text]; the session answers at once. A refusal hands the text
     * back to [onRefused] with the reason, so nothing the visitor wrote is
     * lost; a session that is not ready also starts again.
     */
    fun send(text: String, replyTo: ReplyQuote?, onRefused: (String, SendRefusal) -> Unit) = send(text, replyTo, null, onRefused)

    /** [article]: the help center article the question comes from. */
    fun send(text: String, replyTo: ReplyQuote?, article: com.deskwoot.sdk.model.HelpArticleRef?, onRefused: (String, SendRefusal) -> Unit) {
        val outcome = session.send(text, null, replyTo, article)
        if (outcome is SendOutcome.Refused) {
            if (outcome.reason == SendRefusal.NOT_READY) session.refresh()
            onRefused(text, outcome.reason)
        }
    }

    /**
     * A choice, start or team button: its label goes out as the visitor's
     * message. "Frage geklärt" resolves in the background; [onResolveFailed]
     * hears a failure (the buttons come back with the error line).
     */
    fun press(button: ChatButton, onRefused: (SendRefusal) -> Unit, onResolveFailed: () -> Unit) {
        if (button == ChatButton.FynnResolved) {
            scope.launch { if (session.markResolved() == ResolveResult.FAILED) onResolveFailed() }
            return
        }
        val outcome = session.pressButton(button)
        if (outcome is SendOutcome.Refused) onRefused(outcome.reason)
    }

    fun retry(uuid: String) = session.retry(uuid)

    fun discard(uuid: String) = session.discard(uuid)

    /** The texts the visitor wrote while a step before the chat was due, oldest first. */
    fun heldTexts(thread: ThreadState?): List<String> = thread?.heldMessages.orEmpty()

    /** The form's answers: the problems per field name now, empty when accepted (the held texts then go out in the background). */
    fun submitPreChat(values: Map<String, String>): Map<String, FieldProblem> = session.submitPreChat(values)

    /** The email step: the problem now, or null when accepted. */
    fun submitEmail(email: String): FieldProblem? = session.submitEmail(email)

    fun cancelGate() = session.cancelGate()

    fun gateBeforeChat(): Gate = session.gateBeforeChat()

    fun startNewConversation() = session.startNewConversation()

    fun openConversation(id: String) = session.openConversation(id)

    /**
     * Whether [id] (the in app card's conversation) is one of this visitor's:
     * true when the restore, the list or a write of this visitor named it,
     * false when they settled and nothing did, null while that cannot be said
     * yet. The messenger opens it only on true, shows the start page on false
     * and keeps it waiting on null.
     */
    fun ownsConversation(id: String): Boolean? = session.ownsConversation(id)

    fun setVisible(onScreen: Boolean) = session.setVisible(onScreen)

    fun setTyping(typing: Boolean) = session.setTyping(typing)

    fun refresh() = session.refresh()

    /** [temporary]: the SDK's own copy of the file, deleted once the upload ended either way. */
    fun upload(source: UploadSource, fileName: String, mimeType: String, temporary: java.io.File? = null, onRefused: (UploadErrorCode) -> Unit) {
        scope.launch {
            val code = try {
                session.upload(source, fileName, mimeType)
            } finally {
                if (temporary != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { temporary.delete() }
            }
            if (code != null) onRefused(code)
        }
    }

    fun dismissUpload(id: String) = session.dismissUpload(id)

    fun endConversation(onFailed: () -> Unit) {
        scope.launch { if (!session.endConversation()) onFailed() }
    }

    fun submitCsat(rating: Int, comment: String?, onDone: (Boolean) -> Unit) {
        scope.launch { onDone(session.submitCsat(rating, comment)) }
    }

    fun requestTranscript(email: String?, onDone: (TranscriptState) -> Unit) {
        scope.launch { onDone(session.requestTranscript(email)) }
    }
}
