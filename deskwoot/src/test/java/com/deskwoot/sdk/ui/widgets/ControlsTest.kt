package com.deskwoot.sdk.ui.widgets

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.deskwoot.sdk.R
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.ui.KlartextDesign
import com.deskwoot.sdk.ui.MessengerNavigation
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.SessionActions
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.files.AttachSource
import com.deskwoot.sdk.ui.files.Picked
import com.deskwoot.sdk.ui.image.ImageLoader
import com.deskwoot.sdk.ui.text.Strings
import com.deskwoot.sdk.ui.text.UiLanguage
import com.deskwoot.sdk.ui.theme.Palette
import com.deskwoot.sdk.ui.theme.UiColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Edges for brand fills without contrast and touch heights. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlsTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun ui(brand: String, dark: Boolean = false): Ui {
        val config = checkNotNull(WidgetConfig.parse(baseConfig("brandColor" to brand).toString()))
        val session = ChatSession.create(activity, "wt_test", null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return Ui(
            session = session,
            actions = SessionActions(session, scope),
            strings = Strings(activity, UiLanguage.EN, false, if (dark) R.style.dw_messenger_theme_dark else R.style.dw_messenger_theme_light),
            colors = UiColors(config.brandColor, dark),
            design = KlartextDesign,
            images = ImageLoader.get(activity),
            scope = scope,
            nav = NoNav,
            config = config,
        )
    }

    private object NoNav : MessengerNavigation {
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
        override fun pick(source: AttachSource, allowedTypes: List<String>, from: com.deskwoot.sdk.ui.Route) = Unit
    }

    /** The stroke of a shape as the framework keeps it (-1 or 0: none). */
    private fun strokeWidth(drawable: GradientDrawable): Int {
        val state = GradientDrawable::class.java.getDeclaredField("mGradientState").apply { isAccessible = true }.get(drawable)
        return state.javaClass.getDeclaredField("mStrokeWidth").apply { isAccessible = true }.getInt(state)
    }

    private fun light(brand: String) = UiColors.needsEdge(Palette.brandPalette(brand, false).fill, false)

    private fun dark(brand: String) = UiColors.needsEdge(Palette.brandPalette(brand, true).fill, true)

    @Test
    fun `brand fills under 3 to 1 against the panel get an edge, in light and in dark`() {
        assertTrue("white on white", light("#ffffff"))
        assertTrue("yellow on white", light("#ffe600"))
        assertTrue(light("#facc15"))
        assertTrue("a light green, 2.3 to 1", light("#22c55e"))
        assertFalse("a darker green, 3.3 to 1", light("#16a34a"))
        assertFalse(light("#2563eb"))
        assertFalse(light("#000000"))

        assertTrue("black in dark", dark("#000000"))
        assertTrue("a navy brand in dark", dark("#112233"))
        assertTrue("a slate brand, 2.99 to 1", dark("#4b5563"))
        assertFalse(dark("#2563eb"))
        assertFalse(dark("#ffffff"))
        assertFalse(dark("#ffe600"))

        // The edge is the fill darkened (light) or lightened (dark) until it shows: the web's edgeColor (EdgeColorTest).
        assertEquals(com.deskwoot.sdk.ui.theme.Palette.argb("#8c8c8c"), UiColors("#ffffff", false).fillEdge)
        assertEquals(com.deskwoot.sdk.ui.theme.Palette.argb("#727272"), UiColors("#000000", true).fillEdge)
        assertNull(UiColors("#2563eb", false).fillEdge)
        assertNull(UiColors("#2563eb", true).fillEdge)
    }

    @Test
    fun `the primary button and the visitor's shapes take the edge only when they need it`() {
        val white = PrimaryButton(activity, ui("#ffffff"), "Start")
        val whiteFill = (white.background as RippleDrawable).getDrawable(0) as GradientDrawable
        assertEquals(activity.dp(1), strokeWidth(whiteFill))
        val blue = PrimaryButton(activity, ui("#2563eb"), "Start")
        val blueFill = (blue.background as RippleDrawable).getDrawable(0) as GradientDrawable
        assertTrue(strokeWidth(blueFill) <= 0)

        assertEquals(activity.dp(1), strokeWidth(activity.oval(0).edged(activity, ui("#000000", dark = true).colors)))
        assertTrue(strokeWidth(activity.oval(0).edged(activity, ui("#000000").colors)) <= 0)
    }

    @Test
    fun `segments and Powered by take 48 to the finger and keep their look`() {
        val ui = ui("#2563eb")
        val segmented = Segmented(activity, ui, listOf("Conversation", "Help"), listOf(R.id.dw_tab_conversation, R.id.dw_tab_help)) { }
        segmented.select(0)
        segmented.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(activity.dp(48), segmented.measuredHeight)
        val tab = segmented.findViewById<TextView>(R.id.dw_tab_conversation)
        assertEquals(activity.dp(48), tab.layoutParams.height)
        // The track still shows 42 high, the selected pill 36.
        val track = segmented.background as android.graphics.drawable.InsetDrawable
        track.setBounds(0, 0, 100, activity.dp(48))
        assertEquals(activity.dp(42), checkNotNull(track.drawable).bounds.height())
        val pill = tab.background as android.graphics.drawable.InsetDrawable
        pill.setBounds(0, 0, 100, activity.dp(48))
        assertEquals(activity.dp(36), checkNotNull(pill.drawable).bounds.height())

        val powered = PoweredBy(activity, ui)
        powered.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(powered.measuredHeight >= activity.dp(48))
        assertEquals(activity.dp(10), powered.paddingTop)
    }
}
