package com.deskwoot.sdk.ui

import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.ScriptedSession.Companion.helpConfig
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * When a change of the inbox (its look, its texts, its language) reaches an
 * open messenger, as the README says: whenever the inbox's settings load, at
 * every present, every return to the foreground, every Deskwoot push handed
 * to the SDK and every language change; never on a timer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookArrivesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()
    private lateinit var s: ScriptedSession

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
        s = ScriptedSession(config = helpConfig("businessName" to "Acme")).install()
    }

    @After
    fun tearDown() {
        controllers.forEach { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
        UiMemory.clear()
    }

    private fun open(): ActivityController<DeskwootActivity> {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, DeskwootActivity.Entry.Start))
        controllers += controller
        controller.setup()
        idle()
        return controller
    }

    private fun texts(a: DeskwootActivity): List<String> {
        fun all(v: View): List<View> = if (v is android.view.ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
        return all(a.findViewById(R.id.dw_screen_container)).filter { it.isShown }.filterIsInstance<TextView>().map { it.text.toString() }
    }

    private fun configLoads(): Int = s.http.calls("GET /api/widget/config").size

    private fun name(a: DeskwootActivity): String = a.findViewById<TextView>(R.id.dw_business_name).text.toString()

    /** The owner renames the business; the app does nothing yet. */
    private fun rename(to: String) {
        s.config = helpConfig("businessName" to to)
    }

    @Test
    fun `nothing loads the inbox's settings on a timer`() {
        val a = open().get()
        val loads = configLoads()
        rename("Acme Coffee")
        repeat(18) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            idle()
        }
        assertEquals(loads, configLoads())
        assertEquals("Acme", name(a))
    }

    @Test
    fun `a conversation that comes on screen more than a minute after the last load loads them again`() {
        val a = open().get()
        val loads = configLoads()
        a.findViewById<View>(R.id.dw_write_to_us).performClick()
        idle()
        assertEquals("within the minute nothing loads", loads, configLoads())
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        idle()
        assertEquals(loads, configLoads())
        a.findViewById<View>(R.id.dw_write_to_us).performClick()
        idle()
        assertEquals(loads + 1, configLoads())
    }

    @Test
    fun `a present, the foreground, a push and a language change bring a new look to the open messenger`() {
        val controller = open()
        val a = controller.get()
        assertEquals("Acme", name(a))

        rename("Acme Coffee")
        Deskwoot.present(a)
        controller.newIntent(checkNotNull(shadowOf(a).nextStartedActivity))
        idle()
        assertEquals("a present while it is open", "Acme Coffee", name(a))

        rename("Acme Tea")
        s.session.setForeground(false)
        s.session.setForeground(true)
        idle()
        assertEquals("back in the foreground", "Acme Tea", name(a))

        rename("Acme Juice")
        assertTrue(Deskwoot.handlePush(app, mapOf("deskwoot" to "1", "widgetToken" to s.token, "conversationId" to "c9", "title" to "Mia", "body" to "Hi")))
        idle()
        assertEquals("a Deskwoot push handed to the SDK", "Acme Juice", name(a))

        val loads = configLoads()
        s.session.setLanguage("de")
        idle()
        assertEquals("a language change loads the settings in that language", loads + 1, configLoads())
        assertTrue("the messenger speaks German", texts(a).contains(FileTexts(com.deskwoot.sdk.ui.text.UiLanguage.DE).t(R.string.dw_write_to_us)))

        // A new present after the messenger closed: the Activity is made again and loads them too.
        rename("Acme Market")
        controller.pause().stop().destroy()
        val again = open().get()
        assertEquals("Acme Market", name(again))
    }
}
