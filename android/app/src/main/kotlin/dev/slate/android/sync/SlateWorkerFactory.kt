package dev.slate.android.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import dev.slate.android.di.AppContainer

/** Maps worker class names to workers wired with the AppContainer. */
class SlateWorkerFactory(private val container: AppContainer) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: androidx.work.WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name ->
            SyncWorker(appContext, workerParameters, container.repository, container.widgetUpdater)
        FlushWorker::class.java.name ->
            FlushWorker(
                appContext,
                workerParameters,
                container.slateApi,
                container.settingsStore,
                container.interactions,
                container.repository,
                container.widgetUpdater,
            )
        else -> null
    }
}
