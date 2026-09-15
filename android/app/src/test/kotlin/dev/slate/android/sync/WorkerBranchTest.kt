package dev.slate.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.slate.android.api.SlateApi
import dev.slate.android.data.CacheStore
import dev.slate.android.data.CachedSlate
import dev.slate.android.data.PendingInteraction
import dev.slate.android.data.QueueStore
import dev.slate.android.data.SettingsStore
import dev.slate.android.data.SlateRepository
import dev.slate.android.data.SyncReason
import dev.slate.android.di.AppContainer
import dev.slate.android.interact.InteractionManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Worker branch coverage beyond WorkersTest's happy paths: 5xx retry,
 * unconfigured flush, unknown sync reasons, worker factory mapping, and the
 * widget provider's broadcast branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WorkerBranchTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore
    private lateinit var repository: SlateRepository
    private lateinit var interactions: InteractionManager

    private val responses = mutableMapOf<String, Pair<HttpStatusCode, String>>()
    private var throwIo = false
    private var throwRuntime = false

    private class RecordingUpdater : dev.slate.android.widget.WidgetUpdater {
        var updateAllCalls = 0
        override suspend fun updateAll() {
            updateAllCalls++
        }

        override suspend fun updateOne(appWidgetId: Int) {
            updateAllCalls += 10
        }
    }

    private val updates = RecordingUpdater()

    private fun api(): SlateApi = SlateApi(
        HttpClient(MockEngine { request ->
            if (throwIo) throw IOException("offline")
            if (throwRuntime) throw IllegalStateException("boom")
            val (status, body) = responses[request.url.encodedPath]
                ?: (HttpStatusCode.NotFound to "{}")
            respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }) {
            install(ContentNegotiation) { json(SpecParser.defaultJson) }
        }
    )

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        val name = "wbranch-${System.nanoTime()}"
        settings = SettingsStore(context, "$name-settings")
        cache = CacheStore(context, "$name-cache")
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

    private suspend fun enqueueOne() {
        queue.enqueue(
            PendingInteraction(
                seq = "s1", slateId = "home", kind = dev.slate.android.data.InteractionKind.check,
                elementId = "l", itemId = "i", clientAt = "2026-01-01T00:00:00Z", createdAt = 0,
            ),
        )
    }

    // ---- SyncWorker ----

    @Test
    fun `unknown sync reason falls back to periodic`() = runTest {
        responses["/api/device/slates"] = HttpStatusCode.OK to
            """{"slates":[{"slateId":"home","tone":"neutral","updatedAt":"x","contentHash":"h1"}]}"""
        responses["/api/device/slates/home"] = HttpStatusCode.OK to
            """{"id":"home","version":2,"title":"T","children":[]}"""
        val result = buildSyncWorker(KEY_SYNC_REASON to "NOT_A_REASON").doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertNotNull(cache.get("home"))
    }

    // ---- FlushWorker ----

    @Test
    fun `flush worker retries on 5xx with the queue intact`() = runTest {
        enqueueOne()
        responses["/api/device/interactions"] = HttpStatusCode.BadGateway to "gateway"
        val result = buildFlushWorker().doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(1, queue.all().size)
    }

    @Test
    fun `flush worker without configuration succeeds and keeps the queue`() = runTest {
        settings.saveConnection("", "") // disconnect
        enqueueOne()
        val result = buildFlushWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, queue.all().size)
    }

    @Test
    fun `flush worker rethrows non-IO unexpected failures`() = runTest {
        enqueueOne()
        throwRuntime = true
        var threw = false
        try {
            buildFlushWorker().doWork()
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ---- SlateWorkerFactory mapping ----

    @Test
    fun `worker factory builds sync and flush workers from the container`() {
        val container = AppContainer(context)
        val factory = SlateWorkerFactory(container)

        val sync = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(factory)
            .build()
        assertTrue(sync is SyncWorker)

        val flush = TestListenableWorkerBuilder<FlushWorker>(context)
            .setWorkerFactory(factory)
            .build()
        assertTrue(flush is FlushWorker)
    }

    // ---- widget provider / receiver branches ----

    @Test
    fun `provider ignores unrelated broadcasts without crashing`() {
        val manager = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>().let { ctx ->
            val m = android.appwidget.AppWidgetManager.getInstance(ctx)!!
            org.robolectric.Shadows.shadowOf(m).bindAppWidgetId(
                71,
                android.content.ComponentName(ctx, dev.slate.android.widget.SlateWidgetProvider::class.java),
            )
            m
        }
        dev.slate.android.widget.SlateWidgetProvider().onReceive(
            context,
            android.content.Intent("some.other.ACTION").setPackage(context.packageName),
        )
        // unrelated broadcast → no widget update
        assertEquals(null, org.robolectric.Shadows.shadowOf(manager).getViewFor(71))
    }

    @Test
    fun `click receiver ignores non-tap actions before touching the container`() {
        dev.slate.android.widget.SlateWidgetClickReceiver().onReceive(
            context,
            android.content.Intent("dev.slate.android.WIDGET_TAP_OTHER"),
        )
        // no crash = the early-return branch behaved
    }

    @Test
    fun `cache widget updater renders the waiting surface when nothing is cached`() = runTest {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)!!
        org.robolectric.Shadows.shadowOf(manager).bindAppWidgetId(
            81,
            android.content.ComponentName(context, dev.slate.android.widget.SlateWidgetProvider::class.java),
        )
        val updater = dev.slate.android.widget.CacheWidgetUpdater(context, cache, queue, SpecParser())
        updater.updateOne(81)
        assertNotNull(org.robolectric.Shadows.shadowOf(manager).getViewFor(81))

        cache.put(CachedSlate("home", """{"id":"home","version":2,"title":"T","children":[]}""", "h", "h", 1L))
        updater.updateAll()
        assertNotNull(org.robolectric.Shadows.shadowOf(manager).getViewFor(81))
    }
}
