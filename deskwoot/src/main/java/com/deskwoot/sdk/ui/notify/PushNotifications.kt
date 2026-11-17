package com.deskwoot.sdk.ui.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.engine.PushVisitor
import com.deskwoot.sdk.model.PushPayload
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.store.storeKey
import com.deskwoot.sdk.ui.DeskwootActivity
import com.deskwoot.sdk.ui.people.People
import com.deskwoot.sdk.ui.text.Strings
import com.deskwoot.sdk.ui.text.UiLanguage
import com.deskwoot.sdk.ui.theme.Palette
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The system notification for a reply that arrived as a push (push
 * contract): the app's `FirebaseMessagingService` hands the data message to
 * `Deskwoot.handlePush`, and the SDK draws it in its own channel, one
 * notification per conversation (a newer reply replaces the older one), a
 * tap opens that conversation in the messenger. Only a push for this
 * device's visitor shows (its visitor key names the visitor the session runs
 * as, or the stored signed in user who waits for the app's identify); none
 * while the visitor reads that conversation. The SDK has no Firebase
 * dependency and no permission of its own: without the app's
 * POST_NOTIFICATIONS grant (Android 13 and later) or with its notifications
 * switched off, nothing shows and nothing fails.
 *
 * Every notification carries the tag `deskwoot:<inbox>:<conversation>` and
 * the id [NOTIFICATION_ID], so the SDK finds an inbox's notifications again
 * (the end of a visitor takes them all away, a conversation on screen its
 * own) and never touches one of the app's.
 */
internal object PushNotifications {
    /** The SDK's own channel, created the first time a notification shows. */
    const val CHANNEL_ID: String = "deskwoot_messages"

    /** The id of every Deskwoot notification; the tag names the inbox and the conversation, so no id of the app's own can clash. */
    const val NOTIFICATION_ID: Int = 0x4457

    private const val TAG_PREFIX = "deskwoot:"

    /** The tag of a conversation's notification ([conversationId] null: a push that names none). */
    fun tag(widgetToken: String, conversationId: String?): String = inboxTag(widgetToken) + conversationId.orEmpty()

    /** What every tag of the inbox [widgetToken] starts with. */
    private fun inboxTag(widgetToken: String): String = TAG_PREFIX + storeKey(widgetToken.trim()).take(INBOX_KEY_LENGTH) + ":"

    /**
     * The tags this process posted: Android 5 cannot list the delivered
     * notifications, so there the end of a visitor cancels these. Any thread.
     */
    private val posted: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** The app's choice of small icon (`Deskwoot.setNotificationIcon`); 0 is the app's own icon. */
    @Volatile
    var icon: Int = 0

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** The push when [data] is one of [session]'s inbox; null for the app's own pushes, another inbox's and before setup. */
    fun parse(data: Map<String, String?>, session: ChatSession?): PushPayload? =
        session?.let { PushPayload.parse(data, it.widgetToken) }

    /**
     * Any thread (FCM calls `onMessageReceived` on a worker thread). True
     * when [data] is ours, also when it shows nothing. The notification is
     * posted on the calling thread before this returns, so a process frozen
     * right after has shown it; only the refresh of the session goes to the
     * main thread. [cardShowed] says whether the in app card already showed
     * that message.
     */
    fun handle(context: Context, data: Map<String, String?>, session: ChatSession?, cardShowed: (String) -> Boolean): Boolean {
        val current = session ?: return false
        val push = PushPayload.parse(data, current.widgetToken) ?: return false
        val app = context.applicationContext ?: context
        // The reply reaches the unread count, the list and the launcher badge (the session lives on the main thread).
        if (onMainThread()) current.refresh() else main.post { current.refresh() }
        if (onMainThread() && !current.visitorKnown) {
            // Handed over on the main thread before this process read the visitor's store: the main thread
            // never waits for the disk, so the push is decided once the store was read.
            current.whenVisitorKnown { arrived(app, push, current, cardShowed) }
            return true
        }
        arrived(app, push, current, cardShowed)
        return true
    }

    /**
     * Draws [push] when its visitor key names this device's visitor and its
     * conversation is not on screen; true when a notification was posted.
     * Any thread.
     */
    fun arrived(context: Context, push: PushPayload, session: ChatSession, cardShowed: (String) -> Boolean): Boolean {
        // A visitor this device forgot (a logout, another user, a cookieless visitor of an ended process): nothing
        // of theirs shows, not even the preview. The push also tells that the server still holds this phone for
        // them: a removal still waiting for them goes out now.
        if (session.pushVisitor(push.visitor) == PushVisitor.FORGOTTEN) {
            push.visitor?.let(session::removeForgotten)
            return false
        }
        val conversationId = push.conversationId
        if (conversationId != null && session.isOnScreen(conversationId)) return false
        // The in app card showed this reply already: in the list, without sound or a banner (as iOS).
        val quiet = push.messageId?.let(cardShowed) == true
        return show(context, push, session.widgetToken, session.state.value, quiet)
    }

