package com.deskwoot.sdk.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.design.empfangAskCard
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What is filled with the brand gets the same edge in every look, by the
 * panel's background alone: the visitor's bubble in Klartext, Empfang and
 * Direkt, and Empfang's round send glyph on the start page and in the help.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrandEdgeTest {
    /** The edge of a shape as `#rrggbb`, or null when it has none. */
    private fun edgeOf(drawable: android.graphics.drawable.Drawable?): String? {
        val shape = drawable as GradientDrawable
        val state = GradientDrawable::class.java.getDeclaredField("mGradientState").apply { isAccessible = true }.get(shape)
        val width = state.javaClass.getDeclaredField("mStrokeWidth").apply { isAccessible = true }.getInt(state)
        if (width <= 0) return null
        val colors = state.javaClass.getDeclaredField("mStrokeColors").apply { isAccessible = true }.get(state) as ColorStateList
        return "#%06x".format(colors.defaultColor and 0xFFFFFF)
    }

    /** The edge of the visitor's bubble in a thread of [design] with [brand]. */
    private fun TestScope.bubbleEdge(design: String, brand: String): String? {
        val s = ScreenHarness(this, baseConfig("widgetDesign" to design, "brandColor" to brand), record = VisitorRecord(bearer = "wb_known"))
        s.h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0)) }
        s.h.http.on("GET /api/widget/messages") { ok(pollAnswer(row("v1", type = "INCOMING", content = "Where is my parcel?", at = 0))) }
        s.open(DeskwootActivity.Entry.Open("c1"))
        val text = s.all(R.id.dw_bubble_text).first { (it as TextView).text.toString() == "Where is my parcel?" }
        val bubble = text.parent as View
        assertEquals(R.id.dw_bubble, bubble.id)
        val edge = edgeOf(bubble.background)
        s.close()
        return edge
    }

    @Test
    fun `the visitor's bubble has the same edge in every look, Direkt's tinted canvas included`() = runTest {
        for (design in listOf("direkt", "klartext", "empfang")) {
            assertEquals(design, "#98862b", bubbleEdge(design, "#fde047"))
            assertEquals(design, "#8c8c8c", bubbleEdge(design, "#ffffff"))
            // 3.19:1 on the panel: no edge, also where the fill sits on bg-2.
            assertEquals(design, null, bubbleEdge(design, "#d97706"))
        }
    }

    /** Empfang's start page with [brand]: the edge of the round glyph in its "Write to us" card. */
    private fun TestScope.startGlyphEdge(brand: String): String? {
        val s = ScreenHarness(this, baseConfig("widgetDesign" to "empfang", "brandColor" to brand)).open()
        val card = s.find<ViewGroup>(R.id.dw_write_to_us)
        val glyph = (0 until card.childCount).map(card::getChildAt).filterIsInstance<ImageView>().single()
        val edge = edgeOf(glyph.background)
        s.close()
        return edge
    }

    /** Empfang's ask card in the help with [brand]: the edge of its round glyph. */
    private fun TestScope.helpGlyphEdge(brand: String): String? {
        val s = ScreenHarness(this, baseConfig("widgetDesign" to "empfang", "brandColor" to brand)).open()
        val card = empfangAskCard(s.activity, s.ui()) { null }
        val edge = edgeOf(card.findViewById<View>(R.id.dw_ask_button).background)
        s.close()
        return edge
    }

    @Test
    fun `Empfang's round send glyph keeps its shape on the white card for a white or yellow brand`() = runTest {
        assertEquals("#8c8c8c", startGlyphEdge("#ffffff"))
        assertEquals("#98862b", startGlyphEdge("#fde047"))
        assertEquals(null, startGlyphEdge("#2563eb"))
        assertEquals("#8c8c8c", helpGlyphEdge("#ffffff"))
        assertEquals("#98862b", helpGlyphEdge("#fde047"))
        assertEquals(null, helpGlyphEdge("#2563eb"))
    }
}
