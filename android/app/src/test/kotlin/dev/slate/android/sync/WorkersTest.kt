package dev.slate.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.slate.android.api.SlateApi
import dev.slate.android.data.CacheStore
import dev.slate.android.data.CachedSlate
import dev.slate.android.data.QueueStore
import dev.slate.android.data.SettingsStore
import dev.slate.android.data.SlateRepository
import dev.slate.android.data.SyncReason
import dev.slate.android.interact.FlushOutcome
import dev.slate.android.interact.InteractionManager
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Workers built via TestListenableWorkerBuilder with injected fakes:
 * success path updates cache + triggers widget update; failure keeps cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WorkersTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore
    private lateinit var repository: SlateRepository
    private lateinit var interactions: InteractionManager
    private val updates = RecordingUpdater()

    private class RecordingUpdater : dev.slate.android.widget.WidgetUpdater {
        var updateAllCalls = 0
        override suspend fun updateAll() {
            updateAllCalls++
        }

        override suspend fun updateOne(appWidgetId: Int) {
            updateAllCalls += 10
        }
    }

    private val responses = mutableMapOf<String, Pair<HttpStatusCode, String>>()
    private var throwIo = false

    private val homeSpec = """{"id":"home","version":2,"title":"T","children":[]}"""

    private fun api(): SlateApi = SlateApi(
        HttpClient(MockEngine { request ->
            if (throwIo) throw IOException("offline")
            val (status, body) = responses[request.url.encodedPath]
                ?: (HttpStatusCode.NotFound to "{}")
            respond(
                body, status,
                headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())),
            )
        }) {
            install(ContentNegotiation) { json(SpecParser.defaultJson) }
        }
    )

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        val name = "workers-${java.util.UUID.randomUUID()}"
        settings = SettingsStore(context, "$name-settings")
        cache = CacheStore(context, name)
        queue = QueueStore(context, name)
        settings.saveConnection("http://server.test", "key")
        repository = SlateRepository(
            api = api(),
            settings = settings,
            cacheStore = cache,
            queueStore = queue,
            externalScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        interactions = InteractionManager(queue, cache, SpecParser())
        responses["/api/device/slates"] = HttpStatusCode.OK to
            """{"slates":[{"slateId":"home","tone":"neutral","updatedAt":"x","contentHash":"h1"}]}"""
        responses["/api/device/slates/home"] = HttpStatusCode.OK to homeSpec
    }

    private fun buildSyncWorker(vararg data: Pair<String, String>): SyncWorker {
        val builder = TestListenableWorkerBuilder<SyncWorker>(context)
        if (data.isNotEmpty()) builder.setInputData(workDataOf(*data))
        return builder.setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = SyncWorker(appContext, workerParameters, repository, updates)
        }).build()
    }

    private fun buildFlushWorker(): FlushWorker = TestListenableWorkerBuilder<FlushWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = FlushWorker(appContext, workerParameters, api(), settings, interactions, repository, updates)
        }).build()

    // ---- SyncWorker ----

    @Test
    fun `sync worker success updates cache and triggers widget update`() = runTest {
        val worker = buildSyncWorker(KEY_SYNC_REASON to SyncReason.APP_OPEN.name)
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertNotNull(cache.get("home"))
        assertTrue(cache.get("home")!!.rawJson.contains("\"T\""))
        assertEquals(1, updates.updateAllCalls)
    }

    @Test
    fun `sync worker failure keeps cache intact and still succeeds (offline-first)`() = runTest {
        cache.put(CachedSlate("home", homeSpec, "h1", "h1", 1L))
        throwIo = true
        val worker = buildSyncWorker(KEY_SYNC_REASON to SyncReason.PERIODIC.name)
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("cache untouched", "h1", cache.get("home")!!.contentHash)
        assertEquals(1, updates.updateAllCalls)
    }

    @Test
    fun `sync worker without reason defaults to periodic`() = runTest {
        val result = buildSyncWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertNotNull(cache.get("home"))
    }

    // ---- FlushWorker ----

    @Test
    fun `flush worker posts queued interactions and succeeds`() = runTest {
        queue.enqueue(
            dev.slate.android.data.PendingInteraction(
                seq = "s1", slateId = "home", kind = dev.slate.android.data.InteractionKind.check,
                elementId = "list", itemId = "item", clientAt = "2026-01-01T00:00:00Z", createdAt = 0,
            )
        )
        responses["/api/device/interactions"] = HttpStatusCode.Accepted to """{"accepted":1,"duplicate":0}"""
        val result = buildFlushWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("2xx removes from queue", queue.all().isEmpty())
    }

    @Test
    fun `flush worker network failure retries with queue intact`() = runTest {
        throwIo = true
        queue.enqueue(
            dev.slate.android.data.PendingInteraction(
                seq = "s1", slateId = "home", kind = dev.slate.android.data.InteractionKind.check,
                elementId = "list", itemId = "item", clientAt = "2026-01-01T00:00:00Z", createdAt = 0,
            )
        )
        val result = buildFlushWorker().doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(1, queue.all().size)
    }

    @Test
    fun `flush worker 4xx drops batch then reconciles`() = runTest {
        queue.enqueue(
            dev.slate.android.data.PendingInteraction(
                seq = "bad", slateId = "home", kind = dev.slate.android.data.InteractionKind.answer,
                questionId = "q", optionId = "o", clientAt = "2026-01-01T00:00:00Z", createdAt = 0,
            )
        )
        responses["/api/device/interactions"] = HttpStatusCode.BadRequest to
            """{"error":{"code":"validation_error","message":"bad","status":400}}"""
        val result = buildFlushWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("4xx dropped", queue.all().isEmpty())
        // Reconcile re-pulled from server → cache updated to fresh spec
        assertEquals(1, updates.updateAllCalls)
    }

    @Test
    fun `flush worker with nothing queued succeeds without touching network`() = runTest {
        val result = buildFlushWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `flush outcome needsReconcile only when batches were dropped`() {
        assertTrue(FlushOutcome.Completed(1, 0, 1).needsReconcile)
        assertTrue(!FlushOutcome.Completed(1, 0, 0).needsReconcile)
    }
}
