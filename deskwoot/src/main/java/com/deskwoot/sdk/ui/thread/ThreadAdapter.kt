package com.deskwoot.sdk.ui.thread

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.Attachment
import com.deskwoot.sdk.model.Readback
import com.deskwoot.sdk.ui.ChoiceStyle
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.text.BubblePart
import com.deskwoot.sdk.ui.text.LinkSpan
import com.deskwoot.sdk.ui.text.MarkdownColors
import com.deskwoot.sdk.ui.text.MarkdownSpans
import com.deskwoot.sdk.ui.text.MessageText
import com.deskwoot.sdk.ui.widgets.BubbleTextView
import com.deskwoot.sdk.ui.widgets.CappedColumn
import com.deskwoot.sdk.ui.widgets.FaceView
import com.deskwoot.sdk.ui.widgets.FlowLayout
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.ShimmerText
import com.deskwoot.sdk.ui.widgets.TypingDots
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.backgroundKeepingPadding
import com.deskwoot.sdk.ui.widgets.corners
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

/** What a tap in the thread asks for. */
internal interface ThreadCallbacks {
    fun openUrl(url: String)

    fun openAttachment(attachment: Attachment)

    fun jumpTo(key: String)

    fun choice(label: String, greeting: Boolean)

    fun resolvedButton()

    fun teamButton(label: String)

    fun retry(uuid: String)

    fun discard(uuid: String, text: String?)

    /** Reply to a row (swipe, the long press menu, or the accessibility action). */
    fun reply(row: ThreadRow)

    /**
     * A long press on a message: its menu at [anchor] (Copy with [copyText],
     * Reply); TalkBack's focus goes back to [opener] when it closes. False
     * when the row offers neither.
     */
    fun messageMenu(row: ThreadRow, copyText: String?, anchor: View, opener: View): Boolean

    /** Copy (the long press menu or the accessibility action): [text] goes on the clipboard. */
    fun copy(text: String)

    /** The rating card and the transcript offer keep their own state in the screen. */
    fun bindCsat(holder: FrameLayout)

    fun bindTranscript(holder: FrameLayout)
}

/**
 * The thread's rows in a RecyclerView: DiffUtil runs off the main thread
 * (AsyncListDiffer), every row has a stable id from its key, and markdown is
 * rendered once per text and kept (an LRU of 300 entries).
 */
