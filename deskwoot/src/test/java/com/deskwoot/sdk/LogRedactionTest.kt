package com.deskwoot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Secrets never reach the developer log. */
class LogRedactionTest {
    @Test
    fun `bearers, placeholders, tokens and signatures are masked`() {
        val line = LogRedaction.redact(
            "sent as wb_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcdef, placeholder 6f1c2e0a-3b7d-4c55-9e0f-0b2a7d1e9c44, " +
                "url ?accessToken=tok.123&contactIdentifier=wb_x&identifierHash=${"a".repeat(64)} hash ${"b".repeat(64)}",
        )
        assertTrue(!line.contains("wb_AbCd"))
        assertTrue(!line.contains("6f1c2e0a"))
        assertTrue(!line.contains("tok.123"))
        assertTrue(!line.contains("aaaaaaaa"))
        assertTrue(!line.contains("bbbbbbbb"))
        assertEquals("HTTP 404: unknown widget token", LogRedaction.redact("HTTP 404: unknown widget token"))
    }
}
