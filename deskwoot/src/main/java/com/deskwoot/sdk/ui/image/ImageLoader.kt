package com.deskwoot.sdk.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import com.deskwoot.sdk.AndroidLogger
import com.deskwoot.sdk.R
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.ui.files.PhotoScaler
import com.deskwoot.sdk.ui.files.VisitorFiles
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The SDK's own image loader: files of the chat and faces, fetched through
 * the SDK's HTTP stack (signed links need nothing else) straight into a
 * file on disk (50 MB at most, oldest first out), kept in memory (an eighth
 * of the heap, 32 MB at most), decoded off the main thread and down sampled
 * to at most the size they are drawn at. A request belongs to a view: a
 * newer request for the same view cancels the older one, so a recycled row
 * never shows the wrong picture. Nothing here throws into the app: a full
 * disk, a broken file or too little memory only means no picture.
 *
 * A cookieless inbox keeps its pictures in memory only: each passes
 * through a temporary file that goes as soon as it is decoded, and the
 * disk cache is neither read nor written. A picture still loading when the
 * visitor ends lands nowhere ([VisitorFiles]).
 */
internal class ImageLoader private constructor(context: Context) {
    private val app = context.applicationContext
    private val errors = CoroutineExceptionHandler { _, error -> AndroidLogger.debug("An image could not be loaded: $error") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private val memory = object : LruCache<String, Bitmap>(memoryBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The disk cache's folder (`cacheDir/deskwoot/images`). */
    val diskFolder: File get() = File(app.cacheDir, "deskwoot/images")

    /** Resolved on the I/O dispatcher: the cache folder is created on use (a purge may have removed it). */
    private val folder: File get() = diskFolder.also { it.mkdirs() }

    /** A picture already in memory, for a synchronous first frame. */
    fun cached(key: String, maxSize: Int): Bitmap? = memory.get(memoryKey(key, maxSize))

    /**
     * Whether pictures and files of [session]'s inbox may stay on disk: not
     * in a cookieless inbox, and not before the inbox's settings are known.
     */
    fun keepsOnDisk(session: ChatSession): Boolean = session.state.value.config?.cookielessEnabled == false

    /**
     * Loads [link] (a server path or an absolute URL) at most [maxSize]
     * pixels on its longer side for [owner] and hands it to [onResult] on the
     * main thread; null when it could not be loaded, or when the visitor
     * ended while it loaded. [key] names the picture for the caches (an
     * attachment's id: its signed link changes).
     */
    fun load(owner: View, session: ChatSession, key: String, link: String, maxSize: Int, onResult: (Bitmap?) -> Unit) {
        cancel(owner)
        val mKey = memoryKey(key, maxSize)
        memory.get(mKey)?.let {
            onResult(it)
            return
        }
        owner.setTag(R.id.dw_tag_image_request, mKey)
        val stamp = VisitorFiles.stamp()
        val job = scope.launch {
            val loaded = withContext(Dispatchers.IO) { fetchAndDecode(session, key, link, maxSize, stamp) }
            if (owner.getTag(R.id.dw_tag_image_request) != mKey) return@launch
            owner.setTag(R.id.dw_tag_image_job, null)
            // The visitor ended while it loaded: the picture was theirs, and nothing of it is kept or shown.
            val bitmap = loaded?.takeIf { VisitorFiles.sameVisitor(stamp) }
            if (bitmap != null) memory.put(mKey, bitmap)
            onResult(bitmap)
        }
        owner.setTag(R.id.dw_tag_image_job, job)
    }

    /** Stops the request of [owner], if any (a recycled row, a closed screen). */
    fun cancel(owner: View) {
        (owner.getTag(R.id.dw_tag_image_job) as? Job)?.cancel()
        owner.setTag(R.id.dw_tag_image_job, null)
        owner.setTag(R.id.dw_tag_image_request, null)
    }

    /**
     * The visitor on screen is forgotten: no picture of theirs stays in
     * memory, and none still loading for them is kept, shown or written (the
     * files go with [com.deskwoot.sdk.ui.files.Files.purge]).
     */
    fun clearMemory() {
        VisitorFiles.visitorEnded()
        memory.evictAll()
    }

    /**
     * The original file of [key] in the disk cache, fetched when needed:
     * for the viewer's share and for opening files. Null on any failure, and
     * when the visitor's files were purged since [stamp]; never throws. Only
     * for an inbox that [keepsOnDisk].
     */
    suspend fun file(
        session: ChatSession,
        key: String,
        link: String,
        maxBytes: Int = MAX_FILE_BYTES,
        stamp: VisitorFiles.Stamp = VisitorFiles.stamp(),
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = folder
            val file = File(dir, hash(key))
            if (file.isFile && file.length() > 0) {
                file.setLastModified(System.currentTimeMillis())
                return@withContext file
            }
            if (!download(session, link, maxBytes, file, stamp)) return@withContext null
            trim(dir)
            file
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // A SecurityException of a locked down folder, or the like.
            null
        }
    }

    /**
     * Downloads [link] into [target] through a part file next to it, which
     * replaces nothing until it is complete and lands only when the
     * visitor's files were not purged since [stamp]. False on any failure;
     * never throws.
     */
    suspend fun download(session: ChatSession, link: String, maxBytes: Int, target: File, stamp: VisitorFiles.Stamp): Boolean = withContext(Dispatchers.IO) {
        var part: File? = null
        try {
            val dir = checkNotNull(target.parentFile).also { it.mkdirs() }
            val temp = File.createTempFile("dw-", ".part", dir)
            part = temp
            session.fetchFileTo(link, maxBytes, temp) && temp.length() > 0 && VisitorFiles.place(temp, target, stamp)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            part?.delete()
        }
    }

    private suspend fun fetchAndDecode(session: ChatSession, key: String, link: String, maxSize: Int, stamp: VisitorFiles.Stamp): Bitmap? {
        if (keepsOnDisk(session)) {
            val file = file(session, key, link, MAX_IMAGE_BYTES, stamp) ?: return null
            return decode(file, maxSize)
        }
        // Memory only: the picture passes through a temporary file that goes as soon as it is decoded.
        return withContext(Dispatchers.IO) {
            var part: File? = null
            try {
                val dir = File(app.cacheDir, TEMPORARY_FOLDER).also { it.mkdirs() }
                val temp = File.createTempFile("dw-", ".part", dir)
                part = temp
                if (session.fetchFileTo(link, MAX_IMAGE_BYTES, temp) && temp.length() > 0) decode(temp, maxSize) else null
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            } finally {
                part?.delete()
            }
        }
    }

    /** Keeps the disk cache under its limit, oldest files first. */
    private fun trim(dir: File) = trimFolder(dir, MAX_DISK_BYTES)

    internal companion object {
        const val MAX_IMAGE_BYTES: Int = 15 * 1024 * 1024
        const val MAX_FILE_BYTES: Int = 50 * 1024 * 1024
        const val MAX_DISK_BYTES: Long = 50L * 1024 * 1024

        /** Pictures of a cookieless inbox pass through here on their way into memory (`cacheDir/deskwoot/temporary`). */
        const val TEMPORARY_FOLDER: String = "deskwoot/temporary"

        @Volatile
        private var instance: ImageLoader? = null

        fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
            instance ?: ImageLoader(context).also { instance = it }
        }

        private fun memoryBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L * 1024 * 1024).toInt()

