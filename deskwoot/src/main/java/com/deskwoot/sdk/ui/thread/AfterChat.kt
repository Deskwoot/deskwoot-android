package com.deskwoot.sdk.ui.thread

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.CsatState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PrimaryButton
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.WeightSpan
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle

/**
 * The rating card: the inbox's question, 5 glyphs in
 * its style (stars and hearts fill up to the tap, fire and smileys pick
 * one), an optional comment, Submit; then the thanks. One instance per
 * thread screen, moved into whichever row shows it, so a comment being
 * typed survives the list's recycling.
 */
@SuppressLint("ViewConstructor")
internal class CsatCard(context: Context, private val ui: Ui, private val onSubmit: (Int, String) -> Unit) : LinearLayout(context) {
    private val style = ui.config.csat.ratingStyle
    private val glyphs = LinearLayout(context)
    private val comment = EditText(context)
    private val error = TextView(context)
    private val submit = PrimaryButton(context, ui, ui.t(R.string.dw_csat_submit))
    private val form = LinearLayout(context)
    private val thanks = LinearLayout(context)
    private var rating = 0
    private var state = CsatState.IDLE

    init {
        id = R.id.dw_csat_card
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val c = ui.colors
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = context.rounded(c.bg, 14f, c.line)
        elevation = dp(1).toFloat()
        form.orientation = VERTICAL
        form.gravity = Gravity.CENTER_HORIZONTAL
        form.addView(TextView(context).apply {
            text = ui.config.csat.promptText ?: ui.t(R.string.dw_csat_prompt)
            textStyle(15f, 600, c.ink, 1.35f)
            gravity = Gravity.CENTER
            role(heading = true)
        })
        val kind = when (style) {
            "STARS" -> ui.t(R.string.dw_csat_star)
            "HEARTS" -> ui.t(R.string.dw_csat_heart)
            else -> ui.t(R.string.dw_csat_rating)
        }
        form.addView(TextView(context).apply {
            text = ui.t(R.string.dw_csat_tap, kind)
            textStyle(13f, 400, c.ink2, 1.4f)
            gravity = Gravity.CENTER
        }, linear(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(10) })
        glyphs.id = R.id.dw_csat_rating
        glyphs.orientation = HORIZONTAL
        glyphs.gravity = Gravity.CENTER
        for (n in 1..5) {
            glyphs.addView(TextView(context).apply {
                textSize = 26f
                gravity = Gravity.CENTER
                includeFontPadding = false
                contentDescription = ui.t(R.string.dw_csat_rate_aria, n.toString(), "5")
                role(button = true)
                background = ripple(c.withAlpha(c.ink, 0.08), null, context.rounded(c.bg2, 999f))
                setOnClickListener { choose(n) }
            }, linear(dp(48), dp(48)).apply { if (n > 1) marginStart = dp(4) })
        }
        form.addView(glyphs)
        comment.id = R.id.dw_csat_comment
        // The screen keeps the comment across a rebuild; the view's own saved text would overwrite it.
        comment.isSaveEnabled = false
        comment.contentText()
        comment.textStyle(14.5f, 400, c.ink, 1.4f)
        comment.setHintTextColor(c.ink3)
        comment.hint = ui.t(R.string.dw_csat_feedback_placeholder)
        comment.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        comment.minLines = 2
        comment.maxLines = 4
        comment.minHeight = dp(48)
        comment.gravity = Gravity.TOP or Gravity.START
        comment.setPadding(dp(14), dp(12), dp(14), dp(12))
        comment.background = context.rounded(c.bg2, 12f, c.line)
        comment.setOnFocusChangeListener { _, focused ->
            comment.background = if (focused) context.rounded(c.bg, 12f, c.brandText) else context.rounded(c.bg2, 12f, c.line)
        }
        form.addView(comment, linear(MATCH, WRAP).apply { topMargin = dp(10) })
        error.id = R.id.dw_csat_error
        error.textStyle(12f, 400, c.error, 1.4f)
        error.text = ui.t(R.string.dw_csat_submit_error)
        error.gravity = Gravity.CENTER
        error.visibility = View.GONE
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        form.addView(error, linear(MATCH, WRAP).apply { topMargin = dp(10) })
        submit.id = R.id.dw_csat_submit
        submit.setPadding(dp(24), 0, dp(24), 0)
        submit.setOnClickListener {
            if (rating > 0 && state != CsatState.SENDING) onSubmit(rating, comment.text?.toString().orEmpty())
        }
        form.addView(submit, linear(WRAP, WRAP).apply { topMargin = dp(12) })
        addView(form, linear(MATCH, WRAP))
        thanks.orientation = VERTICAL
        thanks.gravity = Gravity.CENTER_HORIZONTAL
        thanks.addView(TextView(context).apply {
            text = ui.t(R.string.dw_csat_thank_you)
            textStyle(15f, 600, c.ink)
            gravity = Gravity.CENTER
        })
        thanks.addView(TextView(context).apply {
            text = ui.t(R.string.dw_csat_feedback_helps)
            textStyle(13f, 400, c.ink2, 1.4f)
            gravity = Gravity.CENTER
        }, linear(MATCH, WRAP).apply { topMargin = dp(4) })
        thanks.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(thanks, linear(MATCH, WRAP))
        paintGlyphs()
        setState(CsatState.IDLE)
    }

