package com.deskwoot.sdk.engine

import androidx.annotation.MainThread
import com.deskwoot.sdk.Logger
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.store.PushRemoval
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.store.VisitorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Push registration: this phone takes the visitor's replies as pushes while
 * the app is closed, and never stays registered to a visitor the device forgot.
 *
 * - The app's FCM token lives in memory only: the app hands it over at
 *   every start and from `onNewToken`.
 * - The phone is registered whenever a token and a bearer the server knows
 *   exist (never a placeholder), again after every change of the bearer
 *   (the first message of a new visitor, an identity verification, a
 *   switch of user), of the token and of the visitor's language, also in
 *   the background (FCM hands a new token to a service without a screen).
 *   A cookieless inbox registers too: the row is the server's, the phone
 *   stores nothing of the visitor.
 * - Before the first registration request for a bearer leaves the device,
 *   the visitor's record says so ([beforeRegister]); a cookieless inbox
 *   keeps that in memory.
 * - One (token, bearer, language) is registered once per process. A
 *   failure (no answer, 408, 429, 5xx) is tried again on the next start or
 *   foreground, a 429 not before its Retry-After; a 400 or 413 is not tried
 *   again for the same token and bearer. Nothing of it is ever shown.
 * - Before a bearer a registration may exist for leaves the device
 *   ([forget]: a logout, another user, a refused identity, an anonymous
 *   message over a dormant identity, a cookieless inbox), it becomes a
 *   pending [PushRemoval] in the store, whether or not a token is known (a
 *   cookieless inbox keeps it in memory, sent while the process lives). A
 *   bearer no registration was sent for leaves nothing behind. The list
 *   holds the removals of every inbox of the app: each goes out, naming its
 *   own inbox, whenever a token is known (at once, when the token arrives,
 *   at every start and every return to the foreground, and when a push
 *   names that forgotten visitor), and leaves the list only when the server
 *   answered 2xx. At most [PushRemoval.MAX_PER_INBOX] wait per inbox, the
 *   oldest is dropped first, and one older than 30 days is deleted unsent.
 *   A bearer that becomes the visitor's again (the same user signs in
 *   again) leaves the list and registers again.
 *
 * Main thread only. Registrations and removals go out one at a time through
 * one queue, in the order they were asked for: a registration still on its
 * way when its visitor ends is followed by that visitor's removal, never
 * the other way round, and its answer then counts for nothing. The list on
 * the disk changes only in steps of [onDisk], each a read and a write.
 */
