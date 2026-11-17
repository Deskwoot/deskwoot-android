package com.deskwoot.sdk.engine

import androidx.annotation.MainThread
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.Logger
import com.deskwoot.sdk.api.ApiError
import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.api.SendAnswer
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.model.HelpArticleRef
import com.deskwoot.sdk.model.ConversationStatus
import com.deskwoot.sdk.model.ConversationSummary
import com.deskwoot.sdk.model.CsatState
import com.deskwoot.sdk.model.FieldProblem
import com.deskwoot.sdk.model.FynnAction
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.HelpArticleSummary
import com.deskwoot.sdk.model.IsoTime
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.MessageRole
import com.deskwoot.sdk.model.MessageRules
import com.deskwoot.sdk.model.PendingSend
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.PreChatField
import com.deskwoot.sdk.model.ReplyQuote
import com.deskwoot.sdk.model.ResolveResult
import com.deskwoot.sdk.model.SendFailure
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.model.SendRefusal
import com.deskwoot.sdk.model.SessionFailure
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.model.ThreadSegment
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.model.UploadErrorCode
import com.deskwoot.sdk.model.UploadPhase
import com.deskwoot.sdk.model.UploadState
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.model.int
import com.deskwoot.sdk.model.isTrue
import com.deskwoot.sdk.model.obj
import com.deskwoot.sdk.model.putNullable
import com.deskwoot.sdk.model.string
import com.deskwoot.sdk.model.text
import com.deskwoot.sdk.realtime.RealtimeClient
import com.deskwoot.sdk.realtime.RealtimeConnection
import com.deskwoot.sdk.realtime.RealtimeEvent
import com.deskwoot.sdk.store.CacheSnapshot
import com.deskwoot.sdk.store.DiskCache
import com.deskwoot.sdk.store.StoreQueue
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.store.VisitorStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * The state machine of one visitor of one inbox, kept in step with the
 * server the way the web messenger does it: identity,
 * the restore lookup, the list of conversations, the thread with its poll
 * (the reliable floor) and realtime channel (only faster), sends with one
 * id the server stores once, uploads, typing, the bot's typing hold,
 * queue and handover lines, resolve and reopen, closed chats, the survey,
 * the transcript and the read state.
 *
 * All state lives on [mainDispatcher] and is published as [state] at most
 * once per main loop turn (posted on [postDispatcher]). Work that belongs
 * to the current visitor runs in an identity scope that a logout or a user
 * switch cancels as a whole: no poll, stream, timer or send of one visitor
 * survives into the next.
 */
