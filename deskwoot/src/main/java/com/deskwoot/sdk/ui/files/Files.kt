package com.deskwoot.sdk.ui.files

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.deskwoot.sdk.AndroidLogger
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.engine.UploadSource
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.image.ImageLoader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

/** Where a file to send comes from: the attachment menu's 3 entries. */
internal enum class AttachSource { PHOTOS, CAMERA, FILES }

/** A file the visitor picked, ready for the upload. [temporary]: a copy of the SDK's own, deleted once its upload ended. */
internal class Picked(val source: UploadSource, val name: String, val mimeType: String, val temporary: File? = null)

/**
 * Files in and out of the chat, with no permission of the SDK's own: the
 * system photo picker, the camera through `ACTION_IMAGE_CAPTURE` into a
 * file shared through [DeskwootFileProvider] (the CAMERA permission is
 * asked for only when the app itself declares it, since the system then
 * refuses the capture without it), the document picker, and opening or
 * sharing a file of the chat with the apps that handle its type.
 *
 * A pick outlives the Activity: the screen that asked is
 * kept as its [Route] and the camera's file as its path, both in the saved
 * state, and the result goes to [deliver] with that route, also when it
 * arrives in a new Activity after a recreate or after the process died.
 * Nothing here throws into the app: IO errors, a missing app or a refused
 * grant only mean nothing happens.
 */
