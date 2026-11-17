package com.deskwoot.sdk.ui

import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.Harness
import com.deskwoot.sdk.R
import com.deskwoot.sdk.TOKEN
import com.deskwoot.sdk.BASE_URL
import com.deskwoot.sdk.api.FileFetcher
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.help.HelpCenterClient
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.thread.ThreadAdapter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import java.util.concurrent.Executor

/**
 * The real messenger in a Robolectric test: [DeskwootActivity], the
 * [Messenger] and every screen, on a real [ChatSession] whose engine runs on
 * virtual time against the fake server of [Harness] (`h.http` answers every
 * call; script it before the step that makes the call).
 *
 * Two clocks run: the engine's virtual time ([TestScope]) and Robolectric's
 * main looper. [pump] lets both run until nothing is due now; [elapse] lets
 * time pass on both (animations, the announcer's batch, the bot's poll).
 * The lists diff on the calling thread ([ThreadAdapter.diffExecutor]), so
 * rows are there after a pump.
 *
 * ```
 * @Test fun x() = runTest {
 *     val s = ScreenHarness(this).open()
 *     s.click(R.id.dw_write_to_us)
 *     ...
 *     s.close()
 * }
 * ```
 * Call [close] at the end of every test: the session, the diff executor and
 * the drafts in [UiMemory] are process wide.
 */
