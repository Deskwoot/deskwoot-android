package com.deskwoot.sdk.ui

import android.icu.text.PluralRules
import androidx.activity.ComponentActivity
import com.deskwoot.sdk.R
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.ui.design.DirektHeader
import com.deskwoot.sdk.ui.image.ImageLoader
import com.deskwoot.sdk.ui.text.Strings
import com.deskwoot.sdk.ui.text.UiLanguage
import com.deskwoot.sdk.ui.theme.UiColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The unread count read out by TalkBack ("3 new messages") with each
 * language's own plural forms, as CLDR defines them: Arabic has 6 (zero,
 * one, two, few for 3 to 10, many for 11 to 99, other for 100 and up),
 * Japanese and Chinese only one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnreadPluralsTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    /** The CLDR cardinal categories of each language of the SDK. */
    private val cldr = mapOf(
        UiLanguage.EN to setOf("one", "other"),
        UiLanguage.DE to setOf("one", "other"),
        UiLanguage.ES to setOf("one", "many", "other"),
        UiLanguage.FR to setOf("one", "many", "other"),
        UiLanguage.PT_BR to setOf("one", "many", "other"),
        UiLanguage.IT to setOf("one", "many", "other"),
        UiLanguage.HI to setOf("one", "other"),
        UiLanguage.AR to setOf("zero", "one", "two", "few", "many", "other"),
        UiLanguage.JA to setOf("other"),
        UiLanguage.ZH to setOf("other"),
    )

    private fun folder(language: UiLanguage) = when (language) {
        UiLanguage.EN -> "values"
        UiLanguage.PT_BR -> "values-pt-rBR"
        else -> "values-" + language.tag
    }

    /** The quantities the strings file of [language] gives `dw_sdk_unread`. */
    private fun quantities(language: UiLanguage): Set<String> {
        val text = File(RES_DIR, folder(language) + "/strings_sdk.xml").readText()
        val block = checkNotNull(Regex("<plurals name=\"dw_sdk_unread\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).find(text)).groupValues[1]
        return Regex("quantity=\"([a-z]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every language has exactly its CLDR plural forms`() {
        assertEquals(UiLanguage.entries.toSet(), cldr.keys)
        for ((language, categories) in cldr) {
            assertEquals("${language.tag}: the file", categories, quantities(language))
            assertEquals("${language.tag}: the platform's rules", categories, PluralRules.forLocale(language.locale).keywords.toSet())
        }
    }

    @Test
    fun `Arabic picks zero, one, two, few, many and other by the count`() {
        val rules = PluralRules.forLocale(UiLanguage.AR.locale)
        val expected = mapOf(
            0 to "zero", 1 to "one", 2 to "two", 3 to "few", 10 to "few", 11 to "many", 99 to "many",
            100 to "other", 101 to "other", 102 to "other", 103 to "few", 111 to "many",
        )
        for ((n, category) in expected) assertEquals("$n", category, rules.select(n.toDouble()))
        val res = Strings(activity, UiLanguage.AR, false).context.resources
        fun said(n: Int) = res.getQuantityString(R.plurals.dw_sdk_unread, n, n)
        assertEquals("لا توجد رسائل جديدة", said(0))
        assertEquals("رسالة جديدة واحدة", said(1))
        assertEquals("رسالتان جديدتان", said(2))
        // 3 to 10: the plural; 11 to 99 and from 100 on: the singular after the number.
        for (n in listOf(3, 10, 103)) assertTrue(said(n), said(n).endsWith(" رسائل جديدة"))
        for (n in listOf(11, 99, 100, 101, 111)) assertTrue(said(n), said(n).endsWith(" رسالة جديدة"))
    }

    @Test
    fun `the conversations button of the list says the count in the language's plural`() {
        fun header(language: UiLanguage): DirektHeader {
            val config = checkNotNull(WidgetConfig.parse(baseConfig().toString()))
            val session = ChatSession.create(activity, "wt_plural", null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val strings = Strings(activity, language, false, R.style.dw_messenger_theme_light)
            val ui = Ui(session, SessionActions(session, scope), strings, UiColors(config.brandColor, false), DirektDesign, ImageLoader.get(activity), scope, ScreenHarness.NoNavigation, config)
            return DirektHeader(strings.context, ui)
        }
        val ar = header(UiLanguage.AR)
        val label = FileTexts(UiLanguage.AR).t(R.string.dw_your_conversations)
        ar.setBadge(0)
        assertEquals(label, ar.conversations.contentDescription)
        ar.setBadge(1)
        assertEquals("$label, رسالة جديدة واحدة", ar.conversations.contentDescription)
        ar.setBadge(2)
        assertEquals("$label, رسالتان جديدتان", ar.conversations.contentDescription)
        ar.setBadge(4)
        assertTrue(ar.conversations.contentDescription.endsWith(" رسائل جديدة"))
        ar.setBadge(12)
        assertTrue(ar.conversations.contentDescription.endsWith(" رسالة جديدة"))
        ar.setBadge(100)
        assertTrue(ar.conversations.contentDescription.endsWith(" رسالة جديدة"))

        val ja = header(UiLanguage.JA)
        ja.setBadge(1)
        assertTrue(ja.conversations.contentDescription.toString(), ja.conversations.contentDescription.endsWith("新着メッセージ1件"))
        val de = header(UiLanguage.DE)
        de.setBadge(1)
        assertTrue(de.conversations.contentDescription.endsWith(", 1 neue Nachricht"))
        de.setBadge(5)
        assertTrue(de.conversations.contentDescription.endsWith(", 5 neue Nachrichten"))
        assertEquals(Locale.forLanguageTag("ar"), UiLanguage.AR.locale)
    }
}
