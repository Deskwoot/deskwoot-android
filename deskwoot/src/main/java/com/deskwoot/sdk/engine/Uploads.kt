package com.deskwoot.sdk.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A file the visitor picked: bytes in memory, a file on disk, or a `content://` link of a picker. */
internal sealed class UploadSource {
    class Bytes(val data: ByteArray) : UploadSource()

    class LocalFile(val file: File) : UploadSource()

    class Content(val uri: Uri) : UploadSource()
}

internal sealed class UploadRead {
    class Data(val bytes: ByteArray) : UploadRead()

    object TooLarge : UploadRead()

    object Unreadable : UploadRead()
}

/** Reads an [UploadSource] off the main thread, never more than the inbox's limit plus one byte. */
internal fun interface UploadReader {
    suspend fun read(source: UploadSource, maxBytes: Int): UploadRead
}

/** Reads files and `content://` links with the application's ContentResolver. */
internal class ContentUploadReader(private val context: Context, private val io: CoroutineDispatcher) : UploadReader {
    override suspend fun read(source: UploadSource, maxBytes: Int): UploadRead = withContext(io) {
        try {
            when (source) {
                is UploadSource.Bytes -> if (source.data.size > maxBytes) UploadRead.TooLarge else UploadRead.Data(source.data)
                is UploadSource.LocalFile -> source.file.inputStream().use { readLimited(it, maxBytes) }
                is UploadSource.Content -> context.contentResolver.openInputStream(source.uri)?.use { readLimited(it, maxBytes) }
                    ?: UploadRead.Unreadable
            }
        } catch (_: IOException) {
            UploadRead.Unreadable
        } catch (_: SecurityException) {
            UploadRead.Unreadable
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): UploadRead {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > maxBytes) return UploadRead.TooLarge
            out.write(buffer, 0, n)
        }
        return UploadRead.Data(out.toByteArray())
    }
}

/** File rules the server applies, checked before an upload. */
internal object UploadRules {
    /** The extensions the attachments route takes; any other name is refused. */
    val ALLOWED_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico",
        "mp4", "webm", "mov", "avi", "mp3", "wav", "ogg", "m4a",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "txt", "csv", "json", "xml", "zip", "gz", "tar", "rar", "eml", "msg",
    )

    /** The extension the server takes for each MIME type an app may hand over. */
    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/pjpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",
        "image/bmp" to "bmp",
        "image/x-ms-bmp" to "bmp",
        "image/x-icon" to "ico",
        "image/vnd.microsoft.icon" to "ico",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/quicktime" to "mov",
        "video/x-msvideo" to "avi",
        "video/avi" to "avi",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/ogg" to "ogg",
        "application/ogg" to "ogg",
        "audio/mp4" to "m4a",
        "audio/m4a" to "m4a",
        "audio/x-m4a" to "m4a",
        "application/pdf" to "pdf",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "text/comma-separated-values" to "csv",
        "application/json" to "json",
        "application/xml" to "xml",
        "text/xml" to "xml",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/gzip" to "gz",
        "application/x-gzip" to "gz",
        "application/x-tar" to "tar",
        "application/vnd.rar" to "rar",
        "application/x-rar-compressed" to "rar",
        "message/rfc822" to "eml",
        "application/vnd.ms-outlook" to "msg",
    )

    // Whether the inbox takes mimeType: an exact match, a whole type
    // ("image/" followed by a star), any type (star slash star, or a star),
    // or a trailing ".*" (the attachments route's mimeMatchesPatterns). The
    // config's list is stricter than the route's own default, so it is the
    // one to follow.
    fun mimeAllowed(mimeType: String, patterns: List<String>): Boolean {
        val type = mimeType.trim().lowercase()
        for (raw in patterns) {
            val p = raw.trim().lowercase()
            if (p == "*/*" || p == "*" || p == type) return true
            if (p.endsWith("/*") && type.startsWith(p.dropLast(1))) return true
            if (p.endsWith(".*") && type.startsWith(p.dropLast(1))) return true
        }
        return false
    }

    /**
     * A file name the server takes, whose extension matches the data: the
     * server checks the extension, and a photo picker may hand out JPEG data
     * under an `IMG_1234.HEIC` name (HEIC is refused). A known
     * MIME type sets the extension; otherwise the name must already end in
     * an allowed one. Null when neither holds: the server would refuse the
     * file after the whole upload, so it is refused before.
     */
    fun fileName(name: String?, mimeType: String, fallbackBase: String): String? {
        val raw = name.orEmpty().substringAfterLast('/').trim()
        val current = raw.substringAfterLast('.', "").lowercase().takeIf { raw.contains('.') }
        val ext = EXTENSIONS[mimeType.trim().lowercase()]
        if (ext == null) return raw.takeIf { current != null && current in ALLOWED_EXTENSIONS }
        if (current == ext || (ext == "jpg" && current == "jpeg")) return raw
        val stripped = raw.replaceFirst(Regex("\\.[^.]*$"), "")
        val base = if (raw.isEmpty()) fallbackBase else stripped.ifEmpty { raw }
        return "$base.$ext"
    }
}
