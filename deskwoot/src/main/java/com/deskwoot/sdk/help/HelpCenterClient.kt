package com.deskwoot.sdk.help

import androidx.annotation.MainThread
import com.deskwoot.sdk.api.ApiError
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.HelpContext
import com.deskwoot.sdk.model.HelpArticle
import com.deskwoot.sdk.model.HelpArticleSummary
import com.deskwoot.sdk.model.HelpCollection

/**
 * The help center inside the chat: collections, a
 * collection's articles, search, one article, votes and "Suggested for
 * you". Every call asks in the app's raw language tag, the same one the
 * config is asked with; the server applies the inbox's fixed language
 * itself. State lives on the main thread, like the session's.
 */
@MainThread
internal class HelpCenterClient(
    private val api: WidgetApi,
    private val context: () -> HelpContext?,
) {
    private var collections: Pair<String?, List<HelpCollection>>? = null
    private var suggested: Pair<String?, List<HelpArticleSummary>>? = null
    private val votes = HashMap<String, Boolean>()

    /** Collections with articles, loaded once per language. */
    suspend fun collections(): ApiResult<List<HelpCollection>> {
        val help = available() ?: return unavailable()
        collections?.takeIf { it.first == help.locale }?.let { return ApiResult.Success(it.second) }
        val result = api.helpCollections(help.locale, help.portalSlug)
        if (result is ApiResult.Success) collections = help.locale to result.value
        return result
    }

    suspend fun collectionArticles(collectionId: String): ApiResult<List<HelpArticleSummary>> {
        val help = available() ?: return unavailable()
        return api.helpCollectionArticles(collectionId, help.locale, help.portalSlug)
    }

    /** Search; the text is trimmed and cut at 200 characters like the server does, an empty one finds nothing. */
    suspend fun search(query: String): ApiResult<List<HelpArticleSummary>> {
        val help = available() ?: return unavailable()
        val text = query.trim().take(MAX_QUERY)
        if (text.isEmpty()) return ApiResult.Success(emptyList())
        return api.helpSearch(text, help.locale, help.portalSlug)
    }

    suspend fun article(slug: String): ApiResult<HelpArticle> {
        val help = available() ?: return unavailable()
        return api.helpArticle(slug, help.locale, help.portalSlug)
    }

    /**
     * A vote on an article. The same vote again sends nothing, a flip sends
     * the new one; the visitor's bearer makes it one vote per
     * visitor rather than per network address.
     */
    suspend fun vote(slug: String, helpful: Boolean): ApiResult<Unit> {
        val help = available() ?: return unavailable()
        if (votes[slug] == helpful) return ApiResult.Success(Unit)
        val result = api.helpVote(slug, helpful, help.bearer)
        if (result is ApiResult.Success) votes[slug] = helpful
        return result
    }

    /** The vote cast on [slug] in this session, if any. */
    fun voteOf(slug: String): Boolean? = votes[slug]

    /**
     * "Suggested for you": the first list per help center language is kept
     * for the session, so a config fetched again does not reshuffle it; a
     * new language replaces it, even with an empty one.
     */
    fun suggested(): List<HelpArticleSummary> {
        val help = available() ?: return emptyList()
        val kept = suggested
        if (kept != null && kept.first == help.featuredLocale) return kept.second
        suggested = help.featuredLocale to help.featured
        return help.featured
    }

    /** The article on the public help center, or null without a public address. */
    fun articleUrl(article: HelpArticle): String? {
        val help = context() ?: return null
        val base = help.baseUrl?.trimEnd('/') ?: return null
        if (help.portalSlug == null) return null
        val locale = article.locale
        val query = if (locale == null || locale == "en") "" else "?locale=${WidgetApi.encode(locale)}"
        return "$base/${article.slug}$query"
    }

    private fun available(): HelpContext? = context()?.takeIf { it.enabled }

    private fun unavailable(): ApiResult.Failure =
        ApiResult.Failure(ApiError(status = 404, serverMessage = "No help center available"))

    private companion object {
        const val MAX_QUERY = 200
    }
}
