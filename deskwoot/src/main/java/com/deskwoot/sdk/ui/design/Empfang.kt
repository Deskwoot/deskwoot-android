package com.deskwoot.sdk.ui.design

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.model.HelpArticleSummary
import com.deskwoot.sdk.model.HelpCollection
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.ui.InsetAware
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Screen
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.UiMemory
import com.deskwoot.sdk.ui.conversations.ConversationRow
import com.deskwoot.sdk.ui.conversations.ConversationRowView
import com.deskwoot.sdk.ui.conversations.ConversationRows
import com.deskwoot.sdk.ui.help.SearchField
import com.deskwoot.sdk.ui.help.articleRow
import com.deskwoot.sdk.ui.help.folderTile
import com.deskwoot.sdk.ui.people.TeamLine
import com.deskwoot.sdk.ui.widgets.FaceStack
import com.deskwoot.sdk.ui.widgets.IconButton
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PoweredBy
import com.deskwoot.sdk.ui.widgets.SkeletonRows
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.decorative
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.edged
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.oneLine
import com.deskwoot.sdk.ui.widgets.oval
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.sectionLabel
import com.deskwoot.sdk.ui.widgets.textStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A card of Empfang: the card color, a hairline, radius 16. */
internal fun Context.empfangCard(ui: Ui): android.graphics.drawable.GradientDrawable = rounded(ui.colors.eCard, 16f, ui.colors.line)

/**
 * Empfang's start page and help: a hero on the
 * colored band, cards on the gray canvas, the bottom tab bar. One screen for
 * both, as the tab bar stays where it is.
 */
internal class EmpfangHomeScreen(ui: Ui, route: Route) : Screen(ui, route), InsetAware {
    private val ctx = ui.context
    private val c = ui.colors
    private val frame = FrameLayout(ctx)
    private var root: LinearLayout? = null
    private var tabBar: View? = null
    private var startView: View? = null
    private var startHero: LinearLayout? = null
    private var helpView: View? = null
    private var helpHero: LinearLayout? = null
    private val recentCard = LinearLayout(ctx)
    private var shownRows: List<ConversationRow>? = null
    private var topInset = 0
    private var help: EmpfangHelp? = null

    override val barsOnBand: Boolean = true

    override val body: View get() = frame

