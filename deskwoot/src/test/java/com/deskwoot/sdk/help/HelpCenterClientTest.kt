package com.deskwoot.sdk.help

import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.FakeHttp
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.HelpContext
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.HelpArticleSummary
import com.deskwoot.sdk.ok
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The help center inside the chat. */
class HelpCenterClientTest {
    private val featuredDe = listOf(HelpArticleSummary("a1", "Versand", "versand", null))
    private val featuredEn = listOf(HelpArticleSummary("a2", "Shipping", "shipping", null))
    private var help = HelpContext("de-DE", "acme", "https://help.acme.io/", true, featuredDe, "de", "wb_known")
    private val http = FakeHttp()
    private val client = HelpCenterClient(WidgetApi(http, { BASE_URL }, TOKEN)) { help }

    @Test
    fun `collections load once per language and drop empty ones`() = runTest {
        http.on("GET /api/widget/articles") {
            ok(
                json(
                    "portalName" to "Acme Help",
                    "categories" to listOf(
                        mapOf("id" to "c1", "slug" to "billing", "name" to "Abrechnung", "articleCount" to 3),
                        mapOf("id" to "c2", "slug" to "empty", "name" to "Leer", "articleCount" to 0),
                    ),
                ),
            )
        }
        val first = client.collections()
        client.collections()
        assertEquals(listOf("c1"), (first as ApiResult.Success).value.map { it.id })
        assertEquals(1, http.calls.size)
        assertEquals("de-DE", http.calls.single().query["locale"])
        assertEquals("acme", http.calls.single().query["portalSlug"])

        help = help.copy(locale = "en")
        client.collections()
        assertEquals(2, http.calls.size)
    }

    @Test
    fun `search uses the search parameter and trims, an empty query asks nothing`() = runTest {
        http.on("GET /api/widget/articles") { ok(json("articles" to listOf(mapOf("id" to "a1", "title" to "Refunds", "slug" to "refunds")))) }
        assertEquals(ApiResult.Success(emptyList<HelpArticleSummary>()), client.search("   "))
        assertTrue(http.calls.isEmpty())

        client.search("  " + "x".repeat(250) + "  ")
        val query = http.calls.single().query
        assertEquals(200, query["search"]?.length)
        assertNull(query["q"])
    }

    @Test
    fun `a vote carries the bearer and the same vote is sent once`() = runTest {
        http.on("POST /api/widget/articles/feedback") { ok(null) }
        client.vote("refunds", helpful = true)
        client.vote("refunds", helpful = true)
        client.vote("refunds", helpful = false)

        val bodies = http.calls.map { checkNotNull(it.body) }
        assertEquals(listOf("HELPFUL", "NOT_HELPFUL"), bodies.map { it.getString("value") })
        assertEquals("wb_known", bodies.first().getString("contactIdentifier"))
        assertEquals(false, client.voteOf("refunds"))
    }

    @Test
    fun `suggested articles stay for the session until the language changes`() {
        assertEquals(featuredDe, client.suggested())
        help = help.copy(featured = featuredEn.reversed())
        assertEquals(featuredDe, client.suggested())
        help = help.copy(featured = featuredEn, featuredLocale = "en-US")
        assertEquals(featuredEn, client.suggested())
    }

    @Test
    fun `an article opens on the public help center in its language`() = runTest {
        http.on("GET /api/widget/articles") {
            ok(json("article" to mapOf("id" to "a1", "title" to "Versand", "slug" to "versand", "locale" to "de", "content" to "<p>Hi</p>")))
        }
        val article = (client.article("versand") as ApiResult.Success).value
        assertEquals("versand", http.calls.single().query["articleSlug"])
        assertEquals("https://help.acme.io/versand?locale=de", client.articleUrl(article))
        assertEquals("https://help.acme.io/versand", client.articleUrl(article.copy(locale = "en")))
    }

    @Test
    fun `without a help center nothing is asked`() = runTest {
        help = help.copy(enabled = false)
        assertTrue(client.collections() is ApiResult.Failure)
        assertTrue(client.suggested().isEmpty())
        assertTrue(http.calls.isEmpty())
    }
}
