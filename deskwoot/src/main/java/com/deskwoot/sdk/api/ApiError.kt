package com.deskwoot.sdk.api

import com.deskwoot.sdk.model.array
import com.deskwoot.sdk.model.isTrue
import com.deskwoot.sdk.model.obj
import com.deskwoot.sdk.model.strings
import com.deskwoot.sdk.model.text
import org.json.JSONObject
import java.io.IOException

/**
 * A refusal of the server: the HTTP status and the machine `code`. The SDK
 * branches on these two only, never on the English [serverMessage], which
 * is for logs and the few refusals that carry no code.
 */
internal data class ApiError(
    val status: Int,
    val code: String? = null,
    /** `Retry-After` in whole seconds, when the server sent one. */
    val retryAfterSeconds: Int? = null,
    /** The pre chat fields a first message lacked; null when the answer names none. */
    val missingFields: List<String>? = null,
    /** The transcript route asks for an address. */
    val needsEmail: Boolean = false,
    val serverMessage: String? = null,
    /** The body was the API's JSON object, not an edge page or nothing (an identity refusal needs it). */
    val hasJsonBody: Boolean = false,
) {
    val isServerError: Boolean get() = status >= 500

    internal companion object {
        fun from(status: Int, body: JSONObject?, retryAfter: String?): ApiError = ApiError(
            status = status,
            // Ably's REST errors nest the code: { error: { code, message } }.
            code = body?.text("code") ?: body?.obj("error")?.opt("code")?.toString(),
            retryAfterSeconds = retryAfter?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull(),
            missingFields = body?.array("missingFields")?.strings(),
            needsEmail = body?.isTrue("needsEmail") == true,
            serverMessage = body?.text("error"),
            hasJsonBody = body != null,
        )
    }
}

/** The outcome of one call: a decoded answer, a refusal, or no answer at all. Retries live in the engine. */
internal sealed class ApiResult<out T> {
    data class Success<out T>(val value: T) : ApiResult<T>()

    data class Failure(val error: ApiError) : ApiResult<Nothing>()

    /** No HTTP answer: offline, a timeout, a dropped connection. */
    class NetworkFailure(val cause: Throwable?) : ApiResult<Nothing>() {
        /**
         * The device reached no server at all (no network, a timeout, a
         * dropped connection). Not so for an answer that was not the route's
         * ([MalformedAnswer]: a captive portal's page, a redirect, a broken
         * body) or a platform quirk: those say nothing about the connection.
         */
        val isNoConnection: Boolean get() = cause is IOException && cause !is MalformedAnswer

        override fun toString(): String = "NetworkFailure(${cause?.javaClass?.simpleName})"
    }

    val valueOrNull: T? get() = (this as? Success)?.value

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
        is NetworkFailure -> this
    }
}
