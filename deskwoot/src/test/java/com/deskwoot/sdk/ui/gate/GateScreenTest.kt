package com.deskwoot.sdk.ui.gate

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.ui.ScreenHarness
import com.deskwoot.sdk.ui.UiMemory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The steps before a chat on the real messenger: the form from the session's current step, leaving it, its answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GateScreenTest {
    private val formFields = listOf(
        mapOf("name" to "name", "type" to "text", "label" to "Name", "required" to true),
        mapOf("name" to "company", "type" to "text", "label" to "Company"),
        mapOf("name" to "role", "type" to "select", "label" to "Role", "options" to listOf("owner", "dev"), "optionLabels" to listOf("Shop owner", "Developer")),
    )
    private val formConfig = baseConfig("preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to formFields))

    private fun fields(s: ScreenHarness): List<EditText> = s.all(R.id.dw_gate_field).map { it as EditText }

    private fun field(s: ScreenHarness, name: String): EditText = fields(s).first { it.tag == name }

    /** A new visitor in an inbox without a step writes; the server asks for an email: the text waits. */
    private fun heldInThread(s: ScreenHarness, text: String = "Can you help?") {
        s.click(R.id.dw_write_to_us)
        s.h.http.once("POST /api/widget/messages") { fail(400, json("error" to "Email is required", "code" to "email_required")) }
        s.send(text)
        assertTrue("the email step shows", s.has(R.id.dw_gate_email))
    }

    @Test
    fun `an anonymous visitor's second Write to us shows the form's fields again`() = runTest {
        val s = ScreenHarness(this, formConfig).open()
        s.click(R.id.dw_write_to_us)
        assertEquals(listOf("name", "company", "role"), fields(s).map { it.tag })
        field(s, "name").setText("Anna")
        s.click(R.id.dw_gate_submit)
        s.h.http.on("POST /api/widget/messages") { sent(conversationId = "c1") }
        s.send("Hello")
        assertTrue(s.texts().contains("Hello"))

        s.back()
        s.click(R.id.dw_write_to_us)
        // The step comes from the session as it is now, not from the old thread's published state.
        assertTrue(s.has(R.id.dw_gate_screen))
        assertEquals(listOf("name", "company", "role"), fields(s).map { it.tag })
        assertEquals("", field(s, "name").text.toString())
        // An empty required field is refused here; nothing goes to the session.
        s.click(R.id.dw_gate_submit)
        assertTrue(s.has(R.id.dw_gate_screen))
        assertEquals(View.VISIBLE, s.all(R.id.dw_field_error).first().visibility)
        s.close()
    }

    @Test
    fun `a form of optional fields is asked, not skipped, after Write to us a second time`() = runTest {
        val optional = baseConfig("preChatFormEnabled" to true, "preChatFormFields" to mapOf("fields" to listOf(mapOf("name" to "company", "type" to "text", "label" to "Company"))))
        val s = ScreenHarness(this, optional).open()
        s.click(R.id.dw_write_to_us)
        s.click(R.id.dw_gate_submit)
        s.h.http.on("POST /api/widget/messages") { sent(conversationId = "c1") }
        s.send("Hello")
        s.back()
        s.click(R.id.dw_write_to_us)
        assertEquals(listOf("company"), fields(s).map { it.tag })
        s.close()
    }

    @Test
    fun `Cancel, the back arrow, system back, the back gesture and the close button give the held text back, once`() = runTest {
        val s = ScreenHarness(this).open()
        heldInThread(s)

        s.click(R.id.dw_gate_cancel)
        assertFalse(s.has(R.id.dw_gate_screen))
        assertEquals("Can you help?", s.composerText)
        assertEquals(emptyList<String>(), s.h.thread.heldMessages)
        // The step shows again on the next send, and the text waits again.
        s.click(R.id.dw_composer_send)
        assertTrue(s.has(R.id.dw_gate_email))
        assertEquals(1, s.h.sends().size)

        s.click(R.id.dw_back)
        assertEquals("Can you help?", s.composerText)
        s.click(R.id.dw_composer_send)
        assertTrue(s.has(R.id.dw_gate_email))

        s.back()
        assertEquals("Can you help?", s.composerText)
        s.click(R.id.dw_composer_send)
        assertTrue(s.has(R.id.dw_gate_email))

        s.backGesture()
        assertFalse(s.has(R.id.dw_gate_screen))
        assertEquals("Can you help?", s.composerText)
        assertEquals(1, s.threadScreens())
        s.click(R.id.dw_composer_send)
        assertTrue(s.has(R.id.dw_gate_email))

        // The × closes the messenger: the text waits in the composer for the next time.
        s.click(R.id.dw_close)
        assertTrue(s.activity.isFinishing)
        s.pump()
        assertEquals("Can you help?", UiMemory.draft(null))
        assertEquals(emptyList<String>(), s.h.thread.heldMessages)
        s.close()
    }

    @Test
    fun `while the gate slides away the thread neither opens it again nor misses the text`() = runTest {
        val s = ScreenHarness(this, reducedMotion = false).open()
        s.elapse(400)
        heldInThread(s)
        s.elapse(400)
        s.find<View>(R.id.dw_gate_cancel).performClick()
        // Mid slide: the text is back already, and a new state does not bring the gate back.
        s.pump()
        assertEquals("Can you help?", s.composerText)
        s.elapse(600)
        assertFalse(s.has(R.id.dw_gate_screen))
        assertEquals("Can you help?", s.composerText)
        assertEquals(1, s.threadScreens())
        s.close()
    }

    @Test
    fun `an answered step from the thread returns to that thread instead of stacking it twice`() = runTest {
        val s = ScreenHarness(this).open()
        heldInThread(s, "Where is my order?")
        s.find<EditText>(R.id.dw_gate_email).setText("anna@acme.io")
        s.h.http.once("POST /api/widget/messages") { sent(conversationId = "c1") }
        s.click(R.id.dw_gate_submit)
        assertFalse(s.has(R.id.dw_gate_screen))
        assertEquals(1, s.threadScreens())
        assertTrue(s.texts().contains("Where is my order?"))
        assertEquals("anna@acme.io", s.h.sends().last().body?.getString("email"))
        s.close()
    }

    @Test
    fun `answers, the chosen option and its label survive a recreate`() = runTest {
        val s = ScreenHarness(this, formConfig).open()
        s.click(R.id.dw_write_to_us)
        field(s, "name").setText("Anna")
        field(s, "company").setText("Acme")
        // Pick "Developer" in the list the choice opens.
        field(s, "role").performClick()
        val popup = checkNotNull(shadowOf(s.activity.application).latestPopupWindow)
        val list = popup.contentView as? ListView ?: (popup.contentView as android.view.ViewGroup).getChildAt(0) as ListView
        list.performItemClick(null, 1, 1)
        s.pump()
        assertEquals("Developer", field(s, "role").text.toString())

        s.recreate()
        // Each field keeps its own answer (they share one id), the choice shows its label.
        assertEquals("Anna", field(s, "name").text.toString())
        assertEquals("Acme", field(s, "company").text.toString())
        assertEquals("Developer", field(s, "role").text.toString())
        s.click(R.id.dw_gate_submit)
        s.h.http.on("POST /api/widget/messages") { sent(conversationId = "c1") }
        s.send("Hi")
        val body = checkNotNull(s.h.sends().last().body)
        assertEquals("dev", body.getJSONObject("preChatFormValues").getString("role"))
        s.close()
    }

    @Test
    fun `fields are named by their labels, not by a description that hides their text`() = runTest {
        val s = ScreenHarness(this, formConfig).open()
        s.click(R.id.dw_write_to_us)
        val name = field(s, "name")
        // The label above names the field (labeledBy, set by its accessibility delegate); the hint names it while empty.
        assertNull(name.contentDescription)
        assertEquals("Name", name.hint.toString())
        assertTrue(ViewCompat.hasAccessibilityDelegate(name))
        assertFalse(name.isSaveEnabled)
        val cancel = s.find<TextView>(R.id.dw_gate_cancel)
        assertTrue(cancel.height >= (48 * s.activity.resources.displayMetrics.density).toInt())
        s.close()
    }

    @Test
    fun `the gate saved state stays small`() = runTest {
        val s = ScreenHarness(this, formConfig).open()
        s.click(R.id.dw_write_to_us)
        field(s, "company").setText("x".repeat(50_000))
        val out = Bundle()
        s.activity.let { activity ->
            val method = activity.javaClass.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
            method.isAccessible = true
            method.invoke(activity, out)
        }
        val screens = (0 until 4).mapNotNull { out.getBundle("dw_screen_$it") }
        val saved = screens.mapNotNull { it.getString("field_company") }.single()
        assertTrue(saved.length <= 2_000)
        s.close()
    }
}