    private fun choose(n: Int) {
        rating = n
        paintGlyphs()
        setState(state)
    }

    /** The rating tapped so far (0: none), kept by the screen across a rebuild. */
    val chosen: Int get() = rating

    val commentText: String get() = comment.text?.toString().orEmpty()

    /** A rating and comment from before a rebuild. */
    fun restore(rating: Int, comment: String) {
        if (rating in 1..5) choose(rating)
        if (comment.isNotEmpty() && this.comment.text.isNullOrEmpty()) this.comment.setText(comment)
    }

    /** Stars and hearts fill up to the rating; fire and smileys mark one. */
    private fun paintGlyphs() {
        val c = ui.colors
        val upTo = style == "STARS" || style == "HEARTS"
        for (i in 0 until 5) {
            val n = i + 1
            val view = glyphs.getChildAt(i) as TextView
            val on = if (upTo) n <= rating else n == rating
            view.text = when (style) {
                "HEARTS" -> if (on) "♥" else "♡"
                "FIRE" -> "🔥"
                "SMILEYS" -> SMILEYS[i]
                else -> if (on) "★" else "☆"
            }
            view.setTextColor(if (on) c.brandText else c.ink2)
            view.alpha = if (rating == 0 || on) 1f else 0.4f
            val scale = if (on) 1.15f else 1f
            view.scaleX = scale
            view.scaleY = scale
            view.isSelected = on
        }
    }

    fun setState(next: CsatState) {
        state = next
        val done = next == CsatState.SUBMITTED
        form.visibility = if (done) View.GONE else View.VISIBLE
        thanks.visibility = if (done) View.VISIBLE else View.GONE
        error.visibility = if (next == CsatState.FAILED) View.VISIBLE else View.GONE
        submit.text = ui.t(if (next == CsatState.SENDING) R.string.dw_csat_submitting else R.string.dw_csat_submit)
        submit.isEnabled = rating > 0 && next != CsatState.SENDING
    }

    private companion object {
        val SMILEYS = listOf("😡", "🙁", "😐", "🙂", "😍")
    }
}

/**
 * "Would you like a copy of this conversation? Send to my email": sending, sent in green, a retry for 3 s after a failure,
 * and an email field when the server knows no address.
 */
@SuppressLint("ViewConstructor")
internal class TranscriptBanner(context: Context, private val ui: Ui, private val onRequest: (String?) -> Unit) : LinearLayout(context) {
    private val text = TextView(context)
    private val emailRow = LinearLayout(context)
    private val email = EditText(context)
    private val sendButton = PrimaryButton(context, ui, ui.t(R.string.dw_send))
    /** When the retry offer goes back to the plain offer, on the clock the view's delays run on. */
    private var retryUntil = 0L
    private var tappable = false
    private var current = TranscriptState.IDLE
    private val backToIdle = Runnable { show(TranscriptState.IDLE) }

