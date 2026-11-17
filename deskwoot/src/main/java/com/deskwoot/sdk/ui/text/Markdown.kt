package com.deskwoot.sdk.ui.text

/**
 * The markdown of a reply (GitHub flavour) as a small syntax
 * tree: paragraphs, headings, lists, task lists, block quotes, code blocks,
 * tables and rules; bold, italic, strikethrough, inline code, links,
 * autolinks and images. No raw HTML. A single line break inside a
 * paragraph stays a line break (the web joins it with a space; a visitor's
 * or agent's line breaks are kept here).
 *
 * Pure Kotlin; [MarkdownSpans] draws the tree.
 *
 * A message comes from anyone, so parsing stays linear in its length:
 * quotes and lists nest at most [MAX_BLOCK_DEPTH] deep and
 * emphasis and link labels [MAX_INLINE_DEPTH] deep (deeper marks read as
 * text), no pattern can backtrack without end, and the inline parser has a
 * budget of steps after which the rest of its marks read as text.
 */
internal object Markdown {
    /** Quotes and lists inside each other; deeper lines are plain paragraphs. */
    const val MAX_BLOCK_DEPTH: Int = 8

    /** Emphasis and link labels inside each other; deeper marks are text. */
    const val MAX_INLINE_DEPTH: Int = 12

    sealed class Block {
        data class Paragraph(val inlines: List<Inline>) : Block()

        data class Heading(val level: Int, val inlines: List<Inline>) : Block()

        data class ListBlock(val ordered: Boolean, val start: Int, val items: List<Item>) : Block()

        data class Quote(val blocks: List<Block>) : Block()

        data class Code(val text: String) : Block()

        data class Table(val header: List<List<Inline>>, val rows: List<List<List<Inline>>>) : Block()

        object Rule : Block()
    }

    /** One list item; [checked] is set for a task list item (`- [ ]`, `- [x]`). */
    data class Item(val blocks: List<Block>, val checked: Boolean?)

    sealed class Inline {
        data class Text(val text: String) : Inline()

        data class Bold(val children: List<Inline>) : Inline()

        data class Italic(val children: List<Inline>) : Inline()

        data class Strike(val children: List<Inline>) : Inline()

        data class Code(val text: String) : Inline()

        data class Link(val url: String, val children: List<Inline>) : Inline()

        data class Image(val url: String, val alt: String) : Inline()

        object Break : Inline()
    }

    fun parse(source: String): List<Block> = parseBlocks(source.replace("\r\n", "\n").replace('\t', ' ').split('\n'), 0)

    // ── Blocks ───────────────────────────────────────────────────────────

    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val QUOTE = Regex("^ {0,3}> ?(.*)$")
    private val BULLET = Regex("^( {0,3})([-*+])\\s+(.*)$")
    private val ORDERED = Regex("^( {0,3})(\\d{1,9})([.)])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")

