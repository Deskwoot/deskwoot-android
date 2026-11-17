package com.deskwoot.sdk.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.engine.ChatButton
import com.deskwoot.sdk.model.Attachment
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.SessionFailure
import com.deskwoot.sdk.model.ReplyQuote
import com.deskwoot.sdk.model.SendRefusal
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.model.UploadErrorCode
import com.deskwoot.sdk.ui.BackAware
import com.deskwoot.sdk.ui.InsetAware
import com.deskwoot.sdk.ui.PickReceiver
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Screen
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.UiMemory
import com.deskwoot.sdk.ui.design.ModalAccessibility
import com.deskwoot.sdk.ui.files.AttachSource
import com.deskwoot.sdk.ui.files.Picked
import com.deskwoot.sdk.ui.people.HeaderLine
import com.deskwoot.sdk.ui.text.MessageText
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PoweredBy
import com.deskwoot.sdk.ui.widgets.PrimaryButton
import com.deskwoot.sdk.ui.widgets.SubHeader
import com.deskwoot.sdk.ui.ComposerStyle
import com.deskwoot.sdk.ui.HeaderStyle
import com.deskwoot.sdk.ui.widgets.TopLine
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle
import kotlin.math.abs

/**
 * The thread: the header with whoever answers,
 * the rows in a bottom anchored RecyclerView (stable keys, DiffUtil off the
 * main thread, new rows fade up, a "New messages" chip when the visitor
 * scrolled up), the queue or waiting line, the composer or the closed
 * line, "End conversation" or "Start a new conversation", the branding.
 */
internal class ThreadScreen(ui: Ui, route: Route) : Screen(ui, route), InsetAware, BackAware, ThreadCallbacks, ComposerCallbacks, PickReceiver {
    private val ctx = ui.context
    private val c = ui.colors

    /** Klartext's and Empfang's header (Empfang's on the band); Direkt has its own, [direkt]. */
    private var header: SubHeader? = null
    private var direkt: com.deskwoot.sdk.ui.design.DirektHeader? = null
    private lateinit var headerView: View
    private var tray: com.deskwoot.sdk.ui.design.ArticleTray? = null
    private lateinit var list: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: ThreadAdapter
    private lateinit var chip: TextView
    private lateinit var announcer: TextView
    private lateinit var bottom: LinearLayout
    private lateinit var offlineLine: StatusLine
    private lateinit var queueLine: StatusLine
    private lateinit var composer: Composer
    private lateinit var closedLine: TextView
    private lateinit var endButton: TextView
    private lateinit var endFailed: TextView
    private lateinit var startNew: PrimaryButton
    private var csat: CsatCard? = null
    private var transcript: TranscriptBanner? = null
    private var shownTranscript: TranscriptState? = null
    private var topInset = 0

    private var thread: ThreadState? = null
    private var following = true
    private var followNext = false
    private var firstRows = true
    private val seenIncoming = HashSet<String>()
    private var replyTarget: ReplyQuote? = null
    private var replyAuthor: String? = null
    private var fixedBusy = false
    private var fixedFailedFor: String? = null
    private var endFailedShown = false
    private var shown = false
    private var draftKey: String? = null
    private var draftLoaded = false
    private var restoringDraft = false

    /** Text the screen puts back into the field (a refused send): kept as the draft, but no typing signal. */
    private var quietText = false
    private val dismissedUploads = HashSet<String>()
    private var offlineShown: String? = null

    /** A message's long press menu while it shows; TalkBack reads it alone. */
    private var shownMenu: PopupMenu? = null
    private val menuModal = ModalAccessibility()

    /** What screen readers hear next: the lines of one moment go out as one announcement. */
    private val announcements = ArrayList<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val flushAnnouncements = Runnable { flushAnnouncements() }
    private val clearAnnouncer = Runnable { if (this::announcer.isInitialized) announcer.text = "" }

    /** Kept across a rebuild or a recreate until the views exist again. */
    private var savedCsatRating = 0
    private var savedCsatComment = ""
    private var savedTranscriptEmail = ""

    /** The conversation this screen shows, or null for the session's current (a new one; Direkt's start page). */
    private val conversationId: String? = (route as? Route.Thread)?.conversationId

    override val barsOnBand: Boolean get() = ui.design.headerStyle(route) == HeaderStyle.BAND

    override val wantsBack: Boolean get() = this::composer.isInitialized && composer.emojiOpen

    override fun createView(): View {
        val root = LinearLayout(ctx).apply {
            id = R.id.dw_thread_screen
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.design.canvas(c))
        }
        if (ui.design.chatIsStart) {
            val h = com.deskwoot.sdk.ui.design.DirektHeader(ctx, ui)
            h.close.setOnClickListener { ui.nav.close() }
            direkt = h
            headerView = h
        } else {
            val h = SubHeader(ctx, ui, withFace = true, style = ui.design.headerStyle(route))
            h.back.setOnClickListener { ui.nav.back() }
            h.close.setOnClickListener { ui.nav.close() }
            header = h
            headerView = h
        }
        applyTopInset()
        root.addView(headerView, linear(MATCH, WRAP))

