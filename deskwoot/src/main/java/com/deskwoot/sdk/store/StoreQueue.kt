package com.deskwoot.sdk.store

import com.deskwoot.sdk.AndroidLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Every change of what the SDK keeps on the device (visitor records,
 * thread caches, the pending push removals, the pictures and file copies
 * of the chat), of every inbox, one after the other in the order asked for.
 *
 * The steps run in the queue's own [scope], which no end of a session
 * cancels: the end of a visitor that started runs to its end also when the
 * session is stopped or replaced right after (a `setup` with another widget
 * token, a second logout). A session reads the store only after [idle], so
 * it never reads back what a step asked for before its start deletes. A
 * step works on files only and never waits for the main thread, so a caller
 * may wait for it from there for a moment.
 */
internal class StoreQueue(private val scope: CoroutineScope) {
    private val lock = Any()
    private var tail: Job? = null

    /** Runs [step] after every step asked for before it; an error ends that step only. */
    fun run(step: suspend () -> Unit): Job = synchronized(lock) {
        val previous = tail
        val job = scope.launch {
            previous?.join()
            step()
        }
        tail = job
        job
    }

    /** Waits for every step asked for before this call. */
    suspend fun idle() {
        synchronized(lock) { tail }?.join()
    }

    internal companion object {
        /** The queue of the process, on [Dispatchers.IO]. */
        val shared: StoreQueue by lazy {
            StoreQueue(
                CoroutineScope(
                    SupervisorJob() + Dispatchers.IO +
                        CoroutineExceptionHandler { _, error -> AndroidLogger.debug("A step on the SDK's files failed: $error") },
                ),
            )
        }
    }
}