    override fun createView(): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.eCanvas)
        }
        column.addView(frame, linear(MATCH, 0, 1f))
        root = column
        showSection(route)
        return column
    }

    override fun onTopInset(top: Int) {
        topInset = top
        startHero?.let { padHero(it) }
        helpHero?.let { padHero(it) }
    }

    private fun padHero(hero: LinearLayout) {
        hero.setPaddingRelative(ctx.dp(20), topInset + ctx.dp(10), ctx.dp(14), hero.paddingBottom)
    }

    override fun canShow(next: Route): Boolean = next == Route.Start || next == Route.Help

    override fun show(next: Route) {
        super.show(next)
        if (isBuilt) showSection(next)
    }

    private fun showSection(r: Route) {
        val helpOn = ui.config.helpCenterEnabled
        val showHelp = r == Route.Help && helpOn
        frame.removeAllViews()
        val section = if (showHelp) helpView ?: buildHelp().also { helpView = it } else startView ?: buildStart().also { startView = it }
        frame.addView(section, FrameLayout.LayoutParams(MATCH, MATCH))
        val column = root ?: return
        tabBar?.let { column.removeView(it) }
        tabBar = if (helpOn) EmpfangTabBar(ctx, ui, showHelp).also { column.addView(it, linear(MATCH, WRAP)) } else null
        if (showHelp && UiMemory.focusHelpSearch) {
            UiMemory.focusHelpSearch = false
            help?.focus()
        }
    }

    // ── The start page ───────────────────────────────────────────────────

    private fun hero(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(c.band)
    }

    private fun closeOnBand(): IconButton = IconButton(ctx, ui, R.drawable.dw_ic_close, 20, ui.t(R.string.dw_close), c.onBand(0.88)).apply {
        id = R.id.dw_close
        setOnClickListener { ui.nav.close() }
    }

    private fun buildStart(): View {
        val config = ui.config
        val scroll = ScrollView(ctx).apply {
            id = R.id.dw_start_screen
            isFillViewport = true
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // The hero: logo, faces (team first, the bot last), close; the title and the tagline on the band.
        val hero = hero().also { startHero = it }
        hero.setPaddingRelative(ctx.dp(20), ctx.dp(10), ctx.dp(14), ctx.dp(26))
        padHero(hero)
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(bandLogo(ctx, ui, 32), linear(ctx.dp(32), ctx.dp(32)))
        top.addView(View(ctx), linear(0, 1, 1f))
        top.addView(FaceStack(ctx, ui, 32, c.band).apply { setFaces(ui.people.heroFaces()) }, linear(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
        top.addView(closeOnBand())
        hero.addView(top, linear(MATCH, WRAP).apply { bottomMargin = ctx.dp(20) })
        config.welcomeTitle?.takeIf { it.isNotBlank() }?.let { title ->
            hero.addView(TextView(ctx).apply {
                id = R.id.dw_welcome_title
                contentText()
                text = title
                textStyle(26f, 700, c.onBand, 1.18f, -0.015f)
                role(heading = true)
            }, linear(MATCH, WRAP).apply { marginEnd = ctx.dp(6) })
        }
        config.welcomeTagline?.takeIf { it.isNotBlank() }?.let { tagline ->
            hero.addView(TextView(ctx).apply {
                id = R.id.dw_welcome_tagline
                contentText()
                text = tagline
                textStyle(15f, 400, c.onBand(0.82), 1.45f)
            }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(8); marginEnd = ctx.dp(6) })
        }
        column.addView(hero, linear(MATCH, WRAP))
        // The body: cards on the canvas, 14 around and 12 apart.
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), 0)
        }
        recentCard.id = R.id.dw_recent_card
        recentCard.orientation = LinearLayout.VERTICAL
        recentCard.background = ctx.empfangCard(ui)
        recentCard.setPaddingRelative(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(6))
        recentCard.visibility = View.GONE
        body.addView(recentCard, linear(MATCH, WRAP).apply { bottomMargin = ctx.dp(12) })
        body.addView(askCard(), linear(MATCH, WRAP))
        if (config.helpCenterEnabled) body.addView(helpCard(), linear(MATCH, WRAP).apply { topMargin = ctx.dp(12) })
        body.addView(View(ctx), linear(MATCH, 0, 1f))
        if (config.showBranding) body.addView(PoweredBy(ctx, ui), linear(MATCH, WRAP).apply { topMargin = ctx.dp(8) })
        column.addView(body, linear(MATCH, 0, 1f))
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        shownRows = null
        return scroll
    }

    /** "Write to us" as one card: the title, the team line as sentences, the round send button. */
    private fun askCard(): View {
        val card = LinearLayout(ctx).apply {
            id = R.id.dw_write_to_us
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(ctx.dp(18), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            background = ripple(c.withAlpha(c.ink, 0.06), ctx.empfangCard(ui))
            role(button = true)
            setOnClickListener { ui.nav.startConversation(null) }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = ui.t(R.string.dw_write_to_us)
        texts.addView(TextView(ctx).apply {
            text = title
            textStyle(15.5f, 650, c.ink)
        })
        val line = TeamLine.of(ui.config, ui.people, ui.strings).asSentences(ui.strings)
        texts.addView(TextView(ctx).apply {
            text = line
            textStyle(13.5f, 400, c.ink2, 1.4f)
        }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(2) })
        card.addView(texts, linear(0, WRAP, 1f))
        card.addView(ImageView(ctx).apply {
            setImageDrawable(ctx.icon(R.drawable.dw_ic_send, c.onBrand))
            val pad = ctx.dp(10)
            setPadding(pad, pad, pad, pad)
            // A white or yellow brand circle keeps its shape on the white card.
            background = ctx.oval(c.fill).edged(ctx, c)
            decorative()
        }, linear(ctx.dp(40), ctx.dp(40)).apply { marginStart = ctx.dp(14) })
        card.contentDescription = "$title, $line"
        return card
    }

    /** The help card: a search that opens the help with its field focused, then up to 3 suggested articles. */
    private fun helpCard(): View {
        val card = LinearLayout(ctx).apply {
            id = R.id.dw_help_card
            orientation = LinearLayout.VERTICAL
            background = ctx.empfangCard(ui)
            setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
        }
        val search = LinearLayout(ctx).apply {
            id = R.id.dw_help_search
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(48)
            setPaddingRelative(ctx.dp(14), 0, ctx.dp(14), 0)
            background = ripple(c.withAlpha(c.ink, 0.08), ctx.rounded(c.bg2, 12f))
            role(button = true)
            contentDescription = ui.t(R.string.dw_search_placeholder)
            setOnClickListener {
                UiMemory.focusHelpSearch = true
                ui.nav.goTo(Route.Help)
            }
        }
        search.addView(ImageView(ctx).apply {
            setImageDrawable(ctx.icon(R.drawable.dw_ic_search, c.ink3))
            decorative()
        }, linear(ctx.dp(19), ctx.dp(19)))
        search.addView(TextView(ctx).apply {
            text = ui.t(R.string.dw_search_placeholder)
            textStyle(15f, 400, c.ink3)
            oneLine()
            decorative()
        }, linear(0, WRAP, 1f).apply { marginStart = ctx.dp(10) })
        card.addView(search, linear(MATCH, WRAP))
        val suggested = ui.session.help.suggested().take(3)
        suggested.forEachIndexed { i, a ->
            val row = articleRow(ctx, ui, a, withDescription = false, mark = null) { ui.nav.goTo(Route.Article(a.slug)) }
            row.separator = i < suggested.lastIndex
            card.addView(row, linear(MATCH, WRAP).apply { if (i == 0) topMargin = ctx.dp(4) })
        }
        return card
    }

    override fun render(state: SessionState) {
        if (startView == null) return
        val config = state.config ?: return
        val rows = if (config.continuousChat) emptyList() else ConversationRows(ui.people, ui.strings, ui.formats).build(state.conversations, System.currentTimeMillis())
        if (rows == shownRows) return
        shownRows = rows
        recentCard.removeAllViews()
        val newest = rows.firstOrNull()
        recentCard.visibility = if (newest == null) View.GONE else View.VISIBLE
        newest ?: return
        // "Your conversation" and "All {n}", even for one.
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ctx.sectionLabel(ui, ui.t(R.string.dw_your_conversation)), linear(0, WRAP, 1f))
        head.addView(TextView(ctx).apply {
            id = R.id.dw_see_all_conversations
            text = ui.t(R.string.dw_all_count, rows.size.toString())
            textStyle(13f, 600, c.brandText)
            gravity = Gravity.CENTER
            minHeight = ctx.dp(48)
            minWidth = ctx.dp(48)
            role(button = true)
            setOnClickListener { ui.nav.goTo(Route.Conversations) }
        })
        recentCard.addView(head, linear(MATCH, WRAP))
        val view = ConversationRowView(ctx, ui)
        view.bind(newest) { ui.nav.openConversation(newest.id) }
        view.separator = false
        recentCard.addView(view, linear(MATCH, WRAP).apply {
            marginStart = -ctx.dp(8)
            marginEnd = -ctx.dp(8)
        })
    }

    // ── The help ─────────────────────────────────────────────────────────

    private fun buildHelp(): View {
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val hero = hero().also { helpHero = it }
        hero.setPaddingRelative(ctx.dp(20), ctx.dp(10), ctx.dp(14), ctx.dp(20))
        padHero(hero)
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = ui.t(R.string.dw_help)
            textStyle(22f, 700, c.onBand, 1.18f, -0.015f)
            role(heading = true)
        }, linear(0, WRAP, 1f))
        top.addView(closeOnBand())
        hero.addView(top, linear(MATCH, WRAP))
        val body = EmpfangHelp(ctx, ui).also { help = it }
        hero.addView(body.search, linear(MATCH, WRAP).apply { topMargin = ctx.dp(10); marginEnd = ctx.dp(6) })
        column.addView(hero, linear(MATCH, WRAP))
        column.addView(body.view, linear(MATCH, 0, 1f))
        return column
    }

    override fun destroy() {
        help?.destroy()
    }
}