        val middle = FrameLayout(ctx)
        layoutManager = LinearLayoutManager(ctx).apply {
            // Direkt's chat starts with its greeting block at the top; a long thread still opens at its end.
            stackFromEnd = !ui.design.chatIsStart
            isSmoothScrollbarEnabled = false
        }
        adapter = ThreadAdapter(ui, this)
        list = RecyclerView(ctx).apply {
            id = R.id.dw_thread_list
            layoutManager = this@ThreadScreen.layoutManager
            adapter = this@ThreadScreen.adapter
            clipToPadding = false
            setPadding(ctx.dp(16), ctx.dp(18), ctx.dp(16), ctx.dp(14))
            itemAnimator = null
            setItemViewCacheSize(16)
            recycledViewPool.setMaxRecycledViews(ThreadAdapter.T_INCOMING, 24)
            recycledViewPool.setMaxRecycledViews(ThreadAdapter.T_VISITOR, 24)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Only a real scroll moves the follow state; a layout reports 0, 0 while rows arrive.
                    if (dy == 0) return
                    following = atEnd()
                    if (following) chip.visibility = View.GONE
                }
            })
            // The thread keeps its newest row in view while its height changes (keyboard, rotation) and the visitor follows.
            addOnLayoutChangeListener { _, _, top, _, bottomEdge, _, oldTop, _, oldBottom ->
                if (bottomEdge - top != oldBottom - oldTop && following) scrollToEnd(smooth = false)
            }
        }
        middle.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        ItemTouchHelper(SwipeToReply()).attachToRecyclerView(list)
        chip = TextView(ctx).apply {
            id = R.id.dw_new_messages_chip
            text = ui.t(R.string.dw_new_messages)
            textStyle(13f, 600, c.onBrand)
            gravity = Gravity.CENTER
            minHeight = ctx.dp(36)
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            background = ripple(c.onBrand(0.2), ctx.rounded(c.fill, 999f))
            elevation = ctx.dp(3).toFloat()
            visibility = View.GONE
            role(button = true)
            setOnClickListener { scrollToEnd(smooth = true) }
        }
        middle.addView(chip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ctx.dp(12) })
        // New messages are read out by screen readers (the web does not). A live region of 1 px:
        // it is not focusable, and it is empty again a few seconds after it spoke, so TalkBack finds nothing
        // to land on (announceForAccessibility is deprecated).
        announcer = TextView(ctx).apply {
            id = R.id.dw_announcer
            setTextColor(0)
            textSize = 1f
            isFocusable = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        middle.addView(announcer, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        root.addView(middle, linear(MATCH, 0, 1f))

        bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = if (ui.design.composerTopLine) TopLine(c.bg, c.line, ctx.dp(1)) else null
        }
        offlineLine = StatusLine(ctx, ui, withDot = false).apply {
            id = R.id.dw_offline_line
            background = ctx.rounded(c.bg2, 10f)
            setOnClickListener { ui.actions.refresh() }
            visibility = View.GONE
        }
        bottom.addView(offlineLine, linear(MATCH, WRAP).apply { setMargins(ctx.dp(12), ctx.dp(10), ctx.dp(12), 0) })
        queueLine = StatusLine(ctx, ui, withDot = true).apply {
            id = R.id.dw_queue_line
            visibility = View.GONE
            // Empfang draws the quiet lines on the panel, not on the brand tint.
            if (ui.design.composerStyle == ComposerStyle.EMPFANG) background = ctx.rounded(c.bg2, 10f)
        }
        bottom.addView(queueLine, linear(MATCH, WRAP).apply { setMargins(ctx.dp(12), ctx.dp(10), ctx.dp(12), 0) })
        composer = Composer(ctx, ui, this)
        replyTarget?.let { composer.setReply(replyAuthor, it.text, focus = false) }
        bottom.addView(composer, linear(MATCH, WRAP))
        closedLine = TextView(ctx).apply {
            id = R.id.dw_closed_line
            text = ui.t(R.string.dw_conversation_closed_read_only)
            textStyle(13f, 400, c.ink3, 1.4f)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(12), ctx.dp(14), ctx.dp(12), ctx.dp(4))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        bottom.addView(closedLine, linear(MATCH, WRAP))
        endButton = TextView(ctx).apply {
            id = R.id.dw_end_conversation
            text = ui.t(R.string.dw_end_conversation)
            textStyle(12.5f, 400, c.ink3)
            gravity = Gravity.CENTER
            // 40 to the eye, 48 to the finger: the pressed pill keeps 4 free above and below.
            minHeight = ctx.dp(48)
            minWidth = ctx.dp(48)
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            background = ripple(c.withAlpha(c.ink, 0.08), null, android.graphics.drawable.InsetDrawable(ctx.rounded(c.bg2, 8f), 0, ctx.dp(4), 0, ctx.dp(4)))
            role(button = true)
            setOnClickListener { endConversation() }
        }
        bottom.addView(endButton, linear(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(2) - ctx.dp(4); bottomMargin = -ctx.dp(4) })
        endFailed = TextView(ctx).apply {
            id = R.id.dw_end_failed
            text = ui.t(R.string.dw_end_failed)
            textStyle(12.5f, 400, c.error, 1.4f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        bottom.addView(endFailed, linear(MATCH, WRAP).apply { setMargins(ctx.dp(12), 0, ctx.dp(12), ctx.dp(2)) })
        startNew = PrimaryButton(ctx, ui, ui.t(R.string.dw_start_new_conversation)).apply {
            id = R.id.dw_start_new_conversation
            visibility = View.GONE
            setOnClickListener { ui.nav.startConversation(null, replaceCurrent = true) }
        }
        bottom.addView(startNew, linear(MATCH, WRAP).apply { setMargins(ctx.dp(16), ctx.dp(12), ctx.dp(16), 0) })
        // Direkt's article tray: articles that fit what a new visitor types, under the composer.
        if (ui.design.chatIsStart && ui.config.helpCenterEnabled) {
            tray = com.deskwoot.sdk.ui.design.ArticleTray(ctx, ui).also { bottom.addView(it.view, linear(MATCH, WRAP).apply { setMargins(ctx.dp(12), ctx.dp(6), ctx.dp(12), 0) }) }
        }
        if (ui.config.showBranding) bottom.addView(PoweredBy(ctx, ui), linear(MATCH, WRAP))
        root.addView(bottom, linear(MATCH, WRAP))
        return root
    }

    override fun onTopInset(top: Int) {
        topInset = top
        if (isBuilt) applyTopInset()
    }

    private fun applyTopInset() {
        val h = headerView
        h.setPadding(h.paddingLeft, topInset + ctx.dp(if (direkt != null) 6 else 4), h.paddingRight, h.paddingBottom)
    }

    // ── State ────────────────────────────────────────────────────────────

    override fun render(state: SessionState) {
        val t = state.thread ?: return
        val config = state.config ?: return
        // A screen for one conversation draws only that conversation.
        if (conversationId != null && t.conversationId != conversationId) return
        // A new screen for the session's thread, made right after "Start a new conversation", draws nothing of
        // the old thread: the state of the new one follows within this main loop turn.
        if (thread == null && conversationId == null && ui.session.statePending) {
            // The state flow drops a state equal to this one, so look again once the engine has published.
            ui.session.afterPendingState { if (thread == null && !destroyed) render(ui.session.state.value) }
            return
        }
        // Another conversation in this same screen: nothing of the old one stays (its card, banner, reply, errors).
        val previous = thread
        val before = previous?.conversationId
        if (before != null && t.conversationId != before) forgetConversation()
        thread = t
        val line = HeaderLine.of(t, config, ui.people, ui.strings, direkt = direkt != null)
        header?.let { h ->
            h.title.text = line.author.name
            h.face?.setFace(line.author.face)
            h.setLine(line.subtitle, line.online)
        }
        direkt?.let { h ->
            h.setLine(line.subtitle, line.online)
            // The other conversations with an unread reply.
            h.setBadge(state.conversations.count { it.unread > 0 && it.id != t.conversationId })
        }
        // Direkt asks a new visitor for their question.
        if (direkt != null) composer.placeholder = if (isFresh()) R.string.dw_write_your_question else R.string.dw_type_message

        // The draft of this conversation (or of the new one) goes into the field once.
        val key = t.conversationId
        if (!draftLoaded) {
            draftLoaded = true
            draftKey = key
            restoreDraft(key)
        } else if (draftKey != key) {
            // The new conversation got its id from the visitor's first message: what is typed now belongs to it.
            // Another conversation opened over a fresh chat (Direkt's list) keeps its own draft instead.
            val grewFromFresh = draftKey == null && previous != null && previous.conversationId == null &&
                (previous.pending.isNotEmpty() || previous.messages.any { it.role == com.deskwoot.sdk.model.MessageRole.VISITOR })
            if (!grewFromFresh) tray?.hide()
            if (grewFromFresh && UiMemory.draft(key).isEmpty()) {
                UiMemory.setDraft(key, composer.input.text?.toString().orEmpty())
                UiMemory.setDraft(null, "")
            } else {
                restoreDraft(key)
            }
            draftKey = key
        }

        // A message waits for the form or the email step: show the step. Not from a state the
        // engine already moved past (a gate that was just left takes its texts back).
        if (ui.actions.heldTexts(t).isNotEmpty() && t.gate != Gate.None && shown && !ui.session.statePending) {
            ui.nav.goTo(Route.Gate)
            return
        }

        submitRows(t)
        renderBottom(state, t)
        dismissRefusedUploads(t)
    }

    /** The per conversation state of this screen, when it starts to show another conversation. */
    private fun forgetConversation() {
        csat = null
        transcript = null
        shownTranscript = null
        cancelReply()
        fixedFailedFor = null
        endFailedShown = false
        dismissedUploads.clear()
        seenIncoming.clear()
        firstRows = true
        savedCsatRating = 0
        savedCsatComment = ""
        savedTranscriptEmail = ""
        chip.visibility = View.GONE
    }

    private fun restoreDraft(key: String?) {
        restoringDraft = true
        val draft = UiMemory.draft(key)
        if (composer.input.text?.toString() != draft) composer.setDraft(draft)
        restoringDraft = false
        showArticle(key)
    }

    /** The help center article waiting for this conversation's next message, above the field. */
    private fun showArticle(key: String?) {
        composer.setArticle(UiMemory.article(key)) {
            UiMemory.setArticle(key, null)
            showArticle(key)
        }
    }

    private fun submitRows(t: ThreadState) {
        val builder = ThreadRows(ui.config, ui.people, ui.strings, ui.formats, ui.design.greetingAsBubble)
        val built = builder.build(t, System.currentTimeMillis(), FixedButtonsUi(fixedBusy, fixedFailedFor))
        // Direkt opens on its greeting block above the first message.
        val rows = if (direkt != null) builder.direktTop(t, ui.session.state.value.conversations) + built else built
        val first = firstRows
        val newIncoming = newIncomingRows(rows, seenIncoming)
        val follow = first || following || followNext
        followNext = false
        adapter.submitList(rows) {
            if (first) {
                firstRows = false
                list.scrollToPosition(adapter.itemCount - 1)
                // Only rows that arrive after the first paint animate.
                list.post { list.itemAnimator = RowAnimator(ctx.dp(6).toFloat(), ui.reducedMotion) }
            } else if (follow) {
                scrollToEnd(smooth = true)
            } else if (newIncoming.isNotEmpty()) {
                chip.visibility = View.VISIBLE
            }
        }
        if (!first && newIncoming.isNotEmpty() && shown) {
            for (row in newIncoming) {
                val text = MessageText.preview(row.text, 200) ?: row.linkCard?.title
                    ?: row.attachments.firstOrNull()?.let { if (it.isImage) ui.t(R.string.dw_sdk_image) else ui.t(R.string.dw_file_label) }
                    ?: continue
                announce(ui.t(R.string.dw_sdk_announce, row.authorName, text))
            }
        }
    }

    /** Read out by screen readers; what comes within a moment goes out as one announcement, so none cuts off another. */
    private fun announce(text: String) {
        if (!shown) return
        announcements += text
        main.removeCallbacks(flushAnnouncements)
        main.postDelayed(flushAnnouncements, ANNOUNCE_BATCH_MILLIS)
    }

    private fun flushAnnouncements() {
        if (announcements.isEmpty() || !this::announcer.isInitialized) return
        var text = announcements.joinToString("\n")
        announcements.clear()
        // The same words again are still a change, so they are read again.
        if (text == announcer.text?.toString()) text += "\u200B"
        announcer.text = text
        main.removeCallbacks(clearAnnouncer)
        main.postDelayed(clearAnnouncer, ANNOUNCER_CLEAR_MILLIS)
    }

    private fun renderBottom(state: SessionState, t: ThreadState) {
        val canWrite = !t.closed && !t.inEndState
        // A cached thread stays readable without a connection: a quiet line says why sends wait (brief "Quality bar").
        val failedNow = state.phase == Phase.FAILED
        val offlineText = when {
            failedNow && state.failure == SessionFailure.SERVER -> ui.t(R.string.dw_sdk_server_error)
            state.offline || failedNow -> ui.t(R.string.dw_sdk_offline)
            else -> null
        }
        offlineLine.text.text = offlineText
        offlineLine.visibility = if (offlineText == null) View.GONE else View.VISIBLE
        // The line's text is read when it appears (a hidden live region says nothing).
        if (offlineText != null && offlineText != offlineShown) announce(offlineText)
        offlineShown = offlineText
        // The queue position wins over the handover wait.
        val queueText = when {
            !canWrite -> null
            t.queuePosition != null -> if (t.queuePosition <= 1) ui.t(R.string.dw_queue_next) else ui.t(R.string.dw_queue_position, t.queuePosition.toString())
            t.waitingForTeam -> ui.t(R.string.dw_waiting_next_agent)
            else -> null
        }
        queueLine.visibility = if (queueText == null) View.GONE else View.VISIBLE
        queueLine.text.text = queueText
        composer.visibility = if (canWrite) View.VISIBLE else View.GONE
        composer.setState(botReplying = t.botThinking, canSend = t.canSend, canAttach = t.canAttach)
        closedLine.visibility = if (t.closed) View.VISIBLE else View.GONE
        endButton.visibility = if (t.canEnd || t.ending) View.VISIBLE else View.GONE
        endButton.text = ui.t(if (t.ending) R.string.dw_ending else R.string.dw_end_conversation)
        endButton.isEnabled = !t.ending
        endFailed.visibility = if (endFailedShown && t.canEnd) View.VISIBLE else View.GONE
        startNew.visibility = if (t.inEndState && !t.closed) View.VISIBLE else View.GONE
        if (!canWrite && replyTarget != null) cancelReply()
        csat?.setState(t.csatState)
        transcript?.let { banner ->
            if (shownTranscript != t.transcriptState) {
                shownTranscript = t.transcriptState
                banner.show(t.transcriptState)
            }
        }
    }

    /** A refused upload's line shows for 6 s; then it leaves the session too. */
    private fun dismissRefusedUploads(t: ThreadState) {
        for (u in t.uploads) {
            val code = u.error ?: continue
            if (!dismissedUploads.add(u.id)) continue
            composer.showError(uploadText(code), untilTyped = false)
            list.postDelayed({ ui.actions.dismissUpload(u.id) }, 6_000)
        }
    }

    private fun uploadText(code: UploadErrorCode): String = when (code) {
        UploadErrorCode.FILE_TOO_LARGE -> ui.t(R.string.dw_upload_too_large, Math.round(ui.config.maxUploadBytes / (1024.0 * 1024.0)).toString())
        UploadErrorCode.FILE_TYPE_NOT_ALLOWED -> ui.t(R.string.dw_upload_type_not_allowed)
        UploadErrorCode.RATE_LIMITED -> ui.t(R.string.dw_upload_rate_limited)
        UploadErrorCode.SCAN_UNAVAILABLE -> ui.t(R.string.dw_upload_scan_unavailable)
        UploadErrorCode.FILE_REJECTED -> ui.t(R.string.dw_upload_rejected)
        UploadErrorCode.STORAGE_UNAVAILABLE -> ui.t(R.string.dw_upload_storage_unavailable)
        UploadErrorCode.UPLOADS_DISABLED -> ui.t(R.string.dw_uploads_disabled)
        UploadErrorCode.CONVERSATION_CLOSED -> ui.t(R.string.dw_upload_conversation_closed)
        UploadErrorCode.CONVERSATION_NOT_STARTED -> ui.t(R.string.dw_upload_needs_message)
        UploadErrorCode.UPLOAD_FAILED -> ui.t(R.string.dw_upload_failed)
    }

    // ── Scrolling ────────────────────────────────────────────────────────

    /** Within 40 dp of the newest row: the real position of the last row, not the scroll bar's estimate. */
    private fun atEnd(): Boolean {
        val count = adapter.itemCount
        if (count == 0) return true
        val last = layoutManager.findViewByPosition(count - 1) ?: return false
        return last.bottom - (list.height - list.paddingBottom) <= ctx.dp(40)
    }

    private fun scrollToEnd(smooth: Boolean) {
        val last = adapter.itemCount - 1
        if (last < 0) return
        following = true
        chip.visibility = View.GONE
        if (smooth && !ui.reducedMotion && abs(layoutManager.findLastVisibleItemPosition() - last) < 8) {
            list.smoothScrollToPosition(last)
        } else {
            list.scrollToPosition(last)
        }
    }

    override fun jumpTo(key: String) {
        val position = adapter.positionOf(key)
        if (position < 0) return
        following = false
        layoutManager.scrollToPositionWithOffset(position, list.height / 3)
        // The quoted row lights up briefly (1.8 s).
        list.post {
            val view = layoutManager.findViewByPosition(position) ?: return@post
            view.animate().cancel()
            view.alpha = 0.4f
            view.animate().alpha(1f).setDuration(if (ui.reducedMotion) 0 else 900).start()
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onShown() {
        shown = true
        // A step before the chat may have put text back into this conversation's draft.
        if (draftLoaded && UiMemory.draft(draftKey) != composer.input.text?.toString().orEmpty()) restoreDraft(draftKey)
        // The screen's conversation (a route the messenger restored, a trail the visitor went back along) opens
        // again unless the session knows it is not this visitor's: a saved route of another visitor never
        // reaches here, and this keeps any other stale route from opening someone else's conversation.
        conversationId?.let { id ->
            if (ui.session.state.value.thread?.conversationId != id && ui.actions.ownsConversation(id) != false) ui.actions.openConversation(id)
        }
        ui.actions.setVisible(true)
    }

    override fun onHidden() {
        shown = false
        ui.actions.setVisible(false)
        ui.actions.setTyping(false)
    }

    override fun handleBack(): Boolean = composer.closeEmoji()

    private var destroyed = false

    override fun destroy() {
        destroyed = true
        shownMenu?.dismiss()
        if (shown) onHidden()
        main.removeCallbacks(flushAnnouncements)
        main.removeCallbacks(clearAnnouncer)
        if (this::list.isInitialized) list.adapter = null
    }

    /** The reply target, the rating and its comment, the transcript address; the draft lives in UiMemory. */
    override fun saveState(out: Bundle) {
        replyTarget?.let { quote ->
            out.putString(KEY_REPLY_ID, quote.messageId)
            out.putString(KEY_REPLY_TEXT, quote.text.take(MAX_SAVED_QUOTE))
            out.putString(KEY_REPLY_AUTHOR, replyAuthor)
        }
        out.putInt(KEY_CSAT_RATING, csat?.chosen ?: savedCsatRating)
        out.putString(KEY_CSAT_COMMENT, (csat?.commentText ?: savedCsatComment).take(MAX_SAVED_TEXT))
        out.putString(KEY_TRANSCRIPT_EMAIL, (transcript?.emailText ?: savedTranscriptEmail).take(MAX_SAVED_EMAIL))
    }

    override fun restoreState(saved: Bundle) {
        val id = saved.getString(KEY_REPLY_ID)
        val text = saved.getString(KEY_REPLY_TEXT)
        if (id != null && text != null) {
            replyTarget = ReplyQuote(id, text)
            replyAuthor = saved.getString(KEY_REPLY_AUTHOR)
            if (isBuilt) composer.setReply(replyAuthor, text, focus = false)
        }
        savedCsatRating = saved.getInt(KEY_CSAT_RATING, 0)
        savedCsatComment = saved.getString(KEY_CSAT_COMMENT).orEmpty()
        savedTranscriptEmail = saved.getString(KEY_TRANSCRIPT_EMAIL).orEmpty()
        csat?.restore(savedCsatRating, savedCsatComment)
        transcript?.emailText = savedTranscriptEmail
    }

    // ── ComposerCallbacks ────────────────────────────────────────────────

    override fun send(text: String) {
        val quote = replyTarget
        cancelReply()
        UiMemory.setDraft(draftKey, "")
        followNext = true
        // The article goes with this message and leaves the field.
        val article = UiMemory.article(draftKey)
        UiMemory.setArticle(draftKey, null)
        showArticle(draftKey)
        ui.actions.send(text, quote, article) { refused, reason -> refusedSend(refused, reason, quote) }
    }

    /** Nothing the visitor wrote is lost: the text goes back into the field with a reason (a closed chat shows its own line). */
    private fun refusedSend(text: String, reason: SendRefusal, quote: ReplyQuote?) {
        if (reason == SendRefusal.EMPTY) return
        if (reason == SendRefusal.CLOSED) return
        putBack(text)
        if (quote != null && replyTarget == null) {
            replyTarget = quote
            composer.setReply(replyAuthor, quote.text)
        }
        composer.showError(ui.t(if (reason == SendRefusal.TOO_LONG) R.string.dw_sdk_too_long else R.string.dw_send_failed), untilTyped = true)
    }

    /** Text back into the field in front of what is there: the draft keeps it, but nobody is typing. */
    private fun putBack(text: String) {
        quietText = true
        try {
            composer.restore(text)
        } finally {
            quietText = false
        }
    }

    override fun textChanged(text: String) {
        if (restoringDraft) return
        UiMemory.setDraft(draftKey, text)
        if (!quietText) ui.actions.setTyping(text.isNotBlank())
        tray?.typed(text, fresh = isFresh())
    }

    /** A new chat: no conversation yet, or one the visitor has not written in. */
    private fun isFresh(): Boolean {
        val t = thread ?: return true
        return t.conversationId == null || t.messages.none { it.role == com.deskwoot.sdk.model.MessageRole.VISITOR } && t.pending.isEmpty()
    }

    override fun attach(anchor: View) {
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, ui.t(R.string.dw_sdk_photos))
        if (ui.nav.cameraAvailable()) menu.menu.add(0, 2, 1, ui.t(R.string.dw_sdk_camera))
        // The same words for the 3 sources as in the iOS SDK ("Photos" is Android's name for the photo picker).
        menu.menu.add(0, 3, 2, ui.t(R.string.dw_sdk_files))
        menu.setOnMenuItemClickListener { item ->
            val source = when (item.itemId) {
                1 -> AttachSource.PHOTOS
                2 -> AttachSource.CAMERA
                else -> AttachSource.FILES
            }
            ui.nav.pick(source, ui.config.allowedFileTypes, route)
            true
        }
        composer.showMenu(menu)
    }

    /** The file picked for this thread, also when it arrives in a recreated Activity. */
    override fun picked(picked: Picked) {
        followNext = true
        ui.actions.upload(picked.source, picked.name, picked.mimeType, picked.temporary) { /* the row and the line come from the state */ }
    }

    override fun cancelReply() {
        replyTarget = null
        replyAuthor = null
        composer.setReply(null, null)
    }

    // ── ThreadCallbacks ──────────────────────────────────────────────────

    override fun openUrl(url: String) = ui.nav.openUrl(url)

    override fun openAttachment(attachment: Attachment) {
        if (attachment.isImage) {
            ui.nav.showImage(attachment.id, attachment.fileUrl, attachment.fileName)
        } else {
            ui.nav.openFile(attachment.id, attachment.fileUrl, attachment.fileName, attachment.contentType)
        }
    }

    override fun choice(label: String, greeting: Boolean) {
        followNext = true
        ui.actions.press(if (greeting) ChatButton.Greeting(label) else ChatButton.Choice(label), ::refusedButton) {}
    }

    override fun resolvedButton() {
        val id = thread?.fynnEndMessageId
        fixedBusy = true
        fixedFailedFor = null
        followNext = true
        ui.actions.press(ChatButton.FynnResolved, ::refusedButton) {
            fixedFailedFor = id
            thread?.let { submitRows(it) }
        }
        thread?.let { submitRows(it) }
        list.postDelayed({
            fixedBusy = false
            thread?.let { submitRows(it) }
        }, 600)
    }

    override fun teamButton(label: String) {
        followNext = true
        ui.actions.press(ChatButton.FynnTeam(label), ::refusedButton) {}
    }

    /** A button the session would not take: the error line; the buttons stay. */
    private fun refusedButton(reason: SendRefusal) {
        if (reason != SendRefusal.CLOSED) composer.showError(ui.t(R.string.dw_send_failed), untilTyped = true)
    }

    override fun retry(uuid: String) {
        followNext = true
        ui.actions.retry(uuid)
    }

    override fun discard(uuid: String, text: String?) {
        ui.actions.discard(uuid)
        text?.let(::putBack)
        composer.focusField()
    }

    override fun reply(row: ThreadRow) {
        val (id, text, author) = when (row) {
            is IncomingRow -> Triple(row.messageId, row.text?.let(MessageText::quotePreview) ?: row.linkCard?.title, ui.people.businessName)
            is VisitorRow -> Triple(row.messageId, row.text?.let(MessageText::quotePreview), ui.t(R.string.dw_you))
            else -> return
        }
        id ?: return
        val quoted = text ?: ui.t(R.string.dw_file_label)
        replyTarget = ReplyQuote(id, quoted)
        replyAuthor = author
        composer.setReply(author, quoted)
    }

    override fun messageMenu(row: ThreadRow, copyText: String?, anchor: View, opener: View): Boolean {
        val canReply = MessageMenu.canReply(row)
        if (copyText == null && !canReply) return false
        shownMenu?.dismiss()
        // As on iOS: Copy, then Reply; the visitor's messages open it at their end.
        val menu = PopupMenu(ctx, anchor, if (row is VisitorRow) Gravity.END else Gravity.START)
        if (copyText != null) menu.menu.add(0, MENU_COPY, 0, ui.t(R.string.dw_sdk_copy))
        if (canReply) menu.menu.add(0, MENU_REPLY, 1, ui.t(R.string.dw_reply))
        var replied = false
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_COPY -> copyText?.let(::copy)
                MENU_REPLY -> {
                    replied = true
                    reply(row)
                }
            }
            true
        }
        menu.setOnDismissListener {
            if (shownMenu === menu) shownMenu = null
            // After Reply the field has the focus; after Copy or nothing TalkBack goes back to the message.
            menuModal.close(returnFocus = !replied)
        }
        shownMenu = menu
        menu.show()
        menuModal.open(listOf(anchor.rootView.findViewById<View>(android.R.id.content) ?: anchor.rootView), opener = opener, focus = null)
        return true
    }

    /** The message's plain text on the clipboard, as iOS copies it; from Android 13 on the system confirms the copy itself. */
    override fun copy(text: String) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    override fun bindCsat(holder: FrameLayout) {
        val card = csat ?: CsatCard(ctx, ui) { rating, comment -> ui.actions.submitCsat(rating, comment) {} }.also {
            csat = it
            it.restore(savedCsatRating, savedCsatComment)
        }
        (card.parent as? FrameLayout)?.removeView(card)
        holder.removeAllViews()
        holder.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        thread?.let { card.setState(it.csatState) }
    }

    override fun bindTranscript(holder: FrameLayout) {
        val banner = transcript ?: TranscriptBanner(ctx, ui) { email -> ui.actions.requestTranscript(email) {} }.also {
            transcript = it
            it.emailText = savedTranscriptEmail
        }
        (banner.parent as? FrameLayout)?.removeView(banner)
        holder.removeAllViews()
        holder.addView(banner, FrameLayout.LayoutParams(MATCH, WRAP))
        thread?.let {
            shownTranscript = it.transcriptState
            banner.show(it.transcriptState)
        }
    }

    private fun endConversation() {
        endFailedShown = false
        ui.actions.endConversation {
            endFailedShown = true
            endFailed.visibility = View.VISIBLE
        }
    }

    // ── Swipe to reply ───────────────────────────────────────────────────

    /** The bubble follows the finger toward the reading end; past 56 dp a release replies; it resists up to 72 dp. */
    private inner class SwipeToReply : ItemTouchHelper.Callback() {
        private val trigger = ctx.dp(56).toFloat()
        private val limit = ctx.dp(72).toFloat()
        private var armed = false
        private val arrow: Drawable = ctx.icon(R.drawable.dw_ic_reply, c.ink2)
        private val arrowBg = ctx.rounded(c.bg2, 999f)

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            if (viewHolder.itemView.getTag(R.id.dw_bubble) != true) return 0
            return makeMovementFlags(0, ItemTouchHelper.END)
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = Float.MAX_VALUE

        override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

        override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            val sign = if (dX >= 0) 1f else -1f
            val raw = abs(dX)
            val shown = if (raw <= trigger) raw else trigger + (raw - trigger) * 0.35f
            val clamped = minOf(shown, limit) * sign
            if (isCurrentlyActive) {
                if (raw >= trigger) armed = true
            } else if (armed) {
                armed = false
                adapter.rowAt(viewHolder.bindingAdapterPosition)?.let { reply(it) }
            }
            // The arrow grows and fades in behind the bubble on the side it left.
            val progress = (abs(clamped) / trigger).coerceIn(0f, 1f)
            if (progress > 0f) {
                val item = viewHolder.itemView
                val size = ctx.dp(28)
                val cx = if (sign > 0) item.left + ctx.dp(8) + size / 2 else item.right - ctx.dp(8) - size / 2
                val cy = (item.top + item.bottom) / 2
                val s = (size * (0.6f + 0.4f * progress)).toInt()
                arrowBg.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
                arrowBg.alpha = (255 * progress).toInt()
                arrowBg.draw(c)
                val i = (s * 0.57f).toInt()
                arrow.setBounds(cx - i / 2, cy - i / 2, cx + i / 2, cy + i / 2)
                arrow.alpha = (255 * progress).toInt()
                arrow.draw(c)
            }
            super.onChildDraw(c, recyclerView, viewHolder, clamped, dY, actionState, isCurrentlyActive)
        }
    }

    private companion object {
        const val MENU_COPY = 1
        const val MENU_REPLY = 2
        const val KEY_REPLY_ID = "reply_id"
        const val KEY_REPLY_TEXT = "reply_text"
        const val KEY_REPLY_AUTHOR = "reply_author"
        const val KEY_CSAT_RATING = "csat_rating"
        const val KEY_CSAT_COMMENT = "csat_comment"
        const val KEY_TRANSCRIPT_EMAIL = "transcript_email"

        /** What a saved state may carry, so a huge paste never makes the bundle too large. */
        const val MAX_SAVED_QUOTE = 400
        const val MAX_SAVED_TEXT = 4_000
        const val MAX_SAVED_EMAIL = 320

        /** Lines that come within this time are read out together. */
        const val ANNOUNCE_BATCH_MILLIS = 250L

        /** The announcer empties again after this time, so it holds nothing to land on. */
        const val ANNOUNCER_CLEAR_MILLIS = 4_000L
    }
}

/**
 * New rows fade up 6 dp in 160 ms; a typing row filled by its answer
 * crossfades in place. The rise moves the row's content,
 * so it never competes with the fade DefaultItemAnimator runs on the row.
 */
internal class RowAnimator(private val rise: Float, reduced: Boolean) : DefaultItemAnimator() {
    init {
        addDuration = if (reduced) 0 else 160
        removeDuration = if (reduced) 0 else 120
        changeDuration = if (reduced) 0 else 160
        moveDuration = if (reduced) 0 else 160
        supportsChangeAnimations = true
    }

    private fun content(holder: RecyclerView.ViewHolder): View? = (holder.itemView as? android.view.ViewGroup)?.getChildAt(0)

    override fun onAddStarting(item: RecyclerView.ViewHolder) {
        val child = content(item) ?: return
        child.animate().cancel()
        child.translationY = rise
        child.animate().translationY(0f).setDuration(addDuration).start()
    }

    override fun onAddFinished(item: RecyclerView.ViewHolder) {
        content(item)?.let {
            it.animate().cancel()
            it.translationY = 0f
        }
    }
}
