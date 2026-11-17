package com.deskwoot.sdk.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The palette is the web messenger's to the digit: the web's own test vectors. */
class PaletteTest {
    // input, theme, brand, deep, soft, fill, onBrand, onBrandMuted, band, onBand, text, contrast on/fill
    private val vectors = """
        #2563eb | light | #2563eb | #1e51c1 | rgba(37,99,235,0.12) | #2563eb | #ffffff | rgba(255,255,255,0.8) | #2563eb | #ffffff | #2563eb | 5.17
        #2563eb | dark | #2563eb | #1e51c1 | rgba(37,99,235,0.12) | #366fed | #ffffff | rgba(255,255,255,0.8) | #1e4aaf | #ffffff | #5182ef | 4.51
        #049593 | light | #049593 | #037a79 | rgba(4,149,147,0.12) | #049593 | #000000 | rgba(0,0,0,0.8) | #049593 | #000000 | #037776 | 5.73
        #049593 | dark | #049593 | #037a79 | rgba(4,149,147,0.12) | #189d9c | #000000 | rgba(0,0,0,0.8) | #066e6f | #ffffff | #049593 | 6.34
        #00f080 | light | #00f080 | #00c569 | rgba(0,240,128,0.12) | #00f080 | #000000 | rgba(0,0,0,0.8) | #00f080 | #000000 | #007840 | 13.78
        #00f080 | dark | #00f080 | #00c569 | rgba(0,240,128,0.12) | #14f18a | #000000 | rgba(0,0,0,0.8) | #03b062 | #000000 | #00f080 | 13.98
        #111827 | light | #111827 | #0e1420 | rgba(17,24,39,0.12) | #111827 | #ffffff | rgba(255,255,255,0.8) | #111827 | #ffffff | #111827 | 17.74
        #111827 | dark | #111827 | #0e1420 | rgba(17,24,39,0.12) | #242a38 | #ffffff | rgba(255,255,255,0.8) | #0f1422 | #ffffff | #888c93 | 14.35
        #ef4444 | light | #ef4444 | #c43838 | rgba(239,68,68,0.12) | #ef4444 | #000000 | rgba(0,0,0,0.8) | #ef4444 | #000000 | #cb3a3a | 5.58
        #ef4444 | dark | #ef4444 | #c43838 | rgba(239,68,68,0.12) | #f05353 | #000000 | rgba(0,0,0,0.8) | #af3437 | #ffffff | #f04d4d | 6.07
        #f97316 | light | #f97316 | #cc5e12 | rgba(249,115,22,0.12) | #f97316 | #000000 | rgba(0,0,0,0.8) | #f97316 | #000000 | #ae510f | 7.49
        #f97316 | dark | #f97316 | #cc5e12 | rgba(249,115,22,0.12) | #f97e29 | #000000 | rgba(0,0,0,0.8) | #b65615 | #ffffff | #f97316 | 8.04
        #10b981 | light | #10b981 | #0d986a | rgba(16,185,129,0.12) | #10b981 | #000000 | rgba(0,0,0,0.8) | #10b981 | #000000 | #0a7854 | 8.28
        #10b981 | dark | #10b981 | #0d986a | rgba(16,185,129,0.12) | #23bf8b | #000000 | rgba(0,0,0,0.8) | #0f8862 | #000000 | #10b981 | 8.90
        #fde047 | light | #fde047 | #cfb83a | rgba(253,224,71,0.12) | #fde047 | #000000 | rgba(0,0,0,0.8) | #fde047 | #000000 | #7f7024 | 15.93
        #fde047 | dark | #fde047 | #cfb83a | rgba(253,224,71,0.12) | #fde256 | #000000 | rgba(0,0,0,0.8) | #b9a439 | #000000 | #fde047 | 16.19
        #3f3be8 | light | #3f3be8 | #3430be | rgba(63,59,232,0.12) | #3f3be8 | #ffffff | rgba(255,255,255,0.8) | #3f3be8 | #ffffff | #3f3be8 | 6.99
        #3f3be8 | dark | #3f3be8 | #3430be | rgba(63,59,232,0.12) | #4e4bea | #ffffff | rgba(255,255,255,0.8) | #302ead | #ffffff | #7976ef | 5.97
        #808080 | light | #808080 | #696969 | rgba(128,128,128,0.12) | #808080 | #000000 | rgba(0,0,0,0.8) | #808080 | #000000 | #6d6d6d | 5.32
        #808080 | dark | #808080 | #696969 | rgba(128,128,128,0.12) | #8a8a8a | #000000 | rgba(0,0,0,0.8) | #5f5f62 | #ffffff | #868686 | 6.08
        #767676 | light | #767676 | #616161 | rgba(118,118,118,0.12) | #767676 | #ffffff | rgba(255,255,255,0.8) | #767676 | #ffffff | #707070 | 4.54
        #767676 | dark | #767676 | #616161 | rgba(118,118,118,0.12) | #818181 | #000000 | rgba(0,0,0,0.8) | #58585b | #ffffff | #8b8b8b | 5.39
        #1e1b4b | light | #1e1b4b | #19163e | rgba(30,27,75,0.12) | #1e1b4b | #ffffff | rgba(255,255,255,0.8) | #1e1b4b | #ffffff | #1e1b4b | 15.99
        #1e1b4b | dark | #1e1b4b | #19163e | rgba(30,27,75,0.12) | #302d59 | #ffffff | rgba(255,255,255,0.8) | #19173c | #ffffff | #83829c | 12.76
        #0f8 | light | #00ff88 | #00d170 | rgba(0,255,136,0.12) | #00ff88 | #000000 | rgba(0,0,0,0.8) | #00ff88 | #000000 | #008044 | 15.66
        #0f8 | dark | #00ff88 | #00d170 | rgba(0,255,136,0.12) | #14ff92 | #000000 | rgba(0,0,0,0.8) | #03bb68 | #000000 | #00ff88 | 15.75
    """.trimIndent().lines().filter { it.isNotBlank() }.map { line -> line.split('|').map { it.trim() } }