internal class ThreadAdapter(private val ui: Ui, private val callbacks: ThreadCallbacks) :
    ListAdapter<ThreadRow, ThreadAdapter.Holder>(diffConfig(DIFF)) {
    private val ids = HashMap<String, Long>()
    private var nextId = 1L
    private val rendered = LruCache<String, List<BubblePart>>(300)
    private val incomingColors = MarkdownColors(ui.colors.ink, ui.colors.brandText, ui.colors.ink2, ui.colors.line2, ui.colors.withAlpha(ui.colors.ink, 0.07))
    private val visitorColors = MarkdownColors(ui.colors.onBrand, ui.colors.onBrand, ui.colors.onBrand(0.85), ui.colors.onBrand(0.6), ui.colors.onBrand(0.16))
    private val density = ui.context.resources.displayMetrics.density

    /** The bubble's 14.5 at the visitor's font size, for the columns of a table. */
    private val bubbleTextSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f, ui.context.resources.displayMetrics)

    /** Delayed work of the rows; unlike a view's own queue it can always be cancelled, attached or not. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Known image sizes (width / height) by attachment id, so a recycled bubble has its size before the picture. */
    private val aspects = HashMap<String, Float>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = ids.getOrPut(getItem(position).key) { nextId++ }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DayRow -> T_DAY
        is ActivityRow -> T_ACTIVITY
        is DividerRow -> T_DIVIDER
        is IncomingRow -> T_INCOMING
        is VisitorRow -> T_VISITOR
        is UploadRow -> T_UPLOAD
        is TypingRow -> T_TYPING
        is ChoicesRow -> T_CHOICES
        is FixedButtonsRow -> T_FIXED
        is CsatRow -> T_CSAT
        is TranscriptRow -> T_TRANSCRIPT
        is GreetingBlockRow -> T_GREETING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        return when (viewType) {
            T_DAY, T_ACTIVITY -> LineHolder(context, viewType)
            T_DIVIDER -> DividerHolder(context)
            T_INCOMING -> IncomingHolder(context)
            T_VISITOR -> VisitorHolder(context)
            T_UPLOAD -> UploadHolder(context)
            T_TYPING -> TypingHolder(context)
            T_CHOICES -> ChoicesHolder(context)
            T_FIXED -> FixedHolder(context)
            T_GREETING -> GreetingBlockHolder(context)
            else -> SlotHolder(context, viewType)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.bind(row)
        holder.itemView.setPaddingRelative(holder.itemView.paddingStart, holder.itemView.paddingTop, holder.itemView.paddingEnd, ui.context.dp(row.gapBelow))
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycled()
    }

    fun rowAt(position: Int): ThreadRow? = if (position in 0 until itemCount) getItem(position) else null

    fun positionOf(key: String): Int = currentList.indexOfFirst { it.key == key }

    private fun parts(key: String, text: String, visitor: Boolean): List<BubblePart> {
        val cacheKey = (if (visitor) "v" else "i") + key + "\u0000" + text
        rendered.get(cacheKey)?.let { return it }
        val spans = MarkdownSpans(if (visitor) visitorColors else incomingColors, density, callbacks::openUrl, ::isOwnFile, bubbleTextSize)
        return spans.render(text).also { rendered.put(cacheKey, it) }
    }

    /** Deskwoot's own stored files: the only images markdown may load. */
    private fun isOwnFile(url: String): Boolean = url.startsWith("/api/files/") || url.startsWith("/api/widget/files/")

    abstract class Holder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(row: ThreadRow)

        open fun recycled() = Unit
    }

    // ── Lines ────────────────────────────────────────────────────────────

    /** A day line (12.5/600, 0.02em) or an activity line (12.5/400, padding 8/0), centered in ink-3. */
    private inner class LineHolder(context: Context, type: Int) : Holder(FrameLayout(context)) {
        private val text = TextView(context)

        init {
            val root = itemView as FrameLayout
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            val day = type == T_DAY
            text.id = if (day) R.id.dw_day_line else R.id.dw_activity_line
            text.textStyle(12.5f, if (day) 600 else 400, ui.colors.ink3, 1.45f, if (day) 0.02f else 0f)
            text.gravity = Gravity.CENTER
            root.setPadding(0, context.dp(2) + if (day) 0 else context.dp(8), 0, 0)
            if (!day) text.setPadding(context.dp(8), 0, context.dp(8), context.dp(8))
            root.addView(text, FrameLayout.LayoutParams(MATCH, WRAP))
            if (day) ViewCompat.setAccessibilityHeading(text, true)
        }

        override fun bind(row: ThreadRow) {
            text.text = when (row) {
                is DayRow -> row.label
                is ActivityRow -> row.text
                else -> ""
            }
        }
    }

    /** "New conversation" / "Conversation closed · date" between 2 hairlines (continuous chat). */
    private inner class DividerHolder(context: Context) : Holder(LinearLayout(context)) {
        private val text = TextView(context)

        init {
            val root = itemView as LinearLayout
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(0, context.dp(2), 0, 0)
            root.id = R.id.dw_divider
            val line1 = View(context).apply { setBackgroundColor(ui.colors.line) }
            val line2 = View(context).apply { setBackgroundColor(ui.colors.line) }
            text.textStyle(12f, 400, ui.colors.ink3, 1.3f)
            text.gravity = Gravity.CENTER
            root.addView(line1, linear(0, context.dp(1), 1f).apply { width = 0 })
            root.addView(text, linear(WRAP, WRAP).apply { marginStart = context.dp(10); marginEnd = context.dp(10) })
            root.addView(line2, linear(0, context.dp(1), 1f))
            line1.minimumWidth = context.dp(16)
            line2.minimumWidth = context.dp(16)
        }

        override fun bind(row: ThreadRow) {
            text.text = (row as DividerRow).text
            itemView.contentDescription = row.text
        }
    }

    // ── Bubbles ──────────────────────────────────────────────────────────

    /** The parts of a bubble shared by both sides: the quote, the text parts and the attachments. */
    private inner class BubbleContent(private val context: Context, private val visitor: Boolean) {
        val bubble = LinearLayout(context)
        private val quote = LinearLayout(context)
        private val quoteAuthor = TextView(context)
        private val quoteText = TextView(context)
        val text = BubbleTextView(context)
        private val extra = LinearLayout(context)
        private val files = LinearLayout(context)
        private var quoteTarget: String? = null

        /** The links of the text, label and target, for one accessibility action each. */
        var links: List<Pair<String, String>> = emptyList()
            private set

        /**
         * The message's long press, also on what inside the bubble takes its
         * own touches (a quote that jumps, pictures, files); set before [bind].
         */
        var longPress: View.OnLongClickListener? = null

        private fun pressLong(view: View, on: Boolean = true) {
            view.setOnLongClickListener(if (on) longPress else null)
            view.isLongClickable = on && longPress != null
        }

        init {
            val c = ui.colors
            bubble.id = R.id.dw_bubble
            bubble.orientation = LinearLayout.VERTICAL
            bubble.setPaddingRelative(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            // The quote: 3 dp brand stripe, padding 5/9, radius 8.
            quote.id = R.id.dw_reply_quote
            quote.orientation = LinearLayout.VERTICAL
            quote.setPaddingRelative(context.dp(9 + 3), context.dp(5), context.dp(9), context.dp(5))
            quote.background = QuoteBackground(
                if (visitor) c.onBrand(0.16) else c.bg2,
                if (visitor) c.onBrand(0.85) else c.brand,
                context.dp(3).toFloat(),
                context.dp(8).toFloat(),
            )
            quoteAuthor.textStyle(12f, 600, if (visitor) c.onBrand else c.brandText)
            quoteAuthor.oneLine()
            quoteText.textStyle(13f, 400, if (visitor) c.onBrand else c.ink, 1.4f)
            quoteText.alpha = 0.85f
            quoteText.contentText()
            quoteText.maxLines = 2
            quoteText.ellipsize = TextUtils.TruncateAt.END
            quote.addView(quoteAuthor)
            quote.addView(quoteText)
            quote.setOnClickListener { quoteTarget?.let(callbacks::jumpTo) }
            bubble.addView(quote, linear(WRAP, WRAP).apply { topMargin = context.dp(2); bottomMargin = context.dp(6) })
            text.id = R.id.dw_bubble_text
            text.contentText()
            text.textStyle(14.5f, 400, if (visitor) c.onBrand else c.ink, 1.45f)
            text.setLinkTextColor(if (visitor) c.onBrand else c.brandText)
            text.highlightColor = c.withAlpha(c.brand, 0.2)
            ViewCompat.enableAccessibleClickableSpanSupport(text)
            bubble.addView(text, linear(WRAP, WRAP))
            extra.orientation = LinearLayout.VERTICAL
            bubble.addView(extra, linear(WRAP, WRAP))
            files.orientation = LinearLayout.VERTICAL
            bubble.addView(files, linear(WRAP, WRAP))
        }

        /** Fills the bubble; false when it has nothing to show (a reply that is only a link card). */
        fun bind(key: String, body: String?, readback: Readback?, quoteView: QuoteView?, attachments: List<Attachment>): Boolean {
            // Quote
            quote.visibility = if (quoteView == null) View.GONE else View.VISIBLE
            if (quoteView != null) {
                quoteAuthor.visibility = if (quoteView.author == null) View.GONE else View.VISIBLE
                quoteAuthor.text = quoteView.author
                quoteText.text = quoteView.text
                quoteTarget = quoteView.targetKey
                quote.isClickable = quoteView.targetKey != null
            }
            // A quote that does not jump leaves its touches to the message.
            pressLong(quote, on = quote.isClickable)
            // Text: markdown parts, or the readback list.
            extra.removeAllViews()
            val parts = when {
                readback != null -> emptyList()
                body.isNullOrBlank() -> emptyList()
                else -> parts(key, body, visitor)
            }
            val first = parts.firstOrNull() as? BubblePart.Text
            links = linksOf(parts)
            text.visibility = if (first != null) View.VISIBLE else View.GONE
            text.text = first?.text
            for (part in if (first != null) parts.drop(1) else parts) {
                when (part) {
                    is BubblePart.Text -> extra.addView(BubbleTextView(context).apply {
                        contentText()
                        textStyle(14.5f, 400, if (visitor) ui.colors.onBrand else ui.colors.ink, 1.45f)
                        this.text = part.text
                    }, linear(WRAP, WRAP).apply { topMargin = context.dp(6) })
                    is BubblePart.Image -> extra.addView(
                        imageView(Attachment(part.link, part.link, part.alt, com.deskwoot.sdk.model.FileType.IMAGE, "image/*", null)),
                        linear(MATCH, WRAP).apply { topMargin = context.dp(6) },
                    )
                }
            }
            if (readback != null) extra.addView(readbackView(readback, visitor))
            // Attachments: images inline, other files as rows.
            files.removeAllViews()
            val hasText = first != null || readback != null || extra.childCount > 0
            attachments.forEachIndexed { i, a ->
                val v = if (a.isImage) imageView(a) else fileRow(a)
                // A file row's touch room reaches into the gap above it and the space below, so the pills keep their places.
                val room = if (a.isImage) 0 else context.dp(FILE_ROOM)
                files.addView(v, linear(if (a.isImage) MATCH else WRAP, WRAP).apply {
                    topMargin = (if (i == 0 && !hasText) 0 else context.dp(6)) - room
                    bottomMargin = -room
                })
            }
            return hasText || attachments.isNotEmpty() || quoteView != null
        }

        private fun imageView(a: Attachment): View {
            val view = AttachmentImage(context, aspects[a.id] ?: 4f / 3f)
            view.id = R.id.dw_image_attachment
            view.contentDescription = a.fileName?.trim()?.ifEmpty { null } ?: ui.t(R.string.dw_sdk_image)
            view.setBackgroundColor(ui.colors.withAlpha(ui.colors.ink, 0.06))
            view.setOnClickListener { callbacks.openAttachment(a) }
            pressLong(view)
            val max = (context.resources.displayMetrics.widthPixels * 0.8f).toInt()
            ui.images.load(view, ui.session, a.id, a.fileUrl, max) { bitmap ->
                if (bitmap != null) {
                    val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                    aspects[a.id] = aspect
                    view.show(bitmap, aspect)
                }
            }
            return view
        }

        /** A file: 36 high to the eye, 48 to the finger; [FILE_ROOM] of the touch room sits above and below the pill. */
        private fun fileRow(a: Attachment): View {
            val c = ui.colors
            val room = context.dp(FILE_ROOM)
            return LinearLayout(context).apply {
                id = R.id.dw_file_attachment
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(8), context.dp(8) + room, context.dp(8), context.dp(8) + room)
                minimumHeight = context.dp(48)
                minimumWidth = context.dp(48)
                val tint = if (visitor) c.onBrand(0.18) else c.withAlpha(0xFF000000.toInt(), if (c.isDark) 0.2 else 0.05)
                backgroundKeepingPadding(InsetDrawable(ripple(c.withAlpha(if (visitor) c.onBrand else c.ink, 0.12), context.rounded(tint, 8f)), 0, room, 0, room))
                val ink = if (visitor) c.onBrand else c.ink
                addView(ImageView(context).apply {
                    setImageDrawable(context.icon(R.drawable.dw_ic_file, ink))
                    decorative()
                }, linear(context.dp(14), context.dp(14)))
                addView(TextView(context).apply {
                    text = a.fileName?.trim()?.ifEmpty { null } ?: ui.t(R.string.dw_file_label)
                    textStyle(12f, 400, ink)
                    oneLine()
                }, linear(WRAP, WRAP).apply { marginStart = context.dp(6) })
                role(button = true)
                setOnClickListener { callbacks.openAttachment(a) }
                pressLong(this)
            }
        }

        /** A workflow's readback: the intro, a tinted list of label and value, the ask. */
        private fun readbackView(r: Readback, visitor: Boolean): View {
            val c = ui.colors
            val ink = if (visitor) c.onBrand else c.ink
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(context).apply {
                contentText()
                text = r.intro
                textStyle(14.5f, 400, ink, 1.45f)
            })
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(12), context.dp(2), context.dp(12), context.dp(2))
                background = context.rounded(c.withAlpha(ink, 0.06), 12f)
            }
            r.rows.forEachIndexed { i, entry ->
                if (i > 0) list.addView(View(context).apply { setBackgroundColor(c.withAlpha(ink, 0.12)) }, linear(MATCH, context.dp(1)))
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, context.dp(7), 0, context.dp(7))
                }
                if (entry.label.isNotEmpty()) item.addView(TextView(context).apply {
                    text = entry.label
                    textStyle(12f, 400, if (visitor) c.onBrand(0.8) else c.ink2, 1.35f)
                })
                item.addView(TextView(context).apply {
                    text = entry.value
                    textStyle(14.5f, 400, ink, 1.45f)
                })
                list.addView(item)
            }
            column.addView(list, linear(MATCH, WRAP).apply { topMargin = context.dp(8); bottomMargin = context.dp(10) })
            column.addView(TextView(context).apply {
                text = r.ask
                textStyle(14.5f, 400, ink, 1.45f)
            })
            return column
        }

        fun recycled() {
            for (i in 0 until extra.childCount) ui.images.cancel(extra.getChildAt(i))
            for (i in 0 until files.childCount) ui.images.cancel(files.getChildAt(i))
        }
    }

    /** A reply of the bot, the team or the business: face 28, who line, bubble, link and tracking cards. */
    private inner class IncomingHolder(context: Context) : Holder(LinearLayout(context)) {
        private val face = FaceView(context, ui)
        private val column = CappedColumn(context, 0.88f, context.dp(36))
        private val who = TextView(context)
        private val content = BubbleContent(context, visitor = false)
        private val linkCard = LinearLayout(context)
        private val linkTitle = TextView(context)
        private val linkHost = TextView(context)
        private val tracking = LinearLayout(context)
        private val trackingTitle = TextView(context)
        private val trackingMeta = TextView(context)
        private val trackingExpected = TextView(context)
        private val trackingLink = TextView(context)
        private var row: IncomingRow? = null

        init {
            val c = ui.colors
            val root = itemView as LinearLayout
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.BOTTOM
            root.addView(face, linear(context.dp(28), context.dp(28)))
            who.id = R.id.dw_who_line
            who.textStyle(12f, 400, c.ink3)
            who.setPaddingRelative(context.dp(2), 0, 0, 0)
            // At a large font scale the name and the time wrap to a second line rather than losing the time.
            who.maxLines = 2
            who.ellipsize = TextUtils.TruncateAt.END
            column.addView(who, linear(WRAP, WRAP).apply { bottomMargin = context.dp(4) })
            column.addView(content.bubble, linear(WRAP, WRAP))
            // The link card.
            linkCard.id = R.id.dw_link_card
            linkCard.orientation = LinearLayout.HORIZONTAL
            linkCard.setPaddingRelative(context.dp(13), context.dp(10), context.dp(12), context.dp(10))
            linkCard.background = ripple(c.withAlpha(c.ink, 0.08), context.rounded(c.kBubble, 12f))
            linkCard.elevation = context.dp(1).toFloat() / 2
            val linkColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            linkTitle.textStyle(14f, 600, c.ink, 1.4f)
            linkTitle.contentText()
            linkTitle.maxLines = 2
            linkTitle.ellipsize = TextUtils.TruncateAt.END
            linkHost.textStyle(12f, 400, c.ink2)
            linkHost.oneLine()
            linkColumn.addView(linkTitle)
            linkColumn.addView(linkHost)
            linkCard.addView(linkColumn, linear(0, WRAP, 1f))
            linkCard.addView(ImageView(context).apply {
                setImageDrawable(context.icon(R.drawable.dw_ic_external, c.brandText))
                decorative()
            }, linear(context.dp(16), context.dp(16)).apply { marginStart = context.dp(10); topMargin = context.dp(2) })
            linkCard.role(button = true)
            column.addView(linkCard, linear(context.dp(240), WRAP).apply { topMargin = context.dp(4) })
            // The tracking card.
            tracking.id = R.id.dw_tracking_card
            tracking.orientation = LinearLayout.VERTICAL
            tracking.setPaddingRelative(context.dp(13), context.dp(10), context.dp(13), context.dp(11))
            tracking.background = context.rounded(c.kBubble, 12f)
            trackingTitle.textStyle(14f, 600, c.ink, 1.4f)
            trackingMeta.textStyle(12f, 400, c.ink2)
            trackingExpected.textStyle(12f, 400, c.ink2)
            trackingLink.id = R.id.dw_tracking_link
            trackingLink.textStyle(13f, 600, c.brandText)
            trackingLink.text = ui.t(R.string.dw_tracking_link)
            // 48 to the finger; the extra 8 above and below reach into the card's gaps, so the text keeps its place.
            trackingLink.minHeight = context.dp(48)
            trackingLink.minWidth = context.dp(48)
            trackingLink.gravity = Gravity.CENTER_VERTICAL
            trackingLink.role(button = true)
            tracking.addView(trackingTitle)
            tracking.addView(trackingMeta, linear(WRAP, WRAP).apply { topMargin = context.dp(2) })
            tracking.addView(trackingExpected)
            tracking.addView(trackingLink, linear(WRAP, WRAP).apply { topMargin = context.dp(4) - context.dp(8); bottomMargin = -context.dp(8) })
            column.addView(tracking, linear(context.dp(240), WRAP).apply { topMargin = context.dp(4) })
            root.addView(column, linear(0, WRAP, 1f).apply { marginStart = context.dp(8) })
        }

        override fun bind(row: ThreadRow) {
            row as IncomingRow
            this.row = row
            val copy = lazy(LazyThreadSafetyMode.NONE) { MessageMenu.copyText(row) }
            longPress(row, copy, column, content)
            val c = ui.colors
            face.visibility = if (row.face != null) View.VISIBLE else View.INVISIBLE
            face.setFace(row.face)
            who.visibility = if (row.who == null) View.GONE else View.VISIBLE
            if (row.who != null) {
                who.text = row.who.text
                who.typeface = com.deskwoot.sdk.ui.widgets.cachedTypeface(if (row.who.emphasized) 600 else 400)
                (who.layoutParams as LinearLayout.LayoutParams).topMargin = if (row.who.emphasized) itemView.context.dp(4) else 0
            }
            val shows = content.bind(row.key, row.text, row.readback, row.quote, row.attachments)
            content.bubble.visibility = if (shows) View.VISIBLE else View.GONE
            val bubbleColor = ui.design.incomingBubble(c)
            content.bubble.background = bubbleShape(itemView.context, bubbleColor, incoming = true, tail = row.tail)
            val card = row.linkCard
            linkCard.visibility = if (card == null) View.GONE else View.VISIBLE
            if (card != null) {
                linkTitle.text = card.title
                linkHost.text = card.host
                linkHost.visibility = if (card.host == null) View.GONE else View.VISIBLE
                linkCard.setOnClickListener { callbacks.openUrl(card.url) }
                linkCard.contentDescription = listOfNotNull(card.title, card.host).joinToString(", ")
                (linkCard.layoutParams as LinearLayout.LayoutParams).topMargin = if (shows) itemView.context.dp(4) else 0
            }
            val t = row.tracking
            tracking.visibility = if (t == null) View.GONE else View.VISIBLE
            if (t != null) {
                trackingTitle.text = t.title
                trackingMeta.text = t.meta
                trackingMeta.visibility = if (t.meta == null) View.GONE else View.VISIBLE
                trackingExpected.text = t.expected
                trackingExpected.visibility = if (t.expected == null) View.GONE else View.VISIBLE
                trackingLink.setOnClickListener { callbacks.openUrl(t.url) }
            }
            // Screen readers hear the writer, the text and the time; Copy and Reply are actions.
            itemView.contentDescription = null
            content.bubble.contentDescription = null
            ViewCompat.setAccessibilityDelegate(column, rowDelegate(row.canReply, copy, if (shows) content.links else emptyList()) { callbacks.reply(row) })
            column.isFocusable = true
            column.contentDescription = listOfNotNull(row.authorName, MessageText.preview(row.text, 400), row.linkCard?.title, row.time.ifEmpty { null }).joinToString(", ")
            column.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            itemView.setTag(R.id.dw_bubble, row.canReply)
        }

        override fun recycled() {
            content.recycled()
            ui.images.cancel(face)
        }
    }

    /** The visitor's message: brand fill at the end, the time under the last of a group, the state of a send. */
    private inner class VisitorHolder(context: Context) : Holder(LinearLayout(context)) {
        private val column = CappedColumn(context, 0.8f, 0)
        private val helpCard = LinearLayout(context)
        private val helpTitle = TextView(context)
        private val content = BubbleContent(context, visitor = true)
        private val time = TextView(context)
        private val failedLine = TextView(context)
        private val retryButton = TextView(context)
        private val discardButton = TextView(context)
        private val failedRow: FailedSendLine
        /** The key of the sending row this holder shows; the fade checks it, since a recycled holder may show another row by then. */
        private var sendingKey: String? = null
        private val fade = Runnable { if (sendingKey != null) content.bubble.animate().alpha(0.6f).setDuration(300).start() }

        init {
            val c = ui.colors
            val root = itemView as LinearLayout
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.END
            column.gravity = Gravity.END
            // The help center article the question comes from.
            helpCard.id = R.id.dw_help_article_card
            helpCard.orientation = LinearLayout.HORIZONTAL
            helpCard.gravity = Gravity.CENTER_VERTICAL
            helpCard.setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            helpCard.background = ripple(c.withAlpha(c.ink, 0.08), context.rounded(c.bg, 12f, c.line))
            val tile = ImageView(context).apply {
                setImageDrawable(context.icon(R.drawable.dw_ic_doc, c.brandText))
                background = context.rounded(c.brandSoft12, 8f)
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                decorative()
            }
            helpCard.addView(tile, linear(context.dp(32), context.dp(32)))
            val helpColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            helpColumn.addView(TextView(context).apply {
                text = ui.t(R.string.dw_help_center_article)
                textStyle(11.5f, 400, c.ink2, 1.3f)
            })
            helpTitle.textStyle(13.5f, 600, c.ink, 1.35f)
            helpTitle.contentText()
            helpTitle.maxLines = 2
            helpTitle.ellipsize = TextUtils.TruncateAt.END
            helpColumn.addView(helpTitle)
            helpCard.addView(helpColumn, linear(0, WRAP, 1f).apply { marginStart = context.dp(10) })
            column.addView(helpCard, linear(context.dp(260), WRAP).apply { bottomMargin = context.dp(4) })
            column.addView(content.bubble, linear(WRAP, WRAP))
            time.id = R.id.dw_message_time
            time.textStyle(11.5f, 400, c.ink3)
            column.addView(time, linear(WRAP, WRAP).apply { topMargin = context.dp(4) })
            failedLine.id = R.id.dw_send_failed_line
            failedLine.textStyle(12f, 400, c.error, 1.4f)
            failedLine.gravity = Gravity.END
            failedLine.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            fun action(view: TextView, id: Int, label: String, color: Int) {
                view.id = id
                view.text = label
                view.textStyle(13f, 600, color)
                view.gravity = Gravity.CENTER
                view.minHeight = context.dp(48)
                view.minWidth = context.dp(48)
                view.setPadding(context.dp(10), 0, context.dp(10), 0)
                view.background = ripple(c.withAlpha(c.ink, 0.08), null, context.rounded(c.bg2, 10f))
                view.role(button = true)
            }
            action(retryButton, R.id.dw_retry_send, ui.t(R.string.dw_try_again), c.brandText)
            // "Delete" says what happens to a message that was not sent (the iOS SDK says the same).
            action(discardButton, R.id.dw_discard_send, ui.t(R.string.dw_sdk_discard), c.ink2)
            // "Not sent", Try again, Delete; the buttons' 10 dp of padding reach past the end edge, so their words line up with the bubble.
            failedRow = FailedSendLine(context, failedLine, listOf(retryButton, discardButton), context.dp(10), context.dp(4))
            column.addView(failedRow, linear(WRAP, WRAP).apply { marginEnd = -context.dp(10) })
            root.addView(column, linear(0, WRAP, 1f))
        }

        override fun bind(row: ThreadRow) {
            row as VisitorRow
            val c = ui.colors
            main.removeCallbacks(fade)
            val copy = lazy(LazyThreadSafetyMode.NONE) { MessageMenu.copyText(row) }
            longPress(row, copy, column, content)
            sendingKey = if (row.state == SendState.SENDING) row.key else null
            content.bubble.animate().cancel()
            val article = row.helpArticle
            helpCard.visibility = if (article == null) View.GONE else View.VISIBLE
            if (article != null) {
                helpTitle.text = article.title
                helpCard.setOnClickListener { callbacks.openUrl(article.url) }
                helpCard.contentDescription = ui.t(R.string.dw_help_center_article) + ", " + article.title
            }
            content.bind(row.key, row.text, null, row.quote, row.attachments)
            content.bubble.background = bubbleShape(itemView.context, c.fill, incoming = false, tail = row.tail).edged(itemView.context, c)
            time.visibility = if (row.time.isNullOrEmpty() || row.state == SendState.FAILED) View.GONE else View.VISIBLE
            time.text = row.time
            val failed = row.state == SendState.FAILED
            // The reason in the visitor's language; Try again only where it can work.
            val failureText = if (failed) SendFailures.text(row.failure, ui.strings) else null
            failedRow.visibility = if (failed) View.VISIBLE else View.GONE
            failedLine.text = failureText
            retryButton.visibility = if (SendFailures.canRetry(row.failure)) View.VISIBLE else View.GONE
            row.pendingUuid?.let { uuid ->
                retryButton.setOnClickListener { callbacks.retry(uuid) }
                discardButton.setOnClickListener { callbacks.discard(uuid, row.text) }
            }
            // A send still out after 1.5 s fades to 60 %.
            if (row.state == SendState.SENDING) {
                val waited = System.currentTimeMillis() - row.sentAt
                if (waited >= 1_500) {
                    content.bubble.alpha = 0.6f
                } else {
                    content.bubble.alpha = 1f
                    main.postDelayed(fade, 1_500 - waited)
                }
            } else {
                content.bubble.alpha = 1f
            }
            column.contentDescription = listOfNotNull(
                ui.t(R.string.dw_you),
                MessageText.preview(row.text, 400),
                row.time,
                if (row.state == SendState.SENDING) ui.t(R.string.dw_sdk_sending) else null,
                failureText,
            ).joinToString(", ")
            column.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            column.isFocusable = true
            ViewCompat.setAccessibilityDelegate(column, rowDelegate(row.canReply, copy, content.links) { callbacks.reply(row) })
            itemView.setTag(R.id.dw_bubble, row.canReply)
        }

        override fun recycled() {
            main.removeCallbacks(fade)
            sendingKey = null
            content.bubble.animate().cancel()
            content.bubble.alpha = 1f
            content.recycled()
        }
    }

    /** A file on its way up: the visitor's bubble with the file's name and a progress line. */
    private inner class UploadHolder(context: Context) : Holder(LinearLayout(context)) {
        private val name = TextView(context)
        private val status = TextView(context)
        private val progress = ProgressLine(context, ui.colors.onBrand(0.3), ui.colors.onBrand)

        init {
            val c = ui.colors
            val root = itemView as LinearLayout
            root.id = R.id.dw_upload_row
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            root.gravity = Gravity.END
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
                background = bubbleShape(context, c.fill, incoming = false, tail = true).edged(context, c)
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(ImageView(context).apply {
                setImageDrawable(context.icon(R.drawable.dw_ic_file, c.onBrand))
                decorative()
            }, linear(context.dp(14), context.dp(14)))
            name.textStyle(13f, 600, c.onBrand)
            name.oneLine()
            top.addView(name, linear(WRAP, WRAP).apply { marginStart = context.dp(6) })
            bubble.addView(top)
            status.textStyle(12f, 400, c.onBrand(0.85))
            status.text = ui.t(R.string.dw_uploading_file)
            bubble.addView(status, linear(WRAP, WRAP).apply { topMargin = context.dp(2) })
            bubble.addView(progress, linear(context.dp(180), context.dp(3)).apply { topMargin = context.dp(8) })
            val column = CappedColumn(context, 0.8f, 0)
            column.gravity = Gravity.END
            column.addView(bubble, linear(WRAP, WRAP))
            root.addView(column, linear(0, WRAP, 1f))
            status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        override fun bind(row: ThreadRow) {
            row as UploadRow
            name.text = row.fileName
            progress.setProgress(if (row.done) 1f else row.progress)
            itemView.contentDescription = row.fileName + ", " + ui.t(R.string.dw_uploading_file)
        }
    }

    /** The typing dots beside a face, or Fynn's tool line as a shimmering pill. */
    private inner class TypingHolder(context: Context) : Holder(LinearLayout(context)) {
        private val face = FaceView(context, ui)
        private val dotsChip = FrameLayout(context)
        private val tool = ShimmerText(context, ui)

        init {
            val c = ui.colors
            val root = itemView as LinearLayout
            root.id = R.id.dw_typing
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.BOTTOM
            root.setPadding(0, 0, 0, 0)
            root.addView(face, linear(context.dp(28), context.dp(28)))
            dotsChip.setPadding(context.dp(15), context.dp(14), context.dp(15), context.dp(14))
            dotsChip.background = bubbleShape(context, ui.design.incomingBubble(c), incoming = true, tail = true)
            dotsChip.addView(TypingDots(context, ui), FrameLayout.LayoutParams(WRAP, WRAP))
            root.addView(dotsChip, linear(WRAP, WRAP).apply { marginStart = context.dp(8) })
            tool.setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
            tool.background = context.rounded(c.bg2, 999f, c.line)
            root.addView(tool, linear(WRAP, WRAP).apply { marginStart = context.dp(8); bottomMargin = context.dp(2) })
            // Screen readers hear one line: "Fynn is typing", or the tool line; the dots and the face are not read.
            for (i in 0 until root.childCount) root.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            root.isFocusable = true
        }

        override fun bind(row: ThreadRow) {
            row as TypingRow
            face.setFace(row.face)
            dotsChip.visibility = if (row.tool == null) View.VISIBLE else View.GONE
            tool.visibility = if (row.tool == null) View.GONE else View.VISIBLE
            tool.text = row.tool
            itemView.contentDescription = row.tool ?: ui.t(R.string.dw_sdk_is_typing, row.name)
        }
    }

    /** Choice and start buttons, as the design draws them. */
    private inner class ChoicesHolder(context: Context) : Holder(LinearLayout(context)) {
        private val group = LinearLayout(context)

        init {
            val root = itemView as LinearLayout
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            group.orientation = LinearLayout.VERTICAL
            if (ui.design.choiceStyle == ChoiceStyle.INDENTED_ROWS) root.setPaddingRelative(context.dp(36), 0, 0, 0)
            // Empfang's pills hug the end edge.
            if (ui.design.choiceStyle == ChoiceStyle.PILLS) group.gravity = Gravity.END
            ViewCompat.setAccessibilityPaneTitle(group, ui.t(R.string.dw_chat_options))
            group.contentDescription = ui.t(R.string.dw_chat_options)
            root.addView(group, linear(MATCH, WRAP))
        }

        override fun bind(row: ThreadRow) {
            row as ChoicesRow
            group.removeAllViews()
            val c = ui.colors
            val context = itemView.context
            val style = ui.design.choiceStyle
            row.labels.forEachIndexed { i, label ->
                val button = LinearLayout(context).apply {
                    id = R.id.dw_choice_button
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = context.dp(48)
                    role(button = true)
                    contentDescription = label
                    setOnClickListener { callbacks.choice(label, row.greeting) }
                }
                when (style) {
                    // Klartext: rows of line-2 with brand text and a chevron.
                    ChoiceStyle.INDENTED_ROWS -> {
                        button.setPaddingRelative(context.dp(14), context.dp(11), context.dp(14), context.dp(11))
                        button.background = ripple(c.brandSoft, context.rounded(c.bg, 12f, c.line2))
                    }
                    // Empfang: pills with a 1.5 brand border, no chevron; 48 dp to the finger.
                    ChoiceStyle.PILLS -> {
                        button.setPaddingRelative(context.dp(15), context.dp(14), context.dp(15), context.dp(14))
                        button.backgroundKeepingPadding(InsetDrawable(ripple(c.brandSoft, context.rounded(c.eCard, 999f, c.brandText, 1.5f)), 0, context.dp(5), 0, context.dp(5)))
                    }
                    // Direkt: full width cards in ink with a quiet chevron.
                    ChoiceStyle.CARDS -> {
                        button.setPaddingRelative(context.dp(14), context.dp(10), context.dp(12), context.dp(10))
                        button.background = ripple(c.brandSoft, context.rounded(c.bg, 14f, c.line))
                    }
                }
                button.addView(TextView(context).apply {
                    text = label
                    when (style) {
                        ChoiceStyle.INDENTED_ROWS -> textStyle(14.5f, 600, c.brandText, 1.4f)
                        ChoiceStyle.PILLS -> textStyle(14f, 600, c.brandText, 1.4f)
                        ChoiceStyle.CARDS -> textStyle(14.5f, 600, c.ink, 1.4f)
                    }
                }, if (style == ChoiceStyle.PILLS) linear(WRAP, WRAP) else linear(0, WRAP, 1f))
                if (style != ChoiceStyle.PILLS) {
                    val chevron = if (style == ChoiceStyle.CARDS) c.ink3 else c.withAlpha(c.brandText, 0.7)
                    button.addView(ImageView(context).apply {
                        setImageDrawable(context.icon(R.drawable.dw_ic_chevron_right, chevron))
                        decorative()
                        if (ui.rtl) scaleX = -1f
                    }, linear(context.dp(16), context.dp(16)).apply { marginStart = context.dp(10) })
                }
                val params = if (style == ChoiceStyle.PILLS) linear(WRAP, WRAP) else linear(MATCH, WRAP)
                group.addView(button, params.apply { if (i > 0) topMargin = context.dp(if (style == ChoiceStyle.PILLS) -2 else 8) })
            }
            if (!ui.reducedMotion && itemView.alpha == 1f && itemView.tag != row.key) {
                itemView.tag = row.key
                itemView.alpha = 0f
                itemView.translationY = context.dp(6).toFloat()
                itemView.animate().alpha(1f).translationY(0f).setDuration(160).start()
            }
        }
    }

    /** Fynn's fixed buttons in a wrapping row, and the failure line under them. */
    private inner class FixedHolder(context: Context) : Holder(LinearLayout(context)) {
        // 8 apart to the eye; each button has 7 dp of touch room above and below.
        private val flow = FlowLayout(context, context.dp(8), context.dp(8) - 2 * context.dp(7))
        private val error = TextView(context)

        init {
            val c = ui.colors
            val root = itemView as LinearLayout
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            root.orientation = LinearLayout.VERTICAL
            if (ui.design.choiceStyle == ChoiceStyle.INDENTED_ROWS) root.setPaddingRelative(context.dp(36), 0, 0, 0)
            flow.alignEnd = ui.design.choiceStyle == ChoiceStyle.PILLS
            ViewCompat.setAccessibilityPaneTitle(flow, ui.t(R.string.dw_chat_options))
            root.addView(flow, linear(MATCH, WRAP))
            error.id = R.id.dw_fixed_buttons_error
            error.textStyle(12f, 400, c.error, 1.4f)
            error.text = ui.t(R.string.dw_send_failed)
            error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            root.addView(error, linear(MATCH, WRAP).apply { topMargin = context.dp(2) })
        }

        override fun bind(row: ThreadRow) {
            row as FixedButtonsRow
            flow.removeAllViews()
            val context = itemView.context
            row.resolvedLabel?.let { flow.addView(fixedButton(context, R.id.dw_fynn_resolved, R.drawable.dw_ic_check, it, row.busy) { callbacks.resolvedButton() }) }
            row.teamLabel?.let { label -> flow.addView(fixedButton(context, R.id.dw_fynn_team, R.drawable.dw_ic_person, label, row.busy) { callbacks.teamButton(label) }) }
            error.visibility = if (row.failed) View.VISIBLE else View.GONE
        }

        private fun fixedButton(context: Context, id: Int, iconRes: Int, label: String, busy: Boolean, onTap: () -> Unit): View {
            val c = ui.colors
            // 34 high to the eye (radius 10, bg-2), 48 to the finger.
            return LinearLayout(context).apply {
                this.id = id
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(48)
                // The touch room plus 6 inside the pill, so a label that wraps at a large font stays in it.
                setPaddingRelative(context.dp(12), context.dp(13), context.dp(12), context.dp(13))
                // Klartext: bg-2 radius 10; Empfang: a pill on the card color with line-2, ink text; Direkt: bg with a hairline.
                val pill = when (ui.design.choiceStyle) {
                    ChoiceStyle.INDENTED_ROWS -> context.rounded(c.bg2, 10f)
                    ChoiceStyle.PILLS -> context.rounded(c.eCard, 999f, c.line2)
                    ChoiceStyle.CARDS -> context.rounded(c.bg, 10f, c.line)
                }
                backgroundKeepingPadding(android.graphics.drawable.InsetDrawable(ripple(c.withAlpha(c.ink, 0.1), pill), 0, context.dp(7), 0, context.dp(7)))
                addView(ImageView(context).apply {
                    setImageDrawable(context.icon(iconRes, c.ink3))
                    decorative()
                }, linear(context.dp(16), context.dp(16)))
                addView(TextView(context).apply {
                    text = label
                    textStyle(13.5f, 600, if (ui.design.choiceStyle == ChoiceStyle.INDENTED_ROWS) c.ink2 else c.ink, 1.3f)
                }, linear(WRAP, WRAP).apply { marginStart = context.dp(6) })
                role(button = true)
                contentDescription = label
                isEnabled = !busy
                alpha = if (busy) 0.6f else 1f
                setOnClickListener { if (!busy) onTap() }
            }
        }
    }

    /**
     * Direkt's block over the first message: the open
     * conversation banner, the face 48, the welcome title, the greeting with
     * its line breaks, the team faces 22 and the team's part of the team line.
     */
    private inner class GreetingBlockHolder(context: Context) : Holder(LinearLayout(context)) {
        private val banner = LinearLayout(context)
        private val bannerFace = FaceView(context, ui)
        private val bannerTitle = TextView(context)
        private val bannerPreview = TextView(context)
        private val bannerDot = View(context)

        init {
            val c = ui.colors
            val config = ui.config
            val people = ui.people
            val root = itemView as LinearLayout
            root.id = R.id.dw_greeting_block
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            root.orientation = LinearLayout.VERTICAL
            banner.id = R.id.dw_open_conversation_banner
            banner.orientation = LinearLayout.HORIZONTAL
            banner.gravity = Gravity.CENTER_VERTICAL
            banner.setPaddingRelative(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            banner.background = ripple(c.withAlpha(c.ink, 0.08), context.rounded(c.bg, 14f, c.line))
            banner.role(button = true)
            banner.addView(bannerFace, linear(context.dp(32), context.dp(32)))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            bannerTitle.textStyle(13.5f, 650, c.ink)
            bannerTitle.oneLine()
            texts.addView(bannerTitle, linear(MATCH, WRAP))
            bannerPreview.contentText()
            bannerPreview.textStyle(13f, 400, c.ink2)
            bannerPreview.oneLine()
            texts.addView(bannerPreview, linear(MATCH, WRAP))
            banner.addView(texts, linear(0, WRAP, 1f).apply { marginStart = context.dp(12) })
            bannerDot.background = context.oval(c.fill)
            bannerDot.decorative()
            banner.addView(bannerDot, linear(context.dp(9), context.dp(9)).apply { marginStart = context.dp(8) })
            banner.addView(ImageView(context).apply {
                setImageDrawable(context.icon(R.drawable.dw_ic_chevron_right, c.ink3))
                decorative()
                if (ui.rtl) scaleX = -1f
            }, linear(context.dp(16), context.dp(16)).apply { marginStart = context.dp(8) })
            root.addView(banner, linear(MATCH, WRAP).apply { bottomMargin = context.dp(14) })
            val block = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(0, 0, context.dp(2), context.dp(2))
            }
            block.addView(FaceView(context, ui).apply { setFace(people.greetingAuthor().face) }, linear(context.dp(48), context.dp(48)))
            config.welcomeTitle?.takeIf { it.isNotBlank() }?.let { title ->
                block.addView(TextView(context).apply {
                    id = R.id.dw_welcome_title
                    contentText()
                    text = title
                    textStyle(24f, 700, c.ink, 1.2f, -0.015f)
                    role(heading = true)
                }, linear(MATCH, WRAP).apply { topMargin = context.dp(16) })
            }
            (config.greeting?.takeIf { it.isNotBlank() } ?: config.welcomeTagline?.takeIf { it.isNotBlank() })?.let { greeting ->
                block.addView(TextView(context).apply {
                    id = R.id.dw_welcome_tagline
                    contentText()
                    // Direkt keeps the greeting's line breaks.
                    text = greeting.trim()
                    textStyle(15f, 400, c.ink2, 1.5f)
                }, linear(MATCH, WRAP).apply { topMargin = context.dp(10) })
            }
            val teamLine = com.deskwoot.sdk.ui.people.TeamLine.of(config, people, ui.strings)
            val teamText = if (config.botActive) teamLine.rest else teamLine.lead
            val faces = people.teamFaces()
            if (teamText != null) {
                val row = LinearLayout(context).apply {
                    id = R.id.dw_team_line
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (faces.isNotEmpty()) {
                    row.addView(com.deskwoot.sdk.ui.widgets.FaceStack(context, ui, 22, c.directCanvas).apply { setFaces(faces) }, linear(WRAP, WRAP).apply { marginEnd = context.dp(8) })
                }
                row.addView(TextView(context).apply {
                    text = teamText
                    textStyle(13f, 400, c.ink3, 1.4f)
                }, linear(0, WRAP, 1f))
                block.addView(row, linear(MATCH, WRAP).apply { topMargin = context.dp(10) })
            }
            root.addView(block, linear(MATCH, WRAP))
        }

        override fun bind(row: ThreadRow) {
            row as GreetingBlockRow
            val id = row.bannerId
            banner.visibility = if (id == null) View.GONE else View.VISIBLE
            if (id == null) return
            row.bannerFace?.let(bannerFace::setFace)
            bannerTitle.text = ui.t(R.string.dw_open_conversation_with, row.bannerName ?: ui.people.businessName)
            bannerPreview.text = row.bannerPreview
            bannerPreview.visibility = if (row.bannerPreview.isNullOrBlank()) View.GONE else View.VISIBLE
            bannerDot.visibility = if (row.bannerUnread) View.VISIBLE else View.GONE
            banner.contentDescription = listOfNotNull(bannerTitle.text, row.bannerPreview, if (row.bannerUnread) ui.t(R.string.dw_unread_message) else null).joinToString(", ")
            banner.setOnClickListener { ui.nav.openConversation(id) }
        }
    }

    /** The rating card or the transcript offer: the screen fills the slot. */
    private inner class SlotHolder(context: Context, private val type: Int) : Holder(FrameLayout(context)) {
        init {
            itemView.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        }

        override fun bind(row: ThreadRow) {
            if (type == T_CSAT) callbacks.bindCsat(itemView as FrameLayout) else callbacks.bindTranscript(itemView as FrameLayout)
        }
    }

    /**
     * A long press on a message, anywhere on its [column] and on the quote,
     * pictures and files inside its bubble, opens its menu at the bubble;
     * a row that offers neither Copy nor Reply takes no long press.
     */
    private fun longPress(row: ThreadRow, copy: Lazy<String?>, column: View, content: BubbleContent) {
        val press = if (MessageMenu.mayCopy(row) || MessageMenu.canReply(row)) {
            View.OnLongClickListener {
                val anchor = if (content.bubble.visibility == View.VISIBLE) content.bubble else column
                callbacks.messageMenu(row, copy.value, anchor, column)
            }
        } else {
            null
        }
        content.longPress = press
        column.setOnLongClickListener(press)
        column.isLongClickable = press != null
    }

    /**
     * A message is one stop for screen readers: Copy, Reply and each of its
     * links (up to [LINK_ACTIONS]) are actions, since the links inside the
     * column's description cannot be reached otherwise.
     */
    private fun rowDelegate(canReply: Boolean, copy: Lazy<String?>, links: List<Pair<String, String>>, onReply: () -> Unit) = object : androidx.core.view.AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            if (copy.value != null) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.dw_action_copy, ui.t(R.string.dw_sdk_copy)))
            if (canReply) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.dw_reply_bar, ui.t(R.string.dw_reply)))
            links.forEachIndexed { i, (label, _) ->
                LINK_ACTIONS.getOrNull(i)?.let { info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(it, label)) }
            }
        }

        override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
            val text = if (action == R.id.dw_action_copy) copy.value else null
            if (text != null) {
                callbacks.copy(text)
                return true
            }
            if (action == R.id.dw_reply_bar && canReply) {
                onReply()
                return true
            }
            val link = links.getOrNull(LINK_ACTIONS.indexOf(action))
            if (link != null) {
                callbacks.openUrl(link.second)
                return true
            }
            return super.performAccessibilityAction(host, action, args)
        }
    }

    /** The links of a bubble's text parts in reading order: the text a link shows and where it goes. */
    private fun linksOf(parts: List<BubblePart>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (part in parts) {
            val text = (part as? BubblePart.Text)?.text as? android.text.Spanned ?: continue
            text.getSpans(0, text.length, LinkSpan::class.java).sortedBy { text.getSpanStart(it) }.forEach { span ->
                val label = text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString().trim().ifEmpty { span.url }
                out += label to span.url
            }
        }
        return out
    }

    internal companion object {
        /** The touch room above and below a file row's pill. */
        private const val FILE_ROOM = 6

        /** One accessibility action id per link of a message, in reading order. */
        val LINK_ACTIONS: List<Int> = listOf(
            R.id.dw_action_link_1, R.id.dw_action_link_2, R.id.dw_action_link_3, R.id.dw_action_link_4,
            R.id.dw_action_link_5, R.id.dw_action_link_6, R.id.dw_action_link_7, R.id.dw_action_link_8,
        )

        /**
         * Where the lists of the messenger diff: a background thread (null), or for tests
         * the calling thread, so a screen test sees its rows without waiting.
         */
        @Volatile
        var diffExecutor: java.util.concurrent.Executor? = null

        fun <T> diffConfig(callback: DiffUtil.ItemCallback<T>): AsyncDifferConfig<T> =
            AsyncDifferConfig.Builder(callback).apply { diffExecutor?.let(::setBackgroundThreadExecutor) }.build()

        const val T_DAY = 1
        const val T_ACTIVITY = 2
        const val T_DIVIDER = 3
        const val T_INCOMING = 4
        const val T_VISITOR = 5
        const val T_UPLOAD = 6
        const val T_TYPING = 7
        const val T_CHOICES = 8
        const val T_FIXED = 9
        const val T_CSAT = 10
        const val T_TRANSCRIPT = 11
        const val T_GREETING = 12

        val DIFF = object : DiffUtil.ItemCallback<ThreadRow>() {
            override fun areItemsTheSame(oldItem: ThreadRow, newItem: ThreadRow): Boolean = oldItem.key == newItem.key

            @SuppressLint("DiffUtilEquals")
            override fun areContentsTheSame(oldItem: ThreadRow, newItem: ThreadRow): Boolean = oldItem == newItem
        }

        /** Radius 18; the last bubble of a group gets 6 at the corner toward its writer, mirrored right to left. */
        fun bubbleShape(context: Context, color: Int, incoming: Boolean, tail: Boolean): GradientDrawable {
            val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val small = if (tail) 6f else 18f
            // Incoming rows sit at the start, the visitor's at the end; the tail is the bottom corner on that side.
            val tailLeft = incoming != rtl
            return context.corners(color, 18f, 18f, if (tailLeft) 18f else small, if (tailLeft) small else 18f)
        }
    }
}

