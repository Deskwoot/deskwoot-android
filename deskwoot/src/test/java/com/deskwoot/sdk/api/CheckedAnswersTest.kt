package com.deskwoot.sdk.api

import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.FakeHttp
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.parseJsonObject
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.engine.HelpContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Answers are checked before anything uses them: an answer without the
 * route's keys counts as no answer, never as a success. The tests run on
 * Android's own org.json (the
 * AOSP copy on the test classpath, see build.gradle.kts), so they see what
 * a phone does with hostile JSON.
 */
class CheckedAnswersTest {
    private fun api(http: HttpClient, work: kotlinx.coroutines.CoroutineDispatcher) = WidgetApi(http, { BASE_URL }, TOKEN, work)

    @Test
    fun `the tests run on Android's org json, not the reference one`() {
        // Android's optString turns a JSON null into the text "null"; the reference library answers "".
        assertEquals("null", JSONObject("{\"a\":null}").optString("a"))
        // Android takes NaN in an array and then cannot print the object.
        val withNaN = JSONObject("{\"a\":[NaN]}")
        val printed: String? = withNaN.toString()
        assertNull(printed)
    }

    @Test
    fun `hostile JSON is refused before Android's parser sees it`() {
        // Deeper than 64 levels: Android's recursive parser would overflow the stack on a deep enough one.
        assertNull(parseJsonObject("{\"a\":" + "[".repeat(200_000) + "]".repeat(200_000) + "}"))
        assertNull(parseJsonObject("{\"a\":" + "[".repeat(64) + "]".repeat(64) + "}"))
        assertNotNull(parseJsonObject("{\"a\":" + "[".repeat(63) + "]".repeat(63) + "}"))
        // NaN and Infinity are not JSON, whatever Android accepts.
        assertNull(parseJsonObject("{\"a\":[NaN]}"))
        assertNull(parseJsonObject("{\"a\":[1,Infinity]}"))
        // Millions of tiny elements fit in 16 MiB; they are refused.
        assertNull(parseJsonObject("{\"a\":[" + "0,".repeat(300_000) + "0]}"))
        // Ordinary JSON with escapes, exponents and words passes.
        val fine = parseJsonObject("{\"t\":\"a \\\"q\\\" [ { \\u00e9\",\"n\":-1.5e3,\"b\":[true,false,null]}")
        assertEquals(-1500.0, checkNotNull(fine).getDouble("n"), 0.0)
        assertNull(parseJsonObject("{\"a\":1} trailing"))
    }

    @Test
    fun `a row is never printed as null`() {
        val message = checkNotNull(Message.parse(row("m1")))
        assertTrue(message.rawJson.startsWith("{"))
    }

    @Test
    fun `a 2xx must be a JSON object or empty, a redirect is no answer`() {
        assertTrue(UrlConnectionHttpClient.interpret(200, "<html>Sign in to the Wi-Fi</html>", null) is ApiResult.NetworkFailure)
        assertTrue(UrlConnectionHttpClient.interpret(200, "[1,2]", null) is ApiResult.NetworkFailure)
        assertTrue(UrlConnectionHttpClient.interpret(200, "{\"a\":" + "[".repeat(100) + "]".repeat(100) + "}", null) is ApiResult.NetworkFailure)
        assertEquals(ApiResult.Success(null), UrlConnectionHttpClient.interpret(204, "", null))
        assertTrue(UrlConnectionHttpClient.interpret(302, "", null) is ApiResult.NetworkFailure)
        assertTrue(UrlConnectionHttpClient.interpret(301, "", null) is ApiResult.NetworkFailure)
        val html = UrlConnectionHttpClient.interpret(403, "<html>Attention required</html>", null) as ApiResult.Failure
        assertFalse(html.error.hasJsonBody)
        val api = UrlConnectionHttpClient.interpret(401, "{\"error\":\"invalid_identifier_hash\"}", null) as ApiResult.Failure
        assertTrue(api.error.hasJsonBody)
    }

