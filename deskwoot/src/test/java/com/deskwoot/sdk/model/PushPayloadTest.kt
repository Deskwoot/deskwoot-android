package com.deskwoot.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a Deskwoot push carries on Android, and whether it is ours ("What arrives"). */
class PushPayloadTest {
    private val token = "wt_shop"
    private val ours = mapOf(
        "deskwoot" to "1",
        "conversationId" to "c1",
        "widgetToken" to token,
        "messageId" to "m9",
        "visitor" to "FA8CBA00A5BB1DB5",
        "title" to "Priya",
        "body" to "Yes, we ship to Austria.",
    )

    @Test
    fun `a push of this inbox is parsed`() {
        assertEquals(PushPayload("c1", "m9", "fa8cba00a5bb1db5", "Priya", "Yes, we ship to Austria."), PushPayload.parse(ours, token))
        // The token given to setup is trimmed there too.
        assertEquals("c1", PushPayload.parse(ours, " $token ")?.conversationId)
    }

    @Test
    fun `the visitor key is the start of the bearer's SHA-256, as the server computes it`() {
        assertEquals("fa8cba00a5bb1db5", com.deskwoot.sdk.engine.VisitorKey.of("wb_anna"))
        assertEquals("45ee13cf8d569ffb", com.deskwoot.sdk.engine.VisitorKey.of("wa_known"))
        // The server's own pinned vector: a bearer of the length it mints.
        assertEquals("b543967f6eccfbc8", com.deskwoot.sdk.engine.VisitorKey.of("wb_" + "a".repeat(43)))
        // The key in a push is compared in lower case.
        assertEquals(com.deskwoot.sdk.engine.VisitorKey.of("wb_anna"), PushPayload.parse(ours, token)?.visitor)
        assertNull("a push without the key names no visitor", PushPayload.parse(ours - "visitor", token)?.visitor)
    }

    @Test
    fun `the app's own pushes and those of another inbox are not ours`() {
        assertNull("the app's own push", PushPayload.parse(mapOf("title" to "Sale", "body" to "50 % off"), token))
        assertNull("no deskwoot key", PushPayload.parse(ours - "deskwoot", token))
        assertNull("another inbox", PushPayload.parse(ours + ("widgetToken" to "wt_other"), token))
        assertNull("no widget token", PushPayload.parse(ours - "widgetToken", token))
        assertNull("a session without a token", PushPayload.parse(ours, ""))
        assertNull("a blank widget token never matches", PushPayload.parse(ours + ("widgetToken" to " "), " "))
    }

    @Test
    fun `missing or blank fields are null, the push stays ours`() {
        val bare = PushPayload.parse(mapOf("deskwoot" to "1", "widgetToken" to token, "title" to "  "), token)
        assertEquals(PushPayload(null, null, null, null, null), bare)
        // Java maps may hold nulls.
        val withNulls: Map<String, String?> = mapOf("deskwoot" to null, "widgetToken" to token, "conversationId" to null)
        assertEquals(PushPayload(null, null, null, null, null), PushPayload.parse(withNulls, token))
    }
}
