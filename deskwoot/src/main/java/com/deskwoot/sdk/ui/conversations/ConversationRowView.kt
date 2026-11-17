package com.deskwoot.sdk.ui.conversations

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.widgets.FaceView
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.oneLine
import com.deskwoot.sdk.ui.widgets.oval
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle

/**
 * One conversation (`ConversationRow`): face 40, the name
 * and the time, the preview (stronger while unread), the unread dot, and
 * the hairline under it (inset 62 at the start, 10 at the end).
 */
@SuppressLint("ViewConstructor")
internal class ConversationRowView(context: Context, private val ui: Ui) : LinearLayout(context) {
    private val face = FaceView(context, ui)
    private val name = TextView(context)
    private val time = TextView(context)
    private val preview = TextView(context)
    private val dot = View(context)
    private val linePaint = Paint().apply { color = ui.colors.line }
    var separator: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        id = R.id.dw_conversation_row
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(10), dp(12), dp(10), dp(12))
        val c = ui.colors
        background = ripple(c.withAlpha(c.ink, 0.08), null, context.rounded(c.bg2, 12f))
        isClickable = true
        isFocusable = true
        role(button = true)
        setWillNotDraw(false)
        addView(face, linear(dp(40), dp(40)))
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        name.textStyle(14.5f, 600, c.ink)
        name.oneLine()
        top.addView(name, linear(0, WRAP, 1f))
        time.textStyle(12.5f, 400, c.ink3)
        time.oneLine()
        top.addView(time, linear(WRAP, WRAP).apply { marginStart = dp(8) })
        column.addView(top)
        preview.oneLine()
        preview.contentText()
        column.addView(preview, linear(LayoutParams.MATCH_PARENT, WRAP).apply { topMargin = dp(2) })
        addView(column, linear(0, WRAP, 1f).apply { marginStart = dp(12) })
        dot.background = context.oval(c.fill)
        addView(dot, linear(dp(9), dp(9)).apply { marginStart = dp(10) })
    }

    fun bind(row: ConversationRow, onTap: () -> Unit) {
        val c = ui.colors
        face.setFace(row.face)
        name.text = row.name
        time.text = row.time
        preview.text = row.preview
        if (row.unread) {
            preview.textStyle(14f, 500, c.ink)
        } else {
            preview.textStyle(14f, 400, c.ink2)
        }
        dot.visibility = if (row.unread) View.VISIBLE else View.GONE
        // Read as one: name, preview, time, and "New message" when unread.
        contentDescription = listOfNotNull(row.name, row.preview, row.time, if (row.unread) ui.t(R.string.dw_unread_message) else null).joinToString(", ")
        setOnClickListener { onTap() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!separator) return
        val y = height - 1f
        val start = dp(62).toFloat()
        val end = dp(10).toFloat()
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            canvas.drawRect(end, y, width - start, height.toFloat(), linePaint)
        } else {
            canvas.drawRect(start, y, width - end, height.toFloat(), linePaint)
        }
    }
}
