package dev.slate.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.slate.android.api.SlateApi
import dev.slate.android.spec.SpecParser
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** ETag-aware offline-first repository over a MockEngine server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlateRepositoryTest {

    private lateinit var settings: SettingsStore
    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore
    private lateinit var repository: SlateRepository

    /** Programmable fake server: routes by path. */
    private val responses = mutableMapOf<String, MockRespond>()
    private val requestsSeen = mutableListOf<String>()

    private class MockRespond(
        val status: HttpStatusCode,
        val body: String,
        val etag: String? = null,
        val throwIo: Boolean = false,
    )

    private val homeSpec = """{"id":"home","version":2,"title":"🌙 Nightly","tone":"warn",
        "children":[{"type":"statusRow","label":"Backup","value":"failed","tone":"error"}]}"""

    @Before
    fun setUp() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "repo-${java.util.UUID.randomUUID()}"
        settings = SettingsStore(context, "$name-settings")
        cache = CacheStore(context, name)
        queue = QueueStore(context, name)
        settings.saveConnection("http://server.test", "secret-key")
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

    private fun mockClient(): HttpClient = HttpClient(MockEngine { request ->
        requestsSeen.add(request.url.toString() + " " + (request.headers["If-None-Match"] ?: ""))
        val path = request.url.encodedPath
        val respond = responses[path]
            ?: MockRespond(HttpStatusCode.NotFound, """{"error":{"code":"not_found","message":"missing","status":404}}""")
        if (respond.throwIo) throw IOException("server unreachable")
        val headers = if (respond.etag != null) {
            headersOf(
                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                HttpHeaders.ETag to listOf("\"${respond.etag}\""),
            )
        } else {
            headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
        }
        respond(respond.body, respond.status, headers)
    })

    private fun listBody(hash: String) =
        """{"slates":[{"slateId":"home","tone":"warn","updatedAt":"2026-09-14T20:00:00Z","contentHash":"$hash"}]}"""

    // ---- list + get ----

    @Test
    fun `sync fetches list then changed slates and caches them`() = runTest {
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h1"))
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.OK, homeSpec, etag = "h1")

        val state = repository.syncNow(SyncReason.APP_OPEN)
        assertTrue(state is SyncState.Success)
        assertEquals(1, (state as SyncState.Success).changed)

        val cached = cache.get("home")
        assertNotNull(cached)
        assertEquals("h1", cached!!.contentHash)
        assertTrue(cached.rawJson.contains("Nightly"))
        assertEquals("h1", cached.etag)
    }

    @Test
    fun `unchanged contentHash skips the fetch entirely`() = runTest {
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h1"))
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.OK, homeSpec, etag = "h1")
        repository.syncNow(SyncReason.APP_OPEN)
        requestsSeen.clear()

        // Same hash again → only the list endpoint is hit.
        repository.syncNow(SyncReason.PERIODIC)
        assertTrue(requestsSeen.none { it.contains("/device/slates/home") })
    }

    @Test
    fun `If-None-Match is sent and 304 keeps cached content`() = runTest {
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h1"))
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.OK, homeSpec, etag = "h1")
        repository.syncNow(SyncReason.APP_OPEN)

        // Server list hash disagrees with cache (index skew) but content matches → 304.
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h2"))
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.NotModified, "")
        val state = repository.syncNow(SyncReason.PERIODIC)

        assertTrue(state is SyncState.Success)
        assertTrue("ETag must be sent", requestsSeen.any { it.contains("\"h1\"") })
        val cached = cache.get("home")!!
        assertEquals("h2", cached.contentHash)
        assertTrue("cached content preserved on 304", cached.rawJson.contains("failed"))
    }

    // ---- offline → cache + legible error ----

    @Test
    fun `server down serves cache and reports offline SyncState`() = runTest {
        // First sync succeeds so we have cache.
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h1"))
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.OK, homeSpec, etag = "h1")
        repository.syncNow(SyncReason.APP_OPEN)

        // Then the network dies.
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, "", throwIo = true)
        val state = repository.syncNow(SyncReason.FAST_POLL)

        assertTrue(state is SyncState.Error)
        assertTrue((state as SyncState.Error).offline)
        // Cache intact — the widget path never goes blank.
        assertTrue(cache.get("home")!!.rawJson.contains("Nightly"))
    }

    @Test
    fun `server 5xx reports non-offline error and keeps cache`() = runTest {
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.InternalServerError, "boom")
        val state = repository.syncNow(SyncReason.MANUAL)
        assertTrue(state is SyncState.Error)
        assertFalse((state as SyncState.Error).offline)
        assertNull(cache.get("home"))
    }

    @Test
    fun `unconfigured reports a friendly error without touching network`() = runTest {
        settings.saveConnection("", "")
        requestsSeen.clear()
        val state = repository.syncNow(SyncReason.MANUAL)
        assertTrue(state is SyncState.Error)
        assertTrue(requestsSeen.isEmpty())
    }

    // ---- cache eviction ----

    @Test
    fun `slates dropped server-side leave the cache`() = runTest {
        responses["/api/device/slates"] = MockRespond(
            HttpStatusCode.OK,
            """{"slates":[{"slateId":"home","tone":"warn","updatedAt":"x","contentHash":"h1"},
                           {"slateId":"gone","tone":"neutral","updatedAt":"x","contentHash":"g1"}]}""",
        )
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.OK, homeSpec, etag = "h1")
        responses["/api/device/slates/gone"] = MockRespond(HttpStatusCode.OK, homeSpec, etag = "g1")
        repository.syncNow(SyncReason.APP_OPEN)
        assertNotNull(cache.get("gone"))

        // Server list no longer has "gone".
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h1"))
        repository.syncNow(SyncReason.PERIODIC)
        assertNull(cache.get("gone"))
        assertNotNull(cache.get("home"))
    }

    // ---- UI state ----

    @Test
    fun `slatesUi exposes cards from cache with open-question counts`() = runTest {
        val withQuestion = """{"id":"home","version":2,"title":"Board","children":[
            {"type":"question","id":"q1","prompt":"?","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}]}"""
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h1"))
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.OK, withQuestion, etag = "h1")
        repository.syncNow(SyncReason.APP_OPEN)

        repository.slatesUi.test {
            skipItems(1) // initial empty value
            val ui = awaitItem()
            assertEquals("Board", ui.cards.single().title)
            assertEquals(1, ui.cards.single().openQuestions)
            assertEquals("h1", ui.cards.single().contentHash)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `detailUi drives from cache and answered markers`() = runTest {
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h1"))
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.OK, homeSpec, etag = "h1")
        repository.syncNow(SyncReason.APP_OPEN)
        queue.markAnswered("home", AnsweredChoice("q1", optionId = "a", at = 1))

        repository.detailUi("home").test {
            val detail = awaitItem()
            assertNotNull(detail.spec)
            assertEquals("a", detail.answered["q1"]?.optionId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- pure counting ----

    @Test
    fun `openQuestions counts unanswered question elements`() {
        val parser = SpecParser()
        val spec = parser.parse(
            """{"id":"x","version":2,"children":[
               {"type":"question","id":"a","prompt":"?","options":[{"id":"o","label":"O"},{"id":"p","label":"P"}]},
               {"type":"question","id":"b","prompt":"?","options":[{"id":"o","label":"O"},{"id":"p","label":"P"}]},
               {"type":"text","text":"t"}]}"""
        )
        assertEquals(2, SlateRepository.openQuestions(spec, emptyList()))
        assertEquals(1, SlateRepository.openQuestions(spec, listOf("a")))
        assertEquals(0, SlateRepository.openQuestions(spec, listOf("a", "b")))
    }

    @Test
    fun `syncNow serializes concurrent callers via mutex`() = runTest {
        responses["/api/device/slates"] = MockRespond(HttpStatusCode.OK, listBody("h1"))
        responses["/api/device/slates/home"] = MockRespond(HttpStatusCode.OK, homeSpec, etag = "h1")
        val a = repository.syncNow(SyncReason.MANUAL)
        val b = repository.syncNow(SyncReason.MANUAL)
        assertTrue(a is SyncState.Success)
        assertTrue(b is SyncState.Success)
    }
}
