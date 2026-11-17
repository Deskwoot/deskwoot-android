package com.deskwoot.sdk.ui.thread

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.design.ModalAccessibility
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.backgroundKeepingPadding
import com.deskwoot.sdk.ui.widgets.decorative
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.edged
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.oneLine
import com.deskwoot.sdk.ui.widgets.oval
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle

/** What the composer asks for. */
internal interface ComposerCallbacks {
    fun send(text: String)

    fun textChanged(text: String)

    fun attach(anchor: View)

    fun cancelReply()
}

/**
 * The composer: lines above the bar (an error, a send
 * that was refused), the reply bar, and the bar itself: the field (14.5,
 * 20 per line, up to 5 lines then it scrolls), emoji, attach and send. The
 * buttons are 38 to the eye and 48 to the finger. Return makes a new line
 * (Android's chat convention); the send button sends.
 */
@SuppressLint("ViewConstructor")
internal class Composer(context: Context, private val ui: Ui, private val callbacks: ComposerCallbacks) : LinearLayout(context) {
    val input = EditText(context)
    private val status = TextView(context)
    private val replyBar = LinearLayout(context)
    private val article = LinearLayout(context)
    private val articleTitle = TextView(context)
    private var articleRemoved: (() -> Unit)? = null
    private val replyAuthor = TextView(context)
    private val replyText = TextView(context)
    private val bar = LinearLayout(context)
    private val emoji = ImageView(context)
    private val attach = ImageView(context)
    private val send = ImageView(context)
    private var hasText = false
    private var enabledToSend = true
    private var disabled = false
    private var panel: PopupWindow? = null

    /** TalkBack reads the emoji panel alone while it is open; the same for the attachment menu. */
    private val emojiModal = ModalAccessibility()
    private val menuModal = ModalAccessibility()
    private val hideStatus = Runnable { status.visibility = View.GONE }

    val emojiOpen: Boolean get() = panel?.isShowing == true

