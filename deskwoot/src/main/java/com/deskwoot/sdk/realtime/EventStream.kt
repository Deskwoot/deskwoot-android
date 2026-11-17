package com.deskwoot.sdk.realtime

import com.deskwoot.sdk.api.disconnectOnCancel
import com.deskwoot.sdk.api.readCapped
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** An open `text/event-stream` response. Reads block on a socket, so they run off the main thread. */
internal interface EventStream {
    val status: Int

    /** The next piece of text, or null at the end of the stream. Throws an IOException on a drop. */
    suspend fun read(): String?

    /** The body of a refused stream (status 300 and up), for its Ably error code. */
    suspend fun readError(): String

    fun close()
}

/** Opens event streams. An interface so the realtime tests feed scripted text. */
internal fun interface EventStreamConnector {
    suspend fun open(url: String, headers: Map<String, String>): EventStream
}

/** [EventStreamConnector] over `HttpURLConnection`; a cancelled read disconnects the socket. */
internal class UrlConnectionEventStreamConnector(
    private val userAgent: String,
    private val io: CoroutineDispatcher,
) : EventStreamConnector {
    override suspend fun open(url: String, headers: Map<String, String>): EventStream = withContext(io) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        // Ably sends a heartbeat every 15 s; the client's own idle check (45 s) drops a silent stream first.
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "text/event-stream")
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        val status = try {
            disconnectOnCancel(connection) { connection.responseCode }
        } catch (e: Throwable) {
            connection.disconnect()
            throw e
        }
        UrlConnectionEventStream(connection, status, io)
    }

    private class UrlConnectionEventStream(
        private val connection: HttpURLConnection,
        override val status: Int,
        private val io: CoroutineDispatcher,
    ) : EventStream {
        private var reader: InputStreamReader? = null
        private val buffer = CharArray(8 * 1024)

        override suspend fun read(): String? = withContext(io) {
            disconnectOnCancel(connection) {
                val r = reader ?: InputStreamReader(connection.inputStream, Charsets.UTF_8).also { reader = it }
                val n = r.read(buffer)
                if (n < 0) null else String(buffer, 0, n)
            }
        }

        override suspend fun readError(): String = withContext(io) {
            disconnectOnCancel(connection) {
                connection.errorStream?.use { String(readCapped(it, 64 * 1024), Charsets.UTF_8) }.orEmpty()
            }
        }

        override fun close() {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}
