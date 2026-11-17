package com.deskwoot.sdk.realtime

import com.deskwoot.sdk.model.long
import com.deskwoot.sdk.model.parseJsonObject
import com.deskwoot.sdk.model.string
import org.json.JSONObject
import java.io.IOException

/** One server sent event. */
internal data class SseEvent(val event: String?, val id: String?, val data: String)

/** A line or an event over [SseParser.MAX_LINE_CHARS]: the stream is broken and is dropped. */
internal class SseTooLong : IOException("An event stream line or event over 1 MiB")

/**
 * The text/event-stream parser (WHATWG): `event:`, `id:` and `data:` lines,
 * several data lines joined by a newline, a blank line ends an event, a
 * line starting with `:` is a comment (Ably's heartbeat). A line ends with
 * CR LF, LF or a lone CR. Text arrives in chunks that may split a line
 * anywhere (also between CR and LF); the rest waits for the next chunk.
 * An event without data lines is not dispatched. A line or an event over
 * 1 MiB throws [SseTooLong], so a stream that never sends a line end
 * cannot fill the memory.
 */
internal class SseParser {
    private val buffer = StringBuilder()
    private var event: String? = null
    private var id: String? = null
    private val data = ArrayList<String>()
    private var dataChars = 0

    /** The last chunk ended with a CR: a LF at the start of the next one belongs to it. */
    private var afterCr = false

    fun add(chunk: String): List<SseEvent> {
        var text = chunk
        if (afterCr && text.startsWith("\n")) text = text.substring(1)
        afterCr = false
        buffer.append(text)
        val out = ArrayList<SseEvent>()
        var start = 0
        while (true) {
            val end = lineEnd(start)
            if (end < 0) break
            val line = buffer.substring(start, end)
            start = if (buffer[end] == '\r') {
                if (end + 1 < buffer.length) {
                    if (buffer[end + 1] == '\n') end + 2 else end + 1
                } else {
                    afterCr = true
                    end + 1
                }
            } else {
                end + 1
            }
            take(line, out)
        }
        buffer.delete(0, start)
        if (buffer.length > MAX_LINE_CHARS) throw SseTooLong()
        return out
    }

    private fun lineEnd(from: Int): Int {
        for (i in from until buffer.length) {
            val c = buffer[i]
            if (c == '\n' || c == '\r') return i
        }
        return -1
    }

    private fun take(line: String, out: MutableList<SseEvent>) {
        if (line.isEmpty()) {
            if (data.isNotEmpty()) out += SseEvent(event, id, data.joinToString("\n"))
            event = null
            data.clear()
            dataChars = 0
            return
        }
        if (line.startsWith(":")) return
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                dataChars += value.length + 1
                if (dataChars > MAX_LINE_CHARS) throw SseTooLong()
                data += value
            }
            // The id stays for the following events too, as the standard says.
            "id" -> id = value
        }
    }

    internal companion object {
        /** 1 MiB of text; Ably's messages are at most 64 KiB. */
        const val MAX_LINE_CHARS: Int = 1024 * 1024
    }
}

/** An Ably message from the stream: its event [name], the decoded [data] and Ably's [id] for dedupe. */
internal data class AblyMessage(val channel: String, val name: String, val data: Any?, val id: String?, val timestamp: Long?)

/**
 * Reads Ably's SSE envelope `{ id, channel, name, data, encoding, timestamp }`.
 * JSON the server published arrives as a string with encoding `json`; a
 * qualified channel name (`[?rewind=2m]name`) names the channel after the
 * brackets. Null for anything that is not an envelope.
 */
internal fun parseAblyEnvelope(text: String): AblyMessage? {
    val json = parseJsonObject(text) ?: return null
    var channel = json.string("channel") ?: return null
    if (channel.startsWith("[")) {
        val end = channel.indexOf(']')
        if (end > 0) channel = channel.substring(end + 1)
    }
    var data: Any? = json.opt("data")
    val encoding = json.string("encoding")
    if (data is String && encoding != null && encoding.substringAfterLast('/') == "json") {
        data = parseJsonObject(data) ?: data
    }
    if (data == JSONObject.NULL) data = null
    return AblyMessage(channel, json.string("name") ?: "", data, json.string("id"), json.long("timestamp"))
}
