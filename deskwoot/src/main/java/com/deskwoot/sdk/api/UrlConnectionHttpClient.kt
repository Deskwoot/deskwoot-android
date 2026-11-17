package com.deskwoot.sdk.api

import com.deskwoot.sdk.model.parseJsonObject
import android.net.TrafficStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * [HttpClient] over `HttpURLConnection`, run on [io]. No cookies, no
 * `Origin`, no `Authorization`: the widget API needs none. A
 * cancelled call disconnects its socket, so nothing keeps running for a
 * session that moved on.
 *
 * `timeoutMillis` is the limit for the whole call, not only the wait for
 * the next byte: a connection that trickles never holds a send for longer.
 * An upload names its own limit, which grows with the file, and a stall
 * limit (`stallTimeoutMillis`): it ends as soon as no byte of its body went
 * out for that long, so a weak but working link is not cut off by a fixed
 * deadline and a dead one is noticed early. A download of a file follows
 * the same rule from the other side: it ends after 30 s without a byte
 * received, or after 60 s plus 1 s per 20 KB of the size the server
 * announced. Redirects are not followed (a captive portal or a moved host
 * is no answer), and a 2xx body must be empty or a JSON object.
 */
internal open class UrlConnectionHttpClient(
    private val userAgent: String,
    private val io: CoroutineDispatcher,
    /** A monotonic clock in milliseconds, for the stall limit; tests pass their virtual time. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : HttpClient, FileFetcher {

    override suspend fun send(request: HttpRequest): ApiResult<JSONObject?> = withContext(io) {
        val stall = request.stallTimeoutMillis
        withTimeoutOrNull(request.timeoutMillis.toLong()) { if (stall == null) exchange(request) else exchangeWatched(request, stall.toLong()) }
            ?: ApiResult.NetworkFailure(SocketTimeoutException("No answer within ${request.timeoutMillis} ms"))
    }

    /** How far an upload's body got, told from the I/O thread that writes it. */
    private class BodyWatch(startedAt: Long, done: Boolean) {
        @Volatile
        var lastByteAt: Long = startedAt

        @Volatile
        var bodyDone: Boolean = done

        @Volatile
        var stalled: Boolean = false
    }

    /**
     * [exchange] that ends when no byte of the body went out for
     * [stallMillis] (connecting included). Once the whole body is out, the
     * wait for the answer is limited only by the call's own limit.
     */
    private suspend fun exchangeWatched(request: HttpRequest, stallMillis: Long): ApiResult<JSONObject?> = coroutineScope {
        val watch = BodyWatch(now(), done = request.body?.isEmpty() != false)
        val tracked = request.withBodyProgress { sent, total ->
            watch.lastByteAt = now()
            if (sent >= total) watch.bodyDone = true
            request.onBodyProgress?.invoke(sent, total)
        }
        val call = async { exchange(tracked) }
        val watchdog = launch {
            while (!watch.bodyDone) {
                val wait = watch.lastByteAt + stallMillis - now()
                if (wait <= 0) {
                    watch.stalled = true
                    // Disconnects the socket: the blocked write throws at once.
                    call.cancel()
                    return@launch
                }
                delay(wait)
            }
        }
        try {
            call.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!watch.stalled) throw e
            ApiResult.NetworkFailure(SocketTimeoutException("No byte sent for $stallMillis ms"))
        } finally {
            watchdog.cancel()
        }
    }

    /** One request and its answer, without the deadline [send] puts around it. Open for the deadline's test only. */
    protected open suspend fun exchange(request: HttpRequest): ApiResult<JSONObject?> {
        return try {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            disconnectOnCancel(connection) {
                connection.requestMethod = request.method
                // An upload that has not even connected after its stall limit has sent no byte either.
                connection.connectTimeout = request.stallTimeoutMillis?.coerceAtMost(request.timeoutMillis) ?: request.timeoutMillis
                connection.readTimeout = request.timeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/json")
                for ((name, value) in request.headers) connection.setRequestProperty(name, value)
                val body = request.body
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { out -> writeBody(out, body, request.onBodyProgress) }
                }
                val status = connection.responseCode
                // A redirect is answered without reading its body.
                if (status in 300..399) return@disconnectOnCancel interpret(status, "", null)
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.use { String(readCapped(it, MAX_RESPONSE_BYTES), Charsets.UTF_8) }.orEmpty()
                interpret(status, text, connection.getHeaderField("Retry-After"))
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            ApiResult.NetworkFailure(e)
        } catch (e: RuntimeException) {
            // A malformed URL or a platform quirk of HttpURLConnection: no answer.
            currentCoroutineContext().ensureActive()
            ApiResult.NetworkFailure(e)
        }
    }

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray? = withContext(io) {
        val out = ByteArrayOutputStream()
        if (watchedDownload { progress -> receive(url, maxBytes, out, progress) }) out.toByteArray() else null
    }

    override suspend fun fetchTo(url: String, maxBytes: Int, target: java.io.File): Boolean = withContext(io) {
        try {
            target.outputStream().use { out -> watchedDownload { progress -> receive(url, maxBytes, out, progress) } }
        } catch (_: IOException) {
            false
        }
    }

    /** What a download tells its watch, from the I/O thread that reads it. */
    internal interface DownloadProgress {
        /** The answer's head arrived, with the size the server announced (0 or less: none). */
        fun announced(bytes: Long)

        /** Bytes of the body arrived. */
        fun received()
    }

    /** How far a download got. */
    private class DownloadWatch(startedAt: Long) {
        @Volatile
        var lastByteAt: Long = startedAt

        @Volatile
        var deadline: Long = Long.MAX_VALUE

        @Volatile
        var expired: Boolean = false
    }

    /**
     * Runs one try of a download under its limits: it ends after
     * [HttpRequest.DOWNLOAD_STALL_MILLIS] without a byte received (connecting
     * included), or once the limit of the announced size
     * ([HttpRequest.downloadTimeoutMillis]) passed, whichever comes first.
     * False when it ended so; the caller deletes what was written.
     */
    private suspend fun watchedDownload(block: suspend (DownloadProgress) -> Boolean): Boolean = coroutineScope {
        val started = now()
        val watch = DownloadWatch(started)
        val progress = object : DownloadProgress {
            override fun announced(bytes: Long) {
                watch.lastByteAt = now()
                HttpRequest.downloadTimeoutMillis(bytes)?.let { watch.deadline = started + it }
            }

            override fun received() {
                watch.lastByteAt = now()
            }
        }
        val call = async { block(progress) }
        val watchdog = launch {
            while (true) {
                val at = now()
                val until = minOf(watch.lastByteAt + HttpRequest.DOWNLOAD_STALL_MILLIS, watch.deadline)
                if (at >= until) {
                    watch.expired = true
                    // Disconnects the socket: the blocked read throws at once.
                    call.cancel()
                    return@launch
                }
                delay(until - at)
            }
        }
        try {
            call.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!watch.expired) throw e
            false
        } finally {
            watchdog.cancel()
        }
    }

    /**
     * One try of a download into [out], never more than [maxBytes], telling
     * [progress] what arrived; false on any failure. Open for the tests of
     * the download's limits only.
     */
    protected open suspend fun receive(url: String, maxBytes: Int, out: java.io.OutputStream, progress: DownloadProgress): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            disconnectOnCancel(connection) {
                // The socket's own limits follow the stall rule; the watch adds the total limit.
                connection.connectTimeout = HttpRequest.DOWNLOAD_STALL_MILLIS
                connection.readTimeout = HttpRequest.DOWNLOAD_STALL_MILLIS
                connection.useCaches = false
                // A signed file link is answered directly; a redirect to another host is refused.
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                val status = connection.responseCode
                // getContentLengthLong needs Android 7; the header itself works everywhere and beyond 2 GB.
                val declared = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                progress.announced(declared)
                if (status !in 200..299) return@disconnectOnCancel false
                if (declared > maxBytes) return@disconnectOnCancel false
                connection.inputStream.use { input -> copyCapped(input, out, maxBytes, progress::received) }
                true
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (e: RuntimeException) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /** Writes [body] in slices, so an upload can show how far it got. */
    private fun writeBody(out: java.io.OutputStream, body: ByteArray, progress: ((Long, Long) -> Unit)?) {
        if (progress == null) {
            out.write(body)
            return
        }
        val total = body.size.toLong()
        var offset = 0
        while (offset < body.size) {
            val n = minOf(BODY_SLICE_BYTES, body.size - offset)
            out.write(body, offset, n)
            offset += n
            progress(offset.toLong(), total)
        }
    }

    internal companion object {
        /**
         * The answer to one call, by status and body: a 2xx is a JSON object
         * or nothing (204); any other 2xx body (HTML of a captive portal, a
         * JSON array, text) is no usable answer. A refusal keeps whether its
         * body was the API's JSON, which an identity refusal needs.
         */
        fun interpret(status: Int, text: String, retryAfter: String?): ApiResult<JSONObject?> {
            // Redirects are not followed: a captive portal or a moved host is no answer.
            if (status in 300..399) return ApiResult.NetworkFailure(MalformedAnswer("Redirect (HTTP $status) refused"))
            val json = parseJsonObject(text)
            if (status in 200..299) {
                if (json == null && text.isNotBlank()) return ApiResult.NetworkFailure(MalformedAnswer("A $status answer that is not a JSON object"))
                return ApiResult.Success(json)
            }
            return ApiResult.Failure(ApiError.from(status, json, retryAfter))
        }

        /** A poll of a continuous chat carries up to 300 rows; nothing the SDK reads comes near this. */
        const val MAX_RESPONSE_BYTES: Int = 16 * 1024 * 1024
        const val BODY_SLICE_BYTES: Int = 32 * 1024
    }
}

