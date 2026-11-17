package com.deskwoot.sdk.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.text.Layout
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/**
 * A bubble's text: as wide as its widest line (a wrapped TextView would
 * take the whole width it was offered), and a tap on a link opens it while
 * any other touch passes to the row (swipe to reply, long press).
 */
@SuppressLint("ViewConstructor")
internal class BubbleTextView(context: Context) : TextView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val layout = layout ?: return
        if (layout.lineCount <= 1 || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return
        var widest = 0f
        for (i in 0 until layout.lineCount) {
            val lineWidth = if (layout.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT) {
                layout.width - layout.getLineLeft(i)
            } else {
                layout.getLineRight(i)
            }
            widest = max(widest, lineWidth)
        }
        val width = ceil(widest).toInt() + compoundPaddingLeft + compoundPaddingRight
        if (width < measuredWidth) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val text = text as? Spanned ?: return super.onTouchEvent(event)
        val layout = layout ?: return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_DOWN) {
            val x = event.x - totalPaddingLeft + scrollX
            val y = event.y - totalPaddingTop + scrollY
            val line = layout.getLineForVertical(y.toInt())
            val offset = layout.getOffsetForHorizontal(line, x)
            val inLine = x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)
            val link = if (inLine) text.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull() else null
            if (link != null) {
                if (event.action == MotionEvent.ACTION_UP) link.onClick(this)
                return true
            }
        }
        return false
    }
}

/**
 * A column at most [fraction] of the row it sits in wide (a bubble's 88 %
 * or 80 %). [reserve] is what the row puts before it
 * (the face and its gap).
 */
@SuppressLint("ViewConstructor")
internal class CappedColumn(context: Context, private val fraction: Float, private val reserve: Int) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val row = available + reserve
        val cap = (row * fraction).toInt() - reserve
        val width = minOf(available, cap).coerceAtLeast(0)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec)
    }
}

/**
 * Children in rows that wrap, [gap] apart in a row and [rowGap] between rows
 * (negative when the children carry transparent touch room above and
 * below), mirrored right to left (Fynn's fixed buttons).
 */
internal class FlowLayout(context: Context, private val gap: Int, private val rowGap: Int = gap) : ViewGroup(context) {
    /** Rows hug the end edge (Empfang's end aligned pills). */
    var alignEnd: Boolean = false
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + rowGap
                rowHeight = 0
            }
            x += child.measuredWidth + gap
            widest = max(widest, x - gap)
            rowHeight = max(rowHeight, child.measuredHeight)
        }
        val height = y + rowHeight + paddingTop + paddingBottom
        val width = if (alignEnd) MeasureSpec.getSize(widthMeasureSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val maxWidth = width - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        // Rows first, so a row can be pushed to the end edge.
        val rows = ArrayList<MutableList<View>>()
        var x = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (rows.isEmpty() || (x > 0 && x + child.measuredWidth > maxWidth)) {
                rows += ArrayList<View>()
                x = 0
            }
            rows.last() += child
            x += child.measuredWidth + gap
        }
        var y = paddingTop
        for (row in rows) {
            val rowWidth = row.sumOf { it.measuredWidth } + gap * (row.size - 1)
            var offset = if (alignEnd) maxWidth - rowWidth else 0
            var rowHeight = 0
            for (child in row) {
                val left = if (rtl) width - paddingRight - offset - child.measuredWidth else paddingLeft + offset
                child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
                offset += child.measuredWidth + gap
                rowHeight = max(rowHeight, child.measuredHeight)
            }
            y += rowHeight + rowGap
        }
    }
}
