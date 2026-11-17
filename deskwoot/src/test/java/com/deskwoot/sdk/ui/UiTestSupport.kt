package com.deskwoot.sdk.ui

import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.text.Formats
import com.deskwoot.sdk.ui.text.Texts
import com.deskwoot.sdk.ui.text.UiLanguage
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** The library's resource folder, wherever Gradle runs the tests from. */
internal val RES_DIR: File = listOf(File("src/main/res"), File("deskwoot/src/main/res")).first { it.isDirectory }

/** `name -> text` of one strings file, with Android's escapes undone. */
internal fun readStrings(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    val out = LinkedHashMap<String, String>()
    Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).forEach { m ->
        out[m.groupValues[1]] = m.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    }
    return out
}

/** Every string of one `values*` folder (the generated file and the SDK's own lines). */
internal fun stringsOf(folder: String): Map<String, String> =
    readStrings(File(RES_DIR, "$folder/strings.xml")) + readStrings(File(RES_DIR, "$folder/strings_sdk.xml"))

/** [Texts] over the real resource files, without Android: ids are mapped to names through the R class. */
internal class FileTexts(override val language: UiLanguage = UiLanguage.EN) : Texts {
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val folder = when (language) {
        UiLanguage.EN -> "values"
        UiLanguage.PT_BR -> "values-pt-rBR"
        else -> "values-" + language.tag
    }
    private val table = stringsOf("values") + stringsOf(folder)

    override fun t(id: Int, vararg args: String): String {
        val name = names[id] ?: error("No string with id $id")
        val text = table[name] ?: error("No text for $name")
        return if (args.isEmpty()) text else String.format(Locale.ROOT, text, *args)
    }
}

/** CLDR like patterns for the skeletons the formats use, for English (US) and German. */
internal fun testPatterns(skeleton: String, locale: Locale): String {
    val en = mapOf(
        "hmm" to "h:mm a", "HHmm" to "HH:mm", "MMMMd" to "MMMM d", "yMMMMd" to "MMMM d, y", "MMMd" to "MMM d",
        "yMMMd" to "MMM d, y", "MMMdhmm" to "MMM d, h:mm a", "yMMMdhmm" to "MMM d, y, h:mm a", "MMMdHHmm" to "MMM d, HH:mm",
        "EEEMMMd" to "EEE, MMM d",
    )
    val de = mapOf(
        "hmm" to "h:mm a", "HHmm" to "HH:mm", "MMMMd" to "d. MMMM", "yMMMMd" to "d. MMMM y", "MMMd" to "d. MMM",
        "yMMMd" to "d. MMM y", "MMMdHHmm" to "d. MMM, HH:mm", "yMMMdHHmm" to "d. MMM y, HH:mm", "EEEMMMd" to "EEE, d. MMM",
    )
    val table = if (locale.language == "de") de else en
    return table[skeleton] ?: error("No test pattern for $skeleton")
}

internal fun testFormats(texts: Texts = FileTexts(), zone: String = "Europe/Berlin"): Formats =
    Formats(texts, TimeZone.getTimeZone(zone), ::testPatterns)
