package com.deskwoot.sdk.ui

import android.content.Context
import android.text.format.DateFormat
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.ui.image.ImageLoader
import com.deskwoot.sdk.ui.people.People
import com.deskwoot.sdk.ui.text.Formats
import com.deskwoot.sdk.ui.text.Strings
import com.deskwoot.sdk.ui.theme.UiColors
import com.deskwoot.sdk.ui.widgets.reducedMotion
import kotlinx.coroutines.CoroutineScope

/** What a screen asks the messenger to do: go somewhere, open something outside, close. */
internal interface MessengerNavigation {
    fun goTo(route: Route)

    fun replace(route: Route)

    fun back()

    fun close()

    /**
     * "Write to us": a new conversation through the steps before a chat,
     * with [draft] in the field. [replaceCurrent]: in place of the screen on
     * top ("Start a new conversation" in a finished thread).
     */
    fun startConversation(draft: String? = null, replaceCurrent: Boolean = false, article: com.deskwoot.sdk.model.HelpArticleRef? = null)

    /** One of the visitor's conversations. */
    fun openConversation(id: String)

    /** A link in the system browser (or the app that owns it); only http, https, mailto and tel ([Links]). */
    fun openUrl(url: String)

    /** An image of the chat in the full screen viewer. */
    fun showImage(key: String, link: String, name: String?)

    /** Any other file of the chat in the app that opens its type. */
    fun openFile(key: String, link: String, name: String?, mimeType: String?)

    /** Whether the device has a camera the attachment menu can offer. */
    fun cameraAvailable(): Boolean

    /**
     * A file from the photo picker, the camera or the document picker,
     * filtered by the inbox's types, for the screen of [from]: it arrives at
     * that screen's [PickReceiver.picked], also after a recreate.
     */
    fun pick(source: com.deskwoot.sdk.ui.files.AttachSource, allowedTypes: List<String>, from: Route)
}

/**
 * One look of the messenger: the session, its texts in the session's
 * language, its colors, the design, the image loader and the navigation.
 * A new [Ui] is made when the language, the brand or light and dark change;
 * the screens are then built again from their routes.
 */
internal class Ui(
    val session: ChatSession,
    val actions: SessionActions,
    val strings: Strings,
    val colors: UiColors,
    val design: MessengerDesign,
    val images: ImageLoader,
    val scope: CoroutineScope,
    val nav: MessengerNavigation,
    config: WidgetConfig,
) {
    /** Views are built from this context: the session's language and layout direction, the messenger's theme. */
    val context: Context get() = strings.context

    val rtl: Boolean get() = strings.language.isRtl

    val formats: Formats = Formats(strings, patterns = { skeleton, locale -> DateFormat.getBestDateTimePattern(locale, skeleton) })

    val reducedMotion: Boolean = context.reducedMotion()

    var config: WidgetConfig = config
        private set

    var people: People = People(config, strings.t(com.deskwoot.sdk.R.string.dw_support))
        private set

    /** A config of the same look arrived (texts, team, bot): names and faces follow it. */
    fun updateConfig(fresh: WidgetConfig) {
        if (fresh == config) return
        config = fresh
        people = People(fresh, strings.t(com.deskwoot.sdk.R.string.dw_support))
    }

    fun t(id: Int, vararg args: String): String = strings.t(id, *args)
}
