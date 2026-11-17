package com.deskwoot.sdk.ui.text

import com.deskwoot.sdk.ui.text.Markdown.Block
import com.deskwoot.sdk.ui.text.Markdown.Inline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** bubbleText, link cards, previews and the markdown tree. */
class MessageTextTest {
    @Test
    fun `team HTML becomes markdown text`() {
        assertEquals(
            "Hello [our shop](https://shop.example.com/a%20b)\nsecond line\n\n- one\n- two",
            MessageText.bubbleText("<p>Hello <a href=\"https://shop.example.com/a b\">our shop</a><br>second line</p><ul><li>one</li><li>two</li></ul>"),
        )
        // A link that is not http, https or mailto keeps only its label.
        assertEquals("click", MessageText.bubbleText("<a href=\"javascript:alert(1)\">click</a>"))
        assertEquals("Fish & chips <3", MessageText.bubbleText("<p>Fish &amp; chips &lt;3</p>"))
        // Plain text and markdown pass unchanged, entities included.
        assertEquals("**bold** & co", MessageText.bubbleText("**bold** & co"))
    }

    @Test
    fun `the last line that is only an https link becomes a card`() {
        val (rest, card) = MessageText.splitLinkCard("Here are our prices.\n[Plans and prices](https://www.deskwoot.com/pricing)")
        assertEquals("Here are our prices.", rest)
        assertEquals(MessageText.LinkCard("https://www.deskwoot.com/pricing", "Plans and prices", "deskwoot.com"), card)

        val (onlyLink, bare) = MessageText.splitLinkCard("https://deskwoot.com/docs/widget.")
        assertEquals("", onlyLink)
        assertEquals("deskwoot.com/docs/widget", bare?.title)
        // The host shows unless it equals the whole title.
        assertEquals("deskwoot.com", bare?.host)
        assertNull(MessageText.splitLinkCard("[deskwoot.com](https://deskwoot.com)").second?.host)
        // http, a host without a dot and a link inside a sentence stay text.
        assertNull(MessageText.splitLinkCard("http://deskwoot.com").second)
        assertNull(MessageText.splitLinkCard("https://localhost/x").second)
        assertNull(MessageText.splitLinkCard("See https://deskwoot.com for more").second)
    }

    @Test
    fun `previews are one line of plain text`() {
        assertEquals("Here are the options: Standard Express", MessageText.preview("Here are the **options**:\n\n1. Standard\n2. Express".replace("1. ", "").replace("2. ", "")))
        assertEquals("Read this guide now", MessageText.preview("# Read [this guide](https://x.io)\n> now"))
        assertEquals(80, MessageText.preview("a".repeat(200))?.length)
        assertNull(MessageText.preview("  "))
        assertEquals("a".repeat(199) + "…", MessageText.quotePreview("a".repeat(300)))
    }

    @Test
    fun `markdown blocks`() {
        val blocks = Markdown.parse("# Title\n\nFirst line\nsecond line\n\n- one\n- **two**\n\n1. a\n2. b\n\n> quoted\n\n```\ncode()\n```\n\n- [x] done\n- [ ] open\n\n---\n\n| A | B |\n|---|---|\n| 1 | 2 |")
        assertEquals(Block.Heading(1, listOf(Inline.Text("Title"))), blocks[0])
        assertEquals(Block.Paragraph(listOf(Inline.Text("First line"), Inline.Break, Inline.Text("second line"))), blocks[1])
        val bullets = blocks[2] as Block.ListBlock
        assertEquals(false, bullets.ordered)
        assertEquals(Block.Paragraph(listOf(Inline.Bold(listOf(Inline.Text("two"))))), bullets.items[1].blocks.single())
        val numbers = blocks[3] as Block.ListBlock
        assertEquals(true, numbers.ordered)
        assertEquals(2, numbers.items.size)
        assertEquals(Block.Quote(listOf(Block.Paragraph(listOf(Inline.Text("quoted"))))), blocks[4])
        assertEquals(Block.Code("code()"), blocks[5])
        val tasks = blocks[6] as Block.ListBlock
        assertEquals(listOf(true, false), tasks.items.map { it.checked })
        assertEquals(Block.Rule, blocks[7])
        val table = blocks[8] as Block.Table
        assertEquals(listOf("A", "B"), table.header.map { Markdown.plain(it) })
        assertEquals(listOf("1", "2"), table.rows.single().map { Markdown.plain(it) })
    }

    @Test
    fun `markdown inlines`() {
        assertEquals(
            listOf(
                Inline.Bold(listOf(Inline.Text("bold"))), Inline.Text(" "), Inline.Italic(listOf(Inline.Text("it"))), Inline.Text(" "),
                Inline.Strike(listOf(Inline.Text("gone"))), Inline.Text(" "), Inline.Code("x = 1"),
            ),
            Markdown.parseInlines("**bold** *it* ~~gone~~ `x = 1`"),
        )
        assertEquals(listOf(Inline.Link("https://deskwoot.com", listOf(Inline.Text("site")))), Markdown.parseInlines("[site](https://deskwoot.com)"))
        // An unsafe protocol keeps the label as text.
        assertEquals(listOf(Inline.Text("x")), Markdown.parseInlines("[x](javascript:alert(1))"))
        // Bare links lose trailing punctuation; snake_case stays text.
        assertEquals(
            listOf(Inline.Text("See "), Inline.Link("https://deskwoot.com/a", listOf(Inline.Text("https://deskwoot.com/a"))), Inline.Text(".")),
            Markdown.parseInlines("See https://deskwoot.com/a."),
        )
        assertEquals(listOf(Inline.Text("my_var_name")), Markdown.parseInlines("my_var_name"))
        // Unclosed marks are text.
        assertEquals("2 * 3 = 6", Markdown.plain(Markdown.parseInlines("2 * 3 = 6")))
        assertEquals("**open", Markdown.plain(Markdown.parseInlines("**open")))
        assertEquals(listOf(Inline.Image("/api/widget/files/a1", "Receipt")), Markdown.parseInlines("![Receipt](/api/widget/files/a1)"))
        assertTrue(Markdown.parseInlines("a\\*b").single() == Inline.Text("a*b"))
    }
}
