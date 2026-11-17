package com.deskwoot.sdk.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The edge of a brand fill without contrast: the web's rule with its 5 vectors.
 * Whether a fill gets an edge depends on the panel's background alone (#ffffff in light, #14141f in
 * dark), in every look; the edge reads 3:1 on both panel surfaces, which are the web's own: #ffffff
 * and #f5f5f9 in light, #14141f and #1c1c2a in dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeColorTest {
    private fun fill(brand: String, dark: Boolean) = Palette.brandPalette(brand, dark).fill.lowercase()

    private fun edge(brand: String, dark: Boolean): String? =
        UiColors(brand, dark).fillEdge?.let { "#%06x".format(it and 0xFFFFFF) }

    @Test
    fun `the 5 vectors of the web widget`() {
        assertTrue(UiColors.needsEdge(fill("#ffffff", false), false))
        assertEquals("#8c8c8c", UiColors.edgeColor(fill("#ffffff", false), false))
        assertTrue(UiColors.needsEdge(fill("#fde047", false), false))
        assertEquals("#98862b", UiColors.edgeColor(fill("#fde047", false), false))
        assertEquals("#141414", fill("#000000", true))
        assertTrue(UiColors.needsEdge(fill("#000000", true), true))
        assertEquals("#727272", UiColors.edgeColor(fill("#000000", true), true))
        assertTrue(UiColors.needsEdge(fill("#111827", true), true))
        assertEquals("#666a74", UiColors.edgeColor(fill("#111827", true), true))
        assertFalse("5.17:1 against white: no edge", UiColors.needsEdge(fill("#2563eb", false), false))

        // The colors a screen paints with: the same vectors.
        assertEquals("#8c8c8c", edge("#ffffff", false))
        assertEquals("#98862b", edge("#fde047", false))
        assertEquals("#727272", edge("#000000", true))
        assertEquals("#666a74", edge("#111827", true))
        assertNull(edge("#2563eb", false))
        assertNull(edge("#2563eb", true))
        assertNull(edge("#ffffff", true))
        for ((brand, dark) in listOf("#ffffff" to false, "#fde047" to false, "#000000" to true, "#111827" to true)) {
            val shown = checkNotNull(edge(brand, dark))
            val surfaces = if (dark) listOf("#14141f", "#1c1c2a") else listOf("#ffffff", "#f5f5f9")
            for (surface in surfaces) assertTrue("$shown on $surface", Palette.contrastRatio(shown, surface) >= 3.0)
        }
    }

    @Test
    fun `a fill that reads 3 to 1 on the panel's background gets no edge, also where it sits on bg-2`() {
        // #16a34a: 3.30 on white, 3.03 on bg-2. #22c55e: 2.28 and 2.10: an edge.
        assertFalse(UiColors.needsEdge("#16a34a", false))
        assertTrue(UiColors.needsEdge("#22c55e", false))
        // Amber #d97706: 3.19 on white, 2.93 on bg-2 (Klartext's composer bar): no edge, as on the web.
        assertFalse(UiColors.needsEdge("#d97706", false))
        assertNull(edge("#d97706", false))
        // In dark the panel is #14141f: a slate brand's fill #59636f has 2.99 there and gets its edge.
        assertTrue(UiColors.needsEdge(fill("#4b5563", true), true))
        assertEquals("#616b76", edge("#4b5563", true))
    }
}
