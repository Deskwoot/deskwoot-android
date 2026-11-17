package com.deskwoot.sdk.engine

import kotlin.random.Random

/**
 * The two clocks the SDK needs. [now] is wall time in epoch milliseconds,
 * only for what a person reads (a pending bubble's time). [elapsed] is a
 * monotonic reading for every duration (budgets, throttles, holds, stale
 * answer guards, token lifetimes): a device clock that jumps never
 * stretches or cuts them. Server times are compared only with
 * server times. Tests drive both from virtual time.
 */
internal interface Clock {
    fun now(): Long

    fun elapsed(): Long
}

/** The device's clocks; [elapsed] keeps counting while the device sleeps. */
internal object DeviceClock : Clock {
    override fun now(): Long = System.currentTimeMillis()

    override fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()
}

/**
 * When a message goes out again on its own (as the web client does it):
 * after a 5xx, a network error or a try without an answer
 * in time, with the same id, until a 2-minute budget is used up. A 4xx
 * (429 included) is final.
 */
internal object SendRetry {
    /** How long a message keeps trying: a database restart takes up to about a minute. */
    const val BUDGET_MILLIS: Long = 120_000

    /** One try once the server confirmed the visitor's bearer. */
    const val ATTEMPT_TIMEOUT_MILLIS: Int = 30_000

    /**
     * A new visitor's first message, before the bearer is confirmed, gets
     * the whole budget for one try: the server may take longer than 30 s
     * while it makes the contact.
     */
    const val FIRST_SEND_TIMEOUT_MILLIS: Int = 120_000

    private const val FIRST_PAUSE_MILLIS = 1_000L
    private const val MAX_PAUSE_MILLIS = 15_000L
    private const val MAX_RETRY_AFTER_MILLIS = 30_000L

    /**
     * The pause after [tries] tries: the server's Retry-After (at most
     * 30 s), else 1, 2, 4, 8, then 15 s, each up to a quarter shorter at
     * random so waiting visitors do not all come back at once.
     */
    fun pause(tries: Int, retryAfterSeconds: Int?, random: Random): Long {
        if (retryAfterSeconds != null) return minOf(retryAfterSeconds * 1_000L, MAX_RETRY_AFTER_MILLIS)
        val full = minOf(FIRST_PAUSE_MILLIS shl (tries - 1).coerceIn(0, 4), MAX_PAUSE_MILLIS)
        return (full * (1.0 - random.nextDouble() * 0.25)).toLong()
    }
}
