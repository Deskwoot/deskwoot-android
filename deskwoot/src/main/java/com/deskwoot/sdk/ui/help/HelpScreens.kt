package com.deskwoot.sdk.ui.help

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.model.HelpArticle
import com.deskwoot.sdk.ui.InsetAware
import com.deskwoot.sdk.ui.LinkKind
import com.deskwoot.sdk.ui.Links
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Screen
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.UiMemory
import com.deskwoot.sdk.ui.people.Face
import com.deskwoot.sdk.ui.people.People
import com.deskwoot.sdk.ui.widgets.FaceView
import com.deskwoot.sdk.ui.widgets.IconButton
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PoweredBy
import com.deskwoot.sdk.ui.widgets.SkeletonRows
import com.deskwoot.sdk.ui.widgets.SubHeader
import com.deskwoot.sdk.ui.widgets.PrimaryButton
import com.deskwoot.sdk.ui.widgets.TopLine
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.backgroundKeepingPadding
import com.deskwoot.sdk.ui.widgets.decorative
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** A collection of the help center: its name, description and article count, then its articles. */
internal class CollectionScreen(ui: Ui, private val collection: Route.Collection) : Screen(ui, collection), InsetAware {
    private val ctx = ui.context
    private lateinit var header: SubHeader
    private val rows = LinearLayout(ctx)
    private var job: Job? = null
    private var topInset = 0

    /** Direkt: a sheet titled with the collection's name; Empfang: its bar titled so, the rows in one card, the tab bar. */
    private val sheet = ui.design.isSheet(collection)
    private val empfang = ui.design.tabBar(collection)

    override fun createView(): View {
        val c = ui.colors
        val root = LinearLayout(ctx).apply {
            id = R.id.dw_collection_screen
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (sheet) c.bg else ui.design.canvas(c))
        }
        header = SubHeader(ctx, ui, withFace = false, style = ui.design.headerStyle(route))
        header.title.text = if (sheet || empfang) collection.name else ui.t(R.string.dw_help)
        header.back.setOnClickListener { ui.nav.back() }
        header.close.setOnClickListener { if (sheet) ui.nav.goTo(Route.Start) else ui.nav.close() }
        if (sheet) header.showBack(true)
        header.setPadding(header.paddingLeft, topInset + ctx.dp(4), header.paddingRight, header.paddingBottom)
        root.addView(header, linear(MATCH, WRAP))
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val intro = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(18), ctx.dp(20), ctx.dp(6))
        }
        // Only Klartext repeats the name as the body's big title; the others carry it in their head.
        if (!sheet && !empfang) {
            intro.addView(TextView(ctx).apply {
                contentText()
                text = collection.name
                textStyle(24f, 600, c.ink, 1.3f, -0.015f)
                role(heading = true)
            }, linear(MATCH, WRAP).apply { bottomMargin = ctx.dp(6) })
        } else {
            intro.setPadding(ctx.dp(if (sheet) 18 else 16), ctx.dp(if (sheet) 4 else 16), ctx.dp(if (sheet) 18 else 16), ctx.dp(6))
        }
        val introText = TextView(ctx).apply {
            textStyle(14f, 400, c.ink2, 1.5f)
            contentText()
        }
        intro.addView(introText)
        column.addView(intro)
        rows.orientation = LinearLayout.VERTICAL
        rows.setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), 0)
        rows.addView(SkeletonRows(ctx, ui), linear(MATCH, WRAP).apply { marginStart = ctx.dp(10); marginEnd = ctx.dp(10) })
        if (empfang) {
            rows.setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(4))
            rows.background = ctx.rounded(c.eCard, 16f, c.line)
            column.addView(rows, linear(MATCH, WRAP).apply { setMargins(ctx.dp(14), ctx.dp(8), ctx.dp(14), 0) })
        } else {
            column.addView(rows, linear(MATCH, WRAP))
        }
        column.addView(View(ctx), linear(MATCH, 0, 1f))
        if (ui.config.showBranding) column.addView(PoweredBy(ctx, ui), linear(MATCH, WRAP).apply { topMargin = ctx.dp(18) })
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, linear(MATCH, 0, 1f))
        if (empfang && ui.config.helpCenterEnabled) root.addView(com.deskwoot.sdk.ui.design.EmpfangTabBar(ctx, ui, helpSelected = true), linear(MATCH, WRAP))
        load(introText)
        return root
    }

    private fun load(introText: TextView) {
        job = ui.scope.launch {
            val all = (ui.session.help.collections() as? ApiResult.Success)?.value
            val info = all?.firstOrNull { it.id == collection.id }
            if (info != null) {
                val count = if (info.articleCount == 1) ui.t(R.string.dw_article_count_one) else ui.t(R.string.dw_article_count, info.articleCount.toString())
                // Empfang and Direkt say it as 2 sentences (i18n "sentences"), Klartext joins them with a dot.
                introText.text = when {
                    sheet || empfang -> info.description?.let { ui.strings.sentences(ui.strings.sentence(it), ui.strings.sentence(count)) } ?: count
                    else -> info.description?.let { "$it · $count" } ?: count
                }
            } else {
                introText.visibility = View.GONE
            }
            val result = ui.session.help.collectionArticles(collection.id)
            rows.removeAllViews()
            when (result) {
                is ApiResult.Success -> result.value.forEachIndexed { i, article ->
                    val row = articleRow(ctx, ui, article, withDescription = true, mark = null) { ui.nav.goTo(Route.Article(article.slug)) }
                    row.separator = i < result.value.lastIndex
                    rows.addView(row, linear(MATCH, WRAP))
                }
                else -> rows.addView(TextView(ctx).apply {
                    text = ui.t(R.string.dw_article_load_error)
                    textStyle(14.5f, 400, ui.colors.error, 1.5f)
                    setPadding(ctx.dp(10), ctx.dp(18), ctx.dp(10), ctx.dp(18))
                })
            }
        }
    }

    override fun onTopInset(top: Int) {
        topInset = if (sheet) 0 else top
        if (isBuilt) header.setPadding(header.paddingLeft, topInset + ctx.dp(4), header.paddingRight, header.paddingBottom)
    }

    override fun destroy() {
        job?.cancel()
    }
}

