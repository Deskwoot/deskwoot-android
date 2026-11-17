package com.deskwoot.sdk.ui

import android.content.pm.ApplicationInfo
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.ScriptedSession.Companion.helpConfig
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.ScriptedSession.Companion.summary
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
 * Arabic reads right to left on every surface of the messenger in each
 * look: the screens, Direkt's sheets and article tray, Empfang's tab bar,
 * the image viewer over the window and the loading state before the config.
 * (The card and the launcher over the app: HostOverlaysTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RightToLeftTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()
    private var flags = 0

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
        // An app that declares android:supportsRtl, as Android asks of every app that shows right to left.
        flags = app.applicationInfo.flags
        app.applicationInfo.flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
    }

    @After
    fun tearDown() {
        controllers.forEach { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        app.applicationInfo.flags = flags
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
        UiMemory.clear()
    }

    private fun arabic(design: String, vararg overrides: Pair<String, Any?>): ScriptedSession {
        val s = ScriptedSession(config = helpConfig("widgetDesign" to design, *overrides))
        s.session.setLanguage("ar")
        return s.install()
    }

    private fun open(entry: DeskwootActivity.Entry): DeskwootActivity {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry))
        controllers += controller
        controller.setup()
        idle()
        return controller.get()
    }

    /** The views with [id] that are on screen. */
    private fun DeskwootActivity.shown(id: Int): List<View> {
        fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
        return all(window.decorView).filter { it.id == id && it.isShown }
    }

    private fun View.rtl(): Boolean = layoutDirection == View.LAYOUT_DIRECTION_RTL

    @Test
    fun `Klartext's screens read right to left`() {
        arabic("klartext")
        val a = open(DeskwootActivity.Entry.Start)
        assertTrue(a.shown(R.id.dw_start_screen).single().rtl())
        a.shown(R.id.dw_write_to_us).single().performClick()
        idle()
        assertTrue(a.shown(R.id.dw_thread_screen).single().rtl())
        assertTrue(a.shown(R.id.dw_composer_input).single().rtl())
    }

    @Test
    fun `Direkt's chat, its sheets and its article tray read right to left`() {
        val s = arabic("direkt")
        s.searchHits = listOf(summary("refunds", "Refunds"))
        val a = open(DeskwootActivity.Entry.Start)
        assertTrue(a.shown(R.id.dw_header).single().rtl())
        // The tray under the composer, while a new visitor types.
        a.shown(R.id.dw_composer_input).single().let { (it as EditText).text.append("Rückerstattung") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        idle()
        assertTrue(idleUntil { a.shown(R.id.dw_article_tray).isNotEmpty() })
        assertTrue(a.shown(R.id.dw_article_tray).single().rtl())
        // The help as a sheet over the chat, and the article over it.
        a.shown(R.id.dw_header_help).single().performClick()
        idle()
        val sheet = a.shown(R.id.dw_sheet).single()
        assertTrue(sheet.rtl())
        assertTrue(a.shown(R.id.dw_collection_row).first().rtl())
        a.shown(R.id.dw_collection_row).first().performClick()
        idle()
        assertTrue(a.shown(R.id.dw_sheet).single().rtl())
        assertTrue(a.shown(R.id.dw_collection_screen).single().rtl())
        // The list as a sheet.
        a.onBackPressedDispatcher.onBackPressed()
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        a.shown(R.id.dw_header_conversations).single().performClick()
        idle()
        assertTrue(a.shown(R.id.dw_sheet).single().rtl())
    }

    @Test
    fun `Empfang's tab bar reads right to left, the conversation tab first on the right`() {
        arabic("empfang")
        val a = open(DeskwootActivity.Entry.Start)
        val bar = a.shown(R.id.dw_tab_bar).single()
        assertTrue(bar.rtl())
        val conversation = bar.findViewById<View>(R.id.dw_tab_conversation)
        val help = bar.findViewById<View>(R.id.dw_tab_help)
        assertTrue("laid out", bar.width > 0)
        assertTrue("the first tab sits on the right", conversation.left > help.left)
    }

    @Test
    fun `the image viewer over the window reads right to left, its close on the left`() {
        arabic("klartext")
        val a = open(DeskwootActivity.Entry.Start)
        val messenger = DeskwootActivity::class.java.getDeclaredField("messenger").apply { isAccessible = true }.get(a) as Messenger
        messenger.ui.nav.showImage("att_1", "/files/a.png", "a.png")
        idle()
        val viewer = a.shown(R.id.dw_image_viewer).single()
        assertTrue(viewer.rtl())
        val close = viewer.findViewById<View>(R.id.dw_viewer_close)
        val share = viewer.findViewById<View>(R.id.dw_viewer_share)
        assertTrue("laid out", viewer.width > 0)
        assertTrue("the close control at the start, on the left", (close.parent as View).left + close.left < (share.parent as View).left + share.left)
    }

    @Test
    fun `the loading state before the config reads right to left`() {
        val slow = ScriptedSession()
        slow.http.on("GET /api/widget/config") { kotlinx.coroutines.awaitCancellation() }
        slow.session.setLanguage("ar")
        slow.install()
        val a = open(DeskwootActivity.Entry.Start)
        val loading = a.shown(R.id.dw_loading).single()
        assertTrue((loading.parent.parent as View).rtl())
    }

    @Test
    fun `an app without supportsRtl keeps every view left to right, as Android does for all its views`() {
        app.applicationInfo.flags = flags and ApplicationInfo.FLAG_SUPPORTS_RTL.inv()
        arabic("klartext")
        val a = open(DeskwootActivity.Entry.Start)
        assertEquals(View.LAYOUT_DIRECTION_LTR, a.shown(R.id.dw_start_screen).single().layoutDirection)
    }
}
