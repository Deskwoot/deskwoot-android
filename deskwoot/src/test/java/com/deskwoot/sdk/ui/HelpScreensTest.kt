package com.deskwoot.sdk.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.TextView
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.help.ArticleScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The help center's screens against a real session over a scripted server:
 * collections, a collection, an article; the article's links and its body
 * without a WebView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HelpScreensTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var scripted: ScriptedSession
    private var controller: ActivityController<DeskwootActivity>? = null

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
        scripted = ScriptedSession().install()
    }

    @After
    fun tearDown() {
        controller?.let { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        ArticleScreen.makeWebView = { WebView(it) }
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
    }

    /** The messenger on the help, then the first collection's first article ("How to change your plan"). */
    private fun openArticle(): DeskwootActivity {
        val c = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, DeskwootActivity.Entry.Help)).setup()
        controller = c
        idle()
        val a = c.get()
        val collections = rows(a, R.id.dw_collection_row)
        assertEquals(listOf("Billing", "Shipping"), collections.map { firstText(it) })
        collections.first().performClick()
        idle()
        assertTrue(a.findViewById<View>(R.id.dw_collection_screen).isShown)
        val articles = rows(a, R.id.dw_article_row)
        assertEquals(listOf("How to change your plan", "Refunds"), articles.map { firstText(it) })
        articles.first().performClick()
        idle()
        assertTrue(a.findViewById<View>(R.id.dw_article_screen).isShown)
        assertEquals("How to change your plan", a.findViewById<TextView>(R.id.dw_article_title).text.toString())
        return a
    }

    private fun rows(a: DeskwootActivity, id: Int): List<View> = all(a.window.decorView).filter { it.id == id && it.isShown }

    private fun all(root: View): List<View> =
        if (root is android.view.ViewGroup) listOf(root) + (0 until root.childCount).flatMap { all(root.getChildAt(it)) } else listOf(root)

    private fun firstText(row: View): String = all(row).filterIsInstance<TextView>().first().text.toString()

    /** The article's WebView, inside the frame that carries the body's id. */
    private fun articleWeb(a: DeskwootActivity): WebView = a.findViewById<android.view.ViewGroup>(R.id.dw_article_body).getChildAt(0) as WebView

    /**
     * The node UI Automator and Maestro read for [view]: they ask for view
     * ids (a service's request sets this flag on the window), and only such a
     * request reports them.
     */
    private fun nodeAsUiTestsSeeIt(view: View): android.view.accessibility.AccessibilityNodeInfo {
        val attachInfo = View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }.get(view)
        val flags = attachInfo.javaClass.getDeclaredField("mAccessibilityFetchFlags").apply { isAccessible = true }
        val reportIds = android.view.accessibility.AccessibilityNodeInfo::class.java.getField("FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS").getInt(null)
        flags.setInt(attachInfo, flags.getInt(attachInfo) or reportIds)
        return view.createAccessibilityNodeInfo()
    }

    private fun shownArticle(a: DeskwootActivity): String =
        all(a.window.decorView).filterIsInstance<TextView>().last { it.id == R.id.dw_article_title && it.isShown }.text.toString()

    private class Tap(private val url: String, private val gesture: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)

        override fun isForMainFrame(): Boolean = true

        override fun isRedirect(): Boolean = false

        override fun hasGesture(): Boolean = gesture

        override fun getMethod(): String = "GET"

        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    @Test
    fun `the article body is the allow listed HTML in a WebView at the article's own address`() {
        val a = openArticle()
        val web = articleWeb(a)
        val loaded = shadowOf(web).lastLoadDataWithBaseURL
        assertEquals("https://help.acme.test/hc/change-plan", loaded.baseUrl)
        assertTrue(loaded.data, loaded.data.contains("<a href=\"https://help.acme.test/hc/refunds\">Refunds</a>"))
        assertFalse(web.settings.javaScriptEnabled)
        // Both ways to the public help center are 48 dp touch targets.
        val external = a.findViewById<View>(R.id.dw_open_in_help_center)
        assertTrue(external.isShown)
        assertEquals(48f, external.layoutParams.height / a.resources.displayMetrics.density, 0.5f)
        val link = all(a.window.decorView).filterIsInstance<TextView>().single { it.text.startsWith(a.getString(R.string.dw_open_on_help_center)) }
        assertTrue(link.minHeight >= (48 * a.resources.displayMetrics.density).toInt())
        link.performClick()
        assertEquals("https://help.acme.test/hc/change-plan", shadowOf(a).nextStartedActivity?.dataString)
    }

    @Test
    fun `UI Automator and Maestro find the article body by its id, around the WebView and as the text without one`() {
        val a = openArticle()
        val body = a.findViewById<View>(R.id.dw_article_body)
        assertFalse("the id is not on the WebView, whose own tree hides it", body is WebView)
        assertTrue(articleWeb(a).id == View.NO_ID)
        assertEquals(1, all(a.window.decorView).count { it.id == R.id.dw_article_body })
        assertTrue("reported in a compressed tree too", body.isImportantForAccessibility)
        // As in an app: the app's package, then the id.
        assertEquals(app.packageName + ":id/dw_article_body", nodeAsUiTestsSeeIt(body).viewIdResourceName)
        controller?.let { c -> c.pause().stop().destroy() }
        controller = null

        ArticleScreen.makeWebView = { throw IllegalStateException("WebView is disabled") }
        val text = openArticle().findViewById<View>(R.id.dw_article_body)
        assertTrue(text is TextView)
        assertTrue(text.isImportantForAccessibility)
        assertEquals(app.packageName + ":id/dw_article_body", nodeAsUiTestsSeeIt(text).viewIdResourceName)
    }

    @Test
    fun `the ask of an article shows only once the article loaded, and its question carries the article`() {
        val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val articles = scripted.articles
        scripted.http.on("GET /api/widget/articles") { call ->
            val slug = call.query["articleSlug"]
            if (slug != null) loaded.await()
            when {
                slug != null -> articles[slug]?.let { com.deskwoot.sdk.ok(com.deskwoot.sdk.json("article" to it)) } ?: com.deskwoot.sdk.fail(404)
                call.query["categoryId"] != null -> com.deskwoot.sdk.ok(com.deskwoot.sdk.json("categoryArticles" to scripted.collectionArticles[call.query["categoryId"]].orEmpty()))
                else -> com.deskwoot.sdk.ok(com.deskwoot.sdk.json("categories" to scripted.collections))
            }
        }
        val c = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, DeskwootActivity.Entry.Help)).setup()
        controller = c
        idle()
        val a = c.get()
        rows(a, R.id.dw_collection_row).first().performClick()
        idle()
        rows(a, R.id.dw_article_row).first().performClick()
        idle()
        assertTrue(a.findViewById<View>(R.id.dw_article_screen).isShown)
        assertTrue("no ask while the article loads", rows(a, R.id.dw_ask_button).isEmpty())

        loaded.complete(Unit)
        idle()
        val ask = rows(a, R.id.dw_ask_button).single()
        ask.performClick()
        idle()
        val chip = rows(a, R.id.dw_composer_article).single()
        assertTrue(all(chip).filterIsInstance<TextView>().any { it.text.toString() == "How to change your plan" })
        val input = rows(a, R.id.dw_composer_input).single() as android.widget.EditText
        input.setText("Can I switch plans mid month?")
        rows(a, R.id.dw_composer_send).single().performClick()
        idle()
        val sent = checkNotNull(scripted.session.state.value.thread?.pending?.lastOrNull())
        assertEquals("https://help.acme.test/hc/change-plan", sent.helpArticle?.url)
        assertEquals("How to change your plan", sent.helpArticle?.title)
    }

    @Test
    fun `a tap on a link of the help center opens that article natively, other links by the scheme rule`() {
        val a = openArticle()
        val web = articleWeb(a)
        val client = shadowOf(web).webViewClient

        // Without a gesture (a refresh, a redirect) nothing happens.
        assertTrue(client.shouldOverrideUrlLoading(web, Tap("https://shop.example/x", gesture = false)))
        assertTrue(client.shouldOverrideUrlLoading(web, Tap("https://help.acme.test/hc/refunds", gesture = false)))
        assertNull(shadowOf(a).nextStartedActivity)
        assertEquals("How to change your plan", shownArticle(a))

        // Deep links of the app, files and scripts: never.
        for (blocked in listOf("myapp://pay?amount=100", "file:///sdcard/a.pdf", "javascript:alert(1)", "intent://x#Intent;end")) {
            assertTrue(client.shouldOverrideUrlLoading(web, Tap(blocked, gesture = true)))
            assertNull(blocked, shadowOf(a).nextStartedActivity)
        }

        // Another web page: the browser.
        assertTrue(client.shouldOverrideUrlLoading(web, Tap("https://shop.example/x", gesture = true)))
        val started: Intent? = shadowOf(a).nextStartedActivity
        assertEquals("https://shop.example/x", started?.dataString)

        // A jump inside the article scrolls in place.
        assertFalse(client.shouldOverrideUrlLoading(web, Tap("https://help.acme.test/hc/change-plan#steps", gesture = true)))

        // Another article of this help center: the native article screen.
        assertTrue(client.shouldOverrideUrlLoading(web, Tap("https://help.acme.test/hc/refunds", gesture = true)))
        idle()
        assertNull(shadowOf(a).nextStartedActivity)
        assertEquals("Refunds", shownArticle(a))
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("back returns to the article the link was in", "How to change your plan", shownArticle(a))
    }

    @Test
    fun `without a usable WebView the article shows as text with working links instead of crashing`() {
        ArticleScreen.makeWebView = { throw IllegalStateException("WebView is disabled") }
        val a = openArticle()
        val body = a.findViewById<View>(R.id.dw_article_body)
        assertTrue(body is TextView)
        body as TextView
        assertEquals("Open Refunds or the shop.", body.text.toString())
        assertNotNull("the vote and the help center link still follow", a.findViewById(R.id.dw_vote_yes))
        val spans = (body.text as Spanned).let { text -> text.getSpans(0, text.length, ClickableSpan::class.java).associateBy { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() } }
        assertEquals(setOf("Refunds", "the shop"), spans.keys)

        spans.getValue("the shop").onClick(body)
        assertEquals("https://shop.example/x", shadowOf(a).nextStartedActivity?.dataString)
        spans.getValue("Refunds").onClick(body)
        idle()
        assertEquals("Refunds", shownArticle(a))
    }

    @Test
    fun `a WebView that fails while it is set up falls back to text too`() {
        ArticleScreen.makeWebView = { context ->
            object : WebView(context) {
                override fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) {
                    throw RuntimeException("android.webkit.WebViewFactory\$MissingWebViewPackageException")
                }
            }
        }
        val a = openArticle()
        assertTrue(a.findViewById<View>(R.id.dw_article_body) is TextView)
    }
}
