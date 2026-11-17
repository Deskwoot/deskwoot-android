package com.deskwoot.sdk.ui

import android.graphics.Bitmap
import android.view.View
import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.api.FileFetcher
import com.deskwoot.sdk.api.MalformedAnswer
import com.deskwoot.sdk.api.copyCapped
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.image.ImageLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** The image loader's sampling and its failure paths: nothing throws into the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoaderTest {
    private val app = RuntimeEnvironment.getApplication()
    private val loader = ImageLoader.get(app)

    /** The loader is one per process: its folder, wherever the first test put the app's cache. */
    private val images = loader.diskFolder
    private val scripted = ScriptedSession()

    @Before
    fun clean() {
        images.parentFile?.deleteRecursively()
    }

    @Test
    fun `a photo decodes sampled down to the size it is drawn at`() {
        val photo = File(app.cacheDir, "photo.png")
        val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        photo.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val small = checkNotNull(ImageLoader.decode(photo, 100))
        assertEquals(100, small.width)
        assertEquals(75, small.height)
        val full = checkNotNull(ImageLoader.decode(photo, 1000))
        assertEquals(400, full.width)
    }

    @Test
    fun `pictures are decoded at most at the size they are drawn at`() {
        // The viewer on a 2400 px screen: a 4000 by 3000 photo decodes at 2000 by 1500, not at full size (48 MB).
        assertEquals(2, ImageLoader.sampleSize(4000, 3000, 2400))
        assertEquals(4, ImageLoader.sampleSize(4000, 3000, 1080))
        assertEquals(4, ImageLoader.sampleSize(3000, 4000, 1000))
        assertEquals(8, ImageLoader.sampleSize(4000, 3000, 999))
        assertEquals(1, ImageLoader.sampleSize(800, 600, 1080))
        assertEquals(1, ImageLoader.sampleSize(1080, 600, 1080))
        // A broken size never loops forever.
        assertEquals(1 shl 16, ImageLoader.sampleSize(Int.MAX_VALUE, 1, 0))
        for (max in listOf(48, 300, 1080, 2400)) {
            for (longer in listOf(1, 47, 48, 49, 1000, 3999, 4000, 12_000)) {
                val sample = ImageLoader.sampleSize(longer, longer / 2 + 1, max)
                assertTrue("$longer at $max", longer / sample <= max)
                if (sample > 1) assertTrue("$longer at $max is not sampled more than needed", longer / (sample / 2) > max)
            }
        }
    }

    @Test
    fun `a file that is no picture decodes to nothing`() {
        val file = File(app.cacheDir, "not-a-picture").apply { writeText("hello") }
        assertNull(ImageLoader.decode(file, 100))
        assertNull(ImageLoader.decode(File(app.cacheDir, "missing"), 100))
    }

    @Test
    fun `a download goes to disk once and is used from there afterwards`() = runBlocking {
        scripted.files["$BASE_URL/files/a.png"] = ByteArray(1000) { it.toByte() }
        val first = loader.file(scripted.session, "att_1", "/files/a.png")
        val second = loader.file(scripted.session, "att_1", "/files/a.png")
        assertArrayEquals(ByteArray(1000) { it.toByte() }, first?.readBytes())
        assertEquals(first, second)
        assertEquals(1, scripted.fetched.size)
        // Only the finished file is left: no partial download.
        assertEquals(listOf(first?.name), images.listFiles()?.map { it.name })
    }

    @Test
    fun `failed downloads, full disks and refusals give no file and throw nothing`() = runBlocking {
        // No answer.
        assertNull(loader.file(scripted.session, "att_missing", "/files/missing.png"))
        // The fetcher itself throws.
        scripted.fetchThrows = IllegalStateException("broken stack")
        assertNull(loader.file(scripted.session, "att_throws", "/files/x.png"))
        scripted.fetchThrows = null
        // Larger than allowed.
        scripted.files["$BASE_URL/files/big.png"] = ByteArray(2000)
        assertNull(loader.file(scripted.session, "att_big", "/files/big.png", maxBytes = 1000))
        assertTrue("no partial downloads stay behind", images.listFiles().orEmpty().none { it.name.endsWith(".part") })
        // The cache folder cannot be made (a file where the folder should be): an IO error, caught.
        images.deleteRecursively()
        images.parentFile?.mkdirs()
        images.writeText("in the way")
        scripted.files["$BASE_URL/files/b.png"] = ByteArray(10)
        assertNull(loader.file(scripted.session, "att_b", "/files/b.png"))
    }

    @Test
    fun `a picture that cannot be loaded hands null to its view and throws nothing`() {
        var result: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        scripted.fetchThrows = OutOfMemoryError("no room")
        loader.load(View(app), scripted.session, "att_oom", "/files/oom.png", 500) { result = it }
        // An Error passes every catch of the download: the loader's handler takes it and the app keeps running
        // (without the handler the idle below would rethrow it).
        assertTrue(idleUntil { scripted.fetched.isNotEmpty() })
        ScriptedSession.idle(rounds = 10)
        scripted.fetchThrows = null
        var second = false
        loader.load(View(app), scripted.session, "att_none", "/files/none.png", 500) {
            result = it
            second = true
        }
        assertTrue(idleUntil { second })
        assertNull(result)
    }

    @Test
    fun `the cache folder stays under its limit, oldest first, keeping what must stay`() {
        val dir = File(app.cacheDir, "trim").apply { mkdirs() }
        val files = (0 until 6).map { i ->
            File(dir, "f$i").apply {
                writeBytes(ByteArray(100))
                setLastModified(1_000_000L + i * 1000)
            }
        }
        val kept = File(dir, "camera").apply { mkdirs() }.let { File(it, "shot.jpg") }.apply {
            writeBytes(ByteArray(100))
            setLastModified(1L)
        }
        ImageLoader.trimFolder(dir, 400) { it.parentFile?.name == "camera" }
        val left = dir.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        // 600 bytes over 400: the oldest go until 300 (three quarters) is reached.
        assertEquals(setOf("f3", "f4", "f5", "shot.jpg"), left)
        assertTrue(kept.isFile)
        files.forEach { it.delete() }
    }

    @Test
    fun `a download is streamed with a cap, never buffered whole`() {
        val out = ByteArrayOutputStream()
        copyCapped(ByteArrayInputStream(ByteArray(20_000) { 7 }), out, 20_000)
        assertEquals(20_000, out.size())
        try {
            copyCapped(ByteArrayInputStream(ByteArray(20_001)), ByteArrayOutputStream(), 20_000)
            fail("a body over the cap is refused")
        } catch (_: MalformedAnswer) {
            // Expected.
        }
        // The default of a fetcher without streaming writes the whole answer into the file.
        val target = File(app.cacheDir, "streamed")
        val fetcher = FileFetcher { _, _ -> byteArrayOf(1, 2, 3) }
        assertTrue(runBlocking { fetcher.fetchTo("https://x", 10, target) })
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }
}
