package com.deskwoot.sdk.engine

import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.jsonList
import com.deskwoot.sdk.model.UploadErrorCode
import com.deskwoot.sdk.model.UploadPhase
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uploads. */
class UploadTest {
    private val photo = UploadSource.Bytes(ByteArray(100) { 1 })

    private fun harness(scope: TestScope, vararg config: Pair<String, Any?>): Harness {
        val h = Harness(scope, baseConfig(*config), record = VisitorRecord(bearer = "wb_known"))
        h.openThread()
        return h
    }

    @Test
    fun `an upload follows the server and stays until the poll brings its row`() = runTest {
        val h = harness(this)
        h.http.once("POST /api/widget/attachments") {
            ok(json("messageId" to "f1", "conversationId" to "c2", "reopened" to false, "newConversation" to true, "attachment" to null))
        }
        assertNull(h.engine.upload(photo, "IMG_1234.HEIC", "image/jpeg"))

        val request = h.http.calls("POST /api/widget/attachments").single().request
        val body = String(checkNotNull(request.body), Charsets.ISO_8859_1)
        // 60 s plus 1 s per 20 KB of the file (100 bytes: 4 ms), and 30 s without a byte sent.
        assertEquals(60_004, request.timeoutMillis)
        assertEquals(30_000, request.stallTimeoutMillis)
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("multipart/form-data; boundary="))
        assertTrue(body.contains("name=\"conversationId\"\r\n\r\nc1\r\n"))
        assertTrue(body.contains("name=\"contactIdentifier\"\r\n\r\nwb_known\r\n"))
        // The extension follows the data, not the picker's HEIC name.
        assertTrue(body.contains("filename=\"IMG_1234.jpg\"\r\nContent-Type: image/jpeg"))
        assertEquals("c2", h.thread.conversationId)
        assertEquals(UploadPhase.SENT, h.thread.uploads.single().phase)