@MainThread
internal class PushRegistrar(
    private val api: WidgetApi,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val logger: Logger,
    /** The app's package name: the registration's `appId`. */
    private val appId: String,
    /** The bearer to register: one the server knows; null while there is none (nobody wrote yet). */
    private val bearer: () -> String?,
    /** The language of the push texts as a tag the server takes; null when none fits. */
    private val locale: () -> String?,
    /** Every answer, for [com.deskwoot.sdk.model.SessionState.offline]. */
    private val onAnswer: (ApiResult<*>) -> Unit,
    /** Where the pending removals are kept. */
    private val store: VisitorStore,
    /** Runs a step on the store after every step asked for before it (the store queue of the process). */
    private val onDisk: (suspend () -> Unit) -> Job,
    /**
     * A registration for this bearer is about to leave: the visitor's flag
     * "a registration was sent for this bearer" is written, and on the disk,
     * when this returns. False: the bearer is no longer the visitor's and
     * nothing goes.
     */
    private val beforeRegister: suspend (String) -> Boolean,
) {
    private val inbox = api.widgetToken

    private data class Registration(val token: String, val bearer: String, val locale: String?) {
        /** The token and the bearer are secrets: never printed. */
        override fun toString(): String = "Registration(token=[redacted], bearer=[redacted], locale=$locale)"
    }

    private sealed class Call {
        /** [generation]: the visitor it was asked for; a later visitor makes it count for nothing. */
        class Register(val registration: Registration, val generation: Int) : Call()

        /**
         * [token] null: the token known when it goes out. [pending]: a record of
         * the list, deleted when the server answered 2xx.
         */
        class Remove(val removal: PushRemoval, val token: String?, val pending: Boolean) : Call()
    }

    /** The app's token; never stored, never logged. */
    var token: String? = null
        private set

    private var registered: Registration? = null
    private var inFlight: Call.Register? = null
    private var failed: Registration? = null
    private var refused: Registration? = null

    /** A failed registration is not tried again before this moment (monotonic clock; a 429's Retry-After). */
    private var retryAt = 0L

    /** Raised whenever the visitor a registration belongs to ends: its answer counts for nothing. */
    private var generation = 0

    private val queue = ArrayDeque<Call>()
    private var worker: Job? = null

    /** Bearers a registration request was started for in this process. Never printed. */
    private val sentFor = HashSet<String>()

    /**
     * The pending removals of every inbox, oldest first: the stored list once
     * [listRead], and those noted in this process (a cookieless inbox's in
     * memory only). Never printed.
     */
    private var removals: List<PushRemoval> = emptyList()
    private var listRead = false
    private var listAsked = false

    /** Removals that went through, or whose visitor came back, before the stored list arrived: not taken back from it. */
    private val doneBeforeRead = ArrayList<PushRemoval>()

    /** Visitor keys of pushes for a forgotten visitor that came before the list was read. */
    private val forgottenKeys = HashSet<String>()

    /** Pending removals in [queue] or on their way. */
    private val removing = ArrayList<PushRemoval>()

    /** The app's token; null or blank takes the phone off the current visitor and forgets the token. */
    fun setToken(value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == token) return
        if (clean == null) {
            val old = token
            val current = bearer()
            if (old != null && current != null) enqueue(Call.Remove(PushRemoval(inbox, current, clock.now()), old, pending = false))
            endRegistration()
            token = null
            return
        }
        token = clean
        readList()
        sendRemovals()
        sync()
    }

    /** A registration request was started for [bearer] in this process. */
    fun sentFor(bearer: String): Boolean = bearer in sentFor

    /**
     * Registers when a token and a bearer exist and this registration is
     * neither done, on its way, waiting for a retry nor refused. Cheap:
     * called after every change of the session.
     */
    fun sync() {
        val current = bearer() ?: return
        // The device knows this visitor again: no removal for them.
        dropRemoval(current)
        val token = token ?: return
        val wanted = Registration(token, current, locale())
        if (wanted == registered || wanted == inFlight?.registration || wanted == failed || wanted == refused) return
        val call = Call.Register(wanted, generation)
        inFlight = call
        enqueue(call)
    }

    /**
     * Every start and every return to the foreground: a registration that
     * failed goes out again (not before a 429's Retry-After), and so does
     * every pending removal. The first start reads the list from the store.
     */
    fun retryFailed() {
        readList()
        if (failed != null && clock.elapsed() >= retryAt) failed = null
        sendRemovals()
        sync()
    }

    /**
     * [bearers], each one a registration may exist for, are about to leave
     * this device. Call it BEFORE the wipe: each becomes a pending removal,
     * written to the store ahead of any later change of the visitor record
     * when [durable] (a cookieless inbox keeps it in memory only), and its
     * removal goes out at once when a token is known.
     */
    fun forget(bearers: Collection<String>, durable: Boolean) {
        val gone = bearers.map(String::trim).filter(String::isNotEmpty).distinct()
        if (gone.isEmpty()) return
        val ours = listOfNotNull(registered?.bearer, inFlight?.registration?.bearer, failed?.bearer, refused?.bearer)
        if (ours.any { it in gone }) endRegistration()
        val now = clock.now()
        val added = gone.map { PushRemoval(inbox, it, now) }
        note(added)
        if (durable) onDisk { addToStore(added, now) }
        // Called while the leaving bearer may still be the one in use: it is not skipped as "current".
        sendRemovals(skipCurrent = false)
    }

    /**
     * The visitor [stored], read from the store by a step of a wipe (this
     * process had not read it yet): when it says a registration was sent,
     * its removal is written in this very step, before the record goes. Runs
     * on the store queue; the part on the main thread is dropped with a
     * stopped session, and the next session sends the record.
     */
    suspend fun forgetStored(stored: VisitorRecord, now: Long) {
        val gone = stored.bearer?.takeIf { stored.pushRegistered } ?: return
        val added = listOf(PushRemoval(inbox, gone, now))
        addToStore(added, now)
        scope.launch {
            note(added)
            sendRemovals(skipCurrent = false)
        }
    }

    /**
     * A push named [key], the visitor key of a visitor this device forgot: a
     * pending removal whose bearer has that key goes out now (once a token is
     * known). Before the list was read it is read first.
     */
    fun removeForgotten(key: String) {
        if (!listRead) {
            forgottenKeys += key
            readList()
        }
        send(removals.filter { VisitorKey.of(it.bearer) == key })
    }

    /** The registration of the visitor that ended counts for nothing; the next visitor registers anew. */
    private fun endRegistration() {
        generation++
        registered = null
        inFlight = null
        failed = null
        refused = null
    }

    private fun note(added: List<PushRemoval>) {
        doneBeforeRead.removeAll { done -> added.any { it.sameAs(done) } }
        removals = PushRemoval.merge(removals, added)
    }

    /** [current] is this inbox's visitor again: its removal leaves the list. */
    private fun dropRemoval(current: String) {
        val back = removals.filter { it.widgetToken == inbox && it.bearer == current }
        if (back.isEmpty()) return
        done(back)
    }

    /** [entries] leave the list, in memory and on the disk. */
    private fun done(entries: List<PushRemoval>) {
        removals = removals.filterNot { entry -> entries.any { it.sameAs(entry) } }
        if (!listRead) doneBeforeRead += entries
        onDisk { deleteFromStore(entries) }
    }

    /** The stored list, once per process: records older than 30 days go unsent, the rest join what this process noted. */
    private fun readList() {
        if (listAsked) return
        listAsked = true
        val now = clock.now()
        onDisk {
            val stored = store.readPushRemovals()
            val fresh = PushRemoval.fresh(stored, now)
            if (fresh.size != stored.size) store.writePushRemovals(fresh)
            scope.launch { listArrived(fresh) }
        }
    }

    private fun listArrived(stored: List<PushRemoval>) {
        listRead = true
        // What the store kept is older than anything noted in this process.
        removals = PushRemoval.merge(stored, removals).filterNot { entry -> doneBeforeRead.any { it.sameAs(entry) } }
        doneBeforeRead.clear()
        bearer()?.let(::dropRemoval)
        sendRemovals()
        if (forgottenKeys.isNotEmpty()) {
            val keys = forgottenKeys.toSet()
            forgottenKeys.clear()
            send(removals.filter { VisitorKey.of(it.bearer) in keys })
        }
    }

    private suspend fun addToStore(added: List<PushRemoval>, now: Long) {
        val stored = store.readPushRemovals()
        val next = PushRemoval.merge(PushRemoval.fresh(stored, now), added)
        if (next != stored) store.writePushRemovals(next)
    }

    private suspend fun deleteFromStore(entries: List<PushRemoval>) {
        val stored = store.readPushRemovals()
        val next = stored.filterNot { entry -> entries.any { it.sameAs(entry) } }
        if (next.size != stored.size) store.writePushRemovals(next)
    }

    /**
     * Every pending removal not already queued, oldest first, when a token is
     * known. [skipCurrent]: this inbox's bearer in use is left out.
     */
    private fun sendRemovals(skipCurrent: Boolean = true) {
        val current = if (skipCurrent) bearer() else null
        send(removals.filterNot { it.widgetToken == inbox && it.bearer == current })
    }

    private fun send(entries: List<PushRemoval>) {
        if (token == null) return
        val now = clock.now()
        for (entry in entries) {
            if (now - entry.at > PushRemoval.MAX_AGE_MILLIS || removing.any { it.sameAs(entry) }) continue
            removing += entry
            enqueue(Call.Remove(entry, token = null, pending = true))
        }
    }

    private fun enqueue(call: Call) {
        queue.addLast(call)
        if (worker?.isActive != true) worker = scope.launch { work() }
    }

    private suspend fun work() {
        while (true) {
            when (val call = queue.removeFirstOrNull() ?: break) {
                is Call.Register -> register(call)
                is Call.Remove -> remove(call)
            }
        }
        worker = null
    }

    private suspend fun register(call: Call.Register) {
        val wanted = call.registration
        // Its visitor ended before it went out: nothing to register, and nothing to remove after it.
        if (call.generation != generation) {
            if (inFlight === call) inFlight = null
            return
        }
        // The record says a registration was sent for this bearer before the request leaves: a logout
        // from here on leaves a removal record, also when the process ends in between.
        sentFor += wanted.bearer
        if (!beforeRegister(wanted.bearer) || call.generation != generation) {
            if (inFlight === call) inFlight = null
            return
        }
        val result = api.registerPushDevice(wanted.bearer, wanted.token, appId, wanted.locale)
        onAnswer(result)
        if (inFlight === call) inFlight = null
        // "Registered as" is kept only while that visitor is still the current one.
        if (call.generation == generation) settle(wanted, result)
        // The bearer, the token or the language may have changed meanwhile.
        sync()
    }

    private suspend fun remove(call: Call.Remove) {
        val entry = call.removal
        if (call.pending && removals.none { it.sameAs(entry) }) {
            // The visitor came back meanwhile (the registration that follows keeps the phone theirs), or it went through.
            removing.removeAll { it.sameAs(entry) }
            return
        }
        val token = call.token ?: token
        if (token == null) {
            if (call.pending) removing.removeAll { it.sameAs(entry) }
            return
        }
        val result = api.removePushDevice(entry.bearer, token, entry.widgetToken)
        onAnswer(result)
        if (!call.pending) return
        removing.removeAll { it.sameAs(entry) }
        if (result is ApiResult.Success) {
            val gone = removals.filter { it.sameAs(entry) }
            if (gone.isNotEmpty()) done(gone)
            logger.debug("Push: this phone no longer takes the replies of a visitor the device forgot")
        } else {
            logger.debug("Push: a removal got no 2xx answer; tried again on the next start, foreground or token")
        }
    }

    private fun settle(done: Registration, result: ApiResult<Unit>) {
        when (result) {
            is ApiResult.Success -> {
                registered = done
                logger.debug("Push: this phone takes the visitor's replies")
            }
            is ApiResult.Failure -> {
                val error = result.error
                if (error.status == 400 || error.status == 413) {
                    refused = done
                    val reason = error.serverMessage?.let { ", $it" }.orEmpty()
                    logger.error(
                        "Deskwoot.setPushToken: the server refused this phone's registration (HTTP ${error.status}$reason). " +
                            "Pass the FCM registration token of this app.",
                    )
                } else {
                    failed = done
                    retryAt = clock.elapsed() + (error.retryAfterSeconds ?: 0) * 1_000L
                    logger.debug("Push: registration got HTTP ${error.status}; tried again on the next start or foreground")
                }
            }
            is ApiResult.NetworkFailure -> {
                failed = done
                retryAt = clock.elapsed()
                logger.debug("Push: registration got no answer; tried again on the next start or foreground")
            }
        }
    }
}
