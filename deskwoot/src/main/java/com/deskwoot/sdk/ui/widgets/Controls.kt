package com.deskwoot.sdk.ui.widgets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.HeaderStyle
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.people.Face
import com.deskwoot.sdk.ui.theme.UiColors

/**
 * A square icon button (`.ib`): 36 x 36 to the eye with a
 * radius of 10, 48 x 48 to the finger.
 */
@SuppressLint("ViewConstructor")
internal class IconButton(context: Context, private val ui: Ui, iconRes: Int, iconSize: Int, label: String, color: Int = ui.colors.ink2) :
    ImageView(context) {
    init {
        val c = ui.colors
        val inset = dp(6)
        val pad = (dp(48) - dp(iconSize)) / 2
        setPadding(pad, pad, pad, pad)
        backgroundKeepingPadding(InsetDrawable(ripple(c.withAlpha(c.ink, 0.12), null, context.rounded(c.bg3, 10f)), inset))
        scaleType = ScaleType.FIT_CENTER
        setImageDrawable(context.icon(iconRes, color))
        contentDescription = label
        isFocusable = true
        isClickable = true
        role(button = true)
        layoutParams = linear(dp(48), dp(48))
    }

    fun tint(color: Int, iconRes: Int) {
        setImageDrawable(context.icon(iconRes, color))
    }
}

/**
 * The primary button (`.btn-primary`): full width, 48 high, radius 12, the
 * brand fill, 15/600 on-brand, the icon 8 after (or before) the label, both
 * centered together.
 */
