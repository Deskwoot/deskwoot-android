package com.deskwoot.sdk.ui.design

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.ui.BackAware
import com.deskwoot.sdk.ui.InsetAware
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Screen
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.handleBackPending
import com.deskwoot.sdk.ui.people.Face
import com.deskwoot.sdk.ui.widgets.FaceView
import com.deskwoot.sdk.ui.widgets.IconButton
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.decorative
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.oneLine
import com.deskwoot.sdk.ui.widgets.oval
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.tabBarRole
import com.deskwoot.sdk.ui.widgets.tabRole
import com.deskwoot.sdk.ui.widgets.textStyle

/**
 * Direkt's header over the chat (`g-head`): the business
 * logo 32 and NAME (not the person), the sub line with its dot, the Help
 * button, the conversations button with the count of OTHER conversations
 * with an unread reply, close. Padding 12/14/10, min height 60, a hairline.
 */
@SuppressLint("ViewConstructor")
internal class DirektHeader(context: Context, private val ui: Ui) : LinearLayout(context) {
    val title = TextView(context)
    val subtitle = TextView(context)
    private val dot = View(context)
    private val subRow = LinearLayout(context)
    private val badge = TextView(context)
    val conversations: FrameLayout = FrameLayout(context)
    val close: IconButton

    init {
        id = R.id.dw_header
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val c = ui.colors
        minimumHeight = dp(60)
        setPaddingRelative(dp(14), dp(6), dp(4), dp(5))
        background = com.deskwoot.sdk.ui.widgets.BottomLine(c.bg, c.line, dp(1))
        val logo = FaceView(context, ui).apply {
            id = R.id.dw_header_face
            setFace(ui.people.business.face)
        }
        addView(logo, linear(dp(32), dp(32)))
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.id = R.id.dw_header_title
        title.text = ui.people.businessName
        title.textStyle(15f, 650, c.ink)
        title.oneLine()
        title.role(heading = true)
        column.addView(title, linear(MATCH, WRAP))
        subRow.orientation = HORIZONTAL
        subRow.gravity = Gravity.CENTER_VERTICAL
        dot.background = context.oval(c.ok)
        dot.decorative()
        subRow.addView(dot, linear(dp(8), dp(8)).apply { marginEnd = dp(6) })
        subtitle.id = R.id.dw_header_subtitle
        subtitle.textStyle(12.5f, 400, c.ink3)
        subtitle.oneLine()
        subRow.addView(subtitle, linear(0, WRAP, 1f))
        column.addView(subRow, linear(MATCH, WRAP).apply { topMargin = dp(1) })
        addView(column, linear(0, WRAP, 1f).apply { marginStart = dp(10); marginEnd = dp(4) })
        if (ui.config.helpCenterEnabled) {
            // "Help": book 17 and the label, 36 high, radius 10, 13.5/600 on bg with a hairline.
            val help = LinearLayout(context).apply {
                id = R.id.dw_header_help
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                setPaddingRelative(dp(12), dp(6), dp(12), dp(6))
                background = android.graphics.drawable.InsetDrawable(ripple(c.withAlpha(c.ink, 0.1), context.rounded(c.bg, 10f, c.line)), 0, dp(6), 0, dp(6))
                role(button = true)
                contentDescription = ui.t(R.string.dw_help)
                setOnClickListener { ui.nav.goTo(Route.Help) }
            }
            help.addView(ImageView(context).apply {
                setImageDrawable(context.icon(R.drawable.dw_ic_book, c.ink))
                decorative()
            }, linear(dp(17), dp(17)))
            help.addView(TextView(context).apply {
                text = ui.t(R.string.dw_help)
                textStyle(13.5f, 600, c.ink)
                decorative()
            }, linear(WRAP, WRAP).apply { marginStart = dp(6) })
            addView(help, linear(WRAP, WRAP).apply { marginEnd = dp(2) })
        }
        // "Your conversations": clock 18 in a 36 button, a badge at its top end corner.
        conversations.id = R.id.dw_header_conversations
        conversations.contentDescription = ui.t(R.string.dw_your_conversations)
        conversations.role(button = true)
        conversations.isClickable = true
        conversations.isFocusable = true
        conversations.background = android.graphics.drawable.InsetDrawable(ripple(c.withAlpha(c.ink, 0.12), null, context.rounded(c.bg3, 10f)), dp(6))
        conversations.setOnClickListener { ui.nav.goTo(Route.Conversations) }
        conversations.addView(ImageView(context).apply {
            setImageDrawable(context.icon(R.drawable.dw_ic_clock, c.ink2))
            decorative()
        }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        badge.id = R.id.dw_header_badge
        badge.textStyle(10.5f, 700, c.onBrand, 1f)
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(17)
        badge.minHeight = dp(17)
        badge.setPadding(dp(4), 0, dp(4), 0)
        badge.background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(c.fill)
            setStroke(dp(2), c.directCanvas)
        }
        badge.visibility = View.GONE
        conversations.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(1); marginEnd = dp(1) })
        addView(conversations, linear(dp(48), dp(48)))
        close = IconButton(context, ui, R.drawable.dw_ic_close, 20, ui.t(R.string.dw_close)).apply { id = R.id.dw_close }
        addView(close)
        setLine(null, false)
    }

    fun setLine(text: String?, online: Boolean) {
        subRow.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        subtitle.text = text
        dot.visibility = if (online) View.VISIBLE else View.GONE
    }

    /** The number of other conversations with an unread reply; none hides the badge. */
    fun setBadge(count: Int) {
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
        badge.text = if (count > 99) "99+" else count.toString()
        val label = ui.t(R.string.dw_your_conversations)
        conversations.contentDescription = if (count > 0) label + ", " + ui.context.resources.getQuantityString(R.plurals.dw_sdk_unread, count, count) else label
    }
}

