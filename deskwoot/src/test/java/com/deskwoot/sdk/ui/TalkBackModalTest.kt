package com.deskwoot.sdk.ui

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.PopupWindow
import android.widget.TextView
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.ScriptedSession.Companion.helpConfig
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowPopupMenu

/**
 * With TalkBack on, what lies on top is read alone: a Direkt sheet, the
 * image viewer, the emoji panel and the attachment menu hide what lies
 * beneath while they are open, take TalkBack's focus when they open and
 * give it back to the control that opened them when they close.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TalkBackModalTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
        val accessibility = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(accessibility).setEnabled(true)
        shadowOf(accessibility).setTouchExplorationEnabled(true)
    }

    @After
    fun tearDown() {
        controllers.forEach { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
        UiMemory.clear()
    }

    private fun open(entry: DeskwootActivity.Entry = DeskwootActivity.Entry.Start): DeskwootActivity {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry))
        controllers += controller
        controller.setup()
        idle()
        return controller.get()
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    /** The view with [id] that shows on screen. */
    private fun DeskwootActivity.shown(id: Int): View =
        all(window.decorView).single { it.id == id && it.isShown }

    /** The views with [id] that TalkBack reads (shown and not hidden by an ancestor). */
    private fun DeskwootActivity.readable(id: Int): List<View> =
        all(window.decorView).filter { it.id == id && it.isShown && it.isImportantForAccessibility }

    private val accessibility get() = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    /**
     * Where TalkBack's focus moved, in order, by the class and the words of
     * each view (Robolectric has no window surface, so each frame drops the
     * focus a view holds; the events are what TalkBack hears).
     */
    private fun focusMoves(): List<String> = shadowOf(accessibility).sentAccessibilityEvents
        .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED }
        .map { "${it.className}: ${it.contentDescription ?: it.text.joinToString(" ")}" }

    private fun words(view: View): String = "${view.accessibilityClassName}: ${view.contentDescription ?: (view as? TextView)?.text}"

    private fun assertFocusMovedTo(view: View, message: String) = assertEquals(message, words(view), focusMoves().lastOrNull())

    private fun focus(view: View) {
        assertTrue("TalkBack's focus can be put on ${view.javaClass.simpleName}", view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(view.isAccessibilityFocused)
    }

    @Test
    fun `a Direkt sheet hides the chat beneath, takes the focus, and gives it back to its opener`() {
        ScriptedSession(config = helpConfig("widgetDesign" to "direkt")).install()
        val a = open()
        val help = a.shown(R.id.dw_header_help)
        focus(help)
        assertEquals(1, a.readable(R.id.dw_composer_input).size)

        help.performClick()
        idle()
        val sheet = a.shown(R.id.dw_sheet)
        assertTrue("the chat beneath is not read", a.readable(R.id.dw_composer_input).isEmpty())
        assertTrue("the chat beneath is not read", a.readable(R.id.dw_header_help).isEmpty())
        assertTrue("the sheet is read", sheet.isImportantForAccessibility)
        assertFocusMovedTo(sheet.findViewById(R.id.dw_header_title), "the sheet's title has the focus")

        // A sheet over the sheet: the first sheet is hidden too, and the focus comes back to its row.
        val row = all(sheet).first { it.id == R.id.dw_collection_row && it.isShown }
        focus(row)
        row.performClick()
        idle()
        assertTrue("the help sheet beneath is not read", a.readable(R.id.dw_collection_row).isEmpty())
        assertFocusMovedTo(all(a.window.decorView).last { it.id == R.id.dw_header_title && it.isShown }, "the collection's title has the focus")
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertFocusMovedTo(row, "back on the row that opened the collection")

        // The sheet closes: the chat is read again and its Help button has the focus.
        a.shown(R.id.dw_sheet).findViewById<View>(R.id.dw_close).performClick()
        idle()
        assertEquals(1, a.readable(R.id.dw_composer_input).size)
        assertFocusMovedTo(help, "back on Help")
    }

    @Test
    fun `the image viewer takes the focus and gives it back`() {
        ScriptedSession().install()
        val a = open()
        val opener = a.shown(R.id.dw_write_to_us)
        focus(opener)
        val messenger = DeskwootActivity::class.java.getDeclaredField("messenger").apply { isAccessible = true }.get(a) as Messenger
        messenger.ui.nav.showImage("att_1", "/files/a.png", "a.png")
        idle()
        assertTrue("the messenger beneath is not read", a.readable(R.id.dw_write_to_us).isEmpty())
        assertFocusMovedTo(a.shown(R.id.dw_viewer_image), "the picture has the focus")
        a.shown(R.id.dw_viewer_close).performClick()
        idle()
        assertEquals(1, a.readable(R.id.dw_write_to_us).size)
        assertFocusMovedTo(opener, "back on the control that opened it")
    }

    @Test
    fun `the emoji panel is modal for TalkBack and gives the focus back to its button`() {
        ScriptedSession(config = helpConfig("featureEmojiPicker" to true)).install()
        val a = open(DeskwootActivity.Entry.New(null))
        val button = a.shown(R.id.dw_composer_emoji)
        focus(button)
        button.performClick()
        idle()
        val popup = checkNotNull(shadowOf(app).latestPopupWindow) as PopupWindow
        assertTrue(popup.isShowing)
        assertTrue("the messenger beneath is not read", a.readable(R.id.dw_composer_input).isEmpty())
        assertTrue("the messenger beneath is not read", a.readable(R.id.dw_composer_emoji).isEmpty())
        val first = (popup.contentView as ViewGroup).getChildAt(0)
        assertEquals(R.id.dw_emoji, first.id)
        assertFocusMovedTo(first, "the first emoji has the focus")

        // Back closes it.
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(popup.isShowing)
        assertEquals(1, a.readable(R.id.dw_composer_input).size)
        assertFocusMovedTo(button, "back on the emoji button")
    }

    @Test
    fun `the attachment menu hides the messenger while it is open and gives the focus back to the attach button`() {
        ScriptedSession().install()
        val a = open(DeskwootActivity.Entry.New(null))
        val attach = a.shown(R.id.dw_composer_attach)
        focus(attach)
        attach.performClick()
        idle()
        val menu = ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull(menu)
        assertTrue("the messenger beneath is not read", a.readable(R.id.dw_composer_input).isEmpty())
        menu.dismiss()
        idle()
        assertEquals(1, a.readable(R.id.dw_composer_input).size)
        assertFocusMovedTo(attach, "back on the attach button")
    }
}
