package com.deskwoot.sdk

import android.app.Application
import com.deskwoot.sdk.api.FileFetcher
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.help.HelpCenterClient
import com.deskwoot.sdk.store.VisitorRecord
import com.deskwoot.sdk.ui.ScreenHarness
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The facade loads the config again at every present and on a push in front, so a new look or language reaches the UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FacadeConfigTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @After
    fun uninstall() {
        ScreenHarness.install(null)
    }

    @Test
    fun `every present and a push in front load the config again`() = runTest {
        val h = Harness(this, record = VisitorRecord(bearer = "wb_known"))
        val api = WidgetApi(h.http, { BASE_URL }, TOKEN, StandardTestDispatcher(testScheduler))
        ScreenHarness.install(ChatSession(TOKEN, h.engine, HelpCenterClient(api, h.engine::helpContext), api, FileFetcher { _, _ -> null }))
        h.start()
        fun configs(): Int {
            h.settle()
            return h.http.calls("GET /api/widget/config").size
        }
        assertEquals(1, configs())

        Deskwoot.present(app)
        assertEquals("present", 2, configs())
        h.advance(5_000)
        Deskwoot.presentNewConversation(app, "Order 4711")
        assertEquals("present again, also with the messenger open", 3, configs())

        h.advance(5_000)
        val push = mapOf("deskwoot" to "1", "conversationId" to "c1", "widgetToken" to TOKEN, "messageId" to "m9", "title" to "Priya", "body" to "Hi")
        assertTrue(Deskwoot.handlePush(app, push))
        assertEquals("a push in front", 4, configs())
    }
}
