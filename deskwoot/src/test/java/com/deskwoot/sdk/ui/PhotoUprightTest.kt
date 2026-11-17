package com.deskwoot.sdk.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.core.content.FileProvider
import com.deskwoot.sdk.engine.UploadSource
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.files.AttachSource
import com.deskwoot.sdk.ui.files.Files
import com.deskwoot.sdk.ui.files.PhotoScaler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * A picture the SDK uploads is upright and carries no location: real JPEG
 * files with EXIF (the 8 orientations, GPS tags, location in XMP) through
 * the rule, and through the photo library, the camera and the document
 * picker of the messenger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoUprightTest {
    private val app = RuntimeEnvironment.getApplication()

    /** The quadrants of the stored picture: top left, top right, bottom left, bottom right. */
    private val red = Color.RED
    private val green = Color.GREEN
    private val blue = Color.BLUE
    private val yellow = Color.YELLOW

    @Before
    fun forgetProviderFolders() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }

    /** A JPEG of [width] by [height] in 4 colored quadrants, with EXIF [orientation] and, when [gps], a location. */
    private fun photo(name: String, orientation: Int = ExifInterface.ORIENTATION_NORMAL, gps: Boolean = false, width: Int = 120, height: Int = 80, dir: File = app.cacheDir): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        fun quad(color: Int, left: Int, top: Int) {
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + width / 2).toFloat(), (top + height / 2).toFloat(), paint)
        }
        quad(red, 0, 0)
        quad(green, width / 2, 0)
        quad(blue, 0, height / 2)
        quad(yellow, width / 2, height / 2)
        val file = File(dir.apply { mkdirs() }, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        val exif = ExifInterface(file.path)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.setAttribute(ExifInterface.TAG_MAKE, "Phone")
        if (gps) {
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,31/1,1200/100")
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "13/1,24/1,3000/100")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
        }
        exif.saveAttributes()
        return file
    }

    /** The color of a quadrant of [bitmap], by its middle: the JPEG blurs only the edges. */
    private fun quadrant(bitmap: Bitmap, right: Boolean, bottom: Boolean): Int {
        val x = if (right) bitmap.width * 3 / 4 else bitmap.width / 4
        val y = if (bottom) bitmap.height * 3 / 4 else bitmap.height / 4
        val p = bitmap.getPixel(x, y)
        // Snap to the nearest of the 4 colors.
        return listOf(red, green, blue, yellow).minBy { c ->
            val dr = Color.red(c) - Color.red(p)
            val dg = Color.green(c) - Color.green(p)
            val db = Color.blue(c) - Color.blue(p)
            dr * dr + dg * dg + db * db
        }
    }

    private fun corners(file: File): List<Int> {
        val b = checkNotNull(BitmapFactory.decodeFile(file.path)) { "not a picture: $file" }
        return listOf(quadrant(b, false, false), quadrant(b, true, false), quadrant(b, false, true), quadrant(b, true, true))
    }

    private fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    private fun assertNoMetadata(file: File) {
        val exif = ExifInterface(file.path)
        for (tag in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_MAKE)) {
            assertNull("$tag is gone", exif.getAttribute(tag))
        }
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        assertTrue("no turn is left to apply: $orientation", orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED)
        assertFalse("no XMP packet", String(file.readBytes(), Charsets.ISO_8859_1).contains("xmpmeta"))
    }

    /** Upright, the stored quadrants (red, green, blue, yellow) sit here for each EXIF orientation: top left, top right, bottom left, bottom right. */
    private val upright = mapOf(
        ExifInterface.ORIENTATION_NORMAL to listOf(red, green, blue, yellow),
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(green, red, yellow, blue),
        ExifInterface.ORIENTATION_ROTATE_180 to listOf(yellow, blue, green, red),
        ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(blue, yellow, red, green),
        ExifInterface.ORIENTATION_TRANSPOSE to listOf(red, blue, green, yellow),
        ExifInterface.ORIENTATION_ROTATE_90 to listOf(blue, red, yellow, green),
        ExifInterface.ORIENTATION_TRANSVERSE to listOf(yellow, green, blue, red),
        ExifInterface.ORIENTATION_ROTATE_270 to listOf(green, yellow, red, blue),
    )

    @Test
    fun `a photo with a location goes upright and without it, in each of the 8 orientations`() {
        for ((orientation, expected) in upright) {
            val stored = photo("o$orientation.jpg", orientation, gps = true)
            assertTrue("the test file carries its location", ExifInterface(stored.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null)
            val sent = checkNotNull(PhotoScaler.prepare(app, stored, "image/jpeg")) { "orientation $orientation is prepared" }
            val turned = orientation >= ExifInterface.ORIENTATION_TRANSPOSE
            assertEquals("orientation $orientation keeps its own size", if (turned) 80 to 120 else 120 to 80, size(sent))
            assertEquals("orientation $orientation", expected, corners(sent))
            assertNoMetadata(sent)
            assertEquals("the original stays the caller's", true, stored.isFile)
        }
    }

    @Test
    fun `a photo read from a stream is turned upright too, by a copy read by path`() {
        val stored = photo("stream.jpg", ExifInterface.ORIENTATION_TRANSVERSE, gps = true)
        val sent = checkNotNull(PhotoScaler.prepare(app, { stored.inputStream() }, "image/jpeg"))
        assertEquals(upright.getValue(ExifInterface.ORIENTATION_TRANSVERSE), corners(sent))
        assertNoMetadata(sent)
        // Only the prepared JPEG is left in the upload folder, not the copy it was read from.
        assertEquals(listOf(sent.name), PhotoScaler.uploadFolder(app).list()?.toList())
    }

    @Test
    fun `a large photo without a location is scaled to 2560 and upright`() {
        val stored = photo("large.jpg", ExifInterface.ORIENTATION_ROTATE_90, width = 3000, height = 2000)
        val sent = checkNotNull(PhotoScaler.prepare(app, stored, "image/jpeg"))
        assertEquals(1707 to 2560, size(sent))
        assertEquals(upright.getValue(ExifInterface.ORIENTATION_ROTATE_90), corners(sent))
        assertNoMetadata(sent)
    }

    @Test
    fun `a location only in XMP is found and dropped`() {
        val stored = photo("xmp.jpg")
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description exif:GPSLatitude=\"52,31.2N\"/></rdf:RDF></x:xmpmeta>"
        val payload = xmp.toByteArray(Charsets.ISO_8859_1)
        val segment = ByteArrayOutputStream().apply {
            write(0xFF)
            write(0xE1)
            write((payload.size + 2) shr 8)
            write((payload.size + 2) and 0xFF)
            write(payload)
        }.toByteArray()
        val bytes = stored.readBytes()
        stored.writeBytes(bytes.copyOfRange(0, 2) + segment + bytes.copyOfRange(2, bytes.size))
        assertTrue(BitmapFactory.decodeFile(stored.path) != null)
        val sent = checkNotNull(PhotoScaler.prepare(app, stored, "image/jpeg"))
        assertNoMetadata(sent)
    }

    @Test
    fun `a small photo without a location goes as it is, and so does what cannot be decoded`() {
        val plain = photo("plain.jpg", ExifInterface.ORIENTATION_ROTATE_90)
        assertNull("nothing to scale and nothing to hide", PhotoScaler.prepare(app, plain, "image/jpeg"))
        val broken = File(app.cacheDir, "broken.jpg").apply { writeBytes(ByteArray(500) { it.toByte() }) }
        assertNull(PhotoScaler.prepare(app, broken, "image/jpeg"))
        assertNull("a GIF keeps its animation", PhotoScaler.prepare(app, photo("located.gif", gps = true), "image/gif"))
    }

    private fun provided(file: File) = FileProvider.getUriForFile(app, app.packageName + ".deskwoot.files", file)

    @Test
    fun `a photo of the library and a picture chosen as a file go upright and without their location`() {
        val shared = File(Files.sharedRoot(app), "picked")
        val controller = Robolectric.buildActivity(FilesHostActivity::class.java).setup()
        val host = controller.get()
        for (source in listOf(AttachSource.PHOTOS, AttachSource.FILES)) {
            val stored = photo("IMG_${source.name}.jpg", ExifInterface.ORIENTATION_ROTATE_270, gps = true, dir = shared)
            host.files.pick(source, listOf("image/*"), Route.Thread("c1"))
            val request = checkNotNull(shadowOf(host).nextStartedActivityForResult) { "$source opens its picker" }
            host.delivered.clear()
            host.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(provided(stored)))
            assertTrue(idleUntil { host.delivered.isNotEmpty() })
            val picked = host.delivered.single().second
            assertEquals("image/jpeg", picked.mimeType)
            assertEquals("IMG_${source.name}.jpg", picked.name)
            val sent = checkNotNull(picked.temporary)
            assertEquals(sent, (picked.source as UploadSource.LocalFile).file)
            assertEquals(upright.getValue(ExifInterface.ORIENTATION_ROTATE_270), corners(sent))
            assertNoMetadata(sent)
        }
        // A picture without a location that needs no scaling goes as it is, from its own address.
        val plain = photo("plain.jpg", dir = shared)
        host.files.pick(AttachSource.FILES, listOf("image/*"), null)
        val request = checkNotNull(shadowOf(host).nextStartedActivityForResult)
        host.delivered.clear()
        host.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(provided(plain)))
        assertTrue(idleUntil { host.delivered.isNotEmpty() })
        val asIs = host.delivered.single().second
        assertEquals(provided(plain), (asIs.source as UploadSource.Content).uri)
        assertNull(asIs.temporary)
        controller.pause().stop().destroy()
    }

    @Test
    fun `a camera shot goes upright and without its location, and the original goes`() {
        val controller = Robolectric.buildActivity(FilesHostActivity::class.java).setup()
        val host = controller.get()
        host.files.pick(AttachSource.CAMERA, listOf("image/*"), Route.Thread("c1"))
        assertTrue(idleUntil { host.files.cameraFile != null })
        val shot = checkNotNull(host.files.cameraFile)
        val request = checkNotNull(shadowOf(host).nextStartedActivityForResult)
        // The camera app writes a mirrored shot with its location.
        val taken = photo("taken.jpg", ExifInterface.ORIENTATION_TRANSPOSE, gps = true)
        shot.writeBytes(taken.readBytes())
        host.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        assertTrue(idleUntil { host.delivered.isNotEmpty() })
        val picked = host.delivered.single().second
        val sent = (picked.source as UploadSource.LocalFile).file
        assertEquals(upright.getValue(ExifInterface.ORIENTATION_TRANSPOSE), corners(sent))
        assertNoMetadata(sent)
        assertFalse("the camera's original with its location is gone", shot.exists())
        assertArrayEquals(sent.readBytes(), checkNotNull(picked.temporary).readBytes())
        controller.pause().stop().destroy()
    }
}
