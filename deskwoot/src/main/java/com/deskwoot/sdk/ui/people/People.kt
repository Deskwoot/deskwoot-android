package com.deskwoot.sdk.ui.people

import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.Message
import com.deskwoot.sdk.model.MessageRole
import com.deskwoot.sdk.model.SenderKind
import com.deskwoot.sdk.model.ThreadState
import com.deskwoot.sdk.model.WidgetConfig
import com.deskwoot.sdk.ui.text.Texts

/** What a face shows. */
internal sealed class Face {
    /** A team member's photo, or the inbox picture for the team; a failed load shows the person glyph. */
    data class Photo(val url: String) : Face()

    /** 1 or 2 letters on the warm beige of a person without a photo. */
    data class Initials(val letters: String) : Face()

    /** The bot: its picture, else Fynn's mark on the brand fill. */
    data class Fynn(val url: String?) : Face()

    /** An AI agent: the robot glyph on the brand gradient. */
    object Robot : Face()

    /** The business: the logo (a rounded square), else its first letter on the brand fill. */
    data class Logo(val url: String?, val letter: String) : Face()
}

internal enum class AuthorRole { AI, BOT, PERSON, BUSINESS }

/** Who speaks: a name, a face and a role. */
internal data class Author(val name: String, val face: Face, val role: AuthorRole)

/**
 * The web widget's people rules for the thread, the list, the header and the cards:
 * who wrote a row, who the header shows, which faces the start page
 * stacks. [fallbackName] is the word
 * "Support" in the session's language (`dw_support`), for an inbox that
 * names neither the business nor itself.
 */
internal class People(private val config: WidgetConfig, fallbackName: String) {
    val businessName: String = config.businessName.trim().ifEmpty { config.inboxName?.trim().orEmpty() }.ifEmpty { fallbackName }
    private val logoUrl: String? = config.companyLogoUrl ?: config.inboxAvatarUrl
    val botName: String? = config.bot.name?.trim()?.takeIf { it.isNotEmpty() }
    private val botAvatarUrl: String? = if (config.botActive) config.bot.avatarUrl else null
    private val inboxAvatarForTeam: String? = if (config.featureUseInboxAvatar) config.inboxAvatarUrl else null

    val business: Author get() = Author(businessName, Face.Logo(logoUrl, initialLetter(businessName)), AuthorRole.BUSINESS)

    val bot: Author get() = Author(botName ?: businessName, Face.Fynn(botAvatarUrl), AuthorRole.BOT)

    /** Who answers first: the bot when one answers this inbox, else the business. */
    val firstAnswerer: Author get() = if (config.botActive) bot else business

    /** The author of [m]; the greeting speaks as the first answerer. */
    fun authorOf(m: Message): Author {
        val sender = m.sender
        if (sender?.kind == SenderKind.AI_AGENT) return Author(sender.name ?: businessName, Face.Robot, AuthorRole.AI)
        if (m.attributes.chatTriggerId != null) return firstAnswerer
        if (m.role == MessageRole.BOT) {
            val name = sender?.name
            if (name == null) return business
            return Author(name, Face.Fynn(sender.avatarUrl ?: botAvatarUrl), AuthorRole.BOT)
        }
        if (sender?.kind == SenderKind.PERSON && sender.name != null) {
            val photo = inboxAvatarForTeam ?: sender.avatarUrl
            return Author(sender.name, if (photo != null) Face.Photo(photo) else Face.Initials(initials(sender.name)), AuthorRole.PERSON)
        }
        return business
    }

    /** The greeting row's author: the bot when a bot answers, else the business. */
    fun greetingAuthor(): Author = firstAnswerer

    /** The thread header: whoever answered last, skipping typing, trigger and automated rows. */
    fun headerAuthor(messages: List<Message>): Author {
        for (m in messages.asReversed()) {
            if (m.isTyping || m.attributes.chatTriggerId != null || m.automated) continue
            if (m.role == MessageRole.AGENT || m.role == MessageRole.BOT) return authorOf(m)
        }
        return firstAnswerer
    }

    /** The face next to the typing dots: the bot while it types, else the team member who wrote last. */
    fun typingAuthor(messages: List<Message>, botTyping: Boolean): Author {
        if (botTyping && config.botActive) return bot
        for (m in messages.asReversed()) {
            if (m.role == MessageRole.AGENT && m.sender?.kind == SenderKind.PERSON && !m.isTyping &&
                m.attributes.chatTriggerId == null && !m.automated
            ) {
                return authorOf(m)
            }
        }
        return business
    }

    /** The start page's faces: the bot first, then the team, at most 3; nobody: the business logo. */
    fun startFaces(): List<Face> {
        val faces = ArrayList<Face>()
        if (config.botActive) faces += bot.face
        for (member in config.team) {
            if (faces.size >= 3) break
            faces += teamFace(member.name, member.avatarUrl)
        }
        if (faces.isEmpty()) faces += business.face
        return faces.take(3)
    }

