package com.deskwoot.sdk.api

import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.TOKEN
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Uploads have no fixed total deadline: a try ends after 30 s without any
 * byte sent, or after 60 s plus 1 s per 20 KB of the file, whichever comes
 * first. Other requests keep their limits.
 */
class UploadDeadlineTest {
    private val slice = UrlConnectionHttpClient.BODY_SLICE_BYTES

    /**
     * A client whose connection writes the body in slices, one every [sliceEvery]
     * ms (forever when [stallAfter] slices went out), then answers after [answerAfter] ms.
     */
    private fun client(scope: TestScope, sliceEvery: Long, answerAfter: Long = 0, stallAfter: Int = Int.MAX_VALUE) =
        object : UrlConnectionHttpClient("test", StandardTestDispatcher(scope.testScheduler), { scope.testScheduler.currentTime }) {
            override suspend fun exchange(request: HttpRequest): ApiResult<JSONObject?> {
                val total = checkNotNull(request.body).size.toLong()
                var sent = 0L
                var slices = 0
                while (sent < total) {
                    if (slices == stallAfter) while (true) delay(1_000)
                    delay(sliceEvery)
                    sent = minOf(total, sent + slice)
                    slices++
                    request.onBodyProgress?.invoke(sent, total)
                }
                delay(answerAfter)
                return ApiResult.Success(JSONObject().put("messageId", "f1"))
            }
        }

    private fun upload(fileBytes: Int, progress: ((Long, Long) -> Unit)? = null) = HttpRequest(
        method = "POST",
        url = "$BASE_URL/api/widget/attachments",
        body = ByteArray(fileBytes),
        timeoutMillis = HttpRequest.uploadTimeoutMillis(fileBytes),
        onBodyProgress = progress,
        stallTimeoutMillis = HttpRequest.UPLOAD_STALL_MILLIS,
    )

    @Test
    fun `the limit grows with the file, 60 s plus 1 s per 20 KB`() {
        assertEquals(60_000, HttpRequest.uploadTimeoutMillis(0))
        assertEquals(61_000, HttpRequest.uploadTimeoutMillis(20 * 1024))
        // A 5 MB file: about 5 minutes.
        assertEquals(316_000, HttpRequest.uploadTimeoutMillis(5 * 1024 * 1024))
    }

    @Test
    fun `a weak but working link uploads for longer than a minute`() = runTest {
        // 1 MB at one 32 KB slice every 3 s: 96 s of sending, under its limit of 111.2 s.
        val bytes = 1024 * 1024
        var reported = 0L
        val started = testScheduler.currentTime
        val result = client(this, sliceEvery = 3_000).send(upload(bytes) { sent, _ -> reported = sent })
        assertTrue(result is ApiResult.Success)
        assertEquals(bytes.toLong(), reported)
        assertEquals(96_000, testScheduler.currentTime - started)
    }

    @Test
    fun `30 s without any byte sent ends the try`() = runTest {
        val started = testScheduler.currentTime
        // Two slices at 10 s and 20 s, then nothing.
        val result = client(this, sliceEvery = 10_000, stallAfter = 2).send(upload(1024 * 1024))
        assertTrue(result is ApiResult.NetworkFailure)
        assertTrue((result as ApiResult.NetworkFailure).cause is SocketTimeoutException)
        assertEquals("30 s after the last byte", 50_000, testScheduler.currentTime - started)

        // Not even connected: 30 s from the start.
        val again = testScheduler.currentTime
        val none = client(this, sliceEvery = 1_000, stallAfter = 0).send(upload(1024 * 1024))
        assertTrue(none is ApiResult.NetworkFailure)
        assertEquals(30_000, testScheduler.currentTime - again)
    }

    @Test
    fun `bytes that keep trickling end at the limit of the file`() = runTest {
        // A slice every 20 s never stalls, but 32 slices would take 640 s: the limit ends it at 111.2 s.
        val started = testScheduler.currentTime
        val result = client(this, sliceEvery = 20_000).send(upload(1024 * 1024))
        assertTrue(result is ApiResult.NetworkFailure)
        assertEquals(HttpRequest.uploadTimeoutMillis(1024 * 1024).toLong(), testScheduler.currentTime - started)
    }

    @Test
    fun `once the whole body is out, the wait for the answer is not a stall`() = runTest {
        // The server scans the file for 45 s before it answers.
        val result = client(this, sliceEvery = 100, answerAfter = 45_000).send(upload(64 * 1024))
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `the upload names its limits, other requests keep theirs and have no stall rule`() = runTest {
        val seen = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override suspend fun send(request: HttpRequest): ApiResult<JSONObject?> {
                seen += request
                return ApiResult.Success(JSONObject().put("ok", true).put("messageId", "m1"))
            }
        }
        val api = WidgetApi(http, { BASE_URL }, TOKEN, StandardTestDispatcher(testScheduler))
        api.upload("wb", "c1", ByteArray(40 * 1024), "a.png", "image/png")
        api.typing("wb", "c1", true)
        val (fileCall, typingCall) = seen
        assertEquals(62_000, fileCall.timeoutMillis)
        assertEquals(30_000, fileCall.stallTimeoutMillis)
        assertEquals(HttpRequest.JSON_TIMEOUT_MILLIS, typingCall.timeoutMillis)
        assertNull(typingCall.stallTimeoutMillis)
    }
}
