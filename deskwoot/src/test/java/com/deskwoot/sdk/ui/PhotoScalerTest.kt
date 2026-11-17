package com.deskwoot.sdk.ui

import com.deskwoot.sdk.ui.files.PhotoScaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photos go out at most 2560 px on the long side, as JPEG, as in the iOS SDK. */
class PhotoScalerTest {
    @Test
    fun `a large photo is scaled to 2560 on its long side, a small one stays`() {
        assertEquals(2560 to 1920, PhotoScaler.targetSize(8000, 6000))
        assertEquals(1440 to 2560, PhotoScaler.targetSize(3024, 5376))
        assertEquals(1200 to 900, PhotoScaler.targetSize(1200, 900))
        // Decoding samples by powers of 2 and never below the target.
        assertEquals(2, PhotoScaler.sampleSize(8000, 6000))
        assertEquals(1, PhotoScaler.sampleSize(4000, 3000))
    }

    @Test
    fun `only photos are scaled, GIFs and SVGs keep what they are and the name becomes a JPEG`() {
        assertTrue(PhotoScaler.scales("image/jpeg"))
        assertTrue(PhotoScaler.scales("image/heic"))
        assertFalse(PhotoScaler.scales("image/gif"))
        assertFalse(PhotoScaler.scales("image/svg+xml"))
        assertFalse(PhotoScaler.scales("application/pdf"))
        assertEquals("IMG_0042.jpg", PhotoScaler.jpegName("IMG_0042.HEIC"))
        assertEquals("photo.jpg", PhotoScaler.jpegName(".png"))
    }
}