    @Test
    fun `every test vector of the web palette`() {
        for (v in vectors) {
            val p = Palette.brandPalette(v[0], v[1] == "dark")
            val label = "${v[0]} ${v[1]}"
            assertEquals("$label brand", v[2], p.brand)
            assertEquals("$label deep", v[3], p.deep)
            assertEquals("$label soft", v[4], p.soft)
            assertEquals("$label fill", v[5], p.fill)
            assertEquals("$label onBrand", v[6], p.onBrand)
            assertEquals("$label onBrandMuted", v[7], p.onBrandMuted)
            assertEquals("$label band", v[8], p.band)
            assertEquals("$label onBand", v[9], p.onBand)
            assertEquals("$label text", v[10], p.text)
            assertEquals("$label contrast", v[11].toDouble(), Palette.contrastRatio(p.onBrand, p.fill), 0.005)
        }
    }

    @Test
    fun `colors that are not #rgb or #rrggbb fall back to the default brand`() {
        assertEquals("#00f080", Palette.safeColor("#00F080"))
        assertEquals("#00ff88", Palette.safeColor("#0f8"))
        assertEquals("#00ff88", Palette.safeColor(" #0F8 "))
        for (bad in listOf("#10b9b580", "#0f88", "rgb(0,240,128)", "yellow", "", null, "#12345")) {
            assertEquals("$bad", "#2563eb", Palette.safeColor(bad))
        }
        assertEquals(Palette.brandPalette("#2563eb", false), Palette.brandPalette("yellow", false))
        assertEquals(Palette.brandPalette("#2563eb", true), Palette.brandPalette("#10b9b580", true))
    }

    @Test
    fun `the contrast facts the web tests pin`() {
        assertEquals(3.668, Palette.contrastRatio("#ffffff", "#049593"), 0.001)
        assertEquals(5.725, Palette.contrastRatio("#000000", "#049593"), 0.001)
        for (brand in listOf("#2563eb", "#049593", "#00f080", "#111827", "#ef4444", "#f97316", "#10b981", "#fde047", "#3f3be8", "#808080")) {
            for (dark in listOf(false, true)) {
                val p = Palette.brandPalette(brand, dark)
                assertTrue(Palette.contrastRatio(p.onBrand, p.fill) >= 4.5)
                assertTrue(Palette.contrastRatio(p.onBand, p.band) >= 4.5)
                val surfaces = if (dark) Palette.PANEL_SURFACES_DARK else Palette.PANEL_SURFACES_LIGHT
                surfaces.forEach { assertTrue("$brand $dark", Palette.contrastRatio(p.text, it) >= 4.5) }
            }
        }
        // An already readable brand keeps its exact value as text.
        assertEquals("#2563eb", Palette.brandPalette("#2563eb", false).text)
    }

    @Test
    fun `colors become Android colors, and color-mix mixes per channel`() {
        assertEquals(0xFF2563EB.toInt(), Palette.argb("#2563eb"))
        assertEquals(0x1F2563EB, Palette.argb("#2563eb", 0.12))
        // --g-canvas for the default brand: #f6f9fe light, #151a2f dark.
        assertEquals(0xFFF6F9FE.toInt(), Palette.mixArgb(0xFF2563EB.toInt(), 0xFFFFFFFF.toInt(), 0.04))
        assertEquals(0xFF151A2F.toInt(), Palette.mixArgb(0xFF2563EB.toInt(), 0xFF14141F.toInt(), 0.08))
    }
}