/** An inline image of a bubble: as wide as the bubble allows, its own aspect ratio (at most 1.5 times as high as wide), radius 8. */
@SuppressLint("ViewConstructor")
internal class AttachmentImage(context: Context, private var aspect: Float) : ImageView(context) {
    init {
        scaleType = ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.context.dp(8).toFloat())
            }
        }
        isClickable = true
        isFocusable = true
    }

    fun show(bitmap: Bitmap, ratio: Float) {
        setImageBitmap(bitmap)
        if (ratio != aspect) {
            aspect = ratio
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val width = minOf(available, context.dp(260))
        val height = (width / aspect.coerceAtLeast(1f / 1.5f)).toInt()
        setMeasuredDimension(width, height)
    }
}

/** A thin progress bar. */
internal class ProgressLine(context: Context, private val track: Int, private val bar: Int) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = height / 2f
        paint.color = track
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        paint.color = bar
        val w = width * progress
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            canvas.drawRoundRect(width - w, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        } else {
            canvas.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, paint)
        }
    }
}

/**
 * Under a message that was not sent: the reason, then the [buttons] (Try
 * again, Delete) on one line when they fit, as on iOS ("Not sent" beside
 * Try again), else the reason on a line of its own above them, [gap] below
 * the bubble. Everything hugs the end edge, mirrored right to left; the
 * buttons' [inset] of side padding reaches past that edge, the reason's
 * words stop at it.
 */
