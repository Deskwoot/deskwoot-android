package com.deskwoot.sdk.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.R
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.fail
import com.deskwoot.sdk.json
import com.deskwoot.sdk.jsonList
import com.deskwoot.sdk.offline
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.pollAnswer
import com.deskwoot.sdk.row
import com.deskwoot.sdk.sent
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.DeskwootActivity
import com.deskwoot.sdk.ui.ScreenHarness
import com.deskwoot.sdk.model.TranscriptState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu
import java.time.Duration

/** The thread on the real messenger against a scripted session: sends, the bot's turn, what screen readers hear, failures, saved state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadScreenTest {
    private val mia = json("name" to "Mia")
    private val fynn = json("name" to "Fynn")

    /** A returning visitor with conversation c1 open on screen; its poll answers [rows]. */
    private fun TestScope.inThread(config: JSONObject = baseConfig(), vararg rows: JSONObject, resolved: Boolean = false, showCsat: Boolean = false): ScreenHarness {
        val s = ScreenHarness(this, config, record = VisitorRecord(bearer = "wb_known"))
        s.h.restoreAnswer = { ok(json("conversationId" to "c1", "status" to "OPEN", "resolvedAt" to null, "unreadCount" to 0)) }
        s.h.http.on("GET /api/widget/messages") { ok(pollAnswer(*rows, resolved = resolved, showCsatSurvey = showCsat)) }
        s.open(DeskwootActivity.Entry.Open("c1"))
        assertTrue(s.has(R.id.dw_thread_list))
        return s
    }

    private fun dp(s: ScreenHarness, value: Int): Int = (value * s.activity.resources.displayMetrics.density).toInt()

    private fun announcer(s: ScreenHarness): String = s.activity.findViewById<TextView>(R.id.dw_announcer).text.toString()

    /** The focusable column of the message whose description starts with [start]. */
    private fun message(s: ScreenHarness, start: String): View =
        s.rows().flatMap { all(it) }.first { it.isFocusable && it.contentDescription?.startsWith(start) == true }

    private fun all(root: View): List<View> =
        if (root is android.view.ViewGroup) listOf(root) + (0 until root.childCount).flatMap { all(root.getChildAt(it)) } else listOf(root)

    @Test
    fun `a send shows at once, the field keeps its focus through the bot's turn, and the answer in the typing row is read out`() = runTest {
        val s = inThread(baseConfig(BOT_ON))
        s.h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known", botWillReply = true) }
        s.send("Where is my order?")
        val input = s.find<EditText>(R.id.dw_composer_input)
        assertEquals("", input.text.toString())
        assertTrue(s.texts().contains("Where is my order?"))
        // The bot's turn: the field keeps the focus, the hint says why it waits, the typing row says who types.
        assertTrue(input.hasFocus())
        assertEquals("Bot is replying...", input.hint.toString())
        assertEquals("Fynn is typing", s.find<View>(R.id.dw_typing).contentDescription)

        val uuid = s.h.thread.pending.first().uuid
        val mine = row("v1", type = "INCOMING", content = "Where is my order?", at = 1, attrs = json("clientAssignedUuid" to uuid))
        s.h.http.on("GET /api/widget/messages") { ok(pollAnswer(mine, row("r1", content = "", at = 2, attrs = json("bot_id" to "bot1", "isTyping" to true), sender = fynn))) }
        s.elapse(800)
        assertTrue(s.has(R.id.dw_typing))
        s.h.http.on("GET /api/widget/messages") { ok(pollAnswer(mine, row("r1", content = "It ships today.", at = 2, attrs = json("bot_id" to "bot1"), sender = fynn))) }
        s.elapse(800)
        assertFalse(s.has(R.id.dw_typing))
        assertTrue(s.texts().contains("It ships today."))
        // The row turned from typing into the answer: it counts as new and is read out.
        s.elapse(300)
        assertEquals("Fynn: It ships today.", announcer(s))
        assertTrue(input.hasFocus())
        assertEquals("Type a message...", input.hint.toString())
        s.close()
    }

    @Test
    fun `replies that arrive together are one announcement, the offline line is read when it shows, and the announcer empties`() = runTest {
        val s = inThread(baseConfig(), row("a1", content = "Hello", at = 0, sender = mia))
        s.h.http.on("GET /api/widget/messages") {
            ok(pollAnswer(row("a1", content = "Hello", at = 0, sender = mia), row("a2", content = "First answer", at = 5, sender = mia), row("a3", content = "Second answer", at = 6, sender = mia)))
        }
        s.elapse(5_100)
        assertEquals("Mia: First answer\nMia: Second answer", announcer(s))
        val announcerView = s.activity.findViewById<TextView>(R.id.dw_announcer)
        assertFalse(announcerView.isFocusable)
        s.elapse(4_100)
        assertEquals("", announcer(s))

        s.h.http.on("GET /api/widget/messages") { offline() }
        repeat(80) { if (!s.has(R.id.dw_offline_line)) s.elapse(100) }
        assertTrue(s.has(R.id.dw_offline_line))
        s.elapse(300)
        assertEquals("No connection. Trying again…", announcer(s))
        s.close()
    }

    @Test
    fun `a new thread after Write to us draws nothing of the previous conversation`() = runTest {
        val s = inThread(baseConfig(), row("a1", content = "Old answer", at = 0, sender = mia))
        assertTrue(s.texts().contains("Old answer"))
        s.back()
        s.h.http.on("GET /api/widget/messages") { ok(pollAnswer()) }
        s.find<View>(R.id.dw_write_to_us).performClick()
        // Frames are drawn before the session publishes the new thread.
        s.frames()
        val list = s.find<RecyclerView>(R.id.dw_thread_list)
        assertEquals(0, list.adapter?.itemCount)
        assertFalse(s.texts().contains("Old answer"))
        assertNotEquals("Mia", s.find<TextView>(R.id.dw_header_title).text.toString())
        s.pump()
        assertTrue(s.texts().contains("Hi there"))
        assertFalse(s.texts().contains("Old answer"))
        s.close()
    }

    @Test
    fun `links inside a bubble are actions for screen readers`() = runTest {
        val s = inThread(baseConfig(), row("a1", content = "See [our prices](https://deskwoot.com/pricing) and https://deskwoot.com/help today.", at = 0, sender = mia))
        val column = message(s, "Mia")
        val info = AccessibilityNodeInfo()
        column.onInitializeAccessibilityNodeInfo(info)
        val labels = info.actionList.mapNotNull { it.label?.toString() }
        assertTrue(labels.toString(), labels.containsAll(listOf("Reply", "our prices", "https://deskwoot.com/help")))
        assertTrue(column.performAccessibilityAction(R.id.dw_action_link_2, null))
        assertEquals("https://deskwoot.com/help", shadowOf(s.activity).nextStartedActivity.dataString)
        s.close()
    }

    @Test
    fun `a message on its way says so, and one that was not sent says why, with Try again only where it can work`() = runTest {
        val s = inThread()
        s.h.http.once("POST /api/widget/messages") {
            delay(10_000)
            fail(429)
        }
        s.send("First")
        assertTrue(message(s, "You").contentDescription.contains("Sending"))
        s.elapse(10_000)
        // Rate limited: the reason, and Try again (it works in a moment).
        assertEquals("Too many messages in a short time. Trying again works in a moment.", s.find<TextView>(R.id.dw_send_failed_line).text.toString())
        assertTrue(s.has(R.id.dw_retry_send))
        val cancel = s.find<View>(R.id.dw_discard_send)
        assertTrue(cancel.width >= dp(s, 48) && cancel.height >= dp(s, 48))
        s.click(R.id.dw_discard_send)
        assertEquals("First", s.composerText)

        // The conversation is over: the reason, no Try again.
        s.find<EditText>(R.id.dw_composer_input).setText("")
        s.h.http.once("POST /api/widget/messages") { fail(409, json("error" to "Conversation is resolved")) }
        s.send("Second")
        assertEquals("This conversation has ended and takes no more messages.", s.find<TextView>(R.id.dw_send_failed_line).text.toString())
        assertFalse(s.has(R.id.dw_retry_send))
        assertTrue(s.has(R.id.dw_discard_send))
        assertTrue(message(s, "You").contentDescription.contains("This conversation has ended"))
        s.close()
    }

    /** [view]'s left, top, right and bottom in the window. */
    private fun box(view: View): IntArray {
        val at = IntArray(2)
        view.getLocationInWindow(at)
        return intArrayOf(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `a message that was not sent says Not sent beside Try again and Delete, a longer reason takes its own line`() = runTest {
        val s = inThread()
        s.h.http.once("POST /api/widget/messages") { fail(400, json("error" to "Something the app does not know")) }
        s.send("Hello?")
        val reason = s.find<TextView>(R.id.dw_send_failed_line)
        assertEquals("Not sent", reason.text.toString())
        assertTrue(message(s, "You").contentDescription.endsWith(", Not sent"))
        // One line at the end edge: the reason, Try again, Delete; the reason sits in the buttons' height.
        val words = box(reason)
        val retry = box(s.find<View>(R.id.dw_retry_send))
        val delete = box(s.find<View>(R.id.dw_discard_send))
        assertTrue("${words.toList()} ${retry.toList()}", words[2] <= retry[0] && retry[2] <= delete[0])
        val middle = (words[1] + words[3]) / 2
        assertTrue("${words.toList()} ${retry.toList()}", middle in retry[1]..retry[3] && middle in delete[1]..delete[3])
        assertTrue(retry[3] - retry[1] >= dp(s, 48))
        s.click(R.id.dw_discard_send)

        // A reason of its own words stays whole on its own line, above the buttons.
        s.find<EditText>(R.id.dw_composer_input).setText("")
        s.h.http.once("POST /api/widget/messages") { fail(429) }
        s.send("Again?")
        val limited = s.find<TextView>(R.id.dw_send_failed_line)
        assertEquals("Too many messages in a short time. Trying again works in a moment.", limited.text.toString())
        assertTrue(box(limited)[3] <= box(s.find<View>(R.id.dw_retry_send))[1])
        s.close()
    }

    /** The message menu that showed last: its titles in order, and the view it hangs at. */
    private fun shownMenu(): PopupMenu = checkNotNull(ShadowPopupMenu.getLatestPopupMenu())

    private fun titles(menu: PopupMenu): List<String> = (0 until menu.menu.size()).map { menu.menu.getItem(it).title.toString() }

    private fun anchorOf(menu: PopupMenu): View = PopupMenu::class.java.getDeclaredField("mAnchor").apply { isAccessible = true }.get(menu) as View

    private fun choose(menu: PopupMenu, title: String) {
        val item = (0 until menu.menu.size()).map { menu.menu.getItem(it) }.first { it.title.toString() == title }
        assertTrue(shadowOf(menu).onMenuItemClickListener.onMenuItemClick(item))
    }

    private fun clipboard(s: ScreenHarness): ClipboardManager = s.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun copied(s: ScreenHarness): String? = clipboard(s).primaryClip?.getItemAt(0)?.text?.toString()

    private fun actionLabels(view: View): List<String> {
        val info = AccessibilityNodeInfo()
        view.onInitializeAccessibilityNodeInfo(info)
        return info.actionList.mapNotNull { it.label?.toString() }
    }

    @Test
    fun `a long press on a reply opens Copy and Reply at its bubble, Copy fills the clipboard and Reply quotes it`() = runTest {
        val s = inThread(baseConfig(), row("a1", content = "Your parcel left **today**", at = 0, sender = mia))
        val reply = message(s, "Mia")
        assertTrue(reply.performLongClick())
        val menu = shownMenu()
        assertEquals(listOf("Copy", "Reply"), titles(menu))
        assertEquals(R.id.dw_bubble, anchorOf(menu).id)
        choose(menu, "Copy")
        // The text as it reads, without the marks of markdown.
        assertEquals("Your parcel left today", copied(s))

        // The greeting offers Copy only.
        val greeting = s.rows().flatMap { all(it) }.first { it.isFocusable && it.contentDescription?.contains("Hi there") == true }
        assertTrue(greeting.performLongClick())
        assertEquals(listOf("Copy"), titles(shownMenu()))

        assertTrue(reply.performLongClick())
        choose(shownMenu(), "Reply")
        s.pump()
        assertTrue(s.has(R.id.dw_reply_bar))
        s.h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known") }
        s.send("When does it arrive?")
        assertEquals("a1", s.h.sends().last().body?.getString("replyToMessageId"))
        s.close()
    }

    @Test
    fun `screen readers reach Copy and Reply as actions of the message`() = runTest {
        val s = inThread(baseConfig(), row("a1", content = "Your parcel left **today**", at = 0, sender = mia))
        val reply = message(s, "Mia")
        assertEquals(listOf("Copy", "Reply"), actionLabels(reply).filter { it == "Copy" || it == "Reply" })
        clipboard(s).setPrimaryClip(ClipData.newPlainText(null, "something else"))
        assertTrue(reply.performAccessibilityAction(R.id.dw_action_copy, null))
        assertEquals("Your parcel left today", copied(s))

        // A message that was not sent: Copy, no Reply; its long press menu has Copy alone.
        s.h.http.once("POST /api/widget/messages") { fail(400, json("error" to "Something the app does not know")) }
        s.send("Hello?")
        val mine = message(s, "You")
        assertTrue(actionLabels(mine).contains("Copy"))
        assertFalse(actionLabels(mine).contains("Reply"))
        assertTrue(mine.performLongClick())
        assertEquals(listOf("Copy"), titles(shownMenu()))
        assertEquals(R.id.dw_bubble, anchorOf(shownMenu()).id)
        choose(shownMenu(), "Copy")
        assertEquals("Hello?", copied(s))
        s.close()
    }

    @Test
    fun `the composer is named by its hint, keeps no view state and takes at most 20,000 characters`() = runTest {
        val s = inThread()
        val input = s.find<EditText>(R.id.dw_composer_input)
        assertNull(input.contentDescription)
        assertEquals("Type a message...", input.hint.toString())
        assertFalse(input.isSaveEnabled)
        s.type("x".repeat(20_005))
        assertEquals(20_000, s.composerText.length)
        s.close()
    }

    @Test
    fun `the reply target and the draft survive a recreate, and the restored draft sends no typing signal`() = runTest {
        val s = inThread(baseConfig(), row("a1", content = "Your parcel left today", at = 0, sender = mia))
        assertTrue(message(s, "Mia").performAccessibilityAction(R.id.dw_reply_bar, null))
        s.pump()
        assertTrue(s.has(R.id.dw_reply_bar))
        s.type("Thanks, when does it arrive?")
        s.elapse(5_000)
        val typed = s.h.http.calls("POST /api/widget/typing").count { it.body?.getBoolean("isTyping") == true }

        s.recreate()
        assertEquals("Thanks, when does it arrive?", s.composerText)
        assertTrue(s.has(R.id.dw_reply_bar))
        assertTrue(s.texts().contains("Your parcel left today"))
        s.elapse(1_000)
        assertEquals(typed, s.h.http.calls("POST /api/widget/typing").count { it.body?.getBoolean("isTyping") == true })

        // The reply goes out with its quote.
        s.h.http.once("POST /api/widget/messages") { sent(bearer = "wb_known") }
        s.click(R.id.dw_composer_send)
        assertEquals("a1", s.h.sends().last().body?.getString("replyToMessageId"))
        s.close()
    }

    @Test
    fun `the rating, its comment and the transcript address survive a recreate`() = runTest {
        val s = inThread(baseConfig(), row("a1", content = "Glad I could help", at = 0, sender = mia), resolved = true, showCsat = true)
        val glyphs = s.find<LinearLayout>(R.id.dw_csat_rating)
        glyphs.getChildAt(3).performClick()
        s.find<EditText>(R.id.dw_csat_comment).setText("Quick and kind")
        // The transcript: the server knows no address, so the banner asks for one.
        s.h.http.once("POST /api/widget/transcript") { fail(400, json("error" to "Email required", "needsEmail" to true)) }
        s.click(R.id.dw_transcript_banner)
        s.find<EditText>(R.id.dw_transcript_email).setText("anna@acme.io")

        s.recreate()
        val after = s.find<LinearLayout>(R.id.dw_csat_rating)
        assertEquals(listOf(true, true, true, true, false), (0 until 5).map { after.getChildAt(it).isSelected })
        assertEquals("Quick and kind", s.find<EditText>(R.id.dw_csat_comment).text.toString())
        assertFalse(s.find<EditText>(R.id.dw_csat_comment).isSaveEnabled)
        assertEquals("anna@acme.io", s.find<EditText>(R.id.dw_transcript_email).text.toString())
        s.close()
    }

    private fun assertTarget(s: ScreenHarness, id: Int) {
        val v = s.find<View>(id)
        assertTrue("${s.activity.resources.getResourceEntryName(id)} ${v.width}x${v.height}", v.height >= dp(s, 48) && v.width >= dp(s, 48))
    }

    @Test
    fun `End conversation and a file row are 48 to the finger`() = runTest {
        val file = jsonList(json("id" to "f1", "fileUrl" to "/api/widget/files/f1", "fileName" to "invoice.pdf", "fileType" to "FILE", "contentType" to "application/pdf"))
        val s = inThread(baseConfig(), row("a1", content = "Here is your invoice", at = 0, sender = mia, attachments = file))
        assertTarget(s, R.id.dw_end_conversation)
        assertTarget(s, R.id.dw_file_attachment)
        s.close()
    }

    @Test
    fun `the tracking link is 48 to the finger and an image without a name reads Image`() = runTest {
        val image = jsonList(json("id" to "i1", "fileUrl" to "/api/widget/files/i1", "fileType" to "IMAGE", "contentType" to "image/png"))
        val tracking = json("trackingCard" to json("trackingUrl" to "https://track.example.com/1", "status" to "shipped", "carrier" to "DHL"))
        val s = inThread(baseConfig(), row("a2", content = "Your parcel", at = 10, sender = mia, attrs = tracking), row("a3", content = null, at = 20, sender = mia, attachments = image))
        assertTarget(s, R.id.dw_tracking_link)
        assertEquals("Image", s.find<View>(R.id.dw_image_attachment).contentDescription)
        s.close()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `the who line wraps at a large font instead of losing the time`() = runTest {
        RuntimeEnvironment.setFontScale(2f)
        val s = inThread(baseConfig(), row("a1", content = "Hello", at = 0, sender = json("name" to "Miriam Alexandra Fernández")))
        val who = s.find<TextView>(R.id.dw_who_line)
        assertEquals(2, who.maxLines)
        val layout = checkNotNull(who.layout)
        assertTrue(layout.lineCount in 1..2)
        assertEquals(0, layout.getEllipsisCount(layout.lineCount - 1))
        RuntimeEnvironment.setFontScale(1f)
        s.close()
    }

    @Test
    fun `the transcript's retry offer ends on time also when its row was detached meanwhile`() = runTest {
        val s = inThread()
        val ui = s.ui()
        val banner = TranscriptBanner(ui.context, ui) {}
        val holder = FrameLayout(s.activity)
        s.activity.findViewById<FrameLayout>(R.id.dw_messenger_root).addView(holder)
        holder.addView(banner)
        banner.show(TranscriptState.FAILED)
        val text = banner.findViewById<TextView>(R.id.dw_transcript_send)
        val failed = text.text.toString()
        holder.removeView(banner)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        holder.addView(banner)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertNotEquals(failed, text.text.toString())
        assertTrue(text.text.toString().endsWith(ui.t(R.string.dw_transcript_send)))
        banner.emailText = "anna@acme.io"
        assertEquals("anna@acme.io", banner.emailText)
        s.close()
    }

    @Test
    fun `a sent transcript says so alone, in the online green`() = runTest {
        val s = inThread()
        val ui = s.ui()
        val banner = TranscriptBanner(ui.context, ui) {}
        s.activity.findViewById<FrameLayout>(R.id.dw_messenger_root).addView(banner)
        banner.show(TranscriptState.SENT)
        val texts = all(banner).filterIsInstance<TextView>().filter { it.visibility == android.view.View.VISIBLE && it.text.isNotEmpty() }
        assertTrue(texts.any { it.text.toString() == ui.t(R.string.dw_transcript_sent) && it.currentTextColor == ui.colors.ok })
        assertTrue("no link to send again", texts.none { it.text.toString().contains(ui.t(R.string.dw_transcript_send)) })
        s.close()
    }

}
