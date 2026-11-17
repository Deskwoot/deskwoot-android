package com.deskwoot.sdk.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The inbox's settings from `GET /api/widget/config`, the
 * fields a native client uses. Decoding is tolerant: unknown fields are
 * ignored and a missing field takes the value the web widget falls back to,
 * so an older or newer
 * server never stops the chat. [rawJson] is the answer as it came, kept
 * for the disk cache.
 */
internal data class WidgetConfig(
    val businessName: String,
    val inboxName: String?,
    val brandColor: String,
    val backgroundColor: String?,
    val widgetTheme: String,
    val widgetDesign: String,
    val widgetSounds: Boolean,
    val showBranding: Boolean,
    /** The side of the screen the launcher sits on: "right" (the default) or "left" (`widgetPosition`). */
    val widgetPosition: String,
    /**
     * The owner's own launcher text, translated for the language asked
     * (`launcherTitle`); null while the owner kept Deskwoot's default, which
     * the UI then words itself (`launcherDefault`).
     */
    val launcherTitle: String?,
    val poweredByUrl: String?,
    val platformSupportInbox: Boolean,
    val inboxAvatarUrl: String?,
    val featureUseInboxAvatar: Boolean,
    val agentAvatarUrl: String?,
    val companyLogoUrl: String?,
    val team: List<TeamMember>,
    val welcomeTitle: String?,
    val welcomeTagline: String?,
    val greeting: String?,
    val greetingButtons: List<String>,
    val replyTime: String?,
    val outOfOfficeMessage: String?,
    val formOfAddress: String,
    /** The inbox's fixed widget language, or null for "auto". */
    val widgetLocale: String?,
    val preChatFormEnabled: Boolean,
    val preChatFields: List<PreChatField>,
    val letBotCollectPreChatFields: Boolean,
    val emailCollectEnabled: Boolean,
    val available: Boolean,
    val featureFilePicker: Boolean,
    val allowedFileTypes: List<String>,
    val maxUploadBytes: Int,
    val featureEmojiPicker: Boolean,
    val featureEndConversation: Boolean,
    val featureTranscriptEmail: Boolean,
    /** Continuous chat: `continuousChat` or the older `persistentConversations`. */
    val continuousChat: Boolean,
    val persistentConversations: Boolean,
    val allowMessagesAfterResolved: Boolean,
    val conversationContinuityEmail: Boolean,
    val cookielessEnabled: Boolean,
    val csat: CsatConfig,
    val bot: BotConfig,
    val helpCenterEnabled: Boolean,
    val helpCenterPortalSlug: String?,
    val helpCenterBaseUrl: String?,
    val featuredArticles: List<HelpArticleSummary>,
    val featuredArticlesLocale: String?,
    val rawJson: String,
) {
    /** A live AI bot answers the inbox (`bot.enabled && bot.agentBotId`). */
    val botActive: Boolean get() = bot.enabled && bot.agentBotId != null

    /** The bot asks for the pre chat fields in the thread; no form or email step shows. */
    val botCollectsFields: Boolean get() = letBotCollectPreChatFields && botActive

    internal companion object {
        const val DEFAULT_BRAND_COLOR: String = "#2563eb"
        const val DEFAULT_MAX_UPLOAD_BYTES: Int = 5 * 1024 * 1024
        val DEFAULT_FILE_TYPES: List<String> = listOf("image/*", "application/pdf", "text/*")
        private val DESIGNS = setOf("klartext", "empfang", "direkt")
        private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

        fun parse(json: JSONObject): WidgetConfig {
            val inboxName = json.text("inboxName")
            val maxUpload = json.int("maxUploadBytes")
            val widgetLocale = json.text("widgetLocale")
            return WidgetConfig(
                // businessName || inboxName || the word "Support"; the last one is the UI's, in the session's language (`People`).
                businessName = json.text("businessName") ?: inboxName ?: "",
                inboxName = inboxName,
                brandColor = json.text("brandColor")?.takeIf { HEX_COLOR.matches(it) } ?: DEFAULT_BRAND_COLOR,
                backgroundColor = json.text("backgroundColor"),
                widgetTheme = if (json.string("widgetTheme") == "dark") "dark" else "light",
                widgetDesign = json.text("widgetDesign")?.takeIf { it in DESIGNS } ?: "klartext",
                widgetSounds = json.boolean("widgetSounds") ?: true,
                showBranding = json.boolean("showBranding") ?: true,
                widgetPosition = if (json.text("widgetPosition")?.lowercase() == "left") "left" else "right",
                // "" (or only spaces) while the owner kept the default: null.
                launcherTitle = json.text("launcherTitle"),
                poweredByUrl = json.text("poweredByUrl"),
                platformSupportInbox = json.isTrue("platformSupportInbox"),
                inboxAvatarUrl = json.text("inboxAvatarUrl"),
                featureUseInboxAvatar = json.isTrue("featureUseInboxAvatar"),
                agentAvatarUrl = json.text("agentAvatarUrl"),
                companyLogoUrl = json.text("companyLogoUrl") ?: json.text("logoUrl"),
                team = json.array("team")?.objects()?.mapNotNull(TeamMember::parse).orEmpty(),
                welcomeTitle = json.text("welcomeTitle"),
                welcomeTagline = json.text("welcomeTagline"),
                greeting = json.text("greeting"),
                greetingButtons = json.array("greetingButtons")?.strings()
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.take(4).orEmpty(),
                replyTime = json.text("replyTime"),
                outOfOfficeMessage = json.text("outOfOfficeMessage"),
                formOfAddress = if (json.string("formOfAddress") == "sie") "sie" else "du",
                widgetLocale = widgetLocale?.takeUnless { it.equals("auto", ignoreCase = true) },
                preChatFormEnabled = json.isTrue("preChatFormEnabled"),
                preChatFields = PreChatField.parseList(json.opt("preChatFormFields")),
                letBotCollectPreChatFields = json.isTrue("letBotCollectPreChatFields"),
                emailCollectEnabled = json.isTrue("emailCollectEnabled"),
                // Missing reads as true, like showBranding: an older server never shows a business as away.
                available = json.boolean("available") ?: true,
                featureFilePicker = json.isTrue("featureFilePicker"),
                allowedFileTypes = json.array("allowedFileTypes")?.strings()
                    ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DEFAULT_FILE_TYPES,
                maxUploadBytes = maxUpload?.takeIf { it > 0 } ?: DEFAULT_MAX_UPLOAD_BYTES,
                featureEmojiPicker = json.isTrue("featureEmojiPicker"),
                featureEndConversation = json.isTrue("featureEndConversation"),
                featureTranscriptEmail = json.isTrue("featureTranscriptEmail"),
                continuousChat = json.isTrue("continuousChat") || json.isTrue("persistentConversations"),
                persistentConversations = json.isTrue("persistentConversations"),
                allowMessagesAfterResolved = json.boolean("allowMessagesAfterResolved") ?: true,
                conversationContinuityEmail = json.isTrue("conversationContinuityEmail"),
                cookielessEnabled = json.isTrue("cookielessEnabled"),
                csat = CsatConfig.parse(json.obj("csat")),
                bot = BotConfig.parse(json.obj("bot")),
                helpCenterEnabled = json.isTrue("helpCenterEnabled"),
                helpCenterPortalSlug = json.text("helpCenterPortalSlug"),
                helpCenterBaseUrl = json.text("helpCenterBaseUrl"),
                featuredArticles = json.array("featuredArticles")?.objects()
                    ?.mapNotNull(HelpArticleSummary::parse)?.take(4).orEmpty(),
                featuredArticlesLocale = json.text("featuredArticlesLocale"),
                rawJson = json.rawText(),
            )
        }

        fun parse(text: String?): WidgetConfig? = parseJsonObject(text)?.let(::parse)
    }
}

