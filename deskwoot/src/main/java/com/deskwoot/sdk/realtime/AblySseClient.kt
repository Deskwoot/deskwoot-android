package com.deskwoot.sdk.realtime

import com.deskwoot.sdk.Logger
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.api.HttpClient
import com.deskwoot.sdk.api.HttpRequest
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.Clock
import com.deskwoot.sdk.model.int
import com.deskwoot.sdk.model.long
import com.deskwoot.sdk.model.obj
import com.deskwoot.sdk.model.parseJsonObject
import com.deskwoot.sdk.model.string
import com.deskwoot.sdk.model.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * [RealtimeClient] over Ably's SSE endpoint, without the Ably SDK (a port
 * of the transport Deskwoot's Flutter app uses):
 *
 * - the token route's answer is a TokenRequest the server signed (or a
 *   ready token); a TokenRequest is traded for a token at
 *   `POST {rest}/keys/{keyName}/requestToken`, and the token is used until
 *   30 s before it expires, measured on the monotonic clock;
 * - one stream `GET {realtime}/event-stream?v=1.2&channels=...&accessToken=...&heartbeats=true`
 *   carries the channel; a reconnect within 2 minutes asks to resume
 *   after the last event id (`lastEvent`), unless the stream ended with
 *   an error event;
 * - a drop, a silent stream (45 s without even a heartbeat), a server
 *   error or any exception reconnects after 1 s, doubling to at most 15 s;
 *   the backoff starts over only after a delivered event or a stream that
 *   lived 30 s, so a proxy that accepts and closes at once never makes it
 *   spin;
 * - an expired or revoked token (Ably 40140 to 40149) is traded again at
 *   once; any 401 drops the cached token; a refused channel (40160), a 403,
 *   or a token route that refuses for good stops realtime for this
 *   conversation, the poll carries on, and the caller hears of it through
 *   `onStopped`;
 * - message ids already handed on are dropped (the last 500).
 *
 * Runs in [scope], whose dispatcher is the main thread: blocking reads
 * happen inside the [EventStreamConnector], events reach the listener on
 * main.
 */
