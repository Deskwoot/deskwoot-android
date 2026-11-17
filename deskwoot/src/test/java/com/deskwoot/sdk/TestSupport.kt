package com.deskwoot.sdk

import com.deskwoot.sdk.api.ApiError
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.api.HttpClient
import com.deskwoot.sdk.api.HttpRequest
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.Clock
import com.deskwoot.sdk.engine.ConversationEngine
import com.deskwoot.sdk.engine.UploadRead
import com.deskwoot.sdk.engine.UploadSource
import com.deskwoot.sdk.model.IsoTime
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.realtime.RealtimeClient
import com.deskwoot.sdk.realtime.RealtimeConnection
import com.deskwoot.sdk.realtime.RealtimeEvent
import com.deskwoot.sdk.realtime.RealtimeListener
import com.deskwoot.sdk.store.CacheSnapshot
import com.deskwoot.sdk.store.MemoryDiskCache
import com.deskwoot.sdk.store.MemoryVisitorStore
import com.deskwoot.sdk.store.PushRemoval
import com.deskwoot.sdk.store.StoreQueue
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import kotlin.random.Random

internal const val BASE_URL = "https://test.deskwoot.invalid"
internal const val TOKEN = "wt_test"

/** 2026-11-16T10:00:00.000Z: virtual time 0 of every test. */
internal val BASE_TIME: Long = checkNotNull(IsoTime.parse("2026-11-16T10:00:00.000Z"))

/**
 * Both clocks on virtual time: the monotonic one starts at 0, the device
 * clock at [BASE_TIME] plus [deviceSkewMillis], which a test may change to
 * make the device clock wrong or jump.
 */
internal class VirtualClock(private val scheduler: TestCoroutineScheduler, var deviceSkewMillis: Long = 0) : Clock {
    override fun now(): Long = BASE_TIME + deviceSkewMillis + scheduler.currentTime

    override fun elapsed(): Long = scheduler.currentTime
}

internal fun virtualClock(scheduler: TestCoroutineScheduler, deviceSkewMillis: Long = 0): VirtualClock = VirtualClock(scheduler, deviceSkewMillis)

/** A JSON object from pairs; maps and lists nest, null becomes JSON null. */
internal fun json(vararg pairs: Pair<String, Any?>): JSONObject {
    val out = JSONObject()
    for ((key, value) in pairs) out.put(key, toJson(value))
    return out
}

internal fun jsonList(vararg items: Any?): JSONArray = JSONArray().also { array -> items.forEach { array.put(toJson(it)) } }

private fun toJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), toJson(v)) } }
    is List<*> -> JSONArray().also { a -> value.forEach { a.put(toJson(it)) } }
    else -> value
}

internal fun ok(body: JSONObject? = JSONObject()): ApiResult<JSONObject?> = ApiResult.Success(body)

/** A send's answer. */
internal fun sent(
    conversationId: String = "c1",
    bearer: String = "wb_minted",
    botWillReply: Boolean = false,
    newConversation: Boolean = false,
    reopened: Boolean = false,
    emailKnown: Boolean = false,
): ApiResult<JSONObject?> {
    val body = json(
        "messageId" to "m-${conversationId}",
        "conversationId" to conversationId,
        "reopened" to reopened,
        "newConversation" to newConversation,
        "botWillReply" to botWillReply,
        "contactIdentifier" to bearer,
    )
    if (emailKnown) body.put("emailKnown", true)
    return ok(body)
}

internal fun fail(status: Int, body: JSONObject? = null, retryAfter: String? = null): ApiResult<JSONObject?> =
    ApiResult.Failure(ApiError.from(status, body, retryAfter))

internal fun offline(): ApiResult<JSONObject?> = ApiResult.NetworkFailure(IOException("offline"))

internal fun iso(offsetSeconds: Long): String = IsoTime.format(BASE_TIME + offsetSeconds * 1_000)

/** A server row. */
internal fun row(
    id: String,
    type: String = "OUTGOING",
    content: String? = "Hello",
    at: Long = 0,
    conversationId: String? = "c1",
    attrs: JSONObject? = null,
    sender: JSONObject? = null,
    attachments: JSONArray? = null,
): JSONObject {
    val out = JSONObject()
        .put("id", id)
        .put("messageType", type)
        .put("content", content ?: JSONObject.NULL)
        .put("createdAt", iso(at))
        .put("contentAttributes", attrs ?: JSONObject.NULL)
    if (conversationId != null) out.put("conversationId", conversationId)
    if (sender != null) out.put("sender", sender)
    out.put("attachments", attachments ?: JSONArray())
    return out
}

