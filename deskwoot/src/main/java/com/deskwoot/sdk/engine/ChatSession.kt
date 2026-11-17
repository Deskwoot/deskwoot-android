package com.deskwoot.sdk.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import androidx.annotation.MainThread
import com.deskwoot.sdk.AndroidLogger
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.DeskwootInternal
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.api.FileFetcher
import com.deskwoot.sdk.api.UrlConnectionHttpClient
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.help.HelpCenterClient
import com.deskwoot.sdk.model.FieldProblem
import com.deskwoot.sdk.model.FynnAction
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.ReplyQuote
import com.deskwoot.sdk.model.ResolveResult
import com.deskwoot.sdk.model.SendOutcome
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.model.UploadErrorCode
import com.deskwoot.sdk.realtime.AblySseClient
import com.deskwoot.sdk.realtime.UrlConnectionEventStreamConnector
import com.deskwoot.sdk.store.FileDiskCache
import com.deskwoot.sdk.store.FileVisitorStore
import com.deskwoot.sdk.store.StoredVisitors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The chat of one widget token, alive for the whole process: the layers
 * of the SDK wired together (WidgetApi over the HttpClient, the realtime
 * client, the visitor store, the disk cache, the engine and the help
 * center client). The UI reads [state] and calls the actions; every action
 * is safe in any state and is called on the main thread. Sending, a button,
 * the form and the email step answer at once and deliver in the background;
 * the suspending actions switch to the main thread themselves
 * and run their network work off it.
 */