@MainThread
internal class ConversationEngine(
    private val api: WidgetApi,
    private val realtime: RealtimeClient,
    private val store: VisitorStore,
    private val cache: DiskCache,
    private val clock: Clock,
    private val logger: Logger,
    private val mainDispatcher: CoroutineDispatcher,
    private val postDispatcher: CoroutineDispatcher,
    /** The app's own language as a BCP 47 tag, read whenever it is needed (it can change at runtime). */
    private val appLanguage: () -> String?,
    private val uploadReader: UploadReader,
    /** The app's package name, for the heartbeat. */
    private val appId: String,
    private val sdkVersion: String,
    /** `Deskwoot.language` as the app set it before the session existed. */
    initialLanguage: String? = null,
    /** A debug build of the app: its heartbeat says so and the server leaves it out. */
    private val appDebuggable: Boolean = false,
    /**
     * Whether the app is in front when the session is made. Production passes
     * false and the facade reports the real state right after:
     * nothing polls or streams for an app that was started in the background.
     */
    initialForeground: Boolean = true,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val random: Random = Random.Default,
    parentJob: Job? = null,
    /**
     * Whether the caller runs on the thread the state lives on. Only the
     * calls made for a push ask ([pushVisitor], [isOnScreen]): they may come
     * from a worker thread and then read a snapshot, never the state itself.
     */
    private val onMainThread: () -> Boolean = { true },
    /**
     * Where every change of the store runs: the process's queue, which no
     * stop of this session cancels and whose earlier steps a start waits for.
     */
    private val storeQueue: StoreQueue = StoreQueue.shared,
) {
    private val rootJob = SupervisorJob(parentJob)
    private val errors = CoroutineExceptionHandler { _, error -> logger.debug("Unexpected error in the chat session: $error") }
    private val scope = CoroutineScope(rootJob + mainDispatcher + errors)
    private var identityJob = SupervisorJob(rootJob)
    private var identityScope = CoroutineScope(identityJob + mainDispatcher + errors)

    // ── Session ──────────────────────────────────────────────────────────
    private var phase = Phase.LOADING

    /** Why [phase] is FAILED; null in every other phase. */
    private var failure: SessionFailure? = null
    private var config: WidgetConfig? = null

    /** When the config last came, on the monotonic clock; null before the first. */
    private var configFetchedAt: Long? = null
    private var bootJob: Job? = null
    private var flushPosted = false
    private var heartbeatSent = false
    private var storageForgotten = false

    /** The last call got no answer at all; the next answered one clears it ([SessionState.offline]). */
    private var offline = false

    /** The app's language override: kept in memory for the process, never stored. */
    private var languageOverride: String? = initialLanguage?.trim()?.takeIf { it.isNotEmpty() }

    /** This phone's push registration for the visitor. */
    private val push = PushRegistrar(
        api = api,
        scope = scope,
        clock = clock,
        logger = logger,
        appId = appId,
        bearer = { bearer?.takeUnless { placeholder } },
        // The visitor's language, as a send names it: the push texts speak it.
        locale = { Locales.sendTag(effectiveLocale()) },
        onAnswer = ::noteAnswer,
        store = store,
        onDisk = ::onDisk,
        beforeRegister = ::beforeRegistration,
    )

    private val mutableState = MutableStateFlow(SessionState.initial(Locales.effective(null, pageLanguage())))

    /** The session as the UI draws it. */
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /**
     * A change waits to be published: [state] is older than the engine
     * until the end of this main loop turn (a screen made right after
     * `startNewConversation` draws nothing of the old thread).
     */
    val statePending: Boolean get() = flushPosted

    // ── Identity ─────────────────────────────────────────────────────────
    private var record = VisitorRecord()
    private var recordLoaded = false
    private val identityLock = Mutex()

    /** The bearer the SDK writes as; for a new visitor the placeholder UUID. Never logged. */
    private var bearer: String? = null

    /** [bearer] is a client made placeholder: nobody exists on the server yet. */
    private var placeholder = false

    /** The server confirmed [bearer] in this session; until then one send at a time. */
    private var confirmed = false
    private var verifiedExternalId: String? = null

    /** The user payload verified in this process: the same one is verified only once. */
    private var verifiedUser: DeskwootUser? = null
    private var pendingUser: DeskwootUser? = null

    /** The user payload whose verification is on its way: the same identify again is ignored. */
    private var verifyingUser: DeskwootUser? = null

    /**
     * A stored identity was taken back for the user the app named, and its
     * verification has not answered yet: the identity has not settled, so a
     * tapped conversation waits (a rotated bearer may still replace it).
     */
    private var verifyingTakeBack = false

    /** Keys of earlier bearers of the user in use, replaced by a verification of this process: their pushes are still theirs. */
    private val theirKeys = LinkedHashSet<String>()

    /** What a push's visitor key is compared with, for calls from other threads; written on the main thread. */
    @Volatile
    private var keys = PushKeys(storeRead = false)
    private val keyCache = HashMap<String, String>()

    /** Run once the visitor's store was read ([whenVisitorKnown]). */
    private val afterStoreRead = ArrayList<() -> Unit>()

    /** Raised by every identify and logout: an answer for an older one is dropped (the newer user wins). */
    private var identifySeq = 0
    private var identityDecided = false
    private var applyJob: Job? = null
    private var backgroundVerifyJob: Job? = null

    /** The server keeps an email for this visitor: the email question is left out. */
    private var emailKnown = false

    // ── Conversations ────────────────────────────────────────────────────
    private var listEntries: List<ConversationSummary> = emptyList()
    private var conversationId: String? = null

    /** Conversations the server confirmed as [ownBearer]'s: by the restore, the list or a write that landed. */
    private val ownIds = HashSet<String>()
    private var ownBearer: String? = null

    /** A tapped conversation on screen that the server has not shown a row of yet. */
    private var tappedUnconfirmed: String? = null

    /** The tapped conversation the server showed nothing of: not this visitor's. */
    private var refusedTap: String? = null

    /** The conversation on screen with the app in front, for calls from other threads. */
    @Volatile
    private var onScreenNow: String? = null
    private var lastOnScreen: String? = null

    /** Told on the main thread whenever a conversation comes on screen with the app in front. */
    var conversationOnScreen: ((String) -> Unit)? = null

    /** Every server row of the thread, ordered, typing placeholders included (they drive [botThinking]). */
    private var messages: List<Message> = emptyList()
    private var pending: List<PendingSend> = emptyList()
    private var uploads: List<UploadState> = emptyList()
    private val uploadSentAt = HashMap<String, Long>()
    private var segments: List<ThreadSegment> = emptyList()
    private var botThinking = false
    private var botTool: String? = null
    private var agentTyping = false
    private var queuePosition: Int? = null
    private var waiting = false
    private var resolved = false
    private var continuesAfterResolve = false
    private var pollShowCsat = false
    private var closed = false
    private var endedLocally = false
    private var serverAgentReplied: Boolean? = null
    private var csatState = CsatState.IDLE
    private var transcriptState = TranscriptState.IDLE

    /** The conversation an "End conversation" is on its way for: only its thread shows [ThreadState.ending]. */
    private var endingConversation: String? = null

    /** The next send starts a new conversation ("Start a new conversation"). */
    private var forceNew = false

    /**
     * Sends that went out with [forceNew]: their retries carry it too, also
     * after the visitor moved on. Sends of a thread without a conversation go
     * one at a time, so 2 quick messages never make 2 conversations.
     */
    private val forceNewRows = HashSet<String>()

    /** The restore lookup's unread count, used until the thread's rows are here. */
    private var restoredUnread: Int? = null

    /** The last restore got no usable answer: the next refresh tries again. */
    private var restoreFailed = false

    /**
     * Raised by every change of what the thread shows: a restore
     * or a poll that started before it is dropped when it answers.
     */
    private var epoch = 0

    /**
     * Names the thread on screen while it has no conversation yet. Pending
     * rows written there carry no conversation id; [freshOf] keeps which
     * fresh thread they belong to until their first send names one.
     */
    private var freshKey = 0
    private val freshOf = HashMap<String, Int>()

    /**
     * Rows written before the visitor's thread was settled (during the start
     * or a verification): they belong to the thread the session settles on,
     * so their conversation is set when their delivery starts.
     */
    private val unbound = HashSet<String>()

    // ── Steps before the chat ────────────────────────────────────────────
    private var preChatValues: Map<String, String>? = null

    /** The answers each send went out with; only the success of a send that carried [preChatValues] clears them. */
    private val preChatCarriedBy = HashMap<String, Map<String, String>>()
    private var askedStep: AskedStep? = null
    private var missingFields: List<String> = emptyList()

    /** Texts written while a step before the chat was due, in the order written. */
    private var heldMessages: List<String> = emptyList()

    // ── Sends ────────────────────────────────────────────────────────────

    /**
     * The send the others wait for: the first one while the bearer is not
     * confirmed, and the first one of a thread that has no
     * conversation yet. Completed when it answered.
     */
    private var leadSend: CompletableDeferred<Unit>? = null

    /** Sends that left for the server; the others still wait for the start or a verification. */
    private val inFlight = HashSet<String>()
    private var unsent: Unsent? = null
    private var pendingReply: PendingReply? = null
    private var holdTimer: Job? = null

    // ── Poll, realtime, typing, read state ───────────────────────────────
    private var visible = false
    private var appForeground = initialForeground
    private val foregroundFlow = MutableStateFlow(initialForeground)
    private var pollJob: Job? = null
    private var pollConversation: String? = null
    private var pollWake: Channel<Unit>? = null
    private var listJob: Job? = null
    private var connection: RealtimeConnection? = null
    private var connectionConversation: String? = null

    /** Realtime refused this conversation for good: not opened again until the app comes back to the front. */
    private var realtimeStoppedFor: String? = null
    private var agentTypingTimer: Job? = null

    /** When the last typing start went out, on the monotonic clock; null: the next key signals at once. */
    private var typingLastSent: Long? = null
    private var typingConversation: String? = null
    private var typingStopTimer: Job? = null
    private var cacheWritePending = false

    // Stale answer guards: a poll requested before one of these moments must not undo it.
    // All on the monotonic clock; NEVER until the moment happened.
    private var botTurnEndedAt = NEVER
    private var closedAt = NEVER
    private var localStatusAt = NEVER
    private var queuePushAt = NEVER
    private var waitingPushAt = NEVER

    /**
     * A pushed reply ended the handover wait. The server clears the wait
     * just after it pushes the reply, so the poll that
     * follows the reply at once may still say waiting: for 6 s that does
     * not bring the line back.
     */
    private var waitEndPushAt = NEVER

    /**
     * The bot's typing hold after the send [uuid]. [at] is on
     * the monotonic clock; [floor] is the server time of the newest row when
     * it was raised, so a reply is told from an older one by server times only.
     */
    private class PendingReply(val uuid: String, val at: Long, val floor: Long)

    /** The last message that was not sent: an immediate resend of its text keeps its id. */
    private class Unsent(val text: String, val uuid: String, val bearer: String?)

    private enum class VerifyOutcome { ADOPTED_SAME, ADOPTED_NEW, REFUSED, FAILED, STALE }

    /** A step the server asked for although the config did not. */
    private enum class AskedStep { FORM, EMAIL }

    private val continuous: Boolean get() = config?.continuousChat == true

    /** Nothing about the visitor is written anywhere. */
    private val cookieless: Boolean get() = config?.cookielessEnabled == true

    // ═════════════════════════════════════════════════════════════════════
    // Start
    // ═════════════════════════════════════════════════════════════════════

    /** Config, identity, restore, list. Idempotent: a running or finished start is reused. */
    fun start() {
        // A push registration that failed is tried again on the next start.
        push.retryFailed()
        ensureBoot()
    }

    /**
     * The app's FCM registration token, in memory only. The
     * phone registers once the visitor is someone the server knows; null or
     * blank forgets the token and takes the phone off the visitor.
     */
    fun setPushToken(token: String?) = push.setToken(token)

    /**
     * The thread of [id] is on screen with the app in front: its replies need
     * no notification. Any thread: another one reads what the last published
     * state showed.
     */
    fun isOnScreen(id: String): Boolean =
        if (onMainThread()) visible && appForeground && conversationId == id else onScreenNow == id

    /**
     * Fetches the config, the list and the thread again (foreground,
     * present); after a failure, starts over. The restore lookup is skipped
     * while a conversation is on screen, so an end state the visitor is
     * reading is not replaced; it runs when the thread is not
     * on screen, when there is no conversation, and after a restore that got
     * no answer.
     */
    fun refresh() {
        if (phase != Phase.READY) {
            ensureBoot()
            return
        }
        pollSoon()
        if (refreshJob?.isActive == true) {
            // A refresh that began a moment ago answers this call too (a present right after a
            // foreground, the messenger's own refresh as it opens); an older one is followed by one more.
            if (clock.elapsed() - refreshStartedAt >= REFRESH_JOIN_MILLIS) refreshAgain = true
            return
        }
        refreshStartedAt = clock.elapsed()
        refreshAgain = false
        refreshJob = scope.launch {
            refreshConfig()
            val before = conversationId
            // Never while the visitor asked for a new conversation: the restore would bring the old one back.
            if ((!visible || conversationId == null || restoreFailed) && !forceNew) restore()
            loadList()
            if (conversationId != before) startLiveWork() else ensureListLoop()
            changed()
            refreshJob = null
            if (refreshAgain) refresh()
        }
    }

    private var refreshJob: Job? = null
    private var refreshStartedAt = 0L
    private var refreshAgain = false

    private fun ensureBoot(): Job? {
        if (!rootJob.isActive) return null
        bootJob?.takeIf { it.isActive }?.let { return it }
        if (phase == Phase.READY) return null
        phase = Phase.LOADING
        failure = null
        changed()
        val job = scope.launch { boot() }
        bootJob = job
        return job
    }

    /** Waits for the start and for a verification in progress; true when the session takes writes. */
    private suspend fun awaitReady(): Boolean {
        if (phase == Phase.READY) return true
        applyJob?.takeIf { it.isActive }?.join()
        if (phase == Phase.READY) return true
        ensureBoot()?.join()
        return phase == Phase.READY
    }

    private suspend fun boot() {
        // Every step on the store asked for before (a logout's deletion, also one of another inbox's session
        // that was replaced) finishes first: the start never reads back what it deletes.
        storeQueue.idle()
        if (!recordLoaded) {
            record = store.read()
            recordLoaded = true
            publishKeys()
            // What waited for the store (a push handed over on the main thread of a fresh process).
            val waiting = afterStoreRead.toList()
            afterStoreRead.clear()
            waiting.forEach { it() }
            paintFromCache()
        }
        val fetched = when (val loaded = loadConfig()) {
            is ConfigLoad.Loaded -> loaded.config
            is ConfigLoad.Failed -> {
                fail(loaded.why)
                return
            }
        }
        applyConfig(fetched)
        val identified = identityLock.withLock { decideIdentityAtStart() }
        if (!identified) {
            fail(SessionFailure.IDENTITY_CHECK)
            return
        }
        restore()
        loadList()
        phase = Phase.READY
        persistCache()
        changed()
        startLiveWork()
    }

    /** The session failed for [why]: the UI words it, the developer log names it. */
    private fun fail(why: SessionFailure) {
        phase = Phase.FAILED
        failure = why
        logger.debug("The chat session failed: ${why.name.lowercase()}")
        changed()
    }

    private sealed class ConfigLoad {
        class Loaded(val config: WidgetConfig) : ConfigLoad()

        class Failed(val why: SessionFailure) : ConfigLoad()
    }

    /**
     * With a cached config one try, so the restore is not held up; without
     * one the web client's tries after 0, 1, 3 and 8 s. A definite refusal (an unknown token, a switched off widget) is
     * not tried again and the copy is not used. An answer that is not a
     * config counts as no answer and is never used.
     */
    private suspend fun loadConfig(): ConfigLoad {
        val waits = if (config != null) listOf(0L) else listOf(0L, 1_000L, 3_000L, 8_000L)
        var why = SessionFailure.NO_CONNECTION
        for (wait in waits) {
            if (wait > 0) delay(wait)
            val result = api.config(pageLanguage())
            noteAnswer(result)
            when (result) {
                is ApiResult.Success -> {
                    configFetchedAt = clock.elapsed()
                    sendHeartbeatOnce()
                    return ConfigLoad.Loaded(result.value)
                }
                is ApiResult.Failure -> {
                    val status = result.error.status
                    if (status == 404) {
                        logger.error("The widget token passed to Deskwoot.setup is unknown, or the inbox's widget is switched off (HTTP 404).")
                    } else {
                        logger.debug("The widget config was refused (HTTP $status)")
                    }
                    if (refusedForGood(result.error)) {
                        return ConfigLoad.Failed(if (status == 404) SessionFailure.UNKNOWN_WIDGET else SessionFailure.SERVER)
                    }
                    why = SessionFailure.SERVER
                }
                is ApiResult.NetworkFailure -> {
                    logger.debug("The widget config got no usable answer")
                    why = SessionFailure.NO_CONNECTION
                }
            }
        }
        return config?.let { ConfigLoad.Loaded(it) } ?: ConfigLoad.Failed(why)
    }

    private suspend fun refreshConfig() {
        val result = api.config(pageLanguage())
        noteAnswer(result)
        if (result is ApiResult.Success) {
            configFetchedAt = clock.elapsed()
            sendHeartbeatOnce()
            applyConfig(result.value)
            persistCache()
        }
    }

    private fun applyConfig(fresh: WidgetConfig) {
        config = fresh
        if (fresh.cookielessEnabled) forgetStoredVisitor()
        changed()
    }

    /**
     * A cookieless inbox: the visitor lives in memory until the process
     * ends and anything stored earlier for this widget token is deleted
     * (the web's cookieless mode).
     */
    private fun forgetStoredVisitor() {
        val stored = record.bearer
        // The stored visitor leaves the device; one still in use goes on in memory (and stays registered).
        val leaves = stored != null && (!identityDecided || stored != bearer)
        if (leaves) push.forget(mayBeRegistered(stored), durable = true)
        var shown = false
        if (!identityDecided) {
            shown = conversationId != null || messages.isNotEmpty() || listEntries.isNotEmpty()
            record = VisitorRecord()
            clearThread()
            listEntries = emptyList()
            if (shown) visitorGeneration++
        }
        if (storageForgotten) return
        storageForgotten = true
        onDisk {
            store.clear()
            cache.clear()
        }
        // Their pictures and file copies on the disk go too.
        if (leaves) visitorWiped?.invoke(shown)
    }

    /**
     * The inbox counts as installed: sent once after the first config of
     * the process, failures ignored. It names no visitor, so a cookieless
     * inbox sends it too.
     */
    private fun sendHeartbeatOnce() {
        if (heartbeatSent) return
        heartbeatSent = true
        scope.launch { noteAnswer(api.heartbeat(appId, sdkVersion, appDebuggable)) }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Identity
    // ═════════════════════════════════════════════════════════════════════

    /**
     * The host app's signed in user. The newest call wins; identify and
     * logout take effect in the order they were called. The
     * same payload again while its verification runs is ignored, so an app
     * that calls this in every `onResume` never verifies twice.
     */
    fun identify(user: DeskwootUser) {
        if (!user.isValid) {
            logger.error("Deskwoot.identify ignored: the identifier must be 1 to 128 characters and the identifierHash 64 hex characters.")
            return
        }
        if (user == pendingUser && (user == verifyingUser || (!identityDecided && bootJob?.isActive == true))) return
        pendingUser = user
        val seq = ++identifySeq
        backgroundVerifyJob?.cancel()
        backgroundVerifyJob = null
        // Another user's identity, in use or dormant, ends right here, before the app's next call:
        // a text the app hands over right after (a new conversation with a draft) is the new user's.
        if (endsAnotherUser(user.identifier.trim())) {
            wipeVisitor()
            if (identityDecided) {
                becomeAnonymous()
                // Nothing goes out before the new user's verification answered.
                phase = Phase.LOADING
            }
            changed()
        }
        // The stored identity of the user named here is theirs from now on; another user's is not.
        publishKeys()
        if (!identityDecided) {
            ensureBoot()
            return
        }
        applyJob = scope.launch {
            identityLock.withLock { applyUser(user, seq) }
            // The identity settled (or a newer call took over): the state says so.
            changed()
        }
    }

    /** The identity in use, or the stored one, belongs to another user than [id]; known once the store was read. */
    private fun endsAnotherUser(id: String): Boolean {
        if (!recordLoaded) return false
        val inUse = verifiedExternalId
        if (inUse != null) return inUse != id
        val stored = record.verifiedExternalId
        return stored != null && record.bearer != null && stored != id
    }

    /**
     * A local reset (there is no server call): bearer, verified id,
     * conversation, read marker and caches go, every job of this visitor
     * stops, and the next visitor starts new. The app's language stays.
     * Returns the step that deletes the stored visitor (on the store queue,
     * which no stop of the session cancels).
     */
    fun logout(): Job {
        identifySeq++
        pendingUser = null
        applyJob?.cancel()
        applyJob = null
        val decided = identityDecided && config != null
        bootJob?.cancel()
        bootJob = null
        val deleted = wipeVisitor()
        if (decided) {
            becomeAnonymous()
            // A new anonymous visitor has no conversations to load.
            visitorLoaded = true
            phase = Phase.READY
            startLiveWork()
        } else {
            phase = Phase.LOADING
            ensureBoot()
        }
        changed()
        return deleted
    }

    /**
     * The identity at start. A stored verified identity is dormant until the
     * app names its user: without a user the session runs as a new visitor
     * in memory and leaves the stored data alone. The same user takes it
     * back at once and verifies again in the background; another user
     * deletes it first. False when a user without a stored identity got no
     * usable answer: the chat then fails instead of going anonymous.
     */
    private suspend fun decideIdentityAtStart(): Boolean {
        identityDecided = true
        val user = pendingUser
        if (user == null) {
            becomeAnonymous()
            return true
        }
        val id = user.identifier.trim()
        if (record.verifiedExternalId == id && record.bearer != null) {
            useStoredVerified(user, identifySeq)
            return true
        }
        // Another user's stored identity, read only now: nothing of it was shown, so only what the
        // device keeps of it goes; whatever was written meanwhile belongs to the user being verified.
        if (record.verifiedExternalId != null) {
            push.forget(mayBeRegistered(record.bearer), durable = true)
            record = VisitorRecord()
            onDisk {
                store.clear()
                cache.clear()
            }
            visitorWiped?.invoke(false)
        }
        return when (verifyAndAdopt(user, identifySeq)) {
            VerifyOutcome.FAILED -> false
            VerifyOutcome.STALE -> {
                if (bearer == null) becomeAnonymous()
                true
            }
            else -> true
        }
    }

    /** An identify after the start. */
    private suspend fun applyUser(user: DeskwootUser, seq: Int) {
        if (seq != identifySeq) return
        val id = user.identifier.trim()
        val inUse = verifiedExternalId == id && !placeholder && bearer != null && phase == Phase.READY
        // This payload was verified in this process already.
        if (inUse && verifiedUser == user) return
        // The identity in use belongs to this user: it keeps working, the new payload is verified in the background.
        if (inUse) {
            startBackgroundVerify(user, seq)
            return
        }
        // The dormant identity of this very user: taken back at once. Texts held for the step
        // before the chat stay: the verified user needs no step, so they go out once the user's
        // conversations are known.
        if (verifiedExternalId == null && record.verifiedExternalId == id && record.bearer != null && config != null) {
            resetIdentityScope()
            clearThread()
            pending = emptyList()
            uploads = emptyList()
            listEntries = emptyList()
            useStoredVerified(user, seq)
            afterIdentityChange()
            return
        }
        // Another user's identity, in use or dormant: its data goes first. [identify]
        // did that already; this covers a state that changed while this call waited.
        if (verifiedExternalId != null || record.verifiedExternalId != null) {
            wipeVisitor()
            becomeAnonymous()
            changed()
        }
        // No stored identity for this user: no restore and no send before the verification answered.
        val before = bearer
        phase = Phase.LOADING
        changed()
        when (verifyAndAdopt(user, seq)) {
            VerifyOutcome.STALE -> return
            VerifyOutcome.FAILED -> {
                fail(SessionFailure.IDENTITY_CHECK)
                return
            }
            VerifyOutcome.ADOPTED_SAME, VerifyOutcome.REFUSED -> if (bearer == before && config != null) {
                phase = Phase.READY
                changed()
                startLiveWork()
                return
            }
            VerifyOutcome.ADOPTED_NEW -> Unit
        }
        afterIdentityChange()
    }

    /** The stored identity of the user the app names: used at once, verified again in the background. */
    private suspend fun useStoredVerified(user: DeskwootUser, seq: Int) {
        bearer = record.bearer
        placeholder = false
        // A stored verified bearer came from the verify route: confirmed (the web's rule).
        confirmed = true
        verifiedExternalId = user.identifier.trim()
        verifiedUser = null
        // Settled once the verification answered: it may name a new bearer for this user.
        verifyingTakeBack = true
        if (conversationId == null && messages.isEmpty()) paintFromCache()
        changed()
        startBackgroundVerify(user, seq)
    }

    private fun startBackgroundVerify(user: DeskwootUser, seq: Int) {
        backgroundVerifyJob?.cancel()
        backgroundVerifyJob = scope.launch { verifyInBackground(user, seq) }
    }

    /**
     * Verifies a stored identity that is already in use. A refusal (400, 401
     * or 404 with the API's JSON) resets to a new visitor and deletes the stored data; anything else
     * keeps the identity working and tries again later.
     */
    private suspend fun verifyInBackground(user: DeskwootUser, seq: Int) {
        try {
            var wait = VERIFY_RETRY_FIRST_MILLIS
            while (true) {
                foregroundFlow.first { it }
                val result = verifyCall(user)
                if (seq != identifySeq) return
                when (result) {
                    is ApiResult.Success -> {
                        val fresh = result.value.contactIdentifier
                        identityLock.withLock {
                            if (seq != identifySeq) return
                            // From here on this job may reset the identity, which must not cancel itself.
                            backgroundVerifyJob = null
                            if (fresh == bearer) {
                                verifiedUser = user
                                changed()
                            } else {
                                // A rotated identity secret: the same user under a new bearer. Pushes and taps
                                // that name the bearer it replaces are still this user's.
                                bearer?.takeUnless { placeholder }?.let { theirKeys += keyOf(it) }
                                adopt(user, fresh)
                                afterIdentityChange()
                            }
                        }
                        return
                    }
                    is ApiResult.Failure -> if (isIdentityRefusal(result.error)) {
                        identityLock.withLock {
                            if (seq != identifySeq) return
                            backgroundVerifyJob = null
                            dropRefusedIdentity(result.error)
                        }
                        return
                    }
                    is ApiResult.NetworkFailure -> Unit
                }
                // No usable answer: the identity keeps working, and nothing waits for it any longer.
                takeBackSettled()
                logger.debug("The stored identity could not be verified again; it keeps working")
                delay(wait)
                wait = minOf(wait * 2, VERIFY_RETRY_MAX_MILLIS)
            }
        } finally {
            // Answered, failed or stopped: unless a newer identify or logout took over, the identity settled.
            if (seq == identifySeq) takeBackSettled()
        }
    }

    private fun takeBackSettled() {
        if (!verifyingTakeBack) return
        verifyingTakeBack = false
        changed()
    }

    /** One verify call; [verifyingUser] names its payload while it runs. */
    private suspend fun verifyCall(user: DeskwootUser): ApiResult<com.deskwoot.sdk.api.VerifyAnswer> {
        verifyingUser = user
        try {
            return api.verify(user).also(::noteAnswer)
        } finally {
            if (verifyingUser === user) verifyingUser = null
        }
    }

    private suspend fun dropRefusedIdentity(error: ApiError?) {
        logRefusal(error)
        wipeVisitor()
        becomeAnonymous()
        afterIdentityChange()
    }

    /** After the visitor changed: the thread of the new one, live. */
    private suspend fun afterIdentityChange() {
        visitorLoaded = false
        changed()
        if (config == null) {
            ensureBoot()
            return
        }
        restore()
        loadList()
        phase = Phase.READY
        persistCache()
        changed()
        startLiveWork()
    }

    /**
     * `POST /api/widget/identity/verify`, waited for. A refusal (400, 401 or
     * 404 with the API's JSON) leaves the visitor anonymous, as
     * the web does; anything else (no answer, an answer that is not the
     * route's, a 403 page of a proxy, a 5xx) is a failure: going anonymous
     * then would start a second contact whose replies the user never sees.
     */
    private suspend fun verifyAndAdopt(user: DeskwootUser, seq: Int): VerifyOutcome {
        val result = verifyCall(user)
        if (seq != identifySeq) return VerifyOutcome.STALE
        return when (result) {
            is ApiResult.Success -> if (adopt(user, result.value.contactIdentifier)) VerifyOutcome.ADOPTED_NEW else VerifyOutcome.ADOPTED_SAME
            is ApiResult.Failure -> if (isIdentityRefusal(result.error)) {
                refusedVerification(result.error)
                VerifyOutcome.REFUSED
            } else {
                logger.debug("Identity verification got no usable answer (HTTP ${result.error.status}); the chat does not go anonymous")
                VerifyOutcome.FAILED
            }
            is ApiResult.NetworkFailure -> {
                logger.debug("Identity verification got no usable answer; the chat does not go anonymous")
                VerifyOutcome.FAILED
            }
        }
    }

    private fun refusedVerification(error: ApiError?) {
        logRefusal(error)
        if (bearer == null || verifiedExternalId != null) becomeAnonymous()
    }

    /** One line a developer can act on, by the server's reason. */
    private fun logRefusal(error: ApiError?) {
        val reason = when {
            error == null -> "the server named no visitor"
            error.serverMessage == "invalid_identifier_hash" ->
                "identifierHash does not match: compute it on your backend as hex HMAC-SHA256(identity secret, identifier)"
            error.serverMessage == "invalid_attributes_hash" -> "attributesHash does not match the signed attributes"
            error.serverMessage == "identity_verification_not_configured" ->
                "the inbox has no identity secret; switch on identity verification in the inbox settings"
            error.status == 404 -> "the widget token is unknown or the widget is switched off"
            else -> error.serverMessage ?: "HTTP ${error.status}"
        }
        logger.error("Deskwoot.identify was refused (HTTP ${error?.status ?: 200}): $reason. The visitor continues anonymous.")
    }

    /**
     * Takes the verified bearer. A bearer other than the
     * one held clears the thread: an anonymous history is never merged and
     * another user's never shown. True when the contact changed.
     */
    private fun adopt(user: DeskwootUser, newBearer: String): Boolean {
        val externalId = user.identifier.trim()
        val same = !placeholder && (bearer == newBearer || (bearer == null && record.bearer == newBearer)) &&
            (verifiedExternalId == null || verifiedExternalId == externalId) &&
            (record.verifiedExternalId == null || record.verifiedExternalId == externalId)
        if (!same) {
            // The bearer it replaces (an anonymous visitor's, a rotated one) leaves the device:
            // the phone must not stay registered to it.
            push.forget(mayBeRegistered(bearer?.takeUnless { placeholder }, record.bearer).filter { it != newBearer }, durable = !cookieless)
            resetIdentityScope()
            clearThread()
            // Messages written while the verification ran have not left yet: they go out as this
            // user, into the thread this user's session settles on. Texts held for the step before
            // the chat stay too: the verified user needs no step, so they go out on their own.
            pending = pending.filter { it.state == PendingState.SENDING && !it.questionResolved && it.uuid !in inFlight }
                .map { it.copy(conversationId = null) }
            pending.forEach { unbound += it.uuid }
            uploads = emptyList()
            listEntries = emptyList()
            emailKnown = false
            preChatValues = null
            askedStep = null
            missingFields = emptyList()
            closed = false
            record = record.copy(lastConversationId = null, lastReadAt = null)
            if (!cookieless) onDisk { cache.clear() }
        }
        bearer = newBearer
        placeholder = false
        confirmed = true
        verifiedExternalId = externalId
        verifiedUser = user
        record = record.withBearer(newBearer).copy(verifiedExternalId = externalId)
        persistRecord()
        changed()
        return !same
    }

    /**
     * The stored anonymous bearer, else a new placeholder (kept in
     * memory only). A dormant verified identity is neither used nor deleted.
     */
    private fun becomeAnonymous() {
        // An anonymous visitor has no verification to wait for and no earlier bearers of a user.
        verifyingTakeBack = false
        theirKeys.clear()
        val storedAnonymous = record.bearer?.takeIf { record.verifiedExternalId == null }
        // A placeholder already in use stays: a retried first message must keep it.
        if (storedAnonymous == null && placeholder && bearer != null && verifiedExternalId == null) return
        verifiedExternalId = null
        verifiedUser = null
        confirmed = false
        if (storedAnonymous != null) {
            bearer = storedAnonymous
            placeholder = false
        } else {
            bearer = newUuid().lowercase()
            placeholder = true
        }
    }

    /**
     * Forgets this visitor, at once and in call order: memory, the stored
     * record, the cache, the read marker, and through [visitorWiped] what the
     * UI keeps (drafts, pictures, file copies); the UI starts over. The
     * app's language is not the visitor's and stays. Every end of the visitor
     * the UI shows runs it: a logout, another user, a refused identity.
     */
    private fun wipeVisitor(): Job {
        // Before anything is deleted, the bearers a push registration may exist for (the one in use and a
        // stored one, a dormant verified identity included) go into the pending push removals.
        push.forget(mayBeRegistered(bearer?.takeUnless { placeholder }, record.bearer), durable = !cookieless)
        // Before the store was read (a logout right after setup) the stored bearer is not known
        // yet: it is read, kept for its removal when it was registered, and only then deleted.
        val storeUnread = !recordLoaded
        val now = clock.now()
        backgroundVerifyJob?.cancel()
        backgroundVerifyJob = null
        resetIdentityScope()
        bearer = null
        placeholder = false
        confirmed = false
        verifiedExternalId = null
        verifiedUser = null
        verifyingTakeBack = false
        theirKeys.clear()
        tappedUnconfirmed = null
        refusedTap = null
        emailKnown = false
        record = VisitorRecord()
        clearThread()
        pending = emptyList()
        unbound.clear()
        freshOf.clear()
        uploads = emptyList()
        listEntries = emptyList()
        closed = false
        closedAt = NEVER
        forceNew = false
        forceNewRows.clear()
        restoreFailed = false
        preChatValues = null
        askedStep = null
        missingFields = emptyList()
        heldMessages = emptyList()
        unsent = null
        typingConversation = null
        typingLastSent = null
        preChatCarriedBy.clear()
        // On the store queue: a stop or a replacement of this session right after does not cancel it.
        val deleted = onDisk {
            if (storeUnread) push.forgetStored(store.read(), now)
            store.clear()
            cache.clear()
        }
        // The UI drops what it keeps of this visitor too: drafts, pictures, file copies.
        visitorGeneration++
        visitorLoaded = false
        visitorWiped?.invoke(true)
        return deleted
    }

    /**
     * Of [bearers], those a push registration may exist for: the stored
     * record says one was sent for its bearer, or this process sent one.
     * Only these leave a removal record when they leave the device.
     */
    private fun mayBeRegistered(vararg bearers: String?): List<String> =
        bearers.filterNotNull().distinct().filter { (it == record.bearer && record.pushRegistered) || push.sentFor(it) }

    /**
     * A push registration for [forBearer] is about to leave: the visitor's
     * record says so first, on the disk before this returns (in memory only
     * in a cookieless inbox). False when [forBearer] is not the visitor's
     * record any more: nothing is registered then.
     */
    private suspend fun beforeRegistration(forBearer: String): Boolean {
        if (placeholder || bearer != forBearer || record.bearer != forBearer) return false
        if (!record.pushRegistered) {
            record = record.copy(pushRegistered = true)
            persistRecord()?.join()
        }
        return true
    }

    /**
     * Called on the main thread, at once, whenever a visitor is forgotten.
     * True: the visitor the UI shows ended (logout, another user, a refused
     * identity): drafts, pictures and file copies go and the UI starts over
     * before the app's next call. False: only a stored visitor this process
     * never showed ended (another user named before the store was read, an
     * anonymous first message over a dormant identity, a cookieless inbox):
     * the file copies and pictures on the disk go, the UI goes on.
     */
    @Volatile
    var visitorWiped: ((shown: Boolean) -> Unit)? = null

    /** Stops every job of the current visitor at once: poll, stream, timers, sends, cache writes. */
    private fun resetIdentityScope() {
        closeRealtime()
        identityJob.cancel()
        identityJob = SupervisorJob(rootJob)
        identityScope = CoroutineScope(identityJob + mainDispatcher + errors)
        pollJob = null
        pollConversation = null
        pollWake = null
        listJob = null
        holdTimer = null
        agentTypingTimer = null
        typingStopTimer = null
        cacheWritePending = false
        leadSend?.complete(Unit)
        leadSend = null
        realtimeStoppedFor = null
        verifyingUser = null
        endingConversation = null
        pendingReply = null
        botThinking = false
        botTool = null
        agentTyping = false
    }

    // ═════════════════════════════════════════════════════════════════════
    // Restore and the list
    // ═════════════════════════════════════════════════════════════════════

    /**
     * The restore lookup: which conversation to show. A 429 or no answer is
     * not "no conversation": the thread from the disk stays
     * and the next refresh asks again. An answer that comes
     * after the thread changed (the visitor opened it, wrote, switched) is
     * dropped: it describes a thread that is no longer there.
     */
    private suspend fun restore() {
        val asBearer = bearer ?: return
        if (placeholder) {
            clearThread()
            return
        }
        val startedIn = epoch
        val result = api.restore(asBearer, record.lastReadAt?.let(IsoTime::format))
        noteAnswer(result)
        if (asBearer != bearer || startedIn != epoch) return
        if (result !is ApiResult.Success) {
            restoreFailed = true
            // The last conversation is stored only once it was known to be this visitor's.
            if (conversationId == null) conversationId = record.lastConversationId?.also { confirmOwn(listOf(it)) }
            changed()
            return
        }
        restoreFailed = false
        val answer = result.value
        if (answer.closed) markClosed()
        if (answer.emailKnown) emailKnown = true
        val id = answer.conversationId
        if (id != null) confirmOwn(listOf(id))
        if (id == null) {
            clearThread()
            changed()
            return
        }
        // The visitor asked for a new conversation meanwhile: the old one does not come back.
        if (forceNew && conversationId == null) {
            changed()
            return
        }
        val isResolved = ConversationStatus.parse(answer.status) == ConversationStatus.RESOLVED
        val continuing = isResolved && answer.continuesAfterResolve
        if (isResolved && !continuous && !continuing) {
            // A normal inbox shows no finished conversation; the next message may reopen it.
            clearThread()
        } else {
            if (id != conversationId) {
                if (!continuous) messages = emptyList()
                resetConversationView(keepBotHold = false)
                epoch++
                unsent = null
            }
            conversationId = id
            resolved = isResolved
            continuesAfterResolve = continuing
            restoredUnread = answer.unreadCount
            forceNew = false
            rememberConversation(id)
            // A loop of a conversation the answer replaced stops; the shown one starts.
            ensurePolling()
        }
        changed()
    }

    /** `?list=1`; not in continuous chat, where the list is the one thread, and not in a cookieless inbox. */
    private suspend fun loadList() {
        val asBearer = bearer
        if (asBearer == null || placeholder || config?.continuousChat != false || cookieless) {
            // Nothing of this visitor to load: as loaded as it gets.
            if (!visitorLoaded) {
                visitorLoaded = true
                changed()
            }
            return
        }
        val result = api.conversationList(asBearer, record.lastReadAt?.let(IsoTime::format))
        noteAnswer(result)
        if (asBearer != bearer || result !is ApiResult.Success) return
        if (result.value.closed) markClosed()
        listEntries = result.value.conversations
        confirmOwn(listEntries.map { it.id })
        // A conversation opened before it was known to be theirs is remembered once it is.
        conversationId?.takeIf { it in ownIds }?.let(::rememberConversation)
        visitorLoaded = true
        changed()
    }

    /** [ids] are the current visitor's, as the server said (never a placeholder's: it has none). */
    private fun confirmOwn(ids: Collection<String>) {
        val owner = bearer?.takeUnless { placeholder } ?: return
        if (ownBearer != owner) {
            ownIds.clear()
            ownBearer = owner
        }
        ownIds += ids
    }

    /**
     * Whether conversation [id] is this visitor's: true once the restore, the
     * list or a write of this visitor named it; false once the visitor has
     * settled (no identify or start running, their conversations loaded) and
     * nothing did; null while that is not known yet. A notification's tap
     * opens a conversation only on true (another contact's, a stale
     * registration's, a forgotten visitor's id never opens).
     */
    fun ownsConversation(id: String): Boolean? {
        val owner = bearer?.takeUnless { placeholder }
        if (owner != null && owner == ownBearer && id in ownIds) return true
        return if (identitySettled() && visitorLoaded) false else null
    }

    /**
     * Who the visitor is has settled: the start decided it, nothing of an
     * identify or a take back verification is on its way, and no stored
     * verified user waits for the app to name them.
     */
    fun identitySettled(): Boolean =
        identityDecided && config != null && phase == Phase.READY && bootJob?.isActive != true &&
            applyJob?.isActive != true && !verifyingTakeBack && dormantBearer() == null

    /**
     * The stored verified user while still dormant: this launch has seen
     * neither identify nor logout, so whether they come back is not known yet.
     */
    private fun dormantBearer(): String? =
        record.bearer?.takeIf { recordLoaded && record.verifiedExternalId != null && it != bearer && identifySeq == 0 }

    /** The key of the visitor the session runs as; null for a new visitor nobody knows yet. Any thread. */
    fun activeVisitorKey(): String? = if (onMainThread()) bearer?.takeUnless { placeholder }?.let(::keyOf) else keys.active

    /**
     * Whose push [key] names: the visitor the session runs as (or starts as,
     * or takes back now that the app named them), the stored verified user
     * who is still dormant (this launch has seen neither identify nor
     * logout), or a visitor this device forgot (also for a push without a
     * key). Any thread. On another thread in a process that has not read the
     * visitor's store yet, the store is read on that thread first; on the
     * main thread nothing waits, so before the store was read only the
     * visitor in memory counts ([visitorKnown]).
     */
    fun pushVisitor(key: String?): PushVisitor {
        val wanted = VisitorKey.clean(key) ?: return PushVisitor.FORGOTTEN
        if (onMainThread()) return currentKeys().whose(wanted)
        val known = keys
        if (known.storeRead) return known.whose(wanted)
        // A fresh process: the session reads the store on the main thread too, which must not wait for this one.
        val stored = runBlocking { store.read() }
        val now = keys
        return (if (now.storeRead) now else now.withStored(stored.bearer, stored.verifiedExternalId)).whose(wanted)
    }

    /** The visitor's store was read in this process, so [pushVisitor] knows every visitor of this device. Any thread. */
    val visitorKnown: Boolean get() = if (onMainThread()) recordLoaded else keys.storeRead

    /** Runs [action] on the main thread once the visitor's store was read: at once when it was. */
    fun whenVisitorKnown(action: () -> Unit) {
        if (recordLoaded) action() else afterStoreRead += action
    }

    /** The tapped conversation [id] was refused by the server: it shows nothing of it to this visitor. */
    fun tapRefused(id: String): Boolean = refusedTap == id

    /**
     * A push named [key], the visitor key of a visitor this device forgot: a
     * pending removal of that bearer goes out now (when a push token is
     * known), after the removal list was read when this process has not.
     * Any thread.
     */
    fun removeForgotten(key: String) {
        val wanted = VisitorKey.clean(key) ?: return
        if (onMainThread()) push.removeForgotten(wanted) else scope.launch { push.removeForgotten(wanted) }
    }

    /**
     * Whether what the messenger saved for the visitor [savedKey] (null: a
     * visitor nobody knew, with no bearer) may come back after the process
     * was restarted: true when that visitor is the one this session runs or
     * starts as, false when not (another user, a logout, a stored user who
     * waits for the app's identify), null while it is not known yet (the
     * store not read, an identify on its way). Main thread.
     */
    fun ownsSavedState(savedKey: String?): Boolean? {
        if (!recordLoaded) return null
        if (savedKey != null) return currentKeys().whose(savedKey) == PushVisitor.ACTIVE
        // Nobody known: the same as long as the session runs as nobody, once a user the app named was decided.
        if (pendingUser != null && (!identityDecided || applyJob?.isActive == true)) return null
        val runsAs = if (identityDecided) bearer?.takeUnless { placeholder } else record.bearer?.takeIf { record.verifiedExternalId == null }
        return runsAs == null
    }

    /** The keys from the state itself. Main thread. */
    private fun currentKeys(): PushKeys {
        val stored = record.takeIf { recordLoaded }
        return PushKeys(
            storeRead = recordLoaded,
            active = bearer?.takeUnless { placeholder }?.let(::keyOf),
            theirs = theirKeys.toSet(),
            stored = stored?.bearer?.let(::keyOf),
            storedUser = stored?.verifiedExternalId,
            named = pendingUser?.identifier?.trim(),
            spoke = identifySeq > 0,
            decided = identityDecided,
        )
    }

    /** The keys as they are now, for calls from other threads. */
    private fun publishKeys() {
        keys = currentKeys()
    }

    /** [VisitorKey.of], remembered for the few bearers a session sees. Main thread. */
    private fun keyOf(bearer: String): String {
        keyCache[bearer]?.let { return it }
        if (keyCache.size >= KEY_CACHE_SIZE) keyCache.clear()
        return VisitorKey.of(bearer).also { keyCache[bearer] = it }
    }

    /** Every wipe of the visitor counts here; the UI drops drafts, pictures and file copies on a new value. */
    private var visitorGeneration = 0

    /** The visitor's conversations are known since the last identity change. */
    private var visitorLoaded = false

    // ═════════════════════════════════════════════════════════════════════
    // Live work: poll, realtime, list refresh
    // ═════════════════════════════════════════════════════════════════════

    private fun startLiveWork() {
        ensureRealtime()
        ensureListLoop()
        pollSoon()
    }

    /**
     * The thread is on screen: drives `seen=1`, the read marker and the poll
     * speed. Coming on screen starts a new epoch: a restore
     * that started off screen no longer replaces what the visitor reads.
     */
    fun setVisible(onScreen: Boolean) {
        if (visible == onScreen) return
        visible = onScreen
        if (onScreen) epoch++
        if (onScreen && appForeground) {
            markRead()
            // The config is fetched again when the chat opens, so `available` is fresh.
            val fetchedAt = configFetchedAt
            val stale = fetchedAt == null || clock.elapsed() - fetchedAt > CONFIG_FRESH_MILLIS
            if (phase == Phase.READY && stale) scope.launch { refreshConfig() }
        }
        ensureListLoop()
        pollNow()
        changed()
    }

    /** The app came to the front or went to the background: no poll and no stream in the background. */
    fun setForeground(inFront: Boolean) {
        if (appForeground == inFront) return
        appForeground = inFront
        foregroundFlow.value = inFront
        // Back in front, a conversation whose realtime was refused gets one more try, and so does
        // a push registration that failed.
        if (inFront) {
            realtimeStoppedFor = null
            push.retryFailed()
        }
        ensureRealtime()
        ensureListLoop()
        if (inFront) {
            if (visible) markRead()
            refresh()
        } else {
            ensurePolling()
        }
        changed()
    }

    /** Starts or stops the poll loop as the state asks; true when a new loop began (it polls at once). */
    private fun ensurePolling(): Boolean {
        val id = conversationId
        if (id == null || bearer == null || placeholder || !appForeground || phase != Phase.READY) {
            pollJob?.cancel()
            pollJob = null
            pollConversation = null
            pollWake = null
            return false
        }
        if (pollJob?.isActive == true && pollConversation == id) return false
        pollJob?.cancel()
        val wake = Channel<Unit>(Channel.CONFLATED)
        pollWake = wake
        pollConversation = id
        pollJob = identityScope.launch { pollLoop(id, wake) }
        return true
    }

    /** A poll as soon as possible: a new loop polls at once, a running one right after its current poll. */
    private fun pollSoon() {
        if (!ensurePolling()) pollNow()
    }

    /**
     * The poll: every 0.8 s while the bot writes, 5 s otherwise, 30 s while
     * the thread is not on screen. A loop whose conversation is no
     * longer the current one ends before it asks again: its `seen=1` would
     * mark replies read that nobody shows.
     */
    private suspend fun pollLoop(id: String, wake: Channel<Unit>) {
        while (true) {
            if (id != conversationId) return
            pollOnce(id)
            val wait = when {
                !visible -> POLL_HIDDEN_MILLIS
                botThinking -> POLL_THINKING_MILLIS
                else -> POLL_IDLE_MILLIS
            }
            // A poll asked for meanwhile runs right after this one.
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /**
     * A poll right after the current one, or at once when none runs. The
     * wake channel is conflated: however many ask while a poll is in
     * flight, one more poll follows it.
     */
    private fun pollNow() {
        pollWake?.trySend(Unit)
    }

    private suspend fun pollOnce(id: String) {
        val asBearer = bearer ?: return
        if (id != conversationId) return
        val startedIn = epoch
        val requestedAt = clock.elapsed()
        val seen = visible && appForeground
        val result = api.poll(asBearer, id, seen)
        noteAnswer(result)
        if (id != conversationId || asBearer != bearer || startedIn != epoch) return
        val answer = (result as? ApiResult.Success)?.value ?: return
        if (answer.closed) {
            setClosed(true)
            changed()
            return
        }
        if (requestedAt >= closedAt) setClosed(false)
        // An empty answer changes nothing but the closed state: never replace a thread or its
        // queue, wait and reply flags with it.
        if (answer.messages.isEmpty()) {
            // The server shows a conversation's rows only to its own contact, and every conversation
            // has rows: a tapped one it shows nothing of is not this visitor's. The thread lets go of it.
            if (id == tappedUnconfirmed) {
                clearThread()
                refusedTap = id
            }
            changed()
            return
        }
        if (id == tappedUnconfirmed) tappedUnconfirmed = null
        // The server shows a conversation's rows only to its own contact: it is this visitor's.
        if (id !in ownIds || ownBearer != asBearer) {
            confirmOwn(listOf(id))
            rememberConversation(id)
        }
        // A snapshot requested before the visitor's own status change still shows the old status.
        val statusStale = requestedAt < localStatusAt
        if (answer.segments != null && !statusStale) segments = answer.segments
        // A pushed queue position wins over a poll for 6 s; a pushed handover wait over older polls.
        if (queuePushAt == NEVER || clock.elapsed() - queuePushAt > QUEUE_PUSH_WINS_MILLIS) queuePosition = answer.queuePosition
        val waitEndHolds = waitEndPushAt != NEVER && clock.elapsed() - waitEndPushAt <= QUEUE_PUSH_WINS_MILLIS
        if (requestedAt >= waitingPushAt && !(answer.waiting && waitEndHolds)) waiting = answer.waiting
        answer.hasAgentReply?.let { serverAgentReplied = it }
        replaceMessages(answer.messages)
        deriveBotState(answer.messages, requestedAt)
        if (!statusStale) applyResolved(answer.resolved, answer.continuesAfterResolve, answer.showCsatSurvey)
        // A stored file's row is in a snapshot asked for after the upload answered.
        val delivered = uploadSentAt.filterValues { it <= requestedAt }.keys
        if (delivered.isNotEmpty()) {
            uploads = uploads.filterNot { it.id in delivered }
            delivered.forEach(uploadSentAt::remove)
        }
        if (seen) markRead()
        persistCache()
        changed()
    }

    private fun applyResolved(isResolved: Boolean, continuing: Boolean, showCsat: Boolean) {
        if (resolved && !isResolved) {
            // Reopened: the survey and the transcript offer start over.
            csatState = CsatState.IDLE
            transcriptState = TranscriptState.IDLE
            endedLocally = false
        }
        resolved = isResolved
        continuesAfterResolve = isResolved && continuing
        pollShowCsat = showCsat
    }

    /**
     * The bot's typing state after a poll (the web's poll tick): its typing
     * placeholder in the snapshot, or a send the bot will answer whose reply
     * is not there yet, keeps it; the hold ends after 35 s at the latest.
     */
    private fun deriveBotState(rows: List<Message>, requestedAt: Long) {
        val sorted = rows.sortedWith(MessageRules.ORDER)
        val lastNonVisitor = sorted.lastOrNull { it.role != MessageRole.VISITOR && it.role != MessageRole.SYSTEM }
        val hold = pendingReply
        val sentIndex = if (hold == null) -1 else sorted.indexOfFirst { it.clientAssignedUuid == hold.uuid }
        val expired = hold != null && clock.elapsed() - hold.at >= PENDING_REPLY_MAX_MILLIS
        val replyAfter = sentIndex >= 0 && sorted.subList(sentIndex + 1, sorted.size).any(MessageRules::endsBotTurn)
        if (hold != null && (replyAfter || expired)) {
            pendingReply = null
            holdTimer?.cancel()
        }
        val holding = hold != null && !expired && !replyAfter
        val placeholderStale = requestedAt < botTurnEndedAt
        val composing = lastNonVisitor != null && lastNonVisitor.isTyping && !placeholderStale
        botThinking = composing || holding
        botTool = if (composing) lastNonVisitor.attributes.botTool else null
    }

    /**
     * The poll's snapshot over what is on screen: the snapshot
     * wins, except that a stale copy (still typing, or without content)
     * never replaces a finished row, and a pushed row the snapshot does not
     * have yet stays when it is not older than the snapshot's newest row.
     */
    private fun replaceMessages(incoming: List<Message>) {
        val existing = messages.associateBy { it.id }
        val merged = incoming.map { row ->
            val had = existing[row.id]
            val staler = had != null && ((row.isTyping && !had.isTyping) || (row.content.isNullOrEmpty() && !had.content.isNullOrEmpty()))
            if (staler) had else row
        }
        val ids = incoming.mapTo(HashSet()) { it.id }
        val newest = incoming.maxOf { it.createdAt }
        val kept = messages.filter { it.id !in ids && it.createdAt >= newest }
        messages = (merged + kept).sortedWith(MessageRules.ORDER)
        dropConfirmedPending()
    }

    /** A pushed row: same id replaces in place (the bot's placeholder filled), else it is added. */
    private fun appendMessage(row: Message) {
        val index = messages.indexOfFirst { it.id == row.id }
        if (index >= 0) {
            val had = messages[index]
            val identical = had.content == row.content && had.isTyping == row.isTyping &&
                had.role == row.role && had.attachments.size == row.attachments.size
            if (identical) return
            messages = messages.toMutableList().also { it[index] = row }.sortedWith(MessageRules.ORDER)
        } else {
            messages = (messages + row).sortedWith(MessageRules.ORDER)
            if (waiting && MessageRules.endsHandoverWait(row)) waiting = false
        }
        dropConfirmedPending()
    }

    /**
     * A pending send leaves once a row carries its uuid, or its "Frage
     * geklärt" line id. A failed message whose row arrived was
     * stored after all: its id is not reused for a later text.
     */
    private fun dropConfirmedPending() {
        val uuids = messages.mapNotNullTo(HashSet()) { it.clientAssignedUuid }
        unsent?.let { if (it.uuid in uuids) unsent = null }
        if (pending.isEmpty()) return
        val ids = messages.mapTo(HashSet()) { it.id }
        val gone = pending.filter { p -> p.uuid in uuids || (p.lineId != null && p.lineId in ids) }
        if (gone.isEmpty()) return
        pending = pending - gone.toSet()
        gone.forEach { forget(it.uuid) }
    }

    /** The bookkeeping of a pending row that left the thread. */
    private fun forget(uuid: String) {
        freshOf.remove(uuid)
        unbound.remove(uuid)
        forceNewRows.remove(uuid)
        preChatCarriedBy.remove(uuid)
    }

    /** The list every 30 s while the app is in front and the thread is not on screen. */
    private fun ensureListLoop() {
        val run = bearer != null && !placeholder && appForeground && !visible && !cookieless &&
            phase == Phase.READY && config?.continuousChat == false
        if (!run) {
            listJob?.cancel()
            listJob = null
            return
        }
        if (listJob?.isActive == true) return
        listJob = identityScope.launch {
            while (true) {
                delay(LIST_REFRESH_MILLIS)
                loadList()
            }
        }
    }

    // ── Realtime ─────────────────────────────────────────────────────────

    /**
     * Connected while the app is in front and the current conversation is
     * not in its end state, also with the messenger closed, so a reply
     * reaches the unread count within seconds.
     */
    private fun ensureRealtime() {
        val id = conversationId
        val asBearer = bearer
        val cfg = config
        if (id == null || asBearer == null || cfg == null || placeholder || closed || !appForeground ||
            phase != Phase.READY || inEndState(cfg) || realtimeStoppedFor == id
        ) {
            closeRealtime()
            return
        }
        if (connection != null && connectionConversation == id) return
        closeRealtime()
        connectionConversation = id
        lateinit var opened: RealtimeConnection
        opened = realtime.open(
            channel = "widget-conversation:$id",
            // Called on the main thread by the realtime client, like the listener.
            auth = { api.realtimeToken(asBearer, id).also(::noteAnswer) },
            listener = { event -> onRealtime(id, event) },
            onStopped = {
                // Refused for good: let go of it, so the state never holds a dead stream.
                if (connection === opened) {
                    connection = null
                    connectionConversation = null
                    realtimeStoppedFor = id
                }
            },
        )
        connection = opened
    }

    private fun closeRealtime() {
        connection?.close()
        connection = null
        connectionConversation = null
        agentTypingTimer?.cancel()
        agentTypingTimer = null
        agentTyping = false
    }

    private fun onRealtime(id: String, event: RealtimeEvent) {
        if (id != conversationId) return
        val data = event.data ?: JSONObject()
        val about = data.string("conversationId") == id
        when (event.name) {
            "message:created" -> {
                val raw = data.obj("message") ?: return
                if (MessageRules.isHiddenRow(raw) || !MessageRules.isVisibleActivity(raw)) return
                // The channel names one conversation: a row without its own id belongs to it.
                if (raw.text("conversationId") == null) raw.put("conversationId", id)
                val row = Message.parse(raw) ?: return
                if (row.role == MessageRole.AGENT || row.role == MessageRole.BOT) stopAgentTyping()
                if (row.contentOmitted) {
                    // Too large for realtime: never paint it empty, read it in full now.
                    pollNow()
                    changed()
                    return
                }
                if (MessageRules.endsHandoverWait(row)) {
                    waitingPushAt = clock.elapsed()
                    waitEndPushAt = waitingPushAt
                }
                val known = messages.any { it.id == row.id }
                appendMessage(row)
                if (MessageRules.endsBotTurn(row)) {
                    // A late re-push of an earlier reply must not end the turn of the new send.
                    if (answersHold(row, known)) releaseHold()
                } else if (row.role == MessageRole.BOT && row.isTyping) {
                    // A pushed placeholder raises the typing indicator at once
                    // and the poll switches to its fast pace now.
                    botThinking = true
                    botTool = row.attributes.botTool
                    pollNow()
                }
                if (visible && appForeground) {
                    // A reply the visitor sees arrives on screen: a poll with seen=1 now marks it read
                    // on the server, so no push follows 12 s later. pollNow coalesces: a poll in flight is followed by one more, never more.
                    if ((row.role == MessageRole.AGENT || row.role == MessageRole.BOT) && !row.isTyping) pollNow()
                    markRead()
                }
            }
            "typing:start", "typing:stop" -> {
                if (!about || data.string("userId") == "bot") return
                stopAgentTyping()
                if (event.name == "typing:start") {
                    agentTyping = true
                    // No stop within 6 s: the dots go on their own.
                    agentTypingTimer = identityScope.launch {
                        delay(AGENT_TYPING_MILLIS)
                        agentTyping = false
                        changed()
                    }
                }
            }
            "queue:position" -> {
                if (!about) return
                queuePushAt = clock.elapsed()
                queuePosition = data.int("position")
            }
            "handover:waiting" -> {
                if (!about) return
                waitingPushAt = clock.elapsed()
                waiting = data.isTrue("waiting")
                // A new wait (another transfer) is the server's word now.
                if (waiting) waitEndPushAt = NEVER
            }
            "conversation:closed" -> {
                if (!about) return
                markClosed()
            }
            "bot.turn_ended" -> {
                if (!about || data.opt("replied") != false) return
                botTurnEndedAt = clock.elapsed()
                releaseHold()
            }
            "realtime:resync" -> {
                queuePushAt = NEVER
                pollNow()
                return
            }
            else -> return
        }
        persistCache()
        changed()
    }

    /**
     * Whether a pushed row that ends the bot's turn answers the send the
     * hold is for, by row order and server times only: it comes
     * after that send's own row, or, while that row is not here yet, it is
     * new and not older than the newest row when the hold was raised.
     */
    private fun answersHold(row: Message, known: Boolean): Boolean {
        val hold = pendingReply ?: return true
        val own = messages.firstOrNull { it.clientAssignedUuid == hold.uuid }
        if (own != null) return MessageRules.ORDER.compare(row, own) > 0
        return !known && row.createdAt >= hold.floor
    }

    private fun stopAgentTyping() {
        agentTypingTimer?.cancel()
        agentTypingTimer = null
        agentTyping = false
    }

    // ── Closed chats ─────────────────────────────────────────────────────

    private fun markClosed() {
        closedAt = clock.elapsed()
        releaseHold()
        setClosed(true)
    }

    private fun setClosed(value: Boolean) {
        if (closed == value) return
        closed = value
        if (value) {
            botThinking = false
            botTool = null
            agentTyping = false
            queuePosition = null
            waiting = false
            closeRealtime()
        } else {
            ensureRealtime()
        }
    }

    // ── Read state ───────────────────────────────────────────────────────

    /**
     * The visitor sees the thread: the marker moves to the newest row, the
     * restored count goes. The marker is that row's server time, never the
     * device clock: unread compares it with server times only, so a device
     * clock that runs ahead hides no reply. It only moves forward: the
     * marker counts for every conversation of the visitor, so an older
     * conversation read after a newer one leaves it where it is.
     */
    private fun markRead() {
        val id = conversationId ?: return
        restoredUnread = null
        if (listEntries.any { it.id == id && it.unread != 0 }) {
            listEntries = listEntries.map { if (it.id == id) it.copy(unread = 0) else it }
        }
        val newest = messages.maxOfOrNull { it.createdAt } ?: return
        val known = record.lastReadAt
        // One write per newer row.
        if (known == null || newest > known) {
            record = record.copy(lastReadAt = newest)
            if (ownsRecord()) persistRecord()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Conversations: open, start new, follow the server
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Shows one of the visitor's conversations; the poll fills it.
     * Messages on their way keep going to the conversation they were written
     * in and show only there; a sent one of another
     * conversation goes, its row comes with that conversation's poll.
     */
    fun openConversation(id: String) = openConversation(id, tapped = false)

    /**
     * [tapped]: a notification's conversation, opened because its visitor
     * key names this visitor. Until the server shows a row of it, its first
     * empty answer means it is not theirs ([tapRefused]).
     */
    fun openConversation(id: String, tapped: Boolean) {
        if (id == conversationId) return
        stopTypingSignal()
        val entry = listEntries.firstOrNull { it.id == id }
        resetConversationView(keepBotHold = false)
        closeRealtime()
        conversationId = id
        epoch++
        unsent = null
        if (!continuous) messages = emptyList()
        val left = pending.filter { it.state == PendingState.SENT && it.conversationId != id && it.uuid !in unbound }
        pending = pending - left.toSet()
        left.forEach { forget(it.uuid) }
        uploads = emptyList()
        uploadSentAt.clear()
        resolved = entry?.resolved == true
        forceNew = false
        // Only a conversation known to be this visitor's becomes the last one: an id of a tap that
        // is not (yet) confirmed is never stored.
        val owned = ownsConversation(id) == true
        if (owned) rememberConversation(id)
        tappedUnconfirmed = if (tapped && !owned) id else null
        refusedTap = null
        ensureRealtime()
        pollSoon()
        persistCache()
        changed()
    }

    /**
     * "Start a new conversation": in continuous chat, or on a thread that
     * has nothing yet, nothing changes; otherwise the thread clears and the
     * next send asks for a new conversation.
     */
    fun startNewConversation() {
        if (continuous) return
        if (conversationId == null && messages.none { it.role == MessageRole.VISITOR } && visiblePending().isEmpty()) {
            // Nothing to clear, but still a new one: a restore on its way must not bring the old conversation back
            // (presentNewConversation at a cold start, before the restore answered).
            if (!forceNew) {
                forceNew = true
                changed()
            }
            return
        }
        stopTypingSignal()
        closeRealtime()
        clearThread()
        // Messages on their way keep going where they were written; they no longer show here.
        uploads = emptyList()
        uploadSentAt.clear()
        forceNew = true
        preChatValues = null
        askedStep = null
        missingFields = emptyList()
        heldMessages = emptyList()
        if (ownsRecord()) {
            record = record.copy(lastConversationId = null)
            persistRecord()
        }
        ensurePolling()
        persistCache()
        changed()
    }

    /**
     * After a write the server answered (followUpAfterWrite): into
     * the conversation it wrote into, or the conversation is open again.
     * Nothing changes when the visitor moved to another thread meanwhile:
     * [sentFresh] names the fresh thread a write without a
     * conversation came from.
     */
    private fun followWrite(sentInto: String?, sentFresh: Int?, landedIn: String?, reopened: Boolean, keepBotHold: Boolean) {
        if (!isCurrentThread(sentInto, sentFresh)) return
        if (landedIn != null && landedIn != sentInto) {
            if (sentInto != null) {
                resetConversationView(keepBotHold)
                if (!continuous) messages = emptyList()
            }
            conversationId = landedIn
            epoch++
            restoredUnread = null
            rememberConversation(landedIn)
            // The caller polls next (pollSoon), which starts the loop for the new conversation.
            ensureRealtime()
            ensureListLoop()
            return
        }
        if (reopened && sentInto != null) patchStatus(ConversationStatus.OPEN)
    }

    /** A status change the visitor made, shown at once; older polls do not undo it. */
    private fun patchStatus(status: ConversationStatus) {
        val id = conversationId ?: return
        localStatusAt = clock.elapsed()
        val isResolved = status == ConversationStatus.RESOLVED
        val at = if (isResolved) clock.now() else null
        val index = segments.indexOfFirst { it.id == id }
        val segment = ThreadSegment(id, status, at, segments.getOrNull(index)?.createdAt)
        segments = if (index < 0) segments + segment else segments.toMutableList().also { it[index] = segment }
        if (!isResolved && resolved) {
            csatState = CsatState.IDLE
            transcriptState = TranscriptState.IDLE
        }
        resolved = isResolved
        continuesAfterResolve = false
        endedLocally = false
    }

    /** The per conversation view state, until the first poll of another conversation says otherwise. */
    private fun resetConversationView(keepBotHold: Boolean) {
        resolved = false
        continuesAfterResolve = false
        pollShowCsat = false
        endedLocally = false
        queuePosition = null
        waiting = false
        waitEndPushAt = NEVER
        serverAgentReplied = null
        csatState = CsatState.IDLE
        transcriptState = TranscriptState.IDLE
        restoredUnread = null
        if (!continuous) segments = emptyList()
        stopAgentTyping()
        if (!keepBotHold) releaseHold()
    }

    /** An empty thread without a conversation: a new fresh thread and a new epoch; a loop of the old one stops. */
    private fun clearThread() {
        resetConversationView(keepBotHold = false)
        conversationId = null
        tappedUnconfirmed = null
        messages = emptyList()
        segments = emptyList()
        unsent = null
        epoch++
        freshKey++
        ensurePolling()
    }

    private fun rememberConversation(id: String?) {
        if (record.lastConversationId == id) return
        record = record.copy(lastConversationId = id)
        if (ownsRecord()) persistRecord()
    }

    /**
     * The stored record is this visitor's own. A new visitor (a placeholder)
     * never writes it: it may hold a dormant verified identity that must
     * stay as it is until its user comes back.
     */
    private fun ownsRecord(): Boolean = !placeholder && bearer != null && record.bearer == bearer

    /** The thread on screen is the one a write went into: the same conversation, or the same fresh thread. */
    private fun isCurrentThread(conversation: String?, fresh: Int?): Boolean =
        conversationId == conversation && (conversation != null || fresh == freshKey)

    /** Whether a pending row shows in the thread on screen. */
    private fun showsHere(item: PendingSend): Boolean = when {
        item.uuid in unbound -> true
        item.conversationId != null -> item.conversationId == conversationId
        else -> conversationId == null && freshOf[item.uuid] == freshKey
    }

    private fun visiblePending(): List<PendingSend> = pending.filter(::showsHere)

    // ═════════════════════════════════════════════════════════════════════
    // Sending
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Sends a message and answers at once: the bubble shows now
     * and delivery runs in the background; the server stores it once however
     * often it goes out. [action] is Fynn's team button; [replyTo] quotes an
     * earlier message. While a step before the chat is due the text waits in
     * [ThreadState.heldMessages] until the step is answered. No text is ever
     * dropped without an outcome that says why.
     */
    fun send(text: String, action: FynnAction? = null, replyTo: ReplyQuote? = null, article: HelpArticleRef? = null): SendOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SendOutcome.Refused(SendRefusal.EMPTY)
        if (trimmed.length > MAX_MESSAGE_CHARS) return SendOutcome.Refused(SendRefusal.TOO_LONG)
        if (config == null || phase == Phase.FAILED || !rootJob.isActive) return SendOutcome.Refused(SendRefusal.NOT_READY)
        if (!canWrite()) return SendOutcome.Refused(SendRefusal.CLOSED)
        if (gate() != Gate.None || heldMessages.isNotEmpty()) {
            // Behind texts still held: never ahead of them, whether the step is due or just went away.
            hold(listOf(trimmed))
            return releaseHeld().lastOrNull()?.let { SendOutcome.Queued(it) } ?: SendOutcome.Held
        }
        val uuid = enqueue(trimmed, action, replyTo, article)
        dispatch(listOf(uuid))
        return SendOutcome.Queued(uuid)
    }

    /**
     * A pending row for [text] in the thread on screen. Its id is new, except
     * for an immediate resend of the failed text still shown, which keeps the
     * failed one's id, so a copy the server stored after all comes back once.
     */
    private fun enqueue(text: String, action: FynnAction? = null, replyTo: ReplyQuote? = null, article: HelpArticleRef? = null): String {
        val failed = unsent?.let { u ->
            pending.firstOrNull { it.uuid == u.uuid && it.state == PendingState.FAILED && showsHere(it) }
                ?.takeIf { u.text == text && u.bearer == bearer }
        }
        val uuid = failed?.uuid ?: newUuid().lowercase()
        val settled = phase == Phase.READY
        val into = failed?.conversationId ?: if (settled) conversationId else null
        pending = pending.filterNot { it.uuid == uuid } +
            PendingSend(uuid, text, clock.now(), PendingState.SENDING, conversationId = into, replyTo = replyTo, action = action, helpArticle = article)
        if (failed == null) {
            if (!settled) unbound += uuid else if (into == null) freshOf[uuid] = freshKey
        }
        holdForBot(uuid)
        changed()
        return uuid
    }

    /** Sends a message that was not sent again, with its own id, in the background. */
    fun retry(pendingUuid: String) {
        val failed = pending.firstOrNull { it.uuid == pendingUuid && it.state == PendingState.FAILED } ?: return
        if (phase == Phase.FAILED || !canWrite()) return
        updatePending(failed.uuid) { it.copy(state = PendingState.SENDING, error = null) }
        holdForBot(pendingUuid)
        changed()
        dispatch(listOf(pendingUuid))
    }

    /** Drops a message that was not sent; its id is never sent again. */
    fun discard(pendingUuid: String) {
        val gone = pending.filter { it.uuid == pendingUuid && it.state == PendingState.FAILED }
        if (gone.isEmpty()) return
        pending = pending - gone.toSet()
        forget(pendingUuid)
        if (unsent?.uuid == pendingUuid) unsent = null
        changed()
    }

    /**
     * A choice, start or Fynn button, answered at
     * once like [send]. "Frage geklärt" shows the visitor's choice now and
     * resolves in the background; its row goes again when that fails.
     */
    fun pressButton(button: ChatButton): SendOutcome = when (button) {
        is ChatButton.Choice -> send(button.label)
        is ChatButton.Greeting -> send(button.label)
        is ChatButton.FynnTeam -> send(button.label, action = FynnAction.TEAM)
        ChatButton.FynnResolved -> {
            val start = beginResolve()
            if (start == null) {
                SendOutcome.Refused(if (closed) SendRefusal.CLOSED else SendRefusal.NOT_READY)
            } else {
                identityScope.launch { finishResolve(start) }
                SendOutcome.Queued(start.uuid)
            }
        }
    }

    /** Texts that wait for the step before the chat, after the ones already waiting. */
    private fun hold(texts: List<String>) {
        if (texts.isEmpty()) return
        heldMessages = heldMessages + texts
        changed()
    }

    /**
     * Delivers [uuids] one after the other in the background. The wait for
     * the start or a verification in progress runs outside the visitor's
     * scope: a message written meanwhile goes out as the visitor the
     * verification settles on. Each delivery runs in the
     * visitor's scope, so a logout or a user switch stops it.
     */
    private fun dispatch(uuids: List<String>) {
        scope.launch {
            for (uuid in uuids) {
                while (true) {
                    if (!awaitReady()) {
                        failSend(uuid, SendFailure.NOT_SENT, rememberUnsent = true)
                        break
                    }
                    bind(uuid)
                    val delivery = identityScope.launch { deliver(uuid) }
                    delivery.join()
                    // A user switch stopped it before it left: it goes out as the visitor the
                    // switch settled on. After a logout the row is gone and nothing goes.
                    val waiting = pending.any { it.uuid == uuid && it.state == PendingState.SENDING && it.uuid !in inFlight }
                    if (!delivery.isCancelled || !waiting) break
                }
            }
        }
    }

    /** A row written before the thread was settled belongs to the thread on screen once it is. */
    private fun bind(uuid: String) {
        if (!unbound.remove(uuid)) return
        val id = conversationId
        updatePending(uuid) { it.copy(conversationId = id) }
        if (id == null) freshOf[uuid] = freshKey
    }

    private suspend fun deliver(uuid: String) {
        try {
            deliverNow(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A surprise never leaves a bubble spinning.
            logger.debug("A send failed unexpectedly (${e.javaClass.simpleName})")
            failSend(uuid, SendFailure.NOT_SENT, rememberUnsent = true)
        }
    }

    private suspend fun deliverNow(uuid: String) {
        // Until the server confirmed this visitor's bearer, one send at a time; and in a thread
        // without a conversation the first send goes alone. The next ones go out with the bearer
        // and into the conversation the first brought back: one visitor never becomes two
        // and 2 quick messages never make 2 conversations.
        var item: PendingSend
        while (true) {
            item = pending.firstOrNull { it.uuid == uuid } ?: return
            if (item.state != PendingState.SENDING) return
            // The step before the chat may have become due while this one waited.
            if (showsHere(item) && gate() != Gate.None) {
                pending = pending.filterNot { it.uuid == uuid }
                forget(uuid)
                releaseHoldFor(uuid)
                hold(listOf(item.text))
                return
            }
            val lead = leadSend
            if (lead != null && (!confirmed || item.conversationId == null)) {
                lead.await()
                continue
            }
            break
        }
        val sendAs = bearer ?: run {
            failSend(uuid, SendFailure.NOT_SENT, rememberUnsent = true)
            return
        }
        val into = item.conversationId
        val fresh = if (into == null) freshOf[uuid] else null
        val others = if (!confirmed || into == null) CompletableDeferred<Unit>().also { leadSend = it } else null
        inFlight += uuid
        try {
            // "Start a new conversation" rides on the sends of that fresh thread and stays with
            // their retries; only one of them is ever on its way (see above).
            if (into == null && forceNew && fresh == freshKey) forceNewRows += uuid
            val body = sendBody(item, sendAs, into, forceNewConversation = into == null && uuid in forceNewRows)
            // The answers of the step leave with this send: only its success uses them up.
            preChatValues?.let { preChatCarriedBy[uuid] = it }
            // A new visitor's first message gets the whole budget for one try: making the contact
            // may take longer than the usual 30 s.
            val timeout = if (confirmed) SendRetry.ATTEMPT_TIMEOUT_MILLIS else SendRetry.FIRST_SEND_TIMEOUT_MILLIS
            val result = sendWithRetry { api.send(body, uuid, timeout) }
            when (result) {
                is ApiResult.Success -> onSent(uuid, sendAs, into, fresh, result.value)
                is ApiResult.Failure -> onSendRefused(uuid, result.error)
                is ApiResult.NetworkFailure -> failSend(uuid, SendFailure.NOT_SENT, rememberUnsent = true)
            }
        } finally {
            inFlight -= uuid
            if (others != null) {
                if (leadSend === others) leadSend = null
                others.complete(Unit)
            }
        }
    }

    /**
     * Tries until an answer that is not a 5xx, within the 2-minute budget on
     * the monotonic clock. Each try reports to [noteAnswer], so a
     * lost connection shows at its first try, not after the budget.
     */
    private suspend fun sendWithRetry(call: suspend () -> ApiResult<SendAnswer>): ApiResult<SendAnswer> {
        val started = clock.elapsed()
        var tries = 1
        while (true) {
            val result = call()
            noteAnswer(result)
            val retryAfter = when (result) {
                is ApiResult.Success -> return result
                is ApiResult.Failure -> if (result.error.isServerError) result.error.retryAfterSeconds else return result
                is ApiResult.NetworkFailure -> null
            }
            val pause = SendRetry.pause(tries, retryAfter, random)
            if (clock.elapsed() - started + pause > SendRetry.BUDGET_MILLIS) return result
            delay(pause)
            tries++
        }
    }

    private fun sendBody(item: PendingSend, sendAs: String, into: String?, forceNewConversation: Boolean): JSONObject {
        val body = JSONObject()
            .put("contactIdentifier", sendAs)
            .put("content", item.text)
            .putNullable("conversationId", into)
            .put("forceNewConversation", forceNewConversation)
        // The visitor's language: stored on a new contact once and given to the bot.
        Locales.sendTag(effectiveLocale())?.let { body.put("widgetLocale", it) }
        item.replyTo?.let { body.put("replyToMessageId", it.messageId) }
        item.action?.let { body.put("fynnAction", it.wire) }
        // The article the question comes from, as the help center hands it over (the server looks the title up itself).
        item.helpArticle?.let {
            body.put("context", JSONObject().put("hc_source", "help_center").put("hc_article_url", it.url).put("hc_article_title", it.title))
        }
        // The answers of the steps before the chat ride on the first message.
        preChatValues?.let { values ->
            val extra = JSONObject()
            for ((key, value) in values) {
                when (key) {
                    "name", "email", "phone" -> body.put(key, value)
                    else -> extra.put(key, value)
                }
            }
            if (extra.length() > 0) body.put("preChatFormValues", extra)
        }
        return body
    }

    private fun onSent(uuid: String, sentAs: String, sentInto: String?, sentFresh: Int?, answer: SendAnswer) {
        updatePending(uuid) { it.copy(state = PendingState.SENT, error = null) }
        // Any later successful send ends the reuse of a failed message's id.
        unsent = null
        // "Start a new conversation" and the step's answers are used up only by the send that
        // carried them; another send's success (one still on its way for the conversation the
        // visitor left) changes neither. The sends of a thread without a conversation go one at a
        // time and the first carries "new", so only a send of the current fresh thread clears it.
        forceNewRows.remove(uuid)
        val carriedAnswers = preChatCarriedBy.remove(uuid)
        // The dashboard drops its typing line when the message lands: the next key signals at once.
        typingLastSent = null
        // An answer for an identity the session has left is not adopted.
        val sameIdentity = sentAs == bearer
        if (sameIdentity) {
            val minted = answer.contactIdentifier
            confirmed = true
            bearer = minted
            placeholder = false
            storeBearer(minted)
            if (answer.emailKnown) emailKnown = true
            val landed = answer.conversationId
            if (landed != null) confirmOwn(listOf(landed))
            // The other messages of that fresh thread go into the conversation this one made.
            if (sentInto == null && sentFresh != null && landed != null) bindFresh(sentFresh, landed)
            if (sentInto == null && sentFresh == freshKey) forceNew = false
            followWrite(sentInto, sentFresh, landed, answer.reopened, keepBotHold = true)
            if (carriedAnswers != null && carriedAnswers === preChatValues) {
                preChatValues = null
                askedStep = null
                missingFields = emptyList()
            }
        }
        // Hold the typing dots only for a send the server says a bot will answer,
        // and only the hold this very message raised.
        if (!sameIdentity || !answer.botWillReply) releaseHoldFor(uuid)
        dropConfirmedPending()
        ensureRealtime()
        ensureListLoop()
        persistCache()
        changed()
        pollSoon()
        if (answer.newConversation) identityScope.launch { loadList() }
    }

    /** Rows of the fresh thread [fresh] belong to [conversation] from now on. */
    private fun bindFresh(fresh: Int, conversation: String) {
        val moved = pending.filter { it.conversationId == null && it.uuid !in unbound && freshOf[it.uuid] == fresh }
        if (moved.isEmpty()) return
        val ids = moved.mapTo(HashSet()) { it.uuid }
        pending = pending.map { if (it.uuid in ids) it.copy(conversationId = conversation) else it }
        ids.forEach(freshOf::remove)
    }

    /**
     * Keeps the bearer the server confirmed. An anonymous visitor who wrote
     * while a dormant verified identity was stored replaces it: the dormant
     * data is deleted now.
     */
    private fun storeBearer(minted: String) {
        if (verifiedExternalId == null && record.verifiedExternalId != null) {
            // The dormant identity leaves the device: the phone must not stay registered to it,
            // and its pictures and file copies on the disk go. The visitor on screen goes on.
            push.forget(mayBeRegistered(record.bearer).filter { it != minted }, durable = !cookieless)
            record = VisitorRecord(bearer = minted)
            if (!cookieless) onDisk { cache.clear() }
            persistRecord()
            visitorWiped?.invoke(false)
        } else if (record.bearer != minted) {
            record = record.withBearer(minted)
            persistRecord()
        }
    }

    private suspend fun onSendRefused(uuid: String, error: ApiError) {
        val cfg = config
        when {
            error.status == 403 && error.code == CODE_CONVERSATION_CLOSED -> {
                // Closed for this visitor: read only, the text is not offered again.
                pending = pending.filterNot { it.uuid == uuid }
                forget(uuid)
                markClosed()
                changed()
            }
            // The pre chat refusal, with its code or only with `missingFields`.
            error.status == 400 && (error.code == CODE_PRE_CHAT_REQUIRED || error.missingFields != null) &&
                cfg != null && !cfg.botCollectsFields -> {
                // The server wants the form, but the config in hand has none: it may predate the owner's
                // change (the edge keeps it a minute, the disk cache longer). Asked again past every
                // cache, so the visitor gets the form instead of the email step; without a fresh answer,
                // or with no form in it either, the config in hand decides as before.
                val current = if (asksForm(cfg)) cfg else fetchFreshConfig() ?: cfg
                recoverPreChat(uuid, current, error.missingFields.orEmpty())
            }
            error.status == 400 && error.code == CODE_EMAIL_REQUIRED -> {
                // The server wants the email the config did not ask for.
                emailKnown = false
                preChatValues = null
                askedStep = AskedStep.EMAIL
                recoverGate(uuid)
            }
            else -> failSend(uuid, sendFailureOf(error), rememberUnsent = error.isServerError)
        }
    }

    /**
     * 400 "Pre-chat form fields required": the stored bearer named a contact
     * that is gone. A new placeholder takes over, the texts wait, and the
     * form asks for what the server listed. A dormant
     * verified record that is not this visitor's stays as it is.
     */
    private fun recoverPreChat(uuid: String, cfg: WidgetConfig, missing: List<String>) {
        val owned = ownsRecord()
        bearer = newUuid().lowercase()
        placeholder = true
        confirmed = false
        verifiedExternalId = null
        verifiedUser = null
        emailKnown = false
        if (owned) {
            record = record.withBearer(null).copy(verifiedExternalId = null, lastConversationId = null)
            persistRecord()
        }
        clearThread()
        ensureRealtime()
        missingFields = missing
        preChatValues = null
        askedStep = when {
            asksForm(cfg) -> AskedStep.FORM
            cfg.emailCollectEnabled -> AskedStep.EMAIL
            else -> null
        }
        if (askedStep == null) failSend(uuid, SendFailure.REJECTED, rememberUnsent = false) else recoverGate(uuid)
    }

    private fun asksForm(cfg: WidgetConfig): Boolean = cfg.preChatFormEnabled && cfg.preChatFields.isNotEmpty()

    /** The config as the server has it now, past every cache, applied when it came; null without a usable answer. */
    private suspend fun fetchFreshConfig(): WidgetConfig? {
        val result = api.config(pageLanguage(), fresh = true)
        noteAnswer(result)
        if (result !is ApiResult.Success) return null
        configFetchedAt = clock.elapsed()
        applyConfig(result.value)
        persistCache()
        return result.value
    }

    /**
     * The server asked for a step before the chat: the refused message and
     * every message of this visitor that has not left yet move into
     * [heldMessages] in the order written. Nothing stays in sending and
     * nothing goes out before the step.
     */
    private fun recoverGate(uuid: String) {
        val moved = pending.filter { it.uuid == uuid || (it.state == PendingState.SENDING && !it.questionResolved && it.uuid !in inFlight) }
        if (moved.isEmpty()) {
            changed()
            return
        }
        pending = pending - moved.toSet()
        moved.forEach { item ->
            forget(item.uuid)
            forceNewRows.remove(item.uuid)
            releaseHoldFor(item.uuid)
            if (unsent?.uuid == item.uuid) unsent = null
        }
        hold(moved.map { it.text })
    }

    private fun failSend(uuid: String, failure: SendFailure, rememberUnsent: Boolean) {
        val item = pending.firstOrNull { it.uuid == uuid }
        updatePending(uuid) { it.copy(state = PendingState.FAILED, error = failure) }
        if (rememberUnsent && item != null) unsent = Unsent(item.text, uuid, bearer)
        releaseHoldFor(uuid)
        changed()
    }

    private fun sendFailureOf(error: ApiError): SendFailure = when {
        error.isServerError -> SendFailure.NOT_SENT
        error.status == 413 -> SendFailure.MESSAGE_TOO_LONG
        error.status == 429 && error.code == "new_conversation_limit" -> SendFailure.NEW_CONVERSATION_LIMIT
        error.status == 429 -> SendFailure.RATE_LIMITED
        error.status == 403 && error.code == "monthly_conversation_limit" -> SendFailure.MONTHLY_CONVERSATION_LIMIT
        error.status == 409 -> SendFailure.CONVERSATION_RESOLVED
        else -> SendFailure.REJECTED
    }

    private inline fun updatePending(uuid: String, change: (PendingSend) -> PendingSend) {
        pending = pending.map { if (it.uuid == uuid) change(it) else it }
    }

    // ── The bot's typing hold ────────────────────────────────────────────

    /** Right after a send to a live bot: the typing dots, held until its reply or 35 s at most. */
    private fun holdForBot(uuid: String) {
        if (config?.botActive != true) return
        botThinking = true
        botTool = null
        pendingReply = PendingReply(uuid, clock.elapsed(), messages.maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE)
        holdTimer?.cancel()
        holdTimer = identityScope.launch {
            delay(PENDING_REPLY_MAX_MILLIS)
            if (pendingReply?.uuid == uuid) {
                pendingReply = null
                val last = messages.lastOrNull { it.role != MessageRole.VISITOR && it.role != MessageRole.SYSTEM }
                botThinking = last != null && last.isTyping
                if (!botThinking) botTool = null
                changed()
            }
        }
        pollNow()
    }

    /** Ends the hold only when [uuid] raised it: an answer for another message leaves it up. */
    private fun releaseHoldFor(uuid: String) {
        if (pendingReply?.uuid == uuid) releaseHold()
    }

    private fun releaseHold() {
        pendingReply = null
        holdTimer?.cancel()
        holdTimer = null
        botThinking = false
        botTool = null
    }

    // ═════════════════════════════════════════════════════════════════════
    // Steps before the chat
    // ═════════════════════════════════════════════════════════════════════

    /**
     * The step a new message would have to pass right now, for the UI's
     * "Write to us": the form or the email step first, else straight into the
     * thread (gateBeforeChat). Read synchronously, so it already
     * reflects a [startNewConversation] called just before.
     */
    fun gateBeforeChat(): Gate = gate()

    /** Which step comes before the first message (gateBeforeChat). */
    private fun gate(): Gate {
        val cfg = config ?: return Gate.None
        if (preChatValues != null) return Gate.None
        when (askedStep) {
            AskedStep.FORM -> return Gate.PreChatForm(fieldsToAsk(cfg))
            AskedStep.EMAIL -> return Gate.EmailCollect
            null -> Unit
        }
        if (cfg.botCollectsFields) return Gate.None
        if (verifiedExternalId != null && confirmed) return Gate.None
        val fresh = forceNew || (messages.isEmpty() && conversationId == null)
        if (!placeholder && !fresh) return Gate.None
        if (cfg.preChatFormEnabled && cfg.preChatFields.isNotEmpty()) {
            val fields = fieldsToAsk(cfg)
            return if (fields.isNotEmpty()) Gate.PreChatForm(fields) else Gate.None
        }
        if (cfg.emailCollectEnabled && !emailKnown) return Gate.EmailCollect
        return Gate.None
    }

    /** The form leaves out only its email questions when the server knows an email. */
    private fun fieldsToAsk(cfg: WidgetConfig): List<PreChatField> =
        if (emailKnown) cfg.preChatFields.filterNot { it.isEmail } else cfg.preChatFields

    /**
     * The pre chat form's answers, keyed by field name and trimmed.
     * Answers at once: empty when accepted, and the held
     * messages then go out in the background.
     */
    fun submitPreChat(values: Map<String, String>): Map<String, FieldProblem> {
        val cfg = config ?: return emptyMap()
        val fields = (gate() as? Gate.PreChatForm)?.fields ?: fieldsToAsk(cfg)
        val answers = values.mapValues { it.value.trim() }
        val problems = LinkedHashMap<String, FieldProblem>()
        for (field in fields) {
            val value = answers[field.name].orEmpty()
            if (value.isEmpty()) {
                if (field.required) problems[field.name] = FieldProblem.REQUIRED
            } else if (field.isEmail) {
                EmailCheck.check(value)?.let { problems[field.name] = it }
            }
        }
        if (problems.isNotEmpty()) return problems
        val asked = fields.mapTo(HashSet()) { it.name }
        preChatValues = answers.filter { (key, value) -> key in asked && value.isNotEmpty() }
        askedStep = null
        missingFields = emptyList()
        changed()
        sendHeld()
        return emptyMap()
    }

    /** The email step's address. Answers at once: null when accepted, and the held messages then go out in the background. */
    fun submitEmail(email: String): FieldProblem? {
        val address = email.trim()
        EmailCheck.check(address)?.let { return it }
        preChatValues = mapOf("email" to address)
        askedStep = null
        missingFields = emptyList()
        changed()
        sendHeld()
        return null
    }

    /** The visitor left the step before the chat: the held texts go back to them (the UI puts them into the field). */
    fun cancelGate() {
        heldMessages = emptyList()
        changed()
    }

    /** The step is answered: the held texts go out in the order written, into one conversation. */
    private fun sendHeld(): List<String> {
        val held = heldMessages
        if (held.isEmpty()) return emptyList()
        heldMessages = emptyList()
        val uuids = held.map { enqueue(it) }
        dispatch(uuids)
        return uuids
    }

    /**
     * The step before the chat went away by itself while texts waited for it
     * (a late identify verified the visitor, the inbox switched the step off,
     * a conversation was opened while the step showed): the texts go out as
     * normal pending rows, in order, into the conversation on screen or as the
     * next message. Only once the visitor has settled, so they go where the
     * visitor's next message would. Leaving the step on purpose ([cancelGate])
     * hands them back instead. The ids of the rows made, oldest first.
     */
    private fun releaseHeld(): List<String> {
        if (heldMessages.isEmpty() || config == null || !rootJob.isActive) return emptyList()
        if (phase != Phase.READY || !visitorLoaded || applyJob?.isActive == true) return emptyList()
        if (gate() != Gate.None || !canWrite()) return emptyList()
        return sendHeld()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Typing
    // ═════════════════════════════════════════════════════════════════════

    /**
     * The visitor types (true on every change of a non empty field) or the
     * field emptied (false). At most one start every 3 s, a stop 4 s after
     * the last key; keys in another conversation stop the old one first.
     */
    fun setTyping(isTyping: Boolean) {
        val id = conversationId ?: return
        if (bearer == null || placeholder || !canWrite()) return
        if (!isTyping) {
            if (typingStopTimer?.isActive == true) {
                typingStopTimer?.cancel()
                typingStopTimer = null
                typingLastSent = null
                postTyping(id, false)
            }
            return
        }
        if (id != typingConversation) {
            val left = typingConversation
            if (left != null && typingStopTimer?.isActive == true) postTyping(left, false)
            typingConversation = id
            typingLastSent = null
        }
        val now = clock.elapsed()
        val last = typingLastSent
        if (last == null || now - last > TYPING_THROTTLE_MILLIS) {
            typingLastSent = now
            postTyping(id, true)
        }
        typingStopTimer?.cancel()
        typingStopTimer = identityScope.launch {
            delay(TYPING_STOP_MILLIS)
            typingStopTimer = null
            typingLastSent = null
            postTyping(id, false)
        }
    }

    private fun stopTypingSignal() {
        val left = typingConversation ?: return
        if (typingStopTimer?.isActive == true) {
            typingStopTimer?.cancel()
            postTyping(left, false)
        }
        typingStopTimer = null
        typingLastSent = null
    }

    /** Fire and forget: the answer and any failure are ignored, as the web does. */
    private fun postTyping(id: String, isTyping: Boolean) {
        val asBearer = bearer ?: return
        identityScope.launch { noteAnswer(api.typing(asBearer, id, isTyping)) }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Uploads
    // ═════════════════════════════════════════════════════════════════════

    /**
     * One file into the conversation. Null when the server stored it (its
     * row comes with the next poll or push); else the refusal's code, which
     * also stays in the thread as a failed upload until [dismissUpload].
     * [id] names the upload (stored lower case) from the pick to the stored
     * message, so the UI can key its row by it; without one the engine
     * makes one.
     */
    suspend fun upload(source: UploadSource, fileName: String, mimeType: String, id: String? = null): UploadErrorCode? =
        inSession(UploadErrorCode.UPLOAD_FAILED) { uploadNow(source, fileName, mimeType, id) }

    private suspend fun uploadNow(source: UploadSource, fileName: String, mimeType: String, requestedId: String?): UploadErrorCode? {
        val cfg = config ?: return UploadErrorCode.UPLOAD_FAILED
        if (closed) return UploadErrorCode.UPLOAD_FAILED
        val mime = mimeType.trim().ifEmpty { "application/octet-stream" }
        // Null: no extension the server takes; the line shows the name as given.
        val name = UploadRules.fileName(fileName, mime, "file")
        val shownName = name ?: fileName.substringAfterLast('/').trim().ifEmpty { "file" }
        val id = requestedId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: newUuid().lowercase()
        val startedAt = clock.now()

        fun refuse(code: UploadErrorCode): UploadErrorCode {
            putUpload(UploadState(id, shownName, UploadPhase.FAILED, code, mimeType = mime, startedAt = startedAt))
            changed()
            return code
        }

        if (!cfg.featureFilePicker) return refuse(UploadErrorCode.UPLOADS_DISABLED)
        // A file can never be the first message: a text first.
        val conversation = conversationId
        val asBearer = bearer
        if (conversation == null || asBearer == null || placeholder) return refuse(UploadErrorCode.CONVERSATION_NOT_STARTED)
        if (inEndState(cfg) || phase != Phase.READY) return refuse(UploadErrorCode.UPLOAD_FAILED)
        // The size and type checks the server makes, before any request.
        if (name == null || !UploadRules.mimeAllowed(mime, cfg.allowedFileTypes)) return refuse(UploadErrorCode.FILE_TYPE_NOT_ALLOWED)
        val bytes = when (val read = uploadReader.read(source, cfg.maxUploadBytes)) {
            is UploadRead.Data -> read.bytes
            UploadRead.TooLarge -> return refuse(UploadErrorCode.FILE_TOO_LARGE)
            UploadRead.Unreadable -> return refuse(UploadErrorCode.UPLOAD_FAILED)
        }
        putUpload(UploadState(id, name, UploadPhase.UPLOADING, mimeType = mime, startedAt = startedAt))
        changed()

        fun update(phase: UploadPhase, error: UploadErrorCode?) {
            uploads = uploads.map { if (it.id == id) it.copy(phase = phase, error = error) else it }
        }

        // Told on the I/O thread for every slice that went out; the state moves in steps of 2 % at most.
        var lastShown = 0f
        val onProgress: (Long, Long) -> Unit = { sent, total ->
            val fraction = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
            if (fraction - lastShown >= PROGRESS_STEP || (fraction >= 1f && lastShown < 1f)) {
                lastShown = fraction
                scope.launch {
                    if (uploads.any { it.id == id && it.phase == UploadPhase.UPLOADING }) {
                        uploads = uploads.map { if (it.id == id) it.copy(progress = fraction) else it }
                        changed()
                    }
                }
            }
        }

        val result = api.upload(asBearer, conversation, bytes, name, mime, onProgress)
        noteAnswer(result)
        return when (result) {
            is ApiResult.Success -> {
                if (asBearer == bearer) {
                    confirmOwn(listOfNotNull(conversation, result.value.conversationId))
                    // An upload never starts a bot turn: a hold left over belongs to the old conversation.
                    followWrite(conversation, null, result.value.conversationId, result.value.reopened, keepBotHold = false)
                }
                // The stored message the upload became, so the UI keeps one row from the pick to it.
                val stored = result.value.messageId
                uploads = uploads.map { if (it.id == id) it.copy(phase = UploadPhase.SENT, error = null, progress = 1f, messageId = stored) else it }
                uploadSentAt[id] = clock.elapsed()
                pollSoon()
                changed()
                null
            }
            is ApiResult.Failure -> {
                val error = result.error
                if (error.status == 403 && error.code == CODE_CONVERSATION_CLOSED) {
                    // The whole chat is closed for the visitor: no line, the read only state.
                    uploads = uploads.filterNot { it.id == id }
                    markClosed()
                    changed()
                    return UploadErrorCode.CONVERSATION_CLOSED
                }
                val code = UploadErrorCode.from(error)
                update(UploadPhase.FAILED, code)
                changed()
                code
            }
            is ApiResult.NetworkFailure -> {
                update(UploadPhase.FAILED, UploadErrorCode.UPLOAD_FAILED)
                changed()
                UploadErrorCode.UPLOAD_FAILED
            }
        }
    }

    /**
     * Adds an upload's row, or replaces the row of the same id in place (a
     * caller that names a retry with its earlier id): the row keeps its
     * first start time, so it keeps its place in the thread.
     */
    private fun putUpload(upload: UploadState) {
        uploadSentAt.remove(upload.id)
        uploads = if (uploads.any { it.id == upload.id }) {
            uploads.map { if (it.id == upload.id) upload.copy(startedAt = it.startedAt ?: upload.startedAt) else it }
        } else {
            uploads + upload
        }
    }

    /** The UI showed a refused upload's line: it goes. */
    fun dismissUpload(id: String) {
        uploads = uploads.filterNot { it.id == id && it.phase == UploadPhase.FAILED }
        changed()
    }

    // ═════════════════════════════════════════════════════════════════════
    // After the chat: end, question resolved, CSAT, transcript
    // ═════════════════════════════════════════════════════════════════════

    /** "End conversation". True once the server resolved it (or, without a conversation, it ended locally). */
    suspend fun endConversation(): Boolean = inSession(false) { endNow() }

    private suspend fun endNow(): Boolean {
        val cfg = config ?: return false
        if (closed) return false
        val id = conversationId
        if (id == null) {
            // Nothing on the server yet: classic mode only ends locally, continuous chat does nothing.
            if (cfg.continuousChat) return false
            endedLocally = true
            changed()
            return true
        }
        if (endingConversation == id) return false
        val asBearer = bearer ?: return false
        endingConversation = id
        changed()
        try {
            val ended = api.end(asBearer, id)
            noteAnswer(ended)
            if (ended !is ApiResult.Success) return false
            if (id != conversationId) return true
            releaseHold()
            if (cfg.continuousChat) {
                patchStatus(ConversationStatus.RESOLVED)
            } else {
                resolved = true
                endedLocally = true
            }
            persistCache()
            pollNow()
            identityScope.launch { loadList() }
            return true
        } finally {
            if (endingConversation == id) endingConversation = null
            changed()
        }
    }

    /**
     * "Frage geklärt" under Fynn's newest answer ([ThreadState.fynnEndMessageId]):
     * the visitor's choice shows at once with the button's label, the server
     * resolves and answers with its line.
     */
    suspend fun markResolved(): ResolveResult = inSession(ResolveResult.FAILED) {
        val start = beginResolve()
        if (start == null) ResolveResult.FAILED else finishResolve(start)
    }

    /** What a "Frage geklärt" was for: its row, the conversation and the visitor. */
    private class ResolveStart(val uuid: String, val conversation: String, val bearer: String, val config: WidgetConfig)

    /** Shows the visitor's choice at once; null when the button does not apply now. */
    private fun beginResolve(): ResolveStart? {
        val cfg = config ?: return null
        val id = conversationId ?: return null
        val asBearer = bearer ?: return null
        val answerId = fynnEndMessageId(cfg) ?: return null
        val answer = messages.firstOrNull { it.id == answerId } ?: return null
        val label = answer.attributes.fynnEnd?.resolved ?: return null
        val uuid = newUuid().lowercase()
        pending = pending + PendingSend(
            uuid = uuid,
            text = label,
            // Just after the answer, so the choice sits under it.
            createdAt = maxOf(clock.now(), answer.createdAt + 1),
            state = PendingState.SENDING,
            conversationId = id,
            questionResolved = true,
        )
        changed()
        return ResolveStart(uuid, id, asBearer, cfg)
    }

    /** The server call of a "Frage geklärt"; its answer applies to the conversation it was for. */
    private suspend fun finishResolve(start: ResolveStart): ResolveResult {
        val uuid = start.uuid
        val id = start.conversation
        val cfg = start.config
        val result = api.resolved(start.bearer, id)
        noteAnswer(result)
        return when (result) {
            is ApiResult.Success -> {
                val line = result.value.lineId
                if (line != null) {
                    updatePending(uuid) { it.copy(state = PendingState.SENT, lineId = line) }
                } else {
                    pending = pending.filterNot { it.uuid == uuid }
                }
                if (id == conversationId) {
                    if (cfg.continuousChat) {
                        patchStatus(ConversationStatus.RESOLVED)
                    } else {
                        resolved = true
                        endedLocally = true
                    }
                }
                dropConfirmedPending()
                persistCache()
                changed()
                // Fynn's thanks and the survey come with the next poll.
                pollNow()
                ResolveResult.RESOLVED
            }
            is ApiResult.Failure -> {
                pending = pending.filterNot { it.uuid == uuid }
                if (result.error.status == 403 && result.error.code == CODE_CONVERSATION_CLOSED) markClosed()
                // A person answered meanwhile: their reply takes the buttons away.
                if (result.error.status == 409) pollNow()
                changed()
                if (result.error.code == "not_fynn_last") ResolveResult.NOT_FYNN_LAST else ResolveResult.FAILED
            }
            is ApiResult.NetworkFailure -> {
                pending = pending.filterNot { it.uuid == uuid }
                changed()
                ResolveResult.FAILED
            }
        }
    }

    /** The rating, an integer 1 to 5 for every style, and an optional comment. */
    suspend fun submitCsat(rating: Int, comment: String?): Boolean = inSession(false) { submitCsatNow(rating, comment) }

    private suspend fun submitCsatNow(rating: Int, comment: String?): Boolean {
        if (rating !in 1..5 || csatState == CsatState.SENDING) return false
        val id = conversationId ?: return false
        val asBearer = bearer ?: return false
        csatState = CsatState.SENDING
        changed()
        val ok = api.csat(asBearer, id, rating, comment?.trim().orEmpty()).also(::noteAnswer) is ApiResult.Success
        if (id == conversationId) csatState = if (ok) CsatState.SUBMITTED else CsatState.FAILED
        changed()
        return ok
    }

    /** The transcript by email; [email] only after the server asked for one. */
    suspend fun requestTranscript(email: String? = null): TranscriptState =
        inSession(TranscriptState.FAILED) { requestTranscriptNow(email) }

    private suspend fun requestTranscriptNow(email: String?): TranscriptState {
        if (transcriptState == TranscriptState.SENDING) return transcriptState
        val id = conversationId ?: return TranscriptState.FAILED
        val asBearer = bearer ?: return TranscriptState.FAILED
        transcriptState = TranscriptState.SENDING
        changed()
        val result = api.transcript(asBearer, id, email?.trim()?.takeIf { it.isNotEmpty() })
        noteAnswer(result)
        val next = when (result) {
            is ApiResult.Success -> TranscriptState.SENT
            is ApiResult.Failure -> if (result.error.status == 400 && result.error.needsEmail) TranscriptState.NEEDS_EMAIL else TranscriptState.FAILED
            is ApiResult.NetworkFailure -> TranscriptState.FAILED
        }
        if (id == conversationId) transcriptState = next
        changed()
        return next
    }

    // ═════════════════════════════════════════════════════════════════════
    // Language
    // ═════════════════════════════════════════════════════════════════════

    /** The app's language override; null follows the inbox setting, else the app's language. Never stored. */
    fun setLanguage(tag: String?) {
        val clean = tag?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == languageOverride) return
        languageOverride = clean
        // The config's texts are translated for the language it is asked with. A start
        // still running may have asked in the old language: the config is asked again after it.
        val starting = bootJob?.takeIf { it.isActive }
        if (phase == Phase.READY || starting != null) {
            scope.launch {
                starting?.join()
                if (phase == Phase.READY) refreshConfig()
            }
        }
        changed()
    }

    val language: String? get() = languageOverride

    /** The raw tag the config and the help center are asked with. */
    fun pageLanguage(): String? = languageOverride ?: appLanguage()?.trim()?.takeIf { it.isNotEmpty() }

    private fun effectiveLocale(): String = Locales.effective(config?.widgetLocale, pageLanguage())

    /** What the help center client needs from the session. */
    fun helpContext(): HelpContext? {
        val cfg = config ?: return null
        return HelpContext(
            locale = pageLanguage(),
            portalSlug = cfg.helpCenterPortalSlug,
            baseUrl = cfg.helpCenterBaseUrl,
            enabled = cfg.helpCenterEnabled,
            featured = cfg.featuredArticles,
            featuredLocale = cfg.featuredArticlesLocale,
            bearer = bearer?.takeUnless { placeholder },
        )
    }

    /** Stops everything for good: the session's jobs, timers and stream. */
    fun stop() {
        closeRealtime()
        rootJob.cancel()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Persistence
    // ═════════════════════════════════════════════════════════════════════

    /** Writes the visitor record; never in a cookieless inbox (null then). */
    private fun persistRecord(): Job? {
        if (cookieless) return null
        val snapshot = record
        return onDisk { store.write(snapshot) }
    }

    /**
     * Runs [step] on the store after every step asked for before it, in call
     * order, on the store queue of the process: a pending push removal is
     * written before the record that held its bearer is replaced or deleted,
     * writes never overtake each other, and a stop of this session cancels
     * none of them. A step touches the store only, never the engine's state.
     */
    private fun onDisk(step: suspend () -> Unit): Job = storeQueue.run(step)

    /**
     * Writes the thread for an instant first paint, half a second after the
     * last change. Never in a cookieless inbox, and never for a new visitor:
     * a dormant verified identity's cache must stay as it is.
     */
    private fun persistCache() {
        if (cacheWritePending || cookieless || cacheIdentity() == null) return
        cacheWritePending = true
        identityScope.launch {
            delay(CACHE_DEBOUNCE_MILLIS)
            cacheWritePending = false
            if (cookieless) return@launch
            val identity = cacheIdentity() ?: return@launch
            cache.write(
                CacheSnapshot(
                    identity = identity,
                    config = config,
                    conversationId = conversationId,
                    messages = messages.filterNot { it.isTypingPlaceholder }.takeLast(CacheSnapshot.MAX_MESSAGES),
                    conversations = listEntries,
                    segments = segments,
                    resolved = resolved,
                    continuesAfterResolve = continuesAfterResolve,
                ),
            )
        }
    }

    private fun cacheIdentity(): String? = when {
        bearer == null || placeholder -> null
        verifiedExternalId != null -> "$CACHE_VERIFIED$verifiedExternalId"
        else -> CACHE_ANONYMOUS
    }

    /** Paints the config and, for the same visitor only, the last thread before the network answers. */
    private suspend fun paintFromCache() {
        val snapshot = cache.read() ?: return
        if (config == null) config = snapshot.config
        changed()
        // A cookieless inbox paints nothing of a visitor.
        if (config?.cookielessEnabled == true) return
        val storedVerified = record.verifiedExternalId
        val expected = when {
            storedVerified != null -> if (pendingUser?.identifier?.trim() == storedVerified) "$CACHE_VERIFIED$storedVerified" else null
            record.bearer != null -> CACHE_ANONYMOUS
            else -> null
        }
        if (expected != null && snapshot.identity == expected) {
            conversationId = snapshot.conversationId
            messages = snapshot.messages.sortedWith(MessageRules.ORDER)
            listEntries = snapshot.conversations
            segments = snapshot.segments
            resolved = snapshot.resolved
            continuesAfterResolve = snapshot.continuesAfterResolve
        }
        changed()
    }

    // ═════════════════════════════════════════════════════════════════════
    // State
    // ═════════════════════════════════════════════════════════════════════

    private fun canWrite(): Boolean {
        val cfg = config ?: return !closed
        return !closed && !inEndState(cfg)
    }

    private fun inEndState(cfg: WidgetConfig): Boolean =
        !cfg.continuousChat && (endedLocally || (resolved && !continuesAfterResolve))

    /**
     * Publishes the state at most once per main loop turn. Realtime follows
     * the settled state of the turn (end state, closed, background).
     */
    private fun changed() {
        // Who the visitor is may have changed: calls for a push from other threads see it at once.
        publishKeys()
        if (flushPosted) return
        flushPosted = true
        scope.launch(postDispatcher) {
            flushPosted = false
            // The survey starts over whenever the conversation is no longer resolved.
            if (!resolved && csatState != CsatState.IDLE && csatState != CsatState.SENDING) csatState = CsatState.IDLE
            // Texts held for a step that went away by itself go out before anyone sees them stuck.
            releaseHeld()
            ensureRealtime()
            // A new bearer, token or language registers the phone again.
            push.sync()
            publishKeys()
            mutableState.value = buildState()
            // A conversation that came on screen with the app in front: its notification goes.
            val onScreen = conversationId?.takeIf { visible && appForeground }
            onScreenNow = onScreen
            if (onScreen != lastOnScreen) {
                lastOnScreen = onScreen
                if (onScreen != null) conversationOnScreen?.invoke(onScreen)
            }
            if (afterFlush.isNotEmpty()) {
                val waiting = afterFlush.toList()
                afterFlush.clear()
                waiting.forEach { it() }
            }
        }
    }

    private val afterFlush = ArrayList<() -> Unit>()

    /**
     * Runs [action] once the state about to be published is out (at once
     * when nothing is pending). The state flow drops a state equal to the
     * last one, so a screen that waited for [statePending] would otherwise
     * wait for nothing.
     */
    fun afterPendingState(action: () -> Unit) {
        if (flushPosted) afterFlush += action else action()
    }

    private fun buildState(): SessionState {
        val cfg = config
        return SessionState(
            phase = phase,
            config = cfg,
            identity = when {
                bearer == null || placeholder -> VisitorIdentity.AnonymousNew
                verifiedExternalId != null -> VisitorIdentity.Verified(verifiedExternalId.orEmpty())
                else -> VisitorIdentity.AnonymousKnown
            },
            conversations = mergedConversations(),
            thread = cfg?.let(::buildThread),
            unreadCount = unreadCount(),
            locale = effectiveLocale(),
            offline = offline,
            failure = failure.takeIf { phase == Phase.FAILED },
            visitorGeneration = visitorGeneration,
            visitorLoaded = visitorLoaded,
            identitySettled = identitySettled(),
        )
    }

    private fun buildThread(cfg: WidgetConfig): ThreadState {
        val ending = endingConversation != null && endingConversation == conversationId
        val endState = inEndState(cfg)
        val writable = !closed && !endState
        val thinking = botThinking && !closed
        val canSend = writable && !thinking
        return ThreadState(
            conversationId = conversationId,
            status = currentStatus(),
            messages = messages.filterNot { it.isTypingPlaceholder },
            pending = visiblePending(),
            uploads = uploads,
            segments = segments,
            botThinking = thinking,
            botTool = if (thinking) botTool else null,
            agentTyping = agentTyping && !closed,
            queuePosition = if (closed || endState) null else queuePosition,
            // A queue position wins over the handover line.
            waitingForTeam = waiting && queuePosition == null && !resolved && !endedLocally && !closed && !endState,
            resolved = resolved,
            closed = closed,
            continuesAfterResolve = continuesAfterResolve,
            endedLocally = endedLocally,
            showCsat = pollShowCsat,
            csatState = csatState,
            emailKnown = emailKnown,
            heldMessages = heldMessages,
            missingFields = missingFields,
            transcriptState = transcriptState,
            ending = ending,
            hasAgentReply = serverAgentReplied ?: messages.any {
                MessageRules.isHumanReply(it) && (it.conversationId == null || it.conversationId == conversationId)
            },
            gate = gate(),
            inEndState = endState,
            canSend = canSend,
            canAttach = canSend && cfg.featureFilePicker && conversationId != null &&
                uploads.none { it.phase == UploadPhase.UPLOADING },
            canEnd = !closed && cfg.featureEndConversation && conversationId != null && !resolved && !endedLocally && !ending,
            csatDue = !closed && cfg.csat.enabled && conversationId != null && resolved &&
                (pollShowCsat || csatState == CsatState.SUBMITTED),
            transcriptOffered = (if (cfg.continuousChat) resolved || cfg.persistentConversations else endState) &&
                cfg.featureTranscriptEmail && conversationId != null && !closed,
            fynnEndMessageId = fynnEndMessageId(cfg),
            choicesMessageId = choicesMessageId(cfg),
            botTypingRowId = if (thinking) {
                messages.lastOrNull { it.role != MessageRole.VISITOR && it.role != MessageRole.SYSTEM }
                    ?.takeIf { it.isTypingPlaceholder }?.id
            } else {
                null
            },
        )
    }

    /** The visitor wrote after the newest rows: buttons above a message on its way no longer show. */
    private fun hasLiveSend(): Boolean = visiblePending().any { it.state != PendingState.FAILED }

    private fun buttonsMayShow(cfg: WidgetConfig): Boolean =
        !closed && !inEndState(cfg) && !(botThinking && !closed) && !hasLiveSend()

    /**
     * The bot's newest reply whose choice buttons show: walking back over
     * activity lines, the first row that is not the bot's means none.
     */
    private fun choicesMessageId(cfg: WidgetConfig): String? {
        if (!buttonsMayShow(cfg)) return null
        for (m in messages.asReversed()) {
            if (m.role == MessageRole.SYSTEM || m.isTypingPlaceholder) continue
            if (m.role != MessageRole.BOT) return null
            if (m.attributes.buttons.isNotEmpty()) return m.id
        }
        return null
    }

    /**
     * Fynn's newest answer whose fixed buttons show: a handoff, join or
     * resolve line ends them, as does any row that is not the bot's.
     */
    private fun fynnEndMessageId(cfg: WidgetConfig): String? {
        if (!buttonsMayShow(cfg) || resolved) return null
        for (m in messages.asReversed()) {
            if (m.role == MessageRole.SYSTEM) {
                val kind = m.attributes.activity?.kind
                if (kind in FIXED_BUTTONS_END || (kind == "status_changed" && MessageRules.isResolveLine(m.content))) return null
                continue
            }
            if (m.role != MessageRole.BOT) return null
            if (m.isTypingPlaceholder) continue
            if (m.attributes.fynnEnd != null) return m.id
        }
        return null
    }

    private fun currentStatus(): ConversationStatus? {
        val id = conversationId ?: return null
        if (resolved) return ConversationStatus.RESOLVED
        val listed = listEntries.firstOrNull { it.id == id }?.status ?: segments.firstOrNull { it.id == id }?.status
        return listed?.takeIf { it != ConversationStatus.RESOLVED } ?: ConversationStatus.OPEN
    }

    /** The list with the thread's conversation merged in from the thread itself. */
    private fun mergedConversations(): List<ConversationSummary> {
        val id = conversationId
        if (continuous) return listOfNotNull(id?.let { threadSummary(it, null) })
        if (id == null) return listEntries.sortedByDescending { it.lastActivityAt }
        val out = listEntries.toMutableList()
        val index = out.indexOfFirst { it.id == id }
        val merged = threadSummary(id, out.getOrNull(index))
        if (merged != null) {
            if (index >= 0) out[index] = merged else out += merged
        }
        return out.sortedByDescending { it.lastActivityAt }
    }

    private fun threadSummary(id: String, listed: ConversationSummary?): ConversationSummary? {
        val last = messages.lastOrNull {
            it.role != MessageRole.SYSTEM && !it.isTyping && (it.hasText || it.attachments.isNotEmpty()) &&
                (continuous || it.conversationId == null || it.conversationId == id)
        }
        if (listed == null && last == null) return null
        return ConversationSummary(
            id = id,
            status = currentStatus() ?: listed?.status ?: ConversationStatus.OPEN,
            lastActivityAt = maxOf(listed?.lastActivityAt ?: 0L, last?.createdAt ?: 0L),
            lastMessage = last ?: listed?.lastMessage,
            unread = currentUnread(),
        )
    }

    /** Replies the visitor has not seen, across conversations. */
    private fun unreadCount(): Int = currentUnread() + listEntries.filter { it.id != conversationId }.sumOf { it.unread }

    private fun currentUnread(): Int {
        if (conversationId == null || (visible && appForeground)) return 0
        if (messages.isEmpty()) return restoredUnread ?: 0
        val cutoff = record.lastReadAt ?: messages.lastOrNull { it.role == MessageRole.VISITOR }?.createdAt ?: 0L
        return messages.count { MessageRules.countsAsUnread(it) && it.createdAt > cutoff }
    }

    /**
     * Runs [block] in the visitor's scope and waits for it, so a send or an
     * upload is not cancelled when the screen that asked for it goes away.
     * When the visitor changes meanwhile, the work stops and [fallback] is
     * the answer.
     */
    private suspend fun <T> inSession(fallback: T, block: suspend () -> T): T = withContext(mainDispatcher) {
        val work = identityScope.async { block() }
        try {
            work.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            fallback
        }
    }

    /**
     * [SessionState.offline], as on iOS: a call of the session that reached
     * no server at all (no network, a timeout) raises it; any answer clears
     * it, a refusal included. An answer that was not the route's (a captive
     * portal's page, a redirect) says nothing about the connection and
     * changes nothing. Every call of the engine reports here.
     */
    private fun noteAnswer(result: ApiResult<*>) {
        val none = when (result) {
            is ApiResult.NetworkFailure -> if (result.isNoConnection) true else return
            is ApiResult.Success, is ApiResult.Failure -> false
        }
        if (offline != none) {
            offline = none
            changed()
        }
    }

    private fun refusedForGood(error: ApiError): Boolean =
        error.status in 400..499 && error.status != 408 && error.status != 429

    /**
     * Only these refuse an identity: 400, 401 or 404 with the API's JSON. A
     * 403 page of a proxy, a 405 or 451, an HTML answer: no usable answer,
     * the stored identity stays and is tried again.
     */
    private fun isIdentityRefusal(error: ApiError): Boolean = error.status in IDENTITY_REFUSALS && error.hasJsonBody

    internal companion object {
        /** A moment that has not happened yet, on the monotonic clock. */
        const val NEVER: Long = Long.MIN_VALUE

        /** Visitor keys remembered at most; a session sees a handful of bearers. */
        private const val KEY_CACHE_SIZE: Int = 8

        /** The server refuses longer messages with 413 `message_too_long`. */
        const val MAX_MESSAGE_CHARS: Int = 20_000

        const val POLL_THINKING_MILLIS: Long = 800
        const val POLL_IDLE_MILLIS: Long = 5_000
        const val POLL_HIDDEN_MILLIS: Long = 30_000
        const val LIST_REFRESH_MILLIS: Long = 30_000

        /** The web's PENDING_REPLY_MAX_MS: the typing hold for a reply ends after this at the latest. */
        const val PENDING_REPLY_MAX_MILLIS: Long = 35_000
        const val QUEUE_PUSH_WINS_MILLIS: Long = 6_000
        const val AGENT_TYPING_MILLIS: Long = 6_000
        const val TYPING_THROTTLE_MILLIS: Long = 3_000
        const val TYPING_STOP_MILLIS: Long = 4_000
        const val CACHE_DEBOUNCE_MILLIS: Long = 500
        const val CONFIG_FRESH_MILLIS: Long = 60_000

        /** A refresh asked for within this time of the start of a running one is answered by it. */
        const val REFRESH_JOIN_MILLIS: Long = 1_000

        /** An upload's progress moves the state in steps of this much at most. */
        const val PROGRESS_STEP: Float = 0.02f

        /** A stored identity's verification without an answer is tried again after 15 s, doubling to 5 minutes. */
        const val VERIFY_RETRY_FIRST_MILLIS: Long = 15_000
        const val VERIFY_RETRY_MAX_MILLIS: Long = 5 * 60_000

        const val CODE_CONVERSATION_CLOSED: String = "conversation_closed"
        const val CODE_EMAIL_REQUIRED: String = "email_required"
        const val CODE_PRE_CHAT_REQUIRED: String = "pre_chat_required"

        private val IDENTITY_REFUSALS = setOf(400, 401, 404)

        const val CACHE_ANONYMOUS: String = "anonymous"
        const val CACHE_VERIFIED: String = "verified:"

        private val FIXED_BUTTONS_END = setOf("bot_handoff", "agent_joined", "resolved", "auto_resolved", "resolved_by_contact")
    }
}

/** A button the visitor taps in the thread. */
internal sealed class ChatButton {
    /** One of the bot's choice buttons: its label goes out as a normal message. */
    data class Choice(val label: String) : ChatButton()

    /** A start button under the greeting: its label is the first message. */
    data class Greeting(val label: String) : ChatButton()

    /** Fynn's team button: its label goes out with `fynnAction: "team"`. */
    data class FynnTeam(val label: String) : ChatButton()

    /** Fynn's "Frage geklärt" under [ThreadState.fynnEndMessageId]: resolves instead of sending a message. */
    object FynnResolved : ChatButton()
}

/** The session facts the help center needs. */
internal data class HelpContext(
    val locale: String?,
    val portalSlug: String?,
    val baseUrl: String?,
    val enabled: Boolean,
    val featured: List<HelpArticleSummary>,
    val featuredLocale: String?,
    val bearer: String?,
) {
    /** The bearer is a secret: never printed. */
    override fun toString(): String =
        "HelpContext(locale=$locale, portalSlug=$portalSlug, baseUrl=$baseUrl, enabled=$enabled, " +
            "featured=${featured.size} articles, featuredLocale=$featuredLocale, bearer=${if (bearer == null) "null" else "[redacted]"})"
}
