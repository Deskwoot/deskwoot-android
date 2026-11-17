package com.deskwoot.sdk.ui.text

import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.FileTexts
import com.deskwoot.sdk.ui.RES_DIR
import com.deskwoot.sdk.ui.readStrings
import com.deskwoot.sdk.ui.stringsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The string tables: every key in every language, the same arguments everywhere, the German "Sie" table, the language rule. */
class StringsTest {
    private val folders = listOf("values", "values-de", "values-es", "values-fr", "values-pt-rBR", "values-it", "values-ja", "values-zh", "values-hi", "values-ar")
    private val placeholder = Regex("%(\\d)\\\$s")
    /** The SDK's own "Sie" lines have a default in values (aapt2 needs one) but no other translation. */
    private val english = stringsOf("values").filterKeys { !it.endsWith("_formal") }

    @Test
    fun `every language has every key with the same arguments`() {
        assertTrue(english.size >= 189)
        for (folder in folders) {
            val table = stringsOf(folder).filterKeys { !it.endsWith("_formal") }
            assertEquals("$folder keys", english.keys, table.keys)
            for ((key, text) in english) {
                val args = placeholder.findAll(text).map { it.groupValues[1] }.toSet()
                val translated = placeholder.findAll(table.getValue(key)).map { it.groupValues[1] }.toSet()
                assertEquals("$folder $key", args, translated)
                // Formatting with text arguments works and leaves no placeholder behind.
                val formatted = String.format(Locale.ROOT, table.getValue(key), *Array(args.size) { "X${it + 1}" })
                assertFalse("$folder $key", formatted.contains("%"))
            }
        }
    }

    @Test
    fun `the German Sie table has every key under its formal name`() {
        val formal = readStrings(File(RES_DIR, "values-de/strings_formal.xml"))
        val generated = readStrings(File(RES_DIR, "values/strings.xml"))
        assertEquals(generated.keys.map { it + "_formal" }.toSet(), formal.keys)
        assertEquals(generated.size, FormalStrings.map.size)
        // The table differs where it addresses the reader.
        assertEquals("Schreiben Sie eine Nachricht …", formal["dw_type_message_formal"])
        val formalId = checkNotNull(FormalStrings.map[R.string.dw_type_message])
        assertEquals("dw_type_message_formal", R.string::class.java.fields.first { it.getInt(null) == formalId }.name)
    }

    @Test
    fun `texts with arguments in every language`() {
        for (language in UiLanguage.entries) {
            val texts = FileTexts(language)
            val queue = texts.t(R.string.dw_queue_position, "3")
            assertTrue("$language $queue", queue.contains("3"))
            val results = texts.t(R.string.dw_results_for, "2", "Pfeffer")
            assertTrue("$language $results", results.contains("2") && results.contains("Pfeffer"))
            assertTrue(texts.t(R.string.dw_agent_joined, "Priya").contains("Priya"))
        }
        assertEquals("You're number 3 in the queue. The next available agent will be with you.", FileTexts().t(R.string.dw_queue_position, "3"))
    }

    @Test
    fun `sentences end with one mark and join by the language's rule`() {
        val en = FileTexts()
        assertEquals("Fynn answers right away.", en.sentence("Fynn answers right away."))
        assertEquals("The team usually replies.", en.sentence("The team usually replies"))
        assertEquals("A. B.", en.sentences("A.", "B."))
        assertEquals("答える。チーム", FileTexts(UiLanguage.JA).sentences("答える。", "チーム"))
        assertEquals("Fynn answers right away", Texts.withoutFullStop("Fynn answers right away."))
    }

    @Test
    fun `the language follows the tag, its base language, then English`() {
        assertEquals(UiLanguage.DE, UiLanguage.resolve("de-AT"))
        assertEquals(UiLanguage.PT_BR, UiLanguage.resolve("pt"))
        assertEquals(UiLanguage.PT_BR, UiLanguage.resolve("pt_PT"))
        assertEquals(UiLanguage.ZH, UiLanguage.resolve("zh-Hant-TW"))
        assertEquals(UiLanguage.EN, UiLanguage.resolve("nl"))
        assertEquals(UiLanguage.EN, UiLanguage.resolve(null))
        assertEquals(UiLanguage.AR, UiLanguage.resolve("ar-EG"))
        assertTrue(UiLanguage.AR.isRtl)
        assertFalse(UiLanguage.HI.isRtl)
        assertEquals("pt-BR", UiLanguage.PT_BR.locale.toLanguageTag())
    }

    @Test
    fun `only German switches to Sie`() {
        assertTrue(Strings.isFormal("sie"))
        assertFalse(Strings.isFormal("du"))
        assertFalse(Strings.isFormal(null))
    }
}