/**
 * Empfang's bottom tab bar: Conversation and Help, an
 * icon 24 over a 12/600 label, the current one in brand text; bg with a
 * hairline on top. Only while the help center is on. It is built with the
 * screen that carries it, so a new language, light or dark, or the help
 * center switched on or off reaches it with the rebuild of the screens.
 * TalkBack hears a tab bar of 2 tabs, the current one selected.
 */
@SuppressLint("ViewConstructor")
internal class EmpfangTabBar(context: Context, ui: Ui, helpSelected: Boolean) : LinearLayout(context) {
    init {
        id = R.id.dw_tab_bar
        orientation = HORIZONTAL
        val c = ui.colors
        background = com.deskwoot.sdk.ui.widgets.TopLine(c.bg, c.line, dp(1))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        ViewCompat.setAccessibilityPaneTitle(this, ui.t(R.string.dw_sections))
        tabBarRole(TABS)
        fun tab(id: Int, index: Int, iconRes: Int, label: String, selected: Boolean, route: Route): View = LinearLayout(context).apply {
            this.id = id
            orientation = VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(52)
            setPadding(0, dp(7), 0, dp(5))
            background = ripple(c.withAlpha(c.ink, 0.08), null, context.rounded(c.bg2, 12f))
            isClickable = true
            isFocusable = true
            tabRole(index, selected)
            isSelected = selected
            contentDescription = label
            val color = if (selected) c.brandText else c.ink3
            addView(ImageView(context).apply {
                setImageDrawable(context.icon(iconRes, color))
                decorative()
                // The current tab's icon is filled with the soft brand tint.
                if (selected) background = context.oval(c.brandSoft)
            }, linear(dp(24), dp(24)))
            addView(TextView(context).apply {
                text = label
                textStyle(12f, 600, color, 1.2f)
                gravity = Gravity.CENTER
                decorative()
            }, linear(WRAP, WRAP).apply { topMargin = dp(3) })
            setOnClickListener { if (!selected) ui.nav.goTo(route) }
        }
        addView(tab(R.id.dw_tab_conversation, 0, R.drawable.dw_ic_chat, ui.t(R.string.dw_tab_conversation), !helpSelected, Route.Start), linear(0, WRAP, 1f))
        addView(tab(R.id.dw_tab_help, 1, R.drawable.dw_ic_book, ui.t(R.string.dw_help), helpSelected, Route.Help), linear(0, WRAP, 1f).apply { marginStart = dp(4) })
    }

    private companion object {
        const val TABS = 2
    }
}

