package com.deskwoot.sdk.ui.theme

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The web messenger's brand palette, ported 1:1 so a business's chat has the same colors in
 * its app as on its website. Colors are lower case `#rrggbb` strings, as on
 * the web; [argb] turns one into an Android color.
 */
internal object Palette {
    const val DEFAULT_BRAND: String = "#2563eb"
    const val BRAND_DEEP_AMOUNT: Double = 0.18
    const val DARK_FILL_LIGHTEN: Double = 0.08
    const val DARK_BAND_DEEPEN: Double = 0.28
    const val DARK_BAND_GROUND: String = "#0b0b14"
    const val ON_BRAND_MIN_CONTRAST: Double = 4.5

    /** `--dw-bg` and `--dw-bg-sunken` of each theme: the surfaces brand text must read on. */
    val PANEL_SURFACES_LIGHT: List<String> = listOf("#ffffff", "#f5f5f9")
    val PANEL_SURFACES_DARK: List<String> = listOf("#14141f", "#1c1c2a")

    private val HEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    /** `#rgb` or `#rrggbb` (trimmed) as lower case `#rrggbb`; anything else is the default brand. */
    fun safeColor(input: String?): String {
        val text = input?.trim() ?: return DEFAULT_BRAND
        val match = HEX.matchEntire(text) ?: return DEFAULT_BRAND
        val digits = match.groupValues[1].lowercase()
        return if (digits.length == 3) "#" + digits.map { "$it$it" }.joinToString("") else "#$digits"
    }

    fun hexToRgb(hex: String): IntArray {
        val clean = safeColor(hex).substring(1)
        return intArrayOf(clean.substring(0, 2).toInt(16), clean.substring(2, 4).toInt(16), clean.substring(4, 6).toInt(16))
    }

    /** JavaScript's Math.round: halves round up (toward +infinity). */
    fun jsRound(value: Double): Int = floor(value + 0.5).toInt()

    fun toHex(r: Double, g: Double, b: Double): String {
        fun channel(v: Double): String {
            val c = jsRound(min(255.0, max(0.0, v)))
            return c.toString(16).padStart(2, '0')
        }
        return "#" + channel(r) + channel(g) + channel(b)
    }

    fun mixHex(a: String, b: String, weight: Double): String {
        val x = hexToRgb(a)
        val y = hexToRgb(b)
        return toHex(
            x[0] + (y[0] - x[0]) * weight,
            x[1] + (y[1] - x[1]) * weight,
            x[2] + (y[2] - x[2]) * weight,
        )
    }

    fun darkenHex(hex: String, amount: Double): String {
        val f = max(0.0, 1 - amount)
        val c = hexToRgb(hex)
        return toHex(jsRound(c[0] * f).toDouble(), jsRound(c[1] * f).toDouble(), jsRound(c[2] * f).toDouble())
    }

    /** WCAG 2.0 relative luminance (threshold 0.03928, as on the web). */
    fun relativeLuminance(hex: String): Double {
        val c = hexToRgb(hex).map { v ->
            val s = v / 255.0
            if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    }

    fun contrastRatio(a: String, b: String): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** White when it reaches 4.5:1 on [fill], else black. */
    fun textOn(fill: String): String = if (contrastRatio("#ffffff", fill) >= ON_BRAND_MIN_CONTRAST) "#ffffff" else "#000000"

    /** [color] moved toward black or white in 5 % steps until it reads on every surface. */
    fun readableOn(color: String, surfaces: List<String>, min: Double): String {
        val towards = if (relativeLuminance(surfaces[0]) > 0.5) "#000000" else "#ffffff"
        for (step in 0..20) {
            val candidate = mixHex(color, towards, step / 20.0)
            if (surfaces.all { contrastRatio(candidate, it) >= min }) return candidate
        }
        return towards
    }

    fun brandPalette(brandColor: String?, isDark: Boolean): BrandPalette {
        val brand = safeColor(brandColor)
        val fill = if (isDark) mixHex(brand, "#ffffff", DARK_FILL_LIGHTEN) else brand
        val band = if (isDark) mixHex(brand, DARK_BAND_GROUND, DARK_BAND_DEEPEN) else brand
        val onBrand = textOn(fill)
        return BrandPalette(
            brand = brand,
            deep = darkenHex(brand, BRAND_DEEP_AMOUNT),
            fill = fill,
            band = band,
            onBrand = onBrand,
            onBand = textOn(band),
            text = readableOn(brand, if (isDark) PANEL_SURFACES_DARK else PANEL_SURFACES_LIGHT, ON_BRAND_MIN_CONTRAST),
        )
    }

    /** `rgba(r,g,b,a)` as the web writes it, for the test vectors. */
    fun colorAlpha(hex: String, alpha: Double): String {
        val c = hexToRgb(hex)
        val a = if (alpha == floor(alpha)) alpha.toInt().toString() else alpha.toString()
        return "rgba(${c[0]},${c[1]},${c[2]},$a)"
    }

    /** An Android color from `#rrggbb` and an alpha of 0 to 1. */
    fun argb(hex: String, alpha: Double = 1.0): Int {
        val c = hexToRgb(hex)
        val a = jsRound(alpha.coerceIn(0.0, 1.0) * 255)
        return (a shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    /** `color-mix(in srgb, a p, b)` with opaque colors: a per channel mix. */
    fun mixArgb(a: Int, b: Int, weightOfA: Double): Int {
        fun ch(x: Int, shift: Int) = (x shr shift) and 0xff
        fun mix(shift: Int) = jsRound(ch(a, shift) * weightOfA + ch(b, shift) * (1 - weightOfA)).coerceIn(0, 255)
        return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/**
 * The 9 brand colors of the web messenger. `soft` (12 %) and `onBrandMuted`
 * (80 %) are alpha variants and come from [Palette.argb].
 */
internal data class BrandPalette(
    val brand: String,
    val deep: String,
    val fill: String,
    val band: String,
    val onBrand: String,
    val onBand: String,
    val text: String,
) {
    val soft: String get() = Palette.colorAlpha(brand, 0.12)
    val onBrandMuted: String get() = Palette.colorAlpha(onBrand, 0.8)
}