@MainThread
internal class ChatSession(
    val widgetToken: String,
    private val engine: ConversationEngine,
    val help: HelpCenterClient,
    private val api: WidgetApi,
    private val files: FileFetcher,
) {
    val state: StateFlow<SessionState> get() = engine.state

    /** [state] is about to change within this main loop turn (see [ConversationEngine.statePending]). */
    val statePending: Boolean get() = engine.statePending

    /** [action] runs once the pending state is published (see [ConversationEngine.afterPendingState]). */
    fun afterPendingState(action: () -> Unit) = engine.afterPendingState(action)

    val language: String? get() = engine.language

    fun start() = engine.start()

    fun refresh() = engine.refresh()

    /** Ends the session for good (another widget token took over): every job, timer and stream stops. */
    fun stop() = engine.stop()

    fun setVisible(threadOnScreen: Boolean) = engine.setVisible(threadOnScreen)

    fun setForeground(inFront: Boolean) = engine.setForeground(inFront)

    fun openConversation(id: String) = engine.openConversation(id)

    /**
     * Whether conversation [id] (the in app card's) is this visitor's: true
     * once confirmed, false once the visitor settled and it is not theirs,
     * null while not known yet, also while a stored signed in user waits for
     * the app's identify (see [ConversationEngine.ownsConversation]).
     */
    fun ownsConversation(id: String): Boolean? = engine.ownsConversation(id)

    /**
     * Opens a notification's conversation whose visitor key names this
     * visitor; the server's first answer still decides ([tapRefused]).
     */
    fun openTapped(id: String) = engine.openConversation(id, tapped = true)

    /** The server showed nothing of the tapped conversation [id]: it is not this visitor's. */
    fun tapRefused(id: String): Boolean = engine.tapRefused(id)

    /**
     * Who the visitor is has settled: no identify or verification on its
     * way, no stored signed in user waiting for the app to name them
     * ([SessionState.identitySettled] publishes it).
     */
    val identitySettled: Boolean get() = engine.identitySettled()

    fun startNewConversation() = engine.startNewConversation()

    fun send(text: String, action: FynnAction? = null, replyTo: ReplyQuote? = null, article: com.deskwoot.sdk.model.HelpArticleRef? = null): SendOutcome =
        engine.send(text, action, replyTo, article)

    fun retry(pendingUuid: String) = engine.retry(pendingUuid)

    fun discard(pendingUuid: String) = engine.discard(pendingUuid)

    /** [id]: the UI's own name for the upload (kept lower case), its row key from the pick to the stored message. */
    suspend fun upload(source: UploadSource, fileName: String, mimeType: String, id: String? = null): UploadErrorCode? =
        engine.upload(source, fileName, mimeType, id)

    fun dismissUpload(id: String) = engine.dismissUpload(id)

    fun setTyping(isTyping: Boolean) = engine.setTyping(isTyping)

    fun pressButton(button: ChatButton): SendOutcome = engine.pressButton(button)

    fun submitPreChat(values: Map<String, String>): Map<String, FieldProblem> = engine.submitPreChat(values)

    fun submitEmail(email: String): FieldProblem? = engine.submitEmail(email)

    fun cancelGate() = engine.cancelGate()

    /** The step before a new message, read now (see [ConversationEngine.gateBeforeChat]). */
    fun gateBeforeChat(): Gate = engine.gateBeforeChat()

    /** A link of the server (a signed file path) on the API origin. */
    suspend fun absoluteUrl(link: String): String = api.absoluteUrl(link)

    /** A file of the chat through the SDK's HTTP stack; null on any failure. */
    suspend fun fetchFile(link: String, maxBytes: Int): ByteArray? = files.fetch(absoluteUrl(link), maxBytes)

    /** The same file streamed into [target] (never held in memory); false on any failure. */
    suspend fun fetchFileTo(link: String, maxBytes: Int, target: File): Boolean = files.fetchTo(absoluteUrl(link), maxBytes, target)

    suspend fun endConversation(): Boolean = engine.endConversation()

    suspend fun markResolved(): ResolveResult = engine.markResolved()

    suspend fun submitCsat(rating: Int, comment: String?): Boolean = engine.submitCsat(rating, comment)

    suspend fun requestTranscript(email: String? = null): TranscriptState = engine.requestTranscript(email)

    fun identify(user: DeskwootUser) = engine.identify(user)

    /** Returns the step that deletes the stored visitor; it runs to its end also when the session stops right after. */
    fun logout(): kotlinx.coroutines.Job = engine.logout()

    /**
     * [listener] runs at once whenever a visitor is forgotten, so the UI can
     * drop what it keeps of them. `true`: the visitor the UI shows ended (the
     * UI starts over); `false`: only a stored visitor it never showed did
     * (only the files on the disk go). See [ConversationEngine.visitorWiped].
     */
    fun onVisitorWiped(listener: ((shown: Boolean) -> Unit)?) {
        engine.visitorWiped = listener
    }

    fun setLanguage(tag: String?) = engine.setLanguage(tag)

    /** The app's FCM token, in memory only; null or blank forgets it and takes the phone off the visitor. */
    fun setPushToken(token: String?) = engine.setPushToken(token)

    /** The thread of this conversation is on screen with the app in front: its replies need no notification. Any thread. */
    fun isOnScreen(conversationId: String): Boolean = engine.isOnScreen(conversationId)

    /** [listener] hears, on the main thread, every conversation that comes on screen with the app in front. */
    fun onConversationOnScreen(listener: ((String) -> Unit)?) {
        engine.conversationOnScreen = listener
    }

    /** The key of the visitor the session runs as ([VisitorKey]); null for a new visitor nobody knows yet. Any thread. */
    val visitorKey: String? get() = engine.activeVisitorKey()

    /**
     * Whose push a visitor key names: this session's visitor, the stored
     * dormant user, or a visitor this device forgot. Any thread; on a worker
     * thread of a process that has not read the store yet it reads it first
     * (see [ConversationEngine.pushVisitor]).
     */
    fun pushVisitor(key: String?): PushVisitor = engine.pushVisitor(key)

    /** The visitor's store was read, so [pushVisitor] knows every visitor of this device. Any thread. */
    val visitorKnown: Boolean get() = engine.visitorKnown

    /** Runs [action] on the main thread once the visitor's store was read (at once when it was). Main thread. */
    fun whenVisitorKnown(action: () -> Unit) = engine.whenVisitorKnown(action)

    /**
     * A push for a visitor this device forgot named the visitor key [key]: a
     * pending removal of that bearer goes out now (once a push token is
     * known). Any thread; reads the removal list first when this process has not.
     */
    fun removeForgotten(key: String) = engine.removeForgotten(key)

    /**
     * Whether what the messenger saved for the visitor [savedKey] (null: a
     * visitor nobody knew) may come back after a process death: true, false,
     * or null while not known yet (see [ConversationEngine.ownsSavedState]). Main thread.
     */
    fun ownsSavedState(savedKey: String?): Boolean? = engine.ownsSavedState(savedKey)

    internal companion object {
        /**
         * The production wiring. Holds the application context only, never an
         * Activity, and does no disk, network or system service call: the
         * store folder and the manifest's base URL are read on the IO
         * dispatcher the first time they are needed.
         */
        fun create(context: Context, widgetToken: String, language: String?): ChatSession {
            val app = context.applicationContext
            val io = Dispatchers.IO
            val userAgent = userAgent(app)
            val http = UrlConnectionHttpClient(userAgent, io)
            // Answers are decoded off the main thread.
            val api = WidgetApi(http, BaseUrlSource(app, io)::get, widgetToken, Dispatchers.Default)
            // Resolved on the IO dispatcher by the stores: the getter creates the folder.
            val directory = { StoredVisitors.folder(app) }
            val rootJob = SupervisorJob()
            val errors = CoroutineExceptionHandler { _, error -> AndroidLogger.debug("Unexpected error in the realtime stream: $error") }
            val realtime = AblySseClient(
                http = http,
                connector = UrlConnectionEventStreamConnector(userAgent, io),
                scope = CoroutineScope(rootJob + Dispatchers.Main.immediate + errors),
                clock = DeviceClock,
                logger = AndroidLogger,
            )
            val engine = ConversationEngine(
                api = api,
                realtime = realtime,
                store = FileVisitorStore(directory, widgetToken, io),
                cache = FileDiskCache(directory, widgetToken, io),
                clock = DeviceClock,
                logger = AndroidLogger,
                mainDispatcher = Dispatchers.Main.immediate,
                postDispatcher = Dispatchers.Main,
                appLanguage = { appLanguageTag(app) },
                uploadReader = ContentUploadReader(app, io),
                appId = app.packageName,
                sdkVersion = Deskwoot.SDK_VERSION,
                initialLanguage = language,
                appDebuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                // The facade reports the real state right after setup.
                initialForeground = false,
                parentJob = rootJob,
                onMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            )
            return ChatSession(widgetToken, engine, HelpCenterClient(api, engine::helpContext), api, http)
        }

        /**
         * `DeskwootSDK/<version> (Android <release>; <app id>)`. Words that
         * make the help center treat a client as a crawler are kept out of
         * the app id.
         */
        fun userAgent(context: Context): String {
            val appId = context.packageName.replace(CRAWLER_WORDS, "_")
            return "DeskwootSDK/${Deskwoot.SDK_VERSION} (Android ${Build.VERSION.RELEASE}; $appId)"
        }

        /** The app's language: its own per app setting where the system has one, else the device's. */
        fun appLanguageTag(context: Context): String? {
            val configuration = context.resources.configuration
            val locale = if (Build.VERSION.SDK_INT >= 24) {
                configuration.locales.get(0)
            } else {
                @Suppress("DEPRECATION")
                configuration.locale
            }
            return locale?.toLanguageTag()?.takeIf { it.isNotEmpty() && it != "und" }
        }

        private val CRAWLER_WORDS =
            Regex("(?<!cu)bot|crawl|spider|slurp|facebookexternalhit|embedly|preview|inspectiontool|lighthouse", RegexOption.IGNORE_CASE)
    }

    /** The API origin: the manifest's `com.deskwoot.sdk.BASE_URL` (read once, off the main thread), else [DeskwootInternal.baseUrl]. */
    private class BaseUrlSource(private val app: Context, private val io: CoroutineDispatcher) {
        @Volatile
        private var resolved: String? = null

        suspend fun get(): String = resolved ?: withContext(io) {
            DeskwootInternal.manifestBaseUrl(app) ?: DeskwootInternal.baseUrl
        }.also { resolved = it }
    }
}