    init {
        id = R.id.dw_composer
        orientation = VERTICAL
        val c = ui.colors
        // Klartext 10/12/0, Empfang 10/12/4, Direkt 6/12/0.
        when (ui.design.composerStyle) {
            com.deskwoot.sdk.ui.ComposerStyle.KLARTEXT -> setPaddingRelative(dp(12), dp(10), dp(12), 0)
            com.deskwoot.sdk.ui.ComposerStyle.EMPFANG -> setPaddingRelative(dp(12), dp(10), dp(12), dp(4))
            com.deskwoot.sdk.ui.ComposerStyle.DIREKT -> setPaddingRelative(dp(12), dp(6), dp(12), 0)
        }

        status.id = R.id.dw_composer_error
        status.textStyle(12f, 400, c.error, 1.4f)
        status.setPadding(dp(14), dp(6), dp(14), dp(6))
        status.visibility = View.GONE
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        addView(status, linear(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // The reply bar: a 3 dp brand stripe, the author and one line of the quoted text, a close button.
        replyBar.id = R.id.dw_reply_bar
        replyBar.orientation = HORIZONTAL
        replyBar.gravity = Gravity.CENTER_VERTICAL
        replyBar.setPaddingRelative(dp(10 + 3), dp(6), dp(4), dp(6))
        replyBar.background = QuoteBackground(c.bg2, c.brand, dp(3).toFloat(), dp(8).toFloat())
        val replyColumn = LinearLayout(context).apply { orientation = VERTICAL }
        replyAuthor.textStyle(12f, 600, c.brandText)
        replyAuthor.oneLine()
        replyText.textStyle(13f, 400, c.ink2)
        replyText.oneLine()
        replyText.contentText()
        replyColumn.addView(replyAuthor)
        replyColumn.addView(replyText)
        replyBar.addView(replyColumn, linear(0, WRAP, 1f))
        val cancel = ImageView(context).apply {
            id = R.id.dw_reply_cancel
            setImageDrawable(context.icon(R.drawable.dw_ic_close_bold, c.ink3))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            contentDescription = ui.t(R.string.dw_cancel_reply)
            background = ripple(c.withAlpha(c.ink, 0.1), null, context.oval(c.bg3))
            role(button = true)
            setOnClickListener { callbacks.cancelReply() }
        }
        replyBar.addView(cancel, linear(dp(48), dp(48)))
        replyBar.visibility = View.GONE
        ViewCompat.setAccessibilityPaneTitle(replyBar, ui.t(R.string.dw_replying_to_message))
        addView(replyBar, linear(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // The help center article the question comes from, with its ×.
        article.id = R.id.dw_composer_article
        article.orientation = HORIZONTAL
        article.gravity = Gravity.CENTER_VERTICAL
        article.setPaddingRelative(dp(10), dp(4), 0, dp(4))
        article.background = context.rounded(c.bg, 12f, c.line)
        article.addView(ImageView(context).apply {
            setImageDrawable(context.icon(R.drawable.dw_ic_doc, c.brandText))
            background = context.rounded(c.brandSoft12, 8f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            decorative()
        }, linear(dp(32), dp(32)))
        val articleColumn = LinearLayout(context).apply { orientation = VERTICAL }
        articleColumn.addView(TextView(context).apply {
            text = ui.t(R.string.dw_help_center_article)
            textStyle(11.5f, 400, c.ink2, 1.3f)
        })
        articleTitle.textStyle(13.5f, 600, c.ink, 1.35f)
        articleTitle.contentText()
        articleTitle.maxLines = 2
        articleTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        articleColumn.addView(articleTitle)
        article.addView(articleColumn, linear(0, WRAP, 1f).apply { marginStart = dp(10) })
        article.addView(ImageView(context).apply {
            id = R.id.dw_composer_article_remove
            setImageDrawable(context.icon(R.drawable.dw_ic_close_bold, c.ink3))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            contentDescription = ui.t(R.string.dw_remove_article)
            background = ripple(c.withAlpha(c.ink, 0.1), null, context.oval(c.bg3))
            role(button = true)
            setOnClickListener { articleRemoved?.invoke() }
        }, linear(dp(48), dp(48)))
        article.visibility = View.GONE
        addView(article, linear(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // The bar.
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.BOTTOM
        bar.setPaddingRelative(dp(16), 0, 0, 0)
        input.id = R.id.dw_composer_input
        input.contentText()
        input.background = null
        input.textStyle(14.5f, 400, c.ink, 20f / 14.5f)
        input.setHintTextColor(c.ink3)
        // The hint names the field for screen readers too; a content description would hide the text.
        input.hint = ui.t(R.string.dw_type_message)
        // The draft comes back from the SDK's own memory; the view's saved text would come back over it.
        input.isSaveEnabled = false
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.maxLines = 5
        input.isVerticalScrollBarEnabled = true
        input.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        input.setPadding(0, dp(14), 0, dp(14))
        input.minHeight = dp(48)
        input.highlightColor = c.withAlpha(c.brand, 0.25)
        // While the bot replies the field keeps its focus and the keyboard, and refuses typing.
        // Typed and pasted text stops at what the server takes; text the composer sets
        // itself (a draft, a refused send) goes in whole, so nothing the visitor wrote is cut.
        val cap = InputFilter.LengthFilter(com.deskwoot.sdk.engine.ConversationEngine.MAX_MESSAGE_CHARS)
        input.filters = arrayOf(
            InputFilter { source, start, end, dest, dstart, dend ->
                when {
                    settingText -> null
                    disabled -> dest.subSequence(dstart, dend)
                    else -> cap.filter(source, start, end, dest, dstart, dend)
                }
            },
        )
        bar.addView(input, linear(0, WRAP, 1f))
        if (ui.config.featureEmojiPicker) {
            emoji.id = R.id.dw_composer_emoji
            emoji.setImageDrawable(context.icon(R.drawable.dw_ic_emoji, c.ink3))
            emoji.setPadding(dp(15), dp(15), dp(15), dp(15))
            emoji.contentDescription = ui.t(R.string.dw_pick_emoji)
            emoji.backgroundKeepingPadding(InsetDrawable(ripple(c.withAlpha(c.ink, 0.1), null, context.oval(c.bg3)), dp(5)))
            emoji.role(button = true)
            emoji.setOnClickListener { toggleEmoji() }
            bar.addView(emoji, linear(dp(48), dp(48)))
        }
        if (ui.config.featureFilePicker) {
            attach.id = R.id.dw_composer_attach
            attach.setImageDrawable(context.icon(R.drawable.dw_ic_paperclip, c.ink3))
            attach.setPadding(dp(15), dp(15), dp(15), dp(15))
            attach.contentDescription = ui.t(R.string.dw_attach_file)
            attach.backgroundKeepingPadding(InsetDrawable(ripple(c.withAlpha(c.ink, 0.1), null, context.oval(c.bg3)), dp(5)))
            attach.role(button = true)
            attach.setOnClickListener { callbacks.attach(it) }
            bar.addView(attach, linear(dp(48), dp(48)))
        }
        send.id = R.id.dw_composer_send
        send.setPadding(dp(15), dp(15), dp(15), dp(15))
        send.contentDescription = ui.t(R.string.dw_send_message)
        send.role(button = true)
        send.setOnClickListener { submit() }
        bar.addView(send, linear(dp(48), dp(48)))
        addView(bar, linear(MATCH, WRAP))
        bar.setOnClickListener { focusField() }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                hasText = text.isNotBlank()
                // An error line goes with the next keystroke.
                if (status.visibility == View.VISIBLE && status.tag == ERROR_UNTIL_TYPED) status.visibility = View.GONE
                paintSend()
                callbacks.textChanged(text)
            }
        })
        input.setOnFocusChangeListener { _, focused -> paintBar(focused) }
        paintBar(false)
        paintSend()
    }

    private fun paintBar(focused: Boolean) {
        val c = ui.colors
        // Direkt's bar is bg with a line-2 border and a soft shadow.
        val direkt = ui.design.composerStyle == com.deskwoot.sdk.ui.ComposerStyle.DIREKT
        bar.background = when {
            focused -> context.rounded(c.bg, 24f, c.brandText)
            direkt -> context.rounded(c.bg, 24f, c.line2)
            else -> context.rounded(c.bg2, 24f, c.bg2)
        }
        if (direkt) bar.elevation = dp(2).toFloat()
    }

    /** Empty or not allowed: bg-3 with an ink-3 arrow; with text: the brand fill. */
    private fun paintSend() {
        val c = ui.colors
        val active = hasText && enabledToSend && !disabled
        send.setImageDrawable(context.icon(R.drawable.dw_ic_send, if (active) c.onBrand else c.ink3))
        send.backgroundKeepingPadding(InsetDrawable(ripple(c.withAlpha(c.onBrand, 0.2), if (active) context.oval(c.fill).edged(context, c) else context.oval(c.bg3)), dp(5)))
        send.isEnabled = active
    }

    /**
     * The bot types ("Bot is replying...": the field keeps the focus and the
     * keyboard but takes no text), or
     * the state of the session forbids sending.
     */
    fun setState(botReplying: Boolean, canSend: Boolean, canAttach: Boolean) {
        val changed = disabled != botReplying || enabledToSend != canSend
        disabled = botReplying
        enabledToSend = canSend
        input.hint = ui.t(if (botReplying) R.string.dw_bot_replying else placeholder)
        // Screen readers hear that the field waits.
        ViewCompat.setStateDescription(input, if (botReplying) ui.t(R.string.dw_bot_replying) else null)
        emoji.isEnabled = !botReplying
        emoji.alpha = if (botReplying) 0.55f else 1f
        attach.isEnabled = canAttach && !botReplying
        attach.alpha = if (attach.isEnabled) 1f else 0.55f
        if (changed) paintSend()
        if (botReplying) panel?.dismiss()
    }

    /** The placeholder while the field is open (Direkt's fresh chat asks for the question). */
    var placeholder: Int = R.string.dw_type_message
        set(value) {
            field = value
            if (!disabled) input.hint = ui.t(value)
        }

    /** Text set by the composer itself passes the filter that holds typing while the bot replies. */
    private var settingText = false

    /** The help center article the next message carries, or none; [onRemove] runs on its ×. */
    fun setArticle(ref: com.deskwoot.sdk.model.HelpArticleRef?, onRemove: () -> Unit) {
        articleRemoved = onRemove
        article.visibility = if (ref == null) View.GONE else View.VISIBLE
        articleTitle.text = ref?.title
        article.contentDescription = ref?.let { ui.t(R.string.dw_help_center_article) + ", " + it.title }
    }

    /** A stored draft into the field, also while the bot replies. */
    fun setDraft(text: String) = setText(text)

    private fun setText(text: String) {
        settingText = true
        try {
            input.setText(text)
            input.setSelection(text.length)
        } finally {
            settingText = false
        }
    }

    private fun submit() {
        val text = input.text?.toString().orEmpty().trim()
        if (text.isEmpty() || !enabledToSend || disabled) return
        setText("")
        callbacks.send(text)
        panel?.dismiss()
    }

    fun focusField() {
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
    }

    /** A line above the bar: an error ([untilTyped] goes with the next key, else after 6 s) in the error colors. */
    fun showError(text: String, untilTyped: Boolean) {
        val c = ui.colors
        status.text = text
        status.setTextColor(c.error)
        status.background = context.rounded(c.errorBg, 10f)
        status.tag = if (untilTyped) ERROR_UNTIL_TYPED else null
        status.visibility = View.VISIBLE
        removeCallbacks(hideStatus)
        if (!untilTyped) postDelayed(hideStatus, 6_000)
    }

    /** The message being replied to, or none; [focus] moves the caret into the field (not when it comes back after a rebuild). */
    fun setReply(author: String?, text: String?, focus: Boolean = true) {
        if (text == null) {
            replyBar.visibility = View.GONE
            return
        }
        replyAuthor.visibility = if (author == null) View.GONE else View.VISIBLE
        replyAuthor.text = author
        replyText.text = text
        replyBar.visibility = View.VISIBLE
        if (focus) focusField()
    }

    /** Puts text back in front of what the visitor typed meanwhile, joined by a new line. */
    fun restore(text: String) {
        val now = input.text?.toString().orEmpty()
        val joined = if (now.isBlank()) text else text + "\n" + now
        setText(joined)
    }

    // ── Emoji ────────────────────────────────────────────────────────────

    private fun toggleEmoji() {
        if (panel?.isShowing == true) {
            panel?.dismiss()
            return
        }
        val c = ui.colors
        // Each emoji is a 48 dp target: 8 columns where they fit, fewer on a narrow phone.
        val cell = dp(48)
        val room = resources.displayMetrics.widthPixels - dp(32)
        val grid = GridLayout(context).apply {
            id = R.id.dw_emoji_panel
            columnCount = (room / cell).coerceIn(5, 8)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = context.rounded(c.bg, 16f, c.line)
            elevation = dp(4).toFloat()
        }
        for (e in EMOJI) {
            grid.addView(TextView(context).apply {
                id = R.id.dw_emoji
                text = e
                textSize = 20f
                gravity = Gravity.CENTER
                background = ripple(c.withAlpha(c.ink, 0.1), null, context.rounded(c.bg2, 8f))
                contentDescription = e
                setOnClickListener { insert(e) }
            }, GridLayout.LayoutParams().apply {
                width = cell
                height = cell
            })
        }
        val popup = PopupWindow(grid, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            elevation = dp(6).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        }
        grid.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val x = if (rtl) 0 else emoji.width - grid.measuredWidth
        popup.showAsDropDown(emoji, x, -(emoji.height + grid.measuredHeight + dp(8)))
        popup.setOnDismissListener {
            panel = null
            emojiModal.close()
        }
        panel = popup
        // Modal for TalkBack: the messenger beneath leaves its order, the focus moves to the first emoji
        // and comes back to the emoji button. A tap outside, back, or a send closes it.
        emojiModal.open(listOf(beneath()), opener = emoji, focus = grid.getChildAt(0))
        emoji.contentDescription = ui.t(R.string.dw_pick_emoji)
    }

    /** A pick goes in at the caret; the panel stays open and the field keeps the focus. */
    private fun insert(e: String) {
        val editable = input.text ?: return
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        editable.replace(start, end, e)
        input.requestFocus()
    }

    /**
     * Shows the attachment [menu] (the system's own window, which TalkBack
     * moves into by itself); the messenger beneath leaves TalkBack's order
     * meanwhile, and the focus comes back to the attach button.
     */
    fun showMenu(menu: android.widget.PopupMenu) {
        menu.setOnDismissListener { menuModal.close() }
        menu.show()
        menuModal.open(listOf(beneath()), opener = attach, focus = null)
    }

    /** What lies beneath a panel or menu of the composer: the Activity's whole content. */
    private fun beneath(): View = rootView.findViewById<View>(android.R.id.content) ?: rootView

    fun closeEmoji(): Boolean {
        val open = panel?.isShowing == true
        panel?.dismiss()
        return open
    }

    override fun onDetachedFromWindow() {
        panel?.dismiss()
        removeCallbacks(hideStatus)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val ERROR_UNTIL_TYPED = "until-typed"

        /** The web's 40 fixed emoji. */
        val EMOJI = listOf(
            "😀", "😃", "😄", "😁", "😅", "😂", "🤣", "🙂",
            "😉", "😊", "😍", "😘", "🤔", "😐", "😕", "🙁",
            "😢", "😭", "😤", "🥳", "😎", "🤯", "😴", "👍",
            "👎", "👋", "🙏", "👏", "💪", "🎉", "❤️", "🔥",
            "✨", "💯", "✅", "❓", "❗", "👀", "🙌", "🤝",
        )
    }
}

/** A quiet line in the composer's place or above it (queue, waiting, closed, offline). */
@SuppressLint("ViewConstructor")
internal class StatusLine(context: Context, ui: Ui, withDot: Boolean) : LinearLayout(context) {
    val text = TextView(context)
    private val dot = View(context)
    private var pulse: android.animation.ValueAnimator? = null
    private val animated = withDot && !ui.reducedMotion

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = context.rounded(ui.colors.brandSoft12, 10f)
        if (withDot) {
            dot.background = context.oval(ui.colors.brand)
            dot.decorative()
            addView(dot, linear(dp(8), dp(8)).apply { marginEnd = dp(10) })
        }
        text.textStyle(12f, 400, ui.colors.ink3, 1.4f)
        addView(text, linear(0, WRAP, 1f))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animated) return
        pulse = android.animation.ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 800
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }
}
