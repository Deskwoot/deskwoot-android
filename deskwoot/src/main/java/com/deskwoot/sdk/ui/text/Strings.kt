package com.deskwoot.sdk.ui.text

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The texts the messenger shows, by string resource id. Pure code (row
 * builders, formats) takes this interface, so it runs in JVM tests.
 */
internal interface Texts {
    /** The text of [id] in the session's language; [args] are always text (numbers too). */
    fun t(@StringRes id: Int, vararg args: String): String

    /** The language the texts are in. */
    val language: UiLanguage

    /** "{text}." unless the text already ends with a mark of its own (`asSentence`). */
    fun sentence(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty() || trimmed.last() in SENTENCE_ENDS) return trimmed
        return t(com.deskwoot.sdk.R.string.dw_sentence, trimmed)
    }

    /** 2 sentences in a row: a space in Latin scripts, none in Japanese and Chinese (`sentences`). */
    fun sentences(first: String, second: String): String = t(com.deskwoot.sdk.R.string.dw_two_sentences, first, second)

    companion object {
        val SENTENCE_ENDS: Set<Char> = setOf('.', '!', '?', '。', '！', '？', '।')

        /** Drops a final `. 。 ।` (Direkt's header line, `withoutFullStop`). */
        fun withoutFullStop(text: String): String {
            val trimmed = text.trimEnd()
            return if (trimmed.isNotEmpty() && trimmed.last() in setOf('.', '。', '।')) trimmed.dropLast(1) else trimmed
        }
    }
}

/**
 * One of the messenger's 10 languages (the web dictionary's: en, de, es,
 * fr, pt-BR, it, ja, zh, hi, ar). Lookup as on the web: the full
 * tag, then the base language, then English; `pt` and `pt-BR` both read
 * Brazilian Portuguese, every `zh` the Simplified dictionary.
 */
internal enum class UiLanguage(val tag: String, val language: String, val country: String) {
    EN("en", "en", ""),
    DE("de", "de", ""),
    ES("es", "es", ""),
    FR("fr", "fr", ""),
    PT_BR("pt-BR", "pt", "BR"),
    IT("it", "it", ""),
    JA("ja", "ja", ""),
    ZH("zh", "zh", ""),
    HI("hi", "hi", ""),
    AR("ar", "ar", ""),
    ;

    /** The resource locale (`values-pt-rBR` for Portuguese, the bare language otherwise). */
    val locale: Locale get() = Locale.forLanguageTag(tag)

    /** Arabic is the one right to left language. */
    val isRtl: Boolean get() = this == AR

    companion object {
        fun resolve(tag: String?): UiLanguage {
            val clean = tag?.trim()?.replace('_', '-')?.lowercase().orEmpty()
            if (clean.isEmpty()) return EN
            entries.firstOrNull { it.tag.lowercase() == clean }?.let { return it }
            val base = clean.substringBefore('-')
            if (base == "pt") return PT_BR
            return entries.firstOrNull { it.language == base } ?: EN
        }
    }
}

/**
 * [Texts] over the library's string resources, read through a context
 * whose configuration names the SESSION's language, never the device's.
 * German switches to the "Sie" table (`values-de/strings_formal.xml`) when
 * the inbox's form of address is "sie".
 */
internal class Strings(base: Context, override val language: UiLanguage, val formal: Boolean, theme: Int? = null) : Texts {
    /**
     * A context in the session's language and its layout direction. With a
     * [theme] it wraps [base] (an Activity: views built from it keep the
     * window), else it is a configuration context.
     */
    val context: Context = localized(base, language, theme)

    private val useFormal = formal && language == UiLanguage.DE

    override fun t(id: Int, vararg args: String): String {
        val resolved = if (useFormal) FormalStrings.map[id] ?: id else id
        return if (args.isEmpty()) context.getString(resolved) else context.getString(resolved, *args)
    }

    internal companion object {
        fun localized(base: Context, language: UiLanguage, theme: Int?): Context {
            val configuration = Configuration(base.resources.configuration)
            val locale = language.locale
            if (Build.VERSION.SDK_INT >= 24) {
                configuration.setLocales(LocaleList(locale))
            } else {
                configuration.setLocale(locale)
            }
            configuration.setLayoutDirection(locale)
            if (theme == null) return base.createConfigurationContext(configuration)
            return ContextThemeWrapper(base, theme).apply { applyOverrideConfiguration(configuration) }
        }

        /** The inbox asks for "Sie" (`config.formOfAddress`); only German has the table. */
        fun isFormal(formOfAddress: String?): Boolean = formOfAddress == "sie"
    }
}
