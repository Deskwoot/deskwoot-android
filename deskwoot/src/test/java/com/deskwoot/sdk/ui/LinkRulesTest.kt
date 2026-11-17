package com.deskwoot.sdk.ui

import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.ui.help.ArticleHtml
import com.deskwoot.sdk.ui.help.ArticleLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one scheme rule for links, the article's allow list and link routing, the theme before the config. */
class LinkRulesTest {
    @Test
    fun `every link of the messenger follows the same scheme rule`() {
        assertEquals(LinkKind.WEB, Links.kind("https://acme.org"))
        assertEquals(LinkKind.WEB, Links.kind("http://acme.org/a?b=c"))
        assertEquals(LinkKind.WEB, Links.kind("  HTTPS://Acme.org  "))
        assertEquals(LinkKind.SYSTEM, Links.kind("mailto:a@acme.org"))
        assertEquals(LinkKind.SYSTEM, Links.kind("tel:+4930123"))
        for (blocked in listOf(
            "file:///etc/passwd", "file:///sdcard/a.pdf", "content://com.example.provider/x", "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)", "JaVaScRiPt:alert(1)", "java\tscript:alert(1)", "myapp://pay?amount=100", "data:text/html,hi",
            "ftp://acme.org", "sms:123", "market://details?id=x", "https:", "https:///nohost", "relative/page", "/absolute/path", "",
        )) {
            assertEquals(blocked, LinkKind.BLOCKED, Links.kind(blocked))
        }
    }

    @Test
    fun `an article keeps only the web's allow list of tags and attributes`() {
        val html = """
            <p class="lead" style="color:red" onclick="steal()">Hello <span>there</span></p><script>alert(1)</script><SCRIPT>x()</SCRIPT>
            <iframe src="https://x.io"></iframe><style>p{}</style><meta http-equiv="refresh" content="0;url=https://evil.example">
            <a href="javascript:alert(1)">bad</a><a href="myapp://pay?amount=100">deep</a><a href="file:///etc/passwd">file</a>
            <a href="https://shop.example/orders" target="_blank" rel="noopener">ok</a><a href="mailto:help@acme.org">mail</a>
            <img src="https://cdn.example/a.png" alt="A" onerror="x()"><img src="data:image/svg+xml;base64,AAA"><img src="data:image/png;base64,AAA">
            <table><tr><td>1</td></tr></table><form><input value="x"></form><!-- note --><h5>Small</h5><a href="returns">relative</a>
        """.trimIndent()
        val clean = ArticleHtml.clean(html)
        assertTrue(clean, clean.contains("<p class=\"lead\">Hello there</p>"))
        for (gone in listOf("script", "alert", "x()", "iframe", "<style", "<meta", "refresh", "onclick", "onerror", "style=", "javascript", "myapp://", "file:", "<form", "<input", "note", "svg", "<span", "<h5")) {
            assertFalse("$gone in $clean", clean.contains(gone))
        }
        assertTrue("a link without a safe address keeps its text", clean.contains("<a>bad</a>"))
        assertTrue(clean.contains("<a href=\"https://shop.example/orders\" target=\"_blank\" rel=\"noopener\">ok</a>"))
        assertTrue(clean.contains("<a href=\"mailto:help@acme.org\">mail</a>"))
        assertTrue(clean.contains("<a href=\"returns\">relative</a>"))
        assertTrue(clean.contains("<img src=\"https://cdn.example/a.png\" alt=\"A\">"))
        assertTrue(clean.contains("<img src=\"data:image/png;base64,AAA\">"))
        assertTrue(clean.contains("<table><tr><td>1</td></tr></table>"))
        assertTrue("a tag outside the list keeps its text", clean.contains("Small"))
        // Quotes and angle brackets inside a value stay inside it.
        assertEquals("<a title=\"a &quot;b&quot; &lt;c\">x</a>", ArticleHtml.clean("<a title='a \"b\" <c'>x</a>"))
    }

