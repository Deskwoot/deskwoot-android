package com.deskwoot.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.store.StoreQueue
import com.deskwoot.sdk.store.StoredVisitors
import com.deskwoot.sdk.ui.DeskwootActivity
import com.deskwoot.sdk.ui.notify.HostOverlays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the app said about its user before [Deskwoot.setup], applied by setup
 * in the order it was said: a logout before setup counts for the session
 * that starts (the stored visitors were deleted at once, see
 * [Deskwoot.logout]), and only an identify after the last logout still counts.
 */
internal class EarlyIdentity {
    private var loggedOut = false
    private var user: DeskwootUser? = null

    fun identify(user: DeskwootUser) {
        this.user = user
    }

    fun logout() {
        loggedOut = true
        user = null
    }

    /** Replays onto the new session, the logout first: it came before any identify still kept. */
    fun applyTo(identify: (DeskwootUser) -> Unit, logout: () -> Unit) {
        if (loggedOut) logout()
        user?.let(identify)
        loggedOut = false
        user = null
    }
}

/**
 * Deskwoot's native visitor chat. Nothing runs until [setup]: no network,
 * no disk, no background work; only [logout] before setup deletes what the
 * SDK stored in an earlier launch.
 *
 * ```kotlin
 * Deskwoot.setup(application, "YOUR_WIDGET_TOKEN")
 * Deskwoot.identify(DeskwootUser(identifier = "u_42", identifierHash = hashFromYourBackend))
 * Deskwoot.present(activity)
 * ```
 *
 * The messenger is one Activity of the SDK's own ([DeskwootActivity]);
 * `present*` open it on the screen asked for and it closes with back or
 * its close button.
 *
 * Every method may be called from any thread, in any order and more than
 * once; the work happens on the main thread, and disk and network work
 * never does. Listeners are called on the main thread.
 */
public object Deskwoot {
    /** The SDK's version (deskwootSdkVersion in gradle.properties), as published and as sent to the server. */
    public const val SDK_VERSION: String = SDK_VERSION_NAME

    /** How much the SDK writes to Logcat (tag `Deskwoot`, lines prefixed `[Deskwoot]`). Secrets are never written. */
    public enum class LogLevel {
        /** Nothing (the default). */
        NONE,

        /** One line for each thing a developer must fix: an unknown widget token, a refused identity and why. */
        ERRORS,

        /** Also the SDK's own diagnosis. */
        DEBUG,
    }

    /** The developer log switch; off by default. */
    @Volatile
    @JvmStatic
    public var logLevel: LogLevel = LogLevel.NONE

    /** Light or dark for the messenger. */
    public enum class Theme {
        /** The app's own appearance: the night mode of the Activity that opens the messenger (the default). */
        SYSTEM,

        LIGHT,

        DARK,

        /** The inbox's own setting, as on the website. */
        INBOX,
    }

    @Volatile
    private var themeSetting: Theme = Theme.SYSTEM

    /** Light or dark; [Theme.SYSTEM] by default. Safe from any thread; an open messenger takes it at once. */
    @JvmStatic
    public var theme: Theme
        get() = themeSetting
        set(value) {
            themeSetting = value
            onMain { DeskwootActivity.themeChanged() }
        }

    /**
     * A small card at the bottom of the app when a reply arrives while the
     * messenger is closed (avatar, name, 2 lines; a tap opens that
     * conversation). On by default; it never shows over the messenger.
     */
    @Volatile
    @JvmStatic
    public var inAppNotificationsEnabled: Boolean = true

    @Volatile
    private var session: ChatSession? = null

    /**
     * The application context: from the SDK's FileProvider, which Android
     * creates in the app's default process before `Application.onCreate`, or
     * from [setup]. It lets a [logout] before setup delete what an earlier
     * launch stored.
     */
    @Volatile
    private var appContext: Context? = null

    /** A [setup] in a process that is not the app's default one: it waits until the messenger shows or a push comes. */
    @Volatile
    private var idleSetup: Pair<Context, String>? = null

