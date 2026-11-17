package com.deskwoot.sdk.ui.notify

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.deskwoot.sdk.AndroidLogger
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.model.ConversationSummary
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.MessageRules
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.ui.DeskwootActivity
import com.deskwoot.sdk.ui.KlartextDesign
import com.deskwoot.sdk.ui.MessengerNavigation
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.SessionActions
import com.deskwoot.sdk.ui.ThemeChoice
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.files.AttachSource
import com.deskwoot.sdk.ui.files.Picked
import com.deskwoot.sdk.ui.image.ImageLoader
import com.deskwoot.sdk.ui.text.MessageText
import com.deskwoot.sdk.ui.text.Strings
import com.deskwoot.sdk.ui.text.UiLanguage
import com.deskwoot.sdk.ui.theme.UiColors
import com.deskwoot.sdk.ui.widgets.FaceView
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.oneLine
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

/**
 * What the SDK shows on the app's own screens, without any permission and
 * without a system overlay: views added to the resumed Activity's content
 * and removed when it pauses. The card for a reply that arrived while the
 * messenger is closed (avatar, name, 2 lines; a tap opens that
 * conversation), and the optional launcher with its unread badge. Neither
 * ever shows over the messenger itself, over a translucent or floating
 * Activity (a dialog, a sheet, a payment or sign in screen of another SDK)
 * or over an Activity the app excluded (`Deskwoot.excludeFromOverlays`).
 * The launcher steps aside while something of the app's own covers its
 * window (a dialog, a sheet, a menu: the window loses the focus), and both
 * are drawn again when the brand color, the language or light and dark
 * change. Only weak references to activities are held.
 */
