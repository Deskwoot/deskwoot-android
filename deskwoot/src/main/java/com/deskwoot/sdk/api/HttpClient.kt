package com.deskwoot.sdk.api

import org.json.JSONObject

/** One HTTP call. [body] is sent as is; JSON calls set `Content-Type` in [headers]. */
internal class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** The limit for the whole call, the answer included. */
    val timeoutMillis: Int = JSON_TIMEOUT_MILLIS,
    /**
     * Told how much of [body] went out (bytes sent, bytes in all), on the
     * I/O thread, for an upload's progress. Null for every other call.
     */
    val onBodyProgress: ((sent: Long, total: Long) -> Unit)? = null,
    /**
     * Set for uploads: the call ends when no byte of [body] went out for this
     * long (connecting included); once the whole body is out, only
     * [timeoutMillis] limits the wait for the answer. Null for every other call.
     */
    val stallTimeoutMillis: Int? = null,
) {
    /** The same request with [progress] told instead of [onBodyProgress] (which [progress] passes on). */
    fun withBodyProgress(progress: (sent: Long, total: Long) -> Unit): HttpRequest =
        HttpRequest(method, url, headers, body, timeoutMillis, progress, stallTimeoutMillis)

    internal companion object {
        /** JSON calls: 15 s. */
        const val JSON_TIMEOUT_MILLIS: Int = 15_000

        /** A download with no byte received for this long has stalled (connecting included). */
        const val DOWNLOAD_STALL_MILLIS: Int = 30_000

        /**
         * The whole limit of one try of a download whose size the server
         * announced: 60 s plus 1 s per 20 KB, as for an upload. Null without
         * an announced size: only the stall rule and the size cap hold then.
         */
        fun downloadTimeoutMillis(announcedBytes: Long): Long? =
            if (announcedBytes <= 0) null else UPLOAD_BASE_MILLIS + announcedBytes * 1_000L / UPLOAD_BYTES_PER_SECOND

        /** An upload with no byte sent for this long has stalled. */
        const val UPLOAD_STALL_MILLIS: Int = 30_000

        /** An upload's limit starts here (a virus scan may keep the answer waiting up to 45 s)... */
        const val UPLOAD_BASE_MILLIS: Int = 60_000

        /** ...and grows by 1 s for each this many bytes of the file (a 5 MB file: about 5 minutes). */
        const val UPLOAD_BYTES_PER_SECOND: Int = 20 * 1024

        /** The whole limit of one try of an upload of [fileBytes]: no fixed deadline for a slow but working link. */
        fun uploadTimeoutMillis(fileBytes: Int): Int =
            (UPLOAD_BASE_MILLIS + fileBytes.coerceAtLeast(0).toLong() * 1_000L / UPLOAD_BYTES_PER_SECOND)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * The one request function every call goes through: JSON in, JSON out, a
 * typed result. A 2xx answers [ApiResult.Success] with the decoded object
 * (null for an empty body or one that is not an object), any other status
 * [ApiResult.Failure], no answer [ApiResult.NetworkFailure]. An interface so
 * every engine test runs against a scripted fake.
 */
internal interface HttpClient {
    suspend fun send(request: HttpRequest): ApiResult<JSONObject?>
}

/**
 * A 2xx whose body is not what the route promises (not JSON, not an
 * object, a mandatory key missing) or a redirect: no usable answer, never
 * a success.
 */
internal class MalformedAnswer(message: String) : java.io.IOException(message)

/**
 * Downloads a file of the chat (an image, an attachment, a face) through the
 * SDK's own HTTP stack: the bytes, or null for no answer, a refusal or a body
 * larger than [maxBytes]. Signed file links need no other credential. A try
 * ends after [HttpRequest.DOWNLOAD_STALL_MILLIS] without a byte received, or
 * at [HttpRequest.downloadTimeoutMillis] of the announced size.
 */
internal fun interface FileFetcher {
    suspend fun fetch(url: String, maxBytes: Int): ByteArray?

    /**
     * The same file written into [target] as it arrives, so a large one is
     * never held in memory; false on any failure (a partly written [target]
     * is the caller's to delete). This default goes through [fetch].
     */
    suspend fun fetchTo(url: String, maxBytes: Int, target: java.io.File): Boolean {
        val bytes = fetch(url, maxBytes) ?: return false
        return try {
            target.writeBytes(bytes)
            true
        } catch (_: java.io.IOException) {
            false
        }
    }
}
