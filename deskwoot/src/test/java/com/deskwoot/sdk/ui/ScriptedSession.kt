package com.deskwoot.sdk.ui

import android.os.Looper
import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.FakeHttp
import com.deskwoot.sdk.FakeRealtime
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.api.FileFetcher
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.engine.ConversationEngine
import com.deskwoot.sdk.engine.DeviceClock
import com.deskwoot.sdk.engine.UploadRead
import com.deskwoot.sdk.help.HelpCenterClient
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.SilentLogger
import com.deskwoot.sdk.store.MemoryDiskCache
import com.deskwoot.sdk.store.MemoryVisitorStore
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real [ChatSession] (engine, help center client, file fetcher) over a
 * scripted server, on the Robolectric main looper: the screens and the
 * Activity run against it as against the real one.
 *
 * The help center answers [collections], the articles of each collection,
 * [articles] by slug and [searchHits]; files are [files] by absolute URL.
 */
internal class ScriptedSession(
    @Volatile var config: JSONObject = helpConfig(),
    val token: String = "wt_scripted_" + COUNTER.incrementAndGet(),
    record: VisitorRecord = VisitorRecord(),
) {
    val http = FakeHttp()
    val realtime = FakeRealtime()
    val store = MemoryVisitorStore(record)
    val files = HashMap<String, ByteArray>()

    /** Thrown by the file fetcher instead of an answer. */
    @Volatile
    var fetchThrows: Throwable? = null
    val fetched: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    var collections: List<JSONObject> = listOf(
        json("id" to "col1", "name" to "Billing", "description" to "Plans and invoices", "articleCount" to 2),
        json("id" to "col2", "name" to "Shipping", "description" to null, "articleCount" to 1),
    )
    var collectionArticles: Map<String, List<JSONObject>> = mapOf(
        "col1" to listOf(summary("change-plan", "How to change your plan"), summary("refunds", "Refunds")),
        "col2" to listOf(summary("returns", "Returns")),
    )
    val articles = HashMap<String, JSONObject>().apply {
        put("change-plan", article("change-plan", "How to change your plan", "<p>Open <a href=\"https://help.acme.test/hc/refunds\">Refunds</a> or <a href=\"https://shop.example/x\">the shop</a>.</p>"))
        put("refunds", article("refunds", "Refunds", "<p>Money back within 14 days.</p>"))
        put("returns", article("returns", "Returns", "<p>Send it back.</p>"))
    }
    var searchHits: List<JSONObject> = emptyList()

    private val api = WidgetApi(http, { BASE_URL }, token, Dispatchers.Main.immediate)
    val engine = ConversationEngine(
        api = api,
        realtime = realtime,
        store = store,
        cache = MemoryDiskCache(null),
        clock = DeviceClock,
        logger = SilentLogger,
        mainDispatcher = Dispatchers.Main.immediate,
        postDispatcher = Dispatchers.Main,
        appLanguage = { "en" },
        uploadReader = { _, _ -> UploadRead.TooLarge },
        appId = "com.example.shop",
        sdkVersion = "1.0.0",
        initialForeground = true,
        // As in an app: a push handed over on a worker thread reads a snapshot, never the state itself.
        onMainThread = { Looper.myLooper() == Looper.getMainLooper() },
    )
    val session = ChatSession(
        token,
        engine,
        HelpCenterClient(api, engine::helpContext),
        api,
        FileFetcher { url, max ->
            fetched += url
            fetchThrows?.let { throw it }
            files[url]?.takeIf { it.size <= max }
        },
    )

    init {
        http.on("GET /api/widget/config") { ok(config) }
        http.on("GET /api/widget/conversations") { call ->
            if (call.query["list"] == "1") ok(json("conversations" to emptyList<Any>())) else ok(json("conversationId" to null))
        }
        http.on("GET /api/widget/messages") { ok(com.deskwoot.sdk.pollAnswer()) }
        http.on("POST /api/widget/typing") { ok(json("ok" to true)) }
        http.on("POST /api/widget/heartbeat") { ok(null) }
        http.on("POST /api/widget/articles/feedback") { ok(null) }
        http.on("GET /api/widget/articles") { call ->
            val q = call.query
            when {
                q["articleSlug"] != null -> articles[q["articleSlug"]]?.let { ok(json("article" to it)) } ?: com.deskwoot.sdk.fail(404)
                q["categoryId"] != null -> ok(json("categoryArticles" to collectionArticles[q["categoryId"]].orEmpty()))
                q["search"] != null -> ok(json("articles" to searchHits))
                else -> ok(json("categories" to collections))
            }
        }
    }

    /** Makes `Deskwoot.setup` use this session and runs it; the facade then holds it as a real app's would. */
    fun install(): ScriptedSession {
        Deskwoot.makeSession = { _, _, _ -> session }
        Deskwoot.setup(RuntimeEnvironment.getApplication(), token)
        idle()
        return this
    }

    companion object {
        private val COUNTER = AtomicInteger()

        /** A config with a help center at https://help.acme.test/hc. */
        fun helpConfig(vararg overrides: Pair<String, Any?>): JSONObject = baseConfig(
            "helpCenterEnabled" to true,
            "helpCenterPortalSlug" to "acme",
            "helpCenterBaseUrl" to "https://help.acme.test/hc",
            *overrides,
        )

        fun summary(slug: String, title: String): JSONObject = json("id" to "a_$slug", "title" to title, "slug" to slug, "description" to "About $title")

        fun article(slug: String, title: String, content: String): JSONObject =
            json("id" to "a_$slug", "title" to title, "slug" to slug, "locale" to "en", "content" to content, "authorName" to "Anna Keller")

        /** Runs what is due on the main looper, a few rounds, giving IO work in between a moment to finish. */
        fun idle(rounds: Int = 3) {
            repeat(rounds) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        /** Idles until [done] or about 2 seconds passed. */
        fun idleUntil(done: () -> Boolean): Boolean {
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (done()) return true
                Thread.sleep(10)
            }
            return done()
        }
    }
}