/**
 * Empfang's help under the band: "Topics" with each
 * collection as its own card (the folder tile 42, the title, the
 * description, the count as a third line), "Suggested for you" in one card,
 * or while searching the results in one card and the ask card.
 */
internal class EmpfangHelp(private val context: Context, private val ui: Ui) {
    val view: ScrollView = ScrollView(context)
    private val lists = LinearLayout(context)
    val search: SearchField = SearchField(context, ui, onBand = true) { query -> onQuery(query) }
    private var collections: List<HelpCollection>? = null
    private var collectionsJob: Job? = null
    private var searchJob: Job? = null
    private var shownQuery: String? = null

    init {
        view.id = R.id.dw_help_screen
        view.isFillViewport = true
        view.setBackgroundColor(ui.colors.eCanvas)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(2), context.dp(14), 0)
        }
        lists.orientation = LinearLayout.VERTICAL
        column.addView(lists, linear(MATCH, WRAP))
        column.addView(View(context), linear(MATCH, 0, 1f))
        if (ui.config.showBranding) column.addView(PoweredBy(context, ui), linear(MATCH, WRAP).apply { topMargin = context.dp(14) })
        view.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        val kept = UiMemory.helpQuery
        if (kept.isNotEmpty()) search.input.setText(kept) else showLists()
        collectionsJob = ui.scope.launch {
            val result = ui.session.help.collections()
            collections = (result as? ApiResult.Success)?.value ?: emptyList()
            if (shownQuery == null) showLists()
        }
    }

    fun focus() {
        search.input.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(search.input, 0)
    }

    private fun onQuery(query: String) {
        UiMemory.helpQuery = query
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            showLists()
            return
        }
        searchJob = ui.scope.launch {
            delay(250)
            if (shownQuery != q) {
                shownQuery = q
                lists.removeAllViews()
                lists.addView(SkeletonRows(context, ui), linear(MATCH, WRAP).apply { topMargin = context.dp(12) })
            }
            showResults(q, ui.session.help.search(q))
        }
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        textStyle(13f, 600, ui.colors.ink2)
        role(heading = true)
    }

    private fun showLists() {
        shownQuery = null
        lists.removeAllViews()
        val c = ui.colors
        val known = collections
        if (known == null) {
            lists.addView(SkeletonRows(context, ui), linear(MATCH, WRAP).apply { topMargin = context.dp(12) })
        } else if (known.isNotEmpty()) {
            lists.addView(label(ui.t(R.string.dw_topics)), linear(MATCH, WRAP).apply { topMargin = context.dp(16); bottomMargin = context.dp(8) })
            known.forEach { collection -> lists.addView(collectionCard(collection), linear(MATCH, WRAP).apply { bottomMargin = context.dp(12) }) }
        }
        val suggested = ui.session.help.suggested()
        if (suggested.isNotEmpty()) {
            lists.addView(label(ui.t(R.string.dw_suggested)), linear(MATCH, WRAP).apply { topMargin = context.dp(6); bottomMargin = context.dp(8) })
            lists.addView(articlesCard(suggested, withDescription = false, mark = null), linear(MATCH, WRAP))
        }
        lists.setBackgroundColor(c.eCanvas)
    }

    /** A collection as its own card. */
    private fun collectionCard(collection: HelpCollection): View {
        val c = ui.colors
        val card = LinearLayout(context).apply {
            id = R.id.dw_collection_row
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            background = ripple(c.withAlpha(c.ink, 0.06), context.empfangCard(ui))
            role(button = true)
            setOnClickListener { ui.nav.goTo(Route.Collection(collection.id, collection.name)) }
        }
        card.addView(folderTile(context, ui, 42, 12f, 21), linear(context.dp(42), context.dp(42)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            contentText()
            text = collection.name
            textStyle(15f, 600, c.ink)
        })
        collection.description?.let { d ->
            texts.addView(TextView(context).apply {
                contentText()
                text = d
                textStyle(13f, 400, c.ink2, 1.4f)
            }, linear(MATCH, WRAP).apply { topMargin = context.dp(2) })
        }
        val count = if (collection.articleCount == 1) ui.t(R.string.dw_article_count_one) else ui.t(R.string.dw_article_count, collection.articleCount.toString())
        texts.addView(TextView(context).apply {
            text = count
            textStyle(12.5f, 400, c.ink3)
        }, linear(MATCH, WRAP).apply { topMargin = context.dp(4) })
        card.addView(texts, linear(0, WRAP, 1f).apply { marginStart = context.dp(14) })
        card.contentDescription = "${collection.name}, $count"
        return card
    }

    /** Articles inside one card. */
    private fun articlesCard(articles: List<HelpArticleSummary>, withDescription: Boolean, mark: String?): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.empfangCard(ui)
            setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))
        }
        articles.forEachIndexed { i, a ->
            val row = articleRow(context, ui, a, withDescription, mark) { ui.nav.goTo(Route.Article(a.slug)) }
            row.separator = i < articles.lastIndex
            card.addView(row, linear(MATCH, WRAP))
        }
        return card
    }

    private fun showResults(q: String, result: ApiResult<List<HelpArticleSummary>>) {
        shownQuery = q
        lists.removeAllViews()
        val c = ui.colors
        when (result) {
            is ApiResult.Success -> {
                val hits = result.value
                if (hits.isEmpty()) {
                    lists.addView(TextView(context).apply {
                        text = ui.t(R.string.dw_no_results, q)
                        textStyle(14.5f, 400, c.ink2, 1.5f)
                        setPadding(context.dp(4), context.dp(18), context.dp(4), context.dp(8))
                    })
                } else {
                    lists.addView(TextView(context).apply {
                        id = R.id.dw_help_results_label
                        text = if (hits.size == 1) ui.t(R.string.dw_results_for_one, q) else ui.t(R.string.dw_results_for, hits.size.toString(), q)
                        textStyle(13f, 600, c.ink2)
                        role(heading = true)
                    }, linear(MATCH, WRAP).apply { topMargin = context.dp(16); bottomMargin = context.dp(8) })
                    lists.addView(articlesCard(hits, withDescription = true, mark = q), linear(MATCH, WRAP))
                }
            }
            else -> lists.addView(TextView(context).apply {
                text = ui.t(R.string.dw_search_error)
                textStyle(14.5f, 400, c.error, 1.5f)
                setPadding(context.dp(4), context.dp(18), context.dp(4), context.dp(8))
            })
        }
        lists.addView(empfangAskCard(context, ui) { UiMemory.helpQuery.trim().ifEmpty { null } }, linear(MATCH, WRAP).apply { topMargin = context.dp(12) })
    }

    fun destroy() {
        collectionsJob?.cancel()
        searchJob?.cancel()
    }
}