    /** What the app said about its user before setup; touched on the main thread only. */
    private val beforeSetup = EarlyIdentity()

    @Volatile
    private var languageSetting: String? = null

    /** The app's FCM token as [setPushToken] last gave it; touched on the main thread only, never stored. */
    private var pushTokenSetting: String? = null

    private val unreadListeners = CopyOnWriteArrayList<UnreadCountListener>()
    private var unreadWatch: Job? = null

    /** The count the unread listeners heard last (0 before setup, as [unreadCount] reads then); main thread only. */
    private var heardUnread = 0
    private var foregroundTracker: ForegroundTracker? = null

    /** Read by [handlePush] on any thread. */
    @Volatile
    private var overlays: HostOverlays? = null
    private var launcherWanted = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val facadeScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /**
     * Starts the chat of the Live Chat inbox [widgetToken]: the config, the
     * visitor's identity and conversation load in the background, so the
     * unread count is right before the chat ever opens. Call it as early as
     * you like, for example in `Application.onCreate`; it costs nothing on
     * the main thread. Right after it, at every start, call [identify] when
     * the app knows its user, or [logout] when the app finds that its user's
     * session ended while it was closed: a user stored in an earlier launch
     * stays dormant until one of the 2 comes. A second call with the same
     * token does nothing; one with another token replaces the session and
     * closes an open messenger (the next `present*` opens the new inbox),
     * and the unread listeners hear the new inbox's count. In a process of
     * the app that is not its default one (a service's own process, for
     * example) nothing starts (no network, no writes to the device) until
     * that process shows the messenger or hands a push to [handlePush].
     */
    @JvmStatic
    public fun setup(context: Context, widgetToken: String) {
        val token = widgetToken.trim()
        if (token.isEmpty()) {
            AndroidLogger.error("Deskwoot.setup was called with an empty widget token; nothing starts.")
            return
        }
        val app = context.applicationContext
        onMain {
            if (session == null && !AppProcess.isDefault(app)) {
                idleSetup = app to token
                AndroidLogger.debug("Deskwoot.setup in another process of the app: the chat starts when this process shows it or handles a push")
                return@onMain
            }
            setupOnMain(app, token)
        }
    }

