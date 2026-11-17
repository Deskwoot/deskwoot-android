package com.deskwoot.sdk.ui.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TabStopSpan
import android.view.View
import com.deskwoot.sdk.ui.widgets.cachedTypeface

/** The colors a bubble's markdown is drawn in. */
internal data class MarkdownColors(
    val text: Int,
    val link: Int,
    val muted: Int,
    val stripe: Int,
    val codeBackground: Int,
)

/** A part of a bubble: text, or an image of Deskwoot's own files (shown capped to the bubble's width). */
internal sealed class BubblePart {
    data class Text(val text: CharSequence) : BubblePart()

    data class Image(val link: String, val alt: String) : BubblePart()
}

/**
 * Draws [Markdown] as spans: paragraphs with a small gap, real headings,
 * lists with bullets and numbers, task boxes, quotes with a stripe, code in
 * monospace on a tint, tables as aligned rows, links that open outside
 * (the web leaves all of this unstyled). Images load only
 * from Deskwoot's own file routes; any other image is a
 * link named by its alt text.
 */
internal class MarkdownSpans(
    private val colors: MarkdownColors,
    private val density: Float,
    private val onLink: (String) -> Unit,
    private val isOwnFile: (String) -> Boolean,
    /** The bubble's text size in pixels, to measure the columns of a table. */
    private val textSizePx: Float = 14.5f * density,
) {
    fun render(source: String): List<BubblePart> {
        val parts = ArrayList<BubblePart>()
        val out = SpannableStringBuilder()
        fun flushText() {
            trimTrailingNewlines(out)
            if (out.isNotEmpty()) parts += BubblePart.Text(SpannableStringBuilder(out))
            out.clear()
            out.clearSpans()
        }
        val blocks = Markdown.parse(source)
        blocks.forEachIndexed { index, block ->
            // An image alone in a paragraph is drawn as a picture between the text parts.
            val lone = (block as? Markdown.Block.Paragraph)?.inlines?.singleOrNull() as? Markdown.Inline.Image
            if (lone != null && isOwnFile(lone.url)) {
                flushText()
                parts += BubblePart.Image(lone.url, lone.alt)
                return@forEachIndexed
            }
            if (out.isNotEmpty()) out.append('\n')
            val start = out.length
            appendBlock(out, block, 0)
            if (index < blocks.lastIndex) gapAfter(out, start)
        }
        flushText()
        return parts
    }

    private fun appendBlock(out: SpannableStringBuilder, block: Markdown.Block, depth: Int) {
        when (block) {
            is Markdown.Block.Paragraph -> appendInlines(out, block.inlines)
            is Markdown.Block.Heading -> {
                val start = out.length
                appendInlines(out, block.inlines)
                val size = when (block.level) {
                    1 -> 1.3f
                    2 -> 1.18f
                    3 -> 1.08f
                    else -> 1f
                }
                out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(TypefaceSpanCompat(cachedTypeface(700)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            is Markdown.Block.ListBlock -> block.items.forEachIndexed { i, item ->
                if (i > 0) out.append('\n')
                val start = out.length
                val marker = when {
                    item.checked == true -> "☑"
                    item.checked == false -> "☐"
                    block.ordered -> "${block.start + i}."
                    else -> null
                }
                item.blocks.forEachIndexed { j, inner ->
                    if (j > 0) out.append('\n')
                    appendBlock(out, inner, depth + 1)
                }
                if (out.length == start) out.append(' ')
                out.setSpan(ListMarkerSpan(marker, colors.muted, (if (marker != null && marker.length > 2) 28 else 22) * density), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            is Markdown.Block.Quote -> {
                val start = out.length
                block.blocks.forEachIndexed { j, inner ->
                    if (j > 0) out.append('\n')
                    appendBlock(out, inner, depth + 1)
                }
                out.setSpan(QuoteStripeSpan(colors.stripe, 3 * density, 10 * density), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(colors.muted), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            is Markdown.Block.Code -> {
                val start = out.length
                out.append(block.text.ifEmpty { " " })
                out.setSpan(TypefaceSpanCompat(Typeface.MONOSPACE), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.88f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(CodeBlockSpan(colors.codeBackground, 8 * density), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            is Markdown.Block.Table -> appendTable(out, block)
            Markdown.Block.Rule -> {
                val start = out.length
                out.append(" ")
                out.setSpan(RuleSpan(colors.stripe, density), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /**
     * A table as a simple grid: one line per row, the columns lined up on
     * tab stops at the width of their widest cell, the header bold over a
     * hairline. A table wider than the bubble wraps its rows.
     */
    private fun appendTable(out: SpannableStringBuilder, table: Markdown.Block.Table) {
        val rows = listOf(table.header) + table.rows
        val cols = rows.maxOf { it.size }.coerceIn(1, MAX_TABLE_COLUMNS)
        val cells = rows.map { row -> List(cols) { c -> SpannableStringBuilder().also { b -> row.getOrNull(c)?.let { appendInlines(b, it) } } } }
        val plain = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textSizePx }
        val bold = TextPaint(plain).apply { typeface = cachedTypeface(700) }
        val widths = FloatArray(cols)
        cells.forEachIndexed { r, row ->
            row.forEachIndexed { c, cell -> widths[c] = maxOf(widths[c], (if (r == 0) bold else plain).measureText(cell, 0, cell.length)) }
        }
        val start = out.length
        cells.forEachIndexed { r, row ->
            if (r > 0) out.append('\n')
            val rowStart = out.length
            row.forEachIndexed { c, cell ->
                if (c > 0) out.append('\t')
                out.append(cell)
            }
            if (out.length == rowStart) out.append(' ')
            if (r == 0) {
                out.setSpan(TypefaceSpanCompat(cachedTypeface(700)), rowStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RowLineSpan(colors.stripe, density), rowStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val gap = 16 * density
        var x = 0f
        for (c in 1 until cols) {
            x += widths[c - 1] + gap
            out.setSpan(TabStopSpan.Standard(kotlin.math.ceil(x).toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun appendInlines(out: SpannableStringBuilder, inlines: List<Markdown.Inline>) {
        for (inline in inlines) {
            val start = out.length
            when (inline) {
                is Markdown.Inline.Text -> out.append(inline.text)
                is Markdown.Inline.Bold -> {
                    appendInlines(out, inline.children)
                    out.setSpan(TypefaceSpanCompat(cachedTypeface(700)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Markdown.Inline.Italic -> {
                    appendInlines(out, inline.children)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Markdown.Inline.Strike -> {
                    appendInlines(out, inline.children)
                    out.setSpan(StrikethroughSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Markdown.Inline.Code -> {
                    out.append(inline.text)
                    out.setSpan(TypefaceSpanCompat(Typeface.MONOSPACE), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.9f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(colors.codeBackground), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Markdown.Inline.Link -> {
                    appendInlines(out, inline.children)
                    if (out.length == start) out.append(inline.url)
                    out.setSpan(LinkSpan(inline.url, colors.link, onLink), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Markdown.Inline.Image -> {
                    // Not one of Deskwoot's files, or inside a paragraph: a link named by its alt text.
                    out.append(inline.alt.ifEmpty { inline.url })
                    val url = Markdown.safeUrl(inline.url)
                    if (url != null || isOwnFile(inline.url)) {
                        out.setSpan(LinkSpan(url ?: inline.url, colors.link, onLink), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                Markdown.Inline.Break -> out.append('\n')
            }
        }
    }

    /** Half a line of space under a block (the paragraph's last line grows). */
    private fun gapAfter(out: SpannableStringBuilder, start: Int) {
        if (out.length <= start) return
        out.setSpan(BlockGapSpan((6 * density).toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun trimTrailingNewlines(out: SpannableStringBuilder) {
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
    }
}

/** A link: underlined, in the bubble's link color, opened by the bubble's touch handler. */
internal class LinkSpan(val url: String, private val color: Int, private val onLink: (String) -> Unit) : ClickableSpan() {
    override fun onClick(widget: View) = onLink(url)

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
        ds.isUnderlineText = true
    }
}

internal class TypefaceSpanCompat(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) = apply(tp)

    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    private fun apply(tp: TextPaint) {
        val old = tp.typeface
        val italic = old != null && old.isItalic
        tp.typeface = if (italic) Typeface.create(typeface, Typeface.ITALIC) else typeface
    }
}

/** A bullet, a number or a task box in the margin before a list item. */
internal class ListMarkerSpan(private val marker: String?, private val color: Int, private val width: Float) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = width.toInt()

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        if (!first || (text as? Spanned)?.getSpanStart(this) != start) return
        val old = p.color
        val style = p.style
        p.color = color
        if (marker == null) {
            val r = p.textSize * 0.14f
            val cx = x + dir * (width * 0.35f)
            val cy = (top + baseline) / 2f + p.textSize * 0.08f
            p.style = Paint.Style.FILL
            c.drawCircle(cx, cy, r, p)
        } else {
            val w = p.measureText(marker)
            val left = if (dir > 0) x.toFloat() else x - w
            c.drawText(marker, left, baseline.toFloat(), p)
        }
        p.color = old
        p.style = style
    }
}

/** A 3 dp stripe at the start of a quote (`--line-2`). */
internal class QuoteStripeSpan(private val color: Int, private val stripe: Float, private val gap: Float) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = (stripe + gap).toInt()

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        val old = p.color
        val style = p.style
        p.color = color
        p.style = Paint.Style.FILL
        val right = x + dir * stripe
        c.drawRect(minOf(x.toFloat(), right), top.toFloat(), maxOf(x.toFloat(), right), bottom.toFloat(), p)
        p.color = old
        p.style = style
    }
}

/** A tinted box behind the lines of a code block, with an inset at the start. */
internal class CodeBlockSpan(private val color: Int, private val inset: Float) : LeadingMarginSpan, LineBackgroundSpan {
    override fun getLeadingMargin(first: Boolean): Int = inset.toInt()

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) = Unit

    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        val old = paint.color
        paint.color = color
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        paint.color = old
    }
}

/** A horizontal rule: a hairline across the line. */
internal class RuleSpan(private val color: Int, private val density: Float) : LineBackgroundSpan {
    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        val old = paint.color
        paint.color = color
        val y = (top + bottom) / 2f
        canvas.drawRect(left.toFloat(), y, right.toFloat(), y + density, paint)
        paint.color = old
    }
}

/** A hairline under a table's header row. */
internal class RowLineSpan(private val color: Int, private val density: Float) : LineBackgroundSpan {
    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        val old = paint.color
        paint.color = color
        canvas.drawRect(left.toFloat(), bottom - density, right.toFloat(), bottom.toFloat(), paint)
        paint.color = old
    }
}

/** Extra space under the last line of a block. */
internal class BlockGapSpan(private val gap: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val spanEnd = (text as Spanned).getSpanEnd(this)
        if (end == spanEnd || end == spanEnd + 1) {
            fm.descent += gap
            fm.bottom += gap
        }
    }
}

/** More columns than this are left out of a bubble's grid. */
private const val MAX_TABLE_COLUMNS: Int = 8
