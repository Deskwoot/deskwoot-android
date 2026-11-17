package com.deskwoot.sdk.api

import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.model.HelpArticle
import com.deskwoot.sdk.model.HelpArticleSummary
import com.deskwoot.sdk.model.HelpCollection
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.model.array
import com.deskwoot.sdk.model.isTrue
import com.deskwoot.sdk.model.obj
import com.deskwoot.sdk.model.objects
import com.deskwoot.sdk.model.string
import com.deskwoot.sdk.model.text
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * Typed calls for every widget API endpoint the SDK uses,
 * relative to the origin [baseUrl] answers. The visitor is the
 * `widgetToken` plus the bearer: in the `X-Deskwoot-Contact` header on every
 * GET (never in a URL, which tools in the host app may record)
 * and as `contactIdentifier` in POST bodies. The bearer is a
 * secret: it goes into requests and nowhere else.
 *
 * Every answer is checked for the keys its route always sends; a 2xx
 * without them is no usable answer. Decoding the answers and
 * building an upload's body run on [work], never on the main thread.
 *
 * [baseUrl] is asked before every call and may read the app's manifest the
 * first time, which is why it suspends: `Deskwoot.setup` stays free of any
 * such work on the main thread.
 */
internal class WidgetApi(
    private val http: HttpClient,
    private val baseUrl: suspend () -> String,
    val widgetToken: String,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * `GET /api/widget/config` with the app's raw language tag. Never applied unless it is a config.
     * [fresh] adds a query value of its own (`fresh`), so no cache on the way serves an older copy: the
     * edge keeps an answer a minute per query string.
     */
    suspend fun config(locale: String?, fresh: Boolean = false): ApiResult<WidgetConfig> =
        decode(
            get(
                "/api/widget/config",
                null,
                "widgetToken" to widgetToken,
                "locale" to locale?.takeIf { it.isNotBlank() },
                "fresh" to if (fresh) UUID.randomUUID().toString() else null,
            ),
        ) { json ->
            if (CONFIG_KEYS.any(json::has)) WidgetConfig.parse(json) else null
        }

    /** `POST /api/widget/identity/verify`. */
    suspend fun verify(user: DeskwootUser): ApiResult<VerifyAnswer> =
        decode(postJson("/api/widget/identity/verify", JSONObject().put("widgetToken", widgetToken).put("user", user.toJson())), VerifyAnswer::parse)

    /** `GET /api/widget/conversations`: the conversation to show on start. */
    suspend fun restore(bearer: String, lastReadAt: String?): ApiResult<RestoreAnswer> =
        decode(get("/api/widget/conversations", bearer, "widgetToken" to widgetToken, "lastReadAt" to lastReadAt), RestoreAnswer::parse)

    /** `GET /api/widget/conversations?list=1`. */
    suspend fun conversationList(bearer: String, lastReadAt: String?): ApiResult<ListAnswer> =
        decode(
            get("/api/widget/conversations", bearer, "widgetToken" to widgetToken, "lastReadAt" to lastReadAt, "list" to "1"),
            ListAnswer::parse,
        )

    /** `GET /api/widget/messages`. [seen]: the visitor has this thread on screen right now. */
    suspend fun poll(bearer: String, conversationId: String, seen: Boolean): ApiResult<PollAnswer> =
        decode(
            get(
                "/api/widget/messages",
                bearer,
                "widgetToken" to widgetToken,
                "conversationId" to conversationId,
                "seen" to if (seen) "1" else "0",
            ),
            PollAnswer::parse,
        )

    /**
     * One try of `POST /api/widget/messages`. [uuid] is both the
     * `client_assigned_uuid` and the `Idempotency-Key` of every try, so the
     * server stores the message once.
     */
    suspend fun send(body: JSONObject, uuid: String, timeoutMillis: Int): ApiResult<SendAnswer> {
        val payload = withContext(work) {
            JSONObject(body.toString())
                .put("widgetToken", widgetToken)
                .put("client_assigned_uuid", uuid)
                .toString()
                .toByteArray(Charsets.UTF_8)
        }
        return decode(
            http.send(
                HttpRequest(
                    method = "POST",
                    url = url("/api/widget/messages"),
                    headers = mapOf("Content-Type" to JSON, "Idempotency-Key" to uuid),
                    body = payload,
                    timeoutMillis = timeoutMillis,
                ),
            ),
            SendAnswer::parse,
        )
    }

    /** `POST /api/widget/attachments`: one file, multipart; the upload writes the message row. */
    suspend fun upload(
        bearer: String,
        conversationId: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): ApiResult<UploadAnswer> {
        val boundary = "DeskwootBoundary" + UUID.randomUUID().toString().replace("-", "")
        val body = withContext(work) {
            Multipart(boundary)
                .field("widgetToken", widgetToken)
                .field("contactIdentifier", bearer)
                .field("conversationId", conversationId)
                .file("file", fileName, mimeType, bytes)
                .build()
        }
        return decode(
            http.send(
                HttpRequest(
                    method = "POST",
                    url = url("/api/widget/attachments"),
                    headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                    body = body,
                    // No fixed total deadline: 30 s without a byte sent ends a try, else 60 s plus 1 s per 20 KB.
                    timeoutMillis = HttpRequest.uploadTimeoutMillis(bytes.size),
                    onBodyProgress = onProgress,
                    stallTimeoutMillis = HttpRequest.UPLOAD_STALL_MILLIS,
                ),
            ),
            UploadAnswer::parse,
        )
    }

    /** `POST /api/widget/typing`: the dashboard sees the visitor typing. Its answer is ignored. */
    suspend fun typing(bearer: String, conversationId: String, isTyping: Boolean): ApiResult<Unit> =
        postJson(
            "/api/widget/typing",
            JSONObject()
                .put("widgetToken", widgetToken)
                .put("conversationId", conversationId)
                .put("contactIdentifier", bearer)
                .put("isTyping", isTyping),
        ).map { }

    /** `GET /api/widget/realtime-token`: a fresh Ably TokenRequest on every call; its nonce is single use. */
    suspend fun realtimeToken(bearer: String, conversationId: String): ApiResult<JSONObject> =
        decode(get("/api/widget/realtime-token", bearer, "widgetToken" to widgetToken, "conversationId" to conversationId)) { json ->
            json.takeIf { it.text("keyName") != null || it.string("token") != null }
        }

    /** `POST /api/widget/conversations/{id}/end`. */
    suspend fun end(bearer: String, conversationId: String): ApiResult<Unit> =
        decode(
            postJson(
                "/api/widget/conversations/${pathSegment(conversationId)}/end",
                JSONObject().put("widgetToken", widgetToken).put("contactIdentifier", bearer),
            ),
        ) { json -> if (json.isTrue("ok")) Unit else null }

    /** `POST /api/widget/conversations/{id}/resolved`: "Frage geklärt". */
    suspend fun resolved(bearer: String, conversationId: String): ApiResult<ResolvedAnswer> =
        decode(
            postJson(
                "/api/widget/conversations/${pathSegment(conversationId)}/resolved",
                JSONObject().put("widgetToken", widgetToken).put("contactIdentifier", bearer),
            ),
            ResolvedAnswer::parse,
        )

    /** `POST /api/widget/csat`: no widget token, the route checks the conversation's own contact. */
    suspend fun csat(bearer: String, conversationId: String, rating: Int, feedback: String): ApiResult<Unit> =
        decode(
            postJson(
                "/api/widget/csat",
                JSONObject()
                    .put("conversationId", conversationId)
                    .put("contactIdentifier", bearer)
                    .put("rating", rating)
                    .put("feedback", feedback),
            ),
        ) { json -> if (json.has("id")) Unit else null }

    /** `POST /api/widget/transcript`; [email] only once the server asked for one. */
    suspend fun transcript(bearer: String, conversationId: String, email: String?): ApiResult<Unit> {
        val body = JSONObject()
            .put("widgetToken", widgetToken)
            .put("conversationId", conversationId)
            .put("contactIdentifier", bearer)
        if (email != null) body.put("email", email)
        return decode(postJson("/api/widget/transcript", body)) { json -> if (json.isTrue("success")) Unit else null }
    }

    /**
     * `POST /api/widget/heartbeat` for an app: the inbox counts as installed.
     * It names no visitor. [appId] is the package name; [debug]
     * marks a debug build of the app, which the server leaves out.
     */
    suspend fun heartbeat(appId: String, sdkVersion: String, debug: Boolean): ApiResult<Unit> =
        postJson(
            "/api/widget/heartbeat",
            JSONObject()
                .put("widgetToken", widgetToken)
                .put("status", "loaded")
                .put(
                    "app",
                    JSONObject().put("platform", "android").put("id", appId).put("sdk", sdkVersion).put("debug", debug),
                ),
        ).map { }

    /**
     * `POST /api/widget/push/devices`: this phone takes the
     * visitor's replies as pushes. [token] is the app's FCM registration
     * token, [appId] its package name, [locale] the language of the push
     * texts (left out when null). 200 `{ ok: true }` also for an unknown
     * widget or bearer, which store nothing.
     */
    suspend fun registerPushDevice(bearer: String, token: String, appId: String, locale: String?): ApiResult<Unit> {
        val body = JSONObject()
            .put("widgetToken", widgetToken)
            .put("contactIdentifier", bearer)
            .put("platform", "android")
            .put("token", token)
            .put("appId", appId)
        if (locale != null) body.put("locale", locale)
        return decode(postJson("/api/widget/push/devices", body)) { json -> if (json.isTrue("ok")) Unit else null }
    }

    /**
     * `POST /api/widget/push/devices/remove`: the phone stops taking this
     * visitor's pushes (before a logout forgets the bearer). Always 200.
     * [inbox]: the widget token the registration was made for, also one the
     * app set up in an earlier launch.
     */
    suspend fun removePushDevice(bearer: String, token: String, inbox: String = widgetToken): ApiResult<Unit> =
        decode(
            postJson(
                "/api/widget/push/devices/remove",
                JSONObject().put("widgetToken", inbox).put("contactIdentifier", bearer).put("token", token),
            ),
        ) { json -> if (json.isTrue("ok")) Unit else null }

    /** Browse mode of `GET /api/widget/articles`. */
    suspend fun helpCollections(locale: String?, portalSlug: String?): ApiResult<List<HelpCollection>> =
        decode(get("/api/widget/articles", null, "widgetToken" to widgetToken, "locale" to locale, "portalSlug" to portalSlug)) { json ->
            json.array("categories")?.objects()?.mapNotNull(HelpCollection::parse)
        }

    /** One collection's articles. */
    suspend fun helpCollectionArticles(collectionId: String, locale: String?, portalSlug: String?): ApiResult<List<HelpArticleSummary>> =
        decode(
            get(
                "/api/widget/articles",
                null,
                "widgetToken" to widgetToken,
                "locale" to locale,
                "portalSlug" to portalSlug,
                "categoryId" to collectionId,
            ),
        ) { json -> json.array("categoryArticles")?.summaries() }

    /** Search: the parameter is `search`, never `q`. */
    suspend fun helpSearch(query: String, locale: String?, portalSlug: String?): ApiResult<List<HelpArticleSummary>> =
        decode(
            get(
                "/api/widget/articles",
                null,
                "widgetToken" to widgetToken,
                "locale" to locale,
                "portalSlug" to portalSlug,
                "search" to query,
            ),
        ) { json -> json.array("articles")?.summaries() }

    /** One article with its body. */
    suspend fun helpArticle(slug: String, locale: String?, portalSlug: String?): ApiResult<HelpArticle> =
        decode(
            get(
                "/api/widget/articles",
                null,
                "widgetToken" to widgetToken,
                "locale" to locale,
                "portalSlug" to portalSlug,
                "articleSlug" to slug,
            ),
        ) { json -> json.obj("article")?.let(HelpArticle::parse) }

    /** `POST /api/widget/articles/feedback` (204); the bearer makes it one vote per visitor, not per network. */
    suspend fun helpVote(slug: String, helpful: Boolean, bearer: String?): ApiResult<Unit> {
        val body = JSONObject()
            .put("widgetToken", widgetToken)
            .put("articleSlug", slug)
            .put("value", if (helpful) "HELPFUL" else "NOT_HELPFUL")
        if (bearer != null) body.put("contactIdentifier", bearer)
        return postJson("/api/widget/articles/feedback", body).map { }
    }

    /**
     * A link the server gave as a path (a signed file link) on the
     * API origin; an absolute http or https link stays as it is.
     */
    suspend fun absoluteUrl(link: String): String {
        val lower = link.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) return link
        return baseUrl() + (if (link.startsWith("/")) link else "/$link")
    }

    /** A success whose body lacks what the route promises is no usable answer; decoding runs on [work]. */
    private suspend fun <T> decode(result: ApiResult<JSONObject?>, parse: (JSONObject) -> T?): ApiResult<T> = when (result) {
        is ApiResult.Success -> {
            val json = result.value
            val value = if (json == null) null else withContext(work) { parse(json) }
            if (value == null) ApiResult.NetworkFailure(MalformedAnswer("The answer lacks what the route always sends")) else ApiResult.Success(value)
        }
        is ApiResult.Failure -> result
        is ApiResult.NetworkFailure -> result
    }

    private suspend fun get(path: String, bearer: String?, vararg query: Pair<String, String?>): ApiResult<JSONObject?> =
        http.send(
            HttpRequest(
                method = "GET",
                url = url(path, *query),
                headers = if (bearer != null) mapOf(CONTACT_HEADER to bearer) else emptyMap(),
            ),
        )

    private suspend fun postJson(path: String, body: JSONObject): ApiResult<JSONObject?> =
        http.send(
            HttpRequest(
                method = "POST",
                url = url(path),
                headers = mapOf("Content-Type" to JSON),
                body = body.toString().toByteArray(Charsets.UTF_8),
            ),
        )

    private suspend fun url(path: String, vararg query: Pair<String, String?>): String {
        val base = baseUrl()
        val params = query.filter { it.second != null }
        if (params.isEmpty()) return base + path
        return base + path + "?" + params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v.orEmpty())}" }
    }

    private fun JSONArray.summaries(): List<HelpArticleSummary> = objects().mapNotNull(HelpArticleSummary::parse)

    internal companion object {
        const val JSON: String = "application/json"

        /** The visitor's bearer on every GET. */
        const val CONTACT_HEADER: String = "X-Deskwoot-Contact"

        /** A config always names at least one of these; anything else is not a config. */
        private val CONFIG_KEYS = listOf("brandColor", "inboxName", "businessName", "widgetLocale")

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun pathSegment(value: String): String = encode(value)
    }
}

/** A multipart/form-data body (RFC 7578) for one upload: text fields and one file part. */
internal class Multipart(private val boundary: String) {
    private val out = java.io.ByteArrayOutputStream()

    fun field(name: String, value: String): Multipart {
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"${quote(name)}\"\r\n\r\n")
        write(value)
        write("\r\n")
        return this
    }

    fun file(name: String, fileName: String, mimeType: String, bytes: ByteArray): Multipart {
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"${quote(name)}\"; filename=\"${quote(fileName)}\"\r\n")
        // The part's type is the MIME the server checks against the inbox's list.
        write("Content-Type: $mimeType\r\n\r\n")
        out.write(bytes)
        write("\r\n")
        return this
    }

    fun build(): ByteArray {
        write("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

    /** Quotes and line breaks would end the header early; the HTML form encoding replaces them. */
    private fun quote(value: String): String =
        value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")
}