@SuppressLint("ViewConstructor")
internal class PrimaryButton(context: Context, private val ui: Ui, text: String, iconRes: Int? = null, iconAtStart: Boolean = false) :
    LinearLayout(context) {
    private val label = TextView(context)

    var text: CharSequence
        get() = label.text
        set(value) {
            label.text = value
            contentDescription = value
        }

    init {
        val c = ui.colors
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPaddingRelative(dp(16), dp(10), dp(16), dp(10))
        background = ripple(c.onBrand(0.18), context.rounded(c.fill, 12f).edged(context, c))
        label.textStyle(15f, 600, c.onBrand, 1.25f)
        label.gravity = Gravity.CENTER
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val icon = iconRes?.let { res ->
            ImageView(context).apply {
                setImageDrawable(context.icon(res, c.onBrand))
                decorative()
            }
        }
        if (icon != null && iconAtStart) addView(icon, linear(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(label, linear(WRAP, WRAP, 0f).apply { weight = 0f })
        if (icon != null && !iconAtStart) addView(icon, linear(dp(18), dp(18)).apply { marginStart = dp(8) })
        this.text = text
        isFocusable = true
        isClickable = true
        role(button = true)
    }

    /** Empfang's pill: radius 999, 46 high, padding 0 22, a soft shadow (floating "New conversation", ask bars). */
    fun pill(height: Int = 46) {
        val c = ui.colors
        minimumHeight = dp(height)
        setPaddingRelative(dp(22), 0, dp(22), 0)
        background = ripple(c.onBrand(0.18), context.rounded(c.fill, 999f).edged(context, c))
        elevation = dp(3).toFloat()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.55f
    }
}

/**
 * A brand fill that reads under 3:1 against the panel gets its 1 dp edge
 * ([UiColors.fillEdge]): the visitor's bubbles, the send buttons (the
 * composer's and Empfang's round glyph) and primary buttons.
 */
internal fun GradientDrawable.edged(context: Context, colors: UiColors): GradientDrawable {
    colors.fillEdge?.let { setStroke(context.dp(1), it) }
    return this
}

/**
 * The Klartext sub header (`k-head`): back, an optional
 * face, title and sub line (with the online dot), an optional extra
 * button, close. The status bar's height is added above it.
 */
@SuppressLint("ViewConstructor")
internal class SubHeader(
    context: Context,
    private val ui: Ui,
    withFace: Boolean,
    extra: IconButton? = null,
    val style: HeaderStyle = HeaderStyle.PLAIN,
) : LinearLayout(context) {
    val back: IconButton
    val close: IconButton
    val face: FaceView? = if (withFace) FaceView(context, ui) else null
    val title = TextView(context)
    val subtitle = TextView(context)
    private val dot = View(context)
    private val subRow = LinearLayout(context)
    private val column = LinearLayout(context)

    init {
        id = R.id.dw_header
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val c = ui.colors
        val onBand = style == HeaderStyle.BAND
        // Icons on Empfang's band are on-band at 88 %.
        val iconColor = if (onBand) c.onBand(0.88) else c.ink2
        // 10 around the 36 px buttons on the web; the 48 dp touch targets take 6 of it.
        setPaddingRelative(dp(4), dp(4), dp(4), dp(4) + dp(1))
        minimumHeight = dp(
            when (style) {
                HeaderStyle.PLAIN -> 62
                HeaderStyle.BAR -> 60
                HeaderStyle.BAND -> 64
                HeaderStyle.SHEET -> 56
            },
        )
        back = IconButton(context, ui, R.drawable.dw_ic_chevron_left, 22, ui.t(R.string.dw_back), iconColor).apply { id = R.id.dw_back }
        close = IconButton(context, ui, R.drawable.dw_ic_close, 20, ui.t(R.string.dw_close), iconColor).apply { id = R.id.dw_close }
        addView(back)
        face?.let {
            it.id = R.id.dw_header_face
            val size = if (onBand) 38 else 36
            addView(it, linear(dp(size), dp(size)).apply { marginStart = dp(4) })
        }
        column.orientation = VERTICAL
        column.gravity = Gravity.CENTER_VERTICAL
        title.id = R.id.dw_header_title
        when (style) {
            HeaderStyle.PLAIN -> title.textStyle(15f, 600, c.ink)
            HeaderStyle.BAR -> title.textStyle(16f, 650, c.ink)
            HeaderStyle.BAND -> title.textStyle(15.5f, 650, c.onBand)
            HeaderStyle.SHEET -> title.textStyle(18f, 700, c.ink, 1.45f, -0.01f)
        }
        title.oneLine()
        title.role(heading = true)
        column.addView(title, linear(MATCH, WRAP))
        subRow.orientation = HORIZONTAL
        subRow.gravity = Gravity.CENTER_VERTICAL
        // On the band the online dot wears a 2 px ring in the band's color.
        dot.background = if (onBand) context.oval(c.ok).also { it.setStroke(dp(2), c.band) } else context.oval(c.ok)
        dot.decorative()
        val dotSize = if (onBand) 12 else 8
        subRow.addView(dot, linear(dp(dotSize), dp(dotSize)).apply { marginEnd = dp(6) })
        subtitle.id = R.id.dw_header_subtitle
        subtitle.textStyle(12.5f, 400, if (onBand) c.onBand(0.8) else c.ink3)
        subtitle.oneLine()
        subRow.addView(subtitle, linear(0, WRAP, 1f))
        column.addView(subRow, linear(MATCH, WRAP).apply { topMargin = dp(1) })
        addView(column, linear(0, WRAP, 1f).apply { marginStart = dp(10); marginEnd = dp(4) })
        if (extra != null) addView(extra)
        addView(close)
        background = when (style) {
            HeaderStyle.BAND -> android.graphics.drawable.ColorDrawable(c.band)
            HeaderStyle.SHEET -> android.graphics.drawable.ColorDrawable(c.bg)
            else -> BottomLine(c.bg, c.line, dp(1))
        }
        if (style == HeaderStyle.SHEET) showBack(false)
        setLine(null, false)
    }

    /** The sub line and its online dot; no sub line hides the row. */
    fun setLine(text: String?, online: Boolean) {
        subRow.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        subtitle.text = text
        dot.visibility = if (online) View.VISIBLE else View.GONE
    }

    /** A sheet's head shows back only inside the sheet (a collection, an article); the title then starts 18 from the edge. */
    fun showBack(visible: Boolean) {
        back.visibility = if (visible) View.VISIBLE else View.GONE
        (column.layoutParams as? LayoutParams)?.let {
            it.marginStart = if (visible) dp(10) else dp(14)
            column.layoutParams = it
        }
    }
}

/** A background with a hairline at the bottom (header bottoms). */
internal class BottomLine(private val fill: Int, private val line: Int, private val width: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = fill
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        paint.color = line
        canvas.drawRect(b.left.toFloat(), (b.bottom - width).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/** A background with a hairline at the top (the composer, list footers). */
internal class TopLine(private val fill: Int, private val line: Int, private val width: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = fill
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        paint.color = line
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), (b.top + width).toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/**
 * Klartext's segmented control: a bg-3 track with 2
 * equal segments, the selected one on bg with a hairline and a soft shadow.
 * The track shows 42 high; each segment takes 48 to the finger, the extra 3 above and below the track stay transparent.
 */
@SuppressLint("ViewConstructor")
internal class Segmented(context: Context, private val ui: Ui, labels: List<String>, ids: List<Int>, private val onSelect: (Int) -> Unit) :
    LinearLayout(context) {
    private val segments = labels.mapIndexed { index, label ->
        TextView(context).apply {
            id = ids[index]
            text = label
            textStyle(14f, 600, ui.colors.ink2)
            gravity = Gravity.CENTER
            minHeight = dp(TOUCH)
            oneLine()
            isClickable = true
            isFocusable = true
            role(button = true)
            setOnClickListener { select(index, notify = true) }
        }
    }
    private var selected = -1

    init {
        orientation = HORIZONTAL
        val c = ui.colors
        background = InsetDrawable(context.rounded(c.bg3, 12f), 0, dp(SLACK), 0, dp(SLACK))
        setPadding(dp(3), 0, dp(3), 0)
        ViewCompat.setAccessibilityPaneTitle(this, ui.t(R.string.dw_sections))
        segments.forEach { addView(it, linear(0, dp(TOUCH), 1f)) }
    }

    fun select(index: Int, notify: Boolean = false) {
        if (index == selected) return
        selected = index
        val c = ui.colors
        // The pill sits 3 inside the track, as before the touch height grew.
        val inset = dp(SLACK + 3)
        segments.forEachIndexed { i, tv ->
            val on = i == index
            tv.setTextColor(if (on) c.ink else c.ink2)
            tv.isSelected = on
            tv.background = if (on) {
                InsetDrawable(context.rounded(c.bg, 9f, c.line), 0, inset, 0, inset)
            } else {
                InsetDrawable(ripple(c.withAlpha(c.ink, 0.08), null, context.rounded(c.bg3, 9f)), 0, inset, 0, inset)
            }
        }
        if (notify) onSelect(index)
    }

    internal companion object {
        /** Each segment's touch height. */
        const val TOUCH: Int = 48

        /** Transparent space above and below the 42 high track; the screen subtracts it from its own spacing. */
        const val SLACK: Int = (TOUCH - 42) / 2
    }
}

/**
 * "Powered by **Deskwoot**": 11.5 ink-3, the name 650
 * ink-2; a tap opens deskwoot.com. 48 high to the finger: the
 * text keeps its place under what sits above it, the extra height is empty
 * space below it.
 */
@SuppressLint("ViewConstructor")
internal class PoweredBy(context: Context, ui: Ui) : TextView(context) {
    init {
        id = R.id.dw_powered_by
        val c = ui.colors
        textStyle(11.5f, 400, c.ink3)
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val lead = ui.t(R.string.dw_powered_by)
        val text = SpannableStringBuilder(lead).append(' ')
        val start = text.length
        text.append("Deskwoot")
        text.setSpan(WeightSpan(650), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(c.ink2), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        this.text = text
        // The line sat centered in 36 with 8 above and 10 below: its top was 10 down. It stays there.
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minHeight = dp(48)
        val url = ui.config.poweredByUrl?.takeIf { it.startsWith("https://deskwoot.com/") } ?: "https://deskwoot.com/"
        setOnClickListener { ui.nav.openUrl(url) }
        role(button = true)
    }
}

/** A run of text in a CSS weight. */
internal class WeightSpan(private val weight: Int) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = cachedTypeface(weight)
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = cachedTypeface(weight)
    }
}

/** Faces in a row, each overlapping the previous by 8 with a 2 dp ring in the surface color. */
@SuppressLint("ViewConstructor")
internal class FaceStack(context: Context, private val ui: Ui, private val size: Int, private val ringColor: Int) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        decorative()
    }

    fun setFaces(faces: List<Face>) {
        removeAllViews()
        faces.forEachIndexed { index, face ->
            val view = FaceView(context, ui)
            view.ring = ringColor
            view.setFace(face)
            // The ring sits outside the face: 2 dp more on each side.
            val lp = linear(dp(size + 4), dp(size + 4))
            if (index > 0) lp.marginStart = -dp(8 + 2)
            addView(view, lp)
        }
    }
}

/**
 * The typing dots: 3 dots of 6 dp in ink-3 that rise
 * 4 dp and brighten, 1.2 s, the dots 0.15 s apart. Still with reduced motion.
 */
@SuppressLint("ViewConstructor")
internal class TypingDots(context: Context, private val ui: Ui) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.colors.ink3 }
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(6 * 3 + 4 * 2), dp(10))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ui.reducedMotion) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(3).toFloat()
        val baseY = height - r - dp(1)
        for (i in 0..2) {
            var t = (phase - i * 0.125f) % 1f
            if (t < 0) t += 1f
            // ease-in-out up to 30 % of the loop, back down by 60 %.
            val lift = when {
                ui.reducedMotion -> 0f
                t < 0.3f -> easeInOut(t / 0.3f)
                t < 0.6f -> 1f - easeInOut((t - 0.3f) / 0.3f)
                else -> 0f
            }
            paint.alpha = ((0.45f + 0.55f * lift) * 255).toInt()
            val cx = r + i * (r * 2 + dp(4))
            canvas.drawCircle(cx, baseY - lift * dp(4), r, paint)
        }
    }

    private fun easeInOut(x: Float): Float = (1 - kotlin.math.cos(Math.PI * x).toFloat()) / 2
}

