package com.deskwoot.sdk.ui

import android.view.View
import android.widget.TextView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.iso
import com.deskwoot.sdk.json
import com.deskwoot.sdk.ok
import com.deskwoot.sdk.row
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.conversations.ConversationRowView
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The start page and the visitor's list on the real messenger against a scripted session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {
    private fun entry(id: String, minutesAgo: Int, text: String) = json(
        "id" to id,
        "status" to "OPEN",
        "lastActivityAt" to iso(-minutesAgo * 60L),
        "unread" to 0,
        "lastMessage" to row("m-$id", content = text, at = -minutesAgo * 60L, conversationId = id, sender = json("name" to "Mia")),
    )

    @Test
    fun `the start page shows the team, Write to us and the newest conversations`() = runTest {
        val s = ScreenHarness(this, record = VisitorRecord(bearer = "wb_known"))
        s.h.listAnswer = { ok(json("conversations" to listOf(entry("c1", 5, "Your parcel left today"), entry("c2", 50, "Thanks!"), entry("c3", 500, "Hello")))) }
        s.open()

        assertTrue(s.has(R.id.dw_write_to_us))
        assertTrue(s.has(R.id.dw_team_line))
        // 2 of the 3 conversations, the newest first, a hairline only between them.
        val rows = s.all(R.id.dw_conversation_row).map { it as ConversationRowView }
        assertEquals(2, rows.size)
        assertTrue(rows[0].contentDescription.contains("Your parcel left today"))
        assertEquals(listOf(true, false), rows.map { it.separator })
        // "See all (3)" is 48 to the finger.
        val seeAll = s.find<TextView>(R.id.dw_see_all_conversations)
        assertTrue(seeAll.height >= dp(s, 48) && seeAll.width >= dp(s, 48))
        s.close()
    }

    @Test
    fun `the list draws a hairline under every row but the last, also after a new conversation joins it`() = runTest {
        val s = ScreenHarness(this, record = VisitorRecord(bearer = "wb_known"))
        s.h.listAnswer = { ok(json("conversations" to listOf(entry("c1", 5, "One"), entry("c2", 50, "Two"), entry("c3", 500, "Three")))) }
        s.open()
        s.click(R.id.dw_see_all_conversations)
        fun separators() = s.all(R.id.dw_conversation_row).map { (it as ConversationRowView).separator }
        assertEquals(listOf(true, true, false), separators())

        // A fourth, older conversation arrives at the end: the row above it gets its hairline.
        s.h.listAnswer = { ok(json("conversations" to listOf(entry("c1", 5, "One"), entry("c2", 50, "Two"), entry("c3", 500, "Three"), entry("c4", 900, "Four")))) }
        s.session.refresh()
        s.pump()
        assertEquals(listOf(true, true, true, false), separators())
        s.close()
    }

    @Test
    fun `Write to us opens the thread with the greeting`() = runTest {
        val s = ScreenHarness(this).open()
        s.click(R.id.dw_write_to_us)
        assertTrue(s.has(R.id.dw_thread_list))
        assertTrue(s.texts().contains("Hi there"))
        assertEquals(View.VISIBLE, s.find<View>(R.id.dw_composer_input).visibility)
        s.close()
    }

    private fun dp(s: ScreenHarness, value: Int): Int = (value * s.activity.resources.displayMetrics.density).toInt()
}