/** Empfang's ask card in the help: "Write to us" with the team line and the send circle; [draft] goes into the new chat's field. */
internal fun empfangAskCard(context: Context, ui: Ui, draft: () -> String?): View {
    val c = ui.colors
    val card = LinearLayout(context).apply {
        id = R.id.dw_ask_card
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(context.dp(18), context.dp(16), context.dp(16), context.dp(16))
        background = ripple(c.withAlpha(c.ink, 0.06), context.empfangCard(ui))
        role(button = true)
    }
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(context).apply {
        text = ui.t(R.string.dw_write_to_us)
        textStyle(15.5f, 650, c.ink)
    })
    texts.addView(TextView(context).apply {
        text = TeamLine.of(ui.config, ui.people, ui.strings).asSentences(ui.strings)
        textStyle(13.5f, 400, c.ink2, 1.4f)
    }, linear(MATCH, WRAP).apply { topMargin = context.dp(2) })
    card.addView(texts, linear(0, WRAP, 1f))
    card.addView(ImageView(context).apply {
        id = R.id.dw_ask_button
        setImageDrawable(context.icon(R.drawable.dw_ic_send, c.onBrand))
        val pad = context.dp(10)
        setPadding(pad, pad, pad, pad)
        background = context.oval(c.fill).edged(context, c)
        decorative()
    }, linear(context.dp(40), context.dp(40)).apply { marginStart = context.dp(14) })
    card.setOnClickListener { ui.nav.startConversation(draft()) }
    return card
}
