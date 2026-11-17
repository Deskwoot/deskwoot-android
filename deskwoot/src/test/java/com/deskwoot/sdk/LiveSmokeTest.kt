package com.deskwoot.sdk

import com.deskwoot.sdk.api.ApiResult
import com.deskwoot.sdk.api.UrlConnectionHttpClient
import com.deskwoot.sdk.api.WidgetApi
import com.deskwoot.sdk.engine.ConversationEngine
import com.deskwoot.sdk.engine.UploadRead
import com.deskwoot.sdk.engine.Clock
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.MessageRole
import com.deskwoot.sdk.model.Phase
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.realtime.RealtimeClient
import com.deskwoot.sdk.realtime.RealtimeConnection
import com.deskwoot.sdk.store.MemoryDiskCache
import com.deskwoot.sdk.store.MemoryVisitorStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Against a real server, only when the environment names one:
 * `DESKWOOT_TEST_BASE_URL` and `DESKWOOT_TEST_WIDGET_TOKEN` (and
 * `DESKWOOT_TEST_EMAIL` for an inbox that asks for an email first). Config,
 * the first message, the poll until its row is back, and the restore of a
 * fresh session with the same store. Skipped otherwise.
 */
class LiveSmokeTest {
    private val baseUrl: String? = System.getenv("DESKWOOT_TEST_BASE_URL")?.trim()?.trimEnd('/')
    private val token: String? = System.getenv("DESKWOOT_TEST_WIDGET_TOKEN")?.trim()
    private val email: String? = System.getenv("DESKWOOT_TEST_EMAIL")?.trim()

    private object NoRealtime : RealtimeClient {
        override fun open(
            channel: String,
            auth: suspend () -> ApiResult<org.json.JSONObject>,
            listener: com.deskwoot.sdk.realtime.RealtimeListener,
            onStopped: () -> Unit,
        ): RealtimeConnection = RealtimeConnection { }
    }

    /** The JVM's clocks: `SystemClock` is Android's and is not there in a JVM test. */
    private object JvmClock : Clock {
        override fun now(): Long = System.currentTimeMillis()

        override fun elapsed(): Long = System.nanoTime() / 1_000_000
    }

    private fun engine(main: CoroutineDispatcher, store: MemoryVisitorStore): ConversationEngine = ConversationEngine(
        api = WidgetApi(UrlConnectionHttpClient("DeskwootSDK/${Deskwoot.SDK_VERSION} (JVM test)", Dispatchers.IO), { checkNotNull(baseUrl) }, checkNotNull(token)),
        realtime = NoRealtime,
        store = store,
        cache = MemoryDiskCache(),
        clock = JvmClock,
        logger = SilentLogger,
        mainDispatcher = main,
        postDispatcher = main,
        appLanguage = { "en" },
        uploadReader = { _, _ -> UploadRead.Unreadable },
        appId = "com.deskwoot.sdk.test",
        sdkVersion = Deskwoot.SDK_VERSION,
    )

    private suspend fun ConversationEngine.awaitState(seconds: Long, predicate: (SessionState) -> Boolean): SessionState =
        withTimeout(seconds * 1_000) {
            while (!predicate(state.value)) delay(250)
            state.value
        }

    @Test
    fun `config, first message, poll and restore against a live server`() {
        assumeTrue("Set DESKWOOT_TEST_BASE_URL and DESKWOOT_TEST_WIDGET_TOKEN to run", !baseUrl.isNullOrEmpty() && !token.isNullOrEmpty())
        val executor = Executors.newSingleThreadExecutor()
        val main = executor.asCoroutineDispatcher()
        val store = MemoryVisitorStore()
        try {
            runBlocking {
                val first = engine(main, store)
                withContext(main) { first.start() }
                val ready = first.awaitState(30) { it.phase != Phase.LOADING }
                assertEquals(Phase.READY, ready.phase)
                checkNotNull(ready.config)
                withContext(main) { first.setVisible(true) }

                when (val gate = checkNotNull(ready.thread).gate) {
                    Gate.None -> Unit
                    Gate.EmailCollect -> {
                        assumeTrue("The inbox asks for an email: set DESKWOOT_TEST_EMAIL", !email.isNullOrEmpty())
                        withContext(main) { first.submitEmail(checkNotNull(email)) }
                    }
                    is Gate.PreChatForm -> {
                        assumeTrue("The inbox has a pre chat form: set DESKWOOT_TEST_EMAIL", !email.isNullOrEmpty())
                        val values = gate.fields.associate { field ->
                            field.name to if (field.isEmail) checkNotNull(email) else field.options.firstOrNull() ?: "SDK smoke test"
                        }
                        withContext(main) { first.submitPreChat(values) }
                    }
                }

                val text = "Deskwoot Android SDK smoke test ${System.currentTimeMillis()}"
                withContext(main) { first.send(text) }
                val delivered = first.awaitState(60) { state ->
                    state.thread?.messages?.any { it.role == MessageRole.VISITOR && it.content == text } == true
                }
                val conversation = checkNotNull(delivered.thread?.conversationId)
                checkNotNull(store.record.bearer)
                withContext(main) { first.stop() }

                val second = engine(main, store)
                withContext(main) { second.start() }
                val restored = second.awaitState(30) { it.phase != Phase.LOADING }
                assertEquals(Phase.READY, restored.phase)
                assertEquals(conversation, restored.thread?.conversationId)
                withContext(main) { second.stop() }
            }
        } finally {
            main.close()
            executor.shutdownNow()
        }
    }
}