internal class AblySseClient(
    private val http: HttpClient,
    private val connector: EventStreamConnector,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val logger: Logger,
    private val realtimeBase: String = "https://realtime.ably.io",
    private val restBase: String = "https://rest.ably.io",
    private val idleTimeoutMillis: Long = 45_000,
) : RealtimeClient {

    override fun open(
        channel: String,
        auth: suspend () -> ApiResult<JSONObject>,
        listener: RealtimeListener,
        onStopped: () -> Unit,
    ): RealtimeConnection {
        val job = scope.launch {
            Subscription(channel, auth, listener).run()
            // Only a refusal for good ends the loop; a cancel never gets here.
            onStopped()
        }
        return RealtimeConnection { job.cancel() }
    }

    private enum class Outcome { DROP, RENEW, STOP }

    private sealed class TokenResult {
        /** [validUntil] is on the monotonic clock. */
        class Ready(val token: String, val validUntil: Long) : TokenResult()

        object Retry : TokenResult()

        object Stop : TokenResult()
    }

    private class Chunk(val text: String?)

    private inner class Subscription(
        private val channel: String,
        private val auth: suspend () -> ApiResult<JSONObject>,
        private val listener: RealtimeListener,
    ) {
        private var token: String? = null
        private var tokenValidUntil = 0L
        private var lastEventId: String? = null
        private var droppedAt: Long? = null
        private var failures = 0
        private val seen = LinkedHashSet<String>()

        suspend fun run() {
            var renewalsInARow = 0
            while (true) {
                val current = token
                val usable = if (current != null && clock.elapsed() < tokenValidUntil - TOKEN_MARGIN_MILLIS) current else null
                val accessToken = usable ?: when (val result = guarded({ TokenResult.Retry }) { fetchToken() }) {
                    is TokenResult.Ready -> {
                        token = result.token
                        tokenValidUntil = result.validUntil
                        result.token
                    }
                    TokenResult.Stop -> {
                        logger.debug("Realtime stopped: the token was refused; polling carries on")
                        return
                    }
                    TokenResult.Retry -> {
                        backOff()
                        continue
                    }
                }
                val droppedSince = droppedAt
                val resumeFrom = lastEventId?.takeIf { droppedSince == null || clock.elapsed() - droppedSince < RESUME_WINDOW_MILLIS }
                when (guarded({ Outcome.DROP }) { stream(accessToken, resumeFrom) }) {
                    Outcome.STOP -> {
                        logger.debug("Realtime stopped: the channel was refused; polling carries on")
                        return
                    }
                    Outcome.RENEW -> {
                        token = null
                        renewalsInARow++
                        // A token refused again right after a fresh one: wait, do not spin.
                        if (renewalsInARow > 1) backOff()
                    }
                    Outcome.DROP -> {
                        renewalsInARow = 0
                        if (droppedAt == null) droppedAt = clock.elapsed()
                        backOff()
                    }
                }
            }
        }

        /**
         * Runs one step; any exception but a cancel is a drop and the loop
         * goes on: realtime never ends because of a surprise.
         */
        private suspend fun <T> guarded(onError: () -> T, block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Realtime stream dropped (${e.javaClass.simpleName})")
            onError()
        }

        private suspend fun backOff() {
            failures++
            delay(minOf(1_000L shl (failures - 1).coerceAtMost(4), MAX_BACKOFF_MILLIS))
        }

        /** The stream works: the backoff and the resume window start over. */
        private fun healthy() {
            failures = 0
            droppedAt = null
        }

        /** Opens one stream and reads it until it ends; says what to do next. */
        private suspend fun stream(accessToken: String, resumeFrom: String?): Outcome {
            val url = buildString {
                append(realtimeBase).append("/event-stream?v=1.2")
                append("&channels=").append(WidgetApi.encode(channel))
                append("&accessToken=").append(WidgetApi.encode(accessToken))
                append("&heartbeats=true")
                if (resumeFrom != null) append("&lastEvent=").append(WidgetApi.encode(resumeFrom))
            }
            val stream = connector.open(url, emptyMap())
            try {
                if (stream.status >= 300) {
                    // Any 401 means this token is no good, whatever the code says.
                    if (stream.status == 401) token = null
                    val error = parseJsonObject(stream.readError())?.obj("error")
                    val outcome = classify(error?.int("code"), stream.status)
                    // A stream refused while resuming would be refused the same way again: the next one starts fresh.
                    if (outcome == Outcome.DROP && resumeFrom != null) lastEventId = null
                    return outcome
                }
                val openedAt = clock.elapsed()
                val outcome = try {
                    read(stream)
                } finally {
                    if (clock.elapsed() - openedAt >= HEALTHY_UPTIME_MILLIS) healthy()
                }
                return outcome
            } finally {
                stream.close()
            }
        }

        private suspend fun read(stream: EventStream): Outcome {
            val parser = SseParser()
            while (true) {
                val chunk = withTimeoutOrNull(idleTimeoutMillis) { Chunk(stream.read()) } ?: return Outcome.DROP
                val text = chunk.text ?: return Outcome.DROP
                for (event in parser.add(text)) {
                    event.id?.let { lastEventId = it }
                    if (event.event == "error") {
                        // The position named with an error is no place to resume from.
                        lastEventId = null
                        val error = parseJsonObject(event.data)
                        val status = error?.int("statusCode") ?: 0
                        if (status == 401) token = null
                        return classify(error?.int("code"), status)
                    }
                    if (event.event != null && event.event != "message") continue
                    val message = parseAblyEnvelope(event.data) ?: continue
                    if (message.channel != channel) continue
                    val id = message.id
                    if (id != null) {
                        if (!seen.add(id)) continue
                        if (seen.size > SEEN_IDS) seen.remove(seen.first())
                    }
                    healthy()
                    listener.onEvent(RealtimeEvent(message.name, message.data as? JSONObject))
                }
            }
        }

        private fun classify(code: Int?, status: Int): Outcome = when {
            code != null && code in 40140..40149 -> Outcome.RENEW
            code == 40160 || (status == 401 && code == null) -> Outcome.STOP
            status == 403 -> Outcome.STOP
            else -> Outcome.DROP
        }

        private suspend fun fetchToken(): TokenResult {
            val request = when (val result = auth()) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return if (refusedForGood(result.error.status)) TokenResult.Stop else TokenResult.Retry
                is ApiResult.NetworkFailure -> return TokenResult.Retry
            }
            val ready = request.string("token")
            if (ready != null) return TokenResult.Ready(ready, validUntil(request))
            val keyName = request.text("keyName") ?: return TokenResult.Stop
            val trade = http.send(
                HttpRequest(
                    method = "POST",
                    url = "$restBase/keys/${WidgetApi.encode(keyName)}/requestToken",
                    headers = mapOf("Content-Type" to WidgetApi.JSON, "X-Ably-Version" to "2"),
                    body = request.toString().toByteArray(Charsets.UTF_8),
                ),
            )
            return when (trade) {
                is ApiResult.Success -> {
                    val body = trade.value
                    val token = body?.string("token") ?: return TokenResult.Retry
                    TokenResult.Ready(token, validUntil(body))
                }
                is ApiResult.Failure -> if (refusedForGood(trade.error.status)) TokenResult.Stop else TokenResult.Retry
                is ApiResult.NetworkFailure -> TokenResult.Retry
            }
        }

        /**
         * How long a token lives, from Ably's own `issued` and `expires`
         * (both Ably's clock), counted from now on the monotonic clock: a
         * device clock that is wrong never makes a token look fresh or stale.
         */
        private fun validUntil(details: JSONObject): Long {
            val issued = details.long("issued")
            val expires = details.long("expires")
            val life = if (issued != null && expires != null && expires > issued) expires - issued else DEFAULT_TOKEN_LIFE_MILLIS
            return clock.elapsed() + life
        }

        /** A 4xx other than 408 and 429 answers the same next time (404 for a chat continued by email). */
        private fun refusedForGood(status: Int): Boolean = status in 400..499 && status != 408 && status != 429
    }

    internal companion object {
        const val TOKEN_MARGIN_MILLIS: Long = 30_000
        const val DEFAULT_TOKEN_LIFE_MILLIS: Long = 10 * 60_000
        const val MAX_BACKOFF_MILLIS: Long = 15_000
        const val RESUME_WINDOW_MILLIS: Long = 2 * 60_000
        const val SEEN_IDS: Int = 500

        /** A stream that lived this long counts as working even without an event. */
        const val HEALTHY_UPTIME_MILLIS: Long = 30_000
    }
}
