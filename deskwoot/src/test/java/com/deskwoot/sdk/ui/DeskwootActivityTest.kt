package com.deskwoot.sdk.ui

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.DeskwootUser
import com.deskwoot.sdk.R
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.model.VisitorIdentity
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.ui.ScriptedSession.Companion.helpConfig
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import com.deskwoot.sdk.ui.image.ImageViewer
import com.deskwoot.sdk.ui.widgets.dp
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
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import java.lang.reflect.Modifier

/**
 * The messenger's Activity against a real session over a scripted server:
 * back, recreate, process death, insets, the
 * placeholder, a wiped visitor, a new widget token, the theme, the Java
 * surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeskwootActivityTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var scripted: ScriptedSession
    private val controllers = mutableListOf<ActivityController<DeskwootActivity>>()
    private val userA = DeskwootUser("u_a", "a".repeat(64))
    private val userB = DeskwootUser("u_b", "b".repeat(64))
    private val verifyKey = "POST /api/widget/identity/verify"

    @Before
    fun setUp() {
        // Screens move without animations, so a step is done at once.
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        UiMemory.clear()
        Deskwoot.theme = Deskwoot.Theme.LIGHT
        scripted = ScriptedSession().install()
    }

    @After
    fun tearDown() {
        controllers.forEach { c -> runCatching { if (!c.get().isDestroyed) c.pause().stop().destroy() } }
        Deskwoot.theme = Deskwoot.Theme.SYSTEM
        UiMemory.clear()
    }

    private fun open(entry: DeskwootActivity.Entry = DeskwootActivity.Entry.Start, saved: Bundle? = null): ActivityController<DeskwootActivity> {
        val controller = Robolectric.buildActivity(DeskwootActivity::class.java, DeskwootActivity.intent(app, entry))
        controllers += controller
        if (saved == null) controller.setup() else controller.setup(saved)
        idle()
        return controller
    }

    private fun DeskwootActivity.shows(id: Int): Boolean = findViewById<View>(id)?.isShown == true

    @Test
    fun `back steps through the screens and closes the messenger on the start page`() {
        val a = open(DeskwootActivity.Entry.Help).get()
        assertTrue(a.shows(R.id.dw_help_screen))
        a.findViewById<View>(R.id.dw_collection_row).performClick()
        idle()
        assertTrue(a.shows(R.id.dw_collection_screen))

        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(a.shows(R.id.dw_help_screen))
        assertFalse(a.shows(R.id.dw_collection_screen))
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("the help's parent is the start page", a.shows(R.id.dw_start_screen))
        assertFalse(a.isFinishing)
        a.onBackPressedDispatcher.onBackPressed()
        assertTrue("back on the start page closes the messenger", a.isFinishing)
    }

    @Test
    fun `a recreate keeps the screens and the draft`() {
        val controller = open(DeskwootActivity.Entry.New("I have a question about order 1042"))
        val first = controller.get()
        assertTrue(first.shows(R.id.dw_thread_screen))
        assertEquals("I have a question about order 1042", first.findViewById<EditText>(R.id.dw_composer_input).text.toString())

        controller.recreate()
        idle()
        val second = controller.get()
        assertTrue(second !== first)
        assertTrue(second.shows(R.id.dw_thread_screen))
        assertEquals("I have a question about order 1042", second.findViewById<EditText>(R.id.dw_composer_input).text.toString())
        second.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("the start page is still behind the thread", second.shows(R.id.dw_start_screen))
    }

    @Test
    fun `after the process died the routes and the drafts come back from the saved state`() {
        val controller = open(DeskwootActivity.Entry.Help)
        val a = controller.get()
        a.findViewById<View>(R.id.dw_collection_row).performClick()
        idle()
        a.findViewById<View>(R.id.dw_article_row).performClick()
        idle()
        assertTrue(a.shows(R.id.dw_article_screen))
        UiMemory.setDraft(null, "half written")
        val saved = Bundle()
        controller.saveInstanceState(saved)
        controller.pause().stop().destroy()
        // A new process: nothing in memory.
        UiMemory.clear()

        val restored = open(DeskwootActivity.Entry.Start, saved).get()
        assertTrue(restored.shows(R.id.dw_article_screen))
        assertEquals("How to change your plan", restored.findViewById<TextView>(R.id.dw_article_title).text.toString())
        assertEquals("half written", UiMemory.draft(null))
        restored.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("the collection is behind the article again", restored.shows(R.id.dw_collection_screen))
    }

    @Test
    fun `the insets reach the header and the bottom, also of screens made later`() {
        val a = open(DeskwootActivity.Entry.Start).get()
        val root = a.findViewById<View>(R.id.dw_messenger_root)
        val bars = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 60, 0, 40))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, bars)
        assertEquals(40, root.paddingBottom)
        val topBar = a.findViewById<View>(R.id.dw_business_name).parent.parent as View
        assertEquals(60 + a.dp(6), topBar.paddingTop)

        val keyboard = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 60, 0, 40))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 700))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, keyboard)
        assertEquals("the keyboard lifts the bottom", 700, root.paddingBottom)
        ViewCompat.dispatchApplyWindowInsets(root, bars)
        assertEquals(40, root.paddingBottom)

        a.findViewById<View>(R.id.dw_write_to_us).performClick()
        idle()
        assertTrue(a.shows(R.id.dw_thread_screen))
        assertEquals(60 + a.dp(4), a.findViewById<View>(R.id.dw_header).paddingTop)
    }

    @Test
    fun `a logout throws the open screens away, closes the viewer and leaves no draft`() {
        val controller = open(DeskwootActivity.Entry.New("my secret question"))
        val saved = Bundle()
        controller.saveInstanceState(saved)
        controller.pause().stop().destroy()
        // The viewer was open when the Activity went: it comes back over the same screens.
        ImageViewer.save(saved, "att_1", "/files/a.png", "a.png")
        val a = open(DeskwootActivity.Entry.Start, saved).get()
        val root = a.findViewById<View>(R.id.dw_messenger_root)
        assertNotNull(a.findViewById(R.id.dw_image_viewer))
        assertEquals("the screens beneath leave the TalkBack order", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, root.importantForAccessibility)
        val bars = WindowCompat.getInsetsController(a.window, a.window.decorView)
        assertFalse("light icons on the viewer's black", bars.isAppearanceLightStatusBars)
        assertTrue(a.onBackPressedDispatcher.hasEnabledCallbacks())
        a.findViewById<View>(R.id.dw_viewer_close).layoutParams.let {
            assertEquals("a 48 dp close", a.dp(48), it.width)
            assertEquals(a.dp(48), it.height)
        }
        assertEquals("my secret question", a.findViewById<EditText>(R.id.dw_composer_input).text.toString())

        Deskwoot.logout()
        assertTrue(idleUntil { a.shows(R.id.dw_start_screen) })
        assertNull(a.findViewById(R.id.dw_image_viewer))
        assertNull("the old thread is neither readable nor typable", a.findViewById(R.id.dw_thread_screen))
        assertNull(a.findViewById(R.id.dw_composer_input))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, root.importantForAccessibility)
        assertTrue("the status bar icons follow the light messenger again", bars.isAppearanceLightStatusBars)
        assertEquals("", UiMemory.draft(null))
        assertFalse(a.isFinishing)

        // Writing again starts with an empty field.
        a.findViewById<View>(R.id.dw_write_to_us).performClick()
        idle()
        assertEquals("", a.findViewById<EditText>(R.id.dw_composer_input).text.toString())
    }

    @Test
    fun `a change of the signed in user forgets the drafts, the first sign in keeps them`() {
        scripted.http.on(verifyKey) { call -> ok(json("contactIdentifier" to "wb_" + call.body?.optJSONObject("user")?.optString("identifier"))) }
        UiMemory.setDraft(null, "typed before signing in")
        Deskwoot.identify(userA)
        idle()
        assertEquals(VisitorIdentity.Verified("u_a"), scripted.session.state.value.identity)
        // An anonymous visitor who signs in is the same person: nothing is wiped.
        assertEquals("typed before signing in", UiMemory.draft(null))

        UiMemory.setDraft(null, "Anna's text")
        UiMemory.helpQuery = "refund"
        Deskwoot.identify(userB)
        idle()
        assertEquals(VisitorIdentity.Verified("u_b"), scripted.session.state.value.identity)
        assertEquals("", UiMemory.draft(null))
        assertEquals("", UiMemory.helpQuery)
    }

    @Test
    fun `the failure over a running messenger blocks it until it is gone`() {
        val a = open(DeskwootActivity.Entry.Help).get()
        a.findViewById<View>(R.id.dw_collection_row).performClick()
        idle()
        assertTrue(a.onBackPressedDispatcher.hasEnabledCallbacks())
        val container = a.findViewById<View>(R.id.dw_screen_container)

        // The app names a user and the check gets no usable answer: the identity check failed.
        scripted.http.on(verifyKey) { fail(503) }
        Deskwoot.identify(userA)
        idle()
        val error = a.findViewById<TextView>(R.id.dw_load_error)
        assertNotNull(error)
        val placeholder = error.parent.parent as View
        assertTrue("touches never reach the screens beneath", placeholder.isClickable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, container.importantForAccessibility)
        assertFalse("back closes the messenger instead of walking hidden screens", a.onBackPressedDispatcher.hasEnabledCallbacks())
        val close = placeholder.findViewById<View>(R.id.dw_close)
        assertTrue(close.isShown)
        assertEquals(a.dp(48), close.layoutParams.width)
        assertEquals(a.dp(48), close.layoutParams.height)
        assertEquals(app.getString(R.string.dw_close), close.contentDescription)

        // Try again, and the check answers this time.
        scripted.http.on(verifyKey) { ok(json("contactIdentifier" to "wb_a")) }
        placeholder.findViewById<View>(R.id.dw_retry).performClick()
        idle()
        assertNull(a.findViewById(R.id.dw_load_error))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, container.importantForAccessibility)
        assertTrue("back steps through the screens again", a.onBackPressedDispatcher.hasEnabledCallbacks())

        scripted.http.on(verifyKey) { fail(503) }
        Deskwoot.identify(userB)
        idle()
        a.findViewById<TextView>(R.id.dw_load_error).let { (it.parent.parent as View).findViewById<View>(R.id.dw_close).performClick() }
        assertTrue("the close control closes the messenger", a.isFinishing)
    }

    @Test
    fun `an error on the server's side offers Try again, and a tap loads the chat again`() {
        val refused = ScriptedSession()
        refused.http.on("GET /api/widget/config") { fail(403, json("error" to "Widget disabled")) }
        refused.install()
        val a = open(DeskwootActivity.Entry.Start).get()
        assertEquals("Something went wrong on our side.", a.findViewById<TextView>(R.id.dw_load_error).text.toString())
        val retry = a.findViewById<TextView>(R.id.dw_retry)
        assertEquals("Try again", retry.text.toString())

        refused.http.on("GET /api/widget/config") { ok(refused.config) }
        retry.performClick()
        assertTrue(idleUntil { a.findViewById<View>(R.id.dw_load_error) == null })
        assertTrue(a.shows(R.id.dw_start_screen))
    }

    @Test
    @Config(qualifiers = "night")
    fun `the loading screen has a close control and takes the app's dark before the config arrives`() {
        val slow = ScriptedSession()
        slow.http.on("GET /api/widget/config") { kotlinx.coroutines.awaitCancellation() }
        slow.install()
        // The inbox's own setting is not known yet: the app's dark decides.
        Deskwoot.theme = Deskwoot.Theme.INBOX
        val a = open(DeskwootActivity.Entry.Start).get()
        val loading = a.findViewById<TextView>(R.id.dw_loading)
        assertNotNull(loading)
        val placeholder = loading.parent.parent as View
        assertEquals(0xFF14141F.toInt(), (a.window.decorView.background as ColorDrawable).color)
        assertTrue(placeholder.findViewById<View>(R.id.dw_close).isShown)
        placeholder.findViewById<View>(R.id.dw_close).performClick()
        assertTrue(a.isFinishing)
    }

    @Test
    fun `a new widget token while the messenger is open closes it, and the next present opens the new inbox`() {
        val a = open(DeskwootActivity.Entry.New("for the first shop")).get()
        assertTrue(a.shows(R.id.dw_thread_screen))
        val other = ScriptedSession(config = helpConfig("businessName" to "Second Shop", "brandColor" to "#AA3300"))
        Deskwoot.makeSession = { _, _, _ -> other.session }
        Deskwoot.setup(app, other.token)
        assertTrue("closed inside the setup call", a.isFinishing)
        assertEquals("nothing typed for the first shop stays", "", UiMemory.draft(null))
        idle()

        val b = open(DeskwootActivity.Entry.Start).get()
        assertTrue(idleUntil { b.shows(R.id.dw_start_screen) })
        assertEquals("Second Shop", b.findViewById<TextView>(R.id.dw_business_name).text.toString())
    }

    @Test
    fun `the top bar follows a renamed business and a help center switched on while it is open`() {
        scripted.config = helpConfig("helpCenterEnabled" to false, "businessName" to "Acme")
        scripted.session.refresh()
        idle()
        val a = open(DeskwootActivity.Entry.Start).get()
        assertNull("no help center, no tabs", a.findViewById(R.id.dw_tab_help))
        scripted.config = helpConfig("businessName" to "Acme Coffee")
        scripted.session.refresh()
        idle()
        assertNotNull(a.findViewById(R.id.dw_tab_help))
        assertEquals("Acme Coffee", a.findViewById<TextView>(R.id.dw_business_name).text.toString())
    }

    @Test
    fun `the theme set while the messenger is open applies at once`() {
        val controller = open(DeskwootActivity.Entry.Help)
        val first = controller.get()
        assertEquals(android.graphics.Color.WHITE, (first.window.decorView.background as ColorDrawable).color)
        // From any thread, as the README says.
        Thread { Deskwoot.theme = Deskwoot.Theme.DARK }.apply { start() }.join()
        assertEquals(Deskwoot.Theme.DARK, Deskwoot.theme)
        idle()
        val second = controller.get()
        assertTrue("the messenger was built again in dark", second !== first)
        assertEquals(0xFF14141F.toInt(), (second.window.decorView.background as ColorDrawable).color)
        assertTrue("on the same screen", second.shows(R.id.dw_help_screen))
    }

    @Test
    fun `a new brand color builds the screens again in it`() {
        val a = open(DeskwootActivity.Entry.Help).get()
        val before = a.findViewById<View>(R.id.dw_help_screen)
        scripted.config = helpConfig("brandColor" to "#AA3300")
        scripted.session.refresh()
        idle()
        val after = a.findViewById<View>(R.id.dw_help_screen)
        assertTrue(after.isShown)
        assertTrue("restyled, not the old views", after !== before)
    }

    @Test
    fun `the help does not rebuild its rows when the session changes something else`() {
        val a = open(DeskwootActivity.Entry.Help).get()
        val row = a.findViewById<View>(R.id.dw_collection_row)
        val search = a.findViewById<EditText>(R.id.dw_help_search)
        // A new state with the same look: the owner changed the greeting.
        scripted.config = helpConfig("greeting" to "Hello again")
        scripted.session.refresh()
        idle()
        assertEquals("Hello again", scripted.session.state.value.config?.greeting)
        assertTrue(a.findViewById<View>(R.id.dw_collection_row) === row)
        assertTrue(a.findViewById<View>(R.id.dw_help_search) === search)
        // The search field is named by its hint, not by a content description.
        assertNull(search.contentDescription)
        assertEquals(app.getString(R.string.dw_search_placeholder), search.hint.toString())
    }

    @Test
    fun `links leave the SDK only as http, https, mailto and tel`() {
        val a = open(DeskwootActivity.Entry.Help).get()
        val nav = helpUi(a)
        for (blocked in listOf("file:///sdcard/a.pdf", "content://x/y", "intent://scan#Intent;end", "javascript:alert(1)", "myapp://pay")) {
            nav.openUrl(blocked)
            assertNull(blocked, org.robolectric.Shadows.shadowOf(a).nextStartedActivity)
        }
        for (allowed in listOf("https://shop.example/x", "mailto:help@acme.org", "tel:+4930123")) {
            nav.openUrl(allowed)
            val started: Intent? = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
            assertEquals(allowed, started?.dataString)
            assertEquals(Intent.ACTION_VIEW, started?.action)
        }
    }

    @Test
    fun `a link with its scheme in capitals leaves the SDK with the scheme in lower case`() {
        val a = open(DeskwootActivity.Entry.Help).get()
        val nav = helpUi(a)
        for ((written, opened) in listOf("HTTPS://shop.example/Order/42" to "https://shop.example/Order/42", "Mailto:help@acme.org" to "mailto:help@acme.org", "TEL:+4930123" to "tel:+4930123")) {
            nav.openUrl(written)
            val started: Intent? = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
            assertEquals(opened, started?.dataString)
        }
        nav.openUrl("JAVASCRIPT:alert(1)")
        assertNull(org.robolectric.Shadows.shadowOf(a).nextStartedActivity)
    }

    /** The navigation the screens of [a] call, through a screen's Ui. */
    private fun helpUi(a: DeskwootActivity): MessengerNavigation {
        val field = DeskwootActivity::class.java.getDeclaredField("messenger").apply { isAccessible = true }
        return (field.get(a) as Messenger).ui.nav
    }

    /** An app screen whose starts the system refuses (a locked down profile, a broken manifest merge). */
    class RefusingActivity : androidx.activity.ComponentActivity() {
        override fun startActivity(intent: Intent?) = throw SecurityException("refused")

        override fun startActivity(intent: Intent?, options: Bundle?) = throw SecurityException("refused")
    }

    @Test
    fun `a refused start of the messenger throws nothing into the app and switches nothing off`() {
        val host = Robolectric.buildActivity(RefusingActivity::class.java).setup().get()
        Deskwoot.present(host)
        Deskwoot.presentConversation(host)
        idle()
        assertFalse(DeskwootActivity.resumed)
        assertNull(DeskwootActivity.openActivity?.get())
    }

    @Test
    fun `the Activity shows Java nothing of its own`() {
        fun visible(c: Class<*>) = c.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.map { it.name }
        assertEquals(emptyList<String>(), visible(DeskwootActivity::class.java))
        assertEquals(emptyList<String>(), visible(DeskwootActivity.Companion::class.java))
        assertEquals(emptyList<String>(), visible(DeskwootActivity.Entry.Companion::class.java))
    }
}
