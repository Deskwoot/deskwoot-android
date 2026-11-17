package com.deskwoot.sdk.ui.help

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.model.HelpArticleSummary
import com.deskwoot.sdk.model.HelpCollection
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.UiMemory
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PoweredBy
import com.deskwoot.sdk.ui.widgets.SkeletonRows
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.sectionLabel
import com.deskwoot.sdk.ui.widgets.textStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The help center's start: "What do you need
 * help with?", the search field, then the topics and "Suggested for you",
 * or while searching the results with their highlight and the ask card.
 * The search text survives opening an article and coming back.
 */
internal class HelpBody(private val context: Context, private val ui: Ui, private val sheet: Boolean = false) {
    val view: ScrollView = ScrollView(context)
    private val column = LinearLayout(context)
    private val lists = LinearLayout(context)
    private val search: SearchField
    private var collections: List<HelpCollection>? = null
    private var collectionsJob: Job? = null
    private var searchJob: Job? = null
    private var shownQuery: String? = null

    init {
        val c = ui.colors
        view.id = R.id.dw_help_screen
        view.isFillViewport = true
        // Direkt's help is a sheet on bg, titled by its head, the search right under it.
        view.setBackgroundColor(if (sheet) c.bg else ui.design.canvas(c))
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(if (sheet) 18 else 20), if (sheet) dp(4) else 0, dp(if (sheet) 18 else 20), 0)
        if (!sheet) {
            column.addView(TextView(context).apply {
                text = ui.t(R.string.dw_help_title)
                textStyle(28f, 600, c.ink, 1.14f, -0.018f)
                role(heading = true)
            }, linear(MATCH, WRAP).apply { topMargin = dp(18); bottomMargin = dp(10) })
        }
        search = SearchField(context, ui) { query -> onQuery(query) }
        column.addView(search, linear(MATCH, WRAP))
        lists.orientation = LinearLayout.VERTICAL
        column.addView(lists, linear(MATCH, WRAP))
        column.addView(View(context), linear(MATCH, 0, 1f))
        if (ui.config.showBranding) column.addView(PoweredBy(context, ui), linear(MATCH, WRAP).apply { topMargin = dp(18) })
        view.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        val kept = UiMemory.helpQuery
        if (kept.isNotEmpty()) search.input.setText(kept) else showLists()
        loadCollections()
    }

    private fun dp(v: Int) = context.dp(v)

    private fun loadCollections() {
        collectionsJob?.cancel()
        collectionsJob = ui.scope.launch {
            val result = ui.session.help.collections()
            collections = (result as? ApiResult.Success)?.value ?: emptyList()
            if (shownQuery == null) showLists()
        }
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
            // 250 ms after the last key.
            delay(250)
            if (shownQuery != q) showSkeleton(q)
            val result = ui.session.help.search(q)
            showResults(q, result)
        }
    }

    private fun showLists() {
        shownQuery = null
        lists.removeAllViews()
        val c = ui.colors
        val known = collections
        if (known == null) {
            lists.addView(SkeletonRows(context, ui), linear(MATCH, WRAP).apply { topMargin = dp(16) })
        } else if (known.isNotEmpty()) {
            lists.addView(context.sectionLabel(ui, ui.t(R.string.dw_topics)), linear(MATCH, WRAP).apply { topMargin = dp(26); bottomMargin = dp(2) })
            known.forEachIndexed { i, collection ->
                val row = collectionRow(context, ui, collection, tile = sheet) { ui.nav.goTo(Route.Collection(collection.id, collection.name)) }
                row.separator = i < known.lastIndex
                lists.addView(row, negativeMargins())
            }
        }
        val suggested = ui.session.help.suggested()
        if (suggested.isNotEmpty()) {
            lists.addView(context.sectionLabel(ui, ui.t(R.string.dw_suggested)), linear(MATCH, WRAP).apply { topMargin = dp(26); bottomMargin = dp(2) })
            addArticles(suggested, withDescription = false, mark = null)
        }
        lists.setBackgroundColor(if (sheet) c.bg else ui.design.canvas(c))
    }

    private fun showSkeleton(q: String) {
        shownQuery = q
        lists.removeAllViews()
        lists.addView(SkeletonRows(context, ui), linear(MATCH, WRAP).apply { topMargin = dp(16) })
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
                        setPadding(dp(4), dp(22), dp(4), dp(8))
                    })
                } else {
                    lists.addView(TextView(context).apply {
                        id = R.id.dw_help_results_label
                        text = if (hits.size == 1) ui.t(R.string.dw_results_for_one, q) else ui.t(R.string.dw_results_for, hits.size.toString(), q)
                        textStyle(13f, 600, c.ink2)
                        role(heading = true)
                    }, linear(MATCH, WRAP).apply { topMargin = dp(22); bottomMargin = dp(2) })
                    addArticles(hits, withDescription = true, mark = q)
                }
            }
            else -> lists.addView(TextView(context).apply {
                text = ui.t(R.string.dw_search_error)
                textStyle(14.5f, 400, c.error, 1.5f)
                setPadding(dp(4), dp(22), dp(4), dp(8))
            })
        }
        lists.addView(askCard(context, ui) { UiMemory.helpQuery.trim().ifEmpty { null } }, linear(MATCH, WRAP).apply { topMargin = dp(14) })
    }

    private fun addArticles(articles: List<HelpArticleSummary>, withDescription: Boolean, mark: String?) {
        articles.forEachIndexed { i, a ->
            val row = articleRow(context, ui, a, withDescription, mark) { ui.nav.goTo(Route.Article(a.slug)) }
            row.separator = i < articles.lastIndex
            lists.addView(row, negativeMargins())
        }
    }

    /** Rows reach 10 into the page padding (`margin 0 -10`). */
    private fun negativeMargins(): LinearLayout.LayoutParams = linear(MATCH, WRAP).apply {
        marginStart = -dp(10)
        marginEnd = -dp(10)
    }

    fun destroy() {
        collectionsJob?.cancel()
        searchJob?.cancel()
    }
}
