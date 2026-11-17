package com.deskwoot.sdk.model

/**
 * A Deskwoot push as Firebase Cloud Messaging hands it to the app's
 * `onMessageReceived` ("What arrives"): a data message
 * `{ "deskwoot": "1", "conversationId", "widgetToken", "messageId",
 * "visitor", "title", "body" }`. [visitor] is the key of the visitor the
 * device was registered for ([com.deskwoot.sdk.engine.VisitorKey]), lower
 * case. [title] is who wrote (a team member's first name, the AI agent's
 * name, else the business), [body] a plain text preview of at most 160
 * characters. Every field is trimmed, null when missing or blank;
 * [conversationId] is null only for a payload the server never sends, and
 * then nothing names a conversation to open.
 */
internal data class PushPayload(
    val conversationId: String?,
    val messageId: String?,
    val visitor: String?,
    val title: String?,
    val body: String?,
) {
    internal companion object {
        /** The key every Deskwoot push carries. */
        const val KEY: String = "deskwoot"

        /**
         * The payload when [data] is a Deskwoot push for the inbox of
         * [widgetToken] (the token given to setup): it has the `deskwoot`
         * key AND its `widgetToken` is that token. Null for the app's own
         * pushes and for those of another inbox.
         */
        fun parse(data: Map<String, String?>, widgetToken: String): PushPayload? {
            if (!data.containsKey(KEY)) return null
            val own = widgetToken.trim()
            if (own.isEmpty() || data["widgetToken"]?.trim() != own) return null
            return PushPayload(
                conversationId = data.field("conversationId"),
                messageId = data.field("messageId"),
                visitor = data.field("visitor")?.lowercase(),
                title = data.field("title"),
                body = data.field("body"),
            )
        }

        private fun Map<String, String?>.field(key: String): String? = this[key]?.trim()?.takeIf { it.isNotEmpty() }
    }
}
