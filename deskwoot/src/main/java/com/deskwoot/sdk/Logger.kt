package com.deskwoot.sdk

import android.util.Log

/**
 * Where the SDK tells a developer what is wrong. [error] is for something
 * the app's developer must fix (an unknown widget token, a refused identity
 * and why), [debug] for the SDK's own diagnosis. Messages name statuses and
 * codes, never the bearer, a signature, a token or anything the visitor wrote.
 */
internal interface Logger {
    fun error(message: String)

    fun debug(message: String)
}

/** Logcat under the tag `Deskwoot`, each line prefixed `[Deskwoot]`, as [Deskwoot.logLevel] allows. Off by default. */
internal object AndroidLogger : Logger {
    private const val TAG = "Deskwoot"

    override fun error(message: String) {
        if (Deskwoot.logLevel != Deskwoot.LogLevel.NONE) Log.e(TAG, "[Deskwoot] ${LogRedaction.redact(message)}")
    }

    override fun debug(message: String) {
        if (Deskwoot.logLevel == Deskwoot.LogLevel.DEBUG) Log.d(TAG, "[Deskwoot] ${LogRedaction.redact(message)}")
    }
}

/** For tests: says nothing. */
internal object SilentLogger : Logger {
    override fun error(message: String) = Unit

    override fun debug(message: String) = Unit
}

/**
 * A last guard behind the rule that no call site puts a secret into a
 * message: anything shaped like a bearer (`wb_` and 20 or more characters,
 * or a UUID) and the value of an `accessToken`, `contactIdentifier` or
 * `identifierHash` parameter is masked.
 */
internal object LogRedaction {
    private val patterns = listOf(
        Regex("wb_[A-Za-z0-9_-]{20,}"),
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
        Regex("(accessToken|contactIdentifier|identifierHash)=[^&\\s]+"),
        Regex("\\b[0-9a-fA-F]{64}\\b"),
    )

    fun redact(text: String): String = patterns.fold(text) { out, pattern -> pattern.replace(out, "[redacted]") }
}
