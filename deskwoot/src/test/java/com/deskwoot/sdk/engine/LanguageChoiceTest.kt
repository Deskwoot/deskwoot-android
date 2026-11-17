package com.deskwoot.sdk.engine

import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.ui.text.UiLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chat's language: the inbox's fixed language always wins; without one
 * the app's language, which `Deskwoot.language` replaces; a language the SDK
 * does not speak is English.
 */
class LanguageChoiceTest {
    @Test
    fun `without a fixed inbox language the chat follows the app, and the language setting takes the app's place`() = runTest {
        val h = Harness(this, appLanguage = "de-DE")
        h.start()
        assertEquals("de-DE", h.state.locale)
        assertEquals("de-DE", h.http.calls("GET /api/widget/config").last().query["locale"])

        h.engine.setLanguage("fr")
        h.settle()
        assertEquals("fr", h.state.locale)
        assertEquals("fr", h.http.calls("GET /api/widget/config").last().query["locale"])

        h.engine.setLanguage(null)
        h.settle()
        assertEquals("back to the app's language", "de-DE", h.state.locale)
    }

    @Test
    fun `the inbox's fixed language wins over the app's language and over the language setting`() = runTest {
        val h = Harness(this, config = baseConfig("widgetLocale" to "es"), appLanguage = "de-DE", language = "fr")
        h.start()
        assertEquals("es", h.state.locale)
        assertEquals(UiLanguage.ES, UiLanguage.resolve(h.state.locale))

        h.http.once("POST /api/widget/messages") { sent(newConversation = true) }
        h.engine.send("Hola")
        assertEquals("es", h.sends().single().body?.getString("widgetLocale"))

        h.engine.setLanguage(null)
        h.settle()
        assertEquals("es", h.state.locale)
    }

    @Test
    fun `a language the SDK does not speak is English`() {
        assertEquals(UiLanguage.EN, UiLanguage.resolve(Locales.effective(null, "ko-KR")))
        // The inbox's fixed language still decides, also when the SDK does not speak it.
        assertEquals(UiLanguage.EN, UiLanguage.resolve(Locales.effective("ko", "de-DE")))
        assertEquals(UiLanguage.EN, UiLanguage.resolve(Locales.effective(null, null)))
        assertEquals(UiLanguage.DE, UiLanguage.resolve(Locales.effective(null, "de-AT")))
        assertEquals(UiLanguage.PT_BR, UiLanguage.resolve(Locales.effective(null, "pt-PT")))
    }
}