/**
 * Runs a blocking [block] on [connection] and disconnects it when the
 * calling coroutine is cancelled, which makes the blocked read or write
 * throw at once. Blocking socket calls ignore coroutine cancellation on
 * their own.
 */
internal suspend fun <T> disconnectOnCancel(connection: HttpURLConnection, block: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) connection.disconnect()
        }
    }
    // The socket connects inside block(): it carries the SDK's traffic tag (an app's own network
    // statistics and StrictMode's untagged socket check see whose it is); the thread's tag is put back.
    val previousTag = if (ON_DEVICE) TrafficStats.getThreadStatsTag() else 0
    if (ON_DEVICE) TrafficStats.setThreadStatsTag(SOCKET_TAG)
    try {
        block()
    } finally {
        if (ON_DEVICE) TrafficStats.setThreadStatsTag(previousTag)
        finished.set(true)
        watcher.cancel()
    }
}

/** False only on the plain JVM of the unit tests, where android.net is a stub without behaviour. */
private val ON_DEVICE: Boolean = android.os.Build.VERSION.SDK_INT > 0

/** The traffic stats tag of every socket the SDK opens ("DESK" in ASCII). */
internal const val SOCKET_TAG: Int = 0x4445534B

/** Copies at most [limit] bytes from [input] to [out], telling [arrived] after each read; a longer body throws [MalformedAnswer]. */
internal fun copyCapped(input: InputStream, out: java.io.OutputStream, limit: Int, arrived: () -> Unit = {}) {
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        arrived()
        total += n
        if (total > limit) throw MalformedAnswer("Response larger than $limit bytes")
        out.write(buffer, 0, n)
    }
}

/** Reads at most [limit] bytes; a longer body is a broken answer (not a lost connection), not something to hold in memory. */
internal fun readCapped(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (out.size() + n > limit) throw MalformedAnswer("Response larger than $limit bytes")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
