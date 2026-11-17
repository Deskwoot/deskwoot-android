package com.deskwoot.sdk.ui.help

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.HelpArticleSummary
import com.deskwoot.sdk.model.HelpCollection
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.people.TeamLine
import com.deskwoot.sdk.ui.widgets.IconButton
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PrimaryButton
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.decorative
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle

/**
 * The help search field: 48 high, radius 12, bg-2,
 * the search icon, 15 px text of at most 200 characters, a clear button
 * while there is text; focused: a brand border on bg.
 */
@SuppressLint("ViewConstructor")
internal class SearchField(
    context: Context,
    private val ui: Ui,
    /** On Empfang's band: a white field in fixed colors in both themes. */
    private val onBand: Boolean = false,
    private val onChange: (String) -> Unit,
) : LinearLayout(context) {
    private val inkColor = if (onBand) 0xFF16162B.toInt() else ui.colors.ink
    private val hintColor = if (onBand) 0xFF82829A.toInt() else ui.colors.ink3

    val input = EditText(context)
    private val clear: IconButton

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val c = ui.colors
        minimumHeight = dp(48)
        setPaddingRelative(dp(14), 0, 0, 0)
        val icon = ImageView(context).apply {
            setImageDrawable(context.icon(R.drawable.dw_ic_search, hintColor))
            decorative()
        }
        addView(icon, linear(dp(19), dp(19)))
        input.id = R.id.dw_help_search
        input.contentText()
        input.background = null
        input.textStyle(15f, 400, inkColor, 1.3f)
        input.setHintTextColor(hintColor)
        // An editable field is named by its hint, never a content description: TalkBack would read that instead of the text.
        input.hint = ui.t(R.string.dw_search_placeholder)
        input.isSingleLine = true
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.filters = arrayOf(InputFilter.LengthFilter(200))
        input.setPadding(0, dp(12), 0, dp(12))
        input.setOnEditorActionListener { v, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                hideKeyboard(v)
                true
            } else {
                false
            }
        }
        addView(input, linear(0, WRAP, 1f).apply { marginStart = dp(10) })
        clear = IconButton(context, ui, R.drawable.dw_ic_close, 16, ui.t(R.string.dw_clear_search), if (onBand) 0xFF4E4E66.toInt() else c.ink2).apply {
            id = R.id.dw_help_search_clear
            setOnClickListener {
                input.setText("")
                input.requestFocus()
            }
        }
        addView(clear)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                onChange(s?.toString().orEmpty())
            }
        })
        clear.visibility = View.INVISIBLE
        input.setOnFocusChangeListener { _, focused -> paint(focused) }
        paint(false)
        setOnClickListener { input.requestFocus() }
    }

    private fun paint(focused: Boolean) {
        val c = ui.colors
        background = when {
            onBand -> context.rounded(0xFFFFFFFF.toInt(), 12f, if (focused) 0xFFFFFFFF.toInt() else 0x00FFFFFF, 2f)
            focused -> context.rounded(c.bg, 12f, c.brandText)
            else -> context.rounded(c.bg2, 12f, c.bg2)
        }
    }

    private fun hideKeyboard(v: View) {
        (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
    }
}

/** A row of a list with a hairline under it, inset 10 on both sides. */
@SuppressLint("ViewConstructor")
internal open class ListRow(context: Context, ui: Ui) : LinearLayout(context) {
    private val linePaint = Paint().apply { color = ui.colors.line }
    var separator: Boolean = true

    init {
        setWillNotDraw(false)
        val c = ui.colors
        background = ripple(c.withAlpha(c.ink, 0.08), null, context.rounded(c.bg2, 12f))
        isClickable = true
        isFocusable = true
        role(button = true)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (separator) canvas.drawRect(dp(10).toFloat(), height - 1f, (width - dp(10)).toFloat(), height.toFloat(), linePaint)
    }
}

/** A collection: title 15/600, description 13.5 ink-2, the article count and a chevron (`CollectionRow`). */
internal fun collectionRow(context: Context, ui: Ui, collection: HelpCollection, tile: Boolean = false, onTap: () -> Unit): ListRow {
    val c = ui.colors
    return ListRow(context, ui).apply {
        id = R.id.dw_collection_row
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(10), dp(13), dp(10), dp(13))
        // Direkt shows the folder in a 36 tile of the soft brand tint; Klartext hides it.
        if (tile) addView(folderTile(context, ui, 36, 10f, 20), linear(dp(36), dp(36)).apply { marginEnd = dp(12) })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            contentText()
            text = collection.name
            textStyle(15f, 600, c.ink)
        })
        collection.description?.let { d ->
            column.addView(TextView(context).apply {
                contentText()
                text = d
                textStyle(13.5f, 400, c.ink2, 1.4f)
            }, linear(MATCH, WRAP).apply { topMargin = dp(2) })
        }
        addView(column, linear(0, WRAP, 1f))
        val count = if (collection.articleCount == 1) ui.t(R.string.dw_article_count_one) else ui.t(R.string.dw_article_count, collection.articleCount.toString())
        addView(TextView(context).apply {
            text = count
            textStyle(12.5f, 400, c.ink3)
            fontFeatureSettings = "tnum"
        }, linear(WRAP, WRAP).apply { marginStart = dp(10) })
        addView(ImageView(context).apply {
            setImageDrawable(context.icon(R.drawable.dw_ic_chevron_right, c.ink3))
            decorative()
        }, linear(dp(18), dp(18)).apply { marginStart = dp(6) })
        contentDescription = "${collection.name}, $count"
        setOnClickListener { onTap() }
    }
}