internal class ScreenHarness(
    private val test: TestScope,
    config: JSONObject = baseConfig(),
    record: VisitorRecord = VisitorRecord(),
    /** True: no slides or fades (the system's animator scale 0); false: they run and take their time. */
    reducedMotion: Boolean = true,
) {
    val h = Harness(test, config, record, appLanguage = "en-US")
    private val app = RuntimeEnvironment.getApplication()
    private val api = WidgetApi(h.http, { BASE_URL }, TOKEN, StandardTestDispatcher(test.testScheduler))
    val session = ChatSession(TOKEN, h.engine, HelpCenterClient(api, h.engine::helpContext), api, FileFetcher { _, _ -> null })
    private var controller: ActivityController<DeskwootActivity>? = null

    val activity: DeskwootActivity get() = checkNotNull(controller) { "open() first" }.get()

    init {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (reducedMotion) 0f else 1f)
        ThreadAdapter.diffExecutor = Executor { it.run() }
        UiMemory.clear()
        install(session)
    }

    /** Starts the session and opens the messenger the way `Deskwoot.present*` would. */
    fun open(entry: DeskwootActivity.Entry = DeskwootActivity.Entry.Start): ScreenHarness {
        h.start()
        controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry)).setup()
        pump()
        return this
    }

    /** The engine and the main looper run until nothing is due now (a frame included). */
    fun pump() {
        repeat(4) {
            test.runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MILLIS))
        }
        test.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** [millis] pass on the engine's clock and on the main looper. */
    fun elapse(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 100L)
            test.advanceTimeBy(step)
            test.runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
        pump()
    }

    /** Only the main looper runs: what the screens do before the engine publishes its next state. */
    fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** A few frames are drawn (layout included) while the engine stands still. */
    fun frames() {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MILLIS)) }
    }

    /**
     * A [Ui] like the messenger's, over the open Activity and this session,
     * for a component (a card, a banner) tested on its own.
     */
    fun ui(): Ui {
        val config = checkNotNull(session.state.value.config) { "open() first" }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        return Ui(
            session = session,
            actions = SessionActions(session, scope),
            strings = com.deskwoot.sdk.ui.text.Strings(activity, com.deskwoot.sdk.ui.text.UiLanguage.EN, false, R.style.dw_messenger_theme_light),
            colors = com.deskwoot.sdk.ui.theme.UiColors(config.brandColor, false),
            design = KlartextDesign,
            images = com.deskwoot.sdk.ui.image.ImageLoader.get(activity),
            scope = scope,
            nav = NoNavigation,
            config = config,
        )
    }

    /** Navigation that goes nowhere, for components tested on their own. */
    object NoNavigation : MessengerNavigation {
        override fun goTo(route: Route) = Unit
        override fun replace(route: Route) = Unit
        override fun back() = Unit
        override fun close() = Unit
        override fun startConversation(draft: String?, replaceCurrent: Boolean, article: com.deskwoot.sdk.model.HelpArticleRef?) = Unit
        override fun openConversation(id: String) = Unit
        override fun openUrl(url: String) = Unit
        override fun showImage(key: String, link: String, name: String?) = Unit
        override fun openFile(key: String, link: String, name: String?, mimeType: String?) = Unit
        override fun cameraAvailable(): Boolean = false
        override fun pick(source: com.deskwoot.sdk.ui.files.AttachSource, allowedTypes: List<String>, from: Route) = Unit
    }

    /** The screen on top: the last visible child of the screen container. */
    val top: View
        get() {
            val container = activity.findViewById<ViewGroup>(R.id.dw_screen_container)
            return (container.childCount - 1 downTo 0).map(container::getChildAt).first { it.visibility == View.VISIBLE }
        }

    /** The visible views with [id] on the top screen, depth first. */
    fun all(id: Int, root: View = top): List<View> = views(root).filter { it.id == id && it.isShown }

    fun <T : View> find(id: Int): T {
        @Suppress("UNCHECKED_CAST")
        return checkNotNull(all(id).firstOrNull()) { "No visible view ${app.resources.getResourceEntryName(id)} on the top screen" } as T
    }

    fun has(id: Int): Boolean = all(id).isNotEmpty()

    /** Every text shown on the top screen. */
    fun texts(): List<String> = views(top).filter { it.isShown }.filterIsInstance<TextView>().map { it.text.toString() }

    fun click(id: Int) {
        check(find<View>(id).performClick()) { "Nothing happened on a click on ${app.resources.getResourceEntryName(id)}" }
        pump()
    }

    /** Types [text] into the composer as the visitor would. */
    fun type(text: String) {
        val input = find<EditText>(R.id.dw_composer_input)
        input.requestFocus()
        input.text.append(text)
        pump()
    }

    /** Types and taps send. */
    fun send(text: String) {
        type(text)
        click(R.id.dw_composer_send)
    }

    val composerText: String get() = find<EditText>(R.id.dw_composer_input).text.toString()

    /** The thread's rows that are laid out now. */
    fun rows(): List<View> {
        val list = find<RecyclerView>(R.id.dw_thread_list)
        return (0 until list.childCount).map(list::getChildAt)
    }

    /** The number of thread screens in the stack (shown or covered). */
    fun threadScreens(): Int {
        val container = activity.findViewById<ViewGroup>(R.id.dw_screen_container)
        return (0 until container.childCount).count { container.getChildAt(it).id == R.id.dw_thread_screen }
    }

    /** System back, as a tap on the back key or the end of a back swipe without its preview. */
    fun back() {
        activity.onBackPressedDispatcher.onBackPressed()
        pump()
    }

    /** The predictive back gesture: the screen behind shows while the finger moves, then the step back. */
    fun backGesture() {
        val dispatcher = activity.onBackPressedDispatcher
        dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(10f, 500f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(200f, 500f, 0.6f, androidx.activity.BackEventCompat.EDGE_LEFT))
        idleMain()
        dispatcher.onBackPressed()
        elapse(400)
    }

    /** A configuration change: the Activity is made again with its saved state. */
    fun recreate() {
        checkNotNull(controller).recreate()
        pump()
    }

    fun close() {
        controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        controller = null
        install(null)
        ThreadAdapter.diffExecutor = null
        UiMemory.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun views(root: View): List<View> =
        if (root is ViewGroup) listOf(root) + (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else listOf(root)

    internal companion object {
        const val FRAME_MILLIS = 17L

        /** Puts [session] where `Deskwoot.setup` would, without its overlays and trackers. */
        fun install(session: ChatSession?) {
            val field = Deskwoot::class.java.getDeclaredField("session")
            field.isAccessible = true
            field.set(null, session)
        }
    }
}
