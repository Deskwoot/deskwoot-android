package com.deskwoot.sdk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web's navigation rules. */
class NavigatorTest {
    @Test
    fun `views stack, top level views start a new trail, the same view changes nothing`() {
        val nav = Navigator()
        assertTrue(nav.goTo(Route.Conversations))
        assertTrue(nav.goTo(Route.Thread("c1")))
        assertFalse(nav.goTo(Route.Thread("c1")))
        assertEquals(listOf(Route.Start, Route.Conversations, Route.Thread("c1")), nav.routes)
        nav.goTo(Route.Help)
        assertEquals(listOf(Route.Help), nav.routes)
    }

    @Test
    fun `the form replaces itself when left`() {
        val nav = Navigator()
        nav.goTo(Route.Gate)
        nav.goTo(Route.Thread(null))
        assertEquals(listOf(Route.Start, Route.Thread(null)), nav.routes)
        assertTrue(nav.back())
        assertEquals(Route.Start, nav.current)
    }

    @Test
    fun `the form reached from a thread returns to it instead of stacking the thread twice`() {
        val nav = Navigator()
        nav.goTo(Route.Thread(null))
        nav.goTo(Route.Gate)
        nav.replace(Route.Thread(null))
        assertEquals(listOf(Route.Start, Route.Thread(null)), nav.routes)
        // From the start page the form is replaced by the thread as before.
        nav.reset(listOf(Route.Start, Route.Gate))
        nav.replace(Route.Thread(null))
        assertEquals(listOf(Route.Start, Route.Thread(null)), nav.routes)
    }

    @Test
    fun `back goes to the parent when the trail is empty, and is not handled on the start page`() {
        val nav = Navigator(listOf(Route.Article("refund-times")))
        assertEquals(Route.Help, nav.previous())
        assertTrue(nav.back())
        assertEquals(Route.Help, nav.current)
        assertTrue(nav.back())
        assertEquals(Route.Start, nav.current)
        assertNull(nav.previous())
        assertFalse(nav.back())
    }

    @Test
    fun `routes survive the saved state`() {
        val routes = listOf(Route.Start, Route.Conversations, Route.Thread("c1"), Route.Thread(null), Route.Gate, Route.Help, Route.Collection("k1", "Billing: plans"), Route.Article("refund-times"))
        assertEquals(routes, routes.map { checkNotNull(Route.decode(it.encode())) })
        assertNull(Route.decode("nonsense"))
    }

    @Test
    fun `the trail keeps at most 20 views`() {
        val nav = Navigator()
        repeat(30) { nav.goTo(Route.Thread("c$it")) }
        assertEquals(Navigator.MAX_TRAIL, nav.routes.size)
    }

    @Test
    fun `in Direkt the help opens over the chat instead of starting a new trail`() {
        val nav = Navigator(listOf(Route.Start))
        nav.topLevel = { it.isTopLevel && !DirektDesign.isSheet(it) }
        assertTrue(nav.goTo(Route.Help))
        org.junit.Assert.assertEquals(listOf(Route.Start, Route.Help), nav.routes)
        nav.goTo(Route.Collection("c", "C"))
        assertTrue(nav.back())
        assertTrue(nav.back())
        org.junit.Assert.assertEquals(listOf(Route.Start), nav.routes)
    }
}