/** A face of the team for the start page and the header (`config.team`). */
internal data class TeamMember(val name: String, val avatarUrl: String?) {
    internal companion object {
        fun parse(json: JSONObject): TeamMember? {
            val name = json.text("name") ?: return null
            return TeamMember(name, json.text("avatarUrl"))
        }
    }
}

/** The rating after a resolve. `enabled` already includes the plan check. */
internal data class CsatConfig(val enabled: Boolean, val ratingStyle: String, val promptText: String?) {
    internal companion object {
        private val STYLES = setOf("STARS", "HEARTS", "FIRE", "SMILEYS")

        fun parse(json: JSONObject?): CsatConfig = CsatConfig(
            enabled = json?.isTrue("enabled") == true,
            // An unknown style falls back to stars, as the web card does.
            ratingStyle = json?.text("ratingStyle")?.takeIf { it in STYLES } ?: "STARS",
            promptText = json?.text("promptText"),
        )
    }
}

/** The inbox's bot. `enabled` is the raw switch: use the send answer's botWillReply as the truth. */
internal data class BotConfig(
    val enabled: Boolean,
    val mode: String,
    val agentBotId: String?,
    val fallbackMessage: String?,
    val avatarUrl: String?,
    val name: String?,
) {
    internal companion object {
        fun parse(json: JSONObject?): BotConfig = BotConfig(
            enabled = json?.isTrue("enabled") == true,
            mode = json?.text("mode") ?: "SUPPORT",
            agentBotId = json?.text("agentBotId"),
            fallbackMessage = json?.text("fallbackMessage"),
            avatarUrl = json?.text("avatarUrl"),
            name = json?.text("name"),
        )
    }
}

/** One question of the pre chat form. */
internal data class PreChatField(
    val name: String,
    val type: String,
    val label: String?,
    val required: Boolean,
    val options: List<String>,
    /** Translated option texts, used only when there is one per option. */
    val optionLabels: List<String>?,
    val placeholder: String?,
) {
    /** An email question: `type` email or the field named email. */
    val isEmail: Boolean get() = type == "email" || name == "email"

    internal companion object {
        private val TYPES = setOf("text", "email", "tel", "phone", "textarea", "select")

        /** `{ fields: [...] }`, or a bare array on very old inbox rows. */
        fun parseList(raw: Any?): List<PreChatField> {
            val array = when (raw) {
                is JSONObject -> raw.array("fields")
                is JSONArray -> raw
                else -> null
            } ?: return emptyList()
            return array.objects().mapNotNull(::parse)
        }

        fun parse(json: JSONObject): PreChatField? {
            val name = json.text("name") ?: return null
            val options = json.array("options")?.strings().orEmpty()
            val labels = json.array("optionLabels")?.strings()
            return PreChatField(
                name = name,
                type = json.text("type")?.takeIf { it in TYPES } ?: "text",
                label = json.text("label"),
                required = json.isTrue("required"),
                options = options,
                optionLabels = labels?.takeIf { it.size == options.size },
                placeholder = json.text("placeholder"),
            )
        }
    }
}
