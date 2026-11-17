package com.deskwoot.sdk.ui.notify

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.deskwoot.sdk.R
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.ui.FileTexts
import com.deskwoot.sdk.ui.text.UiLanguage
import com.deskwoot.sdk.ui.widgets.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.launch

/** A checkout screen the app keeps the overlays off. */
class CheckoutActivity : ComponentActivity()

/** An Activity the app forces into dark, the way AppCompat's local night mode does it (a configuration of its own). */
class ForcedNightActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val night = Configuration(newBase.resources.configuration)
        night.uiMode = (night.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        super.attachBaseContext(newBase.createConfigurationContext(night))
    }
}

/** The card and the launcher over the app's screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostOverlaysTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity: ComponentActivity = controller.get()
    private val session = ChatSession.create(activity, "wt_test", null)
    private val overlays = HostOverlays { session }

    @After
    fun reset() {
        HostOverlays.excluded.clear()
        HostOverlays.launcherBottomPadding = 0
    }

    private fun config(vararg overrides: Pair<String, Any?>): WidgetConfig = checkNotNull(WidgetConfig.parse(baseConfig(*overrides).toString()))

    private fun state(config: WidgetConfig = config(), unread: Int = 0, locale: String = "en"): SessionState =
        SessionState.initial(locale).copy(phase = Phase.READY, config = config, unreadCount = unread)

    private fun launcher(): View? = activity.findViewById(R.id.dw_launcher)

    /** The launcher on the test Activity, its window focused. */
    private fun showLauncher(state: SessionState = state()): View {
        overlays.onActivityResumed(activity)
        overlays.launcherVisible = true
        overlays.onState(state)
        controller.windowFocusChanged(true)
        return checkNotNull(launcher()) { "the launcher shows" }
    }

    @Test
    fun `an error in the work of the card and the launcher never reaches the app's crash handler`() {
        val reached = ArrayList<Throwable>()
        val thread = Thread.currentThread()
        val before = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> reached += error }
        try {
            overlays.scope.launch { error("a broken reply") }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            var later = false
            overlays.scope.launch { later = true }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("the scope keeps working", later)
        } finally {
            thread.uncaughtExceptionHandler = before
        }
        assertEquals(emptyList<Throwable>(), reached)
    }

    @Test
    fun `never over translucent or floating activities, nor over ones the app excluded`() {
        assertTrue(HostOverlays.acceptsOverlays(activity))
        val translucent = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        translucent.setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        assertFalse(HostOverlays.acceptsOverlays(translucent))
        val dialog = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        dialog.setTheme(android.R.style.Theme_DeviceDefault_Dialog)
        assertFalse(HostOverlays.acceptsOverlays(dialog))

        val checkout = Robolectric.buildActivity(CheckoutActivity::class.java).setup().get()
        assertTrue(HostOverlays.acceptsOverlays(checkout))
        HostOverlays.excluded.add(CheckoutActivity::class.java)
        assertFalse(HostOverlays.acceptsOverlays(checkout))
        assertTrue("other activities keep them", HostOverlays.acceptsOverlays(activity))

        // A translucent screen in front of the app (the app's screen paused below it) gets no launcher.
        overlays.launcherVisible = true
        overlays.onState(state())
        overlays.onActivityResumed(translucent)
        overlays.onState(state(unread = 1))
        assertNull(overlays.currentActivity)
        assertNull(translucent.findViewById<View>(R.id.dw_launcher))
    }

    @Test
    fun `a reply is news for the card only when it is unknown and newer than the newest known one`() {
        fun reply(id: String, at: Long) = checkNotNull(com.deskwoot.sdk.model.Message.parse(com.deskwoot.sdk.row(id, content = "Reply $id", at = at, conversationId = "c$id", sender = com.deskwoot.sdk.json("name" to "Priya"))))
        fun summary(id: String, at: Long) = com.deskwoot.sdk.model.ConversationSummary("c$id", com.deskwoot.sdk.model.ConversationStatus.OPEN, at * 1000, reply(id, at), unread = 1)
        overlays.onActivityResumed(activity)
        overlays.onState(state().copy(conversations = listOf(summary("a", 100))))
        // An older reply that shows up late (a reloaded list) raises no card, even with the count going up.
        overlays.onState(state(unread = 2).copy(conversations = listOf(summary("a", 100), summary("b", 50))))
        assertNull(activity.findViewById<View>(R.id.dw_in_app_card))
        // A newer one does.
        overlays.onState(state(unread = 3).copy(conversations = listOf(summary("c", 200), summary("a", 100), summary("b", 50))))
        assertTrue(activity.findViewById<View>(R.id.dw_in_app_card) != null)
    }

    @Test
    fun `excluding the Activity in front takes the launcher off at once`() {
        showLauncher()
        HostOverlays.excluded.add(ComponentActivity::class.java)
        overlays.recheck()
        assertNull(launcher())
        assertNull(overlays.currentActivity)
    }

    @Test
    fun `an app that sets its content again gets the launcher back`() {
        showLauncher()
        activity.setContentView(FrameLayout(activity))
        assertNull(launcher())
        overlays.onState(state(unread = 1))
        assertNotNull(launcher())
    }

    @Test
    fun `the launcher lifts by the app's bottom padding`() {
        val view = showLauncher()
        val before = (view.layoutParams as FrameLayout.LayoutParams).bottomMargin
        HostOverlays.launcherBottomPadding = 56
        overlays.relayout()
        assertEquals(activity.dp(56), (view.layoutParams as FrameLayout.LayoutParams).bottomMargin - before)
    }

    @Test
    fun `the launcher steps aside while something covers the app's window`() {
        val view = showLauncher()
        assertEquals(View.VISIBLE, view.visibility)
        // A dialog, a sheet or a menu of the app takes the window's focus.
        controller.windowFocusChanged(false)
        assertEquals(View.INVISIBLE, view.visibility)
        controller.windowFocusChanged(true)
        assertEquals(View.VISIBLE, view.visibility)
    }

    @Test
    fun `the launcher says its unread count with the language's plural`() {
        val view = showLauncher(state(unread = 1, locale = "de"))
        val label = FileTexts(UiLanguage.DE).t(R.string.dw_launcher_default)
        assertEquals("$label, 1 neue Nachricht", view.contentDescription)
        overlays.onState(state(unread = 3, locale = "de"))
        assertEquals("$label, 3 neue Nachrichten", launcher()?.contentDescription)
        overlays.onState(state(unread = 0, locale = "de"))
        assertEquals(label, launcher()?.contentDescription)
        // The owner's launcher text names it.
        overlays.onState(state(config("launcherTitle" to "Fragen?"), unread = 2, locale = "de"))
        assertEquals("Fragen?, 2 neue Nachrichten", launcher()?.contentDescription)
        // Arabic has a dual and its own forms for 3 to 10 and 11 to 99 (the digits are the locale's).
        overlays.onState(state(unread = 2, locale = "ar"))
        assertTrue(checkNotNull(launcher()?.contentDescription).endsWith("رسالتان جديدتان"))
        overlays.onState(state(unread = 5, locale = "ar"))
        assertTrue(checkNotNull(launcher()?.contentDescription).endsWith(" رسائل جديدة"))
        overlays.onState(state(unread = 12, locale = "ar"))
        assertTrue(checkNotNull(launcher()?.contentDescription).endsWith(" رسالة جديدة"))
        // One, and from 100 on the singular again; 103 counts as a few.
        overlays.onState(state(unread = 1, locale = "ar"))
        assertTrue(checkNotNull(launcher()?.contentDescription).endsWith(", رسالة جديدة واحدة"))
        overlays.onState(state(unread = 100, locale = "ar"))
        assertTrue(checkNotNull(launcher()?.contentDescription).endsWith(" رسالة جديدة"))
        overlays.onState(state(unread = 103, locale = "ar"))
        assertTrue(checkNotNull(launcher()?.contentDescription).endsWith(" رسائل جديدة"))
    }

    @Test
    fun `a new brand color, language or light and dark draws the launcher again, the same look keeps it`() {
        val first = showLauncher()
        overlays.onState(state(unread = 2))
        assertSame("no new look, no new view", first, launcher())

        overlays.onState(state(config("brandColor" to "#ffe600"), unread = 2))
        val yellow = checkNotNull(launcher())
        assertNotSame(first, yellow)

        overlays.onState(state(config("brandColor" to "#ffe600"), unread = 2, locale = "es"))
        val spanish = checkNotNull(launcher())
        assertNotSame(yellow, spanish)
        assertTrue(spanish.contentDescription.startsWith(FileTexts(UiLanguage.ES).t(R.string.dw_launcher_default)))

        overlays.onState(state(config("brandColor" to "#ffe600", "widgetPosition" to "left"), unread = 2, locale = "es"))
        val left = checkNotNull(launcher())
        assertNotSame(spanish, left)
        // The inbox's side is a side of the screen, as on the website: left stays left in any direction.
        assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.LEFT, (left.layoutParams as FrameLayout.LayoutParams).gravity)
    }

    @Test
    fun `in landscape the card and the launcher keep clear of a cutout at the side`() {
        val content = activity.findViewById<View>(android.R.id.content)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("laid out", content.width > 0)
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(110, 0, 0, 0))
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 126, 0))
            .build()
        assertEquals(HostOverlays.Edges(110, 126, 0), HostOverlays.overlapOf(content, insets))
    }

    @Test
    fun `over an app in English the card and the launcher read right to left in an Arabic chat`() {
        val flags = activity.applicationInfo.flags
        activity.applicationInfo.flags = flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        try {
            fun reply(id: String, at: Long) = checkNotNull(com.deskwoot.sdk.model.Message.parse(com.deskwoot.sdk.row(id, content = "رد $id", at = at, conversationId = "c$id", sender = com.deskwoot.sdk.json("name" to "Priya"))))
            fun summary(id: String, at: Long) = com.deskwoot.sdk.model.ConversationSummary("c$id", com.deskwoot.sdk.model.ConversationStatus.OPEN, at * 1000, reply(id, at), unread = 1)
            val launcher = showLauncher(state(unread = 1, locale = "ar").copy(conversations = listOf(summary("a", 100))))
            assertEquals("the app stays left to right", View.LAYOUT_DIRECTION_LTR, activity.window.decorView.layoutDirection)
            assertEquals(View.LAYOUT_DIRECTION_RTL, launcher.layoutDirection)
            // The inbox's side stays the screen's right.
            assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.RIGHT, (launcher.layoutParams as FrameLayout.LayoutParams).gravity)
            overlays.onState(state(unread = 2, locale = "ar").copy(conversations = listOf(summary("b", 200), summary("a", 100))))
            val card = checkNotNull(activity.findViewById<View>(R.id.dw_in_app_card))
            assertEquals(View.LAYOUT_DIRECTION_RTL, card.layoutDirection)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("laid out", card.width > 0 && launcher.width > 0)
            val badge = launcher.findViewById<View>(R.id.dw_launcher_badge)
            assertTrue("the badge at the top end, the left", badge.left < launcher.width / 2)
            val close = card.findViewById<View>(R.id.dw_in_app_card_close)
            assertTrue("the card's close at its end, the left", close.left < card.width / 2)
        } finally {
            activity.applicationInfo.flags = flags
        }
    }

    @Test
    fun `the overlays follow the night mode an app forces on its Activity`() {
        val forced = Robolectric.buildActivity(ForcedNightActivity::class.java).setup().get()
        assertFalse("the system is light", activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        assertFalse(HostOverlays.isDark(activity, state()))
        assertTrue(HostOverlays.isDark(forced, state()))
        overlays.onActivityResumed(forced)
        assertNotNull("a forced night mode is no reason to keep the overlays off", overlays.currentActivity)
    }

    @Test
    fun `only what the bars cover of the app's content lifts the overlays`() {
        // A phone 1080 x 2400, status bar 63, navigation bar 126.
        // Not edge to edge (Android 6 to 10 report the raw insets): the content already ends above the navigation bar.
        assertEquals(HostOverlays.Edges(0, 0, 0), HostOverlays.overlap(0, 0, 126, 0, 1080, 2274, 1080, 2400))
        // Edge to edge: the content runs under the bar.
        assertEquals(HostOverlays.Edges(0, 0, 126), HostOverlays.overlap(0, 0, 126, 0, 1080, 2400, 1080, 2400))
        // Landscape with the navigation bar on the right.
        assertEquals(HostOverlays.Edges(0, 0, 0), HostOverlays.overlap(0, 126, 0, 0, 2274, 1080, 2400, 1080))
        assertEquals(HostOverlays.Edges(0, 126, 0), HostOverlays.overlap(0, 126, 0, 0, 2400, 1080, 2400, 1080))
        // The keyboard: an edge to edge app is covered, one that resizes for it is not.
        assertEquals(HostOverlays.Edges(0, 0, 900), HostOverlays.overlap(0, 0, 900, 0, 1080, 2400, 1080, 2400))
        assertEquals(HostOverlays.Edges(0, 0, 0), HostOverlays.overlap(0, 0, 900, 0, 1080, 1500, 1080, 2400))
    }
}
