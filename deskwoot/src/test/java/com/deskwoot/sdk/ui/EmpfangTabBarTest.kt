package com.deskwoot.sdk.ui

import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.ScriptedSession.Companion.helpConfig
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.text.UiLanguage
import com.deskwoot.sdk.ui.theme.UiColors
import com.deskwoot.sdk.ui.widgets.TAB_BAR_CLASS
import com.deskwoot.sdk.ui.widgets.TAB_CLASS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Empfang's tab bar follows the language, light and dark and the help
 * center switched on or off while the messenger is open, and TalkBack hears
 * it as a tab bar of 2 tabs with the current one selected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmpfangTabBarTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()
    private lateinit var s: ScriptedSession

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
        s = ScriptedSession(config = helpConfig("widgetDesign" to "empfang")).install()
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

    private fun bar(a: DeskwootActivity): ViewGroup? = a.findViewById<ViewGroup>(R.id.dw_tab_bar)?.takeIf { it.isShown }

    /** The label under a tab's icon. */
    private fun label(tab: View): TextView = (tab as ViewGroup).getChildAt(1) as TextView

    @Test
    fun `the tab bar takes a new language, a help center switched off and on, and light or dark`() {
        val controller = open()
        val a = controller.get()
        val english = checkNotNull(bar(a))
        assertEquals(FileTexts(UiLanguage.EN).t(R.string.dw_tab_conversation), label(english.findViewById(R.id.dw_tab_conversation)).text.toString())
        assertEquals(FileTexts(UiLanguage.EN).t(R.string.dw_help), label(english.findViewById(R.id.dw_tab_help)).text.toString())

        s.session.setLanguage("de")
        idle()
        val german = checkNotNull(bar(a))
        assertTrue(german !== english)
        assertEquals(FileTexts(UiLanguage.DE).t(R.string.dw_tab_conversation), label(german.findViewById(R.id.dw_tab_conversation)).text.toString())
        assertEquals(FileTexts(UiLanguage.DE).t(R.string.dw_help), label(german.findViewById(R.id.dw_tab_help)).text.toString())
        assertEquals(FileTexts(UiLanguage.DE).t(R.string.dw_help), german.findViewById<View>(R.id.dw_tab_help).contentDescription)

        s.config = helpConfig("widgetDesign" to "empfang", "helpCenterEnabled" to false)
        s.session.refresh()
        idle()
        assertNull("no help center, no tabs", bar(a))
        s.config = helpConfig("widgetDesign" to "empfang")
        s.session.refresh()
        idle()
        assertNotNull("the help center back on, the tabs too", bar(a))

        val unselectedLight = label(checkNotNull(bar(a)).findViewById(R.id.dw_tab_help)).currentTextColor
        assertEquals(UiColors(null, false).ink3, unselectedLight)
        Deskwoot.theme = Deskwoot.Theme.DARK
        idle()
        val dark = controller.get()
        assertTrue("made again in dark", dark !== a)
        assertEquals(UiColors(null, true).ink3, label(checkNotNull(bar(dark)).findViewById(R.id.dw_tab_help)).currentTextColor)
    }

    @Test
    fun `TalkBack hears a tab bar of 2 tabs and which one is selected`() {
        val a = open().get()
        fun info(v: View): AccessibilityNodeInfo = v.createAccessibilityNodeInfo()
        val bar = checkNotNull(bar(a))
        val barInfo = info(bar)
        assertEquals(TAB_BAR_CLASS, barInfo.className)
        assertEquals(1, barInfo.collectionInfo.rowCount)
        assertEquals(2, barInfo.collectionInfo.columnCount)
        assertEquals(AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE, barInfo.collectionInfo.selectionMode)

        val conversation = info(bar.findViewById(R.id.dw_tab_conversation))
        assertEquals(TAB_CLASS, conversation.className)
        assertTrue(conversation.isSelected)
        assertEquals(0, conversation.collectionItemInfo.columnIndex)
        assertTrue(conversation.collectionItemInfo.isSelected)
        val help = info(bar.findViewById(R.id.dw_tab_help))
        assertEquals(TAB_CLASS, help.className)
        assertFalse(help.isSelected)
        assertEquals(1, help.collectionItemInfo.columnIndex)
        assertFalse(help.collectionItemInfo.isSelected)

        // On the help, the help tab is the selected one.
        bar.findViewById<View>(R.id.dw_tab_help).performClick()
        idle()
        val onHelp = checkNotNull(bar(a))
        assertTrue(info(onHelp.findViewById(R.id.dw_tab_help)).isSelected)
        assertFalse(info(onHelp.findViewById(R.id.dw_tab_conversation)).isSelected)
    }
}
