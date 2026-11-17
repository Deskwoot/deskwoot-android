package com.deskwoot.sdk.ui

import com.deskwoot.sdk.Deskwoot
import com.deskwoot.sdk.UnreadCountListener
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idle
import com.deskwoot.sdk.ui.ScriptedSession.Companion.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What `Deskwoot.addUnreadCountListener` hears, against real sessions over a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnreadCountListenerTest {
    private val heard = mutableListOf<Int>()
    private val listener = UnreadCountListener { count -> heard += count }

    @Before
    fun setUp() {
        // An inbox whose visitor has nothing unread.
        ScriptedSession().install()
    }

    @After
    fun tearDown() {
        Deskwoot.removeUnreadCountListener(listener)
        ScriptedSession().install()
    }

    /** An inbox whose stored visitor has a conversation with [unread] replies they have not seen. */
    private fun inboxWithUnread(unread: Int): ScriptedSession {
        val inbox = ScriptedSession(record = VisitorRecord(bearer = "wb_known"))
        inbox.http.on("GET /api/widget/conversations") { call ->
            if (call.query["list"] == "1") {
                ok(json("conversations" to emptyList<Any>()))
            } else {
                ok(json("conversationId" to "c1", "status" to "OPEN", "unreadCount" to unread))
            }
        }
        return inbox
    }

    @Test
    fun `a listener hears changes only, not the count at the time it is added`() {
        inboxWithUnread(3).install()
        assertTrue(idleUntil { Deskwoot.unreadCount == 3 })
        Deskwoot.addUnreadCountListener(listener)
        idle()
        assertEquals(emptyList<Int>(), heard)
    }

    @Test
    fun `a listener hears the new inbox's count after setup with another widget token`() {
        Deskwoot.addUnreadCountListener(listener)
        inboxWithUnread(3).install()
        assertTrue(idleUntil { Deskwoot.unreadCount == 3 })
        assertEquals(listOf(3), heard)

        // The visitor of the next inbox has nothing unread: the badge must not stay at 3.
        ScriptedSession().install()
        assertEquals(0, Deskwoot.unreadCount)
        assertEquals(listOf(3, 0), heard)

        // An inbox with as many unread as the listener heard last says nothing new.
        inboxWithUnread(0).install()
        idle()
        assertEquals(listOf(3, 0), heard)
    }
}