        /** Sizes in steps of 64 px share a memory entry. */
        private fun memoryKey(key: String, maxSize: Int): String = key + "@" + ((maxSize + 63) / 64) * 64

        fun hash(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        /**
         * The power of 2 a picture of [width] by [height] is decoded at so
         * that its longer side is at most [maxSize] (a
         * 4000 by 3000 photo in a viewer of 2400 is decoded at 2000, not at
         * full size).
         */
        fun sampleSize(width: Int, height: Int, maxSize: Int): Int {
            val longer = maxOf(width, height)
            val limit = maxSize.coerceAtLeast(1)
            var sample = 1
            while (longer / sample > limit && sample < 1 shl 16) sample *= 2
            return sample
        }

        /** [file] as a bitmap of at most [maxSize] on its longer side, upright; null when it is no picture or memory runs out. */
        fun decode(file: File, maxSize: Int): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize) }
                BitmapFactory.decodeFile(file.path, options)?.let { rotated(it, file) }
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }

        /**
         * Photos from phone cameras carry their orientation in EXIF (turned or
         * mirrored); BitmapFactory ignores it. Too little memory to turn it:
         * as stored.
         */
        private fun rotated(bitmap: Bitmap, file: File): Bitmap {
            val matrix = PhotoScaler.orientationMatrix(PhotoScaler.orientationOf(file))
            if (matrix.isIdentity) return bitmap
            return try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
            } catch (_: OutOfMemoryError) {
                bitmap
            }
        }

        /**
         * Keeps the files under [dir] (all levels) at most [maxBytes]: the
         * oldest go first until a quarter is free again. [keep] files stay
         * whatever their age. Blocking: call it off the main thread.
         */
        fun trimFolder(dir: File, maxBytes: Long, keep: (File) -> Boolean = { false }) {
            try {
                val files = dir.walkTopDown().filter { it.isFile && !keep(it) }.toList()
                var total = files.sumOf { it.length() }
                if (total <= maxBytes) return
                for (f in files.sortedBy { it.lastModified() }) {
                    total -= f.length()
                    f.delete()
                    if (total <= maxBytes * 3 / 4) break
                }
            } catch (_: RuntimeException) {
                // A folder that cannot be read is left as it is.
            }
        }
    }
}
