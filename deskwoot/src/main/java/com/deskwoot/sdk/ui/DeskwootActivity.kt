package com.deskwoot.sdk.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.deskwoot.sdk.AndroidLogger
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.engine.PushVisitor
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.MessageRole
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.SessionFailure
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.ui.conversations.ConversationsScreen
import com.deskwoot.sdk.ui.design.DirektHelpScreen
import com.deskwoot.sdk.ui.design.EmpfangHomeScreen
import com.deskwoot.sdk.ui.design.SheetScreen
import com.deskwoot.sdk.ui.files.AttachSource
import com.deskwoot.sdk.ui.files.Files
import com.deskwoot.sdk.ui.files.Picked
import com.deskwoot.sdk.ui.gate.GateScreen
import com.deskwoot.sdk.ui.help.ArticleScreen
import com.deskwoot.sdk.ui.help.CollectionScreen
import com.deskwoot.sdk.ui.home.HomeScreen
import com.deskwoot.sdk.ui.image.ImageLoader
import com.deskwoot.sdk.ui.image.ImageViewer
import com.deskwoot.sdk.ui.notify.PushNotifications
import com.deskwoot.sdk.ui.text.Strings
import com.deskwoot.sdk.ui.text.UiLanguage
import com.deskwoot.sdk.ui.theme.UiColors
import com.deskwoot.sdk.ui.thread.ThreadScreen
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The whole messenger in one Activity: not exported, in the app's own task
 * (a notification tapped while the app had no task puts the app's launch
 * Activity under it), no orientation lock, edge to edge with the insets applied by
 * hand (the keyboard too), the predictive back gesture through the
 * messenger's screens. Open it with `Deskwoot.present*`; it is public only
 * because the system creates it.
 */
public class DeskwootActivity : ComponentActivity() {
    private var session: ChatSession? = null
    private var stateJob: Job? = null
    private lateinit var root: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var messenger: Messenger
    /** The screens' work (help center calls, results of the session's actions); an error there never reaches the app. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> AndroidLogger.debug("Unexpected error in the messenger: $error") },
    )
    private var look: Look? = null
    private var placeholder: View? = null
    private var placeholderClose: View? = null
    private var viewer: ImageViewer? = null
    /**
     * How the messenger was asked to open, until the session can answer it.
     * One at a time: a newer `present*` or notification tap replaces it, the
     * end of the visitor drops it, a recreate keeps it.
     */
    private var pendingEntry: Entry? = null

    /** A tapped conversation on screen whose rows the server has not shown yet: a refusal ends on the start page. */
    private var unconfirmedTap: String? = null

    /** Looks at a waiting tap again: its wait for the app's identify ran out. */
    private val tapCheck = Runnable { if (!isFinishing) session?.state?.value?.let(::onState) }
    private var restoredRoutes: List<Route>? = null
    private var restoredStates: Bundle? = null

    /** The design [restoredRoutes] are routes of. */
    private var restoredDesign: String? = null

    /**
     * The visitor key the last instance saved its state for ([NO_VISITOR]: a
     * visitor nobody knew), while it is not decided yet whether that state
     * comes back ([restoreChecked]); null when there is nothing to decide.
     */
    private var restoredVisitor: String? = null

    /** The drafts and the help search the last instance saved: they come back with its screens only. */
    private var restoredMemory: Bundle? = null

    /**
     * The lowest visitor generation a state may have to be drawn: a state
     * published before the last end of a visitor still shows that visitor and
     * is never drawn again.
     */
    private var minGeneration = 0

    /** The picture the viewer showed before a recreate, shown again once the screens are back. */
    private var restoredViewer: Triple<String, String, String?>? = null

    /** Picked files whose screen does not exist yet (the screens come back after the config). */
    private val pendingPicks = ArrayList<Pair<Route?, Picked>>()
    private var dark = false
    private var isResumedNow = false
    @get:JvmSynthetic
    internal lateinit var files: Files
        private set