/** Fynn's tool line: the text sweeps from ink-3 over ink and back every 2 s. */
@SuppressLint("ViewConstructor")
internal class ShimmerText(context: Context, private val ui: Ui) : TextView(context) {
    private var animator: ValueAnimator? = null
    private val matrixShift = Matrix()
    private var shader: LinearGradient? = null

    init {
        textStyle(13f, 400, ui.colors.ink3, 1.35f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val c = ui.colors
        shader = LinearGradient(0f, 0f, w * 2.5f, 0f, intArrayOf(c.ink3, c.ink3, c.ink, c.ink3, c.ink3), floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
        if (!ui.reducedMotion) paint.shader = shader
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ui.reducedMotion) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val w = width.toFloat()
                matrixShift.setTranslate(-w * 2.5f + (it.animatedValue as Float) * w * 2.5f + w * 0.75f, 0f)
                shader?.setLocalMatrix(matrixShift)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

/** 3 skeleton rows of 2 bars that pulse while a list loads. */
@SuppressLint("ViewConstructor")
internal class SkeletonRows(context: Context, ui: Ui) : LinearLayout(context) {
    private var animator: ValueAnimator? = null
    private val reduced = ui.reducedMotion

    init {
        orientation = VERTICAL
        setPadding(0, dp(18), 0, dp(18))
        decorative()
        repeat(3) { row ->
            val group = LinearLayout(context).apply { orientation = VERTICAL }
            group.addView(View(context).apply { background = context.rounded(ui.colors.bg2, 8f) }, linear(0, dp(12)))
            group.addView(View(context).apply { background = context.rounded(ui.colors.bg2, 8f) }, linear(0, dp(12)).apply { topMargin = dp(8) })
            addView(group, linear(MATCH, WRAP).apply { if (row > 0) topMargin = dp(18) })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        for (i in 0 until childCount) {
            val group = getChildAt(i) as LinearLayout
            (group.getChildAt(0).layoutParams as LayoutParams).width = (w * 0.8f).toInt()
            (group.getChildAt(1).layoutParams as LayoutParams).width = (w * 0.45f).toInt()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (reduced) return
        animator = ValueAnimator.ofFloat(1f, 0.5f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

/** A section label (`.lbl`): 13/600 ink-2. */
internal fun Context.sectionLabel(ui: Ui, text: String): TextView = TextView(this).apply {
    this.text = text
    textStyle(13f, 600, ui.colors.ink2)
    role(heading = true)
}

/** A frame that only holds one child; handy for swapping bodies. */
internal fun Context.box(): FrameLayout = FrameLayout(this)
