package dev.slate.android.interact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.api.ApiConfig
import dev.slate.android.api.SlateApi
import dev.slate.android.data.CacheStore
import dev.slate.android.data.CachedSlate
import dev.slate.android.data.QueueStore
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.QuestionOption
import dev.slate.android.spec.SpecParser
import dev.slate.android.spec.TodoListElement
import dev.slate.android.spec.TodoItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
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
import java.io.IOException

/**
 * Toggle → optimistic cache + durable queue → flush → confirm semantics:
 * queue entries leave ONLY on 2xx; 4xx drops + reconcile; network errors keep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InteractionManagerTest {

    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore
    private lateinit var manager: InteractionManager
    private val parser = SpecParser()

    private val specJson = """
        {"id":"home","version":2,"title":"Board","children":[
          {"type":"todoList","id":"focus","items":[
            {"id":"doors","label":"Lock doors"},
            {"id":"garage","label":"Close garage"}]},
          {"type":"question","id":"q1","prompt":"Deploy?",
           "options":[{"id":"yes","label":"Yes","style":"primary"},{"id":"no","label":"No"}],
           "allowText":true,"placeholder":"say more"}
        ]}
    """.trimIndent()

    @Before
    fun setUp() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        cache = CacheStore(context)
        queue = QueueStore(context)
        cache.put(CachedSlate("home", specJson, "h1", "h1", fetchedAt = 100L))
        manager = InteractionManager(queue, cache, parser, clock = { 1234L }, uuid = { "seq-${(0..9999).random()}" })
    }

    private fun api(handler: MockRequestHandler): SlateApi =
        SlateApi(
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json(dev.slate.android.spec.SpecParser.defaultJson) }
            }
        )

    private fun okHandler(body: String = """{"accepted":1,"duplicate":0}"""): MockRequestHandler =
        { request ->
            requests.add(request.url.toString() + " " + request.headers["Authorization"])
            respond(body, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())))
        }

    private val requests = mutableListOf<String>()

    private suspend fun todoList(): TodoListElement =
        parser.parse(cache.get("home")!!.rawJson).children.filterIsInstance<TodoListElement>().single()

    private suspend fun question(): QuestionElement =
        parser.parse(cache.get("home")!!.rawJson).children.filterIsInstance<QuestionElement>().single()

    // ---- optimistic apply ordering ----

    @Test
    fun `toggleTodo flips cached spec and enqueues check together`() = runTest {
        val newChecked = manager.toggleTodo("home", "focus", "doors")
        assertTrue(newChecked)

        // Optimistic state persisted in cache
        val item = todoList().items.first { it.id == "doors" }
        assertTrue(item.checked)
        // Queue holds the interaction
        val queued = queue.all()
        assertEquals(1, queued.size)
        assertEquals(dev.slate.android.data.InteractionKind.check, queued[0].kind)
        assertEquals("focus", queued[0].elementId)
        assertEquals("doors", queued[0].itemId)
        // clientAt = tap time (from the injected clock 1234L)
        assertTrue("clientAt should be tap ISO time", queued[0].clientAt.startsWith("1970-01-01T00:00:01"))
        assertEquals(DeliveryStatus.QUEUED, manager.delivery.value[manager.todoKey("home", "focus", "doors")])
    }

    @Test
    fun `toggle twice flips back and queues uncheck`() = runTest {
        manager.toggleTodo("home", "focus", "doors")
        manager.toggleTodo("home", "focus", "doors")
        val kinds = queue.all().map { it.kind }
        assertEquals(listOf(dev.slate.android.data.InteractionKind.check, dev.slate.android.data.InteractionKind.uncheck), kinds)
        assertFalse(todoList().items.first { it.id == "doors" }.checked)
    }

    @Test
    fun `answerQuestion records answered marker with optionLabel echo`() = runTest {
        val q = question()
        manager.answerQuestion("home", q, q.options[0])
        val answered = queue.answeredFor("home")
        assertEquals("yes", answered["q1"]?.optionId)
        assertEquals("Yes", answered["q1"]?.optionLabel)
        val entry = queue.all().single()
        assertEquals(dev.slate.android.data.InteractionKind.answer, entry.kind)
        assertEquals("Yes", entry.optionLabel)
        assertEquals("q1", entry.questionId)
    }

    @Test
    fun `blank sendText is ignored and real values are queued`() = runTest {
        val q = question()
        manager.sendText("home", q, "  ")
        assertTrue(queue.all().isEmpty())
        manager.sendText("home", q, "rotate the token")
        val entry = queue.all().single()
        assertEquals(dev.slate.android.data.InteractionKind.text, entry.kind)
        assertEquals("rotate the token", entry.value)
        assertEquals("rotate the token", queue.answeredFor("home")["q1"]?.value)
    }

    // ---- flush ----

    @Test
    fun `flush removes entries only on 2xx and marks DELIVERED`() = runTest {
        manager.toggleTodo("home", "focus", "doors")
        val outcome = manager.flushOnce(api(okHandler()), ApiConfig("http://x", "k"))
        val completed = outcome as FlushOutcome.Completed
        assertEquals(1, completed.accepted)
        assertTrue(queue.all().isEmpty())
        assertEquals(DeliveryStatus.DELIVERED, manager.delivery.value[manager.todoKey("home", "focus", "doors")])
        // Bearer auth reached the API
        assertTrue(requests.single().contains("Bearer k"))
    }

    @Test
    fun `flush is batched at 50 per request`() = runTest {
        repeat(120) { manager.toggleTodo("home", "focus", "doors") } // 120 entries
        var requestCount = 0
        val handler: MockRequestHandler = { _ ->
            synchronized(this) { requestCount++ }
            respond(
                """{"accepted":50,"duplicate":0}""",
                HttpStatusCode.Accepted,
                headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())),
            )
        }
        val outcome = manager.flushOnce(api(handler), ApiConfig("http://x", "k"))
        assertEquals(120, (outcome as FlushOutcome.Completed).accepted)
        assertTrue(queue.all().isEmpty())
        // 120 entries @ MAX_BATCH 50 → 3 requests
        assertEquals(3, requestCount)
        assertEquals(
            listOf(50, 50, 20),
            InteractionManager.batches(
                List(120) {
                    dev.slate.android.data.PendingInteraction(
                        "s$it", "home", dev.slate.android.data.InteractionKind.tap, clientAt = "", createdAt = 0,
                    )
                }
            ).map { it.size }
        )
    }

    @Test
    fun `4xx drops the batch and asks for reconcile`() = runTest {
        manager.toggleTodo("home", "focus", "doors")
        val handler: MockRequestHandler = { request ->
            respond(
                """{"error":{"code":"validation_error","message":"bad","hint":"fix it","status":400}}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())),
            )
        }
        val outcome = manager.flushOnce(api(handler), ApiConfig("http://x", "k"))
        outcome as FlushOutcome.Completed
        assertTrue(outcome.needsReconcile)
        assertEquals(1, outcome.droppedBatches)
        assertTrue("4xx entries are dropped", queue.all().isEmpty())
        assertEquals(DeliveryStatus.FAILED, manager.delivery.value[manager.todoKey("home", "focus", "doors")])
    }

    @Test
    fun `5xx keeps entries for retry`() = runTest {
        manager.toggleTodo("home", "focus", "doors")
        val handler: MockRequestHandler = { request ->
            respond("boom", HttpStatusCode.InternalServerError)
        }
        var threw = false
        try {
            manager.flushOnce(api(handler), ApiConfig("http://x", "k"))
        } catch (e: dev.slate.android.api.SlateApiException) {
            threw = e.status == 500
        }
        assertTrue(threw)
        assertEquals(1, queue.all().size)
    }

    @Test
    fun `network error keeps entries and propagates for WorkManager retry`() = runTest {
        manager.toggleTodo("home", "focus", "doors")
        val handler: MockRequestHandler = { request -> throw IOException("airplane mode") }
        var threw = false
        try {
            manager.flushOnce(api(handler), ApiConfig("http://x", "k"))
        } catch (e: IOException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals("entry kept for retry", 1, queue.all().size)
        assertEquals(DeliveryStatus.SENDING, manager.delivery.value[manager.todoKey("home", "focus", "doors")])
    }

    @Test
    fun `flush with empty queue is a no-op`() = runTest {
        assertEquals(FlushOutcome.NothingQueued, manager.flushOnce(api(okHandler()), ApiConfig("http://x", "k")))
    }

    // ---- pure helpers ----

    @Test
    fun `withTodoChecked only mutates the target item`() {
        val spec = parser.parse(specJson)
        val updated = manager.withTodoChecked(spec, "focus", "garage", true)
        val items = updated.children.filterIsInstance<TodoListElement>().single().items
        assertTrue(items.first { it.id == "garage" }.checked)
        assertFalse(items.first { it.id == "doors" }.checked)
        // original untouched (pure)
        assertFalse(spec.children.filterIsInstance<TodoListElement>().single().items.first { it.id == "garage" }.checked)
    }

    @Test
    fun `findQuestion routes widget taps`() = runTest {
        assertNotNull(manager.findQuestion("home", "q1"))
        assertFalse(manager.findQuestion("home", "missing") != null)
        assertFalse(manager.findQuestion("nope", "q1") != null)
    }
}
