package com.deskwoot.sdk.ui.help

import com.deskwoot.sdk.ui.LinkKind
import com.deskwoot.sdk.ui.Links
import com.deskwoot.sdk.ui.text.MessageText
import java.net.URI

/**
 * The web help center's allow list for an article's HTML: only these tags
 * and attributes stay.
 * A tag outside the list goes and keeps its text; scripts, styles, frames
 * and the like go with their content. `href` keeps only an http, https,
 * mailto or tel address (or a relative one), `src` only http, https or a
 * raster `data:image`. The iOS SDK's `ArticleSanitizer` keeps the same list.
 */
internal object ArticleHtml {
    val allowedTags: Set<String> = setOf(
        "p", "br", "hr", "h1", "h2", "h3", "h4",
        "strong", "b", "em", "i", "u", "s", "del", "ins", "mark",
        "ul", "ol", "li", "blockquote", "code", "pre", "a", "img",
        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col",
    )
    val allowedAttributes: Set<String> = setOf("href", "title", "alt", "src", "target", "rel", "class")

    /** Gone with everything inside them. */
    private val droppedWithContent = listOf(
        "script", "style", "iframe", "object", "embed", "template", "svg", "math", "noscript", "textarea", "select", "title", "head", "frameset",
    )
    private val voidTags = setOf("br", "hr", "img", "col")
    private val COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)\\b([^>]*)>")
    private val ATTRIBUTE = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>`=]+))?")
    private val DROPPED: List<Pair<Regex, Regex>> = droppedWithContent.map { tag ->
        Regex("<$tag\\b[\\s\\S]*?</$tag\\s*>", RegexOption.IGNORE_CASE) to Regex("<$tag\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    }

    /**
     * The article's HTML with only the allowed tags, each rebuilt from its
     * name and its allowed attributes. Every `<` of the text between them is
     * written `&lt;`: a tag that is not allowed goes, and its neighbours are
     * never joined into a new one (`<<x>script>` stays the text "<script>").
     */
    fun clean(html: String): String {
        var out = COMMENT.replace(html, "")
        for ((block, single) in DROPPED) {
            out = block.replace(out, "")
            out = single.replace(out, "")
        }
        val result = StringBuilder(out.length + 16)
        var textStart = 0
        for (m in TAG.findAll(out)) {
            appendText(result, out, textStart, m.range.first)
            result.append(rebuilt(m))
            textStart = m.range.last + 1
        }
        appendText(result, out, textStart, out.length)
        return result.toString()
    }

    /** The tag of [m] as it may stay, or nothing. */
    private fun rebuilt(m: MatchResult): String {
        val closing = m.groupValues[1] == "/"
        val name = m.groupValues[2].lowercase()
        return when {
            name !in allowedTags -> ""
            closing -> if (name in voidTags) "" else "</$name>"
            else -> "<$name${attributes(m.groupValues[3], name)}>"
        }
    }

    /** Text between tags: a `<` in it can start nothing. */
    private fun appendText(out: StringBuilder, source: String, from: Int, to: Int) {
        for (i in from until to) {
            val ch = source[i]
            if (ch == '<') out.append("&lt;") else out.append(ch)
        }
    }

    private fun attributes(raw: String, tag: String): String {
        val out = StringBuilder()
        for (m in ATTRIBUTE.findAll(raw)) {
            val name = m.groupValues[1].lowercase()
            if (name !in allowedAttributes) continue
            var value = m.groupValues[2]
            if (value.startsWith("\"") || value.startsWith("'")) value = value.substring(1, value.length - 1)
            value = MessageText.decodeEntities(value)
            if (name == "href" && !safeLink(value)) continue
            if (name == "src" && !safeImage(value, tag)) continue
            val escaped = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            out.append(' ').append(name).append("=\"").append(escaped).append('"')
        }
        return out.toString()
    }

    private fun safeLink(value: String): Boolean {
        val scheme = Links.scheme(value) ?: return true
        return scheme in Links.ALLOWED_SCHEMES
    }

    private fun safeImage(value: String, tag: String): Boolean {
        val scheme = Links.scheme(value) ?: return true
        if (scheme == "data") {
            val lower = value.trim().lowercase()
            return tag == "img" && lower.startsWith("data:image/") && "svg" !in lower
        }
        return scheme == "http" || scheme == "https"
    }

    /** For the text fallback: images cannot show there, so they go (their alt text stays as text). */
    fun withoutImages(clean: String): String =
        Regex("<img\\b([^>]*)>").replace(clean) { m ->
            Regex("alt=\"([^\"]*)\"").find(m.groupValues[1])?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { " $it " } ?: ""
        }
}

/** Where a tap on a link of an article goes. */
internal sealed class ArticleLink {
    /** An article of this help center: the native article screen. */
    data class Article(val slug: String) : ArticleLink()

    /** Another web page, a mail or a phone number: outside, by [com.deskwoot.sdk.ui.MessengerNavigation.openUrl]. */
    object Outside : ArticleLink()

    /** Any other scheme (an app's deep link, `javascript:`, `file:`): nothing. */
    object Ignore : ArticleLink()

    internal companion object {
        fun route(url: String, helpCenterBaseUrl: String?): ArticleLink = when (Links.kind(url)) {
            LinkKind.WEB -> helpCenterBaseUrl?.let { articleSlug(url, it) }?.let(::Article) ?: Outside
            LinkKind.SYSTEM -> Outside
            LinkKind.BLOCKED -> Ignore
        }

        /** "https://help.acme.com/hc/orders" with the base "https://help.acme.com/hc": "orders". */
        fun articleSlug(url: String, base: String): String? {
            val link = parse(url) ?: return null
            val root = parse(base) ?: return null
            if (link.host.isNullOrEmpty() || !link.host.equals(root.host, ignoreCase = true)) return null
            val basePath = root.rawPath.orEmpty().trimEnd('/')
            val path = link.rawPath.orEmpty()
            if (!path.startsWith("$basePath/")) return null
            val rest = path.substring(basePath.length + 1)
            if (rest.isEmpty() || rest.contains('/')) return null
            return try {
                java.net.URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                rest
            }
        }

        private fun parse(text: String): URI? = try {
            URI(text.trim())
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }
}
