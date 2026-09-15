package dev.slate.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.api.SlateApi
import dev.slate.android.spec.SpecParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Branch coverage for the repository's UI-state derivation (blank titles,
 * unparsable cache entries, expiry, sorting, per-slate answered filtering) and
 * queue-store no-op guards, complementing SlateRepositoryTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryBranchTest {

    private lateinit var settings: SettingsStore
    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore
    private lateinit var repository: SlateRepository

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "repobranch-${System.nanoTime()}"
        settings = SettingsStore(context, "$name-settings")
        cache = CacheStore(context, "$name-cache")
        queue = QueueStore(context, name)
        settings.saveConnection("http://server.test", "key")
        repository = SlateRepository(
            api = SlateApi(mockClient()),
            settings = settings,
            cacheStore = cache,
            queueStore = queue,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = { 1_000_000L },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private val responses = mutableMapOf<String, Triple<HttpStatusCode, String, String?>>()

    private fun mockClient(): HttpClient = HttpClient(MockEngine { request ->
        val (status, body, etag) = responses[request.url.encodedPath]
            ?: Triple(HttpStatusCode.NotFound, """{"error":{"code":"not_found","status":404}}""", null)
        val headers = if (etag != null) {
            headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"$etag\""))
        } else {
            headersOf(HttpHeaders.ContentType to listOf("application/json"))
        }
        respond(body, status, headers)
    })

    // ---- slatesUi branches ----

    @Test
    fun `cards fall back to the slate id when the cached spec cannot be parsed`() {
        runBlocking {
            cache.put(CachedSlate("broken", "{ not json", "h", "h", fetchedAt = 1L))
            val card = repository.slatesUi.first { it.cards.isNotEmpty() }.cards.single()
            assertEquals("broken", card.title)
            assertEquals(dev.slate.android.spec.Tone.NEUTRAL, card.tone)
            assertEquals(0, card.openQuestions)
        }
    }

    @Test
    fun `blank spec titles fall back to the slate id and expiry flags the card`() {
        runBlocking {
            cache.put(
                CachedSlate(
                    "old", """{"id":"old","version":2,"title":"","expiresAt":"1969-12-31T00:00:00Z","children":[]}""",
                    "h", "h", fetchedAt = 1L,
                ),
            )
            val card = repository.slatesUi.first { it.cards.isNotEmpty() }.cards.single()
            assertEquals("old", card.title)
            assertTrue("past expiresAt flags the card stale", card.expired)
        }
    }

    @Test
    fun `cards sort newest first and answered markers only count for their own slate`() {
        runBlocking {
            cache.put(
                CachedSlate("a", """{"id":"a","version":2,"title":"A","updatedAt":"2026-01-01T00:00:00Z","children":[
                    {"type":"question","id":"q1","prompt":"?","options":[{"id":"x","label":"X"},{"id":"y","label":"Y"}]}]}""",
                    "ha", "ha", fetchedAt = 1L,
                ),
            )
            cache.put(
                CachedSlate("b", """{"id":"b","version":2,"title":"B","updatedAt":"2026-02-01T00:00:00Z","children":[]}""",
                    "hb", "hb", fetchedAt = 2L),
            )
            // marker recorded for slate "b" must not reduce "a"'s open questions
            queue.markAnswered("b", AnsweredChoice("q1", optionId = "x", at = 1))

            val cards = repository.slatesUi.first { it.cards.size == 2 }.cards
            assertEquals(listOf("B", "A"), cards.map { it.title })
            assertEquals(1, cards.first { it.slateId == "a" }.openQuestions)
            assertEquals(0, cards.first { it.slateId == "b" }.openQuestions)
        }
    }

    // ---- detailUi branches ----

    @Test
    fun `detail for an unknown slate renders an empty state instead of failing`() {
        runBlocking {
            val detail = repository.detailUi("nope").first()
            assertNull(detail.spec)
            assertNull(detail.cached)
            assertEquals(0, detail.pendingForSlate)
        }
    }

    @Test
    fun `detail counts pending interactions for its slate only`() {
        runBlocking {
            cache.put(CachedSlate("home", """{"id":"home","version":2,"title":"T","children":[]}""", "h", "h", 1L))
            queue.enqueue(
                PendingInteraction("s1", "home", InteractionKind.check, elementId = "l", itemId = "i", clientAt = "", createdAt = 0),
            )
            queue.enqueue(
                PendingInteraction("s2", "other", InteractionKind.check, elementId = "l", itemId = "i", clientAt = "", createdAt = 0),
            )
            val detail = repository.detailUi("home").first { it.pendingForSlate == 1 }
            assertNotNull(detail.spec)
            assertEquals(1, detail.pendingForSlate)
        }
    }

    // ---- syncNow non-network failure branch ----

    @Test
    fun `undecodable server payload reports a non-offline error`() = runBlocking {
        responses["/api/device/slates"] = Triple(HttpStatusCode.OK, "this is not json", null)
        val state = repository.syncNow(SyncReason.MANUAL)
        assertTrue(state is SyncState.Error)
        assertTrue(!(state as SyncState.Error).offline)
    }

    @Test
    fun `unexpected client failures report non-offline errors and keep the cache`() = runBlocking {
        cache.put(CachedSlate("home", """{"id":"home","version":2,"title":"T","children":[]}""", "h", "h", 1L))
        val throwingApi = dev.slate.android.api.SlateApi(
            io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { _ ->
                throw IllegalStateException("broken http implementation")
            }),
        )
        val repo = SlateRepository(
            api = throwingApi,
            settings = settings,
            cacheStore = cache,
            queueStore = queue,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val state = repo.syncNow(SyncReason.MANUAL)
        assertTrue(state is SyncState.Error)
        assertTrue(!(state as SyncState.Error).offline)
        assertNotNull(cache.get("home"))
    }

    // ---- QueueStore no-op guards ----

    @Test
    fun `queue no-ops on empty enqueues and removals`() = runBlocking {
        queue.enqueueAll(emptyList())
        queue.removeAll(emptySet())
        assertTrue(queue.all().isEmpty())
        queue.enqueue(
            PendingInteraction("s1", "home", InteractionKind.tap, clientAt = "", createdAt = 0),
        )
        queue.removeAll(setOf("absent"))
        assertEquals(1, queue.all().size)
    }

    // ---- error envelope fallbacks ----

    @Test
    fun `exception without a parsable envelope keeps the http code`() {
        val e = dev.slate.android.api.SlateApiException.from(400, "plain garbage")
        assertEquals("http_400", e.code)
        assertEquals("plain garbage", e.message?.substringAfter("] "))
        val empty = dev.slate.android.api.SlateApiException.from(502, "")
        assertTrue(empty.message!!.contains("HTTP 502"))
    }
}
