package com.deskwoot.sdk.ui.text

import com.deskwoot.sdk.ui.text.Markdown.Block
import com.deskwoot.sdk.ui.text.Markdown.Inline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message comes from anyone: whatever its marks, parsing stays cheap and
 * never runs out of stack. Each hostile text is 20,000
 * characters, the most the server stores.
 */
class MarkdownLimitsTest {
    private val hostile = mapOf(
        "a heading with a long run of spaces" to "# a" + " ".repeat(19_990) + "b",
        "a rule that is not one" to "-" + " -".repeat(9_990) + " x",
        "a table delimiter that is not one" to "| a | b |\n|" + " - |".repeat(4_990) + " x",
        "quotes inside quotes" to ">".repeat(20_000) + " deep",
        "lists inside lists" to "- ".repeat(10_000) + "x",
        "italic marks that never close" to "*a ".repeat(6_666),
        "underscores that never close" to "_a ".repeat(6_666),
        "bold marks that never close" to "**a ".repeat(5_000),
        "a run of stars" to "*".repeat(20_000),
        "strikes that never close" to "~~a ".repeat(5_000),
        "brackets that never close" to "[".repeat(20_000),
        "image brackets that never close" to "![".repeat(10_000),
        "links inside link labels" to "[".repeat(10_000) + "x" + "](https://a.io)".repeat(600),
        "angle brackets without an end" to "<".repeat(20_000),
        "angle brackets with one end" to "<".repeat(19_999) + ">",
        "backtick runs of every length" to (1..190).joinToString(" x ") { "`".repeat(it) },
        "a link with closing parentheses" to "https://a.io/" + ")".repeat(19_980),
        "bare links back to back" to "https://a.io ".repeat(1_500),
        "mixed marks" to "*_~[<`".repeat(3_333),
    )

    @Test
    fun `hostile texts parse fast and keep the nesting shallow`() {
        val times = LinkedHashMap<String, Long>()
        for ((name, text) in hostile) {
            val start = System.nanoTime()
            val blocks = Markdown.parse(text)
            times[name] = (System.nanoTime() - start) / 1_000_000
            assertTrue(name, blockDepth(blocks) <= Markdown.MAX_BLOCK_DEPTH + 1)
            assertTrue(name, blocks.maxOfOrNull(::inlineDepthOf) ?: 0 <= Markdown.MAX_INLINE_DEPTH + 1)
            // The text is all still there (as text where its marks were not honoured).
            assertTrue(name, plainLength(blocks) > 0)
        }
        println("Markdown of 20,000 hostile characters, ms: $times")
        // Linear work is well under a millisecond per thousand characters; a generous bound keeps slow machines green.
        times.forEach { (name, ms) -> assertTrue("$name took $ms ms", ms < 1_500) }
        assertTrue("all together ${times.values.sum()} ms", times.values.sum() < 4_000)
    }

    @Test
    fun `quotes nest at most 8 deep, deeper marks read as text`() {
        val blocks = Markdown.parse(">".repeat(12) + " deep")
        var depth = 0
        var block: Block = blocks.single()
        while (block is Block.Quote) {
            depth++
            block = block.blocks.single()
        }
        assertEquals(Markdown.MAX_BLOCK_DEPTH, depth)
        assertEquals(">>>> deep", Markdown.plain((block as Block.Paragraph).inlines))
    }

    @Test
    fun `headings, rules and tables read as before`() {
        assertEquals(Block.Heading(2, listOf(Inline.Text("Title"))), Markdown.parse("## Title ##").single())
        assertEquals(Block.Heading(1, listOf(Inline.Text("C#"))), Markdown.parse("# C#").single())
        assertEquals(Block.Paragraph(listOf(Inline.Text("#hashtag"))), Markdown.parse("#hashtag").single())
        assertEquals(Block.Paragraph(listOf(Inline.Text("####### seven"))), Markdown.parse("####### seven").single())
        assertEquals(Block.Rule, Markdown.parse("* * *").single())
        assertEquals(Block.Rule, Markdown.parse("___").single())
        assertTrue(Markdown.parse("- - x").single() is Block.ListBlock)
        val table = Markdown.parse("| A | B |\n| :-- | --: |\n| 1 | 2 |").single() as Block.Table
        assertEquals(listOf("A", "B"), table.header.map { Markdown.plain(it) })
        // Emphasis and links still nest within the limits.
        assertEquals(
            listOf(Inline.Bold(listOf(Inline.Text("a "), Inline.Italic(listOf(Inline.Text("b"))), Inline.Text(" c")))),
            Markdown.parseInlines("**a *b* c**"),
        )
        assertEquals(listOf(Inline.Link("https://a.io/(x)", listOf(Inline.Text("https://a.io/(x)"))), Inline.Text(").")), Markdown.parseInlines("https://a.io/(x))."))
    }

    private fun blockDepth(blocks: List<Block>): Int = blocks.maxOfOrNull { block ->
        when (block) {
            is Block.Quote -> 1 + blockDepth(block.blocks)
            is Block.ListBlock -> 1 + (block.items.maxOfOrNull { blockDepth(it.blocks) } ?: 0)
            else -> 1
        }
    } ?: 0

    private fun inlineDepthOf(block: Block): Int = when (block) {
        is Block.Paragraph -> inlineDepth(block.inlines)
        is Block.Heading -> inlineDepth(block.inlines)
        is Block.Quote -> block.blocks.maxOfOrNull(::inlineDepthOf) ?: 0
        is Block.ListBlock -> block.items.flatMap { it.blocks }.maxOfOrNull(::inlineDepthOf) ?: 0
        is Block.Table -> (block.header + block.rows.flatten()).maxOfOrNull(::inlineDepth) ?: 0
        else -> 0
    }

    private fun inlineDepth(inlines: List<Inline>): Int = inlines.maxOfOrNull { inline ->
        when (inline) {
            is Inline.Bold -> 1 + inlineDepth(inline.children)
            is Inline.Italic -> 1 + inlineDepth(inline.children)
            is Inline.Strike -> 1 + inlineDepth(inline.children)
            is Inline.Link -> 1 + inlineDepth(inline.children)
            else -> 1
        }
    } ?: 0

    private fun plainLength(blocks: List<Block>): Int = blocks.sumOf { block ->
        when (block) {
            is Block.Paragraph -> Markdown.plain(block.inlines).length
            is Block.Heading -> Markdown.plain(block.inlines).length
            is Block.Quote -> plainLength(block.blocks)
            is Block.ListBlock -> block.items.sumOf { plainLength(it.blocks) }
            is Block.Table -> (block.header + block.rows.flatten()).sumOf { Markdown.plain(it).length }
            is Block.Code -> block.text.length
            Block.Rule -> 1
        }
    }
}