/** An article: title 14.5/500, the description only in results and collections, a chevron (`ArticleRow`). [mark] is a search to highlight. */
internal fun articleRow(context: Context, ui: Ui, article: HelpArticleSummary, withDescription: Boolean, mark: String?, onTap: () -> Unit): ListRow {
    val c = ui.colors
    return ListRow(context, ui).apply {
        id = R.id.dw_article_row
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(10), dp(12), dp(10), dp(12))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            contentText()
            text = highlight(article.title, mark, c.brandSoft)
            textStyle(14.5f, 500, c.ink)
        })
        if (withDescription) {
            article.description?.let { d ->
                column.addView(TextView(context).apply {
                    contentText()
                    text = highlight(d, mark, c.brandSoft)
                    textStyle(13f, 400, c.ink2, 1.4f)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, linear(MATCH, WRAP).apply { topMargin = dp(2) })
            }
        }
        addView(column, linear(0, WRAP, 1f))
        addView(ImageView(context).apply {
            setImageDrawable(context.icon(R.drawable.dw_ic_chevron_right, c.ink3))
            decorative()
        }, linear(dp(18), dp(18)).apply { marginStart = dp(10) })
        setOnClickListener { onTap() }
    }
}

/** The first match of [mark] in [text] on the soft brand tint. */
internal fun highlight(text: String, mark: String?, color: Int): CharSequence {
    val q = mark?.trim()?.takeIf { it.isNotEmpty() } ?: return text
    val at = text.indexOf(q, ignoreCase = true)
    if (at < 0) return text
    return SpannableString(text).apply { setSpan(BackgroundColorSpan(color), at, at + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
}

/**
 * "Nothing that fits?" (`AskCard`): the bot answers
 * right away, or the reply time; "Ask Fynn" or "Write to us" starts a new
 * conversation with [draft] in the field.
 */
internal fun askCard(context: Context, ui: Ui, article: com.deskwoot.sdk.model.HelpArticleRef? = null, draft: () -> String?): LinearLayout {
    val c = ui.colors
    val config = ui.config
    val people = ui.people
    return LinearLayout(context).apply {
        id = R.id.dw_ask_card
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = context.rounded(c.bg, 14f, c.line)
        addView(TextView(context).apply {
            text = ui.t(R.string.dw_nothing_fitting)
            textStyle(15f, 600, c.ink)
        })
        val lead = if (config.botActive) {
            ui.t(R.string.dw_bot_answers_now, people.botName ?: people.businessName)
        } else {
            TeamLine.replyLine(config.replyTime, ui.strings) ?: ui.t(R.string.dw_reply_minutes)
        }
        addView(TextView(context).apply {
            text = lead
            textStyle(14f, 400, c.ink2, 1.5f)
        }, linear(MATCH, WRAP).apply { topMargin = dp(2) })
        val label = if (config.botActive) ui.t(R.string.dw_ask_name, people.botName ?: people.businessName) else ui.t(R.string.dw_write_to_us)
        addView(PrimaryButton(context, ui, label).apply {
            id = R.id.dw_ask_button
            setOnClickListener { ui.nav.startConversation(draft(), article = article) }
        }, linear(MATCH, WRAP).apply { topMargin = dp(12) })
    }
}

/** The folder of a collection in a tile of the soft brand tint (Empfang 42 / radius 12 / icon 21, Direkt 36 / 10 / 20). */
internal fun folderTile(context: Context, ui: Ui, size: Int, radius: Float, icon: Int): android.widget.FrameLayout {
    val c = ui.colors
    return android.widget.FrameLayout(context).apply {
        background = context.rounded(c.brandSoft, radius)
        decorative()
        addView(ImageView(context).apply {
            setImageDrawable(context.icon(R.drawable.dw_ic_folder, c.brandText))
        }, android.widget.FrameLayout.LayoutParams(context.dp(icon), context.dp(icon), Gravity.CENTER))
        minimumWidth = context.dp(size)
        minimumHeight = context.dp(size)
    }
}
