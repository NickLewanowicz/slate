package dev.slate.android.di

import android.content.Context
import dev.slate.android.api.SlateApi
import dev.slate.android.data.CacheStore
import dev.slate.android.data.QueueStore
import dev.slate.android.data.SettingsStore
import dev.slate.android.data.SlateRepository
import dev.slate.android.interact.InteractionManager
import dev.slate.android.spec.SpecParser
import dev.slate.android.sync.SyncScheduler
import dev.slate.android.widget.CacheWidgetUpdater
import dev.slate.android.widget.WidgetTapHandler
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph (no Hilt, per the binding decision). One instance
 * lives on SlateApplication; tests construct fresh containers.
 */
class AppContainer(appContext: Context) {

    val appContext: Context = appContext.applicationContext
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val specParser: SpecParser = SpecParser()

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val cacheStore: CacheStore by lazy { CacheStore(appContext) }
    val queueStore: QueueStore by lazy { QueueStore(appContext) }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(SpecParser.defaultJson) }
        }
    }
    val slateApi: SlateApi by lazy { SlateApi(httpClient) }

    val interactions: InteractionManager by lazy {
        InteractionManager(queueStore, cacheStore, specParser)
    }

    val repository: SlateRepository by lazy {
        SlateRepository(
            api = slateApi,
            settings = settingsStore,
            cacheStore = cacheStore,
            queueStore = queueStore,
            externalScope = applicationScope,
        )
    }

    val widgetUpdater: CacheWidgetUpdater by lazy {
        CacheWidgetUpdater(appContext, cacheStore, queueStore, specParser)
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }

    val widgetTapHandler: WidgetTapHandler by lazy {
        WidgetTapHandler(interactions, widgetUpdater) { syncScheduler.flushNow() }
    }
}

/** Access the container from any context (application must be SlateApplication). */
fun Context.appContainer(): AppContainer =
    (applicationContext as dev.slate.android.SlateApplication).container
