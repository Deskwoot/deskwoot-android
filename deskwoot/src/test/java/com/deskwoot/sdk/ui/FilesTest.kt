package com.deskwoot.sdk.ui

import android.app.Activity
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import com.deskwoot.sdk.engine.UploadSource
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.files.AttachSource
import com.deskwoot.sdk.ui.files.Files
import com.deskwoot.sdk.ui.files.Picked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** An Activity that holds [Files] the way the messenger does: made in onCreate, its pick in the saved state. */
class FilesHostActivity : ComponentActivity() {
    internal lateinit var files: Files
    internal val delivered = mutableListOf<Pair<Route?, Picked>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        files = Files(this, { null }) { route, picked -> delivered += route to picked }
        files.restore(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        files.save(outState)
    }

    override fun onDestroy() {
        files.destroy()
        super.onDestroy()
    }
}

/** Files in and out of the chat: names on disk, the purge, the shared folder's cap, picks across a recreate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilesTest {
    private val app = RuntimeEnvironment.getApplication()

    /**
     * FileProvider keeps the folders of an authority for the process; each
     * Robolectric test has a cache folder of its own, so the kept one is
     * forgotten before each test (a phone keeps one cache folder).
     */
    @Before
    fun forgetProviderFolders() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }

    @Test
    fun `attachment names never become a folder step or an empty name`() {
        assertEquals("file", Files.safeName("."))
        assertEquals("file", Files.safeName(".."))
        assertEquals("file", Files.safeName("..."))
        assertEquals("file", Files.safeName(""))
        assertEquals("file", Files.safeName("   "))
        assertEquals("file", Files.safeName(null))
        assertEquals(".._.._etc_passwd", Files.safeName("../../etc/passwd"))
        assertEquals("a_b", Files.safeName("a/b"))
        assertEquals("a_b", Files.safeName("a\u0000b"))
        assertEquals("Invoice 1042.pdf", Files.safeName(" Invoice 1042.pdf "))
        assertEquals(120, Files.safeName("x".repeat(500)).length)
    }

    @Test
    fun `a copy shared with other apps lands in the provider's folder and the folder keeps its cap`() {
        val cached = File(app.cacheDir, "cached").apply { writeBytes(ByteArray(64)) }
        val camera = File(Files.sharedRoot(app), "camera").apply { mkdirs() }.let { File(it, "photo.jpg") }.apply { writeBytes(ByteArray(10)) }
        val out = checkNotNull(Files.sharedCopy(app, cached, "att_1", ".."))
        assertEquals("file", out.name)
        assertEquals(64, out.length())
        assertTrue(out.canonicalPath.startsWith(Files.sharedRoot(app).canonicalPath + File.separator))
        assertTrue("the camera's file is never trimmed", camera.isFile)
    }

    @Test
    fun `the purge removes every file the SDK wrote`() {
        val root = File(app.cacheDir, "deskwoot")
        File(root, "images").mkdirs()
        File(root, "images/abc").writeText("x")
        File(root, "shared/def").mkdirs()
        File(root, "shared/def/Invoice.pdf").writeText("x")
        File(root, "uploads").mkdirs()
        File(root, "uploads/u.jpg").writeText("x")
        val appsOwn = File(app.cacheDir, "app-own.txt").apply { writeText("keep") }
        Files.purge(app)
        assertFalse(root.exists())
        assertTrue("the app's own cache stays", appsOwn.isFile)
    }

    @Test
    fun `a camera shot survives a recreate of the Activity and reaches the screen that asked`() {
        val controller = Robolectric.buildActivity(FilesHostActivity::class.java).setup()
        val first = controller.get()
        first.files.pick(AttachSource.CAMERA, listOf("image/*"), Route.Thread("c1"))
        assertTrue("the camera's file is made off the main thread", idleUntil { first.files.cameraFile != null })
        val shot = checkNotNull(first.files.cameraFile)
        val request = shadowOf(first).nextStartedActivityForResult
        assertNotNull(request)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.intent.action)
        // The camera app writes the photo while the messenger is in the background.
        shot.writeBytes(ByteArray(2048) { 1 })

        // The Activity is recreated (dark mode, language, or the process died) before the camera returns.
        controller.recreate()
        val second = controller.get()
        assertTrue(second !== first)
        assertEquals(shot.path, second.files.cameraFile?.path)

        second.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        assertTrue(idleUntil { second.delivered.isNotEmpty() })
        val (route, picked) = second.delivered.single()
        assertEquals(Route.Thread("c1"), route)
        assertEquals("image/jpeg", picked.mimeType)
        assertTrue(picked.source is UploadSource.LocalFile)
        assertTrue(first.delivered.isEmpty())
        controller.pause().stop().destroy()
    }

    @Test
    fun `a cancelled camera shot leaves no file behind`() {
        val controller = Robolectric.buildActivity(FilesHostActivity::class.java).setup()
        val host = controller.get()
        host.files.pick(AttachSource.CAMERA, listOf("image/*"), Route.Thread(null))
        assertTrue(idleUntil { host.files.cameraFile != null })
        val shot = checkNotNull(host.files.cameraFile)
        val request = shadowOf(host).nextStartedActivityForResult
        host.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        assertTrue(idleUntil { !shot.exists() })
        assertTrue(host.delivered.isEmpty())
        controller.pause().stop().destroy()
    }
}