internal class HostOverlays(private val sessionOf: () -> ChatSession?) : Application.ActivityLifecycleCallbacks {
    /** The card's and the launcher's work; an error there is logged and never reaches the app's crash handler. */
    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> AndroidLogger.debug("Unexpected error over the app: $error") },
    )
    private var resumed: WeakReference<Activity>? = null
    private var card: View? = null

    /** The conversation and the reply the card shows, to draw it again in a new look. */
    private var cardReply: Pair<String, Message>? = null
    private var launcher: View? = null
    private var cardPlace: Placement? = null
    private var launcherPlace: Placement? = null

    /** The look the card and the launcher were drawn in. */
    private var drawn: Look? = null
    private var lastState: SessionState? = null
    /** Replies the card showed; [cardShowed] reads it on the thread `handlePush` is called on. */
    private val shownMessages: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val hideCard = Runnable { removeCard(animated = true) }

    var launcherVisible: Boolean = false
        set(value) {
            field = value
            refreshLauncher()
        }

    /** The app's foreground Activity, when it is not the messenger and takes overlays. */
    val currentActivity: Activity? get() = resumed?.get()?.takeUnless { it.isFinishing }

    private var watching: Job? = null

    fun watch(session: ChatSession) {
        watching?.cancel()
        lastState = null
        newestKnownReplyAt = Long.MIN_VALUE
        watching = scope.launch { session.state.collect(::onState) }
    }

    @MainThread
    internal fun onState(state: SessionState) {
        val before = lastState
        lastState = state
        // First the look (a new brand, language, light or dark), so a new card is drawn in it.
        sync(state)
        val newestBefore = newestKnownReplyAt
        newestKnownReplyAt = maxOf(newestKnownReplyAt, newestReplyAt(state))
        if (before != null && state.unreadCount > before.unreadCount) newReply(state, newestBefore)
        updateLauncherBadge(state)
    }

    /**
     * The server time of the newest reply known so far. A reply is news for
     * the card only when it is unknown and newer than this (the iOS SDK
     * has the same rule): an older reply that shows up late, a reload of the list or
     * another visitor's state never raises a card.
     */
    private var newestKnownReplyAt = Long.MIN_VALUE

    private fun newestReplyAt(state: SessionState): Long {
        var newest = Long.MIN_VALUE
        state.thread?.messages?.forEach { if (MessageRules.countsAsUnread(it)) newest = maxOf(newest, it.createdAt) }
        state.conversations.forEach { c -> c.lastMessage?.takeIf { MessageRules.countsAsUnread(it) }?.let { newest = maxOf(newest, it.createdAt) } }
        return newest
    }

    /** Whether the in app card showed the reply [messageId] (its push then shows without sound or a banner). Any thread. */
    fun cardShowed(messageId: String): Boolean = messageId in shownMessages

    /** The app excluded an Activity class: the one in front may be one of them. */
    fun recheck() {
        val activity = resumed?.get() ?: return
        if (acceptsOverlays(activity)) return
        removeCard(animated = false)
        removeLauncher()
        resumed = null
    }

    /** The launcher's bottom padding changed. */
    fun relayout() {
        launcherPlace?.update()
        cardPlace?.update()
    }

    /** The visitor was forgotten (logout, another user): a card with their reply goes at once. */
    fun visitorWiped() {
        removeCard(animated = false)
        shownMessages.clear()
        newestKnownReplyAt = Long.MIN_VALUE
    }

    // ── Activity lifecycle ───────────────────────────────────────────────

    override fun onActivityResumed(activity: Activity) {
        if (!acceptsOverlays(activity)) {
            removeCard(animated = false)
            removeLauncher()
            return
        }
        resumed = WeakReference(activity)
        drawn = null
        refreshLauncher()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) {
            removeCard(animated = false)
            removeLauncher()
            resumed = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    // ── The look ─────────────────────────────────────────────────────────

    /** Everything the card and the launcher are drawn from; a change draws them again. */
    private data class Look(
        val brand: String,
        val dark: Boolean,
        val language: UiLanguage,
        val formal: Boolean,
        val left: Boolean,
        val label: String?,
    )

    private fun lookOf(activity: Activity, state: SessionState): Look? {
        val config = state.config ?: return null
        return Look(
            config.brandColor,
            isDark(activity, state),
            UiLanguage.resolve(state.locale),
            Strings.isFormal(config.formOfAddress),
            config.widgetPosition == "left",
            config.launcherTitle,
        )
    }

    /** The launcher where it is wanted, the card and the launcher in the current look. */
    private fun sync(state: SessionState) {
        val activity = currentActivity ?: return
        val look = lookOf(activity, state) ?: return
        if (look != drawn) {
            drawn = look
            if (launcher != null) removeLauncher()
            val reply = cardReply
            if (card != null && reply != null) showCard(activity, state, reply.first, reply.second, animated = false)
        }
        refreshLauncher()
    }

    // ── The card ─────────────────────────────────────────────────────────

    private fun newReply(state: SessionState, newestBefore: Long) {
        if (!Deskwoot.inAppNotificationsEnabled || DeskwootActivity.resumed) return
        val activity = currentActivity ?: return
        if (state.config == null) return
        val (conversationId, message) = newestUnread(state) ?: return
        if (newestBefore != Long.MIN_VALUE && message.createdAt <= newestBefore) return
        if (!shownMessages.add(message.id)) return
        showCard(activity, state, conversationId, message, animated = true)
    }

    private fun showCard(activity: Activity, state: SessionState, conversationId: String, message: Message, animated: Boolean) {
        val content = contentOf(activity) ?: return
        val ui = overlayUi(activity, state) ?: return
        val c = ui.colors
        val ctx = ui.context
        removeCard(animated = false)
        val author = ui.people.authorOf(message)
        val view = CardFrame(ctx) { restyleSoon(it) }.apply {
            id = R.id.dw_in_app_card
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(ctx.dp(16), ctx.dp(12), ctx.dp(4), ctx.dp(12))
            background = ripple(c.withAlpha(c.ink, 0.08), ctx.rounded(c.bg, 20f, c.line))
            elevation = ctx.dp(8).toFloat()
            layoutDirection = if (ui.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            textDirection = if (ui.rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
            role(button = true)
        }
        view.addView(FaceView(ctx, ui).apply { setFace(author.face) }, linear(ctx.dp(32), ctx.dp(32)))
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val text = MessageText.preview(message.content, 200)
            ?: message.attachments.firstOrNull()?.fileName?.let { ui.t(R.string.dw_file_preview, it) }
            ?: ui.t(R.string.dw_file_sent_preview)
        column.addView(TextView(ctx).apply {
            contentText()
            this.text = text
            textStyle(14f, 400, c.ink, 1.43f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val time = ui.formats.ago(message.createdAt, System.currentTimeMillis())
        column.addView(TextView(ctx).apply {
            this.text = ui.t(R.string.dw_sdk_card_meta, author.name, time)
            textStyle(12f, 400, c.ink3, 1.33f)
            oneLine()
        }, linear(MATCH, WRAP).apply { topMargin = ctx.dp(2) })
        view.addView(column, linear(0, WRAP, 1f).apply { marginStart = ctx.dp(12) })
        view.addView(ImageView(ctx).apply {
            id = R.id.dw_in_app_card_close
            setImageDrawable(ctx.icon(R.drawable.dw_ic_close, c.ink3))
            setPadding(ctx.dp(15), ctx.dp(15), ctx.dp(15), ctx.dp(15))
            contentDescription = ui.t(R.string.dw_hide_message)
            role(button = true)
            setOnClickListener { removeCard(animated = true) }
        }, linear(ctx.dp(48), ctx.dp(48)))
        view.contentDescription = ui.t(R.string.dw_new_messages) + ", " + author.name + ", " + time + ", " + text
        view.setOnClickListener {
            removeCard(animated = false)
            Deskwoot.presentConversationFromCard(activity, conversationId)
        }
        content.addView(view, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        // Above the launcher when it shows, as the web's cards sit above it; both above the app's tab bar.
        cardPlace = Placement(view, content, ctx.dp(12)) {
            val overLauncher = if (launcher?.parent != null) ctx.dp(16 + 48 + 8) else 0
            ctx.dp(12 + launcherBottomPadding) + overLauncher
        }
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        card = view
        cardReply = conversationId to message
        if (animated && !ui.reducedMotion) {
            view.alpha = 0f
            view.translationY = ctx.dp(8).toFloat()
            view.scaleX = 0.96f
            view.scaleY = 0.96f
            view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(240).start()
        }
        view.postDelayed(hideCard, CARD_MILLIS)
    }

    /** The newest reply the visitor has not seen: in the current conversation, or the newest of another one. */
    private fun newestUnread(state: SessionState): Pair<String, Message>? {
        val candidates = ArrayList<Pair<String, Message>>()
        val thread = state.thread
        val id = thread?.conversationId
        if (thread != null && id != null) {
            thread.messages.lastOrNull { MessageRules.countsAsUnread(it) }?.let { candidates += id to it }
        }
        state.conversations.filter { it.unread > 0 && it.id != id }.forEach { c: ConversationSummary ->
            c.lastMessage?.takeIf { MessageRules.countsAsUnread(it) }?.let { candidates += c.id to it }
        }
        return candidates.maxByOrNull { it.second.createdAt }
    }

    private fun removeCard(animated: Boolean) {
        val view = card ?: return
        card = null
        cardReply = null
        cardPlace = null
        view.removeCallbacks(hideCard)
        val parent = view.parent as? ViewGroup ?: return
        if (animated && view.isAttachedToWindow) {
            view.animate().alpha(0f).translationY(view.dp(8).toFloat()).setDuration(160).withEndAction { parent.removeView(view) }.start()
        } else {
            parent.removeView(view)
        }
    }

    // ── The launcher ─────────────────────────────────────────────────────

    private fun refreshLauncher() {
        if (!launcherVisible) {
            removeLauncher()
            return
        }
        // Removed on every pause: one that is attached belongs to the Activity in front. One the app
        // took off with its content (a second setContentView) is built again.
        if (launcher?.parent != null) return
        removeLauncher()
        val activity = currentActivity ?: return
        val state = lastState ?: sessionOf()?.state?.value ?: return
        val content = contentOf(activity) ?: return
        val look = lookOf(activity, state) ?: return
        val ui = overlayUi(activity, state) ?: return
        val ctx = ui.context
        val size = ctx.dp(48)
        val frame = LauncherFrame(ctx) { restyleSoon(it) }.apply {
            id = R.id.dw_launcher
            role(button = true)
            setOnClickListener { Deskwoot.present(activity) }
            // Hidden until the window has the focus: nothing of the app's own covers it.
            visibility = if (activity.hasWindowFocus()) View.VISIBLE else View.INVISIBLE
            // The badge sits at the top end in the chat's language, as the card reads in it.
            layoutDirection = if (ui.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val c = ui.colors
        // The web launcher: the brand gradient at 135 degrees, the white mark at 47 %.
        val bubble = ImageView(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c.brand, c.deep)).apply { shape = GradientDrawable.OVAL }
            val pad = (size * 0.265f).toInt()
            setPadding(pad, pad, pad, pad)
            setImageDrawable(ctx.icon(R.drawable.dw_ic_launcher_logo, Color.WHITE))
            elevation = ctx.dp(6).toFloat()
        }
        frame.addView(bubble, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        val badge = TextView(ctx).apply {
            id = R.id.dw_launcher_badge
            textStyle(11f, 700, Color.WHITE, 1f)
            gravity = Gravity.CENTER
            minWidth = ctx.dp(20)
            minHeight = ctx.dp(20)
            setPadding(ctx.dp(5), 0, ctx.dp(5), 0)
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(10).toFloat()
                setColor(0xFFEF4444.toInt())
                setStroke(ctx.dp(2), Color.WHITE)
            }
            elevation = ctx.dp(7).toFloat()
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        frame.label = look.label ?: ui.t(R.string.dw_launcher_default)
        // The inbox's side is a side of the screen (left or right, as on the website), whatever the app's direction.
        val params = FrameLayout.LayoutParams(size + ctx.dp(8), size + ctx.dp(8), Gravity.BOTTOM or if (look.left) Gravity.LEFT else Gravity.RIGHT)
        content.addView(frame, params)
        launcherPlace = Placement(frame, content, ctx.dp(16)) { ctx.dp(16 + launcherBottomPadding) }
        launcher = frame
        drawn = look
        updateLauncherBadge(state)
    }

    private fun updateLauncherBadge(state: SessionState) {
        val frame = launcher as? LauncherFrame ?: return
        val badge = frame.findViewById<TextView>(R.id.dw_launcher_badge) ?: return
        val count = state.unreadCount
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
        badge.text = if (count > 99) "99+" else count.toString()
        // "Chat with us, 3 new messages": the plural of the session's language (the frame's context speaks it).
        frame.contentDescription = if (count > 0) {
            frame.label + ", " + frame.context.resources.getQuantityString(R.plurals.dw_sdk_unread, count, count)
        } else {
            frame.label
        }
    }

    private fun removeLauncher() {
        val view = launcher ?: return
        launcher = null
        launcherPlace = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    // ── Shared ───────────────────────────────────────────────────────────

    private fun contentOf(activity: Activity): FrameLayout? = activity.window?.decorView?.findViewById(android.R.id.content)

    /** The app changed its configuration under an overlay without a new Activity (light or dark, language). */
    private fun restyleSoon(view: View) {
        view.post { lastState?.let(::sync) }
    }

    /**
     * Keeps an overlay [side] from the content's sides and [bottom] above its
     * bottom, plus what the system bars and the keyboard cover of the
     * content. The margins are kept current before every frame: a frame
     * whose margins had to change is skipped, so the view never shows in a
     * wrong place. Only what the bars and the keyboard cover
     * of the app's content counts: an app that is not edge to edge already
     * keeps its content above the navigation bar, and before Android 11 its
     * content view never hears of the insets.
     */
    private class Placement(private val view: View, private val content: View, private val side: Int, private val bottom: () -> Int) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private var dispatched: WindowInsetsCompat? = null
        private var observer: ViewTreeObserver? = null

        init {
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                dispatched = insets
                insets
            }
            view.addOnAttachStateChangeListener(this)
            if (view.isAttachedToWindow) onViewAttachedToWindow(view)
            update()
        }

        override fun onViewAttachedToWindow(v: View) {
            observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }

        override fun onViewDetachedFromWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
        }

        override fun onPreDraw(): Boolean = !update()

        /** True when the margins changed. */
        fun update(): Boolean {
            val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return false
            // Before Android 6 there are no root insets: what reached the view is the raw window's (an edge to edge app).
            val edges = overlapOf(content, ViewCompat.getRootWindowInsets(content) ?: dispatched)
            val left = side + edges.left
            val right = side + edges.right
            val below = bottom() + edges.bottom
            if (lp.leftMargin == left && lp.rightMargin == right && lp.bottomMargin == below && lp.topMargin == 0) return false
            lp.setMargins(left, 0, right, below)
            view.layoutParams = lp
            return true
        }
    }

    /** The world of an overlay: the session's language, the brand, the app's appearance. */
    private fun overlayUi(activity: Activity, state: SessionState): Ui? {
        val session = sessionOf() ?: return null
        val config = state.config ?: return null
        val dark = isDark(activity, state)
        val strings = Strings(
            activity,
            UiLanguage.resolve(state.locale),
            Strings.isFormal(config.formOfAddress),
            if (dark) R.style.dw_messenger_theme_dark else R.style.dw_messenger_theme_light,
        )
        return Ui(session, SessionActions(session, scope), strings, UiColors(config.brandColor, dark), KlartextDesign, ImageLoader.get(activity), scope, OverlayNav(activity), config)
    }

    /** Overlays only open the messenger. */
    private class OverlayNav(activity: Activity) : MessengerNavigation {
        private val ref = WeakReference(activity)

        override fun goTo(route: Route) = Unit

        override fun replace(route: Route) = Unit

        override fun back() = Unit

        override fun close() = Unit

        override fun startConversation(draft: String?, replaceCurrent: Boolean, article: com.deskwoot.sdk.model.HelpArticleRef?) {
            ref.get()?.let { Deskwoot.presentNewConversation(it, draft) }
        }

        override fun openConversation(id: String) {
            ref.get()?.let { Deskwoot.presentConversationFromCard(it, id) }
        }

        override fun openUrl(url: String) = Unit

        override fun showImage(key: String, link: String, name: String?) = Unit

        override fun openFile(key: String, link: String, name: String?, mimeType: String?) = Unit

        override fun cameraAvailable(): Boolean = false

        override fun pick(source: AttachSource, allowedTypes: List<String>, from: Route) = Unit
    }

    /** The card's frame: drawn again when the app's configuration changes under it. */
    @SuppressLint("ViewConstructor")
    private class CardFrame(context: Context, private val onConfig: (View) -> Unit) : LinearLayout(context) {
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            onConfig(this)
        }
    }

    /**
     * The launcher's frame: it steps aside while its window has no focus (the
     * app shows a dialog, a sheet or a menu over it) and is drawn again when
     * the app's configuration changes under it.
     */
    @SuppressLint("ViewConstructor")
    private class LauncherFrame(context: Context, private val onConfig: (View) -> Unit) : FrameLayout(context) {
        /** "Chat with us", or the owner's launcher text. */
        var label: String = ""

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            visibility = if (hasWindowFocus) View.VISIBLE else View.INVISIBLE
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            onConfig(this)
        }
    }

    /** How far the bars and the keyboard reach into the app's content on its left, right and bottom. */
    internal data class Edges(val left: Int, val right: Int, val bottom: Int)

    internal companion object {
        const val CARD_MILLIS = 10_000L

        /** Activity classes the app keeps the overlays off (`Deskwoot.excludeFromOverlays`); any thread. */
        val excluded: MutableSet<Class<*>> = CopyOnWriteArraySet()

        /** dp the launcher and the card are lifted, for example over the app's tab bar (`Deskwoot.setLauncherBottomPadding`). */
        @Volatile
        var launcherBottomPadding: Int = 0

        /** Not the messenger, not excluded by the app, neither translucent nor floating. */
        fun acceptsOverlays(activity: Activity): Boolean {
            if (activity is DeskwootActivity) return false
            if (excluded.any { it.isInstance(activity) }) return false
            return !themeFlag(activity, android.R.attr.windowIsTranslucent) && !themeFlag(activity, android.R.attr.windowIsFloating)
        }

        private fun themeFlag(activity: Activity, attr: Int): Boolean = try {
            val values = activity.theme.obtainStyledAttributes(intArrayOf(attr))
            try {
                values.getBoolean(0, false)
            } finally {
                values.recycle()
            }
        } catch (_: RuntimeException) {
            false
        }

        /**
         * Light or dark as the app shows this Activity: its own configuration,
         * so a night mode the app forces on it (AppCompat's local or default
         * night mode, `UiModeManager.setApplicationNightMode`) counts, not
         * only the system's.
         */
        fun isDark(activity: Activity, state: SessionState): Boolean {
            val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return ThemeChoice.isDark(Deskwoot.theme, state.config, night, night)
        }

        fun overlapOf(content: View, insets: WindowInsetsCompat?): Edges {
            insets ?: return Edges(0, 0, 0)
            val root = content.rootView
            // Not laid out yet: nothing to measure; the check before the first frame comes back.
            if (root.width == 0 || root.height == 0 || content.width == 0 || content.height == 0) return Edges(0, 0, 0)
            // A display cutout counts too: in landscape the card never reaches under the camera.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val at = IntArray(2)
            content.getLocationInWindow(at)
            return overlap(bars.left, bars.right, bars.bottom, at[0], at[0] + content.width, at[1] + content.height, root.width, root.height)
        }

        /** What of the window's bars (in window pixels) lies over the content's rectangle. */
        fun overlap(barLeft: Int, barRight: Int, barBottom: Int, contentLeft: Int, contentRight: Int, contentBottom: Int, windowWidth: Int, windowHeight: Int): Edges =
            Edges(
                left = max(0, barLeft - contentLeft),
                right = max(0, barRight - (windowWidth - contentRight)),
                bottom = max(0, barBottom - (windowHeight - contentBottom)),
            )
    }
}