/**
 * One article: its collection, title, author and
 * dates natively, the body in a system WebView with the messenger's fonts
 * and colors (JavaScript off, links open outside), then the vote, the link
 * to the help center and the ask card.
 */
internal class ArticleScreen(ui: Ui, private val article: Route.Article) : Screen(ui, article), InsetAware {
    private val ctx = ui.context
    private val c = ui.colors
    private lateinit var header: SubHeader
    private val column = LinearLayout(ctx)
    private val status = TextView(ctx)
    private var web: WebView? = null
    private var job: Job? = null
    private var voteJob: Job? = null
    private var topInset = 0
    private var external: IconButton? = null
    private val askBar = LinearLayout(ctx)

    /** Direkt: a sheet with back and an ask bar; Empfang: its bar with the collection's name and a fixed ask bar. */
    private val sheet = ui.design.isSheet(article)
    private val bars = sheet || ui.design.headerStyle(article) == com.deskwoot.sdk.ui.HeaderStyle.BAR

    override fun createView(): View {
        val root = LinearLayout(ctx).apply {
            id = R.id.dw_article_screen
            orientation = LinearLayout.VERTICAL
            // An article reads on the panel in every design: its vote box is bg-2 and would vanish on Empfang's canvas.
            setBackgroundColor(c.bg)
        }
        val open = IconButton(ctx, ui, R.drawable.dw_ic_external, 19, ui.t(R.string.dw_open_in_help_center)).apply {
            id = R.id.dw_open_in_help_center
            visibility = View.GONE
        }
        external = open
        header = SubHeader(ctx, ui, withFace = false, extra = open, style = ui.design.headerStyle(route))
        header.title.text = ui.t(R.string.dw_help)
        header.back.setOnClickListener { ui.nav.back() }
        header.close.setOnClickListener { if (sheet) ui.nav.goTo(Route.Start) else ui.nav.close() }
        if (sheet) header.showBack(true)
        // Empfang's bar names the collection (or Help) in 14/600 ink-2.
        if (bars && !sheet) header.title.textStyle(14f, 600, c.ink2)
        header.setPadding(header.paddingLeft, topInset + ctx.dp(4), header.paddingRight, header.paddingBottom)
        root.addView(header, linear(MATCH, WRAP))
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(ctx.dp(22), ctx.dp(22), ctx.dp(22), ctx.dp(28))
        status.text = ui.t(R.string.dw_article_loading)
        status.textStyle(14f, 400, c.ink3)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        column.addView(status)
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, linear(MATCH, 0, 1f))
        askBar.id = R.id.dw_article_ask_bar
        askBar.orientation = LinearLayout.VERTICAL
        askBar.visibility = View.GONE
        root.addView(askBar, linear(MATCH, WRAP))
        job = ui.scope.launch {
            when (val result = ui.session.help.article(article.slug)) {
                is ApiResult.Success -> show(result.value)
                else -> {
                    status.text = ui.t(R.string.dw_article_load_error)
                    status.setTextColor(c.error)
                }
            }
        }
        return root
    }

    private fun show(a: HelpArticle) {
        column.removeAllViews()
        val url = ui.session.help.articleUrl(a)
        external?.let { button ->
            button.visibility = if (url != null) View.VISIBLE else View.GONE
            button.setOnClickListener { url?.let(ui.nav::openUrl) }
        }
        a.category?.let { category ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(ImageView(ctx).apply {
                setImageDrawable(ctx.icon(R.drawable.dw_ic_folder, c.brandText))
                decorative()
            }, linear(ctx.dp(15), ctx.dp(15)))
            row.addView(TextView(ctx).apply {
                text = category.name
                textStyle(12.5f, 600, c.brandText)
            }, linear(WRAP, WRAP).apply { marginStart = ctx.dp(6) })
            column.addView(row)
        }
        column.addView(TextView(ctx).apply {
            contentText()
            id = R.id.dw_article_title
            text = a.title
            // Klartext 25/600; Empfang and Direkt 23/700.
            if (bars) textStyle(23f, 700, c.ink, 1.22f, -0.012f) else textStyle(25f, 600, c.ink, 1.22f, -0.016f)
            role(heading = true)
        }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(12) })
        // Meta: the author's initials and name when there is one, then "Updated … · N min read".
        val meta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val words = a.content.replace(Regex("<[^>]*>"), " ").split(Regex("\\s+")).count { it.isNotBlank() }
        val minutes = maxOf(1, ceil(words / 230.0).toInt())
        val dates = listOfNotNull((a.updatedAt ?: a.createdAt)?.let { ui.formats.updated(it, System.currentTimeMillis()) }, ui.t(R.string.dw_min_read, minutes.toString()))
            .joinToString(" · ")
        val metaText = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        a.authorName?.let { author ->
            meta.addView(FaceView(ctx, ui).apply { setFace(Face.Initials(People.initials(author))) }, linear(ctx.dp(32), ctx.dp(32)).apply { marginEnd = ctx.dp(8) })
            metaText.addView(TextView(ctx).apply {
                text = author
                textStyle(13.5f, 600, c.ink)
            })
        }
        metaText.addView(TextView(ctx).apply {
            text = dates
            textStyle(12.5f, 400, c.ink3)
        })
        meta.addView(metaText)
        column.addView(meta)
        column.addView(View(ctx).apply { setBackgroundColor(c.line) }, linear(MATCH, ctx.dp(1)).apply { topMargin = ctx.dp(18) })
        column.addView(body(a), linear(MATCH, WRAP).apply { topMargin = ctx.dp(2) })
        column.addView(vote(a.slug), linear(MATCH, WRAP).apply { topMargin = ctx.dp(28) })
        if (url != null) {
            column.addView(TextView(ctx).apply {
                // The arrow is the external icon after the words, not a glyph appended in code.
                text = ui.t(R.string.dw_open_on_help_center)
                textStyle(13f, 400, c.ink3)
                setCompoundDrawablesRelative(null, null, ctx.icon(R.drawable.dw_ic_external, c.ink3).apply { setBounds(0, 0, ctx.dp(13), ctx.dp(13)) }, null)
                compoundDrawablePadding = ctx.dp(4)
                minHeight = ctx.dp(48)
                gravity = Gravity.CENTER_VERTICAL
                role(button = true)
                setOnClickListener { ui.nav.openUrl(url) }
            }, linear(WRAP, WRAP).apply { topMargin = ctx.dp(6) })
        }
        // A question about this article carries it: its card waits above the composer.
        val ref = url?.let { com.deskwoot.sdk.model.HelpArticleRef(it, a.title) }
        if (bars) {
            if (!sheet) a.category?.name?.let { header.title.text = it }
            showAskBar(ref)
        } else {
            column.addView(askCard(ctx, ui, ref) { null }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(14) })
        }
        if (ui.config.showBranding) column.addView(PoweredBy(ctx, ui), linear(MATCH, WRAP).apply { topMargin = ctx.dp(18) })
    }

    /**
     * Direkt: a full width button, the chat icon and "Ask Fynn about this
     * article". Empfang: "Still have questions?" over the
     * team line and a pill "Write to us".
     */
    private fun showAskBar(ref: com.deskwoot.sdk.model.HelpArticleRef?) {
        askBar.removeAllViews()
        askBar.background = TopLine(c.bg, c.line, ctx.dp(1))
        val config = ui.config
        if (sheet) {
            askBar.setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(14))
            val label = if (config.botActive) ui.t(R.string.dw_ask_about_article, ui.people.botName ?: ui.people.businessName) else ui.t(R.string.dw_write_about_article)
            askBar.addView(PrimaryButton(ctx, ui, label, R.drawable.dw_ic_chat, iconAtStart = true).apply {
                id = R.id.dw_ask_button
                setOnClickListener { ui.nav.startConversation(null, article = ref) }
            }, linear(MATCH, WRAP))
        } else {
            askBar.orientation = LinearLayout.HORIZONTAL
            askBar.gravity = Gravity.CENTER_VERTICAL
            askBar.setPaddingRelative(ctx.dp(18), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx).apply {
                text = ui.t(R.string.dw_still_questions)
                textStyle(14.5f, 650, c.ink)
            })
            texts.addView(TextView(ctx).apply {
                text = com.deskwoot.sdk.ui.people.TeamLine.of(config, ui.people, ui.strings).asSentences(ui.strings)
                textStyle(14f, 400, c.ink2, 1.35f)
            }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(2) })
            askBar.addView(texts, linear(0, WRAP, 1f))
            askBar.addView(PrimaryButton(ctx, ui, ui.t(R.string.dw_write_to_us)).apply {
                id = R.id.dw_ask_button
                pill(42)
                setPaddingRelative(ctx.dp(18), 0, ctx.dp(18), 0)
                setOnClickListener { ui.nav.startConversation(null, article = ref) }
            }, linear(WRAP, WRAP).apply { marginStart = ctx.dp(12) })
        }
        askBar.visibility = View.VISIBLE
    }

    /**
     * The body through the help center's allow list in a
     * WebView; a device without a usable WebView (disabled, updating,
     * missing) gets the same body as text instead of a crash. UI Automator
     * and Maestro see no id on a WebView (its own accessibility tree stands
     * in for its node), so the id for UI tests names a frame around it.
     */
    private fun body(a: HelpArticle): View {
        val clean = ArticleHtml.clean(a.content)
        val page = documentUrl(a)
        val view = try {
            makeWebView(ctx)
        } catch (_: Throwable) {
            null
        }
        if (view != null) {
            try {
                configure(view, clean, page)
                web = view
                return FrameLayout(ctx).apply {
                    id = R.id.dw_article_body
                    // Reported to UI tests also in a compressed view tree; TalkBack skips a frame without words.
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    addView(view, FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.WRAP_CONTENT))
                }
            } catch (_: Throwable) {
                try {
                    view.destroy()
                } catch (_: Throwable) {
                    // Nothing more to release.
                }
            }
        }
        return textBody(clean, page)
    }

    /** Where the article lives on the public help center: relative links resolve as they do there. */
    private fun documentUrl(a: HelpArticle): String {
        val base = ui.config.helpCenterBaseUrl?.trim()?.takeIf { Links.kind(it) == LinkKind.WEB }?.trimEnd('/')
            ?: return FALLBACK_BASE
        return base + "/" + android.net.Uri.encode(a.slug)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView, clean: String, page: String) {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        val settings = view.settings
        // The allow list of the help center editor has no scripts or embeds.
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.textZoom = (ctx.resources.configuration.fontScale * 100).toInt()
        view.webViewClient = ArticleClient(page)
        view.loadDataWithBaseURL(page, html(clean), "text/html", "utf-8", null)
    }

    /**
     * Only a tap moves anywhere: a redirect or a refresh of
     * the page never navigates by itself. A link to another article of this
     * help center opens it natively; http, https, mailto and tel go outside
     * through the messenger's one scheme rule; anything else does nothing.
     */
    private inner class ArticleClient(private val page: String) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.hasGesture()) return true
            if (sameDocument(url)) return false
            follow(url)
            return true
        }

        // Before Android 7 the WebView calls only this one, without the gesture flag. The sanitized page
        // has no way to navigate without a tap (no script, no meta refresh), so a call here is a tap.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (sameDocument(url)) return false
            follow(url)
            return true
        }

        /** A jump to a heading of the article itself scrolls in place. */
        private fun sameDocument(url: String): Boolean = url.contains('#') && url.substringBefore('#') == page
    }

    /** Where a link of the article leads. */
    private fun follow(url: String) {
        when (val link = ArticleLink.route(url, ui.config.helpCenterBaseUrl)) {
            is ArticleLink.Article -> if (link.slug != article.slug) ui.nav.goTo(Route.Article(link.slug))
            ArticleLink.Outside -> ui.nav.openUrl(url)
            ArticleLink.Ignore -> Unit
        }
    }

    /** The body as text: the same allow listed HTML, links by the same rule, without the pictures. */
    private fun textBody(clean: String, page: String): View = TextView(ctx).apply {
        id = R.id.dw_article_body
        contentText()
        textStyle(15f, 400, c.ink, 1.62f)
        setLinkTextColor(c.brandText)
        val text = SpannableStringBuilder(HtmlCompat.fromHtml(ArticleHtml.withoutImages(clean), HtmlCompat.FROM_HTML_MODE_COMPACT))
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val flags = text.getSpanFlags(span)
            text.removeSpan(span)
            val target = resolve(page, span.url.orEmpty())
            text.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = follow(target)
                },
                start,
                end,
                flags,
            )
        }
        while (text.isNotEmpty() && text[text.length - 1].isWhitespace()) text.delete(text.length - 1, text.length)
        this.text = text
        movementMethod = LinkMovementMethod.getInstance()
        setPadding(0, ctx.dp(16), 0, 0)
    }

    private fun resolve(page: String, href: String): String = try {
        java.net.URI(page).resolve(href.trim()).toString()
    } catch (_: IllegalArgumentException) {
        href
    } catch (_: java.net.URISyntaxException) {
        href
    }

    /** The article's HTML in a page with the messenger's type and colors. */
    private fun html(content: String): String {
        fun css(color: Int): String = String.format("#%06x", color and 0xFFFFFF)
        fun cssAlpha(color: Int): String = "rgba(${Color.red(color)},${Color.green(color)},${Color.blue(color)},${Color.alpha(color) / 255.0})"
        val dir = if (ui.rtl) "rtl" else "ltr"
        return """
            <!doctype html><html dir="$dir"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="${if (c.isDark) "dark" else "light"}">
            <style>
              html, body { margin: 0; padding: 0; background: transparent; }
              body { font-family: sans-serif; font-size: 15px; line-height: 1.62; color: ${css(c.ink)}; overflow-wrap: anywhere; -webkit-text-size-adjust: none; }
              p { margin: 16px 0 0; } body > :first-child { margin-top: 16px; }
              h1, h2, h3 { font-size: 17px; font-weight: 700; line-height: 1.3; letter-spacing: -0.005em; margin: 26px 0 0; }
              h4 { font-size: 15px; font-weight: 700; margin: 20px 0 0; }
              a { color: ${css(c.brandText)}; font-weight: 600; text-decoration: underline; text-underline-offset: 2px; text-decoration-thickness: 1px; }
              strong, b { font-weight: 700; }
              mark { background: ${cssAlpha(c.brandSoft)}; color: inherit; border-radius: 3px; padding: 0 1px; }
              ul { margin: 16px 0 0; padding-inline-start: 20px; } ul li { margin-top: 6px; } ul li::marker { color: ${css(c.ink3)}; }
              ol { margin: 16px 0 0; padding: 0; list-style: none; counter-reset: step; }
              ol li { counter-increment: step; position: relative; padding-inline-start: 36px; margin-top: 12px; min-height: 24px; }
              ol li::before { content: counter(step); position: absolute; inset-inline-start: 0; top: 0; width: 24px; height: 24px; border-radius: 50%;
                background: ${cssAlpha(c.brandSoft)}; color: ${css(c.brandText)}; font-size: 12.5px; font-weight: 700; line-height: 24px; text-align: center; }
              img { max-width: 100%; height: auto; border-radius: 12px; display: block; margin-top: 16px; }
              blockquote { margin: 16px 0 0; padding-inline-start: 14px; border-inline-start: 3px solid ${css(c.line2)}; color: ${css(c.ink2)}; }
              code { font-family: monospace; font-size: 13px; background: ${css(c.bg2)}; border-radius: 6px; padding: 1px 5px; }
              pre { background: ${css(c.bg2)}; border-radius: 8px; padding: 12px; overflow-x: auto; margin: 16px 0 0; }
              pre code { padding: 0; background: none; }
              hr { border: 0; border-top: 1px solid ${css(c.line)}; margin: 22px 0 0; }
              .table { overflow-x: auto; margin-top: 16px; }
              table { border-collapse: collapse; font-size: 13.5px; min-width: 100%; }
              th, td { border: 1px solid ${css(c.line)}; padding: 6px 10px; text-align: start; vertical-align: top; }
              th { background: ${css(c.bg2)}; font-weight: 700; }
            </style></head><body>${content.replace("<table", "<div class=\"table\"><table").replace("</table>", "</table></div>")}</body></html>
        """.trimIndent()
    }

    /** "Was this article helpful?" with Yes and No; the answer shows at once, a failed request brings the buttons back. */
    private fun vote(slug: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(ctx.dp(16), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            background = ctx.rounded(c.bg2, 14f)
        }
        val question = TextView(ctx).apply {
            text = ui.t(R.string.dw_article_helpful)
            textStyle(14f, 600, c.ink)
        }
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val thanks = TextView(ctx).apply {
            id = R.id.dw_vote_thanks
            textStyle(14f, 400, c.ink2, 1.45f)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun showVote(helpful: Boolean?) {
            question.visibility = if (helpful == null) View.VISIBLE else View.GONE
            buttons.visibility = if (helpful == null) View.VISIBLE else View.GONE
            thanks.visibility = if (helpful == null) View.GONE else View.VISIBLE
            thanks.text = when (helpful) {
                true -> ui.t(R.string.dw_feedback_thanks_yes)
                false -> ui.t(R.string.dw_feedback_thanks_no)
                null -> ""
            }
        }
        fun button(id: Int, iconRes: Int, label: String, helpful: Boolean): View = LinearLayout(ctx).apply {
            this.id = id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(48)
            setPaddingRelative(ctx.dp(14), 0, ctx.dp(14), 0)
            backgroundKeepingPadding(android.graphics.drawable.InsetDrawable(ripple(c.withAlpha(c.brandText, 0.12), ctx.rounded(c.bg, 10f, c.line2)), 0, ctx.dp(6), 0, ctx.dp(6)))
            addView(ImageView(ctx).apply {
                setImageDrawable(ctx.icon(iconRes, c.ink))
                decorative()
            }, linear(ctx.dp(16), ctx.dp(16)))
            addView(TextView(ctx).apply {
                text = label
                textStyle(13.5f, 600, c.ink)
            }, linear(WRAP, WRAP).apply { marginStart = ctx.dp(6) })
            role(button = true)
            contentDescription = label
            setOnClickListener {
                UiMemory.votes[slug] = helpful
                showVote(helpful)
                voteJob = ui.scope.launch {
                    if (ui.session.help.vote(slug, helpful) !is ApiResult.Success) {
                        UiMemory.votes.remove(slug)
                        showVote(null)
                    }
                }
            }
        }
        buttons.addView(button(R.id.dw_vote_yes, R.drawable.dw_ic_thumbs_up, ui.t(R.string.dw_yes), true))
        buttons.addView(button(R.id.dw_vote_no, R.drawable.dw_ic_thumbs_down, ui.t(R.string.dw_no), false), linear(WRAP, WRAP).apply { marginStart = ctx.dp(8) })
        box.addView(question)
        box.addView(buttons, linear(WRAP, WRAP).apply { topMargin = ctx.dp(4) })
        box.addView(thanks)
        showVote(UiMemory.votes[slug] ?: ui.session.help.voteOf(slug))
        return box
    }

    override fun onTopInset(top: Int) {
        topInset = if (sheet) 0 else top
        if (isBuilt) header.setPadding(header.paddingLeft, topInset + ctx.dp(4), header.paddingRight, header.paddingBottom)
    }

    override fun destroy() {
        job?.cancel()
        voteJob?.cancel()
        web?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }

    internal companion object {
        /** Relative links of an article without a public help center address resolve here. */
        const val FALLBACK_BASE: String = "https://deskwoot.com/"

        /** Makes the article's WebView. A test puts one here that throws, as a device without a usable WebView does. */
        @Volatile
        var makeWebView: (Context) -> WebView = { WebView(it) }
    }
}