        h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(row("f1", type = "INCOMING", content = null, conversationId = "c2", attachments = jsonList(json("id" to "at1", "fileUrl" to "/api/widget/files/at1?exp=1&sig=a", "fileType" to "IMAGE")))))
        }
        h.advance(5_000)
        assertTrue(h.thread.uploads.isEmpty())
        assertTrue(h.thread.messages.single().attachments.single().isImage)
    }

    @Test
    fun `an upload keeps the caller's id lower case with its type and start, and names its stored message`() = runTest {
        val h = harness(this)
        h.advance(2_000)
        val pickedAt = h.clock.now()
        h.http.once("POST /api/widget/attachments") {
            delay(1_000)
            ok(json("messageId" to "m9", "conversationId" to "c1", "reopened" to false, "newConversation" to false))
        }
        val result = backgroundScope.async { h.engine.upload(photo, "shot.png", "image/png", id = "ABC-1") }
        h.settle()
        val uploading = h.thread.uploads.single()
        assertEquals("abc-1", uploading.id)
        assertEquals("image/png", uploading.mimeType)
        assertEquals(pickedAt, uploading.startedAt)
        assertEquals(UploadPhase.UPLOADING, uploading.phase)
        assertEquals(0f, uploading.progress, 0f)
        assertNull(uploading.messageId)

        h.advance(1_000)
        assertNull(result.await())
        val stored = h.thread.uploads.single()
        assertEquals("abc-1", stored.id)
        assertEquals(UploadPhase.SENT, stored.phase)
        assertEquals(1f, stored.progress, 0f)
        assertEquals("m9", stored.messageId)
    }

    @Test
    fun `a refusal keeps the caller's id and type, and a retry under the same id takes its row`() = runTest {
        val h = harness(this)
        val big = UploadSource.Bytes(ByteArray(1_001))
        assertEquals(UploadErrorCode.FILE_TOO_LARGE, h.engine.upload(big, "big.pdf", "application/pdf", id = "u2"))
        val refused = h.thread.uploads.single()
        assertEquals("u2", refused.id)
        assertEquals("application/pdf", refused.mimeType)
        assertEquals(h.clock.now(), refused.startedAt)

        h.advance(90_000)
        h.http.once("POST /api/widget/attachments") { ok(json("messageId" to "m3", "conversationId" to "c1")) }
        assertNull(h.engine.upload(photo, "small.pdf", "application/pdf", id = "U2"))
        val again = h.thread.uploads.single()
        assertEquals("u2", again.id)
        assertEquals(UploadPhase.SENT, again.phase)
        assertEquals("m3", again.messageId)
        assertEquals("the row keeps its first start, so its place in the thread", refused.startedAt, again.startedAt)

        // Without an id of the caller the engine makes a lower case one.
        h.http.once("POST /api/widget/attachments") { ok(json("messageId" to "m4", "conversationId" to "c1")) }
        assertNull(h.engine.upload(photo, "a.png", "image/png"))
        val made = h.thread.uploads.last().id
        assertEquals(made.lowercase(), made)
        assertTrue(made != "u2")
    }

    @Test
    fun `size and type are checked against the config before any request`() = runTest {
        val fresh = Harness(this)
        fresh.start()
        assertEquals(UploadErrorCode.CONVERSATION_NOT_STARTED, fresh.engine.upload(photo, "a.jpg", "image/jpeg"))

        val disabled = harness(this, "featureFilePicker" to false)
        assertEquals(UploadErrorCode.UPLOADS_DISABLED, disabled.engine.upload(photo, "a.jpg", "image/jpeg"))

        val h = harness(this)
        assertEquals(UploadErrorCode.FILE_TYPE_NOT_ALLOWED, h.engine.upload(photo, "a.zip", "application/zip"))
        val big = UploadSource.Bytes(ByteArray(1_001))
        assertEquals(UploadErrorCode.FILE_TOO_LARGE, h.engine.upload(big, "a.jpg", "image/jpeg"))
        assertTrue(h.http.calls("POST /api/widget/attachments").isEmpty())
        // A refusal stays in the thread with its code until the UI dismisses it.
        assertEquals(
            listOf(UploadErrorCode.FILE_TYPE_NOT_ALLOWED, UploadErrorCode.FILE_TOO_LARGE),
            h.thread.uploads.map { it.error },
        )
        assertTrue(h.thread.uploads.all { it.phase == UploadPhase.FAILED })
        h.engine.dismissUpload(h.thread.uploads.first().id)
        assertEquals(listOf(UploadErrorCode.FILE_TOO_LARGE), h.thread.uploads.map { it.error })
    }

    @Test
    fun `every refusal code maps to its own error`() = runTest {
        val h = harness(this)
        val cases = listOf(
            fail(400, json("code" to "file_too_large")) to UploadErrorCode.FILE_TOO_LARGE,
            fail(413, null) to UploadErrorCode.FILE_TOO_LARGE,
            fail(400, json("code" to "file_type_not_allowed")) to UploadErrorCode.FILE_TYPE_NOT_ALLOWED,
            fail(400, json("code" to "file_rejected")) to UploadErrorCode.FILE_REJECTED,
            fail(503, json("code" to "scan_unavailable")) to UploadErrorCode.SCAN_UNAVAILABLE,
            fail(503, json("code" to "storage_unavailable")) to UploadErrorCode.STORAGE_UNAVAILABLE,
            fail(429, json("code" to "rate_limited"), retryAfter = "60") to UploadErrorCode.RATE_LIMITED,
            fail(403, json("code" to "uploads_disabled")) to UploadErrorCode.UPLOADS_DISABLED,
            fail(409, json("code" to "conversation_closed")) to UploadErrorCode.CONVERSATION_CLOSED,
            fail(429, json("code" to "new_conversation_limit")) to UploadErrorCode.UPLOAD_FAILED,
            fail(404, json("error" to "Conversation not found")) to UploadErrorCode.UPLOAD_FAILED,
            offline() to UploadErrorCode.UPLOAD_FAILED,
        )
        for ((answer, expected) in cases) {
            h.http.once("POST /api/widget/attachments") { answer }
            assertEquals(expected, h.engine.upload(photo, "a.png", "image/png"))
            val failed = h.thread.uploads.single()
            assertEquals(UploadPhase.FAILED, failed.phase)
            assertEquals(expected, failed.error)
            h.engine.dismissUpload(failed.id)
            assertTrue(h.thread.uploads.isEmpty())
            assertFalse(h.thread.closed)
        }

        // 403 with the same code closes the whole chat for the visitor and leaves no line.
        h.http.once("POST /api/widget/attachments") { fail(403, json("code" to "conversation_closed")) }
        assertEquals(UploadErrorCode.CONVERSATION_CLOSED, h.engine.upload(photo, "a.png", "image/png"))
        assertTrue(h.thread.uploads.isEmpty())
        assertTrue(h.thread.closed)
        assertFalse(h.thread.canSend)
    }
}
