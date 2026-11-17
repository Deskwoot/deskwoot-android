package com.deskwoot.sdk.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.roundToInt

// Small helpers for building the screens in code. Sizes are the web's px
// as dp, font sizes as sp, so the system font scale works.

internal fun Context.dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

internal fun Context.dp(value: Int): Int = dp(value.toFloat())

internal fun Context.dpF(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

internal fun View.dp(value: Float): Int = context.dp(value)

internal fun View.dp(value: Int): Int = context.dp(value)

/** A typeface of a CSS weight: the exact weight from Android 9 on, else regular, medium (500) or bold (600 and up). */
internal fun weightTypeface(weight: Int, monospace: Boolean = false): Typeface {
    val base = if (monospace) Typeface.MONOSPACE else Typeface.SANS_SERIF
    if (Build.VERSION.SDK_INT >= 28) return Typeface.create(base, weight, false)
    return when {
        monospace -> if (weight >= 600) Typeface.create(base, Typeface.BOLD) else base
        weight >= 600 -> Typeface.DEFAULT_BOLD
        weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.DEFAULT
    }
}

private val typefaces = HashMap<Int, Typeface>()

internal fun cachedTypeface(weight: Int): Typeface = typefaces.getOrPut(weight) { weightTypeface(weight) }

/**
 * Size (sp), CSS weight, color, line height as a multiple of the size and
 * letter spacing in em: the web's type roles.
 */
internal fun TextView.textStyle(size: Float, weight: Int, color: Int, lineHeight: Float = 1.45f, letterSpacing: Float = 0f) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    typeface = cachedTypeface(weight)
    setTextColor(color)
    this.letterSpacing = letterSpacing
    applyLineHeight(lineHeight)
    includeFontPadding = false
}

/** A line height of [multiple] times the text size, on every API level. */
internal fun TextView.applyLineHeight(multiple: Float) {
    val wanted = textSize * multiple
    val natural = paint.getFontMetricsInt(null).toFloat()
    setLineSpacing(wanted - natural, 1f)
}

internal fun TextView.oneLine() {
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

/** A rounded rectangle; [radius] in dp; a 1 dp border when [stroke] is given. */
internal fun Context.rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(radius)
        setColor(color)
        if (stroke != null) setStroke(dp(strokeWidth).coerceAtLeast(1), stroke)
    }

/** Corners in dp: top start, top end, bottom end, bottom start (mirrored for right to left by the caller). */
internal fun Context.corners(color: Int, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        val tl = dpF(topLeft)
        val tr = dpF(topRight)
        val br = dpF(bottomRight)
        val bl = dpF(bottomLeft)
        cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
        setColor(color)
    }

internal fun Context.oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** The platform's ripple over [content], clipped to [mask] (or to the content). */
internal fun ripple(pressed: Int, content: Drawable?, mask: Drawable? = null): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(pressed), content, mask ?: content)

/** A drawable of the SDK tinted with [color]. */
internal fun Context.icon(res: Int, color: Int): Drawable {
    val d = DrawableCompat.wrap(checkNotNull(ContextCompat.getDrawable(this, res)).mutate())
    DrawableCompat.setTint(d, color)
    return d
}

internal fun ImageView.setIcon(res: Int, color: Int, sizeDp: Int) {
    setImageDrawable(context.icon(res, color))
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = (layoutParams ?: ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))).also {
        it.width = dp(sizeDp)
        it.height = dp(sizeDp)
    }
}

internal fun lp(width: Int, height: Int): ViewGroup.LayoutParams = ViewGroup.LayoutParams(width, height)

internal fun linear(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight)

internal fun frame(width: Int, height: Int, gravity: Int): FrameLayout.LayoutParams = FrameLayout.LayoutParams(width, height, gravity)

internal const val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun ViewGroup.MarginLayoutParams.margins(context: Context, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): ViewGroup.MarginLayoutParams {
    marginStart = context.dp(start)
    topMargin = context.dp(top)
    marginEnd = context.dp(end)
    bottomMargin = context.dp(bottom)
    return this
}

internal fun View.paddingDp(start: Int, top: Int, end: Int, bottom: Int) {
    setPaddingRelative(dp(start), dp(top), dp(end), dp(bottom))
}

/** Says to accessibility services what a view is: a button, a heading, a tab. */
internal fun View.role(button: Boolean = false, heading: Boolean = false, className: String? = null) {
    if (heading) ViewCompat.setAccessibilityHeading(this, true)
    val roleClass = className ?: if (button) "android.widget.Button" else null
    if (roleClass != null) {
        ViewCompat.setAccessibilityDelegate(
            this,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = roleClass
                }
            },
        )
    }
}

/** A row of [count] tabs: TalkBack hears a tab bar (the platform's tab widget) with one selected tab. */
internal fun View.tabBarRole(count: Int) {
    ViewCompat.setAccessibilityDelegate(
        this,
        object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = TAB_BAR_CLASS
                info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, count, false, AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_SINGLE))
            }
        },
    )
}

/** Tab [index] of a tab bar: announced as a tab, with its place in the bar and whether it is the current one. */
internal fun View.tabRole(index: Int, selected: Boolean) {
    ViewCompat.setAccessibilityDelegate(
        this,
        object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = TAB_CLASS
                info.isSelected = selected
                info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, index, 1, false, selected))
            }
        },
    )
}

/** The class names TalkBack reads as a tab bar and as a tab. */
internal const val TAB_BAR_CLASS: String = "android.widget.TabWidget"
internal const val TAB_CLASS: String = "android.app.ActionBar\$Tab"

/**
 * Sets a background without taking over its padding: a drawable with
 * insets (an InsetDrawable) would otherwise replace the view's own padding.
 */
internal fun View.backgroundKeepingPadding(drawable: Drawable?) {
    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val bottom = paddingBottom
    background = drawable
    setPaddingRelative(start, top, end, bottom)
}

/**
 * Text that comes from people or the help center, not the messenger: its
 * direction follows its own first strong letter, so an English message in
 * an Arabic chat reads left to right (the messenger's own texts take the
 * session's direction from the root).
 */
internal fun TextView.contentText() {
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
}

/** Hidden from accessibility services (faces, decorative icons). */
internal fun View.decorative() {
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Whether the system asks for reduced motion (animator duration scale 0). */
internal fun Context.reducedMotion(): Boolean =
    android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
