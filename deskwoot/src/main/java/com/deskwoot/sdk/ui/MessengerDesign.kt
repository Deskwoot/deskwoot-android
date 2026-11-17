package com.deskwoot.sdk.ui

import com.deskwoot.sdk.ui.theme.UiColors

/** How choice and start buttons look. */
internal enum class ChoiceStyle {
    /** Klartext: full width rows indented by the face column, brand text, a chevron. */
    INDENTED_ROWS,

    /** Empfang: end aligned pills with a brand border. */
    PILLS,

    /** Direkt: full width cards in ink. */
    CARDS,
}

/** Which header a screen gets. */
internal enum class HeaderStyle {
    /** Klartext's `k-head`: back, title 15/600, close, a hairline under it. */
    PLAIN,

    /** Empfang's `e-bar` (and Direkt's forms): back, title 16/650, close, min height 60. */
    BAR,

    /** Empfang's chat header on the colored band. */
    BAND,

    /** Direkt's sheet head: a grabber, the title 18/700, back only inside the sheet, close. */
    SHEET,
}

/** The strip around the composer. */
internal enum class ComposerStyle {
    /** A hairline on top, padding 10/12/0, the bar on bg-2. */
    KLARTEXT,

    /** bg with a hairline on top, padding 10/12/4; the lines under it on bg too. */
    EMPFANG,

    /** No line, padding 6/12/0, the bar on bg with a line-2 border and a soft shadow. */
    DIREKT,
}

/**
 * The inbox's look (`config.widgetDesign`)
 * as the one seam the screens ask. The thread, the composer, the forms,
 * the survey and the article body are the same in every design; what
 * differs is answered here, so no screen branches on the design itself.
 *
 * Klartext: a plain top bar with a segmented control, the start page as
 * text with one primary button. Empfang: a colored hero band, cards on a
 * canvas, a bottom tab bar, the chat header on the band. Direkt: the chat
 * is the start page; the list, the help, a collection and an article open
 * as sheets over it.
 */
internal interface MessengerDesign {
    /** The config value this design answers to. */
    val id: String

    /** Where the messenger opens. Direkt's start page IS the chat. */
    val startRoute: Route get() = Route.Start

    /** The chat is the start page: a thread route shows on the start page (Direkt). */
    val chatIsStart: Boolean get() = false

    /** Screens that share one view (Klartext and Empfang: the start page and the help). */
    fun screenKind(route: Route): String = when (route) {
        Route.Start, Route.Help -> "home"
        else -> route.encode()
    }

    /** The incoming bubble's color (`--bub-in`). */
    fun incomingBubble(colors: UiColors): Int

    /** The page color behind a screen's body. */
    fun canvas(colors: UiColors): Int

    val choiceStyle: ChoiceStyle

    /** Whether the greeting is a bubble at the top of the thread (Direkt shows it in a header block instead). */
    val greetingAsBubble: Boolean get() = true

    val composerStyle: ComposerStyle

    /** Whether the composer bar has a hairline on top of its strip. */
    val composerTopLine: Boolean get() = composerStyle != ComposerStyle.DIREKT

    /** The header of a screen below the start page. */
    fun headerStyle(route: Route): HeaderStyle

    /** Whether [route] opens as a sheet over the chat (Direkt). */
    fun isSheet(route: Route): Boolean = false

    /** Whether [route] carries Empfang's bottom tab bar (start page, list, help, collection). */
    fun tabBar(route: Route): Boolean = false
}

/** Klartext, the default and Deskwoot's own look. */
internal object KlartextDesign : MessengerDesign {
    override val id: String = "klartext"

    override fun incomingBubble(colors: UiColors): Int = colors.kBubble

    override fun canvas(colors: UiColors): Int = colors.bg

    override val choiceStyle: ChoiceStyle = ChoiceStyle.INDENTED_ROWS
    override val composerStyle: ComposerStyle = ComposerStyle.KLARTEXT

    override fun headerStyle(route: Route): HeaderStyle = HeaderStyle.PLAIN
}

/** Empfang: the reception desk. A colored band, white cards on a gray canvas, tabs at the bottom. */
internal object EmpfangDesign : MessengerDesign {
    override val id: String = "empfang"

    override fun incomingBubble(colors: UiColors): Int = colors.eCard

    override fun canvas(colors: UiColors): Int = colors.eCanvas

    override val choiceStyle: ChoiceStyle = ChoiceStyle.PILLS
    override val composerStyle: ComposerStyle = ComposerStyle.EMPFANG

    override fun headerStyle(route: Route): HeaderStyle = if (route is Route.Thread) HeaderStyle.BAND else HeaderStyle.BAR

    override fun tabBar(route: Route): Boolean =
        route == Route.Start || route == Route.Help || route == Route.Conversations || route is Route.Collection
}

/** Direkt: straight into the chat; everything else is a sheet over it. */
internal object DirektDesign : MessengerDesign {
    override val id: String = "direkt"
    override val chatIsStart: Boolean = true

    override fun screenKind(route: Route): String = route.encode()

    override fun incomingBubble(colors: UiColors): Int = colors.gBubble

    override fun canvas(colors: UiColors): Int = colors.directCanvas

    override val choiceStyle: ChoiceStyle = ChoiceStyle.CARDS
    override val greetingAsBubble: Boolean = false
    override val composerStyle: ComposerStyle = ComposerStyle.DIREKT

    override fun headerStyle(route: Route): HeaderStyle = if (isSheet(route)) HeaderStyle.SHEET else HeaderStyle.BAR

    override fun isSheet(route: Route): Boolean =
        route == Route.Help || route == Route.Conversations || route is Route.Collection || route is Route.Article
}

/** The design an inbox asks for (`config.widgetDesign`); an unknown value is Klartext. */
internal fun designFor(widgetDesign: String): MessengerDesign = when (widgetDesign) {
    EmpfangDesign.id -> EmpfangDesign
    DirektDesign.id -> DirektDesign
    else -> KlartextDesign
}
