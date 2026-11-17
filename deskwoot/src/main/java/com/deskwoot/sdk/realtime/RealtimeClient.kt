package com.deskwoot.sdk.realtime

import com.deskwoot.sdk.api.ApiResult
import org.json.JSONObject

/** One event of a conversation's channel: its name and its JSON payload. */
internal data class RealtimeEvent(val name: String, val data: JSONObject?)

internal fun interface RealtimeListener {
    /** Called on the main thread. */
    fun onEvent(event: RealtimeEvent)
}

internal fun interface RealtimeConnection {
    /** Stops the stream for good: no reconnect, no more events. */
    fun close()
}

/**
 * Instant rows, typing and status for one conversation. It only
 * makes things faster: the poll is the floor and always runs, so a stream
 * that drops, is refused or never connects is never an error anybody sees.
 */
internal interface RealtimeClient {
    /**
     * Subscribes to [channel]. [auth] answers what `GET /api/widget/realtime-token`
     * answered; it is called again for every new token, because a
     * TokenRequest's nonce is single use. [onStopped] runs on the
     * main thread when realtime stopped for good on its own (a refused token
     * or channel), so the caller can let go of the connection; it never runs
     * after [RealtimeConnection.close].
     */
    fun open(
        channel: String,
        auth: suspend () -> ApiResult<JSONObject>,
        listener: RealtimeListener,
        onStopped: () -> Unit,
    ): RealtimeConnection
}
