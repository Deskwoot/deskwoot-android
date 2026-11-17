package com.deskwoot.sdk.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.api.FileFetcher
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.help.HelpCenterClient
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.files.Files
import com.deskwoot.sdk.ui.files.PhotoScaler
import com.deskwoot.sdk.ui.files.VisitorFiles
import com.deskwoot.sdk.ui.image.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Holds [Files] over the session a test puts in [session], as the messenger does. */
class SessionFilesHostActivity : ComponentActivity() {
    internal lateinit var files: Files

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        files = Files(this, { session }) { _, _ -> }
    }

    override fun onDestroy() {
        files.destroy()
        super.onDestroy()
    }

    internal companion object {
        @Volatile
        var session: ChatSession? = null
    }
}

/**
 * Pictures and files of the chat against the end of a visitor and in a
 * cookieless inbox: one still loading when the visitor ends lands nowhere,
 * and a cookieless inbox keeps pictures in memory and files only as
 * temporary copies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureFilesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val loader = ImageLoader.get(app)
    private val sdkFolder get() = File(app.cacheDir, "deskwoot")

    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    @Before
    fun setUp() {
        loader.diskFolder.parentFile?.deleteRecursively()
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }

    @After
    fun tearDown() {
        SessionFilesHostActivity.session = null
        UiMemory.clear()
    }

    /** A session of [scripted]'s engine whose downloads run [fetchTo] on the I/O thread. */
    private fun session(scripted: ScriptedSession, fetchTo: (File) -> Boolean): ChatSession {
        val api = WidgetApi(scripted.http, { BASE_URL }, scripted.token, Dispatchers.Main.immediate)
        return ChatSession(
            scripted.token,
            scripted.engine,
            HelpCenterClient(api, scripted.engine::helpContext),
            api,
            object : FileFetcher {
                override suspend fun fetch(url: String, maxBytes: Int): ByteArray? = null

                override suspend fun fetchTo(url: String, maxBytes: Int, target: File): Boolean = fetchTo(target)
            },
        )
    }

    /** [session] set up through the facade, as an app's `setup` does: the end of a visitor reaches the files. */
    private fun installed(session: ChatSession): ChatSession {
        Deskwoot.makeSession = { _, _, _ -> session }
        Deskwoot.setup(app, session.widgetToken)
        assertTrue(idleUntil { session.state.value.config != null })
        return session
    }

    private fun files(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `a picture still loading when the visitor ends lands neither on disk nor in memory`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = installed(
            session(ScriptedSession()) { part ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                // The new visitor's first picture made the folder again meanwhile.
                part.parentFile?.mkdirs()
                part.writeBytes(png)
                true
            },
        )
        var result: Bitmap? = null
        var done = false
        loader.load(View(app), session, "att_old", "/files/old.png", 500) {
            result = it
            done = true
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        Deskwoot.logout()
        assertTrue("the purge ran", idleUntil { !sdkFolder.exists() })
        release.countDown()
        assertTrue(idleUntil { done })
        idle()

        assertNull("nothing of the old visitor shows", result)
        assertNull("nothing of the old visitor stays in memory", loader.cached("att_old", 500))
        assertEquals("nothing of the old visitor lands on disk", emptyList<File>(), files(sdkFolder))
    }

    @Test
    fun `a picture decoded just before the visitor ends is neither kept in memory nor shown`() {
        val session = installed(
            session(ScriptedSession()) { part ->
                part.writeBytes(png)
                true
            },
        )
        var result: Bitmap? = null
        var done = false
        val main = shadowOf(Looper.getMainLooper())
        main.idle()
        loader.load(View(app), session, "att_late", "/files/late.png", 500) {
            result = it
            done = true
        }
        // The download and the decoding ran; the picture waits for the main thread.
        repeat(300) { if (main.isIdle) Thread.sleep(10) }
        assertFalse("the picture waits on the main thread", main.isIdle)

        Deskwoot.logout()
        assertTrue(idleUntil { done })
        assertNull(result)
        assertNull(loader.cached("att_late", 500))
        assertTrue("the purge took the file it wrote before the end", idleUntil { files(sdkFolder).isEmpty() })
    }

    @Test
    fun `a copy for opening a file made across the end of the visitor lands nowhere`() {
        val cached = File(app.cacheDir, "elsewhere/cached.pdf").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64) { 3 })
        }
        val stamp = VisitorFiles.stamp()
        Files.purge(app)
        assertNull(Files.sharedCopy(app, cached, "att_doc", "Invoice.pdf", stamp))
        assertEquals(emptyList<File>(), files(Files.sharedRoot(app)))
        // A copy that started after the end lands as usual.
        assertNotNull(Files.sharedCopy(app, cached, "att_doc", "Invoice.pdf"))
    }

    @Test
    fun `a cookieless inbox keeps its pictures in memory only`() {
        val scripted = ScriptedSession(ScriptedSession.helpConfig("cookielessEnabled" to true))
        val session = installed(
            session(scripted) { part ->
                part.writeBytes(png)
                true
            },
        )
        assertFalse(loader.keepsOnDisk(session))
        var result: Bitmap? = null
        var done = false
        loader.load(View(app), session, "att_c", "/files/c.png", 500) {
            result = it
            done = true
        }
        assertTrue(idleUntil { done })
        assertEquals(40, result?.width)
        assertNotNull("it stays in memory", loader.cached("att_c", 500))
        assertEquals("no picture on disk, not even the one it passed through", emptyList<File>(), files(sdkFolder))
    }

    @Test
    fun `a cookieless inbox shares a file through a temporary copy that goes when the messenger closes`() {
        val pdf = ByteArray(2048) { 9 }
        val scripted = ScriptedSession(ScriptedSession.helpConfig("cookielessEnabled" to true))
        val session = installed(
            session(scripted) { part ->
                part.writeBytes(pdf)
                true
            },
        )
        SessionFilesHostActivity.session = session
        val controller = Robolectric.buildActivity(SessionFilesHostActivity::class.java).setup()
        val host = controller.get()
        host.files.share(loader, "att_pdf", "/files/invoice.pdf", "Invoice.pdf", "application/pdf")
        assertTrue(idleUntil { shadowOf(host).peekNextStartedActivity() != null })
        val chooser = shadowOf(host).nextStartedActivity
        @Suppress("DEPRECATION")
        val stream = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals("Invoice.pdf", stream?.lastPathSegment)

        val copy = Files.temporaryCopy(app, "att_pdf", "Invoice.pdf")
        assertArrayEquals(pdf, copy.readBytes())
        assertEquals("the copy is the only file on disk", listOf(copy.canonicalFile), files(sdkFolder).map { it.canonicalFile })

        // A recreate keeps it; the messenger closing takes it.
        host.finish()
        controller.pause().stop().destroy()
        assertTrue(idleUntil { !copy.exists() })
    }

    @Test
    fun `the start of a run takes the temporary copies and the upload copies an ended run left`() {
        val copy = Files.temporaryCopy(app, "att_x", "Invoice.pdf").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        val passing = File(app.cacheDir, ImageLoader.TEMPORARY_FOLDER + "/dw-1.part").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        val uploads = PhotoScaler.uploadFolder(app).apply { mkdirs() }
        val leftOver = File(uploads, "old.jpg").apply {
            writeBytes(ByteArray(8))
            setLastModified(System.currentTimeMillis() - 60_000)
        }
        val now = File(uploads, "new.jpg").apply { writeBytes(ByteArray(8)) }
        val cachedPicture = File(loader.diskFolder.apply { mkdirs() }, "abc").apply { writeBytes(ByteArray(8)) }

        Files.cleanAfterEarlierRun(app, startedAt = System.currentTimeMillis() - 1_000)

        assertFalse(copy.exists())
        assertFalse(passing.exists())
        assertFalse(leftOver.exists())
        assertTrue("a copy of this run's upload stays", now.exists())
        assertTrue("the picture cache of an inbox that keeps one stays", cachedPicture.exists())
    }
}