    init {
        id = R.id.dw_transcript_banner
        orientation = VERTICAL
        val c = ui.colors
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(48)
        background = ripple(c.withAlpha(c.ink, 0.06), context.rounded(c.bg2, 14f))
        // One paragraph: the offer and its link (13/1.5 ink-3, the link 13/600 brand-text, underlined).
        text.id = R.id.dw_transcript_send
        text.textStyle(13f, 400, c.ink3, 1.5f)
        addView(text, linear(MATCH, WRAP))
        setOnClickListener { if (tappable) onRequest(null) }
        emailRow.orientation = HORIZONTAL
        emailRow.gravity = Gravity.CENTER_VERTICAL
        email.id = R.id.dw_transcript_email
        // The screen keeps the address across a rebuild.
        email.isSaveEnabled = false
        email.textStyle(15f, 400, c.ink)
        email.setHintTextColor(c.ink3)
        email.hint = ui.t(R.string.dw_email_placeholder)
        email.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        email.isSingleLine = true
        email.minHeight = dp(48)
        email.setPadding(dp(14), dp(10), dp(14), dp(10))
        email.background = context.rounded(c.bg, 12f, c.line)
        emailRow.addView(email, linear(0, WRAP, 1f))
        sendButton.id = R.id.dw_transcript_submit
        sendButton.setPadding(dp(16), 0, dp(16), 0)
        sendButton.setOnClickListener {
            val value = email.text?.toString().orEmpty().trim()
            if (EMAIL.matches(value)) onRequest(value)
        }
        emailRow.addView(sendButton, linear(WRAP, WRAP).apply { marginStart = dp(8) })
        email.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                sendButton.isEnabled = EMAIL.matches(s?.toString().orEmpty().trim())
            }
        })
        sendButton.isEnabled = false
        addView(emailRow, linear(MATCH, WRAP).apply { topMargin = dp(8) })
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        show(TranscriptState.IDLE)
    }

    private fun offer(link: String, enabled: Boolean): CharSequence {
        val c = ui.colors
        val out = SpannableStringBuilder(ui.t(R.string.dw_transcript_offer)).append(' ')
        val start = out.length
        out.append(link)
        out.setSpan(ForegroundColorSpan(c.brandText), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(WeightSpan(600), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (enabled) out.setSpan(UnderlineSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /** The address typed so far, kept by the screen across a rebuild. */
    var emailText: String
        get() = email.text?.toString().orEmpty()
        set(value) {
            if (value.isNotEmpty() && email.text.isNullOrEmpty()) email.setText(value)
        }

    fun show(state: TranscriptState) {
        val c = ui.colors
        current = state
        removeCallbacks(backToIdle)
        emailRow.visibility = if (state == TranscriptState.NEEDS_EMAIL) View.VISIBLE else View.GONE
        text.textStyle(13f, 400, c.ink3, 1.5f)
        tappable = state == TranscriptState.IDLE || state == TranscriptState.FAILED
        isClickable = tappable
        when (state) {
            TranscriptState.IDLE -> text.text = offer(ui.t(R.string.dw_transcript_send), enabled = true)
            TranscriptState.SENDING -> text.text = offer(ui.t(R.string.dw_transcript_sending), enabled = false)
            TranscriptState.FAILED -> {
                text.text = offer(ui.t(R.string.dw_transcript_retry), enabled = true)
                if (retryUntil == 0L) retryUntil = SystemClock.uptimeMillis() + 3_000
                postDelayed(backToIdle, (retryUntil - SystemClock.uptimeMillis()).coerceAtLeast(0))
            }
            TranscriptState.SENT -> {
                text.text = ui.t(R.string.dw_transcript_sent)
                text.textStyle(13f, 600, c.ok, 1.5f)
            }
            TranscriptState.NEEDS_EMAIL -> {
                text.text = ui.t(R.string.dw_transcript_need_email)
                if (!email.hasFocus()) email.requestFocus()
            }
        }
        if (tappable) role(button = true)
        if (state != TranscriptState.FAILED) retryUntil = 0L
    }

    /** A row the list detached and attached again without binding it: the retry offer still ends on time. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (current == TranscriptState.FAILED && retryUntil != 0L) {
            removeCallbacks(backToIdle)
            postDelayed(backToIdle, (retryUntil - SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(backToIdle)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** The web transcript banner's address check. */
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
