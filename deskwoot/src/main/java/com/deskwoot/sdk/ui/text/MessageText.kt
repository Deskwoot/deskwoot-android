package com.deskwoot.sdk.ui.text

/**
 * A message's text before it is drawn: team replies written as HTML become
 * markdown text (`bubbleText`), the last line that is
 * only a link becomes a card, and lists show a short preview.
 */
internal object MessageText {
    private val TAG = Regex("<[a-zA-Z/!][^>]*>")
    private val ANCHOR = Regex("<a\\b[^>]*?href\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>(.*?)</a\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val LI_OPEN = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val LI_CLOSE = Regex("</li\\s*>", RegexOption.IGNORE_CASE)
    private val CELL_CLOSE = Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE)
    private val BLOCK_CLOSE = Regex("</(p|div|h[1-6]|blockquote|tr)\\s*>", RegexOption.IGNORE_CASE)
    private val LIST_TAG = Regex("</?(ul|ol|table)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TRAILING_SPACES = Regex("[ \\t]+\\n")
    private val MANY_NEWLINES = Regex("\\n{3,}")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0")

    /** HTML of a team reply as markdown text; plain text and markdown pass unchanged. */
    fun bubbleText(content: String): String {
        if (!TAG.containsMatchIn(content)) return content
        var text = ANCHOR.replace(content) { m ->
            val href = (m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }).trim()
            val label = decodeEntities(m.groupValues[5].replace(TAG, ""))
            val lower = href.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
                val safeLabel = label.replace("[", "\\[").replace("]", "\\]")
                val safeHref = href.replace(" ", "%20").replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E")
                "[$safeLabel]($safeHref)"
            } else {
                label
            }
        }
        text = BR.replace(text, "\n")
        text = LI_OPEN.replace(text, "- ")
        text = LI_CLOSE.replace(text, "\n")
        text = CELL_CLOSE.replace(text, " ")
        text = BLOCK_CLOSE.replace(text, "\n\n")
        text = LIST_TAG.replace(text, "\n")
        text = TAG.replace(text, "")
        text = decodeEntities(text)
        text = TRAILING_SPACES.replace(text, "\n")
        text = MANY_NEWLINES.replace(text, "\n\n")
        return text.trim()
    }

    fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
            body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
            else -> NAMED[body.lowercase()] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

    // ── Link cards ──────────────────────────────────────────────────────

    /** A link card under a reply: title and host (the host hidden when it equals the title). */
    data class LinkCard(val url: String, val title: String, val host: String?)

    private val NAMED_LINK = Regex("^\\[([^\\]]{1,120})]\\((https://[^\\s()<>]{1,2000})\\)$")
    private val BARE_LINK = Regex("^<?(https://[^\\s<>]{1,2000}?)>?[.,;:!?]*$")

    /** The text without its last line when that line is only an https link, and the card for it. */
    fun splitLinkCard(text: String): Pair<String, LinkCard?> {
        val lines = text.trimEnd().split('\n')
        val last = lines.lastOrNull()?.trim().orEmpty()
        if (last.isEmpty()) return text to null
        val decoded = decodeEntities(last)
        val card = NAMED_LINK.matchEntire(decoded)?.let { m -> cardOf(m.groupValues[2], m.groupValues[1].trim()) }
            ?: BARE_LINK.matchEntire(decoded)?.let { m -> cardOf(m.groupValues[1], null) }
            ?: return text to null
        val rest = MANY_NEWLINES.replace(lines.dropLast(1).joinToString("\n"), "\n\n").trim()
        return rest to card
    }

    private fun cardOf(url: String, name: String?): LinkCard? {
        val afterScheme = url.substring("https://".length)
        val hostPart = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (!hostPart.contains('.') || hostPart.isEmpty()) return null
        val host = hostPart.lowercase().removePrefix("www.")
        val title = name?.takeIf { it.isNotEmpty() } ?: run {
            val path = afterScheme.removePrefix(hostPart).substringBefore('?').substringBefore('#').trimEnd('/')
            hostPart.lowercase().removePrefix("www.") + path
        }
        return LinkCard(url, title, host.takeUnless { it == title })
    }

    // ── Previews ────────────────────────────────────────────────────────

    private val MD_IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val MARKS = Regex("[*_~`]")
    private val HEADING_OR_QUOTE = Regex("(^|\\n)\\s{0,3}(#{1,6}\\s+|>\\s?)")
    private val SPACES = Regex("\\s+")

    /** A message as one line of up to 80 characters, or null for no text. */
    fun preview(content: String?, max: Int = 80): String? {
        if (content.isNullOrBlank()) return null
        var text = bubbleText(content)
        text = MD_IMAGE.replace(text) { it.groupValues[1] }
        text = MD_LINK.replace(text) { it.groupValues[1] }
        text = HEADING_OR_QUOTE.replace(text, "$1")
        text = MARKS.replace(text, "")
        text = decodeEntities(text)
        text = SPACES.replace(text, " ").trim()
        if (text.isEmpty()) return null
        return if (text.length > max) text.substring(0, max) else text
    }

    /** The start of a quoted message: 200 characters, the 200th an ellipsis. */
    fun quotePreview(content: String?): String? {
        val text = content?.let { bubbleText(it).trim() }?.takeIf { it.isNotEmpty() } ?: return null
        return if (text.length > 200) text.substring(0, 199) + "…" else text
    }
}