/** A poll answer. */
internal fun pollAnswer(
    vararg rows: JSONObject,
    resolved: Boolean = false,
    showCsatSurvey: Boolean = false,
    queue: Int? = null,
    waiting: Boolean = false,
    closed: Boolean = false,
    hasAgentReply: Boolean? = null,
    continuesAfterResolve: Boolean = false,
): JSONObject {
    val out = json(
        "messages" to JSONArray().also { a -> rows.forEach { a.put(it) } },
        "resolved" to resolved,
        "showCsatSurvey" to showCsatSurvey,
        "queue" to queue?.let { mapOf("position" to it) },
        "waiting" to waiting,
        "continuesAfterResolve" to continuesAfterResolve,
    )
    if (closed) out.put("closed", true)
    if (hasAgentReply != null) out.put("hasAgentReply", hasAgentReply)
    return out
}

/** A config with no step before the chat, no bot, and every feature on. */
internal fun baseConfig(vararg overrides: Pair<String, Any?>): JSONObject {
    val config = json(
        "businessName" to "Acme",
        "inboxName" to "Support",
        "brandColor" to "#112233",
        "widgetLocale" to "auto",
        "greeting" to "Hi there",
        "replyTime" to "in_a_few_minutes",
        "preChatFormEnabled" to false,
        "preChatFormFields" to mapOf("fields" to emptyList<Any>()),
        "letBotCollectPreChatFields" to false,
        "emailCollectEnabled" to false,
        "available" to true,
        "featureFilePicker" to true,
        "allowedFileTypes" to listOf("image/*", "application/pdf"),
        "maxUploadBytes" to 1000,
        "featureEndConversation" to true,
        "featureTranscriptEmail" to true,
        "continuousChat" to false,
        "persistentConversations" to false,
        "csat" to mapOf("enabled" to true, "ratingStyle" to "STARS", "promptText" to null),
        "bot" to mapOf("enabled" to false, "mode" to "SUPPORT", "agentBotId" to null, "name" to null),
    )
    for ((key, value) in overrides) config.put(key, toJson(value))
    return config
}

internal val BOT_ON: Pair<String, Any?> = "bot" to mapOf("enabled" to true, "mode" to "SUPPORT", "agentBotId" to "bot1", "name" to "Fynn")

/** One recorded HTTP call, with its query and JSON body decoded. */
internal class RecordedCall(
    val request: HttpRequest,
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Virtual time of the call, in milliseconds since the test began. */
    val at: Long,
) {
    val body: JSONObject? = request.body?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
    val headers: Map<String, String> get() = request.headers

    /** The bearer a GET names, in its header. */
    val contact: String? get() = request.headers[WidgetApi.CONTACT_HEADER]
    val key: String get() = "$method $path"
}

/**
 * A scripted [HttpClient]: answers come from one shot handlers queued per
 * `METHOD /path`, else from a standing default. Every call is recorded.
 */
internal class FakeHttp(private val now: () -> Long = { 0L }) : HttpClient {
    val calls = mutableListOf<RecordedCall>()
    private val queued = mutableMapOf<String, ArrayDeque<suspend (RecordedCall) -> ApiResult<JSONObject?>>>()
    private val defaults = mutableMapOf<String, suspend (RecordedCall) -> ApiResult<JSONObject?>>()

    fun on(key: String, handler: suspend (RecordedCall) -> ApiResult<JSONObject?>) {
        defaults[key] = handler
    }

    fun once(key: String, handler: suspend (RecordedCall) -> ApiResult<JSONObject?>) {
        queued.getOrPut(key) { ArrayDeque() }.addLast(handler)
    }

    fun calls(key: String): List<RecordedCall> = calls.filter { it.key == key }

    override suspend fun send(request: HttpRequest): ApiResult<JSONObject?> {
        val withoutScheme = request.url.substringAfter("://")
        val pathAndQuery = "/" + withoutScheme.substringAfter('/', "")
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
        val call = RecordedCall(request, request.method, path, query, now())
        calls += call
        val handler = queued[call.key]?.removeFirstOrNull() ?: defaults[call.key]
            ?: error("No answer scripted for ${call.key}")
        return handler(call)
    }
}

/** A [RealtimeClient] the test drives by hand. */
internal class FakeRealtime : RealtimeClient {
    inner class Subscription(
        val channel: String,
        val auth: suspend () -> ApiResult<JSONObject>,
        val listener: RealtimeListener,
        val onStopped: () -> Unit,
    ) {
        var closed = false

        /** Realtime refused this channel for good, as the real client reports it. */
        fun stop() {
            closed = true
            onStopped()
        }
    }

    val opened = mutableListOf<Subscription>()

    val current: Subscription? get() = opened.lastOrNull { !it.closed }

    override fun open(
        channel: String,
        auth: suspend () -> ApiResult<JSONObject>,
        listener: RealtimeListener,
        onStopped: () -> Unit,
    ): RealtimeConnection {
        val subscription = Subscription(channel, auth, listener, onStopped)
        opened += subscription
        return RealtimeConnection { subscription.closed = true }
    }

    fun emit(name: String, data: JSONObject) {
        checkNotNull(current) { "No open realtime subscription" }.listener.onEvent(RealtimeEvent(name, data))
    }
}