@SuppressLint("ViewConstructor")
internal class FailedSendLine(
    context: Context,
    private val reason: TextView,
    private val buttons: List<View>,
    private val inset: Int,
    private val gap: Int,
) : ViewGroup(context) {
    /** Whether the reason shares the buttons' line (known after a measure). */
    private var oneLine = true

    init {
        addView(reason)
        buttons.forEach { addView(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val room = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
        val shown = buttons.filter { it.visibility != GONE }
        shown.forEach { it.measure(free, free) }
        // At a large font the buttons share the room and their words wrap, so none leaves the screen.
        if (shown.sumOf { it.measuredWidth } > room) {
            val each = MeasureSpec.makeMeasureSpec(room / shown.size, MeasureSpec.AT_MOST)
            shown.forEach { it.measure(each, free) }
        }
        val buttonsWidth = shown.sumOf { it.measuredWidth }
        val buttonsHeight = shown.maxOfOrNull { it.measuredHeight } ?: 0
        reason.measure(free, free)
        oneLine = reason.measuredWidth + buttonsWidth <= room
        if (!oneLine) reason.measure(MeasureSpec.makeMeasureSpec((room - inset).coerceAtLeast(0), MeasureSpec.AT_MOST), free)
        val width = if (oneLine) reason.measuredWidth + buttonsWidth else maxOf(reason.measuredWidth + inset, buttonsWidth)
        val height = if (oneLine) maxOf(reason.measuredHeight, buttonsHeight) else gap + reason.measuredHeight + buttonsHeight
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        // [fromEnd]: the distance of the view's end from the end edge.
        fun place(view: View, fromEnd: Int, top: Int) {
            val right = width - fromEnd
            val left = right - view.measuredWidth
            if (rtl) view.layout(width - right, top, width - left, top + view.measuredHeight) else view.layout(left, top, right, top + view.measuredHeight)
        }
        val line = if (oneLine) b - t else buttons.filter { it.visibility != GONE }.maxOfOrNull { it.measuredHeight } ?: 0
        val buttonsTop = if (oneLine) 0 else gap + reason.measuredHeight
        var fromEnd = 0
        for (button in buttons.asReversed()) {
            if (button.visibility == GONE) continue
            place(button, fromEnd, buttonsTop + (line - button.measuredHeight) / 2)
            fromEnd += button.measuredWidth
        }
        if (oneLine) place(reason, fromEnd, (line - reason.measuredHeight) / 2) else place(reason, inset, gap)
    }
}

/** The quote's background: a tint with a stripe at the start. */
internal class QuoteBackground(private val fill: Int, private val stripe: Int, private val stripeWidth: Float, private val radius: Float) :
    android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val rect = android.graphics.RectF(b)
        path.reset()
        path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        paint.color = fill
        canvas.drawRect(rect, paint)
        paint.color = stripe
        val rtl = androidx.core.graphics.drawable.DrawableCompat.getLayoutDirection(this) == View.LAYOUT_DIRECTION_RTL
        if (rtl) canvas.drawRect(rect.right - stripeWidth, rect.top, rect.right, rect.bottom, paint) else canvas.drawRect(rect.left, rect.top, rect.left + stripeWidth, rect.bottom, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