    @Test
    fun `a tag split by a removed tag never joins into one that is not on the list`() {
        val inputs = listOf(
            "<<x>script>alert(1)</script>",
            "<scr<b>ipt>",
            "<<x>iframe src=\"https://evil.example\">",
            "<<x>a href=\"myapp://pay\">",
            "<<x>img src=x onerror=alert(1)>",
            "<<x>meta http-equiv=refresh content=\"0;url=https://evil.example\">",
            "<<x>form><<x>input type=password>",
            "<<x>style>",
        )
        // A tag as the sanitizer writes it: a lower case name, then only name="value" pairs without a raw "<".
        val rebuilt = Regex("<(/?)([a-z0-9]+)((?: [a-z]+=\"[^\"<]*\")*)>")
        for (input in inputs) {
            val clean = ArticleHtml.clean(input)
            var at = clean.indexOf('<')
            while (at >= 0) {
                val tag = rebuilt.find(clean, at)?.takeIf { it.range.first == at }
                assertTrue("a \"<\" that starts no rebuilt tag in $clean (from $input)", tag != null)
                assertTrue("${tag!!.value} is not on the list (from $input)", tag.groupValues[2] in ArticleHtml.allowedTags)
                at = clean.indexOf('<', at + 1)
            }
            for (gone in listOf("<script", "<iframe", "<a ", "<img", "<meta", "<form", "<input", "<style")) {
                assertFalse("$gone in $clean (from $input)", clean.contains(gone))
            }
        }
        assertEquals("&lt;script>alert(1)", ArticleHtml.clean("<<x>script>alert(1)</script>"))
        assertEquals("&lt;form>&lt;input type=password>", ArticleHtml.clean("<<x>form><<x>input type=password>"))
        // A "<" in plain text stays readable as text.
        assertEquals("<p>1 &lt; 2</p>", ArticleHtml.clean("<p>1 < 2</p>"))
    }

    @Test
    fun `links in an article open other articles natively and everything else by the scheme rule`() {
        val base = "https://help.acme.com/hc"
        assertEquals(ArticleLink.Article("returns"), ArticleLink.route("https://help.acme.com/hc/returns", base))
        assertEquals(ArticleLink.Article("returns"), ArticleLink.route("https://HELP.acme.com/hc/returns?locale=de", base))
        assertEquals(ArticleLink.Article("returns"), ArticleLink.route("https://help.acme.com/hc/returns", "$base/"))
        assertEquals(ArticleLink.Article("größe"), ArticleLink.route("https://help.acme.com/hc/gr%C3%B6%C3%9Fe", base))
        assertEquals(ArticleLink.Outside, ArticleLink.route("https://help.acme.com/other/page", base))
        assertEquals(ArticleLink.Outside, ArticleLink.route("https://help.acme.com/hc/a/b", base))
        assertEquals(ArticleLink.Outside, ArticleLink.route("https://help.acme.com/hc/", base))
        assertEquals(ArticleLink.Outside, ArticleLink.route("https://shop.example/x", base))
        assertEquals(ArticleLink.Outside, ArticleLink.route("mailto:help@acme.org", base))
        assertEquals(ArticleLink.Outside, ArticleLink.route("tel:+491234", base))
        assertEquals(ArticleLink.Ignore, ArticleLink.route("myapp://pay?amount=100", base))
        assertEquals(ArticleLink.Ignore, ArticleLink.route("javascript:alert(1)", base))
        assertEquals(ArticleLink.Ignore, ArticleLink.route("sms:+491234", base))
        assertEquals(ArticleLink.Outside, ArticleLink.route("https://help.acme.com/hc/returns", null))
        // A help center at the root of its host.
        assertEquals(ArticleLink.Article("returns"), ArticleLink.route("https://help.acme.com/returns", "https://help.acme.com"))
    }

    @Test
    fun `the text fallback keeps an image's alt text and drops the picture`() {
        assertEquals("<p>a  Chart  b</p>", ArticleHtml.withoutImages("<p>a <img src=\"https://x/a.png\" alt=\"Chart\"> b</p>"))
        assertEquals("<p>a  b</p>", ArticleHtml.withoutImages("<p>a <img src=\"https://x/a.png\"> b</p>"))
    }

    @Test
    fun `before the config the inbox setting follows the app, so a dark app gets no white flash`() {
        val dark = checkNotNull(WidgetConfig.parse(baseConfig("widgetTheme" to "dark").toString()))
        val light = checkNotNull(WidgetConfig.parse(baseConfig("widgetTheme" to "light").toString()))
        assertTrue(ThemeChoice.isDark(Deskwoot.Theme.INBOX, null, hostNight = true, ownNight = false))
        assertFalse(ThemeChoice.isDark(Deskwoot.Theme.INBOX, null, hostNight = false, ownNight = true))
        assertTrue(ThemeChoice.isDark(Deskwoot.Theme.INBOX, null, hostNight = null, ownNight = true))
        // Once the config is there, the inbox decides.
        assertTrue(ThemeChoice.isDark(Deskwoot.Theme.INBOX, dark, hostNight = false, ownNight = false))
        assertFalse(ThemeChoice.isDark(Deskwoot.Theme.INBOX, light, hostNight = true, ownNight = true))
        assertTrue(ThemeChoice.isDark(Deskwoot.Theme.SYSTEM, light, hostNight = true, ownNight = false))
        assertFalse(ThemeChoice.isDark(Deskwoot.Theme.LIGHT, dark, hostNight = true, ownNight = true))
    }
}
