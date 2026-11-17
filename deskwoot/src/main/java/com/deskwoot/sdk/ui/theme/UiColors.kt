package com.deskwoot.sdk.ui.theme

import android.graphics.Color

/**
 * Every color the messenger paints, for one brand and one appearance: the
 * Widget 2.0 neutral tokens and the
 * brand palette (1.2), as Android colors. Nothing reads the app's theme.
 */
internal class UiColors(brandColor: String?, val isDark: Boolean) {
    val palette: BrandPalette = Palette.brandPalette(brandColor, isDark)

    val bg: Int = if (isDark) 0xFF14141F.toInt() else 0xFFFFFFFF.toInt()
    val bg2: Int = if (isDark) 0xFF1C1C2A.toInt() else 0xFFF5F5F9.toInt()
    val bg3: Int = if (isDark) 0xFF262638.toInt() else 0xFFEBEBF2.toInt()
    val line: Int = if (isDark) 0xFF2A2A3D.toInt() else 0xFFE6E6EE.toInt()
    val line2: Int = if (isDark) 0xFF3B3B54.toInt() else 0xFFD4D4E0.toInt()
    val ink: Int = if (isDark) 0xFFEEEEF6.toInt() else 0xFF16162B.toInt()
    val ink2: Int = if (isDark) 0xFFB5B5CA.toInt() else 0xFF4E4E66.toInt()
    val ink3: Int = if (isDark) 0xFF808099.toInt() else 0xFF82829A.toInt()
    val ok: Int = if (isDark) 0xFF34C77B.toInt() else 0xFF16A05C.toInt()
    val scrim: Int = if (isDark) 0x80000000.toInt() else Color.argb(82, 16, 16, 34)
    val error: Int = 0xFFDC2626.toInt()
    val errorBg: Int = if (isDark) Color.argb(31, 220, 38, 38) else 0xFFFEF2F2.toInt()
    val initialsBg: Int = if (isDark) 0xFF3A322C.toInt() else 0xFFE6DDD2.toInt()
    val initialsInk: Int = if (isDark) 0xFFEADBCB.toInt() else 0xFF5C4636.toInt()

    /** Klartext incoming bubble (`--k-bub`); also the link and tracking cards in every design. */
    val kBubble: Int = if (isDark) bg3 else bg2

    val brand: Int = Palette.argb(palette.brand)
    val deep: Int = Palette.argb(palette.deep)
    val fill: Int = Palette.argb(palette.fill)
    val onBrand: Int = Palette.argb(palette.onBrand)
    val band: Int = Palette.argb(palette.band)
    val onBand: Int = Palette.argb(palette.onBand)
    val brandText: Int = Palette.argb(palette.text)

    /** `--dw-brand-soft`: the raw brand at 12 % (queue line, help article icon tile). */
    val brandSoft12: Int = Palette.argb(palette.brand, 0.12)

    /** `--brand-soft`: the raw brand at 9 % light, 24 % dark (search marks, pressed choices, collection tiles). */
    val brandSoft: Int = Palette.argb(palette.brand, if (isDark) 0.24 else 0.09)

    /** The faint brand canvas of the Direkt design (`--g-canvas`). */
    val directCanvas: Int = Palette.mixArgb(brand, bg, if (isDark) 0.08 else 0.04)

    /** Empfang: the page behind the cards (`--e-canvas`) and the cards and incoming bubbles on it (`--e-card`). */
    val eCanvas: Int = if (isDark) bg else bg2
    val eCard: Int = if (isDark) bg2 else bg

    /** Direkt: the incoming bubble (`--g-bub`). */
    val gBubble: Int = if (isDark) bg3 else 0xFFFFFFFF.toInt()

    /** Text and icons on Empfang's colored band, at a given strength (icons 88 %, sub lines 80 %, tagline 82 %). */
    fun onBand(alpha: Double): Int = Palette.argb(palette.onBand, alpha)

    /** Text and icons on the brand fill, at a given strength (quotes and files in the visitor's bubble). */
    fun onBrand(alpha: Double): Int = Palette.argb(palette.onBrand, alpha)

    fun withAlpha(color: Int, alpha: Double): Int = (color and 0x00FFFFFF) or (Palette.jsRound(alpha * 255).coerceIn(0, 255) shl 24)

    /** The small shadow of cards (`--dw-shadow-sm`). */
    val shadowSmall: Int = if (isDark) 0x4D000000 else Color.argb(15, 15, 23, 42)

    /**
     * The brand fill reads under 3:1 against the panel's background (white or
     * yellow brands in light, black or dark brands in dark): what is filled
     * with it (the visitor's bubbles, the send buttons, primary buttons) gets
     * a 1 dp edge. The same rule in every look, as in the web widget.
     */
    val needsEdge: Boolean = needsEdge(palette.fill, isDark)

    /** The edge color of a brand fill, or null when the fill stands out by itself (the web widget's `edgeColor`). */
    val fillEdge: Int? = if (needsEdge) Palette.argb(edgeColor(palette.fill, isDark)) else null

    /** A key of everything these colors depend on: equal keys draw the same. */
    val key: String = "${palette.brand}|$isDark"

    internal companion object {
        /** Below this contrast against a surface a filled shape loses its outline (WCAG 1.4.11). */
        const val EDGE_MIN_CONTRAST: Double = 3.0

        /** The panel's background and bg-2, the web widget's 2 surfaces. */
        private fun panelSurfaces(isDark: Boolean): List<String> = if (isDark) Palette.PANEL_SURFACES_DARK else Palette.PANEL_SURFACES_LIGHT

        /** Under 3:1 against the panel's background (`#ffffff` in light, `#14141f` in dark), whatever the look. */
        fun needsEdge(fill: String, isDark: Boolean): Boolean = Palette.contrastRatio(fill, panelSurfaces(isDark)[0]) < EDGE_MIN_CONTRAST

        /**
         * The edge of a fill without contrast (the web widget's `edgeColor`): the fill
         * mixed toward black (light) or white (dark) in steps of 5 %, each channel rounded half up; the
         * first step with at least 3:1 against both panel surfaces (the background and bg-2), else pure
         * black or white. It keeps the brand's hue: a yellow shop gets a dark olive edge, not a gray one.
         */
        fun edgeColor(fill: String, isDark: Boolean): String {
            val surfaces = panelSurfaces(isDark)
            val target = if (isDark) 255 else 0
            val hex = fill.removePrefix("#")
            val channels = listOf(0, 2, 4).map { hex.substring(it, it + 2).toInt(16) }
            for (step in 0..20) {
                val mix = step / 20.0
                val candidate = "#" + channels.joinToString("") { c ->
                    "%02x".format(kotlin.math.floor(c + (target - c) * mix + 0.5).toInt().coerceIn(0, 255))
                }
                if (surfaces.all { Palette.contrastRatio(candidate, it) >= EDGE_MIN_CONTRAST }) return candidate
            }
            return if (isDark) "#ffffff" else "#000000"
        }
    }
}
