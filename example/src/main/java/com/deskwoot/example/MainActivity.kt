package com.deskwoot.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.UnreadCountListener

/**
 * Every public call of the SDK behind a button, against the server and
 * the inbox the build names (see README, "Example app"). Not part of the library.
 */
class MainActivity : Activity() {
    private lateinit var unread: TextView
    private lateinit var languageButton: Button
    private lateinit var themeButton: Button
    private lateinit var launcherButton: Button
    private lateinit var paddingButton: Button
    private var launcherPadding = 0
    private lateinit var cardsButton: Button
    private lateinit var identifyAtStartButton: Button
    private var launcher = false
    private val languages = listOf(null, "en", "de", "es", "fr", "pt-BR", "it", "ja", "zh", "hi", "ar")
    private val listener = UnreadCountListener { count -> showUnread(count) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }
        column.addView(TextView(this).apply {
            text = "Deskwoot example"
            textSize = 24f
            setTextColor(Color.parseColor("#16162B"))
        })
        unread = TextView(this).apply {
            id = R.id.example_unread
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        }
        column.addView(unread)

        fun button(id: Int, label: String, action: () -> Unit): Button = Button(this).apply {
            this.id = id
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            column.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        button(R.id.example_present, "present()") { Deskwoot.present(this) }
        button(R.id.example_present_conversation, "presentConversation()") { Deskwoot.presentConversation(this) }
        button(R.id.example_present_new, "presentNewConversation(text)") {
            Deskwoot.presentNewConversation(this, "Hi! I have a question about my order.")
        }
        button(R.id.example_present_help, "presentHelpCenter()") { Deskwoot.presentHelpCenter(this) }
        button(R.id.example_identify, "identify(user_4711)") {
            Deskwoot.identify(TestUser.deskwootUser())
            TestUser.setIdentifiesAtStart(this, true)
            paint()
        }
        button(R.id.example_logout, "logout()") {
            Deskwoot.logout()
            TestUser.setIdentifiesAtStart(this, false)
            paint()
        }
        // Whether ExampleApp names the signed in user right after setup; off leaves a stored user dormant at the next start.
        identifyAtStartButton = button(R.id.example_identify_at_start, "") {
            TestUser.setIdentifiesAtStart(this, !TestUser.identifiesAtStart(this))
            paint()
        }
        languageButton = button(R.id.example_language, "") {
            val next = languages[(languages.indexOf(Deskwoot.language) + 1) % languages.size]
            Deskwoot.language = next
            paint()
        }
        themeButton = button(R.id.example_theme, "") {
            val all = Deskwoot.Theme.entries
            Deskwoot.theme = all[(all.indexOf(Deskwoot.theme) + 1) % all.size]
            paint()
        }
        launcherButton = button(R.id.example_launcher, "") {
            launcher = !launcher
            Deskwoot.setLauncherVisible(launcher)
            paint()
        }
        paddingButton = button(R.id.example_launcher_padding, "") {
            launcherPadding = if (launcherPadding == 0) 64 else 0
            Deskwoot.setLauncherBottomPadding(launcherPadding)
            paint()
        }
        cardsButton = button(R.id.example_cards, "") {
            Deskwoot.inAppNotificationsEnabled = !Deskwoot.inAppNotificationsEnabled
            paint()
        }
        if (BuildConfig.DEBUG) addDebugPush(column, ::button)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(column)
        })
        paint()
        showUnread(Deskwoot.unreadCount)
    }

    override fun onStart() {
        super.onStart()
        Deskwoot.addUnreadCountListener(listener)
        showUnread(Deskwoot.unreadCount)
    }

    override fun onStop() {
        Deskwoot.removeUnreadCountListener(listener)
        super.onStop()
    }

    /**
     * DEBUG BUILDS ONLY: a push as Firebase Cloud Messaging would hand it to
     * the app's FirebaseMessagingService, without Firebase. The data map has
     * the shape of the server's; the conversation id and the visitor key (the
     * first 16 hex characters of the SHA-256 of the visitor's bearer, as the
     * server sends it; empty sends none, which the SDK draws nothing for) are
     * typed into the fields. "in 12 s" leaves time to go to the home screen or
     * to open that conversation. Calls come from a worker thread, as FCM's do.
     */
    private fun addDebugPush(column: LinearLayout, button: (Int, String, () -> Unit) -> Button) {
        button(R.id.example_notification_permission, "request POST_NOTIFICATIONS") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        val conversation = EditText(this).apply {
            id = R.id.example_push_conversation
            hint = "conversationId"
            setSingleLine(true)
        }
        column.addView(conversation, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val visitor = EditText(this).apply {
            id = R.id.example_push_visitor
            hint = "visitor key"
            setSingleLine(true)
        }
        column.addView(visitor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val result = TextView(this).apply {
            id = R.id.example_push_result
            textSize = 15f
        }
        fun push(widgetToken: String, delayMillis: Long) {
            val data = mutableMapOf(
                "deskwoot" to "1",
                "conversationId" to conversation.text.toString().trim(),
                "widgetToken" to widgetToken,
                "messageId" to "debug-${System.currentTimeMillis()}",
                "title" to "Priya",
                "body" to "Yes, we ship to Austria.",
            )
            visitor.text.toString().trim().takeIf { it.isNotEmpty() }?.let { data["visitor"] = it }
            val app: Context = applicationContext
            Thread {
                Thread.sleep(delayMillis)
                val ours = Deskwoot.handlePush(app, data)
                runOnUiThread { result.text = "handlePush = $ours" }
            }.start()
        }
        // A made up token in the shape of an FCM token (64 to 1024 of A-Z, a-z, 0-9, _, : and -), so the local server stores it; nothing goes to Google.
        button(R.id.example_push_token, "setPushToken(debug token)") { Deskwoot.setPushToken("debugLocalExample:APA91b_debug_token_of_the_example_app_never_sent_to_firebase_0001") }
        button(R.id.example_push_token_clear, "setPushToken(null)") { Deskwoot.setPushToken(null) }
        button(R.id.example_notification_icon, "setNotificationIcon(stat_notify_chat)") {
            Deskwoot.setNotificationIcon(android.R.drawable.stat_notify_chat)
        }
        button(R.id.example_debug_push, "handlePush(debug push)") { push(BuildConfig.WIDGET_TOKEN, 0) }
        button(R.id.example_debug_push_later, "handlePush(debug push) in 12 s") { push(BuildConfig.WIDGET_TOKEN, 12_000) }
        button(R.id.example_debug_push_other, "handlePush(push of another inbox)") { push("ANOTHER_INBOX_WIDGET_TOKEN", 0) }
        column.addView(result)
    }

    private fun paint() {
        languageButton.text = "language = ${Deskwoot.language ?: "auto"}"
        themeButton.text = "theme = ${Deskwoot.theme}"
        launcherButton.text = "setLauncherVisible(${!launcher})"
        paddingButton.text = "setLauncherBottomPadding(${if (launcherPadding == 0) 64 else 0})"
        cardsButton.text = "inAppNotificationsEnabled = ${Deskwoot.inAppNotificationsEnabled}"
        identifyAtStartButton.text = "identify in Application.onCreate = ${TestUser.identifiesAtStart(this)}"
    }

    private fun showUnread(count: Int) {
        unread.text = "unreadCount = $count"
        unread.visibility = View.VISIBLE
        unread.gravity = Gravity.START
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