/**
 * The business logo inverted on Empfang's band:
 * the tile in on-band with the letter in the band color, or the logo itself.
 */
internal fun bandLogo(context: Context, ui: Ui, size: Int): View {
    val c = ui.colors
    val face = ui.people.business.face
    if (face is Face.Logo && face.url == null) {
        return TextView(context).apply {
            text = face.letter
            textStyle(size * 0.52f, 750, c.band, 1f)
            gravity = Gravity.CENTER
            background = context.rounded(c.onBand, size * 0.28f)
            decorative()
        }
    }
    return FaceView(context, ui).apply { setFace(face) }
}

/**
 * A Direkt sheet over the chat: the scrim over the chat,
 * the sheet from 68 below the top with radius 20 at the top, the grabber,
 * then the screen inside. Only the first sheet slides up (320 ms); moving
 * between sheets does not animate. A tap on the scrim or close goes back
 * to the chat.
 */
internal class SheetScreen(private val inner: Screen) : Screen(inner.ui, inner.route), InsetAware, BackAware {
    private val ctx = ui.context
    private lateinit var scrim: View
    private lateinit var panel: LinearLayout
    private var topInset = 0

    override val isOverlay: Boolean = true

    override val body: View? get() = if (this::panel.isInitialized) panel else null

    override val wantsBack: Boolean get() = inner.handleBackPending()

    override fun createView(): View {
        val c = ui.colors
        val frame = FrameLayout(ctx)
        scrim = View(ctx).apply {
            setBackgroundColor(c.scrim)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { ui.nav.goTo(Route.Start) }
        }
        frame.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        panel = LinearLayout(ctx).apply {
            id = R.id.dw_sheet
            orientation = LinearLayout.VERTICAL
            val radius = ctx.dp(20).toFloat()
            background = GradientDrawable().apply {
                setColor(c.bg)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
            elevation = ctx.dp(12).toFloat()
            isClickable = true
        }
        panel.addView(View(ctx).apply {
            background = ctx.rounded(c.line2, 2f)
            decorative()
        }, linear(ctx.dp(36), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(8) })
        panel.addView(inner.view, linear(MATCH, 0, 1f))
        frame.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = topInset + ctx.dp(68) })
        return frame
    }

    override fun onTopInset(top: Int) {
        topInset = top
        if (this::panel.isInitialized) {
            (panel.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = top + ctx.dp(68)
                panel.layoutParams = it
            }
        }
    }

    /** The first sheet: the scrim fades in (200 ms), the sheet slides up (320 ms). */
    fun slideUp() {
        if (ui.reducedMotion) return
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        panel.translationY = ctx.resources.displayMetrics.heightPixels.toFloat()
        panel.animate().translationY(0f).setDuration(320).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.3f, 1f)).setListener(null).start()
    }

    /** Back to the chat: the sheet slides down and the scrim fades. */
    fun slideDown(done: () -> Unit) {
        if (ui.reducedMotion || !this::panel.isInitialized) {
            done()
            return
        }
        scrim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(220).setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    panel.animate().setListener(null)
                    done()
                }
            }).start()
    }

    /** Where TalkBack's focus goes when the sheet opens: its title, else the sheet itself. */
    fun focusTarget(): View? = if (this::panel.isInitialized) panel.findViewById<View>(R.id.dw_header_title) ?: panel else null

    override fun render(state: SessionState) = inner.render(state)

    override fun onShown() {
        inner.onShown()
        // TalkBack treats the sheet as a dialog titled by its head.
        ViewCompat.setAccessibilityPaneTitle(panel, (inner.view.findViewById<TextView>(R.id.dw_header_title))?.text)
    }

    override fun onHidden() = inner.onHidden()

    override fun onRemoved() = inner.onRemoved()

    override fun destroy() = inner.destroy()

    override fun handleBack(): Boolean = inner.handleBack()

    override fun canShow(next: Route): Boolean = inner.canShow(next)

    override fun show(next: Route) {
        super.show(next)
        inner.show(next)
    }

    override fun saveState(out: Bundle) = inner.saveState(out)

    override fun restoreState(saved: Bundle) = inner.restoreState(saved)
}
