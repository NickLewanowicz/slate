package dev.slate.android.interact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.api.ApiConfig
import dev.slate.android.api.SlateApi
import dev.slate.android.data.CacheStore
import dev.slate.android.data.CachedSlate
import dev.slate.android.data.InteractionKind
import dev.slate.android.data.PendingInteraction
import dev.slate.android.data.QueueStore
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.QuestionOption
import dev.slate.android.spec.SpecParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Branch coverage for interaction delivery states (Queued → Sending →
 * Delivered / Failed) and per-kind key routing, complementing
 * InteractionManagerTest's queue-semantics coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InteractionManagerBranchTest {

    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore
    private lateinit var manager: InteractionManager
    private val parser = SpecParser()

    private val specJson = """
        {"id":"home","version":2,"title":"Board","children":[
          {"type":"todoList","id":"focus","items":[{"id":"doors","label":"Lock doors"}]},
          {"type":"question","id":"q1","prompt":"Deploy?",
           "options":[{"id":"yes","label":"Yes"}]}
        ]}
    """.trimIndent()

    @Before
    fun setUp() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        cache = CacheStore(context, "imbranch-${System.nanoTime()}")
        queue = QueueStore(context, "imbranch-${System.nanoTime()}")
        cache.put(CachedSlate("home", specJson, "h1", "h1", fetchedAt = 100L))
        manager = InteractionManager(queue, cache, parser, clock = { 1234L })
    }

    private fun api(handler: MockRequestHandler): SlateApi = SlateApi(
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(SpecParser.defaultJson) }
        }
    )

    private fun ok(body: String = """{"accepted":1,"duplicate":0}"""): MockRequestHandler =
        { respond(body, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType to listOf("application/json"))) }

    private fun badRequest(): MockRequestHandler =
        { respond(
            """{"error":{"code":"validation_error","message":"bad","status":400}}""",
            HttpStatusCode.BadRequest,
            headersOf(HttpHeaders.ContentType to listOf("application/json")),
        ) }

    private suspend fun question(): QuestionElement =
        parser.parse(cache.get("home")!!.rawJson).children.filterIsInstance<QuestionElement>().single()

    // ---- delivery keys ----

    @Test
    fun `text reply routes under the question key and delivers`() = runTest {
        manager.sendText("home", question(), "rotate the token")
        assertEquals(DeliveryStatus.QUEUED, manager.delivery.value[manager.questionKey("home", "q1")])

        val outcome = manager.flushOnce(api(ok()), ApiConfig("http://x", "k"))
        assertTrue(outcome is FlushOutcome.Completed)
        assertEquals(DeliveryStatus.DELIVERED, manager.delivery.value[manager.questionKey("home", "q1")])
    }

    @Test
    fun `tap entries route under their own seq key`() = runTest {
        queue.enqueue(
            PendingInteraction(
                seq = "tap-1", slateId = "home", kind = InteractionKind.tap,
                clientAt = "2026-01-01T00:00:00Z", createdAt = 0,
            ),
        )
        manager.flushOnce(api(ok()), ApiConfig("http://x", "k"))
        assertEquals(DeliveryStatus.DELIVERED, manager.delivery.value["tap:tap-1"])
    }

    @Test
    fun `answer marks queued then delivered with optionLabel echo`() = runTest {
        val q = question()
        manager.answerQuestion("home", q, q.options[0])
        assertEquals(DeliveryStatus.QUEUED, manager.delivery.value[manager.questionKey("home", "q1")])
        assertNotNull(queue.answeredFor("home")["q1"]?.optionLabel)

        manager.flushOnce(api(ok()), ApiConfig("http://x", "k"))
        assertEquals(DeliveryStatus.DELIVERED, manager.delivery.value[manager.questionKey("home", "q1")])
    }

    @Test
    fun `rejected 4xx batch marks the keys FAILED`() = runTest {
        manager.toggleTodo("home", "focus", "doors")
        val outcome = manager.flushOnce(api(badRequest()), ApiConfig("http://x", "k"))
        outcome as FlushOutcome.Completed
        assertTrue(outcome.needsReconcile)
        assertEquals(DeliveryStatus.FAILED, manager.delivery.value[manager.todoKey("home", "focus", "doors")])
    }

    @Test
    fun `empty batches helper yields no requests`() {
        assertEquals(emptyList<List<PendingInteraction>>(), InteractionManager.batches(emptyList()))
    }

    @Test
    fun `toggle without a cached spec assumes unchecked and queues a check`() = runTest {
        val newChecked = manager.toggleTodo("ghost", "focus", "doors")
        assertTrue(newChecked)
        val entry = queue.all().single()
        assertEquals(InteractionKind.check, entry.kind)
        assertEquals("ghost", entry.slateId)
    }

    @Test
    fun `withTodoChecked ignores unknown lists and items`() = runTest {
        val spec = parser.parse(specJson)
        val unchanged = manager.withTodoChecked(spec, "wrong-list", "doors", true)
        val stillSame = manager.withTodoChecked(unchanged, "focus", "wrong-item", true)
        val list = stillSame.children.filterIsInstance<dev.slate.android.spec.TodoListElement>().single()
        assertTrue(list.items.none { it.checked })
    }

    @Test
    fun `todo key routes check and uncheck under the same key`() = runTest {
        manager.toggleTodo("home", "focus", "doors")
        val key = manager.todoKey("home", "focus", "doors")
        assertEquals(DeliveryStatus.QUEUED, manager.delivery.value[key])
        manager.toggleTodo("home", "focus", "doors") // back to unchecked — same key
        val kinds = queue.all().map { it.kind }
        assertEquals(listOf(InteractionKind.check, InteractionKind.uncheck), kinds)
        assertEquals(1, manager.delivery.value.size) // key reused, not duplicated
    }
}
