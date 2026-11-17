package com.deskwoot.sdk.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import com.deskwoot.sdk.ui.files.PhotoScaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A picture with transparency becomes a JPEG on white, not on black. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoScalerPixelsTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `the clear parts of a PNG turn white in the JPEG`() {
        // A logo: a clear 60 by 40 picture with an opaque red square in its middle.
        val logo = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        logo.eraseColor(Color.TRANSPARENT)
        for (x in 20 until 40) for (y in 10 until 30) logo.setPixel(x, y, Color.RED)
        val png = File(app.cacheDir, "logo.png")
        png.outputStream().use { logo.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val jpeg = checkNotNull(PhotoScaler.jpeg(app, png, ExifInterface.ORIENTATION_NORMAL))
        val sent = checkNotNull(BitmapFactory.decodeFile(jpeg.path))
        assertEquals(60, sent.width)
        val corner = sent.getPixel(2, 2)
        assertTrue("the corner is white, not black: ${Integer.toHexString(corner)}", Color.red(corner) > 240 && Color.green(corner) > 240 && Color.blue(corner) > 240)
        val middle = sent.getPixel(30, 20)
        assertTrue("the opaque part keeps its color: ${Integer.toHexString(middle)}", Color.red(middle) > 200 && Color.green(middle) < 60)
        jpeg.delete()
    }

    @Test
    fun `an opaque photo is not copied for nothing`() {
        val photo = Bitmap.createBitmap(10, 10, Bitmap.Config.RGB_565)
        assertTrue(PhotoScaler.onWhite(photo) === photo)
    }
}