    /** The team without the bot, at most 3 (Direkt's team line). */
    fun teamFaces(): List<Face> = config.team.take(3).map { teamFace(it.name, it.avatarUrl) }

    /** Empfang's hero: the team first and the bot LAST, at most 3. */
    fun heroFaces(): List<Face> {
        val faces = ArrayList<Face>(teamFaces())
        if (config.botActive) faces += bot.face
        if (faces.isEmpty()) faces += business.face
        return faces.takeLast(3).let { if (config.botActive && faces.size > 3) faces.take(2) + bot.face else it }
    }

    private fun teamFace(name: String, avatarUrl: String?): Face {
        val photo = inboxAvatarForTeam ?: avatarUrl
        return if (photo != null) Face.Photo(photo) else Face.Initials(initials(name))
    }

    internal companion object {
        /** Up to 2 letters: the first of the first 2 words, upper case; "?" for none. */
        fun initials(name: String): String {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val letters = words.take(2).mapNotNull { word -> firstGrapheme(word) }.joinToString("") { it.uppercase() }
            return letters.ifEmpty { "?" }
        }

        fun initialLetter(name: String): String = firstGrapheme(name.trim())?.uppercase() ?: "?"

        private fun firstGrapheme(word: String): String? {
            if (word.isEmpty()) return null
            val cp = word.codePointAt(0)
            return String(Character.toChars(cp))
        }
    }
}

/** The text of the start page's team line: a bold lead and an optional second line. */
internal data class TeamLine(val lead: String, val rest: String?) {
    /** Both as sentences in one paragraph (Empfang's ask card, Direkt). */
    fun asSentences(texts: Texts): String =
        if (rest == null) texts.sentence(lead) else texts.sentences(texts.sentence(lead), rest)

    internal companion object {
        fun of(config: WidgetConfig, people: People, texts: Texts): TeamLine {
            if (config.botActive) {
                val rest = when (config.replyTime) {
                    "in_a_few_hours" -> texts.t(R.string.dw_team_hours)
                    "in_a_day" -> texts.t(R.string.dw_team_day)
                    else -> texts.t(R.string.dw_team_minutes)
                }
                return TeamLine(texts.t(R.string.dw_bot_answers_now, people.botName ?: people.businessName), rest)
            }
            return TeamLine(replyLine(config.replyTime, texts) ?: texts.t(R.string.dw_reply_minutes), null)
        }

        /** "Typically replies in a few minutes" and its siblings; null for an unknown reply time. */
        fun replyLine(replyTime: String?, texts: Texts): String? = when (replyTime) {
            "in_a_few_minutes" -> texts.t(R.string.dw_reply_minutes)
            "in_a_few_hours" -> texts.t(R.string.dw_reply_hours)
            "in_a_day" -> texts.t(R.string.dw_reply_day)
            else -> null
        }
    }
}

/** The thread header's name, sub line and online dot. */
internal data class HeaderLine(val author: Author, val subtitle: String, val online: Boolean) {
    internal companion object {
        /** [direkt]: Direkt's header names the business and words the sub line as a sentence. */
        fun of(thread: ThreadState?, config: WidgetConfig, people: People, texts: Texts, direkt: Boolean = false): HeaderLine {
            val messages = thread?.messages.orEmpty()
            val author = people.headerAuthor(messages)
            val ended = thread?.inEndState == true
            return when {
                thread?.waitingForTeam == true && !thread.resolved -> HeaderLine(author, texts.t(R.string.dw_connecting), false)
                author.role == AuthorRole.PERSON && ended -> HeaderLine(author, people.businessName, false)
                author.role == AuthorRole.PERSON && direkt -> HeaderLine(author, texts.t(R.string.dw_is_in_the_chat, author.name), config.available)
                author.role == AuthorRole.PERSON ->
                    HeaderLine(author, "${people.businessName} · ${texts.t(R.string.dw_in_the_chat)}", config.available)
                author.role == AuthorRole.BOT && direkt ->
                    HeaderLine(author, texts.t(R.string.dw_bot_answers_now, author.name).trimEnd('.', '。', ' '), true)
                author.role == AuthorRole.BOT ->
                    HeaderLine(author, "${texts.t(R.string.dw_ai_assistant)} · ${texts.t(R.string.dw_answers_right_away)}", false)
                else -> {
                    val replied = thread?.hasAgentReply == true
                    val reply = if (replied) null else TeamLine.replyLine(config.replyTime, texts)
                    val line = reply ?: texts.t(if (config.available) R.string.dw_online else R.string.dw_away)
                    HeaderLine(author, line, config.available)
                }
            }
        }
    }
}