    private fun parseBlocks(lines: List<String>, depth: Int): List<Block> {
        if (depth >= MAX_BLOCK_DEPTH) return paragraphs(lines)
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val marks = fence.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marks)) {
                    body += lines[i]
                    i++
                }
                i++ // the closing fence, or the end
                out += Block.Code(body.joinToString("\n"))
                continue
            }
            val heading = heading(line)
            if (heading != null) {
                out += Block.Heading(heading.first, parseInlines(heading.second))
                i++
                continue
            }
            if (isRule(line)) {
                out += Block.Rule
                i++
                continue
            }
            if (QUOTE.matches(line)) {
                val inner = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val q = QUOTE.matchEntire(lines[i])
                    inner += q?.groupValues?.get(1) ?: lines[i]
                    i++
                }
                out += Block.Quote(parseBlocks(inner, depth + 1))
                continue
            }
            if (BULLET.matches(line) || ORDERED.matches(line)) {
                val (block, next) = parseList(lines, i, depth)
                out += block
                i = next
                continue
            }
            if (i + 1 < lines.size && line.contains('|') && isTableDelimiter(lines[i + 1])) {
                val header = cells(line)
                val rows = ArrayList<List<List<Inline>>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    rows += cells(lines[i])
                    i++
                }
                out += Block.Table(header, rows)
                continue
            }
            // A paragraph: lines up to a blank line or the start of another block.
            val para = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && (para.isEmpty() || !startsBlock(lines[i]))) {
                para += lines[i].trim()
                i++
            }
            out += Block.Paragraph(parseInlines(para.joinToString("\n")))
        }
        return out
    }

    private fun startsBlock(line: String): Boolean =
        FENCE.matches(line) || heading(line) != null || QUOTE.matches(line) || BULLET.matches(line) ||
            ORDERED.matches(line) || isRule(line)

    /** Past the deepest nesting: every run of lines up to a blank line is a paragraph of text. */
    private fun paragraphs(lines: List<String>): List<Block> {
        val out = ArrayList<Block>()
        val para = ArrayList<String>()
        for (line in lines + "") {
            if (line.isNotBlank()) {
                para += line.trim()
            } else if (para.isNotEmpty()) {
                out += Block.Paragraph(parseInlines(para.joinToString("\n")))
                para.clear()
            }
        }
        return out
    }

    /** Up to 3 spaces of indent, then what follows them; null for 4 or more. */
    private fun afterIndent(line: String): Int? {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        return if (i <= 3) i else null
    }

    /**
     * `# Title` (1 to 6 marks, then a space or the end): the level and the
     * text without a closing run of marks. Scanned by hand: a pattern for it
     * backtracks for ever on a long run of spaces.
     */
    private fun heading(line: String): Pair<Int, String>? {
        val start = afterIndent(line) ?: return null
        var i = start
        while (i < line.length && line[i] == '#') i++
        val level = i - start
        if (level !in 1..6) return null
        if (i < line.length && !line[i].isWhitespace()) return null
        var text = line.substring(i).trim()
        // A closing run of marks goes when a space stands before it (or it is all there is).
        val closing = text.trimEnd('#')
        if (closing.isEmpty() || closing.last().isWhitespace()) text = closing.trimEnd()
        return level to text
    }

    /** `---`, `***`, `___`: 3 or more of one mark, spaces between them allowed. */
    private fun isRule(line: String): Boolean {
        val start = afterIndent(line) ?: return false
        val mark = line.getOrNull(start)?.takeIf { it == '-' || it == '*' || it == '_' } ?: return false
        var count = 0
        for (i in start until line.length) {
            val c = line[i]
            if (c == mark) count++ else if (!c.isWhitespace()) return false
        }
        return count >= 3
    }

    /** The line under a table's header: cells of dashes with optional colons, split by pipes. */
    private fun isTableDelimiter(line: String): Boolean {
        var text = line.trim()
        if (!text.contains('-')) return false
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|")) text = text.dropLast(1)
        return text.split('|').all { cell ->
            val c = cell.trim().removePrefix(":").removeSuffix(":")
            c.isNotEmpty() && c.all { it == '-' }
        }
    }

    private fun parseList(lines: List<String>, from: Int, depth: Int): Pair<Block, Int> {
        val first = lines[from]
        val ordered = ORDERED.matches(first)
        val start = ORDERED.matchEntire(first)?.groupValues?.get(2)?.toIntOrNull() ?: 1
        val baseIndent = first.length - first.trimStart().length
        val items = ArrayList<Item>()
        var i = from
        while (i < lines.size) {
            val line = lines[i]
            val indent = line.length - line.trimStart().length
            val bullet = BULLET.matchEntire(line)
            val number = ORDERED.matchEntire(line)
            val marker = if (ordered) number else bullet
            if (marker == null || indent > baseIndent + 1) break
            var text = if (ordered) marker.groupValues[4] else marker.groupValues[3]
            var checked: Boolean? = null
            TASK.matchEntire(text)?.let { t ->
                checked = t.groupValues[1] != " "
                text = t.groupValues[2]
            }
            val body = arrayListOf(text)
            i++
            // Continuation and nested lines: indented deeper than the marker, until a blank line before an unindented line.
            while (i < lines.size) {
                val next = lines[i]
                val nextIndent = next.length - next.trimStart().length
                if (next.isBlank()) {
                    val after = lines.getOrNull(i + 1)
                    if (after != null && after.isNotBlank() && (after.length - after.trimStart().length) > baseIndent + 1) {
                        body += ""
                        i++
                        continue
                    }
                    break
                }
                if (nextIndent > baseIndent + 1) {
                    body += next.substring(minOf(nextIndent, baseIndent + 2))
                    i++
                    continue
                }
                if (BULLET.matches(next) || ORDERED.matches(next) || startsBlock(next)) break
                // A lazy continuation of the item's paragraph.
                body += next.trim()
                i++
            }
            items += Item(parseBlocks(body, depth + 1), checked)
            // A blank line between items keeps the list going.
            if (i < lines.size && lines[i].isBlank()) {
                val after = lines.getOrNull(i + 1)
                val sameKind = after != null && (if (ordered) ORDERED.matches(after) else BULLET.matches(after))
                if (sameKind) i++
            }
        }
        return Block.ListBlock(ordered, start, items) to i
    }

    private fun cells(line: String): List<List<Inline>> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
        return text.split(Regex("(?<!\\\\)\\|")).map { parseInlines(it.trim().replace("\\|", "|")) }
    }

    // ── Inlines ──────────────────────────────────────────────────────────

    private val URL_SCHEMES = listOf("http://", "https://", "mailto:")
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>\""

    /** The longest link label and target looked for; anything longer is text. */
    private const val MAX_LABEL = 1_000
    private const val MAX_TARGET = 2_048

    fun parseInlines(text: String): List<Inline> = InlineParser(text, 0, Budget(text.length * 8L + 4_096)).parse()

    /** A link target a visitor may open: http, https or mailto (react-markdown's safe protocols that matter here). */
    fun safeUrl(url: String): String? {
        val trimmed = url.trim().removeSurrounding("<", ">")
        val lower = trimmed.lowercase()
        return if (URL_SCHEMES.any { lower.startsWith(it) }) trimmed else null
    }

    /** Steps the inline parser of one text may take, link labels included; once spent, marks are text. */
    private class Budget(var left: Long) {
        fun spend(steps: Int) {
            left -= steps
        }

        val spent: Boolean get() = left <= 0
    }

    private class InlineParser(private val s: String, depth: Int, private val budget: Budget) {
        private var pos = 0

        /** How deep the emphasis being parsed is nested (link labels count as a level). */
        private var level = depth

        /** Per length of a backtick run, a position after which no closing run follows: none follows a later one either. */
        private val unclosedTicks = HashMap<Int, Int>()

        /** The next `>` at or after a position, remembered, so a run of `<` scans once. */
        private var gtFrom = -1
        private var gtAt = -1

        fun parse(): List<Inline> = parseUntil(null)

        /** Whether emphasis and links may open here: not too deep, and the budget lasts. */
        private val mayNest: Boolean get() = level < MAX_INLINE_DEPTH && !budget.spent

        /** Parses until [closer] (a delimiter string) or the end; the closer is consumed. */
        private fun parseUntil(closer: String?): List<Inline> {
            val out = ArrayList<Inline>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    out += Inline.Text(text.toString())
                    text.clear()
                }
            }
            while (pos < s.length) {
                budget.spend(1)
                if (closer != null && s.startsWith(closer, pos) && closes(closer)) {
                    pos += closer.length
                    flush()
                    return out
                }
                val c = s[pos]
                when {
                    c == '\\' && pos + 1 < s.length && s[pos + 1] in ESCAPABLE -> {
                        text.append(s[pos + 1])
                        pos += 2
                    }
                    c == '\n' -> {
                        flush()
                        out += Inline.Break
                        pos++
                    }
                    c == '`' -> {
                        val run = countRun('`', Int.MAX_VALUE)
                        val end = if ((unclosedTicks[run] ?: Int.MAX_VALUE) <= pos) -1 else s.indexOf("`".repeat(run), pos + run)
                        if (end < 0) {
                            unclosedTicks[run] = minOf(pos, unclosedTicks[run] ?: Int.MAX_VALUE)
                            budget.spend(s.length - pos)
                            text.append("`".repeat(run))
                            pos += run
                        } else {
                            flush()
                            out += Inline.Code(s.substring(pos + run, end).trim())
                            pos = end + run
                        }
                    }
                    c == '!' && pos + 1 < s.length && s[pos + 1] == '[' -> {
                        val link = if (mayNest) linkAt(pos + 1) else null
                        if (link != null) {
                            flush()
                            out += Inline.Image(link.second, s.substring(pos + 2, link.first))
                            pos = link.third
                        } else {
                            text.append(c)
                            pos++
                        }
                    }
                    c == '[' -> {
                        val link = if (mayNest) linkAt(pos) else null
                        if (link != null) {
                            flush()
                            val label = s.substring(pos + 1, link.first)
                            val url = safeUrl(link.second)
                            val children = InlineParser(label, level + 1, budget).parse()
                            out += if (url != null) Inline.Link(url, children) else Inline.Text(plain(children))
                            pos = link.third
                        } else {
                            text.append(c)
                            pos++
                        }
                    }
                    c == '<' -> {
                        val end = nextGt(pos)
                        val url = if (end > pos + 1 && end - pos <= MAX_TARGET) {
                            val inner = s.substring(pos + 1, end)
                            if (!inner.contains(' ')) safeUrl(inner) else null
                        } else {
                            null
                        }
                        if (url != null) {
                            flush()
                            out += Inline.Link(url, listOf(Inline.Text(url.removePrefix("mailto:"))))
                            pos = end + 1
                        } else {
                            text.append(c)
                            pos++
                        }
                    }
                    (c == 'h' || c == 'H') && atWordStart() && bareUrlEnd() > pos -> {
                        val url = s.substring(pos, trimUrlEnd(bareUrlEnd()))
                        flush()
                        out += Inline.Link(url, listOf(Inline.Text(url)))
                        pos += url.length
                    }
                    c == '~' && s.startsWith("~~", pos) -> emphasis("~~", text, out, ::flush) { Inline.Strike(it) }
                    (c == '*' || c == '_') && countRun(c, 2) >= 2 -> {
                        val delim = "$c$c"
                        emphasis(delim, text, out, ::flush) { Inline.Bold(it) }
                    }
                    c == '*' || c == '_' -> emphasis(c.toString(), text, out, ::flush) { Inline.Italic(it) }
                    else -> {
                        text.append(c)
                        pos++
                    }
                }
            }
            flush()
            if (closer != null) throw Unclosed()
            return out
        }

        private class Unclosed : RuntimeException() {
            override fun fillInStackTrace(): Throwable = this
        }

        /** Opens an emphasis at [pos] when a matching closer follows; else the marks are text. */
        private inline fun emphasis(
            delim: String,
            text: StringBuilder,
            out: MutableList<Inline>,
            flush: () -> Unit,
            make: (List<Inline>) -> Inline,
        ) {
            if (!mayNest || !canOpen(delim)) {
                text.append(delim)
                pos += delim.length
                return
            }
            val saved = pos
            pos += delim.length
            level++
            val children = try {
                parseUntil(delim)
            } catch (_: Unclosed) {
                null
            } finally {
                level--
            }
            if (children.isNullOrEmpty()) {
                pos = saved
                text.append(delim)
                pos += delim.length
                return
            }
            flush()
            out += make(children)
        }

        private fun canOpen(delim: String): Boolean {
            val after = s.getOrNull(pos + delim.length) ?: return false
            if (after.isWhitespace()) return false
            // An underscore inside a word is a letter (snake_case), never emphasis.
            if (delim[0] == '_') {
                val before = s.getOrNull(pos - 1)
                if (before != null && before.isLetterOrDigit()) return false
            }
            return true
        }

        private fun closes(delim: String): Boolean {
            val before = s.getOrNull(pos - 1) ?: return false
            if (before.isWhitespace()) return false
            if (delim[0] == '_') {
                val after = s.getOrNull(pos + delim.length)
                if (after != null && after.isLetterOrDigit()) return false
            }
            // A single mark must not eat half of a double one.
            if (delim.length == 1 && s.getOrNull(pos + 1) == delim[0]) return false
            return true
        }

        /** How many [c] follow from [pos], counted up to [max]. */
        private fun countRun(c: Char, max: Int): Int {
            var n = 0
            while (n < max && pos + n < s.length && s[pos + n] == c) n++
            return n
        }

        private fun atWordStart(): Boolean {
            val before = s.getOrNull(pos - 1) ?: return true
            return !before.isLetterOrDigit()
        }

        /** The end of a bare `http://` or `https://` link at [pos] (up to a space or `<`), or [pos] when there is none. */
        private fun bareUrlEnd(): Int {
            val scheme = when {
                s.regionMatches(pos, "https://", 0, 8, ignoreCase = true) -> 8
                s.regionMatches(pos, "http://", 0, 7, ignoreCase = true) -> 7
                else -> return pos
            }
            var end = pos + scheme
            while (end < s.length && !s[end].isWhitespace() && s[end] != '<') end++
            return if (end > pos + scheme) end else pos
        }

        /** Trailing punctuation and an unbalanced closing parenthesis stay outside a bare link (GFM autolinks). */
        private fun trimUrlEnd(urlEnd: Int): Int {
            var opens = 0
            var closes = 0
            for (k in pos until urlEnd) {
                if (s[k] == '(') opens++ else if (s[k] == ')') closes++
            }
            var end = urlEnd
            while (end > pos) {
                val last = s[end - 1]
                when {
                    last in ".,;:!?*_~'\"" -> end--
                    last == ')' && closes > opens -> {
                        end--
                        closes--
                    }
                    else -> break
                }
            }
            return end
        }

        private fun nextGt(from: Int): Int {
            if (gtFrom < 0 || from < gtFrom || (gtAt in 0 until from)) {
                gtAt = s.indexOf('>', from)
                budget.spend((if (gtAt < 0) s.length else gtAt) - from)
            }
            gtFrom = from
            return gtAt
        }

        /** `[label](url)` at [open]: the label's end index, the url and the index after the link. */
        private fun linkAt(open: Int): Triple<Int, String, Int>? {
            var brackets = 0
            var i = open
            val labelLimit = minOf(s.length, open + MAX_LABEL)
            while (i < labelLimit) {
                when (s[i]) {
                    '\\' -> i++
                    '[' -> brackets++
                    ']' -> {
                        brackets--
                        if (brackets == 0) break
                    }
                    '\n' -> if (s.getOrNull(i + 1) == '\n') return null
                }
                i++
            }
            budget.spend(i - open)
            if (i >= labelLimit || s.getOrNull(i + 1) != '(') return null
            val labelEnd = i
            var j = i + 2
            var parens = 1
            val targetLimit = minOf(s.length, j + MAX_TARGET)
            while (j < targetLimit) {
                when (s[j]) {
                    '(' -> parens++
                    ')' -> {
                        parens--
                        if (parens == 0) break
                    }
                    ' ', '\n' -> {
                        // A title after the url ("url "title"") is dropped.
                    }
                }
                j++
            }
            budget.spend(j - labelEnd)
            if (j >= targetLimit) return null
            val target = s.substring(i + 2, j).trim().substringBefore(' ')
            return Triple(labelEnd, target, j + 1)
        }
    }

    /** The plain text of inlines (link labels, alt text, previews). */
    fun plain(inlines: List<Inline>): String = buildString {
        for (inline in inlines) {
            when (inline) {
                is Inline.Text -> append(inline.text)
                is Inline.Bold -> append(plain(inline.children))
                is Inline.Italic -> append(plain(inline.children))
                is Inline.Strike -> append(plain(inline.children))
                is Inline.Code -> append(inline.text)
                is Inline.Link -> append(plain(inline.children))
                is Inline.Image -> append(inline.alt)
                Inline.Break -> append('\n')
            }
        }
    }
}