    @Test
    fun `every route checks the keys it always sends`() = runTest {
        val http = FakeHttp()
        val api = api(http, StandardTestDispatcher(testScheduler))
        http.on("GET /api/widget/config") { ok(json("ok" to true)) }
        http.on("POST /api/widget/identity/verify") { ok(json("attributesIgnored" to null)) }
        http.on("GET /api/widget/conversations") { ok(JSONObject()) }
        http.on("GET /api/widget/messages") { ok(json("resolved" to false)) }
        http.on("POST /api/widget/messages") { ok(json("messageId" to "m1")) }
        http.on("GET /api/widget/realtime-token") { ok(JSONObject()) }
        http.on("POST /api/widget/conversations/c1/end") { ok(json("ok" to false)) }
        http.on("POST /api/widget/conversations/c1/resolved") { ok(json("alreadyResolved" to false)) }
        http.on("POST /api/widget/csat") { ok(null) }
        http.on("POST /api/widget/transcript") { ok(JSONObject()) }
        http.on("POST /api/widget/attachments") { ok(json("conversationId" to "c1")) }

        assertTrue(api.config(null) is ApiResult.NetworkFailure)
        assertTrue(api.verify(DeskwootUser("u", "a".repeat(64))) is ApiResult.NetworkFailure)
        assertTrue(api.restore("wb", null) is ApiResult.NetworkFailure)
        assertTrue(api.conversationList("wb", null) is ApiResult.NetworkFailure)
        assertTrue(api.poll("wb", "c1", true) is ApiResult.NetworkFailure)
        assertTrue(api.send(JSONObject(), "u1", 1_000) is ApiResult.NetworkFailure)
        assertTrue(api.realtimeToken("wb", "c1") is ApiResult.NetworkFailure)
        assertTrue(api.end("wb", "c1") is ApiResult.NetworkFailure)
        assertTrue(api.resolved("wb", "c1") is ApiResult.NetworkFailure)
        assertTrue(api.csat("wb", "c1", 5, "") is ApiResult.NetworkFailure)
        assertTrue(api.transcript("wb", "c1", null) is ApiResult.NetworkFailure)
        assertTrue(api.upload("wb", "c1", ByteArray(1), "a.png", "image/png") is ApiResult.NetworkFailure)

        // The same routes with what they promise.
        http.on("GET /api/widget/conversations") { ok(json("conversationId" to null)) }
        http.on("GET /api/widget/messages") { ok(pollAnswer(row("m1"))) }
        http.on("POST /api/widget/messages") { ok(json("messageId" to "m1", "contactIdentifier" to "wb_x")) }
        http.on("GET /api/widget/realtime-token") { ok(json("keyName" to "k", "nonce" to "n")) }
        http.on("POST /api/widget/conversations/c1/end") { ok(json("ok" to true)) }
        assertTrue(api.restore("wb", null) is ApiResult.Success)
        assertTrue(api.poll("wb", "c1", true) is ApiResult.Success)
        assertTrue(api.send(JSONObject(), "u1", 1_000) is ApiResult.Success)
        assertTrue(api.realtimeToken("wb", "c1") is ApiResult.Success)
        assertTrue(api.end("wb", "c1") is ApiResult.Success)
    }

    @Test
    fun `GETs carry the bearer in a header, the heartbeat says whether the app is a debug build`() = runTest {
        val http = FakeHttp()
        val api = api(http, StandardTestDispatcher(testScheduler))
        http.on("GET /api/widget/messages") { ok(pollAnswer()) }
        http.on("POST /api/widget/heartbeat") { ok(null) }
        api.poll("wb_secret", "c1", seen = false)
        val poll = http.calls.single()
        assertEquals("wb_secret", poll.headers[WidgetApi.CONTACT_HEADER])
        assertFalse(poll.request.url.contains("wb_secret"))

        api.heartbeat("com.example.shop", "1.0.0", debug = true)
        val app = checkNotNull(http.calls.last().body).getJSONObject("app")
        assertEquals("android", app.getString("platform"))
        assertEquals("com.example.shop", app.getString("id"))
        assertTrue(app.getBoolean("debug"))
    }

    /** An answer that notes the thread every key of it is read on. */
    private class SpyJson(text: String, private val threads: MutableList<String>) : JSONObject(text) {
        override fun opt(name: String?): Any? {
            threads += Thread.currentThread().name
            return super.opt(name)
        }
    }

    @Test
    fun `answers are decoded on the work dispatcher, never on the caller's thread`() {
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor { Thread(it, "deskwoot-work") }
        val work = executor.asCoroutineDispatcher()
        try {
            val http = object : HttpClient {
                override suspend fun send(request: HttpRequest): ApiResult<JSONObject?> =
                    ApiResult.Success(SpyJson(pollAnswer(row("m1")).toString(), threads))
            }
            val answer = runBlocking { api(http, work).poll("wb", "c1", seen = true) }
            assertTrue(answer is ApiResult.Success)
            assertTrue(threads.isNotEmpty())
            // Debug builds of kotlinx.coroutines add " @coroutine#n" to the name.
            assertTrue("read on ${threads.toSet()}", threads.all { it.startsWith("deskwoot-work") })
        } finally {
            work.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `the timeout limits the whole call, not only the wait for the next byte`() = runTest {
        // A connection that trickles a byte every second forever.
        val client = object : UrlConnectionHttpClient("test", StandardTestDispatcher(testScheduler)) {
            override suspend fun exchange(request: HttpRequest): ApiResult<JSONObject?> {
                while (true) delay(1_000)
            }
        }
        val started = testScheduler.currentTime
        val result = client.send(HttpRequest("POST", "$BASE_URL/api/widget/messages", timeoutMillis = 30_000))
        assertTrue(result is ApiResult.NetworkFailure)
        assertTrue((result as ApiResult.NetworkFailure).cause is SocketTimeoutException)
        assertEquals(30_000, testScheduler.currentTime - started)
    }

    @Test
    fun `types that hold a bearer never print it`() {
        val record = VisitorRecord(bearer = "wb_secret", verifiedExternalId = "u_1")
        assertFalse(record.toString().contains("wb_secret"))
        assertTrue(record.toString().contains("u_1"))
        val send = checkNotNull(SendAnswer.parse(json("messageId" to "m1", "contactIdentifier" to "wb_secret")))
        assertFalse(send.toString().contains("wb_secret"))
        val verify = checkNotNull(VerifyAnswer.parse(json("contactIdentifier" to "wb_secret")))
        assertFalse(verify.toString().contains("wb_secret"))
        val help = HelpContext(null, null, null, true, emptyList(), null, "wb_secret")
        assertFalse(help.toString().contains("wb_secret"))
        assertFalse(DeskwootUser("u_1", "a".repeat(64)).toString().contains("a".repeat(64)))
    }
}