    /** What the messenger draws with: equal looks need no rebuild. */
    /**
     * What the screens are built from. Besides language, address, brand, light or dark and the design, the texts
     * and parts the top bars draw once: a help center switched on or off, or a new business name, builds them again;
     * other texts (a greeting) change nothing here, so the help keeps its rows.
     */
    private data class Look(
        val language: UiLanguage,
        val formal: Boolean,
        val brand: String?,
        val dark: Boolean,
        val design: String,
        val helpCenter: Boolean = false,
        val texts: List<String?> = emptyList(),
    )

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (viewer == null && hasScreens()) messenger.gestureStarted()
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (viewer == null && hasScreens()) messenger.gestureProgressed(backEvent.progress, backEvent.swipeEdge == BackEventCompat.EDGE_LEFT)
        }

        override fun handleOnBackCancelled() {
            if (hasScreens()) messenger.gestureCancelled()
        }

        override fun handleOnBackPressed() {
            viewer?.let {
                it.close()
                return
            }
            if (!hasScreens() || !messenger.gestureCommitted()) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val current = Deskwoot.currentSession ?: (savedInstanceState?.getString(KEY_TOKEN) ?: intent?.getStringExtra(EXTRA_TOKEN))?.let { token ->
            // The process was restarted with the messenger on screen, or a notification was tapped before the
            // app called setup in this process: the session of that inbox starts (from its disk cache), here and
            // now, never posted behind calls the app made from other threads.
            Deskwoot.setupNow(applicationContext, token)
        }
        dark = ThemeChoice.isDark(Deskwoot.theme, current?.state?.value?.config, hostNightMode(), ownNightMode())
        setTheme(if (dark) R.style.dw_messenger_theme_dark else R.style.dw_messenger_theme_light)
        super.onCreate(savedInstanceState)
        if (current == null) {
            // present* was never called through setup: nothing to show.
            finish()
            return
        }
        // A notification tap: the time it arrived bounds its wait, also across a recreate.
        stampTap(intent)
        if (savedInstanceState == null && isFirstTap(intent) && isTaskRoot && reopenOverApp()) return
        session = current
        openActivity = WeakReference(this)
        val bg = if (dark) DARK_BACKGROUND else Color.WHITE
        window.setBackgroundDrawable(ColorDrawable(bg))
        enableEdgeToEdge(barStyle(dark), barStyle(dark))
        files = Files(this, { session }, ::deliverPicked)
        files.restore(savedInstanceState)

        root = FrameLayout(this).apply {
            id = R.id.dw_messenger_root
            setBackgroundColor(bg)
        }
        container = FrameLayout(this).apply { id = R.id.dw_screen_container }
        root.addView(container, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        applyInsets()

        messenger = Messenger(container, ::createScreen, { enabled -> updateBack(enabled) }, ::followBars)
        onBackPressedDispatcher.addCallback(this, backCallback)

        // What the last instance saved belongs to the visitor it showed: it waits until the session knows
        // whether that is still the visitor (the process may have ended, a logout may have come meanwhile).
        if (savedInstanceState != null) {
            restoredVisitor = savedInstanceState.getString(KEY_VISITOR) ?: UNKNOWN_VISITOR
            restoredMemory = savedInstanceState
        }
        restoredRoutes = savedInstanceState?.getStringArrayList(Messenger.KEY_ROUTES)?.mapNotNull(Route::decode)?.takeIf { it.isNotEmpty() }
        restoredDesign = savedInstanceState?.getString(Messenger.KEY_DESIGN)
        restoredStates = savedInstanceState
        restoredViewer = ImageViewer.restore(savedInstanceState)
        // An entry that waited when the last instance went (a tap delivered to the open messenger), else the
        // intent's, unless the screens of the last instance come back.
        pendingEntry = Entry.restore(savedInstanceState?.getBundle(KEY_PENDING)) ?: if (restoredRoutes == null) Entry.from(intent) else null

        watch(current)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        stampTap(intent)
        // The newest request is the Activity's intent from now on: a recreate starts from it.
        setIntent(intent)
        pendingEntry = Entry.from(intent)
        unconfirmedTap = null
        main.removeCallbacks(tapCheck)
        session?.state?.value?.let(::onState)
        // Every present loads the inbox's settings again, also with the messenger open: a new look arrives with it.
        session?.refresh()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        isResumedNow = true
        // Under the loading or failure cover nothing of the screens is on screen.
        if (hasScreens() && placeholder == null) messenger.setShown(true)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        isResumedNow = false
        if (hasScreens()) messenger.setShown(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session?.let { outState.putString(KEY_TOKEN, it.widgetToken) }
        // Whose state this is: after a process death it comes back only for the same visitor.
        outState.putString(KEY_VISITOR, restoredVisitor ?: session?.visitorKey ?: NO_VISITOR)
        // An entry still waiting (a tap that arrived with the messenger open) is applied after the recreate.
        pendingEntry?.let { outState.putBundle(KEY_PENDING, it.toBundle()) }
        if (hasScreens()) {
            messenger.save(outState)
        } else {
            // The screens are not back yet (no config): what came from the last instance goes on.
            restoredRoutes?.let { routes -> outState.putStringArrayList(Messenger.KEY_ROUTES, ArrayList(routes.map { it.encode() })) }
            restoredDesign?.let { outState.putString(Messenger.KEY_DESIGN, it) }
            restoredStates?.let { saved ->
                saved.keySet().filter { it.startsWith(Messenger.KEY_SCREEN) }.forEach { outState.putBundle(it, saved.getBundle(it)) }
                saved.getStringArrayList(Messenger.KEY_KINDS)?.let { outState.putStringArrayList(Messenger.KEY_KINDS, it) }
            }
        }
        // A saved state still waiting for its visitor goes on as it was.
        if (restoredVisitor != null) UiMemory.carry(restoredMemory, outState) else UiMemory.save(outState)
        if (::files.isInitialized) files.save(outState)
        val shownPicture = viewer?.let { Triple(it.key, it.link, it.name) } ?: restoredViewer
        shownPicture?.let { (key, link, name) -> ImageViewer.save(outState, key, link, name) }
    }

    override fun onDestroy() {
        main.removeCallbacks(tapCheck)
        if (::messenger.isInitialized) messenger.destroy()
        if (::files.isInitialized) files.destroy()
        viewer?.dismissNow()
        scope.cancel()
        if (openActivity?.get() === this) openActivity = null
        super.onDestroy()
    }

    private fun hasScreens(): Boolean = ::messenger.isInitialized && messenger.started && messenger.screens.isNotEmpty()

    /** Draws every state of [current] from now on. */
    private fun watch(current: ChatSession) {
        stateJob?.cancel()
        // The generations count the ends of this session's visitors.
        minGeneration = 0
        stateJob = scope.launch { current.state.collect { onState(it) } }
        // A conversation the visitor reads loses its notification (the hook holds no Activity).
        val app = applicationContext
        val token = current.widgetToken
        current.onConversationOnScreen { id -> PushNotifications.cancel(app, token, id) }
        current.refresh()
    }

    // ── State ────────────────────────────────────────────────────────────

    private fun onState(state: SessionState) {
        val current = session ?: return
        // A state from before the last end of a visitor: the next one, of the new visitor, is on its way.
        if (state.visitorGeneration < minGeneration) return
        val config = state.config
        // Without a config there is nothing to draw; an unknown widget or a failed identity check is not shown from a cache.
        val blocking = state.phase == Phase.FAILED && (state.failure == SessionFailure.UNKNOWN_WIDGET || state.failure == SessionFailure.IDENTITY_CHECK)
        if (config == null || blocking) {
            showPlaceholder(state)
            return
        }
        // The state of the last instance comes back only for its visitor; the loading state shows until that is known.
        if (!restoreChecked(current)) {
            showPlaceholder(state)
            return
        }
        // A tapped conversation waits until the session can say whose it is: the loading state shows
        // meanwhile (the failure, when the session could not load), never another conversation.
        val step = (pendingEntry as? Entry.Open)?.let { tapStep(current, it) }
        if (step == TapStep.WAIT) {
            showPlaceholder(state)
            // A change on its way may equal the state drawn now and never be published: look again after it.
            if (current.statePending) current.afterPendingState { tapCheck.run() }
            return
        }
        val next = Look(
            UiLanguage.resolve(state.locale),
            Strings.isFormal(config.formOfAddress),
            config.brandColor,
            ThemeChoice.isDark(Deskwoot.theme, config, hostNightMode(), ownNightMode()),
            config.widgetDesign,
            config.helpCenterEnabled,
            listOf(config.businessName, config.inboxName),
        )
        if (next.dark != dark) {
            // Light or dark changed under an open messenger (the inbox's own setting arrived): start again in the right theme.
            recreate()
            return
        }
        val fresh = !hasScreens()
        if (next != look || fresh) {
            look = next
            val ui = makeUi(current, config, next)
            root.layoutDirection = if (next.language.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            // The messenger's own texts read in the session's direction; content views opt out (contentText).
            root.textDirection = if (next.language.isRtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
            val conversation = state.thread?.conversationId
            if (fresh) {
                // Routes saved in another look (it changed while the Activity was made again) go into this one.
                val routes = restoredRoutes?.let { saved -> LookSwitch.routes(saved, designFor(restoredDesign ?: ui.design.id), ui.design, conversation) }
                    ?: listOf(ui.design.startRoute)
                restoredRoutes = null
                restoredDesign = null
                messenger.start(ui, routes)
                restoredStates?.let(messenger::restoreStates)
                restoredStates = null
            } else {
                // The same screens in the new look: the same conversation, draft, list or article (never the first entry again).
                messenger.rebuild(ui, LookSwitch.routes(messenger.routes, messenger.ui.design, ui.design, conversation))
                // The viewer takes the new texts too.
                viewer?.let { shown -> openViewer(shown.key, shown.link, shown.name, animated = false) }
            }
        } else {
            messenger.ui.updateConfig(config)
        }
        hidePlaceholder()
        followTap(current, state)
        // The entry first: a conversation it opens is never drawn from the state before it, nor marked read.
        handleEntry(state, config, step)
        if (isResumedNow) messenger.setShown(true)
        messenger.render(state)
        if (fresh) screensBack()
    }

    /**
     * What the last instance saved (its screens with their routes and
     * fields, the drafts, the help search, the picture in the viewer)
     * belongs to the visitor it showed. It comes back when the session runs
     * as that visitor ([ChatSession.ownsSavedState]); for another visitor
     * (a logout or another user while the process was gone, a stored user
     * the app has not named yet) it is dropped and the start page shows.
     * False while that is not known yet.
     */
    private fun restoreChecked(current: ChatSession): Boolean {
        val saved = restoredVisitor ?: return true
        val owns = when (saved) {
            UNKNOWN_VISITOR -> false
            NO_VISITOR -> current.ownsSavedState(null)
            else -> current.ownsSavedState(saved)
        } ?: return false
        if (owns) {
            UiMemory.restore(restoredMemory)
        } else {
            restoredRoutes = null
            restoredDesign = null
            restoredStates = null
            restoredViewer = null
            dropPendingPicks()
            // A notification's tap decides by its own visitor key; anything else the last instance waited for goes.
            if ((pendingEntry as? Entry.Open)?.push == null) pendingEntry = null
        }
        restoredVisitor = null
        restoredMemory = null
        return true
    }

    /** The screens exist again: what waited for them (a picked file, the viewer of a recreated Activity) gets them. */
    private fun screensBack() {
        flushPicks()
        restoredViewer?.let { (key, link, name) ->
            restoredViewer = null
            openViewer(key, link, name, animated = false)
        }
    }

    private fun makeUi(current: ChatSession, config: WidgetConfig, l: Look): Ui {
        val themeRes = if (l.dark) R.style.dw_messenger_theme_dark else R.style.dw_messenger_theme_light
        val strings = Strings(this, l.language, l.formal, themeRes)
        val design = designFor(config.widgetDesign)
        return Ui(
            session = current,
            actions = SessionActions(current, scope),
            strings = strings,
            colors = UiColors(config.brandColor, l.dark),
            design = design,
            images = ImageLoader.get(this),
            scope = scope,
            nav = navigation,
            config = config,
        )
    }

    /** What becomes of a tapped conversation now. */
    private enum class TapStep {
        /** Not decided yet: the loading state shows, with its close button. */
        WAIT,

        /** It is the visitor's: it opens. */
        OPEN,

        /** It is nobody's this device knows: the start page shows. */
        START,

        /** Its wait for the app's identify ran out: the messenger shows what it would without the tap. */
        AS_BEFORE,
    }

    /**
     * A notification's conversation goes by the push's visitor key: the
     * visitor the session runs as opens it once who they are has settled,
     * the stored signed in user who waits for the app's identify keeps it
     * waiting for at most [TAP_WAIT_MILLIS] from the tap, anybody else's (or
     * no key) shows the start page. The in app card's conversation, which
     * has no key, opens once the session confirmed it as the visitor's.
     */
    private fun tapStep(current: ChatSession, entry: Entry.Open): TapStep {
        val push = entry.push ?: return when (current.ownsConversation(entry.conversationId)) {
            null -> TapStep.WAIT
            true -> TapStep.OPEN
            false -> TapStep.START
        }
        // Before the visitor's store was read, no visitor of this device is known.
        if (!current.visitorKnown) return TapStep.WAIT
        return when (current.pushVisitor(push.visitor)) {
            PushVisitor.FORGOTTEN -> TapStep.START
            PushVisitor.DORMANT -> {
                val left = push.tappedAt + TAP_WAIT_MILLIS - SystemClock.elapsedRealtime()
                if (left <= 0) {
                    TapStep.AS_BEFORE
                } else {
                    main.removeCallbacks(tapCheck)
                    main.postDelayed(tapCheck, left)
                    TapStep.WAIT
                }
            }
            PushVisitor.ACTIVE -> if (current.identitySettled) TapStep.OPEN else TapStep.WAIT
        }
    }

    /**
     * A tapped conversation opened on the strength of its key: once the
     * server showed a row of it, it is the visitor's; when it showed none, it
     * is not, and the start page shows.
     */
    private fun followTap(current: ChatSession, state: SessionState) {
        val id = unconfirmedTap ?: return
        when {
            current.tapRefused(id) -> {
                unconfirmedTap = null
                messenger.reset(listOf(messenger.ui.design.startRoute))
                // The visitor's own conversation comes back with the restore.
                current.refresh()
            }
            current.ownsConversation(id) == true || state.thread?.conversationId != id -> unconfirmedTap = null
        }
    }

    /** The entry `present*` asked for, once the session can answer it (present waits for the session by itself). */
    private fun handleEntry(state: SessionState, config: WidgetConfig, step: TapStep?) {
        val entry = pendingEntry ?: return
        when (entry) {
            is Entry.Start -> messenger.reset(listOf(messenger.ui.design.startRoute))
            is Entry.Help -> {
                val design = messenger.ui.design
                messenger.reset(
                    when {
                        !config.helpCenterEnabled -> listOf(design.startRoute)
                        // Direkt's help is a sheet over the chat.
                        design.isSheet(Route.Help) -> listOf(design.startRoute, Route.Help)
                        else -> listOf(Route.Help)
                    },
                )
            }
            is Entry.Open -> when (step) {
                TapStep.OPEN -> {
                    messenger.reset(listOf(messenger.ui.design.startRoute))
                    val id = entry.conversationId
                    val session = session
                    if (entry.push != null && session != null && session.ownsConversation(id) != true) {
                        // Its key says it is theirs; the server's first answer still decides.
                        session.openTapped(id)
                        unconfirmedTap = id
                    }
                    navigation.openConversation(id)
                }
                // Any other id (another contact's, a stale registration, a visitor the device forgot) shows the
                // start page and is never stored.
                TapStep.START -> messenger.reset(listOf(messenger.ui.design.startRoute))
                TapStep.AS_BEFORE -> Unit
                TapStep.WAIT, null -> return
            }
            is Entry.New -> {
                messenger.reset(listOf(messenger.ui.design.startRoute))
                navigation.startConversation(entry.draft)
            }
            is Entry.Conversation -> {
                // The newest running conversation needs the list: wait until the session is ready.
                if (state.phase == Phase.LOADING && state.conversations.isEmpty() && state.thread?.conversationId == null) return
                messenger.reset(listOf(messenger.ui.design.startRoute))
                val running = state.conversations.firstOrNull { !it.resolved }?.id
                    ?: state.thread?.takeIf { it.conversationId != null && !it.inEndState }?.conversationId
                val thread = state.thread
                val empty = thread == null || (thread.conversationId == null && thread.messages.none { it.role == MessageRole.VISITOR } && thread.pending.isEmpty())
                when {
                    running != null -> navigation.openConversation(running)
                    // Before the visitor's conversations are known, the empty thread is entered as it is, not marked
                    // new: a restore still to come may bring the running conversation.
                    !state.visitorLoaded && empty -> {
                        UiMemory.setArticle(null, null)
                        messenger.goTo(designRoute(if (messenger.ui.actions.gateBeforeChat() != Gate.None) Route.Gate else Route.Thread(null)))
                    }
                    else -> navigation.startConversation(null)
                }
            }
        }
        pendingEntry = null
    }

    // ── Changes from the facade ──────────────────────────────────────────

    /**
     * The visitor was forgotten (logout, another user, a refused identity, a
     * cookieless inbox): the viewer closes, a picked file of theirs goes, a
     * conversation tapped for them no longer opens, and every screen is
     * thrown away, so nothing of theirs stays readable or typable. Runs at
     * once, inside the call that ended the visitor, so whatever the app calls
     * next (`presentNewConversation(text)` right after `identify`) is kept
     * for the new visitor. The start page comes back with the new visitor's
     * first state; a state published before the wipe is never drawn again.
     */
    private fun startOver() {
        viewer?.dismissNow()
        viewer = null
        restoredViewer = null
        dropPendingPicks()
        pendingEntry = null
        unconfirmedTap = null
        main.removeCallbacks(tapCheck)
        restoredRoutes = null
        restoredDesign = null
        restoredStates = null
        restoredVisitor = null
        restoredMemory = null
        look = null
        minGeneration = maxOf(minGeneration, (session?.state?.value?.visitorGeneration ?: 0) + 1)
        if (::messenger.isInitialized) messenger.clear()
        // The new visitor's state is published on the main loop right after the wipe; draw it then.
        if (::root.isInitialized) root.post { if (!hasScreens()) session?.state?.value?.let(::onState) }
    }

    /** `Deskwoot.theme` changed: light or dark at once, also with the messenger open. */
    private fun applyTheme() {
        val wanted = ThemeChoice.isDark(Deskwoot.theme, session?.state?.value?.config, hostNightMode(), ownNightMode())
        if (wanted != dark) recreate()
    }

    // ── Before the config: loading and failure ──────────────────────────

    /**
     * Loading or the failure, over everything: it takes the touches and
     * hides the screens beneath from TalkBack, in the host's
     * light or dark until the inbox's own arrives, with a close control
     * in every state.
     */
    private fun showPlaceholder(state: SessionState) {
        val failed = state.phase == Phase.FAILED
        val language = UiLanguage.resolve(state.locale)
        val strings = Strings(this, language, Strings.isFormal(state.config?.formOfAddress))
        val colors = UiColors(state.config?.brandColor, dark)
        val view = FrameLayout(this).apply {
            setBackgroundColor(colors.bg)
            // Nothing beneath it reacts to a touch.
            isClickable = true
            // In the session's direction already, before the screens set it on the root.
            layoutDirection = if (language.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            textDirection = if (language.isRtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        // Each reason in its own words; Try again only where it can help.
        val (message, retryLabel) = when {
            !failed -> R.string.dw_loading to null
            state.failure == SessionFailure.UNKNOWN_WIDGET -> R.string.dw_sdk_unavailable to null
            state.failure == SessionFailure.IDENTITY_CHECK -> R.string.dw_sdk_identity_failed to R.string.dw_try_again
            state.failure == SessionFailure.SERVER -> R.string.dw_sdk_server_error to R.string.dw_try_again
            else -> R.string.dw_load_error to R.string.dw_try_again
        }
        val text = TextView(this).apply {
            id = if (failed) R.id.dw_load_error else R.id.dw_loading
            this.text = strings.t(message)
            textStyle(14f, 400, if (failed) colors.ink else colors.ink3)
            gravity = Gravity.CENTER
            maxWidth = dp(280)
            if (failed) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        column.addView(text)
        if (retryLabel != null) {
            val retry = TextView(this).apply {
                id = R.id.dw_retry
                this.text = strings.t(retryLabel)
                textStyle(14f, 500, colors.ink)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                minHeight = dp(48)
                gravity = Gravity.CENTER
                background = ripple(colors.withAlpha(colors.ink, 0.1), rounded(colors.bg, 999f, colors.line2))
                role(button = true)
                setOnClickListener { session?.refresh() }
            }
            column.addView(retry, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
        }
        view.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        val close = ImageView(this).apply {
            id = R.id.dw_close
            setImageDrawable(icon(R.drawable.dw_ic_close, colors.ink2))
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            background = ripple(colors.withAlpha(colors.ink, 0.12), null, rounded(colors.bg3, 999f))
            contentDescription = strings.t(R.string.dw_close)
            role(button = true)
            setOnClickListener { finish() }
        }
        view.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        placeholder?.let { root.removeView(it) }
        placeholder = view
        placeholderClose = close
        placeClose()
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        container.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // The screens beneath are not on screen: their thread is not read meanwhile.
        if (hasScreens()) messenger.setShown(false)
        // Back closes the messenger while there is nothing else.
        backCallback.isEnabled = viewer != null
    }

    private fun hidePlaceholder() {
        val shown = placeholder ?: return
        root.removeView(shown)
        placeholder = null
        placeholderClose = null
        container.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (hasScreens()) messenger.updateBackState()
    }

    /** The close control sits below the status bar, at the end. */
    private fun placeClose() {
        val close = placeholderClose ?: return
        val lp = close.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.topMargin = topInset + dp(4)
        lp.marginEnd = dp(8)
        close.layoutParams = lp
    }

    // ── Insets: header below the status bar, the bottom above the navigation bar and the keyboard ──

    private var systemBottom = 0
    private var imeBottom = 0
    private var imeAnimating = false

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemBottom = bars.bottom
            v.setPadding(bars.left, 0, bars.right, v.paddingBottom)
            topInset = bars.top
            if (!imeAnimating) imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            updateBottom()
            if (hasScreens()) messenger.screens.forEach { (it as? InsetAware)?.onTopInset(bars.top) }
            placeClose()
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
                }

                override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    if (running.any { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }) {
                        imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        updateBottom()
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimating = false
                        ViewCompat.getRootWindowInsets(root)?.let {
                            imeBottom = it.getInsets(WindowInsetsCompat.Type.ime()).bottom
                            updateBottom()
                        }
                    }
                }
            },
        )
    }

    private fun updateBottom() {
        val bottom = maxOf(systemBottom, imeBottom)
        if (root.paddingBottom != bottom) root.setPadding(root.paddingLeft, 0, root.paddingRight, bottom)
    }

    private fun updateBack(enabled: Boolean) {
        backCallback.isEnabled = enabled || viewer != null
    }

    // ── Screens ──────────────────────────────────────────────────────────

    /** The screen of [route] in the inbox's design. */
    private fun createScreen(route: Route, ui: Ui): Screen {
        val design = ui.design
        val inner = when (route) {
            Route.Start -> when {
                design.chatIsStart -> ThreadScreen(ui, route)
                design === EmpfangDesign -> EmpfangHomeScreen(ui, route)
                else -> HomeScreen(ui, route)
            }
            Route.Help -> when {
                design.isSheet(route) -> DirektHelpScreen(ui, route)
                design === EmpfangDesign -> EmpfangHomeScreen(ui, route)
                else -> HomeScreen(ui, route)
            }
            Route.Conversations -> ConversationsScreen(ui, route)
            is Route.Thread -> ThreadScreen(ui, route)
            Route.Gate -> GateScreen(ui, route)
            is Route.Collection -> CollectionScreen(ui, route)
            is Route.Article -> ArticleScreen(ui, route)
        }
        // Direkt's list, help, collection and article are sheets over the chat; their heads need no status bar room.
        val screen = if (design.isSheet(route)) SheetScreen(inner) else inner
        (screen as? InsetAware)?.onTopInset(topInset)
        return screen
    }

    /** Direkt's chat is its start page: every thread shows there. */
    private fun designRoute(route: Route): Route =
        if (messenger.started && messenger.ui.design.chatIsStart && route is Route.Thread) Route.Start else route

    /** Status bar icons in the band's text color while Empfang's band is under them, else the theme's. */
    private fun followBars(top: Screen) {
        val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val lightBars = if (top.barsOnBand && messenger.started) {
            androidx.core.graphics.ColorUtils.calculateLuminance(messenger.ui.colors.onBand) < 0.5
        } else {
            !dark
        }
        bars.isAppearanceLightStatusBars = lightBars
    }

    private var topInset = 0

    // ── Picked files ─────────────────────────────────────────────────────

    /** A picked file for the screen of [from]; it waits while the screens are not back yet. */
    private fun deliverPicked(from: Route?, picked: Picked) {
        pendingPicks += from to picked
        flushPicks()
    }

    private fun flushPicks() {
        if (!hasScreens() || pendingPicks.isEmpty()) return
        val waiting = pendingPicks.toList()
        pendingPicks.clear()
        for ((route, picked) in waiting) {
            // The chat changes its route with the look (Direkt's start page, a thread screen elsewhere): it still gets its pick.
            val chat = route?.takeIf { it == Route.Start || it is Route.Thread }
                ?.let { messenger.screens.lastOrNull { s -> LookSwitch.sameScreen(s.route, messenger.ui.design) == "chat" } }
            val receiver = route?.let(messenger::screenFor) as? PickReceiver ?: chat as? PickReceiver
            // The screen that asked is gone (the visitor went back meanwhile): the copy goes too.
            if (receiver != null) receiver.picked(picked) else discard(picked)
        }
    }

    private fun dropPendingPicks() {
        pendingPicks.forEach { discard(it.second) }
        pendingPicks.clear()
    }

    private fun discard(picked: Picked) {
        val temp = picked.temporary ?: return
        scope.launch(Dispatchers.IO) { temp.delete() }
    }

    // ── The image viewer ─────────────────────────────────────────────────

    private fun openViewer(key: String, link: String, name: String?, animated: Boolean) {
        if (!hasScreens()) return
        val ui = messenger.ui
        viewer?.dismissNow()
        val content = findViewById<ViewGroup>(android.R.id.content) ?: root
        val opened = ImageViewer(this, ui, key, link, name) {
            viewer = null
            if (hasScreens()) messenger.updateBackState() else updateBack(false)
        }
        viewer = opened
        opened.show(content, root, animated)
        updateBack(true)
    }

    // ── MessengerNavigation ──────────────────────────────────────────────

    /** What the screens ask of the messenger (kept off the Activity's public surface). */
    private val navigation = object : MessengerNavigation {
        override fun goTo(route: Route) = messenger.goTo(designRoute(route))

        override fun replace(route: Route) = messenger.replace(designRoute(route))

        override fun back() {
            if (!messenger.back()) finish()
        }

        override fun close() = finish()

        override fun startConversation(draft: String?, replaceCurrent: Boolean, article: com.deskwoot.sdk.model.HelpArticleRef?) {
            val ui = messenger.ui
            ui.actions.startNewConversation()
            val key = if (ui.config.continuousChat) session?.state?.value?.thread?.conversationId else null
            if (draft != null) UiMemory.setDraft(key, draft)
            // The article a question from the help comes from waits above the field.
            UiMemory.setArticle(key, article)
            val next = designRoute(if (ui.actions.gateBeforeChat() != Gate.None) Route.Gate else Route.Thread(null))
            if (replaceCurrent) messenger.replace(next) else messenger.goTo(next)
        }

        override fun openConversation(id: String) {
            messenger.ui.actions.openConversation(id)
            // Direkt: the chat under the sheets switches to that conversation and the sheets close.
            messenger.goTo(designRoute(Route.Thread(id)))
        }

        /** Only http, https, mailto and tel leave the SDK; whatever the system throws stays here. */
        override fun openUrl(url: String) {
            val uri = Links.outgoing(url) ?: return
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: RuntimeException) {
                // No app for it (ActivityNotFoundException), or the app that would open it does not let us.
            }
        }

        override fun showImage(key: String, link: String, name: String?) = openViewer(key, link, name, animated = true)

        override fun openFile(key: String, link: String, name: String?, mimeType: String?) {
            files.open(messenger.ui, key, link, name, mimeType)
        }

        override fun cameraAvailable(): Boolean = files.cameraAvailable()

        override fun pick(source: AttachSource, allowedTypes: List<String>, from: Route) = files.pick(source, allowedTypes, from)
    }

    /** The Activity's own night mode (it follows the app's when the app sets one for all its activities). */
    private fun ownNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun hostNightMode(): Boolean? = presenter?.get()?.takeUnless { it.isFinishing }?.let {
        it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun barStyle(dark: Boolean): SystemBarStyle =
        if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, SCRIM_FOR_OLD_BARS)

    // ── A notification's tap ─────────────────────────────────────────────

    /** Writes the time a notification's tap arrived into [tap], once: its wait counts from then, also after a recreate. */
    private fun stampTap(tap: Intent?) {
        if (tap == null || !tap.getBooleanExtra(EXTRA_PUSH, false) || tap.hasExtra(EXTRA_TAPPED_AT)) return
        tap.putExtra(EXTRA_TAPPED_AT, SystemClock.elapsedRealtime())
    }

    /** A notification's tap as the system delivered it, not one this Activity passed on already. */
    private fun isFirstTap(tap: Intent?): Boolean =
        tap != null && tap.getBooleanExtra(EXTRA_PUSH, false) && !tap.getBooleanExtra(EXTRA_FORWARDED, false)

    /**
     * The tap arrived while the app had no task (it was swiped away, or its
     * process ended): the messenger would be the root of a new task, so Back
     * would lead to the home screen and the app would leave Recents. Instead
     * the app's launch Activity becomes the root of a fresh task with the
     * messenger on top (the same tap, its time included), and this instance
     * ends. When the app's task is alive the messenger simply opens on top of
     * it and this is never asked. False when the app has no launch Activity
     * or the system refuses: the messenger then shows here.
     */
    private fun reopenOverApp(): Boolean {
        val launch = try {
            packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: RuntimeException) {
            null
        } ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Without flags of its own it goes into the launch Activity's task; marked, so it never comes back here.
        val again = Intent(intent).setFlags(0).putExtra(EXTRA_FORWARDED, true)
        return try {
            startActivities(arrayOf(launch, again))
            finish()
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * What the facade and the overlays need of the open messenger. Every
     * member is hidden from Java (`@JvmSynthetic`): the Activity is public
     * only because the system creates it.
     */
    internal companion object {
        private const val KEY_TOKEN = "dw_widget_token"
        private const val KEY_PENDING = "dw_pending_entry"

        /** The visitor key of the visitor whose state was saved. */
        private const val KEY_VISITOR = "dw_visitor"

        /** Saved for a visitor nobody knew yet (no bearer). */
        private const val NO_VISITOR = "none"

        /** A saved state without a visitor key: whose it was is not known, so it never comes back. */
        private const val UNKNOWN_VISITOR = "unknown"
        private const val EXTRA_ENTRY = "com.deskwoot.sdk.ENTRY"
        private const val EXTRA_TEXT = "com.deskwoot.sdk.TEXT"
        private const val EXTRA_CONVERSATION = "com.deskwoot.sdk.CONVERSATION"
        private const val EXTRA_TOKEN = "com.deskwoot.sdk.WIDGET_TOKEN"

        /** A notification's tap. */
        private const val EXTRA_PUSH = "com.deskwoot.sdk.PUSH"

        /** The visitor key of the tapped notification's push. */
        private const val EXTRA_VISITOR = "com.deskwoot.sdk.VISITOR"

        /** When the tap arrived, on [SystemClock.elapsedRealtime] (it keeps counting across a process death). */
        private const val EXTRA_TAPPED_AT = "com.deskwoot.sdk.TAPPED_AT"

        /** The tap passed on over the app's launch Activity: never passed on again. */
        private const val EXTRA_FORWARDED = "com.deskwoot.sdk.FORWARDED"

        /** How long a tap for the stored signed in user waits for the app's identify, from the tap. */
        private const val TAP_WAIT_MILLIS = 10_000L
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private const val DARK_BACKGROUND = 0xFF14141F.toInt()

        /** Before Android 6 the status bar icons are always light: a dark scrim keeps them readable over a light messenger. */
        private const val SCRIM_FOR_OLD_BARS = 0x801B1B1B.toInt()

        private val main by lazy { Handler(Looper.getMainLooper()) }

        /** The Activity that asked for the messenger, for its light or dark appearance. Weak: never kept alive. */
        @Volatile
        @get:JvmSynthetic
        @set:JvmSynthetic
        var presenter: WeakReference<Activity>? = null

        /** The open messenger, for `Deskwoot.dismiss` and to keep the in app card off it. */
        @Volatile
        @get:JvmSynthetic
        @set:JvmSynthetic
        var openActivity: WeakReference<DeskwootActivity>? = null

        @Volatile
        @get:JvmSynthetic
        @set:JvmSynthetic
        var resumed: Boolean = false

        /** [widgetToken]: the inbox to set up when the app has not (a notification tapped after the process ended). */
        @JvmSynthetic
        fun intent(context: Context, entry: Entry, widgetToken: String? = null): Intent {
            val intent = Intent(context, DeskwootActivity::class.java)
                .putExtra(EXTRA_ENTRY, entry.name)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            widgetToken?.let { intent.putExtra(EXTRA_TOKEN, it) }
            when (entry) {
                is Entry.New -> entry.draft?.let { intent.putExtra(EXTRA_TEXT, it) }
                is Entry.Open -> intent.putExtra(EXTRA_CONVERSATION, entry.conversationId)
                else -> Unit
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent
        }

        /**
         * The intent of a notification's tap: [entry] of the inbox
         * [widgetToken], with the push's [visitor] key, which decides whose
         * conversation it is.
         */
        @JvmSynthetic
        fun tapIntent(context: Context, entry: Entry, widgetToken: String, visitor: String?): Intent =
            intent(context, entry, widgetToken).putExtra(EXTRA_PUSH, true).putExtra(EXTRA_VISITOR, visitor)

        /**
         * The visitor was forgotten: an open messenger starts over before
         * the next call of the app is handled (at once on the main thread,
         * where the session ends a visitor).
         */
        @JvmSynthetic
        fun visitorWiped() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                openActivity?.get()?.takeUnless { it.isFinishing }?.startOver()
            } else {
                main.post { openActivity?.get()?.takeUnless { it.isFinishing }?.startOver() }
            }
        }

        /**
         * Another widget token replaces the session: an open messenger
         * closes at once, inside the app's call (main thread); the next
         * present opens the new inbox.
         */
        @JvmSynthetic
        fun sessionReplaced() {
            openActivity?.get()?.takeUnless { it.isFinishing }?.finish()
        }

        /** `Deskwoot.theme` changed: an open messenger takes it at once. */
        @JvmSynthetic
        fun themeChanged() {
            openActivity?.get()?.takeUnless { it.isFinishing }?.applyTheme()
        }
    }

    /** How the messenger is asked to open. */
    internal sealed class Entry(val name: String) {
        object Start : Entry("start")

        object Help : Entry("help")

        object Conversation : Entry("conversation")

        class New(val draft: String?) : Entry("new")

        /** [push]: the conversation of a notification's tap (the in app card's has none). */
        class Open(val conversationId: String, val push: PushTap? = null) : Entry("open")

        /** A notification's tap: the visitor key of its push, and when it arrived ([SystemClock.elapsedRealtime]). */
        class PushTap(val visitor: String?, val tappedAt: Long)

        /** The entry as extras, the way an intent carries it, for the saved state. */
        @JvmSynthetic
        fun toBundle(): Bundle = Bundle().also { out ->
            out.putString(EXTRA_ENTRY, name)
            when (this) {
                is New -> draft?.let { out.putString(EXTRA_TEXT, it) }
                is Open -> {
                    out.putString(EXTRA_CONVERSATION, conversationId)
                    push?.let {
                        out.putBoolean(EXTRA_PUSH, true)
                        out.putString(EXTRA_VISITOR, it.visitor)
                        out.putLong(EXTRA_TAPPED_AT, it.tappedAt)
                    }
                }
                else -> Unit
            }
        }

        internal companion object {
            @JvmSynthetic
            fun from(intent: Intent?): Entry = read(intent?.extras) ?: Start

            /** A saved entry; null when none was saved. */
            @JvmSynthetic
            fun restore(saved: Bundle?): Entry? = read(saved)

            private fun read(extras: Bundle?): Entry? = when (extras?.getString(EXTRA_ENTRY)) {
                null -> null
                "help" -> Help
                "conversation" -> Conversation
                "new" -> New(extras.getString(EXTRA_TEXT))
                "open" -> extras.getString(EXTRA_CONVERSATION)?.let { id ->
                    val push = if (extras.getBoolean(EXTRA_PUSH, false)) {
                        PushTap(extras.getString(EXTRA_VISITOR), extras.getLong(EXTRA_TAPPED_AT, SystemClock.elapsedRealtime()))
                    } else {
                        null
                    }
                    Open(id, push)
                } ?: Start
                else -> Start
            }
        }
    }
}

/** A screen whose header takes the status bar's height above it. */
internal interface InsetAware {
    fun onTopInset(top: Int)
}

/**
 * Light or dark (brief "Theme"): the app's appearance by default, or what
 * the app chose. [Deskwoot.Theme.INBOX] follows the app too until the
 * inbox's config is there, so a dark app never flashes a white messenger.
 */
internal object ThemeChoice {
    fun isDark(mode: Deskwoot.Theme, config: WidgetConfig?, hostNight: Boolean?, ownNight: Boolean): Boolean = when (mode) {
        Deskwoot.Theme.LIGHT -> false
        Deskwoot.Theme.DARK -> true
        Deskwoot.Theme.INBOX -> config?.let { it.widgetTheme == "dark" } ?: (hostNight ?: ownNight)
        Deskwoot.Theme.SYSTEM -> hostNight ?: ownNight
    }
}
