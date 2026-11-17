package com.deskwoot.sdk.ui

import android.net.Uri
import androidx.core.net.toUri

/**
 * The messenger's views. A route says what a screen
 * shows; the screen itself draws from the session.
 */
internal sealed class Route {
    /** The start page. */
    object Start : Route()

    /** The help center's start. */
    object Help : Route()

    object Conversations : Route()

    /** A thread. [conversationId] null: whatever the session's thread is (a new one, or the current one). */
    data class Thread(val conversationId: String?) : Route()

    /** The pre chat form or the email step ("Before we start"). */
    object Gate : Route()

    data class Collection(val id: String, val name: String) : Route()

    data class Article(val slug: String) : Route()

    /** Top level views start a new trail. */
    val isTopLevel: Boolean get() = this == Start || this == Help

    /** A string that names the route, for the saved state. */
    fun encode(): String = when (this) {
        Start -> "start"
        Help -> "help"
        Conversations -> "conversations"
        Gate -> "gate"
        is Thread -> "thread:" + (conversationId ?: "")
        is Collection -> "collection:" + id + "\u0000" + name
        is Article -> "article:$slug"
    }

    internal companion object {
        fun decode(text: String): Route? {
            val kind = text.substringBefore(':')
            val rest = text.substringAfter(':', "")
            return when (kind) {
                "start" -> Start
                "help" -> Help
                "conversations" -> Conversations
                "gate" -> Gate
                "thread" -> Thread(rest.ifEmpty { null })
                "collection" -> {
                    val id = rest.substringBefore('\u0000')
                    if (id.isEmpty()) null else Collection(id, rest.substringAfter('\u0000', ""))
                }
                "article" -> if (rest.isEmpty()) null else Article(rest)
                else -> null
            }
        }
    }
}

/**
 * The trail of one look in another (the inbox changed its look while the
 * messenger is open): the visitor stays on the screen they are on, in the
 * same conversation. Direkt shows the chat on its start page with the other
 * views as sheets over it; Klartext and Empfang have a start page of their
 * own and a thread screen.
 */
internal object LookSwitch {
    /**
     * [routes] of [from] as routes of [to]. [conversationId]: the conversation
     * the session shows (null: a new one), which Direkt's start page draws.
     */
    fun routes(routes: List<Route>, from: MessengerDesign, to: MessengerDesign, conversationId: String?): List<Route> {
        if (from.chatIsStart == to.chatIsStart) return routes
        return if (to.chatIsStart) intoChatStart(routes) else outOfChatStart(routes, conversationId)
    }

    /** Klartext or Empfang into Direkt: a thread is the chat of the start page, the rest goes over it. */
    private fun intoChatStart(routes: List<Route>): List<Route> {
        val out = arrayListOf<Route>(Route.Start)
        for (route in routes) {
            if (route == Route.Start || route is Route.Thread) continue
            out += route
        }
        return out
    }

    /** Direkt into Klartext or Empfang: the chat becomes the thread screen; the help is a view of its own there. */
    private fun outOfChatStart(routes: List<Route>, conversationId: String?): List<Route> {
        var out = arrayListOf<Route>(Route.Start, Route.Thread(conversationId))
        for (route in routes) {
            when {
                route == Route.Start || route is Route.Thread -> Unit
                // The form replaces itself with the thread once answered: it opens from the start page.
                route == Route.Gate -> out = arrayListOf(Route.Start, Route.Gate)
                route.isTopLevel -> out = arrayListOf(route)
                else -> out += route
            }
        }
        return out
    }

    /** Screens that show the same thing in both looks: the chat (a thread, or Direkt's start page), else the route itself. */
    fun sameScreen(route: Route, design: MessengerDesign): String =
        if (route is Route.Thread || (route == Route.Start && design.chatIsStart)) "chat" else route.encode()
}

/** What a link of the messenger is to the SDK. */
internal enum class LinkKind {
    /** http or https: the browser, or the app that owns the address. */
    WEB,

    /** mailto or tel: the system's mail or phone app. */
    SYSTEM,

    /** Anything else (`file:`, `content:`, `intent:`, `javascript:`, an app's deep link, a relative address): never opened. */
    BLOCKED,
}

