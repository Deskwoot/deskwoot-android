package com.deskwoot.sdk.ui

import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * A phone in landscape with its camera cutout on the left and the
 * navigation bar on the right: no header, thread, composer, form, tab bar,
 * sheet, loading state or viewer control reaches under either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CutoutInsetsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()

    /** Landscape: 2400 by 1080 px, a cutout of 110 on the left, the navigation bar of 126 on the right, the status bar 63. */
    private val width = 2400
    private val height = 1080
    private val cutout = 110
    private val navigation = 126
    private val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(cutout, 0, 0, 0))
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 63, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, navigation, 0))
        .build()

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
    }

    @After
    fun tearDown() {
        controllers.forEach { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
        UiMemory.clear()
    }

    private fun open(entry: DeskwootActivity.Entry): DeskwootActivity {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry))
        controllers += controller
        controller.setup()
        idle()
        return controller.get()
    }

    /** The insets reach the messenger and it is laid out at the landscape size. */
    private fun landscape(a: DeskwootActivity): ViewGroup {
        val root = a.findViewById<ViewGroup>(R.id.dw_messenger_root)
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        return root
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    /** Every view on screen with [ids] (all of them must be there) lies between the cutout and the navigation bar. */
    private fun assertClear(a: DeskwootActivity, vararg ids: Int) {
        val root = landscape(a)
        for (id in ids) {
            val views = all(root).filter { it.id == id && it.isShown }
            assertTrue("${app.resources.getResourceEntryName(id)} is on screen", views.isNotEmpty())
        }
        // Every view on screen, not only the named ones.
        for (v in all(root).filter { it.isShown && it.width > 0 && it !== root }) {
            val r = Rect(0, 0, v.width, v.height)
            root.offsetDescendantRectToMyCoords(v, r)
            val name = if (v.id != View.NO_ID) app.resources.getResourceEntryName(v.id) else v.javaClass.simpleName
            assertTrue("$name starts at ${r.left}, under the cutout", r.left >= cutout)
            assertTrue("$name ends at ${r.right}, under the navigation bar", r.right <= width - navigation)
        }
    }

    @Test
    fun `Klartext's start page, thread and composer keep clear of the cutout and the navigation bar`() {
        ScriptedSession().install()
        val a = open(DeskwootActivity.Entry.Start)
        assertClear(a, R.id.dw_start_screen, R.id.dw_write_to_us)
        a.findViewById<View>(R.id.dw_write_to_us).performClick()
        idle()
        assertClear(a, R.id.dw_header, R.id.dw_thread_list, R.id.dw_composer_input)
    }

    @Test
    fun `the form before the chat keeps clear`() {
        val fields = listOf(mapOf("name" to "name", "type" to "text", "label" to "Name", "required" to true))
        ScriptedSession(config = helpConfig("preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to fields))).install()
        val a = open(DeskwootActivity.Entry.New(null))
        assertClear(a, R.id.dw_gate_field)
    }

    @Test
    fun `Empfang's start page and its tab bar keep clear`() {
        ScriptedSession(config = helpConfig("widgetDesign" to "empfang")).install()
        val a = open(DeskwootActivity.Entry.Start)
        assertClear(a, R.id.dw_tab_bar, R.id.dw_write_to_us)
    }

    @Test
    fun `Direkt's chat and its sheets keep clear`() {
        ScriptedSession(config = helpConfig("widgetDesign" to "direkt")).install()
        val a = open(DeskwootActivity.Entry.Start)
        assertClear(a, R.id.dw_header, R.id.dw_composer_input)
        a.findViewById<View>(R.id.dw_header_help).performClick()
        idle()
        assertClear(a, R.id.dw_sheet, R.id.dw_collection_row)
    }

    @Test
    fun `the loading state keeps clear`() {
        val slow = ScriptedSession()
        slow.http.on("GET /api/widget/config") { kotlinx.coroutines.awaitCancellation() }
        slow.install()
        val a = open(DeskwootActivity.Entry.Start)
        assertClear(a, R.id.dw_loading, R.id.dw_close)
    }

    @Test
    fun `the image viewer's controls keep clear, the picture may use the whole screen`() {
        ScriptedSession().install()
        val a = open(DeskwootActivity.Entry.Start)
        val messenger = DeskwootActivity::class.java.getDeclaredField("messenger").apply { isAccessible = true }.get(a) as Messenger
        messenger.ui.nav.showImage("att_1", "/files/a.png", "a.png")
        idle()
        val viewer = a.findViewById<ViewGroup>(R.id.dw_image_viewer)
        ViewCompat.dispatchApplyWindowInsets(viewer, insets)
        viewer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        viewer.layout(0, 0, width, height)
        for (id in listOf(R.id.dw_viewer_close, R.id.dw_viewer_share)) {
            val control = viewer.findViewById<View>(id)
            val r = Rect(0, 0, control.width, control.height)
            viewer.offsetDescendantRectToMyCoords(control, r)
            assertTrue("${app.resources.getResourceEntryName(id)} at ${r.left}", r.left >= cutout && r.right <= width - navigation)
        }
        assertEquals("the picture fills the screen", width, viewer.findViewById<View>(R.id.dw_viewer_image).width)
    }
}