internal class Files(
    private val activity: ComponentActivity,
    private val sessionOf: () -> ChatSession?,
    private val deliver: (Route?, Picked) -> Unit,
) {
    private val errors = CoroutineExceptionHandler { _, error -> AndroidLogger.debug("A file of the chat could not be handled: $error") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private val authority = activity.packageName + ".deskwoot.files"
    private var destroyed = false

    /** The screen that asked for the pick in progress. */
    private var requester: Route? = null

    /** The file the camera app writes into, while it is open. */
    internal var cameraFile: File? = null
        private set

    private val photos = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::picked) }
    private val documents = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::picked) }
    private val camera = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraFile
        cameraFile = null
        if (file != null) cameraDone(file, taken)
    }
    private val cameraPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    init {
        latest = WeakReference(this)
    }

    fun cameraAvailable(): Boolean = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** A file from [source] for the screen of [from], filtered by the inbox's types. */
    fun pick(source: AttachSource, allowedTypes: List<String>, from: Route?) {
        requester = from
        try {
            when (source) {
                AttachSource.PHOTOS -> {
                    val videos = allowedTypes.any { it.startsWith("video/") || it == "*/*" || it == "*" }
                    val type = if (videos) ActivityResultContracts.PickVisualMedia.ImageAndVideo else ActivityResultContracts.PickVisualMedia.ImageOnly
                    photos.launch(PickVisualMediaRequest.Builder().setMediaType(type).build())
                }
                AttachSource.CAMERA -> if (needsCameraPermission()) cameraPermission.launch(Manifest.permission.CAMERA) else launchCamera()
                AttachSource.FILES -> documents.launch(allowedTypes.map(::documentType).distinct().ifEmpty { listOf("*/*") }.toTypedArray())
            }
        } catch (_: RuntimeException) {
            // No app on the device handles this (ActivityNotFoundException), or it refused: the menu entry does nothing.
        }
    }

    /** The pick in progress, for the saved state. */
    fun save(out: Bundle) {
        cameraFile?.let { out.putString(KEY_CAMERA, it.path) }
        requester?.let { out.putString(KEY_REQUESTER, it.encode()) }
    }

    /** The pick of the Activity before this one: its result may arrive now. */
    fun restore(saved: Bundle?) {
        saved ?: return
        saved.getString(KEY_CAMERA)?.let { path -> if (cameraFile == null) cameraFile = File(path) }
        saved.getString(KEY_REQUESTER)?.let { if (requester == null) requester = Route.decode(it) }
    }

    /** An app that declares CAMERA must hold it, or the system refuses the capture intent (Intent docs, ACTION_IMAGE_CAPTURE). */
    private fun needsCameraPermission(): Boolean {
        val declared = try {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(Manifest.permission.CAMERA) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return declared && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
    }

    private fun launchCamera() {
        val context = activity.applicationContext
        scope.launch {
            // The folder, the file and the provider's address are disk work.
            val target = withContext(Dispatchers.IO) {
                try {
                    val dir = File(sharedRoot(context), CAMERA_FOLDER).also { it.mkdirs() }
                    val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                    file to FileProvider.getUriForFile(context, authority, file)
                } catch (_: RuntimeException) {
                    null
                }
            } ?: return@launch
            cameraFile = target.first
            try {
                camera.launch(target.second)
            } catch (_: RuntimeException) {
                // No camera app.
                cameraFile = null
            }
        }
    }

    /**
     * Name and type of a picked file, read off the main thread. A picture,
     * from the library or chosen as a file, goes through [PhotoScaler]
     * first: upright and without its location when it needs scaling or
     * carries one, else as it is.
     */
    private fun picked(uri: Uri) {
        val from = requester
        requester = null
        val resolver = activity.contentResolver
        val context = activity.applicationContext
        work.launch {
            val result = withContext(Dispatchers.IO) {
                var name: String? = null
                try {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0)
                    }
                } catch (_: Exception) {
                    // A provider without the column: the name comes from the type below.
                }
                val type = try {
                    resolver.getType(uri)
                } catch (_: RuntimeException) {
                    null
                } ?: name?.let(::typeOfName) ?: "application/octet-stream"
                val prepared = PhotoScaler.prepare(context, { resolver.openInputStream(uri) }, type)
                if (prepared != null) {
                    Picked(UploadSource.LocalFile(prepared), PhotoScaler.jpegName(name ?: "photo"), "image/jpeg", temporary = prepared)
                } else {
                    Picked(UploadSource.Content(uri), name ?: "file", type)
                }
            }
            handOver(from, result)
        }
    }

    /** A camera shot follows the rule of a library photo; the original goes as soon as a prepared copy replaces it. */
    private fun cameraDone(file: File, taken: Boolean) {
        val from = requester
        requester = null
        val context = activity.applicationContext
        work.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (!taken || file.length() <= 0) {
                        file.delete()
                        return@withContext null
                    }
                    val prepared = PhotoScaler.prepare(context, file, "image/jpeg")
                    if (prepared != null) {
                        file.delete()
                        Picked(UploadSource.LocalFile(prepared), file.name, "image/jpeg", temporary = prepared)
                    } else {
                        Picked(UploadSource.LocalFile(file), file.name, "image/jpeg", temporary = file)
                    }
                } catch (_: RuntimeException) {
                    file.delete()
                    null
                }
            } ?: return@launch
            handOver(from, result)
        }
    }

    /** To this Activity, or to the one that replaced it while the file was read; with none, the copy goes. */
    private fun handOver(from: Route?, result: Picked) {
        val target = if (!destroyed) this else latest?.get()?.takeUnless { it.destroyed }
        if (target != null) {
            target.deliver(from, result)
        } else {
            result.temporary?.let { temp -> work.launch(Dispatchers.IO) { temp.delete() } }
        }
    }

    /** Opens a file of the chat with the app for its type; without one, its signed link in the browser. */
    fun open(ui: Ui, key: String, link: String, name: String?, mimeType: String?) {
        val session = sessionOf() ?: return
        val context = activity.applicationContext
        scope.launch {
            val type = mimeType ?: name?.let(::typeOfName) ?: "application/octet-stream"
            val uri = sharedUri(context, ui.images, session, key, link, name)
            if (uri != null) {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri(name, uri)
                if (start(intent)) return@launch
            }
            val web = session.absoluteUrl(link)
            if (web.startsWith("https://") || web.startsWith("http://")) start(Intent(Intent.ACTION_VIEW, Uri.parse(web)))
        }
    }

    /** Shares a file of the chat (the image viewer's share button). */
    fun share(images: ImageLoader, key: String, link: String, name: String?, mimeType: String) {
        val session = sessionOf() ?: return
        val context = activity.applicationContext
        scope.launch {
            val uri = sharedUri(context, images, session, key, link, name) ?: return@launch
            val send = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(name, uri)
            start(Intent.createChooser(send, null))
        }
    }

    /** Starts [intent]; false when nothing handles it or the system refused (no app, a grant, an exposed file). */
    private fun start(intent: Intent): Boolean = try {
        if (!destroyed) activity.startActivity(intent)
        !destroyed
    } catch (_: RuntimeException) {
        false
    }

    /**
     * The provider's address of the file under its own name, made off the
     * main thread: a copy of the cached file, or in a cookieless inbox a
     * temporary copy downloaded for this alone, which goes when the
     * messenger closes and at the next start. Null when the visitor ended
     * while it loaded.
     */
    private suspend fun sharedUri(context: Context, images: ImageLoader, session: ChatSession, key: String, link: String, name: String?): Uri? {
        val stamp = VisitorFiles.stamp()
        val out = if (images.keepsOnDisk(session)) {
            val cached = images.file(session, key, link, ImageLoader.MAX_FILE_BYTES, stamp) ?: return null
            withContext(Dispatchers.IO) {
                try {
                    sharedCopy(context, cached, key, name, stamp)
                } catch (_: IOException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            }
        } else {
            val target = temporaryCopy(context, key, name)
            val ready = withContext(Dispatchers.IO) { target.isFile && target.length() > 0 } ||
                images.download(session, link, ImageLoader.MAX_FILE_BYTES, target, stamp)
            target.takeIf { ready }
        } ?: return null
        return withContext(Dispatchers.IO) {
            try {
                FileProvider.getUriForFile(context, authority, out)
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    /**
     * Stops what belongs to this Activity; a pick in progress still reaches
     * the next one. When the messenger closes for good, the temporary copies
     * of a cookieless inbox go.
     */
    fun destroy() {
        destroyed = true
        scope.cancel()
        if (activity.isFinishing && !activity.isChangingConfigurations) {
            val context = activity.applicationContext
            work.launch(Dispatchers.IO) { removeTemporaryCopies(context) }
        }
    }

    internal companion object {
        private const val KEY_CAMERA = "dw_files_camera"
        private const val KEY_REQUESTER = "dw_files_requester"
        private const val CAMERA_FOLDER = "camera"
        private const val TEMPORARY_FOLDER = "temporary"

        /** Copies shared with other apps: at most this much, oldest first out. */
        const val MAX_SHARED_BYTES: Long = 50L * 1024 * 1024

        /** Reads a picked file after its Activity is gone; it hands over to [latest]. */
        private val work = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, error -> AndroidLogger.debug("A picked file could not be read: $error") },
        )

        /** The newest instance, for a result read while its Activity was recreated. */
        @Volatile
        private var latest: WeakReference<Files>? = null

        /** The folder the provider shares from (`res/xml/dw_file_paths.xml`). */
        fun sharedRoot(context: Context): File = File(context.cacheDir, "deskwoot/shared")

        /** Copies of a cookieless inbox's files, shared only while the messenger is open. */
        private fun temporaryRoot(context: Context): File = File(sharedRoot(context), TEMPORARY_FOLDER)

        /**
         * [cached] under the attachment's own name in the provider's folder,
         * the folder kept under [MAX_SHARED_BYTES] (the camera's files and the
         * temporary copies stay). Null when the visitor's files were purged
         * since [stamp]: the copy was the old visitor's. Blocking: call it off
         * the main thread.
         */
        fun sharedCopy(context: Context, cached: File, key: String, name: String?, stamp: VisitorFiles.Stamp = VisitorFiles.stamp()): File? {
            val root = sharedRoot(context)
            val dir = File(root, ImageLoader.hash(key)).also { it.mkdirs() }
            val out = File(dir, safeName(name))
            if (!out.isFile || out.length() != cached.length()) {
                val part = File.createTempFile("dw-", ".part", dir)
                try {
                    cached.copyTo(part, overwrite = true)
                    if (!VisitorFiles.place(part, out, stamp)) return null
                } finally {
                    part.delete()
                }
            }
            out.setLastModified(System.currentTimeMillis())
            val temporary = temporaryRoot(context)
            ImageLoader.trimFolder(root, MAX_SHARED_BYTES) { it == out || it.parentFile?.name == CAMERA_FOLDER || it.parentFile?.parentFile == temporary }
            return out
        }

        /** Where a cookieless inbox's file [key] goes under its own [name] to be opened or shared. Not created yet. */
        fun temporaryCopy(context: Context, key: String, name: String?): File =
            File(File(temporaryRoot(context), ImageLoader.hash(key)), safeName(name))

        /** The copies a cookieless inbox opened or shared. Blocking: call it off the main thread. */
        fun removeTemporaryCopies(context: Context) {
            try {
                temporaryRoot(context).deleteRecursively()
            } catch (_: RuntimeException) {
                // A folder the SDK cannot touch holds nothing of the SDK's.
            }
        }

        private val startCleaned = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Once per process, at the first setup: the temporary copies of the
         * last run go, and so do upload copies an ended process left behind
         * (older than this call; a pick of this run is newer).
         */
        fun cleanAtStart(context: Context) {
            if (!startCleaned.compareAndSet(false, true)) return
            val app = context.applicationContext
            val startedAt = System.currentTimeMillis()
            work.launch(Dispatchers.IO) { cleanAfterEarlierRun(app, startedAt) }
        }

        /**
         * What [cleanAtStart] deletes: the temporary copies, the pictures that
         * were passing through on their way into memory, and upload copies
         * older than [startedAt]. Blocking.
         */
        fun cleanAfterEarlierRun(context: Context, startedAt: Long) {
            removeTemporaryCopies(context)
            try {
                File(context.cacheDir, ImageLoader.TEMPORARY_FOLDER).deleteRecursively()
                PhotoScaler.uploadFolder(context).listFiles()?.forEach { if (it.lastModified() < startedAt) it.delete() }
            } catch (_: RuntimeException) {
                // Nothing to clean.
            }
        }

        /**
         * A file name of the chat as a name on disk: no folder separators or
         * characters other apps choke on, at most 120 characters, and never
         * empty, "." or "..": those become "file".
         */
        fun safeName(name: String?): String {
            val cleaned = name.orEmpty().trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).trim()
            return if (cleaned.isEmpty() || cleaned.all { it == '.' }) "file" else cleaned
        }

        /**
         * Everything the SDK wrote for the visitor: pictures and files of the
         * chat, copies shared with other apps, camera shots and upload copies.
         * A picture or file still loading writes nothing afterwards.
         * Blocking: call it off the main thread.
         */
        fun purge(context: Context) {
            try {
                VisitorFiles.purge(File(context.cacheDir, "deskwoot"))
            } catch (_: RuntimeException) {
                // A folder the SDK cannot touch holds nothing of the SDK's.
            }
        }
    }

    // The picker only knows whole types such as all images; a narrower pattern
    // widens to its whole type, and the upload checks the rest.
    private fun documentType(type: String): String = when {
        type == "*" -> "*/*"
        type.contains('*') -> type.substringBefore('/') + "/*"
        else -> type
    }

    private fun typeOfName(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
}
