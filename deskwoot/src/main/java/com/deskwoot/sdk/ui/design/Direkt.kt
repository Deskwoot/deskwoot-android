package com.deskwoot.sdk.ui.design

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.ui.HeaderStyle
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Screen
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.help.HelpBody
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.SubHeader
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Direkt's help as a sheet over the chat: the head titled "Help", the search, the sections. */
internal class DirektHelpScreen(ui: Ui, route: Route) : Screen(ui, route) {
    private var helpBody: HelpBody? = null

    override fun createView(): View {
        val ctx = ui.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.colors.bg)
        }
        val header = SubHeader(ctx, ui, withFace = false, style = HeaderStyle.SHEET)
        header.title.text = ui.t(R.string.dw_help)
        header.close.setOnClickListener { ui.nav.goTo(Route.Start) }
        root.addView(header, linear(MATCH, WRAP))
        val help = HelpBody(ctx, ui, sheet = true).also { helpBody = it }
        root.addView(help.view, linear(MATCH, 0, 1f))
        return root
    }

    override fun render(state: SessionState) = Unit

    override fun destroy() {
        helpBody?.destroy()
    }
}

/**
 * Direkt's article tray: while a new visitor types,
 * the last word of at least 4 letters or digits is looked up 400 ms after
 * the last key; up to 2 articles show in a card under the composer. A tap
 * opens the article as a sheet over the chat.
 */
internal class ArticleTray(private val context: Context, private val ui: Ui) {
    val view: LinearLayout = LinearLayout(context)
    private val rows = LinearLayout(context)
    private var job: Job? = null
    private var shownFor: String? = null

    init {
        val c = ui.colors
        view.id = R.id.dw_article_tray
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(context.dp(6), context.dp(10), context.dp(6), context.dp(6))
        view.background = context.rounded(c.bg, 16f, c.line)
        view.elevation = context.dp(2).toFloat()
        view.addView(TextView(context).apply {
            text = ui.t(R.string.dw_might_help)
            textStyle(12.5f, 400, c.ink3)
            setPaddingRelative(context.dp(10), 0, context.dp(10), context.dp(4))
            role(heading = true)
        })
        rows.orientation = LinearLayout.VERTICAL
        view.addView(rows, linear(MATCH, WRAP))
        view.visibility = View.GONE
    }

    /** [fresh]: the chat has no message of the visitor yet; the tray shows only then. */
    fun typed(written: String, fresh: Boolean) {
        val query = if (fresh) trayQuery(written) else ""
        if (query == shownFor && query.isNotEmpty()) return
        job?.cancel()
        if (query.isEmpty()) {
            hide()
            return
        }
        job = ui.scope.launch {
            delay(400)
            val hits = (ui.session.help.search(query) as? ApiResult.Success)?.value.orEmpty().take(2)
            shownFor = query
            if (hits.isEmpty()) {
                hide()
                return@launch
            }
            rows.removeAllViews()
            val c = ui.colors
            for (hit in hits) {
                rows.addView(TextView(context).apply {
                    id = R.id.dw_article_row
                    contentText()
                    text = hit.title
                    textStyle(14f, 600, c.ink, 1.4f)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = context.dp(48)
                    setPaddingRelative(context.dp(10), context.dp(9), context.dp(10), context.dp(9))
                    background = ripple(c.withAlpha(c.ink, 0.08), null, context.rounded(c.bg2, 10f))
                    role(button = true)
                    setOnClickListener { ui.nav.goTo(Route.Article(hit.slug)) }
                }, linear(MATCH, WRAP))
            }
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                if (!ui.reducedMotion) {
                    view.alpha = 0f
                    view.translationY = context.dp(4).toFloat()
                    view.animate().alpha(1f).translationY(0f).setDuration(180).start()
                }
            }
        }
    }

    fun hide() {
        shownFor = null
        view.visibility = View.GONE
    }

    internal companion object {
        /** The last word of at least 4 letters or digits, without its punctuation. */
        fun trayQuery(text: String): String {
            val words = text.split(Regex("\\s+")).filter { w -> w.count { it.isLetterOrDigit() } >= 4 }
            return words.lastOrNull()?.filter { it.isLetterOrDigit() || it == '-' }.orEmpty()
        }
    }
}
