package com.deskwoot.sdk.ui

import com.deskwoot.sdk.ui.people.Face
import com.deskwoot.sdk.ui.thread.ActivityRow
import com.deskwoot.sdk.ui.thread.IncomingRow
import com.deskwoot.sdk.ui.thread.ThreadRow
import com.deskwoot.sdk.ui.thread.TypingRow
import com.deskwoot.sdk.ui.thread.newIncomingRows
import com.deskwoot.sdk.ui.thread.uniqueKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Row identity rules: unique keys and the typing row that becomes a reply. */
class ThreadKeysTest {
    private fun incoming(key: String) = IncomingRow(
        key = key, messageId = key.removePrefix("m:"), authorName = "Fynn", who = null, face = null, tail = true, text = "Hi",
        readback = null, linkCard = null, tracking = null, quote = null, attachments = emptyList(), canReply = true, time = "", gapBelow = 14,
    )

    @Test
    fun `two rows with one key keep the first and give the later one its own`() {
        val rows: List<ThreadRow> = listOf(ActivityRow("qr:f1", "Resolved"), incoming("m:a"), ActivityRow("qr:f1", "Resolved"), ActivityRow("qr:f1", "Resolved"))
        val keys = uniqueKeys(rows).map { it.key }
        assertEquals(listOf("qr:f1", "m:a", "qr:f1~1", "qr:f1~2"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `rows with unique keys come back unchanged`() {
        val rows: List<ThreadRow> = listOf(ActivityRow("a", "x"), incoming("m:b"))
        assertSame(rows, uniqueKeys(rows))
    }

    @Test
    fun `the typing row filled in place counts as a new reply`() {
        val seen = HashSet<String>()
        val typing = listOf<ThreadRow>(incoming("m:old"), TypingRow("m:p1", Face.Initials("F"), null, byBot = true, name = "Fynn"))
        assertEquals(listOf("m:old"), newIncomingRows(typing, seen).map { it.key })
        val answered = listOf<ThreadRow>(incoming("m:old"), incoming("m:p1"))
        assertEquals(listOf("m:p1"), newIncomingRows(answered, seen).map { it.key })
        assertEquals(emptyList<String>(), newIncomingRows(answered, seen).map { it.key })
    }
}