/**
 * The one scheme rule for every link the messenger opens: in a reply, a
 * link card, an article, the help center link, the branding. Only http,
 * https, mailto and tel ever leave the SDK, so a link can neither expose a
 * file (`FileUriExposedException`) nor reach the host app's own deep links.
 */
internal object Links {
    val ALLOWED_SCHEMES: Set<String> = setOf("http", "https", "mailto", "tel")

    /** Schemes compare without regard to case ("HTTPS://" is a web link). */
    fun kind(url: String): LinkKind = when (scheme(url)) {
        "http", "https" -> if (hasHost(url)) LinkKind.WEB else LinkKind.BLOCKED
        "mailto", "tel" -> LinkKind.SYSTEM
        else -> LinkKind.BLOCKED
    }

    /**
     * The address a link leaves the SDK with, its scheme in lower case
     * (apps that open links match the scheme exactly, so "HTTPS://" would
     * open nothing); null for a link that never leaves.
     */
    fun outgoing(url: String): Uri? = if (kind(url) == LinkKind.BLOCKED) null else url.trim().toUri().normalizeScheme()

    /** The scheme of an address in lower case, or null for a relative one ("a/b:c" and "?q=a:b" are relative). */
    fun scheme(value: String): String? {
        val trimmed = value.trim { it.isWhitespace() || it.isISOControl() }
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return null
        val head = trimmed.substring(0, colon)
        if (head.any { it == '/' || it == '?' || it == '#' }) return null
        return head.filterNot { it.isWhitespace() || it.isISOControl() }.lowercase()
    }

    /** "https:" alone or "http:foo" name no page. */
    private fun hasHost(url: String): Boolean {
        val rest = url.trim().substringAfter(':')
        return rest.startsWith("//") && rest.length > 2 && rest[2] != '/'
    }
}

/**
 * The web's navigation rules: the current view and a
 * trail of earlier views. The same view again changes nothing; top level
 * views start a new trail; leaving the form replaces it (it never comes
 * back); back takes the last trail entry, else the parent (a collection and
 * an article go to the help, everything else to the start page), and on
 * the start page it is not handled (the Activity closes).
 */
internal class Navigator(initial: List<Route> = listOf(Route.Start)) {
    private val stack = ArrayList<Route>(initial.ifEmpty { listOf(Route.Start) })

    /** Which views start a new trail; Direkt's help is a sheet over the chat, not one of them. */
    var topLevel: (Route) -> Boolean = { it.isTopLevel }

    val routes: List<Route> get() = stack.toList()

    val current: Route get() = stack.last()

    /** Goes to [route]; false when nothing changed. */
    fun goTo(route: Route): Boolean {
        if (route == current) return false
        when {
            topLevel(route) -> {
                stack.clear()
                stack += route
            }
            current == Route.Gate -> stack[stack.lastIndex] = route
            else -> {
                stack += route
                while (stack.size > MAX_TRAIL) stack.removeAt(0)
            }
        }
        return true
    }

    /**
     * Switches to [route] without a step back. When
     * [route] is the entry behind the current one, this is that step back:
     * the form reached from a thread returns to it instead of stacking the
     * same thread twice.
     */
    fun replace(route: Route) {
        if (stack.size > 1 && stack[stack.lastIndex - 1] == route) {
            stack.removeAt(stack.lastIndex)
            return
        }
        stack[stack.lastIndex] = route
    }

    /** Sets the whole trail, the last entry on screen. */
    fun reset(routes: List<Route>) {
        stack.clear()
        stack += routes.ifEmpty { listOf(Route.Start) }
    }

    /** One step back; false on the start page with nothing behind it. */
    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        val parent = when (current) {
            Route.Start -> return false
            is Route.Collection, is Route.Article -> Route.Help
            else -> Route.Start
        }
        stack[0] = parent
        return true
    }

    /** Where back leads, without going there (for the predictive back gesture). */
    fun previous(): Route? = when {
        stack.size > 1 -> stack[stack.lastIndex - 1]
        current == Route.Start -> null
        current is Route.Collection || current is Route.Article -> Route.Help
        else -> Route.Start
    }

    internal companion object {
        const val MAX_TRAIL: Int = 20
    }
}
