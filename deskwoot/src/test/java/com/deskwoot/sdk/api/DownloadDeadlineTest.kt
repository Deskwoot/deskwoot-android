package com.deskwoot.sdk.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream

/**
 * Downloads (pictures and files) have no fixed total deadline: a try ends
 * after 30 s without a byte received (connecting included), or after 60 s
 * plus 1 s per 20 KB of the size the server announced, whichever comes
 * first. Without an announced size only the 30 s rule and the size cap hold.
 */
class DownloadDeadlineTest {
    private val slice = 32 * 1024

    /**
     * A client whose server answers its head after [headAfter] ms, announcing
     * [announced] bytes (none when null), then sends [total] bytes in slices,
     * one every [sliceEvery] ms, and nothing more once [stallAfter] slices went out.
     */
    private fun client(
        scope: TestScope,
        total: Int,
        sliceEvery: Long,
        announced: Long? = total.toLong(),
        headAfter: Long = 0,
        stallAfter: Int = Int.MAX_VALUE,
    ) = object : UrlConnectionHttpClient("test", StandardTestDispatcher(scope.testScheduler), { scope.testScheduler.currentTime }) {
        override suspend fun receive(url: String, maxBytes: Int, out: OutputStream, progress: DownloadProgress): Boolean {
            delay(headAfter)
            progress.announced(announced ?: -1)
            if ((announced ?: 0) > maxBytes) return false
            var sent = 0
            var slices = 0
            while (sent < total) {
                if (slices == stallAfter) while (true) delay(1_000)
                delay(sliceEvery)
                val n = minOf(slice, total - sent)
                if (sent + n > maxBytes) return false
                out.write(ByteArray(n) { 7 })
                sent += n
                slices++
                progress.received()
            }
            return true
        }
    }

    @Test
    fun `the limit grows with the announced size, 60 s plus 1 s per 20 KB, and there is none without a size`() {
        assertEquals(61_000L, HttpRequest.downloadTimeoutMillis(20 * 1024))
        // A 15 MB picture: about 13 minutes; a 50 MB file: about 43 minutes.
        assertEquals(828_000L, HttpRequest.downloadTimeoutMillis(15L * 1024 * 1024))
        assertNull(HttpRequest.downloadTimeoutMillis(0))
        assertNull(HttpRequest.downloadTimeoutMillis(-1))
        assertEquals(30_000, HttpRequest.DOWNLOAD_STALL_MILLIS)
    }

    @Test
    fun `a weak but working link downloads for longer than a minute`() = runTest {
        // 1 MB at one 32 KB slice every 3 s: 96 s, under its limit of 111.2 s.
        val bytes = 1024 * 1024
        val started = testScheduler.currentTime
        val got = client(this, bytes, sliceEvery = 3_000).fetch("https://files.test/a.pdf", 50 * 1024 * 1024)
        assertEquals(bytes, got?.size)
        assertEquals(96_000, testScheduler.currentTime - started)
    }

    @Test
    fun `30 s without a byte ends the try, also before the first one`() = runTest {
        val started = testScheduler.currentTime
        // Two slices at 10 s and 20 s, then nothing.
        assertNull(client(this, 1024 * 1024, sliceEvery = 10_000, stallAfter = 2).fetch("https://files.test/a.pdf", 50 * 1024 * 1024))
        assertEquals("30 s after the last byte", 50_000, testScheduler.currentTime - started)

        // The server does not even answer: 30 s from the start.
        val again = testScheduler.currentTime
        assertNull(client(this, 1024, sliceEvery = 10, headAfter = 600_000).fetch("https://files.test/b.pdf", 50 * 1024 * 1024))
        assertEquals(30_000, testScheduler.currentTime - again)
    }

    @Test
    fun `bytes that keep trickling end at the limit of the announced size`() = runTest {
        // A slice every 20 s never stalls, but 32 slices would take 640 s: the limit ends it at 111.2 s.
        val started = testScheduler.currentTime
        assertNull(client(this, 1024 * 1024, sliceEvery = 20_000).fetch("https://files.test/a.pdf", 50 * 1024 * 1024))
        assertEquals(HttpRequest.downloadTimeoutMillis(1024L * 1024), testScheduler.currentTime - started)
    }

    @Test
    fun `without an announced size only the stall rule and the cap hold`() = runTest {
        // 32 slices, one every 20 s: 640 s, far beyond any fixed limit, and never 30 s without a byte.
        val started = testScheduler.currentTime
        val got = client(this, 1024 * 1024, sliceEvery = 20_000, announced = null).fetch("https://files.test/a.pdf", 50 * 1024 * 1024)
        assertEquals(1024 * 1024, got?.size)
        assertEquals(640_000, testScheduler.currentTime - started)
        // The cap still ends a body that grows past it.
        assertNull(client(this, 2 * slice, sliceEvery = 10, announced = null).fetch("https://files.test/b.pdf", slice))
    }

    @Test
    fun `a download into a file follows the same limits`() = runTest {
        val target = File.createTempFile("download", ".part")
        try {
            val started = testScheduler.currentTime
            assertFalse(client(this, 1024 * 1024, sliceEvery = 10_000, stallAfter = 1).fetchTo("https://files.test/a.png", 15 * 1024 * 1024, target))
            assertEquals(40_000, testScheduler.currentTime - started)
            // The head after 1 s, then 3 slices 20 s apart: 61 s, beyond a fixed minute and inside its size's limit of 64.8 s.
            assertTrue(client(this, 3 * slice, sliceEvery = 20_000, headAfter = 1_000).fetchTo("https://files.test/a.png", 15 * 1024 * 1024, target))
            assertArrayEquals(ByteArray(3 * slice) { 7 }, target.readBytes())
        } finally {
            target.delete()
        }
    }
}
