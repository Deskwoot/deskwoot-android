package com.deskwoot.sdk.ui.home

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.ui.InsetAware
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Screen
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.conversations.ConversationRow
import com.deskwoot.sdk.ui.conversations.ConversationRowView
import com.deskwoot.sdk.ui.conversations.ConversationRows
import com.deskwoot.sdk.ui.help.HelpBody
import com.deskwoot.sdk.ui.people.TeamLine
import com.deskwoot.sdk.ui.widgets.FaceStack
import com.deskwoot.sdk.ui.widgets.FaceView
import com.deskwoot.sdk.ui.widgets.IconButton
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PoweredBy
import com.deskwoot.sdk.ui.widgets.PrimaryButton
import com.deskwoot.sdk.ui.widgets.Segmented
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.WeightSpan
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.oneLine
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.sectionLabel
import com.deskwoot.sdk.ui.widgets.textStyle

/**
 * Klartext's top level: the brand row with the
 * logo, the business name and close, the segmented control "Conversation |
 * Help" when the help center is on, and below it either the start page or
 * the help. Both routes share this screen, so switching keeps the top bar
 * still and only the body changes.
 */
internal class HomeScreen(ui: Ui, route: Route) : Screen(ui, route), InsetAware {
    private val ctx = ui.context
    private val c = ui.colors
    private val topBar = LinearLayout(ctx)
    private val segmented: Segmented? = if (ui.config.helpCenterEnabled) {
        Segmented(ctx, ui, listOf(ui.t(R.string.dw_tab_conversation), ui.t(R.string.dw_help)), listOf(R.id.dw_tab_conversation, R.id.dw_tab_help)) { index ->
            ui.nav.goTo(if (index == 0) Route.Start else Route.Help)
        }
    } else {
        null
    }
    private val bodyFrame = FrameLayout(ctx)
    private var startBody: ScrollView? = null
    private var helpBody: HelpBody? = null
    private val conversationsSection = LinearLayout(ctx)
    private var shownRows: List<ConversationRow>? = null
    private var topInset = 0

    override val body: View get() = bodyFrame