    /** The SDK's FileProvider was created with the app's process: its application context, before any setup. */
    @JvmSynthetic
    internal fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext ?: context
    }

    /**
     * The session, starting an idle [setup] of another process of the app
     * first: that process shows the messenger or handles a push. Any
     * thread; another one waits a moment for the main thread to start it.
     */
    @JvmSynthetic
    internal fun activeSession(): ChatSession? {
        session?.let { return it }
        if (idleSetup == null) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return wake()
        val started = CountDownLatch(1)
        onMain {
            wake()
            started.countDown()
        }
        started.await(WAKE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        return session
    }

    /** Main thread. */
    private fun wake(): ChatSession? {
        session?.let { return it }
        val (app, token) = idleSetup ?: return null
        idleSetup = null
        setupOnMain(app, token)
        return session
    }

    /**
     * The messenger's own setup, when Android made it before the app called
     * [setup] (a notification tapped after the process ended, the messenger
     * restored after a process death): on the main thread, at once, so the
     * messenger reads the session it just started. Calls the app made from
     * other threads before it still run after it, in their order.
     */
    @JvmSynthetic
    internal fun setupNow(context: Context, widgetToken: String): ChatSession? {
        val token = widgetToken.trim().takeIf { it.isNotEmpty() } ?: return null
        setupOnMain(context.applicationContext, token)
        return session
    }

    /**
     * The app's signed in user, proven by [DeskwootUser.identifierHash] from
     * the app's backend. Call it at every start as soon as the app knows its
     * user (in `Application.onCreate` right after [setup] when the app keeps
     * its user and the hash: a tap on a notification may start the process
     * without any Activity), and again whenever the user signs in. Until it
     * or [logout] comes, a user stored in an earlier launch stays dormant:
     * the chat runs as a new visitor meanwhile. This call takes a stored
     * identity of the same user back at once; it is never used for anybody
     * else. Another user replaces the one in use, whose data is deleted
     * first. A user whose identifier is not 1 to 128 characters or whose
     * hash is not 64 hex characters is ignored.
     */
    @JvmStatic
    public fun identify(user: DeskwootUser) {
        if (!user.isValid) {
            AndroidLogger.error("Deskwoot.identify ignored: the identifier must be 1 to 128 characters and the identifierHash 64 hex characters.")
            return
        }
        onMain {
            val current = session
            if (current != null) current.identify(user) else beforeSetup.identify(user)
        }
    }

    /**
     * The user signed out: everything the SDK keeps about the visitor goes,
     * of every inbox the app set up, also before [setup] and in a launch that
     * never calls it, and the next visitor starts new. Call it when the user
     * signs out, and also at a start where the app finds that its user's
     * session ended while it was closed: a user stored in an earlier launch
     * stays dormant until [identify] or this call. Not at a start without
     * such a reason: it also deletes the chat of a visitor who never signed
     * in. When a push registration was sent for the visitor, only what takes
     * the phone off them stays (see the README) until the server confirmed
     * it. [language] is the app's setting and stays. Called on the main
     * thread, the stored visitor is deleted when this returns (it waits for
     * the disk at most 200 ms; the disk work itself never runs on the main
     * thread).
     */
    @JvmStatic
    public fun logout() {
        onMain {
            com.deskwoot.sdk.ui.UiMemory.clear()
            val current = session
            val deleted = ArrayList<Job>(2)
            // The stored visitors of the app's other inboxes are the same person's: they go too. Another
            // process of the app that was never started leaves the files to the default one.
            val app = if (current != null) appContext else appContext?.takeIf { AppProcess.isDefault(it) }
            if (app != null) deleted += forgetStoredVisitors(app, except = current?.widgetToken)
            if (current != null) deleted += current.logout() else beforeSetup.logout()
            // The pictures the session's end deletes after these steps are not waited for.
            awaitBriefly(deleted)
        }
    }

    /**
     * Every stored visitor of every inbox except [except] (which a running
     * session wipes itself) ends, on the store queue: a removal record where
     * a push registration was sent, then the records, the thread caches and
     * the notifications; without a session also the pictures and file copies
     * of the chat. Returns the step that deletes the records.
     */
    private fun forgetStoredVisitors(app: Context, except: String?): Job {
        val now = System.currentTimeMillis()
        val deleted = StoreQueue.shared.run {
            val inboxes = StoredVisitors.forgetAll(StoredVisitors.folder(app), except, now)
            inboxes.forEach { com.deskwoot.sdk.ui.notify.PushNotifications.cancelAll(app, it) }
        }
        if (except == null) StoreQueue.shared.run { com.deskwoot.sdk.ui.files.Files.purge(app) }
        return deleted
    }

    /** Waits for [jobs], at most [LOGOUT_WAIT_MILLIS]: they work on files only and never wait for the main thread. */
    private fun awaitBriefly(jobs: List<Job>) {
        val done = CountDownLatch(jobs.size)
        jobs.forEach { job -> job.invokeOnCompletion { done.countDown() } }
        done.await(LOGOUT_WAIT_MILLIS, TimeUnit.MILLISECONDS)
    }

    /**
     * A BCP 47 tag (for example `de`, `pt-BR`) in place of the app's
     * language; null (the default) follows the app. The inbox's fixed
     * language always wins, also over this. Without one the chat speaks
     * this language, or with null the app's: the phone's language, or the
     * app's own when the user set one for it (`configuration.locales[0]` of
     * the app's resources). A language the SDK does not speak falls back to
     * English. Kept in memory for the process, never stored; [logout]
     * leaves it as it is.
     */
    @JvmStatic
    public var language: String?
        get() = languageSetting
        set(value) {
            languageSetting = value
            onMain { session?.setLanguage(value) }
        }

    /**
     * The app's Firebase Cloud Messaging registration token, so replies
     * reach the visitor as push notifications while the app is closed. Call
     * it from `FirebaseMessagingService.onNewToken` and once at every start
     * (`FirebaseMessaging.getInstance().token`): the SDK keeps it in memory
     * only. It may come before [setup]; the phone registers as soon as the
     * visitor is someone the server knows (a returning visitor at once, a
     * new one after their first message). Null or blank forgets it and takes
     * the phone off the visitor. The SDK has no Firebase dependency and asks
     * for no permission.
     */
    @JvmStatic
    public fun setPushToken(token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        onMain {
            pushTokenSetting = clean
            session?.setPushToken(clean)
        }
    }

    /**
     * Opens the messenger on its start page. Works right after [setup], also
     * before the session has loaded: it paints from the disk cache or waits
     * for it by itself. Pass the Activity you call it from when you can; it
     * decides light or dark ([Theme.SYSTEM]) and keeps the messenger in the
     * app's task.
     */
    @JvmStatic
    public fun present(context: Context) {
        open(context, DeskwootActivity.Entry.Start)
    }

    /** Opens the visitor's newest running conversation, or a new one when there is none. */
    @JvmStatic
    public fun presentConversation(context: Context) {
        open(context, DeskwootActivity.Entry.Conversation)
    }

    /**
     * Opens a new conversation (through the inbox's steps before a chat) with
     * [text] in the message field, not sent: the visitor reads it and sends it.
     */
    @JvmStatic
    @JvmOverloads
    public fun presentNewConversation(context: Context, text: String? = null) {
        open(context, DeskwootActivity.Entry.New(text?.takeIf { it.isNotBlank() }))
    }

    /** Opens the help center (the start page when the inbox has none). */
    @JvmStatic
    public fun presentHelpCenter(context: Context) {
        open(context, DeskwootActivity.Entry.Help)
    }

    /** Closes the messenger if it is open. */
    @JvmStatic
    public fun dismiss() {
        onMain { DeskwootActivity.openActivity?.get()?.finish() }
    }

    /**
     * Shows or hides the round chat button above the app's content (the web
     * widget's launcher: the brand color, the inbox's side, the unread
     * badge). Hidden by default. A tap opens the messenger.
     */
    @JvmStatic
    public fun setLauncherVisible(visible: Boolean) {
        onMain {
            launcherWanted = visible
            overlays?.launcherVisible = visible
        }
    }

    /** Replies the visitor has not seen yet, across conversations; 0 before setup. Read it for the first value. */
    @JvmStatic
    public val unreadCount: Int
        get() = session?.state?.value?.unreadCount ?: 0

    /**
     * Told whenever [unreadCount] changes (changes only, not the value at the
     * time the listener is added), also when [setup] with another widget
     * token brings another count.
     */
    @JvmStatic
    public fun addUnreadCountListener(listener: UnreadCountListener) {
        unreadListeners.addIfAbsent(listener)
    }

    @JvmStatic
    public fun removeUnreadCountListener(listener: UnreadCountListener) {
        unreadListeners.remove(listener)
    }

    // ── Push notifications, and the launcher and the card over the app ───

    /**
     * Whether an FCM data message (`RemoteMessage.data`) is a Deskwoot reply
     * for the inbox given to [setup]. The app's own pushes, those of another
     * inbox and every push before setup answer false. Any thread.
     */
    @JvmStatic
    public fun isDeskwootPush(data: Map<String, String>): Boolean =
        com.deskwoot.sdk.ui.notify.PushNotifications.parse(data, activeSession()) != null

    /**
     * Shows a Deskwoot push as a notification; call it from
     * `FirebaseMessagingService.onMessageReceived` (any thread). The
     * notification is posted before this returns. The SDK's own channel, the
     * app's icon unless [setNotificationIcon] names one, a tap opens that
     * conversation; none while the visitor reads that conversation, and none
     * for a visitor this device forgot (logged out, another user). True when
     * the push was Deskwoot's, also when nothing showed (that visitor, no
     * POST_NOTIFICATIONS grant, notifications off; the SDK never asks for
     * the permission). False when it is the app's own to handle.
     */
    @JvmStatic
    public fun handlePush(context: Context, data: Map<String, String>): Boolean =
        com.deskwoot.sdk.ui.notify.PushNotifications.handle(context, data, activeSession()) { messageId ->
            overlays?.cardShowed(messageId) == true
        }

    /**
     * The small icon of the SDK's notifications, a drawable of the app
     * (Android draws it as a white silhouette). The app's own icon by
     * default. Any thread.
     */
    @JvmStatic
    public fun setNotificationIcon(@androidx.annotation.DrawableRes resId: Int) {
        com.deskwoot.sdk.ui.notify.PushNotifications.icon = resId
    }

    /**
     * Lifts the launcher by [dp] above its place (16 over the navigation
     * bar), for example over the app's own tab bar; the in app card moves up
     * with it. 0 by default. The launcher also steps aside by itself while
     * the app shows a dialog, a sheet or a menu over its screen.
     */
    @JvmStatic
    public fun setLauncherBottomPadding(dp: Int) {
        HostOverlays.launcherBottomPadding = dp.coerceAtLeast(0)
        onMain { overlays?.relayout() }
    }

    /**
     * Neither the launcher nor the in app card ever shows over
     * [activityClass] or its subclasses (a checkout, a video player, a
     * screen of another SDK). Translucent and floating activities (dialogs,
     * sheets) never get them anyway. Any thread, also before [setup].
     */
    @JvmStatic
    public fun excludeFromOverlays(activityClass: Class<out Activity>) {
        HostOverlays.excluded.add(activityClass)
        onMain { overlays?.recheck() }
    }

    /** The running session, for the UI layer of the SDK; the messenger starts an idle setup of its process. Main thread. */
    @get:JvmSynthetic
    internal val currentSession: ChatSession? get() = session ?: wake()

    /** How [setup] makes its session; the UI tests put a scripted one here. */
    @Volatile
    @get:JvmSynthetic
    @set:JvmSynthetic
    internal var makeSession: (Context, String, String?) -> ChatSession = { context, token, language -> ChatSession.create(context, token, language) }

    /** The in app card's tap: that very conversation. */
    @JvmSynthetic
    internal fun presentConversationFromCard(context: Context, conversationId: String) {
        open(context, DeskwootActivity.Entry.Open(conversationId))
    }

    private fun open(context: Context, entry: DeskwootActivity.Entry) {
        onMain {
            val current = session ?: wake()
            if (current == null) {
                AndroidLogger.error("Deskwoot.present was called before Deskwoot.setup; nothing opens.")
                return@onMain
            }
            // Every present loads the config again, so a change of the look or the language reaches
            // the messenger, also one that is already open.
            current.refresh()
            val activity = context.findActivity() ?: overlays?.currentActivity
            DeskwootActivity.presenter = activity?.let { WeakReference(it) }
            val from = activity ?: context.applicationContext
            // Nothing is switched off before the messenger really shows (the overlays follow its onResume),
            // so a refused start leaves the app as it was; it never throws into the app.
            try {
                from.startActivity(DeskwootActivity.intent(from, entry))
            } catch (e: RuntimeException) {
                AndroidLogger.error("Deskwoot.present could not open the messenger: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun setupOnMain(app: Context, token: String) {
        com.deskwoot.sdk.ui.files.Files.cleanAtStart(app)
        val current = session
        if (current != null && current.widgetToken == token) {
            current.start()
            return
        }
        if (current != null) {
            // Another inbox: an open messenger of the old one closes (the next present opens the new
            // inbox), and nothing typed for the old one shows in the new.
            DeskwootActivity.sessionReplaced()
            com.deskwoot.sdk.ui.UiMemory.clear()
        }
        current?.stop()
        unreadWatch?.cancel()
        appContext = app
        idleSetup = null
        val created = makeSession(app, token, languageSetting)
        session = created
        // A push token given before setup registers once the visitor is known.
        created.setPushToken(pushTokenSetting)
        // Logout, another user or a refused identity: nothing of the old visitor stays on the phone,
        // their notifications included.
        created.onVisitorWiped { shown ->
            com.deskwoot.sdk.ui.notify.PushNotifications.cancelAll(app, token)
            forgetVisitorFiles(app, shown)
        }
        beforeSetup.applyTo(identify = created::identify, logout = created::logout)
        installForegroundTracker(app)
        installOverlays(app, created)
        // The listeners hear every change of what unreadCount reads, also the one a new widget token
        // brings: the count they heard last is compared, not the new session's first value.
        unreadWatch = facadeScope.launch {
            created.state.map { it.unreadCount }.distinctUntilChanged().collect { count ->
                if (count != heardUnread) {
                    heardUnread = count
                    for (listener in unreadListeners) listener.onUnreadCountChanged(count)
                }
            }
        }
        created.start()
    }

    /**
     * Every file the SDK wrote for the visitor (pictures, file copies). When
     * the visitor the UI shows ended ([shown]) also the drafts, the help search
     * and votes and the pictures in memory, at once and before the app's next
     * call; an open messenger starts over and a card with their reply goes.
     * A stored visitor that was never shown leaves the UI as it is.
     */
    private fun forgetVisitorFiles(app: Context, shown: Boolean) {
        if (shown) {
            val forget = {
                com.deskwoot.sdk.ui.UiMemory.clear()
                com.deskwoot.sdk.ui.image.ImageLoader.get(app).clearMemory()
                overlays?.visitorWiped()
                DeskwootActivity.visitorWiped()
            }
            // The session ends a visitor on the main thread: the UI is reset right there, inside the app's
            // call, so the next call (presentNewConversation right after identify) is handled after it.
            if (Looper.myLooper() == Looper.getMainLooper()) forget() else mainHandler.post { forget() }
        }
        // On the store queue, after the session's own steps of this end: a session stopped or replaced right
        // after does not cancel it, and the next session starts only after it.
        StoreQueue.shared.run { com.deskwoot.sdk.ui.files.Files.purge(app) }
    }

    private fun installOverlays(app: Context, created: ChatSession) {
        val application = app as? Application ?: return
        val current = overlays ?: HostOverlays { session }.also {
            application.registerActivityLifecycleCallbacks(it)
            overlays = it
        }
        current.watch(created)
        current.launcherVisible = launcherWanted
    }

    private fun installForegroundTracker(app: Context) {
        if (foregroundTracker == null) {
            val application = app as? Application ?: return
            val tracker = ForegroundTracker { inFront -> session?.setForeground(inFront) }
            application.registerActivityLifecycleCallbacks(tracker)
            foregroundTracker = tracker
        }
        // Whether the app shows something right now asks a system service: not on the main thread.
        facadeScope.launch {
            val inFront = withContext(Dispatchers.IO) { ForegroundTracker.isForegroundNow() }
            if (foregroundTracker?.heardAny != true) session?.setForeground(inFront)
        }
    }

    /**
     * Runs [block] on the main thread in call order: a call on the main
     * thread runs at once only when no call from another thread waits in the
     * queue, so an identify and a logout never overtake each other.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper() && queued.get() == 0) {
            block()
            return
        }
        queued.incrementAndGet()
        mainHandler.post {
            queued.decrementAndGet()
            block()
        }
    }

    private val queued = AtomicInteger(0)

    /** How long [logout] waits at most for the stored visitor to be deleted. */
    private const val LOGOUT_WAIT_MILLIS = 200L

    /** How long a push handed over on a worker thread waits at most for an idle setup to start on the main thread. */
    private const val WAKE_WAIT_MILLIS = 2_000L
}
