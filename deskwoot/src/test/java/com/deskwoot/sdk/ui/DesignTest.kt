package com.deskwoot.sdk.ui

import android.text.Spanned
import android.text.style.TabStopSpan
import com.deskwoot.sdk.ui.design.ArticleTray
import com.deskwoot.sdk.ui.text.BubblePart
import com.deskwoot.sdk.ui.text.MarkdownColors
import com.deskwoot.sdk.ui.text.MarkdownSpans
import com.deskwoot.sdk.ui.text.westernDigits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 3 designs behind the seam, and the details each one draws its own way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesignTest {
    @Test
    fun `each design answers what differs`() {
        assertSame(KlartextDesign, designFor("klartext"))
        assertSame(EmpfangDesign, designFor("empfang"))
        assertSame(DirektDesign, designFor("direkt"))
        assertSame("an unknown design is Klartext", KlartextDesign, designFor("something new"))

        // Klartext: the plain sub header everywhere, no sheets, no tab bar.
        assertEquals(HeaderStyle.PLAIN, KlartextDesign.headerStyle(Route.Thread(null)))
        assertFalse(KlartextDesign.isSheet(Route.Help))
        assertFalse(KlartextDesign.tabBar(Route.Start))
        assertEquals(ChoiceStyle.INDENTED_ROWS, KlartextDesign.choiceStyle)

        // Empfang: the chat header on the band, bars elsewhere, the tab bar on 4 views, pills.
        assertEquals(HeaderStyle.BAND, EmpfangDesign.headerStyle(Route.Thread("c1")))
        assertEquals(HeaderStyle.BAR, EmpfangDesign.headerStyle(Route.Conversations))
        assertEquals(listOf(true, true, true, true, false, false, false), listOf(Route.Start, Route.Help, Route.Conversations, Route.Collection("a", "A"), Route.Article("x"), Route.Thread(null), Route.Gate).map(EmpfangDesign::tabBar))
        assertEquals(ChoiceStyle.PILLS, EmpfangDesign.choiceStyle)

        // Direkt: the chat is the start; list, help, collection and article are sheets; the forms keep a full screen.
        assertTrue(DirektDesign.chatIsStart)
        assertFalse(DirektDesign.greetingAsBubble)
        assertEquals(listOf(true, true, true, true, false, false), listOf(Route.Help, Route.Conversations, Route.Collection("a", "A"), Route.Article("x"), Route.Gate, Route.Start).map(DirektDesign::isSheet))
        assertEquals(HeaderStyle.SHEET, DirektDesign.headerStyle(Route.Help))
        assertEquals(HeaderStyle.BAR, DirektDesign.headerStyle(Route.Gate))
        assertEquals(ChoiceStyle.CARDS, DirektDesign.choiceStyle)
        assertEquals(ComposerStyle.DIREKT, DirektDesign.composerStyle)
        assertFalse(DirektDesign.composerTopLine)
    }

    @Test
    fun `Direkt's tray looks up the last word of at least 4 letters`() {
        assertEquals("gluckert", ArticleTray.trayQuery("Meine Heizung gluckert"))
        assertEquals("parcel", ArticleTray.trayQuery("Where is my parcel? ok"))
        assertEquals("", ArticleTray.trayQuery("hi you"))
        assertEquals("e-mail", ArticleTray.trayQuery("my e-mail!"))
    }

    @Test
    fun `dates and times use Western digits in Arabic, as the web widget does`() {
        assertEquals("1:57 م", westernDigits("١:٥٧ م"))
        assertEquals("16 نوفمبر", westernDigits("١٦ نوفمبر"))
        assertEquals("12", westernDigits("۱۲"))
        assertEquals("9:14 PM", westernDigits("9:14 PM"))
    }

    @Test
    fun `a markdown table is a grid with one line per row and its columns on tab stops`() {
        val colors = MarkdownColors(text = 0xFF000000.toInt(), link = 0xFF0000FF.toInt(), muted = 0xFF777777.toInt(), stripe = 0xFFCCCCCC.toInt(), codeBackground = 0xFFEEEEEE.toInt())
        val parts = MarkdownSpans(colors, 1f, {}, { false }).render("| Plan | Price |\n|---|---|\n| Starter | $4 |\n| Business | $12 |")
        val text = (parts.single() as BubblePart.Text).text
        assertEquals("Plan\tPrice\nStarter\t$4\nBusiness\t$12", text.toString())
        val stops = (text as Spanned).getSpans(0, text.length, TabStopSpan::class.java)
        assertEquals("one stop for the second column", 1, stops.size)
        assertTrue("the second column starts after the widest first cell", stops.single().tabStop > 0)
    }
}