    override fun createView(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.design.canvas(c))
        }
        // Brand row: logo 30, business name 15/600, close.
        topBar.orientation = LinearLayout.VERTICAL
        topBar.setBackgroundColor(c.bg)
        val brand = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = FaceView(ctx, ui).apply { setFace(ui.people.business.face) }
        brand.addView(logo, linear(ctx.dp(30), ctx.dp(30)))
        brand.addView(TextView(ctx).apply {
            id = R.id.dw_business_name
            text = ui.people.businessName
            textStyle(15f, 600, c.ink)
            oneLine()
            role(heading = true)
        }, linear(0, WRAP, 1f).apply { marginStart = ctx.dp(10) })
        val close = IconButton(ctx, ui, R.drawable.dw_ic_close, 20, ui.t(R.string.dw_close)).apply {
            id = R.id.dw_close
            setOnClickListener { ui.nav.close() }
        }
        // The visible button keeps 16 from the edge; its touch area takes 6 of that.
        brand.addView(close, linear(ctx.dp(48), ctx.dp(48)).apply { marginEnd = -ctx.dp(6) })
        topBar.addView(brand, linear(MATCH, WRAP))
        segmented?.let { topBar.addView(it, linear(MATCH, WRAP).apply { topMargin = ctx.dp(6 - Segmented.SLACK) }) }
        applyTopPadding()
        root.addView(topBar, linear(MATCH, WRAP))
        root.addView(bodyFrame, linear(MATCH, 0, 1f))
        showSection(route)
        return root
    }

    override fun onTopInset(top: Int) {
        topInset = top
        if (isBuilt) applyTopPadding()
    }

    private fun applyTopPadding() {
        // 12 above the brand row on the web; the 48 dp close button takes 6 of it.
        topBar.setPaddingRelative(ctx.dp(16), topInset + ctx.dp(6), ctx.dp(16), if (segmented != null) ctx.dp(12 - Segmented.SLACK) else ctx.dp(6))
    }

    override fun canShow(next: Route): Boolean = next == Route.Start || next == Route.Help

    override fun show(next: Route) {
        super.show(next)
        if (isBuilt) showSection(next)
    }

    private fun showSection(r: Route) {
        val help = r == Route.Help && ui.config.helpCenterEnabled
        segmented?.select(if (help) 1 else 0)
        bodyFrame.removeAllViews()
        if (help) {
            val body = helpBody ?: HelpBody(ctx, ui).also { helpBody = it }
            bodyFrame.addView(body.view, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val body = startBody ?: buildStart().also { startBody = it }
            bodyFrame.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        animateBody()
    }

    private fun animateBody() {
        if (ui.reducedMotion) return
        bodyFrame.alpha = 0f
        bodyFrame.translationY = ctx.dp(6).toFloat()
        bodyFrame.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    // ── The start page (Klartext) ────────────────────────────────────────

    private fun buildStart(): ScrollView {
        val config = ui.config
        val scroll = ScrollView(ctx).apply {
            id = R.id.dw_start_screen
            isFillViewport = true
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
        }
        column.addView(TextView(ctx).apply {
            id = R.id.dw_welcome_title
            contentText()
            text = config.welcomeTitle ?: ""
            textStyle(28f, 600, c.ink, 1.14f, -0.018f)
            role(heading = true)
            visibility = if (config.welcomeTitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(18) })
        config.welcomeTagline?.takeIf { it.isNotBlank() }?.let { tagline ->
            column.addView(TextView(ctx).apply {
                id = R.id.dw_welcome_tagline
                contentText()
                text = tagline
                textStyle(15f, 400, c.ink2, 1.5f)
            }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(10) })
        }
        column.addView(teamLine(), linear(MATCH, WRAP).apply { topMargin = ctx.dp(22) })
        column.addView(PrimaryButton(ctx, ui, ui.t(R.string.dw_write_to_us), R.drawable.dw_ic_arrow_right).apply {
            id = R.id.dw_write_to_us
            setOnClickListener { ui.nav.startConversation(null) }
        }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(16) })
        conversationsSection.orientation = LinearLayout.VERTICAL
        column.addView(conversationsSection, linear(MATCH, WRAP).apply { topMargin = ctx.dp(30) })
        column.addView(View(ctx), linear(MATCH, 0, 1f))
        if (config.showBranding) column.addView(PoweredBy(ctx, ui), linear(MATCH, WRAP).apply { topMargin = ctx.dp(8) })
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        shownRows = null
        return scroll
    }

    /** Faces 32 overlapping by 8, then the bold lead and the second line. */
    private fun teamLine(): View {
        val row = LinearLayout(ctx).apply {
            id = R.id.dw_team_line
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val faces = FaceStack(ctx, ui, 32, c.bg)
        faces.setFaces(ui.people.startFaces())
        row.addView(faces, linear(WRAP, WRAP).apply { marginStart = -ctx.dp(2) })
        val line = TeamLine.of(ui.config, ui.people, ui.strings)
        val text = SpannableStringBuilder(line.lead)
        text.setSpan(WeightSpan(600), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(c.ink), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.rest?.let { text.append('\n').append(it) }
        row.addView(TextView(ctx).apply {
            this.text = text
            textStyle(13.5f, 400, c.ink2, 1.4f)
        }, linear(0, WRAP, 1f).apply { marginStart = ctx.dp(10) })
        return row
    }

    override fun render(state: SessionState) {
        if (startBody == null) return
        val config = state.config ?: return
        val rows = if (config.continuousChat) emptyList() else ConversationRows(ui.people, ui.strings, ui.formats).build(state.conversations, System.currentTimeMillis())
        if (rows == shownRows) return
        shownRows = rows
        conversationsSection.removeAllViews()
        if (rows.isEmpty()) {
            conversationsSection.visibility = View.GONE
            return
        }
        conversationsSection.visibility = View.VISIBLE
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ctx.sectionLabel(ui, ui.t(R.string.dw_your_conversations)), linear(0, WRAP, 1f))
        if (rows.size > 2) {
            header.addView(TextView(ctx).apply {
                id = R.id.dw_see_all_conversations
                text = ui.t(R.string.dw_see_all_conversations, rows.size.toString())
                textStyle(13.5f, 600, c.brandText)
                gravity = Gravity.CENTER
                minHeight = ctx.dp(48)
                minWidth = ctx.dp(48)
                setPadding(ctx.dp(8), 0, 0, 0)
                role(button = true)
                setOnClickListener { ui.nav.goTo(Route.Conversations) }
            })
        }
        conversationsSection.addView(header, linear(MATCH, WRAP))
        rows.take(2).forEachIndexed { i, row ->
            val view = ConversationRowView(ctx, ui)
            view.bind(row) { ui.nav.openConversation(row.id) }
            view.separator = i == 0 && rows.size > 1
            conversationsSection.addView(view, linear(MATCH, WRAP).apply {
                marginStart = -ctx.dp(10)
                marginEnd = -ctx.dp(10)
            })
        }
    }

    override fun destroy() {
        helpBody?.destroy()
    }
}
