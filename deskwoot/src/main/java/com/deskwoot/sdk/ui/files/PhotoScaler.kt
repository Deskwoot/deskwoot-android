package com.deskwoot.sdk.ui.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * A picture the SDK uploads is upright and carries no location. One that
 * needs scaling (over 2560 px on its long side: a 48 MP shot would pass no
 * inbox's limit) or carries location metadata (EXIF GPS tags, or GPS and
 * location tags in XMP) goes out as an upright JPEG at quality 85 without
 * any metadata: scaled, or at its own size. Transparency is drawn on
 * white. Any other picture, GIFs and SVGs, and whatever cannot be decoded
 * go as they are. The same holds for the photo library, the camera and a
 * picture chosen as a file (the iOS SDK does the same). The orientation is
 * read from a file by path, which works on every Android version (reading
 * it from a stream needs Android 7), and all 8 values are applied, the
 * mirrored ones included. Everything here blocks: call it on an I/O
 * dispatcher.
 */
internal object PhotoScaler {
    const val MAX_SIDE: Int = 2560
    const val QUALITY: Int = 85

    /** The size a photo of [width] by [height] is sent at. */
    fun targetSize(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val longer = maxOf(width, height)
        if (longer <= max || longer <= 0) return width to height
        val scale = max.toDouble() / longer
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** The largest power of 2 that decodes at least [max] on the long side (the exact size follows by scaling). */
    fun sampleSize(width: Int, height: Int, max: Int = MAX_SIDE): Int {
        var sample = 1
        val longer = maxOf(width, height)
        while (longer / (sample * 2) >= max) sample *= 2
        return sample
    }

    /** Whether a picked type is a picture this looks at (GIFs keep their animation, SVGs are no pixels). */
    fun scales(mimeType: String): Boolean {
        val type = mimeType.lowercase()
        return type.startsWith("image/") && type != "image/gif" && type != "image/svg+xml"
    }

    /** The name a prepared picture is sent under: its own name with the JPEG extension. */
    fun jpegName(name: String): String {
        val base = name.substringBeforeLast('.', name).trim().ifEmpty { "photo" }
        return "$base.jpg"
    }

    /**
     * What the picture [open] gives goes out as: an upright JPEG without
     * metadata in the SDK's upload folder, or null when it goes as it is.
     * The picture is copied into the upload folder first, so its metadata
     * can be read by path on every version; the copy goes afterwards.
     */
    fun prepare(context: Context, open: () -> InputStream?, mimeType: String): File? {
        if (!scales(mimeType)) return null
        var copy: File? = null
        return try {
            val dir = uploadFolder(context).also { it.mkdirs() }
            val source = File(dir, UUID.randomUUID().toString() + ".source")
            copy = source
            val input = open() ?: return null
            input.use { stream -> source.outputStream().use { stream.copyTo(it) } }
            prepare(context, source, mimeType)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        } finally {
            copy?.delete()
        }
    }

    /** What the picture file [source] goes out as (see the other [prepare]); [source] stays. */
    fun prepare(context: Context, source: File, mimeType: String): File? {
        if (!scales(mimeType)) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // Decoding only the bounds answers null by design; the size is in the options.
            BitmapFactory.decodeFile(source.path, bounds)
            // Nothing the SDK can decode: it goes as it is.
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val exif = try {
                ExifInterface(source.path)
            } catch (_: Exception) {
                null
            }
            val needsScaling = maxOf(bounds.outWidth, bounds.outHeight) > MAX_SIDE
            if (!needsScaling && !hasLocation(exif, source)) return null
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
            jpeg(context, source, orientation)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [source] decoded at most [MAX_SIDE] on its long side, turned upright by
     * [orientation] (an EXIF value, 1 to 8), on white, written as a JPEG
     * without metadata into the upload folder. Null when it cannot be
     * decoded, also at half the size.
     */
    fun jpeg(context: Context, source: File, orientation: Int): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val first = sampleSize(bounds.outWidth, bounds.outHeight)
        // Too little memory for the full size: once more at half the size.
        val decoded = decodeSampled(source, first) ?: decodeSampled(source, first * 2) ?: return null
        val (w, h) = targetSize(decoded.width, decoded.height)
        val matrix = orientationMatrix(orientation)
        if (w != decoded.width || h != decoded.height) matrix.preScale(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
        val upright = try {
            if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            return null
        }
        val flat = onWhite(upright)
        val dir = uploadFolder(context).also { it.mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        // Bitmap.compress writes the pixels only: no EXIF, no XMP, no location.
        out.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        flat.recycle()
        return out.takeIf { it.length() > 0 }
    }

    private fun decodeSampled(source: File, sample: Int): Bitmap? = try {
        BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: OutOfMemoryError) {
        null
    }

    /**
     * The transform that shows a picture stored with EXIF [orientation]
     * upright; the mirrored values (2, 4, 5, 7) flip it too. The identity for
     * 1 and anything unknown. Bitmap.createBitmap moves the result back into
     * view.
     */
    fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> reset()
        }
    }

    /** The EXIF orientation of a picture file (1 when it has none or cannot be read). */
    fun orientationOf(file: File): Int = try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** EXIF tags that hold where a picture was taken (the names work on every version). */
    private val GPS_TAGS = listOf(
        "GPSLatitude", "GPSLongitude", "GPSAltitude", "GPSDestLatitude", "GPSDestLongitude",
        "GPSAreaInformation", "GPSProcessingMethod", "GPSDateStamp", "GPSTimeStamp", "GPSLatitudeRef", "GPSLongitudeRef",
    )

    /** Location metadata: an EXIF GPS tag, or GPS or location tags in an XMP packet near the start of [file]. */
    fun hasLocation(exif: ExifInterface?, file: File): Boolean {
        if (exif != null && GPS_TAGS.any { !exif.getAttribute(it).isNullOrBlank() }) return true
        return xmpHasLocation(file)
    }

    /** Reads up to the first 512 KB for an XMP packet (JPEG keeps it in its first segments) and looks for GPS or location tags. */
    private fun xmpHasLocation(file: File): Boolean = try {
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(file.length(), XMP_SCAN_BYTES.toLong()).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.ISO_8859_1)
        }
        val start = head.indexOf("<x:xmpmeta")
        val end = if (start >= 0) head.indexOf("</x:xmpmeta>", start) else -1
        val packet = if (start >= 0) head.substring(start, if (end > start) end else head.length) else ""
        packet.contains("GPS") || packet.contains("location", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private const val XMP_SCAN_BYTES = 512 * 1024

    /**
     * JPEG has no transparency: a picture with an alpha channel (a PNG
     * screenshot, a logo) is drawn on white first, so its clear parts do not
     * turn black.
     */
    fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
        return flat
    }

    /** Copies made for an upload; each goes when its upload ends, all go with the visitor. */
    fun uploadFolder(context: Context): File = File(context.cacheDir, "deskwoot/uploads")
}