/**
 * The engine on virtual time with a fake server, a fake realtime client
 * and in memory storage. Ids are predictable: the n-th id is
 * `00000000-0000-4000-8000-00000000000n`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Harness(
    private val test: TestScope,
    config: JSONObject = baseConfig(),
    record: VisitorRecord = VisitorRecord(),
    cached: CacheSnapshot? = null,
    appLanguage: String? = "de-DE",
    language: String? = null,
    /** The device clock runs this far ahead of the server's. */
    deviceSkewMillis: Long = 0,
    /** Ids as some platforms make them, in capitals: the engine must lower case them. */
    upperCaseIds: Boolean = false,
    initialForeground: Boolean = true,
    /** Push removals of this inbox a run before this one left in the store (written at virtual time 0), oldest first. */
    pushRemovals: List<String> = emptyList(),
    /** Push removals of any inbox a run before this one left in the store, oldest first; after [pushRemovals]. */
    removals: List<PushRemoval> = emptyList(),
    /** Whether a call runs on the session's thread; a test of a worker thread names it. */
    onMainThread: () -> Boolean = { true },
    /** Another harness's store queue: two sessions of one process, one after the other. */
    sharedQueue: StoreQueue? = null,
) {
    val http = FakeHttp { test.testScheduler.currentTime }
    val realtime = FakeRealtime()

    /** The restore lookup's answer; the list (`list=1`) has its own. */
    var restoreAnswer: () -> ApiResult<JSONObject?> = { ok(json("conversationId" to null)) }
    var listAnswer: () -> ApiResult<JSONObject?> = { ok(json("conversations" to emptyList<Any>())) }
    val store = MemoryVisitorStore(record, pushRemovals.map { PushRemoval(TOKEN, it, BASE_TIME) } + removals)
    val cache = MemoryDiskCache(cached)
    private var ids = 0
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    val clock = virtualClock(test.testScheduler, deviceSkewMillis)

    /** The store queue of this test: on virtual time, and, as in the app, no stop of the engine cancels it. */
    val storeQueue = sharedQueue ?: StoreQueue(CoroutineScope(SupervisorJob(test.backgroundScope.coroutineContext[Job]) + dispatcher))

    val engine = ConversationEngine(
        api = WidgetApi(http, { BASE_URL }, TOKEN, dispatcher),
        realtime = realtime,
        store = store,
        cache = cache,
        clock = clock,
        logger = SilentLogger,
        mainDispatcher = dispatcher,
        postDispatcher = dispatcher,
        appLanguage = { appLanguage },
        uploadReader = { source, max ->
            val bytes = (source as UploadSource.Bytes).data
            if (bytes.size > max) UploadRead.TooLarge else UploadRead.Data(bytes)
        },
        appId = "com.example.shop",
        sdkVersion = "1.0.0",
        initialLanguage = language,
        initialForeground = initialForeground,
        newUuid = { if (upperCaseIds) "ABCDEF00-0000-4000-8000-%012d".format(++ids) else uuid(++ids) },
        random = Random(7),
        parentJob = test.backgroundScope.coroutineContext[Job],
        onMainThread = onMainThread,
        storeQueue = storeQueue,
    )

    init {
        http.on("GET /api/widget/config") { ok(config) }
        http.on("GET /api/widget/conversations") { call -> if (call.query["list"] == "1") listAnswer() else restoreAnswer() }
        http.on("GET /api/widget/messages") { ok(pollAnswer()) }
        http.on("POST /api/widget/typing") { ok(json("ok" to true)) }
        http.on("POST /api/widget/heartbeat") { ok(null) }
    }

    /** The published state after everything due now has run. */
    val state: SessionState
        get() {
            test.runCurrent()
            return engine.state.value
        }

    val thread: ThreadState get() = checkNotNull(state.thread) { "No thread yet" }

    fun settle() = test.runCurrent()

    fun advance(millis: Long) {
        test.advanceTimeBy(millis)
        test.runCurrent()
    }

    fun start() {
        engine.start()
        settle()
    }

    // The calls so far, after everything due now has run: actions answer at once and deliver in
    // the background.
    fun polls(): List<RecordedCall> = settled { http.calls("GET /api/widget/messages") }

    fun sends(): List<RecordedCall> = settled { http.calls("POST /api/widget/messages") }

    fun restores(): List<RecordedCall> = settled { http.calls("GET /api/widget/conversations").filter { it.query["list"] != "1" } }

    private fun <T> settled(read: () -> T): T {
        settle()
        return read()
    }

    /** The visitor has an open conversation [id] on screen. */
    fun openThread(id: String = "c1", bearer: String = "wb_known") {
        restoreAnswer = { ok(json("conversationId" to id, "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0)) }
        check(store.record.bearer == bearer) { "Start the harness with VisitorRecord(bearer = \"$bearer\")" }
        start()
        engine.setVisible(true)
        settle()
    }

    companion object {
        fun uuid(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)
    }
}
