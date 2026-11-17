package com.deskwoot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The facade's rules that run without a device: the calls before setup and the first foreground value. */
class FacadeRulesTest {
    private val userA = DeskwootUser("u_a", "a".repeat(64))
    private val userB = DeskwootUser("u_b", "b".repeat(64))

    private fun replay(early: EarlyIdentity): List<String> {
        val calls = mutableListOf<String>()
        early.applyTo(identify = { calls += "identify ${it.identifier}" }, logout = { calls += "logout" })
        return calls
    }

    @Test
    fun `a logout before setup is replayed, before any identify that came after it`() {
        val logoutOnly = EarlyIdentity()
        logoutOnly.logout()
        assertEquals(listOf("logout"), replay(logoutOnly))

        val logoutThenUser = EarlyIdentity()
        logoutThenUser.identify(userA)
        logoutThenUser.logout()
        logoutThenUser.identify(userB)
        assertEquals(listOf("logout", "identify u_b"), replay(logoutThenUser))

        val userThenLogout = EarlyIdentity()
        userThenLogout.identify(userA)
        userThenLogout.logout()
        assertEquals(listOf("logout"), replay(userThenLogout))

        // Replayed once: a second setup with another token starts clean.
        assertTrue(replay(userThenLogout).isEmpty())
    }

    @Test
    fun `only IMPORTANCE_FOREGROUND counts as in front at setup`() {
        assertTrue(ForegroundTracker.countsAsForeground(100))
        // A foreground service, a perceptible process below API 26, a visible one, the cache.
        assertFalse(ForegroundTracker.countsAsForeground(125))
        assertFalse(ForegroundTracker.countsAsForeground(130))
        assertFalse(ForegroundTracker.countsAsForeground(200))
        assertFalse(ForegroundTracker.countsAsForeground(400))
    }
}
