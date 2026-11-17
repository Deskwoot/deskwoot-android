package com.deskwoot.sdk.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deskwoot.sdk.BOT_ON
import com.deskwoot.sdk.R
import com.deskwoot.sdk.baseConfig
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.json
import com.deskwoot.sdk.jsonList
import com.deskwoot.sdk.model.ConversationStatus
import com.deskwoot.sdk.model.CsatState
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.PendingSend
import com.deskwoot.sdk.model.PendingState
import com.deskwoot.sdk.model.SendFailure
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.TranscriptState
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.row
import com.deskwoot.sdk.ui.files.AttachSource
import com.deskwoot.sdk.ui.files.Picked
import com.deskwoot.sdk.ui.image.ImageLoader
import com.deskwoot.sdk.ui.text.Strings
import com.deskwoot.sdk.ui.text.UiLanguage
import com.deskwoot.sdk.ui.theme.UiColors
import com.deskwoot.sdk.ui.thread.Composer
import com.deskwoot.sdk.ui.thread.ComposerCallbacks
import com.deskwoot.sdk.ui.thread.ThreadAdapter
import com.deskwoot.sdk.ui.thread.ThreadCallbacks
import com.deskwoot.sdk.ui.thread.ThreadRow
import com.deskwoot.sdk.ui.thread.ThreadRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The messenger's views against real resources: strings per language, rows bound into views, the composer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewsTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun config(vararg overrides: Pair<String, Any?>): WidgetConfig = checkNotNull(WidgetConfig.parse(baseConfig(*overrides).toString()))

    private fun ui(config: WidgetConfig, language: UiLanguage = UiLanguage.EN, formal: Boolean = false): Ui {
        val session = ChatSession.create(activity, "wt_test", null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return Ui(
            session = session,
            actions = SessionActions(session, scope),
            strings = Strings(activity, language, formal, R.style.dw_messenger_theme_light),
            colors = UiColors(config.brandColor, false),
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
        override fun pick(source: AttachSource, allowedTypes: List<String>, from: Route) = Unit
    }

    private object NoCallbacks : ThreadCallbacks {
        override fun openUrl(url: String) = Unit
        override fun openAttachment(attachment: com.deskwoot.sdk.model.Attachment) = Unit
        override fun jumpTo(key: String) = Unit
        override fun choice(label: String, greeting: Boolean) = Unit
        override fun resolvedButton() = Unit
        override fun teamButton(label: String) = Unit
        override fun retry(uuid: String) = Unit
        override fun discard(uuid: String, text: String?) = Unit
        override fun reply(row: ThreadRow) = Unit
        override fun messageMenu(row: ThreadRow, copyText: String?, anchor: View, opener: View): Boolean = false
        override fun copy(text: String) = Unit
        override fun bindCsat(holder: FrameLayout) = Unit
        override fun bindTranscript(holder: FrameLayout) = Unit
    }

    private fun thread(messages: List<Message>, pending: List<PendingSend> = emptyList()) = ThreadState(
        conversationId = "c1", status = ConversationStatus.OPEN, messages = messages, pending = pending, uploads = emptyList(),
        segments = emptyList(), botThinking = false, botTool = null, agentTyping = false, queuePosition = null, waitingForTeam = false,
        resolved = false, closed = false, continuesAfterResolve = false, endedLocally = false, showCsat = false, csatState = CsatState.IDLE,
        emailKnown = false, heldMessages = emptyList(), missingFields = emptyList(), transcriptState = TranscriptState.IDLE, ending = false,
        hasAgentReply = false, gate = Gate.None, inEndState = false, canSend = true, canAttach = true, canEnd = true, csatDue = false,
        transcriptOffered = false, fynnEndMessageId = null, choicesMessageId = "b1", botTypingRowId = null,
    )

    /** Every view under [root], depth first. */
    private fun all(root: View): List<View> = if (root is ViewGroup) listOf(root) + (0 until root.childCount).flatMap { all(root.getChildAt(it)) } else listOf(root)

    @Test
    fun `strings come in the session's language, German in the inbox's form of address`() {
        val du = Strings(activity, UiLanguage.DE, formal = false)
        val sie = Strings(activity, UiLanguage.DE, formal = true)
        assertEquals("Schreib eine Nachricht …", du.t(R.string.dw_type_message))
        assertEquals("Schreiben Sie eine Nachricht …", sie.t(R.string.dw_type_message))
        // Only German has a Sie table; formal English is plain English.
        assertEquals("Type a message...", Strings(activity, UiLanguage.EN, formal = true).t(R.string.dw_type_message))
        assertEquals("Sie: Hallo", sie.t(R.string.dw_you_said, "Hallo"))
        // The SDK's own lines resolve in every language, with arguments.
        assertEquals("Keine Verbindung. Neuer Versuch läuft …", du.t(R.string.dw_sdk_offline))
        assertEquals("Das Konto konnte nicht bestätigt werden.", sie.t(R.string.dw_sdk_identity_failed))
        // Real plurals per language.
        assertEquals("1 neue Nachricht", du.context.resources.getQuantityString(R.plurals.dw_sdk_unread, 1, 1))
        assertEquals("3 neue Nachrichten", du.context.resources.getQuantityString(R.plurals.dw_sdk_unread, 3, 3))
        val ar = Strings(activity, UiLanguage.AR, formal = false)
        assertEquals(View.LAYOUT_DIRECTION_RTL, ar.context.resources.configuration.layoutDirection)
        assertTrue(ar.t(R.string.dw_queue_position, "3").contains("3"))
    }

    @Test
    fun `rows bind into views with their ids, texts and accessibility`() {
        val cfg = config(BOT_ON, "greeting" to null)
        val ui = ui(cfg)
        val answer = checkNotNull(
            Message.parse(
                row(
                    "b1", content = "We ship **worldwide**.\n[Shipping](https://deskwoot.com/shipping)", at = 10,
                    attrs = json("bot_id" to "bot1", "buttons" to jsonList(json("label" to "Standard"), json("label" to "Express"))),
                    sender = json("name" to "Fynn"),
                ),
            ),
        )
        val mine = checkNotNull(Message.parse(row("v1", type = "INCOMING", content = "Do you ship to Austria?", attrs = json("clientAssignedUuid" to "u1"))))
        val failed = PendingSend("u2", "Hello?", System.currentTimeMillis(), PendingState.FAILED, error = SendFailure.NOT_SENT)
        val rows = ThreadRows(cfg, ui.people, ui.strings, ui.formats).build(thread(listOf(mine, answer), listOf(failed)), System.currentTimeMillis())

        val list = RecyclerView(ui.context).apply {
            layoutManager = LinearLayoutManager(ui.context)
            itemAnimator = null
        }
        val adapter = ThreadAdapter(ui, NoCallbacks)
        list.adapter = adapter
        activity.setContentView(list, ViewGroup.LayoutParams(1080, 2400))
        adapter.submitList(rows)
        // AsyncListDiffer diffs on a background thread; wait for it, then for the layout.
        repeat(20) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1080, 4000)

        val views = all(list)
        val texts = views.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Do you ship to Austria?"))
        assertTrue(texts.any { it.startsWith("We ship worldwide.") })
        // The link line became a card, out of the text.
        assertTrue(views.any { it.id == R.id.dw_link_card && it.visibility == View.VISIBLE })
        assertFalse(texts.any { it.contains("](https://") })
        // Choice buttons, labelled for screen readers.
        assertEquals(listOf("Standard", "Express"), views.filter { it.id == R.id.dw_choice_button }.map { it.contentDescription.toString() })
        // The failed send keeps its bubble with Try again and Cancel.
        assertNotNull(views.firstOrNull { it.id == R.id.dw_retry_send && it.visibility == View.VISIBLE })
        assertEquals("Try again", (views.first { it.id == R.id.dw_retry_send } as TextView).text.toString())
        // The writer line and the face of the bot's group.
        assertTrue(texts.any { it.startsWith("Fynn · AI assistant") })
        assertTrue(views.any { it.id == R.id.dw_bubble && it.background != null })
    }

    @Test
    fun `the composer sends only with text and says why it is disabled`() {
        val ui = ui(config(BOT_ON))
        var sent: String? = null
        val composer = Composer(
            ui.context, ui,
            object : ComposerCallbacks {
                override fun send(text: String) {
                    sent = text
                }

                override fun textChanged(text: String) = Unit

                override fun attach(anchor: View) = Unit

                override fun cancelReply() = Unit
            },
        )
        activity.setContentView(composer)
        val send = composer.findViewById<View>(R.id.dw_composer_send)
        assertFalse(send.isEnabled)
        assertEquals("Send message", send.contentDescription)
        composer.input.setText("  Hello  ")
        assertTrue(send.isEnabled)
        send.performClick()
        assertEquals("Hello", sent)
        assertEquals("", composer.input.text.toString())

        // While the bot replies the field keeps focus and keyboard but takes no text.
        composer.input.requestFocus()
        composer.setState(botReplying = true, canSend = false, canAttach = false)
        assertEquals("Bot is replying...", composer.input.hint.toString())
        assertTrue(composer.input.isEnabled)
        assertTrue(composer.input.hasFocus())
        composer.input.text?.append("typed")
        assertEquals("", composer.input.text.toString())
        assertFalse(send.isEnabled)
        assertFalse(composer.findViewById<View>(R.id.dw_composer_attach).isEnabled)
        composer.setState(botReplying = false, canSend = true, canAttach = true)
        composer.input.text?.append("Hi")
        assertEquals("Hi", composer.input.text.toString())
        assertTrue(composer.input.hasFocus())
    }

    @Test
    fun `German formal labels reach the controls`() {
        val ui = ui(config(), UiLanguage.DE, formal = true)
        val composer = Composer(ui.context, ui, object : ComposerCallbacks {
            override fun send(text: String) = Unit
            override fun textChanged(text: String) = Unit
            override fun attach(anchor: View) = Unit
            override fun cancelReply() = Unit
        })
        assertEquals("Schreiben Sie eine Nachricht …", composer.input.hint.toString())
        assertEquals("Nachricht senden", composer.findViewById<View>(R.id.dw_composer_send).contentDescription)
    }
}
