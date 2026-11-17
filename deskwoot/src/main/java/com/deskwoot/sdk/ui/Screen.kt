package com.deskwoot.sdk.ui

import android.os.Bundle
import android.view.View
import com.deskwoot.sdk.model.SessionState

/**
 * One screen of the messenger: builds its view from [ui], draws the
 * session state it is given and says when it is on screen. Screens hold no
 * state of the conversation themselves; what must survive a rebuild (a
 * draft, a search) lives in [UiMemory] or the saved state.
 */
internal abstract class Screen(val ui: Ui, route: Route) {
    var route: Route = route
        private set

    private var built: View? = null

    val view: View get() = built ?: createView().also { built = it }

    val isBuilt: Boolean get() = built != null

    /** A screen over the one below it, which stays visible (Direkt's sheets over the chat). */
    open val isOverlay: Boolean get() = false

    /** The status bar sits on Empfang's colored band: its icons take the band's text color. */
    open val barsOnBand: Boolean get() = false

        /** The part of the view that moves on a view change; headers keep still. */
    open val body: View? get() = null

    protected abstract fun createView(): View

    /** The latest session state; called on the main thread at most once per change. */
    open fun render(state: SessionState) = Unit

    /** On screen (top of the stack, Activity resumed) or not. */
    open fun onShown() = Unit

    open fun onHidden() = Unit

    /** The screen leaves the stack (back, replaced); [destroy] follows. Not called when the views are only rebuilt. */
    open fun onRemoved() = Unit

    /** The screen's views go: stop work, release them. */
    open fun destroy() = Unit

    /** Something inside takes the back step (an open panel); true when it did. */
    open fun handleBack(): Boolean = false

    /** Whether this screen can show [next] in place (the start page and the help share Klartext's top bar). */
    open fun canShow(next: Route): Boolean = false

    /** Shows [next] in place; only called when [canShow] said yes. */
    open fun show(next: Route) {
        route = next
    }

    open fun saveState(out: Bundle) = Unit

    open fun restoreState(saved: Bundle) = Unit
}

/**
 * A screen that sends files. A pick it asked for comes back to it by its
 * route, not through a closure of the screen that asked: the Activity may
 * have been recreated, or the process restarted, while the picker or the
 * camera was open.
 */
internal interface PickReceiver {
    fun picked(picked: com.deskwoot.sdk.ui.files.Picked)
}

/** What survives a rebuild of the screens and a closed messenger: drafts per conversation, the help search. */
internal object UiMemory {
    private val drafts = HashMap<String, String>()

    /** The draft of a conversation, or of the new one ([NEW]). */
    fun draft(conversationId: String?): String = drafts[conversationId ?: NEW].orEmpty()

    fun setDraft(conversationId: String?, text: String) {
        if (text.isEmpty()) drafts.remove(conversationId ?: NEW) else drafts[conversationId ?: NEW] = text
    }

    var helpQuery: String = ""

    /** The help center article a new question comes from, per draft key, shown above the field until sent or taken off. */
    private val articles = HashMap<String, com.deskwoot.sdk.model.HelpArticleRef>()

    fun article(conversationId: String?): com.deskwoot.sdk.model.HelpArticleRef? = articles[conversationId ?: NEW]

    fun setArticle(conversationId: String?, article: com.deskwoot.sdk.model.HelpArticleRef?) {
        if (article == null) articles.remove(conversationId ?: NEW) else articles[conversationId ?: NEW] = article
    }

        /** Empfang's search card asks the help to open with its field focused. */
    var focusHelpSearch: Boolean = false

    /** The articles voted on in this process: their slug and the vote. */
    val votes: HashMap<String, Boolean> = HashMap()

    fun save(out: Bundle) {
        val b = Bundle()
        // A huge paste never makes the saved state too large for the system: drafts up to a budget.
        var room = MAX_SAVED_CHARS
        for ((k, v) in drafts) {
            if (v.length > room) continue
            b.putString(k, v)
            room -= v.length
        }
        out.putBundle(KEY_DRAFTS, b)
        out.putString(KEY_QUERY, helpQuery.take(MAX_SAVED_QUERY))
    }

    /**
     * The drafts and the search of an earlier saved state. The messenger calls
     * it only once it knows that state is the current visitor's: after a
     * process death the visitor may have changed.
     */
    fun restore(saved: Bundle?) {
        val b = saved?.getBundle(KEY_DRAFTS) ?: return
        for (key in b.keySet()) b.getString(key)?.let { if (!drafts.containsKey(key)) drafts[key] = it }
        if (helpQuery.isEmpty()) helpQuery = saved.getString(KEY_QUERY).orEmpty()
    }

    /** What [save] wrote into [saved], into [out] as it is: a saved state still waiting for that decision goes on. */
    fun carry(saved: Bundle?, out: Bundle) {
        saved ?: return
        saved.getBundle(KEY_DRAFTS)?.let { out.putBundle(KEY_DRAFTS, it) }
        saved.getString(KEY_QUERY)?.let { out.putString(KEY_QUERY, it) }
    }

    /** Logout or another user: nothing of the old visitor stays. */
    fun clear() {
        drafts.clear()
        articles.clear()
        focusHelpSearch = false
        helpQuery = ""
        votes.clear()
    }

    const val NEW: String = "\u0000new"

    private const val KEY_DRAFTS = "dw_drafts"
    private const val KEY_QUERY = "dw_help_query"

    /** At most this much draft text goes into the saved state (a full message is 20,000). */
    private const val MAX_SAVED_CHARS = 50_000
    private const val MAX_SAVED_QUERY = 500
}