    // The app declares POST_NOTIFICATIONS, never the SDK (README, "Push notifications"); its grant is checked right here.
    @SuppressLint("MissingPermission")
    fun show(context: Context, push: PushPayload, widgetToken: String, state: SessionState?, quiet: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        return try {
            val texts = texts(context, state)
            // Again each time: the name follows the session's language.
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(texts.t(R.string.dw_sdk_notification_channel))
                    .build(),
            )
            val tag = tag(widgetToken, push.conversationId)
            posted += tag
            manager.notify(tag, NOTIFICATION_ID, build(context, push, widgetToken, state, texts, quiet))
            true
        } catch (_: SecurityException) {
            // The grant went away in between.
            false
        } catch (_: RuntimeException) {
            // The system service is gone, a bad icon: a notification is never worth a crash of the app.
            false
        }
    }

    /** Takes the notification of [conversationId] away: the visitor reads it on screen. */
    fun cancel(context: Context, widgetToken: String, conversationId: String) {
        cancelTag(context, tag(widgetToken, conversationId))
    }

    /**
     * Takes every delivered notification of the inbox [widgetToken] away: its
     * visitor ended. Those of the app and of another inbox stay.
     */
    fun cancelAll(context: Context, widgetToken: String) {
        val prefix = inboxTag(widgetToken)
        val tags = HashSet<String>()
        try {
            // Android 6 and later list what is delivered; earlier versions answer an empty list.
            for (shown in NotificationManagerCompat.from(context).activeNotifications) {
                val tag = shown.tag ?: continue
                if (shown.id == NOTIFICATION_ID && tag.startsWith(prefix)) tags += tag
            }
        } catch (_: RuntimeException) {
            // The system service is gone: what this process posted still goes.
        }
        posted.filterTo(tags) { it.startsWith(prefix) }
        tags.forEach { cancelTag(context, it) }
    }

    private fun cancelTag(context: Context, tag: String) {
        posted -= tag
        try {
            NotificationManagerCompat.from(context).cancel(tag, NOTIFICATION_ID)
        } catch (_: RuntimeException) {
            // Nothing to take away, or the system service is gone.
        }
    }

    private fun build(context: Context, push: PushPayload, widgetToken: String, state: SessionState?, texts: Strings, quiet: Boolean): Notification {
        val config = state?.config
        val support = texts.t(R.string.dw_support)
        val title = push.title ?: config?.let { People(it, support).businessName } ?: support
        val body = push.body ?: texts.t(R.string.dw_unread_message)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Before Android 8 a banner needs the high priority and a sound; later the channel decides.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(tapIntent(context, widgetToken, push))
            .setSilent(quiet)
        config?.brandColor?.let { builder.setColor(Palette.argb(Palette.safeColor(it))) }
        return builder.build()
    }

    /**
     * A tap opens that conversation in the messenger (the newest running one
     * for a push without an id): the one Activity of the SDK, immutable. The
     * conversation is the intent's data, so each conversation has its own
     * PendingIntent, also 2 ids with equal hashes. It names the inbox, so a
     * tap after the process ended opens the messenger even before the app
     * calls setup, and the push's visitor key, which decides whose it is.
     */
    fun tapIntent(context: Context, widgetToken: String, push: PushPayload): PendingIntent? {
        val conversationId = push.conversationId
        val entry = if (conversationId != null) DeskwootActivity.Entry.Open(conversationId) else DeskwootActivity.Entry.Conversation
        // From the application context: the intent asks for the app's task (FLAG_ACTIVITY_NEW_TASK).
        val intent = DeskwootActivity.tapIntent(context.applicationContext ?: context, entry, widgetToken, push.visitor)
        intent.data = Uri.Builder().scheme(DATA_SCHEME).authority(DATA_HOST).appendPath(conversationId.orEmpty()).build()
        return PendingIntentCompat.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)
    }

    /** The app's choice, else the app's icon, else the SDK's chat glyph. */
    fun smallIcon(context: Context): Int {
        val chosen = icon
        if (chosen != 0 && exists(context, chosen)) return chosen
        val appIcon = context.applicationInfo.icon
        if (appIcon == 0) return R.drawable.dw_ic_chat
        // Android 8.0 cannot draw an adaptive launcher icon in the status bar (the system rejects the
        // notification and the app crashes): the SDK's glyph there.
        if (Build.VERSION.SDK_INT == 26 && isAdaptive(context, appIcon)) return R.drawable.dw_ic_chat
        return appIcon
    }

    private fun exists(context: Context, id: Int): Boolean = try {
        context.resources.getResourceName(id)
        true
    } catch (_: Resources.NotFoundException) {
        false
    }

    private fun isAdaptive(context: Context, id: Int): Boolean = try {
        Build.VERSION.SDK_INT >= 26 && ContextCompat.getDrawable(context, id) is AdaptiveIconDrawable
    } catch (_: RuntimeException) {
        true
    }

    /** The session's language (the inbox's fixed one, else the app's or the device's). */
    private fun texts(context: Context, state: SessionState?): Strings {
        val tag = state?.locale?.takeIf { it.isNotBlank() } ?: ChatSession.appLanguageTag(context)
        return Strings(context, UiLanguage.resolve(tag), Strings.isFormal(state?.config?.formOfAddress))
    }

    private fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** The data of a tap's intent: `deskwoot://conversation/<id>`. */
    private const val DATA_SCHEME = "deskwoot"
    private const val DATA_HOST = "conversation"

    /** Hex characters of the inbox's key in a tag. */
    private const val INBOX_KEY_LENGTH = 16
}
